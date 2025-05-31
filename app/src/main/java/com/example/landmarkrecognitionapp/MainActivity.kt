package com.example.landmarkrecognitionapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var landmarkClassifier: LandmarkClassifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landmarkClassifier = LandmarkClassifier(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LandmarkRecognitionApp(landmarkClassifier, cameraExecutor)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        landmarkClassifier.close()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LandmarkRecognitionApp(
    landmarkClassifier: LandmarkClassifier,
    cameraExecutor: ExecutorService
) {
    val permissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var detectedLandmarks by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 1) Permission screen
        if (!permissionState.status.isGranted) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Camera permission is required", fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionState.launchPermissionRequest() }) {
                    Text("Request permission")
                }
            }
            return@Column
        }

        // 2) If no photo yet → show camera preview
        if (capturedImage == null) {
            Box(Modifier.weight(1f)) {
                CameraPreview(
                    onImageCaptured = { bmp ->
                        capturedImage = bmp
                        detectedLandmarks = emptyList()
                    },
                    cameraExecutor = cameraExecutor
                )
                // capture button
//                Box(
//                    Modifier.fillMaxWidth().padding(bottom = 20.dp),
//                    contentAlignment = Alignment.BottomCenter
//                ) {
//                    IconButton(
//                        onClick = { /* handled inside CameraPreview */ },
//                        modifier = Modifier
//                            .size(80.dp)
//                            .background(Color.White.copy(alpha = 0.5f), CircleShape)
//                    ) {
//                        // empty— preview composable already has its own button
//                    }
//                }
            }
        } else {
            // 3) Show captured image
            Image(
                bitmap = capturedImage!!.asImageBitmap(),
                contentDescription = "Captured",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // 4) Predict button
            Button(
                onClick = {
                    scope.launch {
                        val labels = try {
                            withContext(Dispatchers.Default) {
                                landmarkClassifier.classify(capturedImage!!)
                            }
                        } catch (e: Exception) {
                            Log.e("Classifier", "error", e)
                            emptyList<String>()
                        }
                        detectedLandmarks = labels
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Predict Landmark")
            }

            // 5) Display results
            if (detectedLandmarks.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    Text("Detected:", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    detectedLandmarks.forEach {
                        Text(it, color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // 6) Retake button
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    capturedImage = null
                    detectedLandmarks = emptyList()
                },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Retake")
            }
        }
    }
}

@Composable
fun CameraPreview(
    onImageCaptured: (Bitmap) -> Unit,
    cameraExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    LaunchedEffect(previewView) {
        val provider = context.getCameraProvider()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // Single capture button
        Button(
            onClick = {
                imageCapture?.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val bmp = image.toBitmap()
                                onImageCaptured(bmp)
                            } catch (e: Exception) {
                                Log.e("Capture", "YUV→Bitmap failed", e)
                            } finally {
                                image.close()
                            }
                        }
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("CameraCapture", "failed", exc)
                        }
                    }
                )
            },
            modifier = Modifier
                .padding(bottom = 20.dp)
                .size(80.dp)
                .background(Color.White.copy(alpha = 0.5f), CircleShape),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) { /* Icon or empty */ }
    }
}

// Helper extensions unchanged from the “optimized” version:

private fun ImageProxy.toBitmap(): Bitmap {
    val y = planes[0].buffer; val u = planes[1].buffer; val v = planes[2].buffer
    val ySize = y.remaining(); val uSize = u.remaining(); val vSize = v.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    y.get(nv21, 0, ySize)
    v.get(nv21, ySize, vSize)
    u.get(nv21, ySize + vSize, uSize)

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream().also { yuv.compressToJpeg(Rect(0,0,width,height), 100, it) }
    val jpeg = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
}

private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
        }
    }


// This is a test change for Git 2

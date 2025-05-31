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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.layout.ContentScale
import androidx.camera.core.AspectRatio

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

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1) Permission screen
        if (!permissionState.status.isGranted) {
            Column(
                Modifier.fillMaxSize(),
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

        // Title
        Text(
            text = "Landmark Recognition",
            fontSize = 20.sp,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        // 2) Camera Preview or Captured Image in 1:1 Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f) // This creates 1:1 ratio
                .background(Color.Gray.copy(alpha = 0.3f))
        ) {
            if (capturedImage == null) {
                // Camera Preview
                CameraPreview(
                    onImageCaptured = { bmp ->
                        // Crop the bitmap to square before storing
                        capturedImage = cropToSquare(bmp)
                        detectedLandmarks = emptyList()
                    },
                    cameraExecutor = cameraExecutor,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Show captured image (already cropped to square)
                Image(
                    bitmap = capturedImage!!.asImageBitmap(),
                    contentDescription = "Captured",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 3) Buttons Section
        if (capturedImage != null) {
            // Predict button
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
                    .padding(vertical = 8.dp)
            ) {
                Text("Predict Landmark")
            }

            // Retake button
            Button(
                onClick = {
                    capturedImage = null
                    detectedLandmarks = emptyList()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Retake Photo")
            }
        }

        // 4) Results Section
        if (detectedLandmarks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f))
            ) {
                Column(
                    Modifier.padding(16.dp)
                ) {
                    Text("Detected Landmark:", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    detectedLandmarks.forEach { landmark ->
                        Text(landmark, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    onImageCaptured: (Bitmap) -> Unit,
    cameraExecutor: ExecutorService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    LaunchedEffect(previewView) {
        val provider = context.getCameraProvider()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        // Configure preview to maintain aspect ratio
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // This helps with cropping
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    }

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Capture button
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
                .padding(16.dp)
                .size(60.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {
            // You can add a camera icon here if needed
        }
    }
}

// Helper extensions unchanged from the “optimized” version:
private fun cropToSquare(bitmap: Bitmap): Bitmap {
    val dimension = minOf(bitmap.width, bitmap.height)
    val xOffset = (bitmap.width - dimension) / 2
    val yOffset = (bitmap.height - dimension) / 2

    return Bitmap.createBitmap(bitmap, xOffset, yOffset, dimension, dimension)
}

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

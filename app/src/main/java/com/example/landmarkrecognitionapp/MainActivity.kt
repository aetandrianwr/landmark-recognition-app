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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.layout.ContentScale
import androidx.camera.core.AspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import android.content.ContentValues

import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton



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
    var shouldCapture by remember { mutableStateOf(false) }
    var captureButtonEnabled by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Permission screen
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
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
        )

        // Main content
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Landmark Recognition",
                fontSize = 20.sp,
                color = Color.White,
                modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
            )

            // Spacer to center the camera preview vertically
            Spacer(modifier = Modifier.weight(1f))

            // 1:1 Camera Preview Box - Centered
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f) // 80% of screen width
                    .aspectRatio(1f) // 1:1 ratio
                    .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
            ) {
                if (capturedImage == null) {
                    // Camera Preview with square cropping overlay
                    CameraPreview(
                        onImageCaptured = { bmp ->
                            capturedImage = cropToSquare(bmp)
                            detectedLandmarks = emptyList()
                        },
                        cameraExecutor = cameraExecutor,
                        modifier = Modifier.fillMaxSize(),
                        shouldCapture = shouldCapture,
                        onCaptureProcessed = { shouldCapture = false }
                    )

                    // Square overlay to show crop area
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    )
                } else {
                    // Show captured square image
                    Image(
                        bitmap = capturedImage!!.asImageBitmap(),
                        contentDescription = "Captured",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Spacer to center the camera preview vertically
            Spacer(modifier = Modifier.weight(1f))

            // Results Section - Shows after prediction (between image and buttons)
            if (detectedLandmarks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.8f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Detected Landmark:",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        detectedLandmarks.forEach { landmark ->
                            Text(
                                landmark,
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Bottom section with buttons
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (capturedImage == null) {
                    // Shutter button - outside camera preview
                    Button(
                        onClick = {
                            Log.d("LandmarkApp", "Shutter button clicked")
                            if (captureButtonEnabled && !shouldCapture) {
                                shouldCapture = true
                                captureButtonEnabled = false
                                // Re-enable after delay
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    captureButtonEnabled = true
                                }
                            }
                        },
                        modifier = Modifier.size(80.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (captureButtonEnabled) Color.Red else Color.Gray,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                        enabled = captureButtonEnabled && !shouldCapture
                    ) {
                        // Camera icon or circle
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color.White, CircleShape)
                        )
                    }

                    // Debug text
                    Text(
                        "Capture: $shouldCapture, Enabled: $captureButtonEnabled",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    // Action buttons when image is captured
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Retake button
                        Button(
                            onClick = {
                                Log.d("LandmarkApp", "Retake button clicked")
                                capturedImage = null
                                detectedLandmarks = emptyList()
                                isProcessing = false
                                captureButtonEnabled = true
                                shouldCapture = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Gray,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retake")
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Predict button
                        Button(
                            onClick = {
                                Log.d("LandmarkApp", "Predict button clicked")
                                isProcessing = true
                                scope.launch {
                                    try {
                                        val labels = withContext(Dispatchers.Default) {
                                            Log.d("LandmarkApp", "Starting classification...")
                                            val result = landmarkClassifier.classify(capturedImage!!)
                                            Log.d("LandmarkApp", "Classification result: $result")
                                            result
                                        }

                                        // Ensure we always have some result
                                        detectedLandmarks = if (labels.isEmpty()) {
                                            listOf("No landmark detected")
                                        } else {
                                            labels
                                        }

                                        Log.d("LandmarkApp", "Final detected landmarks: $detectedLandmarks")
                                    } catch (e: Exception) {
                                        Log.e("Classifier", "Classification error", e)
                                        detectedLandmarks = listOf("Error: ${e.message}")
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isProcessing) Color.Gray else Color.Blue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            enabled = !isProcessing
                        ) {
                            Text(if (isProcessing) "Processing..." else "Predict")
                        }
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
    modifier: Modifier = Modifier,
    shouldCapture: Boolean = false,
    onCaptureProcessed: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    var isCameraReady by remember { mutableStateOf(false) }

    // Configure PreviewView to show square crop
    LaunchedEffect(previewView) {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    LaunchedEffect(previewView) {
        try {
            val provider = context.getCameraProvider()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageCaptureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)

            imageCapture = imageCaptureBuilder.build()

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)

            isCameraReady = true
            Log.d("CameraPreview", "Camera setup complete")
        } catch (e: Exception) {
            Log.e("CameraPreview", "Failed to setup camera", e)
        }
    }

    // Handle capture when triggered externally with better error handling
    LaunchedEffect(shouldCapture) {
        if (shouldCapture && isCameraReady && imageCapture != null) {
            Log.d("CameraPreview", "Attempting to capture image")
            try {
                imageCapture?.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            Log.d("CameraPreview", "Image captured successfully")
                            try {
                                val bmp = image.toBitmap()
                                val rotated = rotateBitmap(bmp, image.imageInfo.rotationDegrees)
                                onImageCaptured(rotated)
                            } catch (e: Exception) {
                                Log.e("Capture", "YUV→Bitmap conversion failed", e)
                            } finally {
                                image.close()
                            }
                        }
                        override fun onError(exc: ImageCaptureException) {
                            Log.e("CameraCapture", "Image capture failed", exc)
                        }
                    }
                )
            } finally {
                onCaptureProcessed() // Always reset the capture trigger
            }
        } else if (shouldCapture) {
            Log.w("CameraPreview", "Capture requested but camera not ready. Ready: $isCameraReady, ImageCapture: ${imageCapture != null}")
            onCaptureProcessed() // Reset trigger if camera not ready
        }
    }

    // Camera preview
    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
fun SaveImageDialog(
    onDismiss: () -> Unit,
    onSaveWithPrediction: () -> Unit,
    onSaveWithoutPrediction: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Save Image",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "Choose how you want to save the image:",
                fontSize = 14.sp
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Save with prediction button
                Button(
                    onClick = onSaveWithPrediction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Blue
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save with Prediction")
                }

                // Save without prediction button
                Button(
                    onClick = onSaveWithoutPrediction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Original Image")
                }

                // Cancel button
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        },
        dismissButton = null
    )
}

// Function to add prediction text to image
private fun addPredictionToImage(bitmap: Bitmap, prediction: String): Bitmap {
    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(mutableBitmap)

    // Configure text paint
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = bitmap.width * 0.04f // 4% of image width
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Configure background paint
    val backgroundPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        alpha = 180 // Semi-transparent
        style = android.graphics.Paint.Style.FILL
    }

    // Measure text
    val textBounds = android.graphics.Rect()
    textPaint.getTextBounds(prediction, 0, prediction.length, textBounds)

    // Calculate position (bottom-right corner with padding)
    val padding = bitmap.width * 0.02f // 2% padding
    val textWidth = textBounds.width()
    val textHeight = textBounds.height()

    val textX = bitmap.width - textWidth - padding
    val textY = bitmap.height - padding

    // Draw background rectangle
    val backgroundRect = android.graphics.RectF(
        textX - padding * 0.5f,
        textY - textHeight - padding * 0.5f,
        textX + textWidth + padding * 0.5f,
        textY + padding * 0.5f
    )

    canvas.drawRoundRect(backgroundRect, padding * 0.3f, padding * 0.3f, backgroundPaint)

    // Draw text
    canvas.drawText(prediction, textX, textY, textPaint)

    return mutableBitmap
}

// Function to save image to gallery
private suspend fun saveImageToGallery(context: Context, bitmap: Bitmap, filename: String) {
    withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${filename}_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LandmarkRecognition")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let { imageUri ->
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }

                // Show success message on main thread
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Image saved to Gallery", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_LONG).show()
            }
            Log.e("SaveImage", "Error saving image", e)
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

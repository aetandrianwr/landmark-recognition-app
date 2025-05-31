package com.example.landmarkrecognitionapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
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
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var landmarkClassifier: LandmarkClassifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landmarkClassifier = LandmarkClassifier(this)
        // Use cached thread pool for better performance
        cameraExecutor = Executors.newCachedThreadPool()

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
    var isProcessing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current



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

    // Save Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Image") },
            text = { Text("How would you like to save the image?") },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            showSaveDialog = false
                            scope.launch {
                                saveImageWithPrediction(context, capturedImage!!, detectedLandmarks)
                            }
                        }
                    ) {
                        Text("With Prediction")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            showSaveDialog = false
                            scope.launch {
                                saveImageWithoutPrediction(context, capturedImage!!)
                            }
                        }
                    ) {
                        Text("Without Prediction")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f))
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
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
            )

            // Spacer to center the camera preview vertically
            Spacer(modifier = Modifier.weight(1f))

            // 1:1 Camera Preview Box - Centered
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .background(androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
            ) {
                if (capturedImage == null) {
                    CameraPreview(
                        onImageCaptured = { bmp ->
                            capturedImage = bmp
                            detectedLandmarks = emptyList()
                            isProcessing = false
                        },
                        cameraExecutor = cameraExecutor,
                        modifier = Modifier.fillMaxSize(),
                        shouldCapture = shouldCapture,
                        onCaptureProcessed = { shouldCapture = false },
                        onCaptureStarted = { isProcessing = true }
                    )

                    // Square overlay to show crop area
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(2.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    )
                } else {
                    Image(
                        bitmap = capturedImage!!.asImageBitmap(),
                        contentDescription = "Captured",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Processing indicator
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
                    }
                }
            }

            // Spacer to center the camera preview vertically
            Spacer(modifier = Modifier.weight(1f))

            // Results Section
            if (detectedLandmarks.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Detected Landmark:",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        detectedLandmarks.forEach { landmark ->
                            Text(
                                landmark,
                                color = androidx.compose.ui.graphics.Color.White,
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
                    // Shutter button
                    Button(
                        onClick = {
                            if (!isProcessing) {
                                shouldCapture = true
                            }
                        },
                        modifier = Modifier
                            .size(80.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isProcessing) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color.Red,
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                        enabled = !isProcessing
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(androidx.compose.ui.graphics.Color.White, CircleShape)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                capturedImage = null
                                detectedLandmarks = emptyList()
                                isProcessing = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.ui.graphics.Color.Gray,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Retake")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    isProcessing = true
                                    val labels = try {
                                        withContext(Dispatchers.Default) {
                                            landmarkClassifier.classify(capturedImage!!)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Classifier", "Classification error", e)
                                        emptyList<String>()
                                    } finally {
                                        isProcessing = false
                                    }
                                    detectedLandmarks = labels
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isProcessing) androidx.compose.ui.graphics.Color.Gray else androidx.compose.ui.graphics.Color.Blue,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.weight(1f),
                            enabled = !isProcessing
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Predict")
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (detectedLandmarks.isNotEmpty()) {
                                    showSaveDialog = true
                                } else {
                                    scope.launch {
                                        saveImageWithoutPrediction(context, capturedImage!!)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.ui.graphics.Color.Green,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save")
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
    onCaptureProcessed: () -> Unit = {},
    onCaptureStarted: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // Configure PreviewView
    LaunchedEffect(previewView) {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    LaunchedEffect(previewView) {
        val provider = context.getCameraProvider()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Optimized ImageCapture configuration
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setJpegQuality(85) // Reduce quality for faster processing
            .build()

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    }

    // Optimized capture handling
    LaunchedEffect(shouldCapture) {
        if (shouldCapture && imageCapture != null) {
            onCaptureStarted()

            imageCapture?.takePicture(
                cameraExecutor, // Use background executor
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bmp = image.toBitmapOptimized()
                            val rotated = rotateBitmapOptimized(bmp, image.imageInfo.rotationDegrees)
                            val cropped = cropToSquareOptimized(rotated)

                            // Switch back to main thread for UI update
                            ContextCompat.getMainExecutor(context).execute {
                                onImageCaptured(cropped)
                            }
                        } catch (e: Exception) {
                            Log.e("Capture", "Image processing failed", e)
                            ContextCompat.getMainExecutor(context).execute {
                                onCaptureProcessed()
                            }
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exc: ImageCaptureException) {
                        Log.e("CameraCapture", "Capture failed", exc)
                        ContextCompat.getMainExecutor(context).execute {
                            onCaptureProcessed()
                        }
                    }
                }
            )
            onCaptureProcessed()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

// Save Functions
suspend fun saveImageWithPrediction(context: Context, bitmap: Bitmap, predictions: List<String>) {
    withContext(Dispatchers.IO) {
        try {
            val imageWithPrediction = addPredictionOverlay(bitmap, predictions)
            val fileName = "landmark_with_prediction_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            saveImageToGallery(context, imageWithPrediction, fileName)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Image saved with prediction!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("SaveImage", "Failed to save image with prediction", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

suspend fun saveImageWithoutPrediction(context: Context, bitmap: Bitmap) {
    withContext(Dispatchers.IO) {
        try {
            val fileName = "landmark_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            saveImageToGallery(context, bitmap, fileName)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Image saved!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("SaveImage", "Failed to save image", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun addPredictionOverlay(bitmap: Bitmap, predictions: List<String>): Bitmap {
    val overlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(overlayBitmap)

    // Configure text paint
    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = bitmap.width * 0.04f // Scale text size based on image width
        isAntiAlias = true
        style = Paint.Style.FILL
        setShadowLayer(4f, 2f, 2f, Color.BLACK) // Add shadow for better visibility
    }

    // Configure background paint
    val backgroundPaint = Paint().apply {
        color = Color.BLACK
        alpha = 180 // Semi-transparent background
        style = Paint.Style.FILL
    }

    if (predictions.isNotEmpty()) {
        val predictionText = predictions.joinToString("\n")
        val textBounds = Rect()
        textPaint.getTextBounds(predictionText, 0, predictionText.length, textBounds)

        val padding = 20f
        val backgroundWidth = textBounds.width() + padding * 2
        val backgroundHeight = textBounds.height() + padding * 2

        // Position in bottom-right corner
        val x = bitmap.width - backgroundWidth - 20f
        val y = bitmap.height - backgroundHeight - 20f

        // Draw background
        canvas.drawRect(x, y, x + backgroundWidth, y + backgroundHeight, backgroundPaint)

        // Draw text
        val lines = predictionText.split("\n")
        var textY = y + padding + textBounds.height()

        for (line in lines) {
            canvas.drawText(line, x + padding, textY, textPaint)
            textY += textPaint.textSize + 5f
        }
    }

    return overlayBitmap
}

private fun saveImageToGallery(context: Context, bitmap: Bitmap, fileName: String) {
    val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "LandmarkRecognition")
    if (!picturesDir.exists()) {
        picturesDir.mkdirs()
    }

    val imageFile = File(picturesDir, fileName)
    FileOutputStream(imageFile).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }

    // Notify media scanner to make the image visible in gallery
    android.media.MediaScannerConnection.scanFile(
        context,
        arrayOf(imageFile.absolutePath),
        arrayOf("image/jpeg"),
        null
    )
}

// Optimized helper functions
private fun cropToSquareOptimized(bitmap: Bitmap): Bitmap {
    val dimension = minOf(bitmap.width, bitmap.height)
    val xOffset = (bitmap.width - dimension) / 2
    val yOffset = (bitmap.height - dimension) / 2

    return Bitmap.createBitmap(bitmap, xOffset, yOffset, dimension, dimension)
}

private fun ImageProxy.toBitmapOptimized(): Bitmap {
    return when (format) {
        ImageFormat.JPEG -> {
            // Direct JPEG decoding - fastest path
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        ImageFormat.YUV_420_888 -> {
            // Optimized YUV conversion with pre-allocated arrays
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            // Copy Y plane
            yBuffer.get(nv21, 0, ySize)

            // Interleave U and V for NV21 format
            val uvPixelStride = planes[1].pixelStride
            if (uvPixelStride == 1) {
                // Packed format
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)
            } else {
                // Semi-planar format - need to interleave manually
                val uvBuffer = ByteArray(uSize + vSize)
                vBuffer.get(uvBuffer, 0, vSize)
                uBuffer.get(uvBuffer, vSize, uSize)
                System.arraycopy(uvBuffer, 0, nv21, ySize, uvBuffer.size)
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }
        else -> {
            // Fallback for other formats
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}

private fun rotateBitmapOptimized(bitmap: Bitmap, rotation: Int): Bitmap {
    if (rotation == 0) return bitmap

    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
        }
    }
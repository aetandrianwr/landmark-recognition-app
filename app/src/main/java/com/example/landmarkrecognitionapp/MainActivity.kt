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
import android.location.Address
import android.location.Geocoder
import android.location.Location
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
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landmarkClassifier = LandmarkClassifier(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Use cached thread pool for better performance
        cameraExecutor = Executors.newCachedThreadPool()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LandmarkRecognitionApp(landmarkClassifier, cameraExecutor, fusedLocationClient)
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
    cameraExecutor: ExecutorService,
    fusedLocationClient: FusedLocationProviderClient
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var detectedLandmarks by remember { mutableStateOf<List<String>>(emptyList()) }
    var shouldCapture by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<String?>(null) }
    var isLoadingLocation by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Check if all required permissions are granted
    val allPermissionsGranted = permissionsState.allPermissionsGranted

    // Permission screen
    if (!allPermissionsGranted) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Camera and Location permissions are required",
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(16.dp))

            // Show which permissions are missing
            val missingPermissions = permissionsState.permissions.filter { !it.status.isGranted }
            missingPermissions.forEach { permission ->
                val permissionName = when (permission.permission) {
                    android.Manifest.permission.CAMERA -> "Camera"
                    android.Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
                    android.Manifest.permission.ACCESS_COARSE_LOCATION -> "Coarse Location"
                    else -> "Unknown"
                }
                Text(
                    "• $permissionName permission needed",
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color.Gray
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text("Request permissions")
            }
        }
        return
    }

    // Save Dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Image") },
            text = { Text("Do you want to save the image with the prediction result?") },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            showSaveDialog = false
                            scope.launch {
                                saveImageWithPrediction(context, capturedImage!!, detectedLandmarks, currentLocation)
                            }
                        }
                    ) {
                        Text("Yes")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            showSaveDialog = false
                            scope.launch {
                                saveImageWithoutPrediction(context, capturedImage!!, currentLocation)
                            }
                        }
                    ) {
                        Text("No")
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
                            currentLocation = null
                            locationError = null

                            // Start location retrieval after image is captured
                            scope.launch {
                                isLoadingLocation = true
                                try {
                                    val location = getCurrentLocation(context, fusedLocationClient)
                                    currentLocation = location
                                    locationError = null
                                } catch (e: Exception) {
                                    locationError = "Failed to get location"
                                    Log.e("Location", "Failed to get location", e)
                                } finally {
                                    isLoadingLocation = false
                                }
                            }
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

            // Location Section
            if (capturedImage != null) {
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
                            "Location:",
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))

                        when {
                            isLoadingLocation -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = androidx.compose.ui.graphics.Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Fetching location...",
                                        color = androidx.compose.ui.graphics.Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            locationError != null -> {
                                Text(
                                    locationError!!,
                                    color = androidx.compose.ui.graphics.Color.Red,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                            currentLocation != null -> {
                                Text(
                                    currentLocation!!,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

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
                                currentLocation = null
                                isLoadingLocation = false
                                locationError = null
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
                                        saveImageWithoutPrediction(context, capturedImage!!, currentLocation)
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

// Location Functions
suspend fun getCurrentLocation(context: Context, fusedLocationClient: FusedLocationProviderClient): String {
    return withContext(Dispatchers.IO) {
        try {
            // Check for location permissions
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Location permissions not granted")
            }

            // Get current location with timeout
            val location = withTimeoutOrNull(10000L) { // 10 second timeout
                val cancellationTokenSource = CancellationTokenSource()
                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).await()
            } ?: throw Exception("Location request timed out")

            // Convert coordinates to address
            getAddressFromLocation(context, location.latitude, location.longitude)

        } catch (e: Exception) {
            Log.e("Location", "Error getting location", e)
            throw e
        }
    }
}

private fun getAddressFromLocation(context: Context, latitude: Double, longitude: Double): String {
    return try {
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses: List<Address> = geocoder.getFromLocation(latitude, longitude, 1) ?: emptyList()

        if (addresses.isNotEmpty()) {
            val address = addresses[0]
            buildString {
                // Add venue name if available
                address.featureName?.let { if (it.isNotBlank()) append("at $it, ") }

                // Add sub-locality (district)
                address.subLocality?.let { if (it.isNotBlank()) append("$it, ") }

                // Add locality (city)
                address.locality?.let { if (it.isNotBlank()) append("$it, ") }

                // Add admin area (state/province)
                address.adminArea?.let { if (it.isNotBlank()) append(it) }

                // If nothing specific found, use the address line
                if (this.isEmpty()) {
                    address.getAddressLine(0)?.let { append(it) }
                }

                // Fallback to coordinates
                if (this.isEmpty()) {
                    append("${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}")
                }
            }.toString().removeSuffix(", ")
        } else {
            "Location: ${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}"
        }
    } catch (e: Exception) {
        Log.e("Geocoding", "Error converting coordinates to address", e)
        "Location: ${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}"
    }
}

// Updated Save Functions with Location
suspend fun saveImageWithPrediction(context: Context, bitmap: Bitmap, predictions: List<String>, location: String?) {
    withContext(Dispatchers.IO) {
        try {
            val imageWithPrediction = addPredictionOverlay(bitmap, predictions, location)
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

suspend fun saveImageWithoutPrediction(context: Context, bitmap: Bitmap, location: String?) {
    withContext(Dispatchers.IO) {
        try {
            val imageWithLocation = if (location != null) {
                addLocationOverlay(bitmap, location)
            } else {
                bitmap
            }
            val fileName = "landmark_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
            saveImageToGallery(context, imageWithLocation, fileName)

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

private fun addPredictionOverlay(bitmap: Bitmap, predictions: List<String>, location: String?): Bitmap {
    val overlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(overlayBitmap)

    // Configure text paint
    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = bitmap.width * 0.02f // Scale text size based on image width
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

    // Combine predictions and location
    val overlayText = buildString {
        if (predictions.isNotEmpty()) {
            append("Landmark: ${predictions.joinToString(", ")}")
        }
        if (location != null) {
            if (isNotEmpty()) append("\n")
            append("Location: $location")
        }
    }




    if (overlayText.isNotEmpty()) {
        val textBounds = Rect()
        textPaint.getTextBounds(overlayText, 0, overlayText.length, textBounds)

        val padding = 20f
        val lines = overlayText.split("\n")
        val maxLineWidth = lines.maxOfOrNull { line ->
            val bounds = Rect()
            textPaint.getTextBounds(line, 0, line.length, bounds)
            bounds.width()
        } ?: 0

        val backgroundWidth = maxLineWidth + padding * 2
        val backgroundHeight = (textPaint.textSize + 5f) * lines.size + padding * 2

        // Position in bottom-right corner
        val x = 20f
        val y = bitmap.height - backgroundHeight - 20f

        // Draw background
        canvas.drawRect(x, y, x + backgroundWidth, y + backgroundHeight, backgroundPaint)

        // Draw text
        var textY = y + padding + textPaint.textSize
        for (line in lines) {
            canvas.drawText(line, x + padding, textY, textPaint)
            textY += textPaint.textSize + 5f
        }
    }

    return overlayBitmap
}

private fun addLocationOverlay(bitmap: Bitmap, location: String): Bitmap {
    val overlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(overlayBitmap)

    // Configure text paint
    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = bitmap.width * 0.02f // Scale text size based on image width
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

    val overlayText = "Location: $location"
    val textBounds = Rect()
    textPaint.getTextBounds(overlayText, 0, overlayText.length, textBounds)

    val padding = 20f
    val backgroundWidth = textBounds.width() + padding * 2
    val backgroundHeight = textBounds.height() + padding * 2

    // Position in bottom-right corner
    val x = 20f
    val y = bitmap.height - backgroundHeight - 20f

    // Draw background
    canvas.drawRect(x, y, x + backgroundWidth, y + backgroundHeight, backgroundPaint)

    // Draw text
    canvas.drawText(overlayText, x + padding, y + padding + textBounds.height(), textPaint)

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
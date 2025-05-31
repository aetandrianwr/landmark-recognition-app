// LandmarkClassifier.kt
package com.example.landmarkrecognitionapp

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.Classifications
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.IOException

class LandmarkClassifier(private val context: Context) {
    private var classifier: ImageClassifier? = null

    init {
        setupClassifier()
    }

    private fun setupClassifier() {
        val baseOptions = BaseOptions.builder()
            .setNumThreads(4)
            .build()

        val options = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(1)
            .setScoreThreshold(0.5f)
            .build()

        try {
            classifier = ImageClassifier.createFromFileAndOptions(
                context,
                "landmark_europe_v1.tflite",
                options
            )
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun classify(image: Bitmap): List<String> {
        val clf = classifier ?: return emptyList()

        // Preprocess to model’s 321×321 input size
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(321, 321, ResizeOp.ResizeMethod.BILINEAR))
            .build()
//        val imageProcessor = ImageProcessor.Builder()
//            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
//            .build()
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))
        val results = clf.classify(tensorImage)

        return parseResults(results)
    }

    private fun parseResults(results: List<Classifications>?): List<String> {
        if (results.isNullOrEmpty() || results[0].categories.isEmpty()) {
            return listOf("No landmark detected")
        }
        return results[0].categories.map { cat ->
            "${cat.displayName} (${String.format("%.1f", cat.score * 100)}%)"
        }
    }

    fun close() {
        classifier?.close()
        classifier = null
    }
}

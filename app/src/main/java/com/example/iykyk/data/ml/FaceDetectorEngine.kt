package com.example.iykyk.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.iykyk.domain.model.FaceDetectionResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class FaceDetectorEngine {

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.10f) // Ignore tiny background noise faces
            .build()
        detector = FaceDetection.getClient(options)
    }

    /**
     * Runs ML Kit Face Detection synchronously on the given frame bitmap.
     */
    suspend fun detectFaces(
        frameBitmap: Bitmap,
        frameTimestampMs: Long
    ): List<FaceDetectionResult> = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(frameBitmap, 0)
        val task = detector.process(inputImage)

        val faces: List<Face> = try {
            Tasks.await(task)
        } catch (e: Exception) {
            emptyList()
        }

        val frameWidth = frameBitmap.width
        val frameHeight = frameBitmap.height

        faces.mapNotNull { face ->
            val box = face.boundingBox
            // Ensure bounding box is clamped to bitmap bounds
            val left = box.left.coerceIn(0, frameWidth - 1)
            val top = box.top.coerceIn(0, frameHeight - 1)
            val right = box.right.coerceIn(left + 1, frameWidth)
            val bottom = box.bottom.coerceIn(top + 1, frameHeight)

            val width = right - left
            val height = bottom - top

            // Skip invalid or ultra-tiny face boxes
            if (width < 20 || height < 20) return@mapNotNull null

            val clampedBox = Rect(left, top, right, bottom)
            val sharpness = calculateLaplacianSharpness(frameBitmap, clampedBox)

            FaceDetectionResult(
                id = UUID.randomUUID().toString(),
                frameTimestampMs = frameTimestampMs,
                boundingBox = clampedBox,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                eulerX = face.headEulerAngleX,
                eulerY = face.headEulerAngleY,
                eulerZ = face.headEulerAngleZ,
                leftEyeOpenProb = face.leftEyeOpenProbability,
                rightEyeOpenProb = face.rightEyeOpenProbability,
                smileProb = face.smilingProbability,
                sharpnessScore = sharpness,
                frameBitmap = frameBitmap
            )
        }
    }

    /**
     * Calculates the variance of the Laplacian filter on the face region.
     * High variance = sharp edges and fine details. Low variance = blurry / out-of-focus.
     */
    fun calculateLaplacianSharpness(bitmap: Bitmap, box: Rect): Float {
        val width = box.width()
        val height = box.height()
        if (width < 3 || height < 3) return 0f

        // Sample up to 128x128 for efficient sharpness computation
        val sampleScale = min(1.0f, 128f / max(width, height))
        val sampleW = (width * sampleScale).toInt().coerceAtLeast(3)
        val sampleH = (height * sampleScale).toInt().coerceAtLeast(3)

        val crop = try {
            val rawCrop = Bitmap.createBitmap(bitmap, box.left, box.top, width, height)
            if (sampleScale < 1.0f) {
                val scaled = Bitmap.createScaledBitmap(rawCrop, sampleW, sampleH, true)
                rawCrop.recycle()
                scaled
            } else {
                rawCrop
            }
        } catch (e: Exception) {
            return 0f
        }

        val pixels = IntArray(sampleW * sampleH)
        crop.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        crop.recycle()

        // Grayscale conversion
        val gray = FloatArray(sampleW * sampleH)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Apply 3x3 Laplacian operator: [0, 1, 0; 1, -4, 1; 0, 1, 0]
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until sampleH - 1) {
            val rowOffset = y * sampleW
            for (x in 1 until sampleW - 1) {
                val center = gray[rowOffset + x]
                val top = gray[(y - 1) * sampleW + x]
                val bottom = gray[(y + 1) * sampleW + x]
                val left = gray[rowOffset + x - 1]
                val right = gray[rowOffset + x + 1]

                val laplacian = (top + bottom + left + right - 4f * center).toDouble()
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return variance.toFloat().coerceAtLeast(0f)
    }

    fun close() {
        detector.close()
    }
}

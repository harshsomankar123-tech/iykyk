package com.example.iykyk.data.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.example.iykyk.domain.model.FaceDetectionResult
import com.example.iykyk.domain.util.BoxGeometry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class FaceDetectorEngine {

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.08f) // High sensitivity for real video faces
            .build()
        detector = FaceDetection.getClient(options)
    }

    /**
     * Runs ML Kit Face Detection asynchronously using coroutine-native await().
     */
    suspend fun detectFaces(
        frameBitmap: Bitmap,
        frameTimestampMs: Long
    ): List<FaceDetectionResult> = withContext(Dispatchers.Default) {
        val inputImage = InputImage.fromBitmap(frameBitmap, 0)

        val faces: List<Face> = try {
            detector.process(inputImage).await()
        } catch (e: Exception) {
            emptyList()
        }

        val frameWidth = frameBitmap.width
        val frameHeight = frameBitmap.height

        val detections = faces.mapNotNull { face ->
            val box = face.boundingBox
            // Ensure bounding box is clamped to bitmap bounds
            val left = box.left.coerceIn(0, frameWidth - 1)
            val top = box.top.coerceIn(0, frameHeight - 1)
            val right = box.right.coerceIn(left + 1, frameWidth)
            val bottom = box.bottom.coerceIn(top + 1, frameHeight)

            val width = right - left
            val height = bottom - top

            // Skip invalid or ultra-tiny face boxes
            if (width < 25 || height < 25) return@mapNotNull null

            // Human face aspect ratio validation: genuine faces are between 0.50 and 1.50 aspect
            // Accepts people with glasses, headsets, and head coverings while rejecting phantom seam bars
            val aspect = width.toFloat() / height.toFloat()
            if (aspect < 0.50f || aspect > 1.50f) return@mapNotNull null

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
                portraitCrop = null // Will be cropped and assigned during streaming
            )
        }

        // Apply Non-Maximum Suppression to remove duplicate bounding boxes on the same face
        performNms(detections, iouThreshold = 0.25f)
    }

    private fun performNms(detections: List<FaceDetectionResult>, iouThreshold: Float): List<FaceDetectionResult> {
        if (detections.size <= 1) return detections
        val sorted = detections.sortedByDescending { it.sharpnessScore }
        val result = mutableListOf<FaceDetectionResult>()
        for (det in sorted) {
            val hasOverlap = result.any { BoxGeometry.iou(it.boundingBox, det.boundingBox) > iouThreshold }
            if (!hasOverlap) {
                result.add(det)
            }
        }
        // Discard only a genuine phantom box that is WIDER than its neighbours and straddles a real
        // face on both sides (split-screen seam artifact). A real middle person in a group is about
        // the same width as their neighbours, so the width guard prevents dropping an actual face.
        if (result.size >= 3) {
            val nonBridging = result.filterNot { candidate ->
                val candBox = candidate.boundingBox
                val hasFaceToLeft = result.any { other -> other != candidate && other.boundingBox.centerX() < candBox.left && candBox.width() > other.boundingBox.width() && BoxGeometry.iou(other.boundingBox, candBox) > 0.05f }
                val hasFaceToRight = result.any { other -> other != candidate && other.boundingBox.centerX() > candBox.right && candBox.width() > other.boundingBox.width() && BoxGeometry.iou(other.boundingBox, candBox) > 0.05f }
                hasFaceToLeft && hasFaceToRight
            }
            return if (nonBridging.isNotEmpty()) nonBridging else result
        }

        return result
    }

    /**
     * Calculates the statistical variance of the 3x3 Laplacian filter on the face region.
     * High variance = sharp edges & in-focus details. Low variance = blurry motion / out-of-focus.
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

        // Grayscale conversion: Y = 0.299R + 0.587G + 0.114B
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
        try {
            detector.close()
        } catch (ignored: Exception) {}
    }
}

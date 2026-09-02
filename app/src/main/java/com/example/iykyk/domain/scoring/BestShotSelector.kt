package com.example.iykyk.domain.scoring

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.iykyk.domain.model.FaceDetectionResult
import kotlin.math.max
import kotlin.math.min

class BestShotSelector(
    val weightSharpness: Float = 0.35f,
    val weightFrontality: Float = 0.25f,
    val weightEyesOpen: Float = 0.25f,
    val weightSmile: Float = 0.15f,
    val penaltyClipped: Float = 0.35f
) {

    /**
     * Evaluates and assigns the composite quality score for a face detection.
     */
    fun evaluateQualityScore(detection: FaceDetectionResult): Float {
        // Blurry face gate: severely penalize blurry shots so sharp faces are always preferred
        if (detection.sharpnessScore < 35f) {
            val blurScore = (detection.sharpnessScore / 1000f).coerceIn(0f, 0.05f)
            detection.qualityScore = blurScore
            return blurScore
        }

        // 1. Sharpness normalized via soft saturation: variance / (variance + 120.0) -> [0, 1]
        val sSharp = detection.sharpnessScore / (detection.sharpnessScore + 120.0f)

        // 2. Frontality score
        val sFront = detection.frontalityScore

        // 3. Eyes open score
        val sEyes = detection.eyesOpenScore

        // 4. Smile score
        val sSmile = detection.smileProb ?: 0.3f

        // 5. Boundary clip penalty
        val clipPenalty = if (detection.isTouchingBoundary) penaltyClipped else 0f

        val composite = (
                weightSharpness * sSharp +
                weightFrontality * sFront +
                weightEyesOpen * sEyes +
                weightSmile * sSmile -
                clipPenalty
        ).coerceIn(0.0f, 1.0f)

        detection.qualityScore = composite
        return composite
    }

    /**
     * Selects the single best representative detection from a candidate list.
     */
    fun selectBestDetection(candidates: List<FaceDetectionResult>): FaceDetectionResult? {
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { evaluateQualityScore(it) }
    }

    /**
     * Crops a generous, high-aesthetic portrait bust shot centered on the face.
     * Avoids tight face cutouts by expanding vertically for chest/head and horizontally for hair/shoulders.
     * @param targetAspectRatio Width / Height ratio (e.g. 4/5 = 0.8f or 1.0f for square)
     */
    fun createGenerousPortraitCrop(
        frameBitmap: Bitmap,
        faceBox: Rect,
        otherFaceBoxes: List<Rect> = emptyList(),
        targetAspectRatio: Float = 0.80f, // 4:5 portrait ratio
        expansionRatio: Float = 0.20f     // 20% clean margin
    ): Bitmap {
        val fw = frameBitmap.width
        val fh = frameBitmap.height

        val faceW = max(1, faceBox.right - faceBox.left)
        val faceH = max(1, faceBox.bottom - faceBox.top)
        val centerX = (faceBox.left + faceBox.right) / 2
        val centerY = (faceBox.top + faceBox.bottom) / 2

        val baseDim = max(faceW, faceH) * (1.0f + expansionRatio)

        val cropHeight = baseDim / targetAspectRatio
        val cropWidth = baseDim

        // Shift center slightly downward for neck/hair balance
        val adjustedCenterY = centerY + (faceH * 0.10f).toInt()

        var left = (centerX - cropWidth / 2f).toInt()
        var top = (adjustedCenterY - cropHeight / 2f).toInt()
        var right = (centerX + cropWidth / 2f).toInt()
        var bottom = (adjustedCenterY + cropHeight / 2f).toInt()

        // Split-screen seam constraint: if multiple faces are present, never cross the center line
        if (otherFaceBoxes.size >= 2) {
            val halfW = fw / 2
            if (centerX < halfW) {
                right = min(right, halfW)
            } else {
                left = max(left, halfW)
            }
        }

        // Boundary constraint: never overlap into another person's face in multi-person scenes
        for (other in otherFaceBoxes) {
            if (other == faceBox) continue
            val otherCenterX = (other.left + other.right) / 2
            if (otherCenterX < centerX) {
                val midX = (other.right + faceBox.left) / 2
                left = max(left, midX)
            } else if (otherCenterX > centerX) {
                val midX = (faceBox.right + other.left) / 2
                right = min(right, midX)
            }
        }

        // Clamp to bitmap boundaries
        left = left.coerceIn(0, fw - 1)
        top = top.coerceIn(0, fh - 1)
        right = right.coerceIn(left + 1, fw)
        bottom = bottom.coerceIn(top + 1, fh)

        val finalW = max(1, right - left)
        val finalH = max(1, bottom - top)

        return Bitmap.createBitmap(frameBitmap, left, top, finalW, finalH)
    }
}

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
        // 1. Sharpness normalized via soft saturation: variance / (variance + 60.0) -> [0, 1]
        val sSharp = if (detection.sharpnessScore > 0f) {
            detection.sharpnessScore / (detection.sharpnessScore + 60.0f)
        } else {
            0.1f
        }

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
        targetAspectRatio: Float = 0.80f, // 4:5 portrait ratio
        expansionRatio: Float = 0.60f     // 60% extra margin for bust/hair
    ): Bitmap {
        val fw = frameBitmap.width
        val fh = frameBitmap.height

        val faceW = faceBox.width()
        val faceH = faceBox.height()
        val centerX = faceBox.centerX()
        val centerY = faceBox.centerY()

        // Generously expanded base size
        val baseDim = max(faceW, faceH) * (1.0f + expansionRatio)

        var cropHeight = baseDim / targetAspectRatio
        var cropWidth = baseDim

        // Shift center slightly downward to capture hair on top and neck/shoulders below
        val adjustedCenterY = centerY + (faceH * 0.15f).toInt()

        var left = (centerX - cropWidth / 2f).toInt()
        var top = (adjustedCenterY - cropHeight / 2f).toInt()
        var right = (centerX + cropWidth / 2f).toInt()
        var bottom = (adjustedCenterY + cropHeight / 2f).toInt()

        // Clamp to bitmap boundaries while maintaining aspect ratio
        if (left < 0) {
            right += -left
            left = 0
        }
        if (top < 0) {
            bottom += -top
            top = 0
        }
        if (right > fw) {
            val overflow = right - fw
            left = max(0, left - overflow)
            right = fw
        }
        if (bottom > fh) {
            val overflow = bottom - fh
            top = max(0, top - overflow)
            bottom = fh
        }

        val finalW = max(1, right - left)
        val finalH = max(1, bottom - top)

        return Bitmap.createBitmap(frameBitmap, left, top, finalW, finalH)
    }
}

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
        // If qualityScore was already populated (with solo-shot priority bonus), respect it; otherwise evaluate
        return candidates.maxByOrNull {
            if (it.qualityScore > 0f) it.qualityScore else evaluateQualityScore(it)
        }
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
        expansionRatio: Float = 0.85f
    ): Bitmap {
        val fw = frameBitmap.width
        val fh = frameBitmap.height
        val halfW = fw / 2

        val centerX = (faceBox.left + faceBox.right) / 2
        val centerY = (faceBox.top + faceBox.bottom) / 2

        // Determine if this is a split-screen tile (actor positioned on one half of the screen)
        val isSplitScreen = otherFaceBoxes.size >= 2 || centerX < (fw * 0.40f) || centerX > (fw * 0.60f)

        val cropLeft: Int
        val cropRight: Int
        val cropTop: Int
        val cropBottom: Int

        if (isSplitScreen) {
            // Split-screen: use the complete natural half-frame tile!
            if (centerX < halfW) {
                cropLeft = 0
                cropRight = halfW
            } else {
                cropLeft = halfW
                cropRight = fw
            }
            val tileW = cropRight - cropLeft
            val desiredH = (tileW / targetAspectRatio).toInt().coerceAtMost(fh)

            // Center vertically around the face with natural headroom above the hair
            val targetCenterY = (centerY + (desiredH * 0.12f).toInt()).coerceIn(desiredH / 2, fh - desiredH / 2)
            cropTop = (targetCenterY - desiredH / 2).coerceIn(0, fh - desiredH)
            cropBottom = (cropTop + desiredH).coerceAtMost(fh)
        } else {
            // Solo shot: use full natural camera framing with upper body and headroom
            val desiredW = fw
            val desiredH = (desiredW / targetAspectRatio).toInt().coerceAtMost(fh)

            cropLeft = 0
            cropRight = fw

            // Place face naturally in the upper half with full hair and shoulders
            val targetCenterY = (centerY + (desiredH * 0.10f).toInt()).coerceIn(desiredH / 2, fh - desiredH / 2)
            cropTop = (targetCenterY - desiredH / 2).coerceIn(0, fh - desiredH)
            cropBottom = (cropTop + desiredH).coerceAtMost(fh)
        }

        val finalW = max(1, cropRight - cropLeft)
        val finalH = max(1, cropBottom - cropTop)

        return Bitmap.createBitmap(frameBitmap, cropLeft, cropTop, finalW, finalH)
    }
}

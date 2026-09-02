package com.example.iykyk

import android.graphics.Rect
import com.example.iykyk.domain.model.FaceDetectionResult
import com.example.iykyk.domain.scoring.BestShotSelector
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class BestShotSelectorTest {

    private val selector = BestShotSelector()

    @Test
    fun testFrontalityScoring() {
        // Perfectly frontal face (0 Euler angles)
        val frontal = FaceDetectionResult(
            id = "1",
            frameTimestampMs = 1000L,
            boundingBox = Rect(100, 100, 300, 300),
            frameWidth = 1080,
            frameHeight = 1920,
            eulerX = 0f,
            eulerY = 0f,
            eulerZ = 0f
        )
        assertThat(frontal.frontalityScore).isWithin(1e-5f).of(1.0f)

        // Tilted / Turned face (yaw = 45 deg) -> 1.0 - (45/90) = 0.5
        val turned = FaceDetectionResult(
            id = "2",
            frameTimestampMs = 1000L,
            boundingBox = Rect(100, 100, 300, 300),
            frameWidth = 1080,
            frameHeight = 1920,
            eulerX = 0f,
            eulerY = 45f,
            eulerZ = 0f
        )
        assertThat(turned.frontalityScore).isWithin(1e-5f).of(0.5f)
    }

    @Test
    fun testBoundaryClippingPenalty() {
        // Normal non-clipped face
        val nonClipped = FaceDetectionResult(
            id = "1",
            frameTimestampMs = 1000L,
            boundingBox = Rect(200, 200, 400, 400),
            frameWidth = 1080,
            frameHeight = 1920,
            sharpnessScore = 200f,
            smileProb = 0.9f,
            leftEyeOpenProb = 0.95f,
            rightEyeOpenProb = 0.95f
        )

        // Clipped face touching boundary
        val clipped = FaceDetectionResult(
            id = "2",
            frameTimestampMs = 1000L,
            boundingBox = Rect(2, 200, 400, 400), // left <= margin
            frameWidth = 1080,
            frameHeight = 1920,
            sharpnessScore = 200f,
            smileProb = 0.9f,
            leftEyeOpenProb = 0.95f,
            rightEyeOpenProb = 0.95f
        )

        val scoreNonClipped = selector.evaluateQualityScore(nonClipped)
        val scoreClipped = selector.evaluateQualityScore(clipped)

        assertThat(scoreNonClipped).isGreaterThan(scoreClipped)
    }

    @Test
    fun testSelectBestDetectionChoosesOptimalFrame() {
        val blurryFace = FaceDetectionResult(
            id = "blurry",
            frameTimestampMs = 1000L,
            boundingBox = Rect(200, 200, 400, 400),
            frameWidth = 1080,
            frameHeight = 1920,
            sharpnessScore = 10f,
            smileProb = 0.2f,
            leftEyeOpenProb = 0.3f,
            rightEyeOpenProb = 0.3f
        )

        val perfectFace = FaceDetectionResult(
            id = "perfect",
            frameTimestampMs = 2000L,
            boundingBox = Rect(200, 200, 400, 400),
            frameWidth = 1080,
            frameHeight = 1920,
            sharpnessScore = 450f,
            smileProb = 0.98f,
            leftEyeOpenProb = 0.99f,
            rightEyeOpenProb = 0.99f,
            eulerX = 0f,
            eulerY = 0f,
            eulerZ = 0f
        )

        val best = selector.selectBestDetection(listOf(blurryFace, perfectFace))
        assertThat(best?.id).isEqualTo("perfect")
    }
}

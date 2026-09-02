package com.example.iykyk.data.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.iykyk.data.ml.FaceEmbeddingEngine
import com.example.iykyk.domain.model.FaceDetectionResult
import java.util.UUID

object SampleVideoGenerator {

    /**
     * Generates a synthetic dataset modeling "Sample 1" benchmark:
     * - 5 distinct individuals (Person 1 through Person 5)
     * - 4 appearances per person = 20 total appearances
     * - Key co-occurrences at 10.1–11.5s and 20.2–21.6s
     * - Variation in eye open prob, smile prob, head pose angles, and sharpness across frames
     */
    fun createSample1SyntheticData(): List<FaceDetectionResult> {
        val detections = mutableListOf<FaceDetectionResult>()

        // 5 distinct base feature fingerprints (192-d vectors, distinct angles)
        val personIdentities = (1..5).map { personId ->
            val vec = FloatArray(192)
            // Distinct frequency pattern for each identity
            for (i in 0 until 192) {
                vec[i] = kotlin.math.sin((i + 1) * (personId * 1.57f)).toFloat()
            }
            FaceEmbeddingEngine.l2Normalize(vec)
        }

        // Appearance windows for Sample 1 (in milliseconds)
        // Each person appears in 4 separate time intervals:
        // Person 1: [1000..3000], [7000..9000], [10100..11500] (co-occurrence), [15000..17000]
        // Person 2: [2000..4000], [8000..10000], [10100..11500] (co-occurrence), [18000..20000]
        // Person 3: [3500..5500], [12000..14000], [20200..21600] (co-occurrence), [24000..26000]
        // Person 4: [5000..7000], [13000..15000], [20200..21600] (co-occurrence), [25000..27000]
        // Person 5: [0..2000], [9000..11000], [16000..18000], [22000..24000]

        val appearanceSchedule = listOf(
            // Person 1
            listOf(1000L..3000L, 7000L..9000L, 10100L..11500L, 15000L..17000L),
            // Person 2
            listOf(2000L..4000L, 8000L..10000L, 10100L..11500L, 18000L..20000L),
            // Person 3
            listOf(3500L..5500L, 12000L..14000L, 20200L..21600L, 24000L..26000L),
            // Person 4
            listOf(5000L..7000L, 13000L..15000L, 20200L..21600L, 25000L..27000L),
            // Person 5
            listOf(0L..2000L, 9000L..11000L, 16000L..18000L, 22000L..24000L)
        )

        val frameIntervalMs = 250L // 4 FPS

        for ((pIdx, intervals) in appearanceSchedule.withIndex()) {
            val baseEmbedding = personIdentities[pIdx]
            val personColor = when (pIdx) {
                0 -> Color.rgb(60, 60, 60)
                1 -> Color.rgb(80, 80, 80)
                2 -> Color.rgb(100, 100, 100)
                3 -> Color.rgb(120, 120, 120)
                else -> Color.rgb(140, 140, 140)
            }

            for (interval in intervals) {
                var currentMs = interval.first
                var frameInSegment = 0

                while (currentMs <= interval.last) {
                    frameInSegment++
                    // Perturb embedding slightly (natural intra-class variation, distance < 0.15)
                    val sampleEmbedding = FloatArray(192) { idx ->
                        baseEmbedding[idx] + ((kotlin.math.sin(currentMs.toDouble() + idx) * 0.05).toFloat())
                    }
                    FaceEmbeddingEngine.l2Normalize(sampleEmbedding)

                    // Make the middle frame of 3rd appearance the optimal best shot (smiling, eyes open, front facing, sharp)
                    val isPeakShot = (frameInSegment == 3)
                    val eulerX = if (isPeakShot) 1.5f else (5.0f + (pIdx * 3.0f))
                    val eulerY = if (isPeakShot) 2.0f else (-8.0f + (pIdx * 4.0f))
                    val eulerZ = 0.5f
                    val eyeOpen = if (isPeakShot) 0.98f else 0.85f
                    val smile = if (isPeakShot) 0.95f else 0.40f
                    val sharpness = if (isPeakShot) 320.0f else 110.0f

                    val syntheticBmp = createSyntheticFaceBitmap(pIdx + 1, personColor, isPeakShot)

                    detections.add(
                        FaceDetectionResult(
                            id = UUID.randomUUID().toString(),
                            frameTimestampMs = currentMs,
                            boundingBox = Rect(120, 180, 360, 480),
                            frameWidth = 480,
                            frameHeight = 720,
                            eulerX = eulerX,
                            eulerY = eulerY,
                            eulerZ = eulerZ,
                            leftEyeOpenProb = eyeOpen,
                            rightEyeOpenProb = eyeOpen,
                            smileProb = smile,
                            sharpnessScore = sharpness,
                            frameBitmap = syntheticBmp,
                            embedding = sampleEmbedding
                        )
                    )

                    currentMs += frameIntervalMs
                }
            }
        }

        return detections.sortedBy { it.frameTimestampMs }
    }

    /**
     * Renders a clean illustrative avatar bitmap for synthetic person testing.
     */
    fun createSyntheticFaceBitmap(personNum: Int, accentColor: Int, isSmiling: Boolean): Bitmap {
        val width = 480
        val height = 720
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Background
        val bgPaint = Paint().apply { color = Color.rgb(18, 18, 18) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Silhouette Bust
        val bustPaint = Paint().apply {
            color = accentColor
            isAntiAlias = true
        }
        canvas.drawCircle(width / 2f, 320f, 130f, bustPaint)
        canvas.drawRoundRect(width / 2f - 160f, 440f, width / 2f + 160f, 720f, 40f, 40f, bustPaint)

        // Face text / badge
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            isAntiAlias = true
        }
        canvas.drawText("Person #$personNum", width / 2f, 335f, textPaint)

        if (isSmiling) {
            val smilePaint = Paint().apply {
                color = Color.rgb(220, 220, 220)
                textSize = 26f
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText("Best Shot", width / 2f, 270f, smilePaint)
        }

        return bmp
    }
}

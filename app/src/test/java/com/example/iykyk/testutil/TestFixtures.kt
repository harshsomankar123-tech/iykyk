package com.example.iykyk.testutil

import android.graphics.Rect
import com.example.iykyk.data.ml.FaceEmbeddingEngine
import com.example.iykyk.domain.model.FaceDetectionResult
import java.util.UUID

object TestFixtures {

    fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    /**
     * Synthetic test fixture for unit-testing the HAC clustering algorithm and appearance counter math.
     * Simulates 5 distinct mathematical clusters across 4 separate temporal segments (with >2.0s breaks).
     */
    fun createSyntheticClusteringTestData(): List<FaceDetectionResult> {
        val detections = mutableListOf<FaceDetectionResult>()

        // 5 distinct orthogonal base fingerprints
        val personIdentities = (0 until 5).map { personId ->
            val vec = FloatArray(192)
            vec[personId * 35] = 1.0f
            vec[personId * 35 + 1] = 0.5f
            FaceEmbeddingEngine.l2Normalize(vec)
        }

        // Each schedule has 4 distinct time intervals separated by > 2000ms gaps (> 1200ms break threshold)
        val appearanceSchedule = listOf(
            // Person 1: [1..3s], [6..8s], [11..13s], [16..18s]
            listOf(1000L..3000L, 6000L..8000L, 11000L..13000L, 16000L..18000L),
            // Person 2: [2..4s], [7..9s], [12..14s], [17..19s]
            listOf(2000L..4000L, 7000L..9000L, 12000L..14000L, 17000L..19000L),
            // Person 3: [3..5s], [8..10s], [13..15s], [20..22s]
            listOf(3000L..5000L, 8000L..10000L, 13000L..15000L, 20000L..22000L),
            // Person 4: [4..6s], [9..11s], [14..16s], [21..23s]
            listOf(4000L..6000L, 9000L..11000L, 14000L..16000L, 21000L..23000L),
            // Person 5: [0..2s], [10..12s], [15..17s], [22..24s]
            listOf(0L..2000L, 10000L..12000L, 15000L..17000L, 22000L..24000L)
        )

        val frameIntervalMs = 250L

        for ((pIdx, intervals) in appearanceSchedule.withIndex()) {
            val baseEmbedding = personIdentities[pIdx]

            for (interval in intervals) {
                var currentMs = interval.first

                while (currentMs <= interval.last) {
                    val sampleEmbedding = FloatArray(192) { idx ->
                        baseEmbedding[idx] + ((kotlin.math.sin(currentMs.toDouble() + idx) * 0.01).toFloat())
                    }
                    FaceEmbeddingEngine.l2Normalize(sampleEmbedding)

                    detections.add(
                        FaceDetectionResult(
                            id = UUID.randomUUID().toString(),
                            frameTimestampMs = currentMs,
                            boundingBox = createRect(100, 100, 300, 300),
                            frameWidth = 1080,
                            frameHeight = 1920,
                            eulerX = 1f,
                            eulerY = 2f,
                            eulerZ = 0f,
                            leftEyeOpenProb = 0.95f,
                            rightEyeOpenProb = 0.95f,
                            smileProb = 0.90f,
                            sharpnessScore = 200f,
                            portraitCrop = null,
                            embedding = sampleEmbedding
                        )
                    )

                    currentMs += frameIntervalMs
                }
            }
        }

        return detections.sortedBy { it.frameTimestampMs }
    }
}

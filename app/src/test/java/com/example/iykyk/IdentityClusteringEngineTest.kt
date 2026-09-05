package com.example.iykyk

import android.graphics.Rect
import com.example.iykyk.data.ml.FaceEmbeddingEngine
import com.example.iykyk.domain.clustering.IdentityClusteringEngine
import com.example.iykyk.domain.model.FaceDetectionResult
import com.example.iykyk.domain.util.BoxGeometry
import com.example.iykyk.testutil.TestFixtures
import com.example.iykyk.testutil.TestFixtures.createRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class IdentityClusteringEngineTest {

    private val clusteringEngine = IdentityClusteringEngine(
        maxCosineDistanceThreshold = 0.48f,
        appearanceBreakGapMs = 1200L
    )

    @Test
    fun testCosineSimilarityAndDistance() {
        val vecA = floatArrayOf(1f, 0f, 0f)
        val vecB = floatArrayOf(1f, 0f, 0f)
        val vecC = floatArrayOf(0f, 1f, 0f)

        // Identical vectors
        assertThat(FaceEmbeddingEngine.cosineSimilarity(vecA, vecB)).isWithin(1e-5f).of(1.0f)
        assertThat(FaceEmbeddingEngine.cosineDistance(vecA, vecB)).isWithin(1e-5f).of(0.0f)

        // Orthogonal vectors
        assertThat(FaceEmbeddingEngine.cosineSimilarity(vecA, vecC)).isWithin(1e-5f).of(0.0f)
        assertThat(FaceEmbeddingEngine.cosineDistance(vecA, vecC)).isWithin(1e-5f).of(1.0f)
    }

    @Test
    fun testCalculateIoU() {
        val boxA = createRect(0, 0, 100, 100)
        val boxB = createRect(0, 0, 100, 100)
        val boxC = createRect(50, 0, 150, 100)
        val boxD = createRect(200, 200, 300, 300)

        // 100% overlap
        assertThat(BoxGeometry.iou(boxA, boxB)).isWithin(1e-5f).of(1.0f)

        // 50% width overlap (Area A=10000, Area C=10000, Inter=5000, Union=15000 -> 0.333)
        assertThat(BoxGeometry.iou(boxA, boxC)).isWithin(1e-2f).of(0.333f)

        // 0% overlap
        assertThat(BoxGeometry.iou(boxA, boxD)).isWithin(1e-5f).of(0.0f)
    }

    @Test
    fun testAppearanceSegmentationWithGaps() {
        // Continuous detections: [1000ms, 1250ms, 1500ms], then GAP, then [4000ms, 4250ms]
        val detections = listOf(
            createMockDetection(1000L),
            createMockDetection(1250L),
            createMockDetection(1500L),
            createMockDetection(4000L), // gap = 2500ms > 1200ms
            createMockDetection(4250L)
        )

        val segments = clusteringEngine.computeAppearanceSegments(detections)

        assertThat(segments).hasSize(2)
        assertThat(segments[0].startMs).isEqualTo(1000L)
        assertThat(segments[0].endMs).isEqualTo(1500L)
        assertThat(segments[0].detectionCount).isEqualTo(3)

        assertThat(segments[1].startMs).isEqualTo(4000L)
        assertThat(segments[1].endMs).isEqualTo(4250L)
        assertThat(segments[1].detectionCount).isEqualTo(2)
    }

    @Test
    fun testHierarchicalClusteringMathWithSyntheticFixtures() {
        val testData = TestFixtures.createSyntheticClusteringTestData()

        val clusters = clusteringEngine.clusterFaces(testData)

        // Validates mathematical grouping separates 5 synthetic clusters
        assertThat(clusters).hasSize(5)

        // Validates temporal appearance segmentation groups 4 segments per cluster
        for (cluster in clusters) {
            val segments = clusteringEngine.computeAppearanceSegments(cluster.detections)
            assertThat(segments).hasSize(4)
        }

        val totalAppearances = clusters.sumOf {
            clusteringEngine.computeAppearanceSegments(it.detections).size
        }
        assertThat(totalAppearances).isEqualTo(20)
    }

    private fun createMockDetection(timestampMs: Long): FaceDetectionResult {
        return FaceDetectionResult(
            id = UUID.randomUUID().toString(),
            frameTimestampMs = timestampMs,
            boundingBox = createRect(10, 10, 100, 100),
            frameWidth = 1080,
            frameHeight = 1920
        )
    }
}

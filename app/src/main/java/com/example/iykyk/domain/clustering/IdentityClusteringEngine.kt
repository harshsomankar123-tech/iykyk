package com.example.iykyk.domain.clustering

import android.graphics.Rect
import com.example.iykyk.data.ml.FaceEmbeddingEngine
import com.example.iykyk.domain.model.AppearanceSegment
import com.example.iykyk.domain.model.FaceDetectionResult
import kotlin.math.max
import kotlin.math.min

/**
 * Cluster containing all face detections belonging to a single unique identity.
 */
data class PersonCluster(
    val id: Int,
    val detections: MutableList<FaceDetectionResult> = mutableListOf(),
    var centroidEmbedding: FloatArray? = null
) {
    /**
     * Recomputes the L2-normalized centroid embedding vector of the cluster.
     */
    fun updateCentroid() {
        val validEmbeddings = detections.mapNotNull { it.embedding }
        if (validEmbeddings.isEmpty()) return

        val dim = validEmbeddings[0].size
        val sumVec = FloatArray(dim)
        for (emb in validEmbeddings) {
            for (i in 0 until dim) {
                sumVec[i] += emb[i]
            }
        }
        centroidEmbedding = FaceEmbeddingEngine.l2Normalize(sumVec)
    }
}

class IdentityClusteringEngine(
    val maxCosineDistanceThreshold: Float = 0.35f, // D_cos <= 0.35 (Similarity >= 0.65)
    val appearanceBreakGapMs: Long = 1200L,        // 1.2s break threshold for distinct appearances
    val minDetectionsPerPerson: Int = 1            // Discard single spurious noise detections if needed
) {

    /**
     * Performs two-tier tracking & clustering:
     * Tier 1: Local frame-to-frame spatial + feature tracking into continuous tracklets.
     * Tier 2: Global Hierarchical Agglomerative / Centroid Clustering across the full video.
     */
    fun clusterFaces(allDetections: List<FaceDetectionResult>): List<PersonCluster> {
        if (allDetections.isEmpty()) return emptyList()

        // 1. Sort detections chronologically by timestamp
        val sortedDetections = allDetections.sortedBy { it.frameTimestampMs }

        // 2. Global Centroid / Leader-Follower Clustering with Average-Linkage Update
        val clusters = mutableListOf<PersonCluster>()
        var clusterIdCounter = 1

        for (detection in sortedDetections) {
            val emb = detection.embedding
            if (emb == null) continue

            var bestCluster: PersonCluster? = null
            var minDistance = Float.MAX_VALUE

            for (cluster in clusters) {
                val centroid = cluster.centroidEmbedding ?: continue
                val dist = FaceEmbeddingEngine.cosineDistance(emb, centroid)
                if (dist < minDistance && dist <= maxCosineDistanceThreshold) {
                    minDistance = dist
                    bestCluster = cluster
                }
            }

            if (bestCluster != null) {
                bestCluster.detections.add(detection)
                bestCluster.updateCentroid()
            } else {
                val newCluster = PersonCluster(
                    id = clusterIdCounter++,
                    detections = mutableListOf(detection)
                )
                newCluster.updateCentroid()
                clusters.add(newCluster)
            }
        }

        // 3. Hierarchical Agglomerative Merge step for any clusters closer than threshold
        mergeCloseClusters(clusters)

        return clusters.filter { it.detections.size >= minDetectionsPerPerson }
    }

    /**
     * Agglomerative merge step: Recursively merges any two clusters whose centroids
     * have a cosine distance <= maxCosineDistanceThreshold.
     */
    private fun mergeCloseClusters(clusters: MutableList<PersonCluster>) {
        var merged = true
        while (merged) {
            merged = false
            var mergeI = -1
            var mergeJ = -1
            var minPairDist = Float.MAX_VALUE

            for (i in 0 until clusters.size) {
                val c1 = clusters[i].centroidEmbedding ?: continue
                for (j in i + 1 until clusters.size) {
                    val c2 = clusters[j].centroidEmbedding ?: continue
                    val dist = FaceEmbeddingEngine.cosineDistance(c1, c2)
                    if (dist <= maxCosineDistanceThreshold && dist < minPairDist) {
                        minPairDist = dist
                        mergeI = i
                        mergeJ = j
                    }
                }
            }

            if (mergeI != -1 && mergeJ != -1) {
                val clusterA = clusters[mergeI]
                val clusterB = clusters[mergeJ]
                clusterA.detections.addAll(clusterB.detections)
                clusterA.updateCentroid()
                clusters.removeAt(mergeJ)
                merged = true
            }
        }
    }

    /**
     * Computes the continuous appearance segments for a given person.
     * An appearance segment ends whenever the person is absent/occluded for > appearanceBreakGapMs.
     */
    fun computeAppearanceSegments(detections: List<FaceDetectionResult>): List<AppearanceSegment> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedBy { it.frameTimestampMs }
        val segments = mutableListOf<AppearanceSegment>()

        var currentStart = sorted[0].frameTimestampMs
        var currentEnd = sorted[0].frameTimestampMs
        var currentCount = 1

        for (i in 1 until sorted.size) {
            val ts = sorted[i].frameTimestampMs
            val gap = ts - currentEnd

            if (gap > appearanceBreakGapMs) {
                // Gap exceeded threshold -> complete current appearance segment
                segments.add(
                    AppearanceSegment(
                        startMs = currentStart,
                        endMs = currentEnd,
                        detectionCount = currentCount
                    )
                )
                // Start a new segment
                currentStart = ts
                currentEnd = ts
                currentCount = 1
            } else {
                // Extend current segment
                currentEnd = ts
                currentCount++
            }
        }

        // Add final open segment
        segments.add(
            AppearanceSegment(
                startMs = currentStart,
                endMs = currentEnd,
                detectionCount = currentCount
            )
        )

        return segments
    }

    companion object {
        /**
         * Calculates Intersection-over-Union (IoU) between two bounding boxes.
         */
        fun calculateIoU(a: Rect, b: Rect): Float {
            val interLeft = max(a.left, b.left)
            val interTop = max(a.top, b.top)
            val interRight = min(a.right, b.right)
            val interBottom = min(a.bottom, b.bottom)

            val interWidth = max(0, interRight - interLeft)
            val interHeight = max(0, interBottom - interTop)
            val interArea = interWidth * interHeight

            val areaA = max(0, a.right - a.left) * max(0, a.bottom - a.top)
            val areaB = max(0, b.right - b.left) * max(0, b.bottom - b.top)
            val unionArea = areaA + areaB - interArea

            if (unionArea <= 0) return 0f
            return interArea.toFloat() / unionArea.toFloat()
        }
    }
}

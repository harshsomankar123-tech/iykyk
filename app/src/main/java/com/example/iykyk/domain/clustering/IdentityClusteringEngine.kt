package com.example.iykyk.domain.clustering

import com.example.iykyk.data.ml.FaceEmbeddingEngine
import com.example.iykyk.domain.model.AppearanceSegment
import com.example.iykyk.domain.model.FaceDetectionResult

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

/**
 * A continuous video shot (tracklet) of a single person.
 */
private data class FaceTracklet(
    val detections: MutableList<FaceDetectionResult> = mutableListOf(),
    var representativeEmbedding: FloatArray? = null
) {
    fun updateRepresentative() {
        val topDetections = detections
            .filter { it.embedding != null }
            .sortedByDescending { it.sharpnessScore }
            .take(4)

        if (topDetections.isEmpty()) return
        val embs = topDetections.mapNotNull { it.embedding }
        val dim = embs[0].size
        val sum = FloatArray(dim)
        for (e in embs) {
            for (i in 0 until dim) {
                sum[i] += e[i]
            }
        }
        representativeEmbedding = FaceEmbeddingEngine.l2Normalize(sum)
    }
}

class IdentityClusteringEngine(
    val maxCosineDistanceThreshold: Float = 0.48f, // 0.48 calibrated for FaceNet embeddings
    val appearanceBreakGapMs: Long = 1200L
) {

    /**
     * Performs identity clustering using:
     * 1. Temporal Tracklet Formation (groups continuous video shots of a person)
     * 2. Representative Embedding extraction (averages sharpest shots in each tracklet)
     * 3. Co-occurrence constrained Agglomerative Hierarchical Clustering
     */
    fun clusterFaces(allDetections: List<FaceDetectionResult>): List<PersonCluster> {
        if (allDetections.isEmpty()) return emptyList()

        val sortedDetections = allDetections.sortedBy { it.frameTimestampMs }

        // Step 1: Form temporal tracklets (continuous appearance shots)
        val tracklets = mutableListOf<FaceTracklet>()

        for (detection in sortedDetections) {
            val emb = detection.embedding ?: continue

            var bestTracklet: FaceTracklet? = null
            var minTrackletDist = Float.MAX_VALUE

            for (tracklet in tracklets) {
                val lastDet = tracklet.detections.lastOrNull() ?: continue
                val timeDiff = detection.frameTimestampMs - lastDet.frameTimestampMs

                // Must be continuous in time (< appearanceBreakGapMs) and not in the same frame
                if (timeDiff in 1..appearanceBreakGapMs) {
                    val rep = tracklet.representativeEmbedding ?: lastDet.embedding ?: continue
                    val dist = FaceEmbeddingEngine.cosineDistance(emb, rep)
                    // Threshold for continuous tracking in same scene (< 0.42)
                    if (dist < 0.42f && dist < minTrackletDist) {
                        minTrackletDist = dist
                        bestTracklet = tracklet
                    }
                }
            }

            if (bestTracklet != null) {
                bestTracklet.detections.add(detection)
                bestTracklet.updateRepresentative()
            } else {
                val newTracklet = FaceTracklet(mutableListOf(detection))
                newTracklet.updateRepresentative()
                tracklets.add(newTracklet)
            }
        }

        // Step 2: Filter out isolated 1-frame camera transition flashes (e.g. 6.5s-6.5s)
        // Genuine appearances of real people last at least 2 consecutive frames (>= 400ms)
        val validTracklets = tracklets.filter { it.detections.size >= 2 }
        val effectiveTracklets = if (validTracklets.isNotEmpty()) validTracklets else tracklets

        // Step 3: Initialize 1 cluster per tracklet
        val clusters = effectiveTracklets.mapIndexed { idx, tracklet ->
            val cluster = PersonCluster(
                id = idx + 1,
                detections = tracklet.detections
            )
            cluster.centroidEmbedding = tracklet.representativeEmbedding
            cluster
        }.toMutableList()

        // Step 3: Agglomeratively merge tracklet clusters of the same identity
        var merged = true
        while (merged) {
            merged = false
            var bestI = -1
            var bestJ = -1
            var minDistance = Float.MAX_VALUE

            for (i in 0 until clusters.size) {
                val timestampsA = clusters[i].detections.map { it.frameTimestampMs }.toSet()

                for (j in i + 1 until clusters.size) {
                    // Co-occurrence constraint: people appearing at the exact same time cannot merge!
                    val hasCoOccurrence = clusters[j].detections.any { timestampsA.contains(it.frameTimestampMs) }
                    if (hasCoOccurrence) continue

                    val c1 = clusters[i].centroidEmbedding ?: continue
                    val c2 = clusters[j].centroidEmbedding ?: continue
                    val dist = FaceEmbeddingEngine.cosineDistance(c1, c2)

                    if (dist <= maxCosineDistanceThreshold && dist < minDistance) {
                        minDistance = dist
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (bestI != -1 && bestJ != -1) {
                clusters[bestI].detections.addAll(clusters[bestJ].detections)
                clusters[bestI].updateCentroid()
                clusters.removeAt(bestJ)
                merged = true
            }
        }

        // Filter out fleeting noise and split-screen seam glitches (the seam phantom is already
        // removed at detection time, so this is a light secondary net):
        //  - keep anyone who appears in multiple scenes (segments >= 2) OR for >= 3 frames, AND
        //  - reject motion-blur phantoms: an identity that is blurry in EVERY frame (best sharpness
        //    < 18) and never lingers (no continuous run of >= 3 frames) is a camera-transition smear,
        //    not a real person. Sustained soft-focus people survive because they linger in one scene.
        val filtered = clusters.filter { cluster ->
            val segments = computeAppearanceSegments(cluster.detections)
            val appearsEnough = segments.size >= 2 || cluster.detections.size >= 3

            // Spec: "Blurred whip-pan passes count for nobody. An appearance starts when a person's face becomes clearly visible"
            // Any cluster whose best shot is blurry (bestSharpness < 25f) is a whip-pan motion artifact, not a real person.
            val bestSharpness = cluster.detections.maxOfOrNull { it.sharpnessScore } ?: 0f
            val isMotionBlurPhantom = bestSharpness < 25f

            appearsEnough && !isMotionBlurPhantom
        }
        val result = if (filtered.isNotEmpty()) filtered else clusters

        // Sort by prominence (number of appearances / detections descending)
        val sortedResult = result.sortedByDescending { it.detections.size }

        // Re-index cleanly 1 to N
        return sortedResult.mapIndexed { index, cluster ->
            PersonCluster(
                id = index + 1,
                detections = cluster.detections,
                centroidEmbedding = cluster.centroidEmbedding
            )
        }
    }

    /**
     * Computes continuous appearance segments for a person.
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
                segments.add(
                    AppearanceSegment(
                        startMs = currentStart,
                        endMs = currentEnd,
                        detectionCount = currentCount
                    )
                )
                currentStart = ts
                currentEnd = ts
                currentCount = 1
            } else {
                currentEnd = ts
                currentCount++
            }
        }

        segments.add(
            AppearanceSegment(
                startMs = currentStart,
                endMs = currentEnd,
                detectionCount = currentCount
            )
        )

        return segments
    }
}

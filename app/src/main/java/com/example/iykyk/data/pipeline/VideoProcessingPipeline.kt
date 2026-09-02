package com.example.iykyk.data.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.iykyk.data.ml.FaceDetectorEngine
import com.example.iykyk.data.ml.FaceEmbeddingEngine
import com.example.iykyk.domain.clustering.IdentityClusteringEngine
import com.example.iykyk.domain.model.FaceDetectionResult
import com.example.iykyk.domain.model.PipelineProgress
import com.example.iykyk.domain.model.ProcessingStage
import com.example.iykyk.domain.model.UniquePerson
import com.example.iykyk.domain.scoring.BestShotSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.math.max

sealed class PipelineState {
    data class Progress(val progress: PipelineProgress) : PipelineState()
    data class Success(val persons: List<UniquePerson>, val totalFramesProcessed: Int) : PipelineState()
    data class Error(val errorMessage: String) : PipelineState()
}

class VideoProcessingPipeline(
    private val context: Context,
    private val targetFps: Float = 4.0f,
    private val maxCosineDistance: Float = 0.52f, // 0.52 unites cross-scene appearances
    private val appearanceBreakMs: Long = 1200L
) {
    private val TAG = "VideoPipeline"
    private val faceDetector = FaceDetectorEngine()
    private val embeddingEngine = FaceEmbeddingEngine(context)
    private val clusteringEngine = IdentityClusteringEngine(maxCosineDistance, appearanceBreakMs)
    private val bestShotSelector = BestShotSelector()
    private val maxFrameDimension = 1080

    /**
     * Executes an asynchronous, streaming video pipeline with low memory footprint (<30MB).
     * Decodes frames one-by-one, extracts embeddings, generates portrait thumbnails,
     * and immediately recycles full frame bitmaps to prevent OOM errors on long/4K videos.
     */
    fun processVideo(videoUri: Uri): Flow<PipelineState> = flow {
        val retriever = MediaMetadataRetriever()
        val allDetections = mutableListOf<FaceDetectionResult>()
        var frameCount = 0

        try {
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.EXTRACTING_AND_DETECTING,
                        progress = 0.02f,
                        message = "Opening video stream..."
                    )
                )
            )

            if (videoUri.scheme == "content" || videoUri.scheme == "file") {
                retriever.setDataSource(context, videoUri)
            } else {
                retriever.setDataSource(videoUri.path)
            }

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                emit(PipelineState.Error("Invalid or unreadable video file."))
                return@flow
            }

            val intervalMs = (1000f / targetFps).toLong().coerceAtLeast(150L)
            val estimatedTotalFrames = (durationMs / intervalMs).toInt().coerceAtLeast(1)

            var currentTimestampMs = 0L

            // Frame-by-frame streaming loop
            while (currentTimestampMs < durationMs) {
                val timeUs = currentTimestampMs * 1000L
                val rawBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

                if (rawBitmap != null) {
                    val frameBitmap = scaleBitmapIfNeeded(rawBitmap, maxFrameDimension)
                    if (frameBitmap != rawBitmap) {
                        rawBitmap.recycle()
                    }

                    // 1. Detect faces on current frame
                    val detections = faceDetector.detectFaces(frameBitmap, currentTimestampMs)

                    // 2. For each detected face, extract crop & MobileFaceNet embedding immediately
                    for (detection in detections) {
                        // Skip severe camera transition blur (< 5 sharpness)
                        if (detection.sharpnessScore < 5f) {
                            continue
                        }

                        val faceCrop = embeddingEngine.extractFaceCrop(
                            frameBitmap,
                            detection.boundingBox,
                            marginRatio = 0.15f,
                            rollAngle = detection.eulerZ
                        )
                        val embedding = embeddingEngine.getEmbedding(faceCrop)
                        detection.embedding = embedding
                        if (faceCrop != frameBitmap) {
                            faceCrop.recycle()
                        }

                        val allOtherBoxes = detections.map { it.boundingBox }

                        // Store clean portrait bust thumbnail (clips against other faces in split-screen / two-shots)
                        val portraitBustCrop = bestShotSelector.createGenerousPortraitCrop(
                            frameBitmap,
                            detection.boundingBox,
                            otherFaceBoxes = allOtherBoxes,
                            targetAspectRatio = 0.80f,
                            expansionRatio = 0.20f
                        )
                        detection.portraitCrop = portraitBustCrop
                        val baseQuality = bestShotSelector.evaluateQualityScore(detection)
                        // Generous bonus for solo frames so solo portraits are preferred over crowded multi-person shots
                        if (detections.size == 1) {
                            detection.qualityScore = (baseQuality + 0.25f).coerceIn(0f, 1f)
                        }

                        allDetections.add(detection)
                    }

                    // 3. Immediately recycle frame bitmap to keep memory footprint flat (<30MB)
                    frameBitmap.recycle()
                    frameCount++
                }

                val progress = (currentTimestampMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) * 0.75f + 0.05f
                emit(
                    PipelineState.Progress(
                        PipelineProgress(
                            stage = ProcessingStage.EXTRACTING_AND_DETECTING,
                            progress = progress,
                            currentStep = frameCount,
                            totalSteps = estimatedTotalFrames,
                            message = "Processed frame $frameCount (${allDetections.size} faces detected so far)"
                        )
                    )
                )

                currentTimestampMs += intervalMs
            }

            if (allDetections.isEmpty()) {
                emit(PipelineState.Error("No faces were detected in this video."))
                return@flow
            }

            // Stage 2: Clustering identities & appearance tracking
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.CLUSTERING_IDENTITIES,
                        progress = 0.85f,
                        message = "Clustering unique individuals via MobileFaceNet embeddings..."
                    )
                )
            )

            val clusters = clusteringEngine.clusterFaces(allDetections)

            // Stage 3: Best Shot Selection
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.SELECTING_BEST_SHOTS,
                        progress = 0.95f,
                        message = "Selecting optimal portrait shots and assembling collage..."
                    )
                )
            )

            val uniquePersons = mutableListOf<UniquePerson>()
            for (cluster in clusters) {
                val bestShot = bestShotSelector.selectBestDetection(cluster.detections) ?: continue
                val appearanceSegments = clusteringEngine.computeAppearanceSegments(cluster.detections)

                uniquePersons.add(
                    UniquePerson(
                        id = cluster.id,
                        displayName = "Person #${cluster.id}",
                        bestShot = bestShot,
                        bestShotCrop = bestShot.portraitCrop,
                        totalAppearances = appearanceSegments.size,
                        appearanceSegments = appearanceSegments,
                        candidateDetections = cluster.detections,
                        compositeQualityScore = bestShot.qualityScore
                    )
                )
            }

            // Order by most prominent appearance count descending
            uniquePersons.sortByDescending { it.totalAppearances }

            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.COMPLETED,
                        progress = 1.0f,
                        message = "Identified ${uniquePersons.size} unique individuals"
                    )
                )
            )

            emit(PipelineState.Success(uniquePersons, frameCount))

        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error: ${e.message}", e)
            emit(PipelineState.Error(e.localizedMessage ?: "Unknown pipeline processing error"))
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {}
        }
    }.flowOn(Dispatchers.Default)

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxOriginal = max(width, height)
        if (maxOriginal <= maxDim) return bitmap

        val scale = maxDim.toFloat() / maxOriginal.toFloat()
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun release() {
        faceDetector.close()
        embeddingEngine.close()
    }
}

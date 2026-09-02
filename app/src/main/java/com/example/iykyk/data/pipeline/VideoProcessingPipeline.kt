package com.example.iykyk.data.pipeline

import android.content.Context
import android.net.Uri
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

sealed class PipelineState {
    data class Progress(val progress: PipelineProgress) : PipelineState()
    data class Success(val persons: List<UniquePerson>, val totalFramesProcessed: Int) : PipelineState()
    data class Error(val errorMessage: String) : PipelineState()
}

class VideoProcessingPipeline(
    private val context: Context,
    private val targetFps: Float = 4.0f,
    private val maxCosineDistance: Float = 0.35f,
    private val appearanceBreakMs: Long = 1200L
) {
    private val frameExtractor = VideoFrameExtractor(context, targetFps)
    private val faceDetector = FaceDetectorEngine()
    private val embeddingEngine = FaceEmbeddingEngine(context)
    private val clusteringEngine = IdentityClusteringEngine(maxCosineDistance, appearanceBreakMs)
    private val bestShotSelector = BestShotSelector()

    /**
     * Executes the full end-to-end video analysis pipeline.
     */
    fun processVideo(videoUri: Uri): Flow<PipelineState> = flow {
        val extractedFrames = mutableListOf<ExtractedFrame>()
        val allDetections = mutableListOf<FaceDetectionResult>()

        try {
            // Stage 1: Frame Extraction
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.EXTRACTING_FRAMES,
                        progress = 0.05f,
                        message = "Sampling video frames at ${targetFps.toInt()} FPS..."
                    )
                )
            )

            frameExtractor.extractFrames(videoUri).collect { event ->
                when (event) {
                    is FrameExtractionState.Progress -> {
                        // Map frame extraction progress to 0% - 25% of overall pipeline
                        val mappedProgress = 0.05f + (event.progress.progress * 0.20f)
                        emit(
                            PipelineState.Progress(
                                PipelineProgress(
                                    stage = ProcessingStage.EXTRACTING_FRAMES,
                                    progress = mappedProgress,
                                    currentStep = event.progress.currentStep,
                                    totalSteps = event.progress.totalSteps,
                                    message = event.progress.message
                                )
                            )
                        )
                    }
                    is FrameExtractionState.FrameReady -> {
                        extractedFrames.add(event.frame)
                    }
                    is FrameExtractionState.Completed -> {
                        // Extracted all frames
                    }
                    is FrameExtractionState.Error -> {
                        throw event.throwable
                    }
                }
            }

            if (extractedFrames.isEmpty()) {
                emit(PipelineState.Error("No frames could be extracted from video."))
                return@flow
            }

            // Stage 2: Face Detection & Landmark Extraction
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.DETECTING_FACES,
                        progress = 0.30f,
                        message = "Detecting faces and computing facial landmarks..."
                    )
                )
            )

            val totalFrames = extractedFrames.size
            for ((index, frame) in extractedFrames.withIndex()) {
                val detections = faceDetector.detectFaces(frame.bitmap, frame.timestampMs)
                allDetections.addAll(detections)

                val progress = 0.30f + ((index + 1).toFloat() / totalFrames.toFloat()) * 0.25f
                emit(
                    PipelineState.Progress(
                        PipelineProgress(
                            stage = ProcessingStage.DETECTING_FACES,
                            progress = progress,
                            currentStep = index + 1,
                            totalSteps = totalFrames,
                            message = "Processed frame ${index + 1}/$totalFrames (detected ${detections.size} faces)"
                        )
                    )
                )
            }

            if (allDetections.isEmpty()) {
                emit(PipelineState.Error("No faces detected in the video."))
                return@flow
            }

            // Stage 3: Face Embeddings Extraction (TFLite MobileFaceNet)
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.EXTRACTING_EMBEDDINGS,
                        progress = 0.55f,
                        message = "Generating on-device TFLite face embeddings..."
                    )
                )
            )

            val totalDetections = allDetections.size
            for ((index, detection) in allDetections.withIndex()) {
                val frameBmp = detection.frameBitmap
                if (frameBmp != null) {
                    val embedding = embeddingEngine.getEmbedding(frameBmp, detection.boundingBox)
                    detection.embedding = embedding
                }

                val progress = 0.55f + ((index + 1).toFloat() / totalDetections.toFloat()) * 0.20f
                emit(
                    PipelineState.Progress(
                        PipelineProgress(
                            stage = ProcessingStage.EXTRACTING_EMBEDDINGS,
                            progress = progress,
                            currentStep = index + 1,
                            totalSteps = totalDetections,
                            message = "Embedded face ${index + 1}/$totalDetections"
                        )
                    )
                )
            }

            // Stage 4: Identity Clustering & Appearance Counting
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.CLUSTERING_IDENTITIES,
                        progress = 0.78f,
                        message = "Clustering identities & calculating appearance segments..."
                    )
                )
            )

            val clusters = clusteringEngine.clusterFaces(allDetections)

            // Stage 5: Best Shot Selection & Portrait Cropping
            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.SELECTING_BEST_SHOTS,
                        progress = 0.90f,
                        message = "Selecting optimal representative portrait shots..."
                    )
                )
            )

            val uniquePersons = mutableListOf<UniquePerson>()
            for (cluster in clusters) {
                val bestShot = bestShotSelector.selectBestDetection(cluster.detections) ?: continue
                val appearanceSegments = clusteringEngine.computeAppearanceSegments(cluster.detections)

                val crop = if (bestShot.frameBitmap != null) {
                    bestShotSelector.createGenerousPortraitCrop(
                        bestShot.frameBitmap,
                        bestShot.boundingBox,
                        targetAspectRatio = 0.80f,
                        expansionRatio = 0.60f
                    )
                } else null

                uniquePersons.add(
                    UniquePerson(
                        id = cluster.id,
                        displayName = "Person ${cluster.id}",
                        bestShot = bestShot,
                        bestShotCrop = crop,
                        totalAppearances = appearanceSegments.size,
                        appearanceSegments = appearanceSegments,
                        candidateDetections = cluster.detections,
                        compositeQualityScore = bestShot.qualityScore
                    )
                )
            }

            // Sort persons by prominence / total appearances descending
            uniquePersons.sortByDescending { it.totalAppearances }

            emit(
                PipelineState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.COMPLETED,
                        progress = 1.0f,
                        message = "Identified ${uniquePersons.size} unique individuals!"
                    )
                )
            )

            emit(PipelineState.Success(uniquePersons, totalFrames))

        } catch (e: Exception) {
            emit(PipelineState.Error(e.localizedMessage ?: "Unknown pipeline processing error"))
        }
    }.flowOn(Dispatchers.Default)

    fun release() {
        faceDetector.close()
        embeddingEngine.close()
    }
}

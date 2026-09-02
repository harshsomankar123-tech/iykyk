package com.example.iykyk.data.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.iykyk.domain.model.PipelineProgress
import com.example.iykyk.domain.model.ProcessingStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

data class ExtractedFrame(
    val timestampMs: Long,
    val bitmap: Bitmap,
    val width: Int,
    val height: Int
)

sealed class FrameExtractionState {
    data class Progress(val progress: PipelineProgress) : FrameExtractionState()
    data class FrameReady(val frame: ExtractedFrame) : FrameExtractionState()
    data class Completed(val frames: List<ExtractedFrame>, val durationMs: Long) : FrameExtractionState()
    data class Error(val throwable: Throwable) : FrameExtractionState()
}

class VideoFrameExtractor(
    private val context: Context,
    private val targetFps: Float = 4.0f, // 4 frames per second (~250ms interval)
    private val maxDimension: Int = 1080 // Rescale max dimension to 1080p for performance & memory efficiency
) {
    /**
     * Extracts frames asynchronously at fixed intervals (3–5 FPS) from video URI.
     */
    fun extractFrames(videoUri: Uri): Flow<FrameExtractionState> = flow {
        val retriever = MediaMetadataRetriever()
        val extractedList = mutableListOf<ExtractedFrame>()

        try {
            emit(
                FrameExtractionState.Progress(
                    PipelineProgress(
                        stage = ProcessingStage.EXTRACTING_FRAMES,
                        progress = 0f,
                        message = "Opening video file..."
                    )
                )
            )

            // Set data source based on URI scheme
            if (videoUri.scheme == "content" || videoUri.scheme == "file") {
                retriever.setDataSource(context, videoUri)
            } else {
                retriever.setDataSource(videoUri.path)
            }

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                emit(FrameExtractionState.Error(IllegalArgumentException("Invalid or empty video file")))
                return@flow
            }

            val intervalMs = (1000f / targetFps).toLong().coerceAtLeast(100L)
            val totalFramesEstimated = (durationMs / intervalMs).toInt().coerceAtLeast(1)

            var currentTimestampMs = 0L
            var frameIndex = 0

            while (currentTimestampMs < durationMs) {
                val timeUs = currentTimestampMs * 1000L
                val rawBitmap = retriever.getFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )

                if (rawBitmap != null) {
                    val scaledBitmap = scaleBitmapIfNeeded(rawBitmap, maxDimension)
                    if (scaledBitmap != rawBitmap) {
                        rawBitmap.recycle()
                    }

                    val frame = ExtractedFrame(
                        timestampMs = currentTimestampMs,
                        bitmap = scaledBitmap,
                        width = scaledBitmap.width,
                        height = scaledBitmap.height
                    )
                    extractedList.add(frame)
                    emit(FrameExtractionState.FrameReady(frame))
                }

                frameIndex++
                val progressFraction = (currentTimestampMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                emit(
                    FrameExtractionState.Progress(
                        PipelineProgress(
                            stage = ProcessingStage.EXTRACTING_FRAMES,
                            progress = progressFraction,
                            currentStep = frameIndex,
                            totalSteps = totalFramesEstimated,
                            message = "Extracted frame $frameIndex at ${currentTimestampMs / 1000f}s"
                        )
                    )
                )

                currentTimestampMs += intervalMs
            }

            emit(FrameExtractionState.Completed(extractedList, durationMs))

        } catch (e: Exception) {
            emit(FrameExtractionState.Error(e))
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }.flowOn(Dispatchers.IO)

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
}

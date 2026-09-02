package com.example.iykyk.domain.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Processing stages for the video-to-collage pipeline.
 */
enum class ProcessingStage(val title: String) {
    IDLE("Ready"),
    EXTRACTING_FRAMES("Extracting Frames (3–5 FPS)"),
    DETECTING_FACES("Detecting Faces & Landmarks"),
    EXTRACTING_EMBEDDINGS("Generating TFLite Face Embeddings"),
    CLUSTERING_IDENTITIES("Clustering Unique Identities"),
    SELECTING_BEST_SHOTS("Selecting Best Representative Shots"),
    COMPLETED("Collage Ready"),
    ERROR("Processing Failed")
}

/**
 * Real-time progress update emitted during video processing.
 */
data class PipelineProgress(
    val stage: ProcessingStage = ProcessingStage.IDLE,
    val progress: Float = 0f, // 0.0 to 1.0
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val message: String = "Ready to process video"
)

/**
 * Single face detection in a video frame with extracted ML Kit attributes and sharpness.
 */
data class FaceDetectionResult(
    val id: String,
    val frameTimestampMs: Long,
    val boundingBox: Rect,
    val frameWidth: Int,
    val frameHeight: Int,
    val eulerX: Float = 0f, // Head tilt (pitch: up/down)
    val eulerY: Float = 0f, // Head turn (yaw: left/right)
    val eulerZ: Float = 0f, // Head tilt (roll: slant)
    val leftEyeOpenProb: Float? = null,
    val rightEyeOpenProb: Float? = null,
    val smileProb: Float? = null,
    val sharpnessScore: Float = 0f,
    val frameBitmap: Bitmap? = null,
    var embedding: FloatArray? = null,
    var qualityScore: Float = 0f
) {
    val isTouchingBoundary: Boolean
        get() {
            val margin = 8
            return boundingBox.left <= margin ||
                    boundingBox.top <= margin ||
                    boundingBox.right >= frameWidth - margin ||
                    boundingBox.bottom >= frameHeight - margin
        }

    val eyesOpenScore: Float
        get() {
            val left = leftEyeOpenProb ?: 0.5f
            val right = rightEyeOpenProb ?: 0.5f
            return (left + right) / 2f
        }

    val frontalityScore: Float
        get() {
            // Frontality: 1.0 - (|EulerY| + |EulerZ| + 0.5 * |EulerX|) / 90.0
            val penalty = (Math.abs(eulerY) + Math.abs(eulerZ) + 0.5f * Math.abs(eulerX)) / 90.0f
            return (1.0f - penalty).coerceIn(0.0f, 1.0f)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceDetectionResult
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Continuous appearance interval of a single person in the video timeline.
 */
data class AppearanceSegment(
    val startMs: Long,
    val endMs: Long,
    val detectionCount: Int
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val durationSeconds: Float get() = durationMs / 1000f
}

/**
 * Isolated unique person cluster containing all their detections across the video,
 * calculated appearance segments, and the best selected shot.
 */
data class UniquePerson(
    val id: Int,
    val displayName: String = "Person $id",
    val bestShot: FaceDetectionResult,
    val bestShotCrop: Bitmap? = null,
    val totalAppearances: Int,
    val appearanceSegments: List<AppearanceSegment> = emptyList(),
    val candidateDetections: List<FaceDetectionResult> = emptyList(),
    val compositeQualityScore: Float = 0f
)

/**
 * Styling configuration for Instagram Story / Bento Grid collage.
 */
data class CollageConfig(
    val title: String = "Video Highlights",
    val subtitle: String = "Unique Individuals Detected",
    val showAppearanceBadges: Boolean = true,
    val showQualityScores: Boolean = false,
    val backgroundColorHex: Long = 0xFF000000, // Pure Black
    val cardCornerRadiusDp: Int = 16,
    val spacingDp: Int = 12,
    val maxColumns: Int = 2,
    val bustCropExpansion: Float = 0.35f // 35% extra margin for generous portrait bust shot
)

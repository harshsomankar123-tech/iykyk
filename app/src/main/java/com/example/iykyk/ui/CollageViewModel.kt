package com.example.iykyk.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iykyk.data.pipeline.PipelineState
import com.example.iykyk.data.pipeline.SampleVideoGenerator
import com.example.iykyk.data.pipeline.VideoProcessingPipeline
import com.example.iykyk.domain.clustering.IdentityClusteringEngine
import com.example.iykyk.domain.model.CollageConfig
import com.example.iykyk.domain.model.PipelineProgress
import com.example.iykyk.domain.model.ProcessingStage
import com.example.iykyk.domain.model.UniquePerson
import com.example.iykyk.domain.scoring.BestShotSelector
import com.example.iykyk.ui.export.CollageBitmapRenderer
import com.example.iykyk.ui.export.CollageExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CollageUiState(
    val selectedVideoUri: Uri? = null,
    val isProcessing: Boolean = false,
    val progress: PipelineProgress = PipelineProgress(),
    val persons: List<UniquePerson> = emptyList(),
    val config: CollageConfig = CollageConfig(),
    val renderedCollageBitmap: Bitmap? = null,
    val isExporting: Boolean = false,
    val errorMessage: String? = null
)

class CollageViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CollageUiState())
    val uiState: StateFlow<CollageUiState> = _uiState.asStateFlow()

    private val exportManager = CollageExportManager(application.applicationContext)
    private var currentPipeline: VideoProcessingPipeline? = null

    fun selectVideo(uri: Uri) {
        _uiState.update {
            it.copy(
                selectedVideoUri = uri,
                errorMessage = null
            )
        }
    }

    /**
     * Runs full video pipeline on the selected video URI.
     */
    fun startProcessingVideo(videoUri: Uri? = _uiState.value.selectedVideoUri) {
        val uri = videoUri ?: return

        _uiState.update {
            it.copy(
                isProcessing = true,
                errorMessage = null,
                persons = emptyList(),
                renderedCollageBitmap = null,
                progress = PipelineProgress(stage = ProcessingStage.EXTRACTING_FRAMES, progress = 0.05f)
            )
        }

        viewModelScope.launch {
            val pipeline = VideoProcessingPipeline(getApplication())
            currentPipeline = pipeline

            pipeline.processVideo(uri).collect { state ->
                when (state) {
                    is PipelineState.Progress -> {
                        _uiState.update {
                            it.copy(
                                progress = state.progress,
                                isProcessing = state.progress.stage != ProcessingStage.COMPLETED
                            )
                        }
                    }
                    is PipelineState.Success -> {
                        val renderedBmp = CollageBitmapRenderer.renderStoryCollage(
                            persons = state.persons,
                            config = _uiState.value.config
                        )

                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                persons = state.persons,
                                renderedCollageBitmap = renderedBmp,
                                progress = PipelineProgress(stage = ProcessingStage.COMPLETED, progress = 1.0f)
                            )
                        }
                    }
                    is PipelineState.Error -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = state.errorMessage,
                                progress = PipelineProgress(stage = ProcessingStage.ERROR, progress = 0f)
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads the benchmark Sample 1 test dataset (5 individuals with 4 appearances each).
     */
    fun loadSample1Demo() {
        _uiState.update {
            it.copy(
                isProcessing = true,
                errorMessage = null,
                progress = PipelineProgress(
                    stage = ProcessingStage.CLUSTERING_IDENTITIES,
                    progress = 0.6f,
                    message = "Simulating Sample 1 (5 persons, 4 appearances each)..."
                )
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            val syntheticDetections = SampleVideoGenerator.createSample1SyntheticData()
            val clusteringEngine = IdentityClusteringEngine(maxCosineDistanceThreshold = 0.35f, appearanceBreakGapMs = 1200L)
            val bestShotSelector = BestShotSelector()

            val clusters = clusteringEngine.clusterFaces(syntheticDetections)
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
                        displayName = "Person #${cluster.id}",
                        bestShot = bestShot,
                        bestShotCrop = crop,
                        totalAppearances = appearanceSegments.size,
                        appearanceSegments = appearanceSegments,
                        candidateDetections = cluster.detections,
                        compositeQualityScore = bestShot.qualityScore
                    )
                )
            }

            uniquePersons.sortBy { it.id }

            val renderedBmp = CollageBitmapRenderer.renderStoryCollage(
                persons = uniquePersons,
                config = _uiState.value.config
            )

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        persons = uniquePersons,
                        renderedCollageBitmap = renderedBmp,
                        progress = PipelineProgress(
                            stage = ProcessingStage.COMPLETED,
                            progress = 1.0f,
                            message = "Loaded Sample 1 benchmark (5 people, 4 appearances each)"
                        )
                    )
                }
            }
        }
    }

    fun updateTitle(newTitle: String) {
        val newConfig = _uiState.value.config.copy(title = newTitle)
        val currentPersons = _uiState.value.persons
        val updatedBmp = if (currentPersons.isNotEmpty()) {
            CollageBitmapRenderer.renderStoryCollage(currentPersons, newConfig)
        } else null

        _uiState.update {
            it.copy(config = newConfig, renderedCollageBitmap = updatedBmp)
        }
    }

    fun toggleBadges(show: Boolean) {
        val newConfig = _uiState.value.config.copy(showAppearanceBadges = show)
        val currentPersons = _uiState.value.persons
        val updatedBmp = if (currentPersons.isNotEmpty()) {
            CollageBitmapRenderer.renderStoryCollage(currentPersons, newConfig)
        } else null

        _uiState.update {
            it.copy(config = newConfig, renderedCollageBitmap = updatedBmp)
        }
    }

    fun saveToGallery(onComplete: (Boolean) -> Unit) {
        val bmp = _uiState.value.renderedCollageBitmap ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val uri = exportManager.saveToGallery(bmp)
            _uiState.update { it.copy(isExporting = false) }
            onComplete(uri != null)
        }
    }

    fun shareCollage() {
        val bmp = _uiState.value.renderedCollageBitmap ?: return
        viewModelScope.launch {
            exportManager.shareCollage(bmp)
        }
    }

    fun resetState() {
        _uiState.update {
            CollageUiState()
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentPipeline?.release()
    }
}

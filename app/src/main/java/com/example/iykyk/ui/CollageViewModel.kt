package com.example.iykyk.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iykyk.data.pipeline.PipelineState
import com.example.iykyk.data.pipeline.VideoProcessingPipeline
import com.example.iykyk.domain.model.CollageConfig
import com.example.iykyk.domain.model.PipelineProgress
import com.example.iykyk.domain.model.ProcessingStage
import com.example.iykyk.domain.model.UniquePerson
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
     * Runs full on-device video pipeline on the selected video URI.
     */
    fun startProcessingVideo(videoUri: Uri? = _uiState.value.selectedVideoUri) {
        val uri = videoUri ?: return

        _uiState.update {
            it.copy(
                isProcessing = true,
                errorMessage = null,
                persons = emptyList(),
                renderedCollageBitmap = null,
                progress = PipelineProgress(stage = ProcessingStage.EXTRACTING_AND_DETECTING, progress = 0.05f)
            )
        }

        viewModelScope.launch {
            try {
                val pipeline = withContext(Dispatchers.IO) {
                    VideoProcessingPipeline(getApplication())
                }
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = e.localizedMessage ?: "Processing failed",
                        progress = PipelineProgress(stage = ProcessingStage.ERROR, progress = 0f)
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
        val currentPersons = _uiState.value.persons
        if (currentPersons.isEmpty()) return
        val currentConfig = _uiState.value.config
        val bmp = CollageBitmapRenderer.renderStoryCollage(currentPersons, currentConfig)

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, renderedCollageBitmap = bmp) }
            val uri = exportManager.saveToGallery(bmp)
            _uiState.update { it.copy(isExporting = false) }
            onComplete(uri != null)
        }
    }

    fun saveIndividualImage(bitmap: Bitmap, personName: String, onComplete: (Boolean) -> Unit) {
        val cleanName = personName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val filename = "IYKYK_${cleanName}_${System.currentTimeMillis()}.png"
        viewModelScope.launch {
            val uri = exportManager.saveToGallery(bitmap, filename)
            onComplete(uri != null)
        }
    }

    fun shareCollage() {
        val currentPersons = _uiState.value.persons
        if (currentPersons.isEmpty()) return
        val currentConfig = _uiState.value.config
        val bmp = CollageBitmapRenderer.renderStoryCollage(currentPersons, currentConfig)

        viewModelScope.launch {
            _uiState.update { it.copy(renderedCollageBitmap = bmp) }
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

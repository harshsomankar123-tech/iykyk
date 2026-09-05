package com.example.iykyk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.iykyk.ui.CollageViewModel
import com.example.iykyk.ui.screens.CollageResultScreen
import com.example.iykyk.ui.screens.VideoPickerScreen
import com.example.iykyk.ui.theme.IYKYKTheme
import com.example.iykyk.ui.theme.PureBlack

class MainActivity : ComponentActivity() {

    private val viewModel: CollageViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            IYKYKTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PureBlack
                ) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: CollageViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = "picker"
    ) {
        composable("picker") {
            VideoPickerScreen(
                uiState = uiState,
                onVideoSelected = { uri -> viewModel.selectVideo(uri) },
                onStartProcessing = {
                    viewModel.startProcessingVideo()
                },
                onViewResults = {
                    navController.navigate("results")
                },
                onClearVideo = {
                    viewModel.resetState()
                }
            )
        }

        composable("results") {
            CollageResultScreen(
                uiState = uiState,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onUpdateTitle = { title ->
                    viewModel.updateTitle(title)
                },
                onToggleBadges = { show ->
                    viewModel.toggleBadges(show)
                },
                onSaveToGallery = { callback ->
                    viewModel.saveToGallery(callback)
                },
                onShareCollage = {
                    viewModel.shareCollage()
                },
                onSaveIndividualImage = { bmp, name, callback ->
                    viewModel.saveIndividualImage(bmp, name, callback)
                }
            )
        }
    }
}

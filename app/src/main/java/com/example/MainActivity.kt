package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vyro.core.designsystem.VyroTheme
import com.vyro.feature.editor.DiagnosticsScreen
import com.vyro.feature.editor.DiagnosticsViewModel
import com.vyro.feature.editor.EditorScreen
import com.vyro.feature.editor.EditorViewModel
import com.vyro.feature.home.HomeScreen
import com.vyro.feature.home.HomeViewModel
import com.vyro.feature.media.MediaSelectorScreen
import com.vyro.feature.media.MediaSelectorViewModel

object VyroRoutes {
    const val ROUTE_HOME = "home"
    const val ROUTE_MEDIA = "media?targetProjectId={targetProjectId}"
    const val ROUTE_EDITOR = "editor/{projectId}"
    const val ROUTE_DIAGNOSTICS = "diagnostics"

    fun mediaRoute(targetProjectId: String? = null): String {
        return if (targetProjectId.isNullOrBlank()) {
            "media"
        } else {
            "media?targetProjectId=$targetProjectId"
        }
    }

    fun editorRoute(projectId: String): String = "editor/$projectId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as VyroApplication).container
        setContent {
            VyroTheme {
                VyroNavHost(container = container)
            }
        }
    }
}

@Composable
fun VyroNavHost(container: AppContainer) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = VyroRoutes.ROUTE_HOME,
    ) {
        composable(VyroRoutes.ROUTE_HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(
                    projectRepository = container.projectRepository,
                    engineSession = container.engineSession,
                ),
            )
            HomeScreen(
                viewModel = vm,
                onNewProjectClick = {
                    navController.navigate(VyroRoutes.mediaRoute(null))
                },
                onOpenProject = { projectId ->
                    navController.navigate(VyroRoutes.editorRoute(projectId))
                },
                onOpenDiagnostics = {
                    navController.navigate(VyroRoutes.ROUTE_DIAGNOSTICS)
                },
            )
        }

        composable(
            route = VyroRoutes.ROUTE_MEDIA,
            arguments = listOf(
                navArgument("targetProjectId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val targetProjectId = backStackEntry.arguments?.getString("targetProjectId")
            val vm: MediaSelectorViewModel = viewModel(
                key = "media_${targetProjectId ?: "new"}",
                factory = MediaSelectorViewModel.Factory(
                    targetProjectId = targetProjectId,
                    projectRepository = container.projectRepository,
                    mediaInspector = container.mediaInspector,
                ),
            )
            MediaSelectorScreen(
                viewModel = vm,
                onClose = { navController.popBackStack() },
                onComplete = { resultProjectId ->
                    if (targetProjectId == null) {
                        navController.navigate(VyroRoutes.editorRoute(resultProjectId)) {
                            popUpTo(VyroRoutes.ROUTE_HOME)
                        }
                    } else {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("media_updated", true)
                        navController.popBackStack()
                    }
                },
            )
        }

        composable(
            route = VyroRoutes.ROUTE_EDITOR,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            val vm: EditorViewModel = viewModel(
                key = "editor_$projectId",
                factory = run {
                    val renderer = container.createCompositionRenderer()
                    val bundle = container.createPlaybackBundle(renderer)
                    EditorViewModel.Factory(
                        projectId = projectId,
                        projectRepository = container.projectRepository,
                        engineSession = container.engineSession,
                        mediaInspector = container.mediaInspector,
                        playbackController = bundle.playbackController,
                        compositionRenderer = renderer,
                        audioSource = bundle.audioSource,
                        audioEngine = bundle.audioEngine,
                        cacheManager = container.cacheManager,
                        exportCoordinator = container.exportCoordinator,
                        onPublishTelemetry = container::publishPlaybackTelemetry,
                        onPublishRenderTelemetry = container::publishRenderTelemetry,
                        onPublishAudioTelemetry = container::publishAudioTelemetry,
                    )
                },
            )
            val mediaUpdated by backStackEntry.savedStateHandle
                .getStateFlow("media_updated", false)
                .collectAsStateWithLifecycle()

            LaunchedEffect(mediaUpdated) {
                if (mediaUpdated) {
                    backStackEntry.savedStateHandle["media_updated"] = false
                    vm.reloadProject()
                }
            }

            EditorScreen(
                viewModel = vm,
                onClose = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(VyroRoutes.ROUTE_DIAGNOSTICS) },
                onAddMedia = { id ->
                    navController.navigate(VyroRoutes.mediaRoute(id))
                },
            )
        }

        composable(VyroRoutes.ROUTE_DIAGNOSTICS) {
            val vm: DiagnosticsViewModel = viewModel(
                factory = DiagnosticsViewModel.Factory(
                    engineSession = container.engineSession,
                    playbackTelemetryFlow = container.latestPlaybackTelemetry,
                    renderTelemetryFlow = container.latestRenderTelemetry,
                    audioTelemetryFlow = container.latestAudioTelemetry,
                    cacheManager = container.cacheManager,
                    projectRepository = container.projectRepository,
                    exportCoordinator = container.exportCoordinator,
                    runtimeHardeningMonitor = container.runtimeHardeningMonitor,
                ),
            )
            DiagnosticsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

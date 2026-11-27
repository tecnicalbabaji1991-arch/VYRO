package com.example

import android.content.Context
import com.vyro.bridge.jni.VyroEngineSessionManager
import com.vyro.core.common.DefaultDispatchersProvider
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.contracts.AudioDiagnosticsSnapshot
import com.vyro.core.contracts.AudioPlaybackEngineContract
import com.vyro.core.contracts.AudioSourceContract
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.CompositionRendererContract
import com.vyro.core.contracts.EngineSessionContract
import com.vyro.core.contracts.ExportCoordinatorContract
import com.vyro.core.contracts.MediaInspectorContract
import com.vyro.core.contracts.PlaybackControllerContract
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.contracts.RenderDiagnosticsSnapshot
import com.vyro.core.database.VyroStorageModule
import com.vyro.platform.media.AndroidAudioPlaybackEngine
import com.vyro.platform.media.AndroidCacheAndProxyManager
import com.vyro.platform.media.AndroidExportEngine
import com.vyro.platform.media.AndroidMediaCodecAudioDecoder
import com.vyro.platform.media.AndroidMediaCodecDecoder
import com.vyro.platform.media.AndroidMediaInspector
import com.vyro.platform.media.AndroidPlaybackCoordinator
import com.vyro.platform.media.GlesCompositionRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Explicit dependency composition container for VYRO (avoids hidden global service locators).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val dispatchers: DispatchersProvider = DefaultDispatchersProvider

    private val _latestPlaybackTelemetry = MutableStateFlow(PlaybackDiagnosticsSnapshot())
    val latestPlaybackTelemetry: StateFlow<PlaybackDiagnosticsSnapshot> = _latestPlaybackTelemetry.asStateFlow()

    private val _latestRenderTelemetry = MutableStateFlow(RenderDiagnosticsSnapshot())
    val latestRenderTelemetry: StateFlow<RenderDiagnosticsSnapshot> = _latestRenderTelemetry.asStateFlow()

    private val _latestAudioTelemetry = MutableStateFlow(AudioDiagnosticsSnapshot())
    val latestAudioTelemetry: StateFlow<AudioDiagnosticsSnapshot> = _latestAudioTelemetry.asStateFlow()

    val cacheManager: CacheAndProxyManagerContract by lazy {
        AndroidCacheAndProxyManager(
            context = appContext,
            dispatchers = dispatchers,
        )
    }

    val projectRepository: ProjectRepository by lazy {
        VyroStorageModule.createProjectRepository(
            context = appContext,
            rootDir = File(appContext.filesDir, "vyro"),
            dispatchers = dispatchers,
        )
    }

    val mediaInspector: MediaInspectorContract by lazy {
        AndroidMediaInspector(
            context = appContext,
            dispatchers = dispatchers,
        )
    }

    val engineSession: EngineSessionContract by lazy {
        VyroEngineSessionManager(
            context = appContext,
            dispatchers = dispatchers,
        )
    }

    val exportCoordinator: ExportCoordinatorContract by lazy {
        AndroidExportEngine(
            context = appContext,
            audioSource = AndroidMediaCodecAudioDecoder(
                context = appContext,
                cacheManager = null,
            ),
            dispatchers = dispatchers,
        )
    }

    fun createCompositionRenderer(): CompositionRendererContract {
        return GlesCompositionRenderer(
            context = appContext,
            useOffscreenPbufferForPreview = true,
        )
    }

    fun createAudioSource(): AudioSourceContract {
        return AndroidMediaCodecAudioDecoder(
            context = appContext,
            cacheManager = cacheManager,
        )
    }

    fun createPlaybackBundle(renderer: CompositionRendererContract? = null): PlaybackBundle {
        val audioSource = createAudioSource()
        var coordinatorRef: PlaybackControllerContract? = null
        val audioEngine = AndroidAudioPlaybackEngine(
            context = appContext,
            audioSource = audioSource,
            dispatchers = dispatchers,
            onAudioClockAdvanced = { clockUs ->
                coordinatorRef?.reportAudioClockUs(clockUs)
            },
        )
        val coordinator = AndroidPlaybackCoordinator(
            decoder = AndroidMediaCodecDecoder(appContext),
            dispatchers = dispatchers,
            compositionRenderer = renderer,
            audioEngine = audioEngine,
            cacheManager = cacheManager,
        )
        coordinatorRef = coordinator
        return PlaybackBundle(
            playbackController = coordinator,
            audioSource = audioSource,
            audioEngine = audioEngine,
        )
    }

    fun createPlaybackController(renderer: CompositionRendererContract? = null): PlaybackControllerContract {
        return createPlaybackBundle(renderer).playbackController
    }

    fun publishPlaybackTelemetry(snapshot: PlaybackDiagnosticsSnapshot) {
        _latestPlaybackTelemetry.value = snapshot
    }

    fun publishRenderTelemetry(snapshot: RenderDiagnosticsSnapshot) {
        _latestRenderTelemetry.value = snapshot
    }

    fun publishAudioTelemetry(snapshot: AudioDiagnosticsSnapshot) {
        _latestAudioTelemetry.value = snapshot
    }

    data class PlaybackBundle(
        val playbackController: PlaybackControllerContract,
        val audioSource: AudioSourceContract,
        val audioEngine: AudioPlaybackEngineContract,
    )
}

package com.vyro.feature.editor

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.AudioDiagnosticsSnapshot
import com.vyro.core.contracts.AudioMixEngine
import com.vyro.core.contracts.AudioPlaybackEngineContract
import com.vyro.core.contracts.AudioSourceContract
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.CacheMemoryDiagnosticsSnapshot
import com.vyro.core.contracts.CommandHistory
import com.vyro.core.contracts.CompositionRendererContract
import com.vyro.core.contracts.DeviceCapabilities
import com.vyro.core.contracts.EditCommand
import com.vyro.core.contracts.EngineBackendMode
import com.vyro.core.contracts.EngineSessionContract
import com.vyro.core.contracts.ExportCodecCapabilityReport
import com.vyro.core.contracts.ExportConfig
import com.vyro.core.contracts.ExportCoordinatorContract
import com.vyro.core.contracts.ExportDiagnosticsSnapshot
import com.vyro.core.contracts.ExportProgressSnapshot
import com.vyro.core.contracts.ExportResolutionPreset
import com.vyro.core.contracts.ExportVideoCodec
import com.vyro.core.contracts.MediaInspectorContract
import com.vyro.core.contracts.PersistenceDiagnosticsSnapshot
import com.vyro.core.contracts.PersistenceSaveStatus
import com.vyro.core.contracts.PlaybackControllerContract
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.PlaybackState
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.contracts.RecoverySource
import com.vyro.core.contracts.RenderDiagnosticsSnapshot
import com.vyro.core.contracts.RenderGraph
import com.vyro.core.contracts.RenderGraphEvaluator
import com.vyro.core.contracts.TimelineEvaluator
import com.vyro.core.contracts.UndoHistoryPolicy
import com.vyro.core.contracts.WaveformProfile
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.BlendMode
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.CropRect
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.TrackType
import com.vyro.core.model.TransformKeyframe
import com.vyro.core.model.TransitionPreset
import com.vyro.core.model.TransitionSpec
import com.vyro.core.model.VisualEffectPreset
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class EditorUiState(
    val isLoading: Boolean = true,
    val isRelinking: Boolean = false,
    val projectId: String = "",
    val projectTitle: String = "",
    val sequence: Sequence? = null,
    val assets: List<MediaAsset> = emptyList(),
    val activeClips: List<Clip> = emptyList(),
    val selectedClipId: String? = null,
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val isFullscreen: Boolean = false,
    val zoomScale: Float = 1.0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val backendMode: EngineBackendMode = EngineBackendMode.JVM_CONTRACT_FALLBACK,
    val capabilities: DeviceCapabilities? = null,
    val playbackDiagnostics: PlaybackDiagnosticsSnapshot = PlaybackDiagnosticsSnapshot(),
    val renderGraph: RenderGraph? = null,
    val renderDiagnostics: RenderDiagnosticsSnapshot = RenderDiagnosticsSnapshot(),
    val audioDiagnostics: AudioDiagnosticsSnapshot = AudioDiagnosticsSnapshot(),
    val cacheDiagnostics: CacheMemoryDiagnosticsSnapshot = CacheMemoryDiagnosticsSnapshot(),
    val persistenceDiagnostics: PersistenceDiagnosticsSnapshot = PersistenceDiagnosticsSnapshot(),
    val saveStatus: PersistenceSaveStatus = PersistenceSaveStatus.IDLE,
    val recoverySource: RecoverySource = RecoverySource.NONE,
    val showRecoveryBanner: Boolean = false,
    val hasCommittedBaseline: Boolean = false,
    val recoveredCommandLabels: List<String> = emptyList(),
    val migratedFromVersion: Int? = null,
    val hasUnsavedChanges: Boolean = false,
    val proxyEnabled: Boolean = false,
    val waveforms: Map<String, WaveformProfile> = emptyMap(),
    val exportConfig: ExportConfig? = null,
    val exportProgress: ExportProgressSnapshot = ExportProgressSnapshot(),
    val exportDiagnostics: ExportDiagnosticsSnapshot = ExportDiagnosticsSnapshot(),
    val exportCodecCapabilities: ExportCodecCapabilityReport = ExportCodecCapabilityReport(),
    val loadError: EngineError? = null,
    val error: EngineError? = null,
) {
    val missingAssets: List<MediaAsset>
        get() = assets.filter { it.availability != AssetAvailability.AVAILABLE }

    val selectedClip: Clip?
        get() = sequence?.tracks?.flatMap { it.clips }?.firstOrNull { it.id == selectedClipId }

    val primaryVideoClip: Clip?
        get() = activeClips.firstOrNull { clip ->
            sequence?.tracks?.any { it.id == clip.trackId && it.type == TrackType.VIDEO } == true
        } ?: sequence?.tracks?.firstOrNull { it.type == TrackType.VIDEO }?.clips?.firstOrNull()

    val primaryAudioTargetClip: Clip?
        get() {
            val seq = sequence ?: return null
            val selected = selectedClip
            if (selected != null) {
                val track = seq.tracks.firstOrNull { it.id == selected.trackId }
                if (track != null && AudioMixEngine.isAudioBearingClip(track.type, selected)) {
                    return selected
                }
            }
            val firstAudio = seq.tracks.firstOrNull { it.type == TrackType.AUDIO }?.clips?.firstOrNull()
            if (firstAudio != null) return firstAudio
            val videoWithAudio = seq.tracks.firstOrNull { it.type == TrackType.VIDEO }
                ?.clips
                ?.firstOrNull { AudioMixEngine.isAudioBearingClip(TrackType.VIDEO, it) }
            return videoWithAudio
        }

    val isSelectedClipText: Boolean
        get() = selectedClip?.textSpec != null

    val isClipAudioMuted: Boolean
        get() {
            val seq = sequence ?: return true
            val videoMuted = seq.tracks.firstOrNull { it.type == TrackType.VIDEO }?.isMuted ?: false
            val audioTrack = seq.tracks.firstOrNull { it.type == TrackType.AUDIO }
            val audioMuted = audioTrack?.isMuted ?: false
            return if (audioTrack?.clips?.isNotEmpty() == true) audioMuted else videoMuted
        }

    val keyframeTargetClip: Clip?
        get() = selectedClip ?: primaryVideoClip

    val hasKeyframeNearPlayhead: Boolean
        get() {
            val target = keyframeTargetClip ?: return false
            val offsetUs = (playheadUs - target.timing.timelineStartUs).coerceIn(0L, target.timing.timelineDurationUs)
            val tolerance = (sequence?.frameRate?.frameDurationUs ?: 33_333L).coerceAtLeast(33_333L)
            return target.keyframes.any { abs(it.offsetUs - offsetUs) <= tolerance }
        }

    val selectedTrack: com.vyro.core.model.Track?
        get() {
            val target = selectedClip ?: primaryVideoClip ?: return null
            return sequence?.tracks?.firstOrNull { it.id == target.trackId }
        }

    val isSelectedTrackLocked: Boolean
        get() = selectedTrack?.isLocked == true
}

class EditorViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val engineSession: EngineSessionContract,
    private val mediaInspector: MediaInspectorContract,
    private val playbackController: PlaybackControllerContract,
    private val compositionRenderer: CompositionRendererContract,
    private val audioSource: AudioSourceContract? = null,
    private val audioEngine: AudioPlaybackEngineContract? = null,
    private val cacheManager: CacheAndProxyManagerContract? = null,
    private val exportCoordinator: ExportCoordinatorContract? = null,
    private val onPublishTelemetry: (PlaybackDiagnosticsSnapshot) -> Unit = {},
    private val onPublishRenderTelemetry: (RenderDiagnosticsSnapshot) -> Unit = {},
    private val onPublishAudioTelemetry: (AudioDiagnosticsSnapshot) -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState(projectId = projectId))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var document: ProjectDocument? = null
    private var history: CommandHistory? = null
    private var nativeProjectId: Long = 0L
    private var snapshotDebounceJob: Job? = null
    private var autosaveDebounceJob: Job? = null

    init {
        viewModelScope.launch {
            playbackController.telemetry.collect { snapshot ->
                onPublishTelemetry(snapshot)
                val seq = _uiState.value.sequence
                _uiState.update { state ->
                    val playing = snapshot.playbackState == PlaybackState.PLAYING
                    val updatedPos = if (playing || state.playheadUs != snapshot.timelinePositionUs) {
                        snapshot.timelinePositionUs
                    } else {
                        state.playheadUs
                    }
                    val updatedGraph = if (seq != null && updatedPos != state.playheadUs) {
                        RenderGraphEvaluator.evaluate(seq, updatedPos)
                    } else {
                        state.renderGraph
                    }
                    state.copy(
                        playheadUs = updatedPos,
                        isPlaying = playing,
                        activeClips = if (seq != null && updatedPos != state.playheadUs) {
                            TimelineEvaluator.activeClipsAt(seq, updatedPos)
                        } else {
                            state.activeClips
                        },
                        renderGraph = updatedGraph,
                        playbackDiagnostics = snapshot,
                    )
                }
            }
        }
        viewModelScope.launch {
            compositionRenderer.renderTelemetry.collect { renderSnapshot ->
                onPublishRenderTelemetry(renderSnapshot)
                _uiState.update { it.copy(renderDiagnostics = renderSnapshot) }
            }
        }
        audioEngine?.let { engine ->
            viewModelScope.launch {
                engine.diagnostics.collect { audioSnapshot ->
                    onPublishAudioTelemetry(audioSnapshot)
                    _uiState.update { it.copy(audioDiagnostics = audioSnapshot) }
                }
            }
        }
        cacheManager?.let { cm ->
            viewModelScope.launch {
                cm.diagnostics.collect { cacheSnap ->
                    _uiState.update {
                        it.copy(
                            cacheDiagnostics = cacheSnap,
                            proxyEnabled = cacheSnap.proxyEnabled,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            projectRepository.persistenceDiagnostics.collect { persistenceSnap ->
                _uiState.update {
                    it.copy(
                        persistenceDiagnostics = persistenceSnap,
                        saveStatus = persistenceSnap.saveStatus,
                    )
                }
            }
        }
        exportCoordinator?.let { exporter ->
            viewModelScope.launch {
                exporter.progress.collect { exportSnap ->
                    _uiState.update { it.copy(exportProgress = exportSnap) }
                }
            }
            viewModelScope.launch {
                exporter.diagnostics.collect { diagSnap ->
                    _uiState.update {
                        it.copy(
                            exportDiagnostics = diagSnap,
                            exportCodecCapabilities = diagSnap.codecCapabilities,
                        )
                    }
                }
            }
        }
        loadProject(isReload = false)
    }

    fun reloadProject() {
        loadProject(isReload = true)
    }

    private fun loadProject(isReload: Boolean) {
        viewModelScope.launch {
            val initResult = engineSession.initialize()
            val mode = initResult.getOrNull()?.backendMode ?: EngineBackendMode.JVM_CONTRACT_FALLBACK
            val caps = runCatching { engineSession.queryDeviceCapabilities() }.getOrNull()

            when (val loaded = projectRepository.loadProjectWithDetails(projectId)) {
                is EngineResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadError = loaded.error,
                            error = loaded.error,
                            saveStatus = PersistenceSaveStatus.FAILED,
                            backendMode = mode,
                            capabilities = caps,
                        )
                    }
                }
                is EngineResult.Success -> {
                    val details = loaded.value
                    var doc = details.document
                    if (doc.assets.isNotEmpty()) {
                        val revalidated = mediaInspector.revalidateAssets(doc.assets)
                        if (revalidated != doc.assets) {
                            doc = when (val persisted = projectRepository.updateProjectAssetStatuses(doc.projectId, revalidated)) {
                                is EngineResult.Success -> persisted.value
                                is EngineResult.Failure -> doc.copy(assets = revalidated)
                            }
                        }
                    }

                    applyLoadedDocument(
                        doc = doc,
                        recoverySource = details.recoverySource,
                        migratedFromVersion = details.migratedFromVersion,
                        recoveredCommandLabels = details.recoveredCommandLabels,
                        hasCommittedBaseline = details.hasCommittedBaseline,
                        mode = mode,
                        caps = caps,
                        isReload = isReload,
                    )
                }
            }
        }
    }

    private suspend fun applyLoadedDocument(
        doc: ProjectDocument,
        recoverySource: RecoverySource,
        migratedFromVersion: Int?,
        recoveredCommandLabels: List<String>,
        hasCommittedBaseline: Boolean,
        mode: EngineBackendMode,
        caps: DeviceCapabilities?,
        isReload: Boolean,
    ) {
        document = doc
        // Enforce session-scoped undo policy across process restarts
        history = CommandHistory(doc.sequence, undoPolicy = UndoHistoryPolicy.RESET_ON_PROCESS_RESTART)

        if (nativeProjectId > 0L) {
            engineSession.closeProject(nativeProjectId)
            nativeProjectId = 0L
        }
        val openRes = engineSession.openProject(doc)
        if (openRes is EngineResult.Success) {
            nativeProjectId = openRes.value
        }

        val initialSelection = _uiState.value.selectedClipId?.takeIf { id ->
            isReload && doc.sequence.tracks.any { t -> t.clips.any { c -> c.id == id } }
        } ?: doc.sequence.tracks
            .firstOrNull { it.type == TrackType.VIDEO }
            ?.clips
            ?.firstOrNull()
            ?.id
            ?: doc.sequence.tracks.flatMap { it.clips }.firstOrNull()?.id

        val targetPlayhead = if (isReload) {
            _uiState.value.playheadUs.coerceIn(0L, doc.sequence.durationUs.coerceAtLeast(0L))
        } else {
            0L
        }

        playbackController.updateSequence(doc.sequence)
        playbackController.seekTo(targetPlayhead, frameAccurate = true)
        scheduleProxiesForAssets(doc.assets)
        val initialGraph = RenderGraphEvaluator.evaluate(doc.sequence, targetPlayhead)
        val initialWaveforms = buildWaveformMap(doc.sequence)

        val showBanner = recoverySource == RecoverySource.RECOVERY_SNAPSHOT ||
            recoverySource == RecoverySource.BACKUP_FILE

        _uiState.update {
            it.copy(
                isLoading = false,
                loadError = null,
                projectTitle = doc.title,
                sequence = doc.sequence,
                assets = doc.assets,
                activeClips = TimelineEvaluator.activeClipsAt(doc.sequence, targetPlayhead),
                renderGraph = initialGraph,
                waveforms = initialWaveforms,
                selectedClipId = initialSelection,
                playheadUs = targetPlayhead,
                canUndo = false,
                canRedo = false,
                backendMode = mode,
                capabilities = caps,
                recoverySource = recoverySource,
                showRecoveryBanner = showBanner,
                hasCommittedBaseline = hasCommittedBaseline,
                recoveredCommandLabels = recoveredCommandLabels,
                migratedFromVersion = migratedFromVersion,
                hasUnsavedChanges = recoverySource == RecoverySource.RECOVERY_SNAPSHOT,
                saveStatus = when (recoverySource) {
                    RecoverySource.RECOVERY_SNAPSHOT -> PersistenceSaveStatus.RECOVERED_SNAPSHOT
                    RecoverySource.BACKUP_FILE -> PersistenceSaveStatus.RECOVERED_BACKUP
                    else -> PersistenceSaveStatus.SAVED
                },
            )
        }
    }

    fun dismissRecoveryBanner() {
        _uiState.update { it.copy(showRecoveryBanner = false) }
        viewModelScope.launch {
            commitCurrentDocumentNow()
        }
    }

    fun revertToCommittedBaseline() {
        viewModelScope.launch {
            snapshotDebounceJob?.cancel()
            autosaveDebounceJob?.cancel()
            when (val reverted = projectRepository.discardRecoverySnapshot(projectId)) {
                is EngineResult.Failure -> _uiState.update { it.copy(error = reverted.error) }
                is EngineResult.Success -> {
                    val details = reverted.value
                    applyLoadedDocument(
                        doc = details.document,
                        recoverySource = details.recoverySource,
                        migratedFromVersion = details.migratedFromVersion,
                        recoveredCommandLabels = emptyList(),
                        hasCommittedBaseline = details.hasCommittedBaseline,
                        mode = _uiState.value.backendMode,
                        caps = _uiState.value.capabilities,
                        isReload = false,
                    )
                }
            }
        }
    }

    fun onLifecyclePauseOrStop() {
        val currentDoc = document ?: return
        if (!_uiState.value.hasUnsavedChanges) return
        val currentSeq = history?.currentSequence ?: currentDoc.sequence
        val trail = history?.recentCommandLabels().orEmpty()
        viewModelScope.launch {
            projectRepository.saveRecoverySnapshot(currentDoc.copy(sequence = currentSeq), trail)
        }
    }

    fun triggerAutosaveNow() {
        viewModelScope.launch {
            commitCurrentDocumentNow()
        }
    }

    fun relinkAsset(oldAssetId: String, newUriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRelinking = true) }
            when (val inspected = mediaInspector.relinkAsset(oldAssetId, newUriString)) {
                is EngineResult.Failure -> {
                    _uiState.update { it.copy(isRelinking = false, error = inspected.error) }
                }
                is EngineResult.Success -> {
                    when (val relinked = projectRepository.relinkProjectAsset(projectId, oldAssetId, inspected.value)) {
                        is EngineResult.Failure -> {
                            _uiState.update { it.copy(isRelinking = false, error = relinked.error) }
                        }
                        is EngineResult.Success -> {
                            val updatedDoc = relinked.value
                            document = updatedDoc
                            history = CommandHistory(updatedDoc.sequence, UndoHistoryPolicy.RESET_ON_PROCESS_RESTART)
                            val playhead = _uiState.value.playheadUs.coerceIn(0L, updatedDoc.sequence.durationUs.coerceAtLeast(0L))
                            playbackController.updateSequence(updatedDoc.sequence)
                            playbackController.seekTo(playhead, frameAccurate = true)
                            scheduleProxiesForAssets(updatedDoc.assets)
                            val updatedGraph = RenderGraphEvaluator.evaluate(updatedDoc.sequence, playhead)
                            _uiState.update {
                                it.copy(
                                    isRelinking = false,
                                    sequence = updatedDoc.sequence,
                                    assets = updatedDoc.assets,
                                    activeClips = TimelineEvaluator.activeClipsAt(updatedDoc.sequence, playhead),
                                    renderGraph = updatedGraph,
                                    playheadUs = playhead,
                                    canUndo = false,
                                    canRedo = false,
                                    hasUnsavedChanges = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun attachPreviewSurface(surface: Surface, width: Int, height: Int) {
        playbackController.attachPreviewSurface(surface, width, height)
    }

    fun detachPreviewSurface(surface: Surface) {
        playbackController.detachPreviewSurface(surface)
    }

    fun selectClip(clipId: String?) {
        _uiState.update { it.copy(selectedClipId = clipId) }
    }

    fun toggleFullscreen() {
        _uiState.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun jumpToStart() {
        pausePlayback()
        scrubTo(0L)
    }

    fun scrubTo(positionUs: Long) {
        val seq = _uiState.value.sequence ?: return
        val maxDur = if (seq.durationUs > 0L) seq.durationUs else 4_000_000L
        val clamped = seq.frameRate.snapToFrame(positionUs.coerceIn(0L, maxDur))
        playbackController.seekTo(clamped, frameAccurate = true)
        val graph = RenderGraphEvaluator.evaluate(seq, clamped)
        _uiState.update {
            it.copy(
                playheadUs = clamped,
                activeClips = TimelineEvaluator.activeClipsAt(seq, clamped),
                renderGraph = graph,
            )
        }
        viewModelScope.launch {
            if (nativeProjectId > 0L) {
                engineSession.seek(nativeProjectId, clamped)
            }
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val seq = _uiState.value.sequence ?: return
        val duration = seq.durationUs.coerceAtLeast(1_000_000L)
        if (_uiState.value.playheadUs >= duration) {
            scrubTo(0L)
        }
        playbackController.play()
        _uiState.update { it.copy(isPlaying = true) }
        viewModelScope.launch {
            if (nativeProjectId > 0L) engineSession.play(nativeProjectId)
        }
    }

    fun pausePlayback() {
        playbackController.pause()
        _uiState.update { it.copy(isPlaying = false) }
        viewModelScope.launch {
            if (nativeProjectId > 0L) engineSession.pause(nativeProjectId)
        }
    }

    fun stepFrame(forward: Boolean) {
        val seq = _uiState.value.sequence ?: return
        val next = playbackController.stepFrame(forward)
        val graph = RenderGraphEvaluator.evaluate(seq, next)
        _uiState.update {
            it.copy(
                isPlaying = false,
                playheadUs = next,
                activeClips = TimelineEvaluator.activeClipsAt(seq, next),
                renderGraph = graph,
            )
        }
        viewModelScope.launch {
            if (nativeProjectId > 0L) {
                engineSession.pause(nativeProjectId)
                engineSession.seek(nativeProjectId, next)
            }
        }
    }

    fun adjustZoom(delta: Float) {
        _uiState.update { it.copy(zoomScale = (it.zoomScale + delta).coerceIn(0.4f, 3.0f)) }
    }

    fun setZoom(scale: Float) {
        _uiState.update { it.copy(zoomScale = scale.coerceIn(0.4f, 3.0f)) }
    }

    fun resetZoom() {
        _uiState.update { it.copy(zoomScale = 1.0f) }
    }

    fun addTextClip() {
        val seq = _uiState.value.sequence ?: return
        val textTrack = seq.tracks.firstOrNull { it.type == TrackType.TEXT } ?: return
        var startUs = seq.frameRate.snapToFrame(_uiState.value.playheadUs)
        val durationUs = 3_000_000L
        val sortedTextClips = textTrack.clips.sortedBy { it.timing.timelineStartUs }
        for (existing in sortedTextClips) {
            val candidateEnd = startUs + durationUs
            if (startUs < existing.timing.timelineEndUs && existing.timing.timelineStartUs < candidateEnd) {
                startUs = seq.frameRate.snapToFrame(existing.timing.timelineEndUs)
            }
        }
        val clipId = "clip_txt_${UUID.randomUUID().toString().take(6)}"
        val newClip = Clip(
            id = clipId,
            trackId = textTrack.id,
            label = "New Title",
            timing = ClipTiming(
                timelineStartUs = startUs,
                sourceInUs = 0L,
                sourceOutUs = durationUs,
            ),
            transform = LayerTransform(normalizedX = 0.5f, normalizedY = 0.5f),
            textSpec = TextClipSpec(
                text = "New Title",
                fontId = "space_grotesk",
                colorArgb = 0xFF00E5FFL,
                fontSizeSp = 28f,
                animationPreset = TextAnimationPreset.FADE_IN,
            ),
            accentColorArgb = 0xFF8B5CF6L,
        )
        executeCommand(EditCommand.AddClip(textTrack.id, newClip), selectClipId = clipId)
    }

    fun addAudioClip() {
        val seq = _uiState.value.sequence ?: return
        val audioTrack = seq.tracks.firstOrNull { it.type == TrackType.AUDIO } ?: return
        val endUs = audioTrack.clips.maxOfOrNull { it.timing.timelineEndUs } ?: 0L
        val clipId = "clip_aud_${UUID.randomUUID().toString().take(6)}"
        val count = audioTrack.clips.size + 1
        val newClip = Clip(
            id = clipId,
            trackId = audioTrack.id,
            label = "Studio Beat $count",
            timing = ClipTiming(
                timelineStartUs = endUs,
                sourceInUs = 0L,
                sourceOutUs = 4_000_000L,
            ),
            audioSpec = AudioClipSpec(gainDb = -2.0f),
            accentColorArgb = 0xFF14B8A6L,
        )
        executeCommand(EditCommand.AddClip(audioTrack.id, newClip), selectClipId = clipId)
    }

    fun moveClip(clipId: String, newTimelineStartUs: Long) {
        executeCommand(EditCommand.MoveClip(clipId, newTimelineStartUs), selectClipId = clipId)
    }

    fun trimClip(clipId: String, newStartUs: Long, newSourceInUs: Long, newSourceOutUs: Long) {
        executeCommand(
            EditCommand.TrimClip(clipId, newStartUs, newSourceInUs, newSourceOutUs),
            selectClipId = clipId,
        )
    }

    fun nudgeSelectedClipEnd(deltaUs: Long) {
        val clip = _uiState.value.selectedClip ?: _uiState.value.primaryVideoClip ?: return
        val newOut = (clip.timing.sourceOutUs + deltaUs).coerceAtLeast(clip.timing.sourceInUs + 500_000L)
        executeCommand(
            EditCommand.TrimClip(
                clipId = clip.id,
                newTimelineStartUs = clip.timing.timelineStartUs,
                newSourceInUs = clip.timing.sourceInUs,
                newSourceOutUs = newOut,
            ),
            selectClipId = clip.id,
        )
    }

    fun splitSelectedClip() {
        val clipId = _uiState.value.selectedClipId ?: _uiState.value.primaryVideoClip?.id ?: return
        val rightId = "clip_split_${UUID.randomUUID().toString().take(6)}"
        executeCommand(
            EditCommand.SplitClip(clipId, _uiState.value.playheadUs, rightId),
            selectClipId = rightId,
        )
    }

    fun deleteSelectedClip() {
        val clipId = _uiState.value.selectedClipId ?: return
        executeCommand(EditCommand.DeleteClip(clipId), selectClipId = null)
    }

    fun updateTransform(clipId: String, transform: LayerTransform) {
        executeCommand(EditCommand.UpdateTransform(clipId, transform), selectClipId = clipId)
    }

    fun updateSelectedClipTransform(mutate: (LayerTransform) -> LayerTransform) {
        val target = _uiState.value.selectedClip ?: _uiState.value.primaryVideoClip ?: return
        executeCommand(
            EditCommand.UpdateTransform(target.id, mutate(target.transform)),
            selectClipId = target.id,
        )
    }

    fun setSelectedClipCrop(crop: CropRect) {
        updateSelectedClipTransform { it.copy(crop = crop.normalized()) }
    }

    fun setSelectedClipBlendMode(blendMode: BlendMode) {
        updateSelectedClipTransform { it.copy(blendMode = blendMode) }
    }

    fun setSelectedClipOpacity(opacity: Float) {
        updateSelectedClipTransform { it.copy(opacity = opacity.coerceIn(0f, 1f)) }
    }

    fun rotateSelectedClipQuarterTurn() {
        updateSelectedClipTransform { it.copy(rotationDegrees = (it.rotationDegrees + 90f) % 360f) }
    }

    fun updateSelectedTextSpec(transform: (TextClipSpec) -> TextClipSpec) {
        val clip = _uiState.value.selectedClip ?: return
        val spec = clip.textSpec ?: return
        executeCommand(EditCommand.UpdateTextSpec(clip.id, transform(spec)), selectClipId = clip.id)
    }

    fun setFilter(preset: ColorFilterPreset) {
        val target = _uiState.value.primaryVideoClip ?: _uiState.value.selectedClip ?: return
        executeCommand(EditCommand.UpdateFilter(target.id, preset), selectClipId = target.id)
    }

    fun setEffect(preset: VisualEffectPreset) {
        val target = _uiState.value.primaryVideoClip ?: _uiState.value.selectedClip ?: return
        executeCommand(EditCommand.UpdateEffect(target.id, preset), selectClipId = target.id)
    }

    fun setSelectedClipTransition(preset: TransitionPreset) {
        val target = _uiState.value.selectedClip ?: _uiState.value.primaryVideoClip ?: return
        executeCommand(
            EditCommand.UpdateTransition(target.id, TransitionSpec(preset = preset, durationUs = 500_000L)),
            selectClipId = target.id,
        )
    }

    fun updateAudioSpec(clipId: String, spec: AudioClipSpec) {
        executeCommand(EditCommand.UpdateAudioSpec(clipId, spec), selectClipId = clipId)
    }

    fun toggleProxyPlayback() {
        val cm = cacheManager ?: return
        val next = !cm.proxyEnabled.value
        cm.setProxyPlaybackEnabled(next)
        _uiState.update { it.copy(proxyEnabled = next) }
        playbackController.seekTo(_uiState.value.playheadUs, frameAccurate = true)
    }

    private fun scheduleProxiesForAssets(assets: List<MediaAsset>) {
        val cm = cacheManager ?: return
        assets.forEach { asset ->
            val uri = asset.uriString
            if (asset.mediaKind == com.vyro.core.model.MediaKind.VIDEO &&
                asset.availability == AssetAvailability.AVAILABLE &&
                !uri.isNullOrBlank()
            ) {
                cm.ensureProxyForAsset(
                    assetId = asset.assetId,
                    originalUri = uri,
                    width = asset.width.coerceAtLeast(540),
                    height = asset.height.coerceAtLeast(960),
                )
            }
        }
    }

    fun setCanvasAspect(preset: AspectRatioPreset) {
        executeCommand(EditCommand.UpdateCanvasAspect(preset), selectClipId = _uiState.value.selectedClipId)
    }

    fun updateCover(coverSpec: CoverSpec) {
        executeCommand(EditCommand.UpdateCover(coverSpec), selectClipId = _uiState.value.selectedClipId)
    }

    fun toggleMuteClipAudio() {
        val seq = _uiState.value.sequence ?: return
        val audioTrack = seq.tracks.firstOrNull { it.type == TrackType.AUDIO }
        val targetTrack = if (audioTrack != null && audioTrack.clips.isNotEmpty()) {
            audioTrack
        } else {
            seq.tracks.firstOrNull { it.type == TrackType.VIDEO }
        } ?: return
        executeCommand(EditCommand.ToggleTrackMute(targetTrack.id), selectClipId = _uiState.value.selectedClipId)
    }

    fun toggleSelectedKeyframe() {
        val clipId = _uiState.value.selectedClipId ?: _uiState.value.primaryVideoClip?.id ?: return
        executeCommand(EditCommand.ToggleKeyframe(clipId), selectClipId = clipId)
    }

    fun addOrUpdateKeyframeAtPlayhead(scaleOverride: Float? = null) {
        val target = _uiState.value.keyframeTargetClip ?: return
        val offsetUs = (_uiState.value.playheadUs - target.timing.timelineStartUs)
            .coerceIn(0L, target.timing.timelineDurationUs.coerceAtLeast(0L))
        val evaluated = RenderGraphEvaluator.resolveClipTransform(target, offsetUs)
        val keyframe = TransformKeyframe(
            offsetUs = offsetUs,
            normalizedX = evaluated.normalizedX,
            normalizedY = evaluated.normalizedY,
            scale = (scaleOverride ?: evaluated.scale).coerceIn(0.35f, 3.0f),
            rotationDegrees = evaluated.rotationDegrees,
            opacity = evaluated.opacity,
        )
        executeCommand(EditCommand.AddOrUpdateKeyframe(target.id, keyframe), selectClipId = target.id)
    }

    fun stepKeyframeScale(delta: Float = 0.20f) {
        val target = _uiState.value.keyframeTargetClip ?: return
        val offsetUs = (_uiState.value.playheadUs - target.timing.timelineStartUs)
            .coerceIn(0L, target.timing.timelineDurationUs.coerceAtLeast(0L))
        val evaluated = RenderGraphEvaluator.resolveClipTransform(target, offsetUs)
        val nextScale = if (evaluated.scale + delta > 2.0f) 1.0f else (evaluated.scale + delta).coerceIn(0.35f, 3.0f)
        addOrUpdateKeyframeAtPlayhead(scaleOverride = nextScale)
    }

    fun removeKeyframeAtPlayhead() {
        val target = _uiState.value.keyframeTargetClip ?: return
        val offsetUs = (_uiState.value.playheadUs - target.timing.timelineStartUs)
            .coerceIn(0L, target.timing.timelineDurationUs.coerceAtLeast(0L))
        executeCommand(EditCommand.RemoveKeyframe(target.id, offsetUs), selectClipId = target.id)
    }

    fun jumpToAdjacentKeyframe(forward: Boolean) {
        val target = _uiState.value.keyframeTargetClip ?: return
        if (target.keyframes.isEmpty()) return
        val localOffsetUs = (_uiState.value.playheadUs - target.timing.timelineStartUs)
            .coerceIn(0L, target.timing.timelineDurationUs.coerceAtLeast(0L))
        val tolerance = (_uiState.value.sequence?.frameRate?.frameDurationUs ?: 33_333L).coerceAtLeast(33_333L)
        val candidate = if (forward) {
            target.keyframes.firstOrNull { it.offsetUs > localOffsetUs + tolerance } ?: target.keyframes.last()
        } else {
            target.keyframes.lastOrNull { it.offsetUs < localOffsetUs - tolerance } ?: target.keyframes.first()
        }
        scrubTo(target.timing.timelineStartUs + candidate.offsetUs)
    }

    fun toggleTrackMute(trackId: String) {
        executeCommand(EditCommand.ToggleTrackMute(trackId), selectClipId = _uiState.value.selectedClipId)
    }

    fun toggleTrackLock(trackId: String) {
        executeCommand(EditCommand.ToggleTrackLock(trackId), selectClipId = _uiState.value.selectedClipId)
    }

    fun toggleSelectedTrackLock() {
        val track = _uiState.value.selectedTrack ?: return
        toggleTrackLock(track.id)
    }

    fun notifyLockedTrackInteraction(trackId: String) {
        val trackName = _uiState.value.sequence?.tracks?.firstOrNull { it.id == trackId }?.name ?: trackId
        _uiState.update {
            it.copy(
                error = EngineError(
                    code = EngineErrorCode.INVALID_TIMELINE_EDIT,
                    sourceModule = "feature:editor",
                    userMessage = "Track \"$trackName\" is locked. Unlock it to move or trim clips.",
                    diagnosticDetail = "Gesture blocked on locked track $trackId",
                    recoverable = true,
                ),
            )
        }
    }

    fun undo() {
        val hist = history ?: return
        val prev = hist.undo() ?: return
        applySequenceState(prev, _uiState.value.selectedClipId)
        scheduleSnapshotAndAutosave(prev, hist)
    }

    fun redo() {
        val hist = history ?: return
        val next = hist.redo() ?: return
        applySequenceState(next, _uiState.value.selectedClipId)
        scheduleSnapshotAndAutosave(next, hist)
    }

    private fun executeCommand(command: EditCommand, selectClipId: String?) {
        val hist = history ?: return
        when (val result = TimelineEvaluator.apply(hist.currentSequence, command)) {
            is EngineResult.Failure -> {
                _uiState.update { it.copy(error = result.error) }
            }
            is EngineResult.Success -> {
                hist.commit(result.value, command.commandName)
                applySequenceState(result.value, selectClipId)
                viewModelScope.launch {
                    if (nativeProjectId > 0L) {
                        engineSession.submitCommand(nativeProjectId, command)
                    }
                }
                scheduleSnapshotAndAutosave(result.value, hist)
            }
        }
    }

    private fun scheduleSnapshotAndAutosave(seq: Sequence, hist: CommandHistory) {
        val doc = document ?: return
        val updated = doc.copy(
            sequence = seq,
            revision = doc.revision + 1L,
        )
        document = updated
        _uiState.update { it.copy(hasUnsavedChanges = true, saveStatus = PersistenceSaveStatus.DIRTY) }
        projectRepository.updatePersistenceRuntimeStats(
            saveStatus = PersistenceSaveStatus.DIRTY,
            hasUnsavedChanges = true,
            undoDepth = hist.undoDepth,
            redoDepth = hist.redoDepth,
        )

        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = viewModelScope.launch {
            delay(SNAPSHOT_DEBOUNCE_MS)
            projectRepository.saveRecoverySnapshot(updated, hist.recentCommandLabels())
        }

        autosaveDebounceJob?.cancel()
        autosaveDebounceJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            commitCurrentDocumentNow()
        }
    }

    private suspend fun commitCurrentDocumentNow() {
        val currentDoc = document ?: return
        val hist = history
        val currentSeq = hist?.currentSequence ?: currentDoc.sequence
        val targetDoc = currentDoc.copy(sequence = currentSeq)
        _uiState.update { it.copy(saveStatus = PersistenceSaveStatus.AUTOSAVING) }
        projectRepository.updatePersistenceRuntimeStats(
            saveStatus = PersistenceSaveStatus.AUTOSAVING,
            hasUnsavedChanges = true,
            undoDepth = hist?.undoDepth ?: 0,
            redoDepth = hist?.redoDepth ?: 0,
        )
        when (projectRepository.saveProject(targetDoc)) {
            is EngineResult.Success -> {
                document = targetDoc
                _uiState.update {
                    it.copy(
                        hasUnsavedChanges = false,
                        saveStatus = PersistenceSaveStatus.SAVED,
                    )
                }
            }
            is EngineResult.Failure -> {
                _uiState.update { it.copy(saveStatus = PersistenceSaveStatus.FAILED) }
            }
        }
    }

    private fun applySequenceState(nextSeq: Sequence, preferredClipId: String?) {
        val hist = history
        val resolvedClipId = preferredClipId?.takeIf { id ->
            nextSeq.tracks.any { t -> t.clips.any { c -> c.id == id } }
        }
        playbackController.updateSequence(nextSeq)
        val playhead = _uiState.value.playheadUs
        val nextGraph = RenderGraphEvaluator.evaluate(nextSeq, playhead)
        val nextWaveforms = buildWaveformMap(nextSeq)
        _uiState.update {
            it.copy(
                sequence = nextSeq,
                activeClips = TimelineEvaluator.activeClipsAt(nextSeq, playhead),
                renderGraph = nextGraph,
                waveforms = nextWaveforms,
                selectedClipId = resolvedClipId,
                canUndo = hist?.canUndo == true,
                canRedo = hist?.canRedo == true,
            )
        }
    }

    private fun buildWaveformMap(seq: Sequence): Map<String, WaveformProfile> {
        val src = audioSource ?: return emptyMap()
        val result = LinkedHashMap<String, WaveformProfile>()
        seq.tracks.forEach { track ->
            track.clips.forEach { clip ->
                if (AudioMixEngine.isAudioBearingClip(track.type, clip)) {
                    result[clip.id] = src.extractWaveform(clip, bucketCount = 40)
                }
            }
        }
        return result
    }

    suspend fun saveBeforeExit() {
        pausePlayback()
        snapshotDebounceJob?.cancel()
        autosaveDebounceJob?.cancel()
        commitCurrentDocumentNow()
        if (nativeProjectId > 0L) {
            engineSession.closeProject(nativeProjectId)
            nativeProjectId = 0L
        }
    }

    fun prepareExportSheet() {
        val doc = document ?: return
        val currentSeq = history?.currentSequence ?: doc.sequence
        val updatedDoc = doc.copy(sequence = currentSeq)
        val existing = _uiState.value.exportConfig
        val config = if (existing == null || existing.projectId != updatedDoc.projectId) {
            ExportConfig.fromSequence(updatedDoc)
        } else {
            val (w, h) = ExportConfig.computeAlignedDimensions(
                srcWidth = currentSeq.width.coerceAtLeast(16),
                srcHeight = currentSeq.height.coerceAtLeast(16),
                preset = existing.resolutionPreset,
            )
            existing.copy(
                outputTitle = updatedDoc.title,
                width = w,
                height = h,
                frameRate = currentSeq.frameRate,
            )
        }
        _uiState.update { it.copy(exportConfig = config) }
        exportCoordinator?.let { exporter ->
            viewModelScope.launch {
                val report = exporter.queryCodecCapabilities()
                _uiState.update { it.copy(exportCodecCapabilities = report) }
            }
        }
    }

    fun selectExportPreset(preset: ExportResolutionPreset) {
        val seq = _uiState.value.sequence ?: return
        val current = _uiState.value.exportConfig ?: return
        val (w, h) = ExportConfig.computeAlignedDimensions(
            srcWidth = seq.width.coerceAtLeast(16),
            srcHeight = seq.height.coerceAtLeast(16),
            preset = preset,
        )
        _uiState.update {
            it.copy(
                exportConfig = current.copy(
                    resolutionPreset = preset,
                    width = w,
                    height = h,
                    videoBitrateBps = ExportConfig.defaultBitrateForPreset(preset),
                ),
            )
        }
    }

    fun selectExportCodec(codec: ExportVideoCodec) {
        val current = _uiState.value.exportConfig ?: return
        _uiState.update { it.copy(exportConfig = current.copy(videoCodec = codec)) }
    }

    fun toggleExportIncludeAudio(enabled: Boolean) {
        val current = _uiState.value.exportConfig ?: return
        _uiState.update { it.copy(exportConfig = current.copy(includeAudio = enabled)) }
    }

    fun toggleExportSaveToGallery(enabled: Boolean) {
        val current = _uiState.value.exportConfig ?: return
        _uiState.update { it.copy(exportConfig = current.copy(saveToGallery = enabled)) }
    }

    fun startExport() {
        val exporter = exportCoordinator ?: return
        val doc = document ?: return
        val currentSeq = history?.currentSequence ?: doc.sequence
        val targetDoc = doc.copy(sequence = currentSeq)
        val config = _uiState.value.exportConfig ?: ExportConfig.fromSequence(targetDoc)
        pausePlayback()
        exporter.startExport(targetDoc, config)
    }

    fun cancelExport() {
        exportCoordinator?.cancelExport()
    }

    fun resetExportState() {
        exportCoordinator?.resetCompletedState()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        snapshotDebounceJob?.cancel()
        autosaveDebounceJob?.cancel()
        exportCoordinator?.cancelExport()
        playbackController.release()
        compositionRenderer.release()
        if (nativeProjectId > 0L) {
            VyroLogger.log(LogSeverity.DEBUG, "feature:editor", "EditorViewModel cleared", projectId)
        }
        super.onCleared()
    }

    class Factory(
        private val projectId: String,
        private val projectRepository: ProjectRepository,
        private val engineSession: EngineSessionContract,
        private val mediaInspector: MediaInspectorContract,
        private val playbackController: PlaybackControllerContract,
        private val compositionRenderer: CompositionRendererContract,
        private val audioSource: AudioSourceContract? = null,
        private val audioEngine: AudioPlaybackEngineContract? = null,
        private val cacheManager: CacheAndProxyManagerContract? = null,
        private val exportCoordinator: ExportCoordinatorContract? = null,
        private val onPublishTelemetry: (PlaybackDiagnosticsSnapshot) -> Unit = {},
        private val onPublishRenderTelemetry: (RenderDiagnosticsSnapshot) -> Unit = {},
        private val onPublishAudioTelemetry: (AudioDiagnosticsSnapshot) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EditorViewModel(
                projectId = projectId,
                projectRepository = projectRepository,
                engineSession = engineSession,
                mediaInspector = mediaInspector,
                playbackController = playbackController,
                compositionRenderer = compositionRenderer,
                audioSource = audioSource,
                audioEngine = audioEngine,
                cacheManager = cacheManager,
                exportCoordinator = exportCoordinator,
                onPublishTelemetry = onPublishTelemetry,
                onPublishRenderTelemetry = onPublishRenderTelemetry,
                onPublishAudioTelemetry = onPublishAudioTelemetry,
            ) as T
        }
    }

    companion object {
        private const val SNAPSHOT_DEBOUNCE_MS = 300L
        private const val AUTOSAVE_DEBOUNCE_MS = 2_000L
    }
}

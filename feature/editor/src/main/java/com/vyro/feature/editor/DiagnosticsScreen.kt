package com.vyro.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vyro.core.common.EngineResult
import com.vyro.core.common.StructuredLogEntry
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.AudioDiagnosticsSnapshot
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.CacheMemoryDiagnosticsSnapshot
import com.vyro.core.contracts.DeviceCapabilities
import com.vyro.core.contracts.EngineBackendMode
import com.vyro.core.contracts.EngineSessionContract
import com.vyro.core.contracts.EngineTelemetry
import com.vyro.core.contracts.ExportCoordinatorContract
import com.vyro.core.contracts.ExportDiagnosticsSnapshot
import com.vyro.core.contracts.ExportPhase
import com.vyro.core.contracts.HardeningDiagnosticsSnapshot
import com.vyro.core.contracts.MemoryPressureLevel
import com.vyro.core.contracts.PersistenceDiagnosticsSnapshot
import com.vyro.core.contracts.PersistenceSaveStatus
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.ProductionHardeningSuiteReport
import com.vyro.core.contracts.ProductionHardeningVerifier
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.contracts.RenderDiagnosticsSnapshot
import com.vyro.core.contracts.RuntimeHardeningMonitorContract
import com.vyro.core.contracts.SmokeTestReport
import com.vyro.core.contracts.ThermalStatusLevel
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.designsystem.VyroStudioPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnosticsUiState(
    val telemetry: EngineTelemetry? = null,
    val capabilities: DeviceCapabilities? = null,
    val playbackTelemetry: PlaybackDiagnosticsSnapshot = PlaybackDiagnosticsSnapshot(),
    val renderTelemetry: RenderDiagnosticsSnapshot = RenderDiagnosticsSnapshot(),
    val audioTelemetry: AudioDiagnosticsSnapshot = AudioDiagnosticsSnapshot(),
    val cacheTelemetry: CacheMemoryDiagnosticsSnapshot = CacheMemoryDiagnosticsSnapshot(),
    val persistenceTelemetry: PersistenceDiagnosticsSnapshot = PersistenceDiagnosticsSnapshot(),
    val exportTelemetry: ExportDiagnosticsSnapshot = ExportDiagnosticsSnapshot(),
    val hardeningTelemetry: HardeningDiagnosticsSnapshot = HardeningDiagnosticsSnapshot(),
    val smokeTestReport: SmokeTestReport? = null,
    val isRunningSmoke: Boolean = false,
    val hardeningReport: ProductionHardeningSuiteReport? = null,
    val isRunningHardeningSuite: Boolean = false,
    val recentLogs: List<StructuredLogEntry> = emptyList(),
)

class DiagnosticsViewModel(
    private val engineSession: EngineSessionContract,
    private val playbackTelemetryFlow: StateFlow<PlaybackDiagnosticsSnapshot> = MutableStateFlow(PlaybackDiagnosticsSnapshot()),
    private val renderTelemetryFlow: StateFlow<RenderDiagnosticsSnapshot> = MutableStateFlow(RenderDiagnosticsSnapshot()),
    private val audioTelemetryFlow: StateFlow<AudioDiagnosticsSnapshot> = MutableStateFlow(AudioDiagnosticsSnapshot()),
    private val cacheManager: CacheAndProxyManagerContract? = null,
    private val projectRepository: ProjectRepository? = null,
    private val exportCoordinator: ExportCoordinatorContract? = null,
    private val runtimeHardeningMonitor: RuntimeHardeningMonitorContract? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackTelemetryFlow.collect { snapshot ->
                _uiState.update { it.copy(playbackTelemetry = snapshot) }
            }
        }
        viewModelScope.launch {
            renderTelemetryFlow.collect { renderSnapshot ->
                _uiState.update { it.copy(renderTelemetry = renderSnapshot) }
            }
        }
        viewModelScope.launch {
            audioTelemetryFlow.collect { audioSnapshot ->
                _uiState.update { it.copy(audioTelemetry = audioSnapshot) }
            }
        }
        cacheManager?.let { cm ->
            viewModelScope.launch {
                cm.diagnostics.collect { cacheSnap ->
                    _uiState.update { it.copy(cacheTelemetry = cacheSnap) }
                }
            }
        }
        projectRepository?.let { repo ->
            viewModelScope.launch {
                repo.persistenceDiagnostics.collect { persistenceSnap ->
                    _uiState.update { it.copy(persistenceTelemetry = persistenceSnap) }
                }
            }
        }
        exportCoordinator?.let { exporter ->
            viewModelScope.launch {
                exporter.diagnostics.collect { exportSnap ->
                    _uiState.update { it.copy(exportTelemetry = exportSnap) }
                }
            }
        }
        runtimeHardeningMonitor?.let { monitor ->
            viewModelScope.launch {
                monitor.snapshot.collect { hardeningSnap ->
                    _uiState.update { it.copy(hardeningTelemetry = hardeningSnap) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runtimeHardeningMonitor?.refreshSystemTelemetry()
            engineSession.initialize()
            val t = engineSession.telemetry()
            val caps = engineSession.queryDeviceCapabilities()
            _uiState.update {
                it.copy(
                    telemetry = t,
                    capabilities = caps,
                    recentLogs = VyroLogger.recentEntries().asReversed().take(20),
                )
            }
        }
    }

    fun runSmokeTest() {
        if (_uiState.value.isRunningSmoke) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningSmoke = true) }
            val result = engineSession.runLifecycleSmokeTest(iterations = 50)
            val t = engineSession.telemetry()
            _uiState.update {
                it.copy(
                    isRunningSmoke = false,
                    telemetry = t,
                    smokeTestReport = (result as? EngineResult.Success)?.value,
                    recentLogs = VyroLogger.recentEntries().asReversed().take(20),
                )
            }
        }
    }

    fun triggerMemoryTrim() {
        cacheManager?.onTrimMemory(MemoryPressureLevel.LOW)
        runtimeHardeningMonitor?.refreshSystemTelemetry()
        _uiState.update { it.copy(recentLogs = VyroLogger.recentEntries().asReversed().take(20)) }
    }

    fun runProductionHardeningSuite() {
        if (_uiState.value.isRunningHardeningSuite) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningHardeningSuite = true) }
            val timelineReport = withContext(Dispatchers.Default) {
                ProductionHardeningVerifier.runLongSessionTimelineAndCacheStress(
                    editIterations = 120,
                    cachePutIterations = 240,
                )
            }
            val sessionSmoke = engineSession.runLifecycleSmokeTest(iterations = 25)
            val leakedSessions = (sessionSmoke as? EngineResult.Success)?.value?.leakedSessions ?: 0
            val combinedReport = timelineReport.copy(
                leakedSessions = leakedSessions,
                passed = timelineReport.passed && leakedSessions == 0,
            )
            runtimeHardeningMonitor?.recordStressSuiteResult(combinedReport)
            runtimeHardeningMonitor?.refreshSystemTelemetry()
            _uiState.update {
                it.copy(
                    isRunningHardeningSuite = false,
                    hardeningReport = combinedReport,
                    recentLogs = VyroLogger.recentEntries().asReversed().take(20),
                )
            }
        }
    }

    class Factory(
        private val engineSession: EngineSessionContract,
        private val playbackTelemetryFlow: StateFlow<PlaybackDiagnosticsSnapshot> = MutableStateFlow(PlaybackDiagnosticsSnapshot()),
        private val renderTelemetryFlow: StateFlow<RenderDiagnosticsSnapshot> = MutableStateFlow(RenderDiagnosticsSnapshot()),
        private val audioTelemetryFlow: StateFlow<AudioDiagnosticsSnapshot> = MutableStateFlow(AudioDiagnosticsSnapshot()),
        private val cacheManager: CacheAndProxyManagerContract? = null,
        private val projectRepository: ProjectRepository? = null,
        private val exportCoordinator: ExportCoordinatorContract? = null,
        private val runtimeHardeningMonitor: RuntimeHardeningMonitorContract? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiagnosticsViewModel(
                engineSession = engineSession,
                playbackTelemetryFlow = playbackTelemetryFlow,
                renderTelemetryFlow = renderTelemetryFlow,
                audioTelemetryFlow = audioTelemetryFlow,
                cacheManager = cacheManager,
                projectRepository = projectRepository,
                exportCoordinator = exportCoordinator,
                runtimeHardeningMonitor = runtimeHardeningMonitor,
            ) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("diagnostics_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.diagnostics_back_desc),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.diagnostics_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 840.dp)
                    .testTag("diagnostics_lazy_column"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            item {
                VyroStudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val mode = state.telemetry?.backendMode ?: EngineBackendMode.JVM_CONTRACT_FALLBACK
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Engine Session Runtime", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = mode.name,
                                accent = if (mode == EngineBackendMode.NATIVE_JNI) {
                                    VyroColors.VideoEmerald
                                } else {
                                    VyroColors.StudioAmber
                                },
                            )
                        }
                        Text(
                            text = if (mode == EngineBackendMode.NATIVE_JNI) {
                                "Native libvyro_engine.so loaded cleanly via JNI."
                            } else {
                                "NDK/CMake not provisioned in build SDK; running deterministic JVM contract parity engine."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.telemetry?.let { t ->
                            Text(
                                text = "Handle: ${t.nativeHandle}  •  Active Sessions: ${t.activeSessionCount}  •  Commands: ${t.commandCount}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item {
                VyroStudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Phase 1 Lifecycle Stress Test", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = viewModel::runSmokeTest,
                            enabled = !state.isRunningSmoke,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_smoke_test_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (state.isRunningSmoke) {
                                    stringResource(R.string.diagnostics_running)
                                } else {
                                    stringResource(R.string.diagnostics_run_smoke)
                                },
                            )
                        }

                        state.smokeTestReport?.let { report ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (report.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (report.passed) VyroColors.VideoEmerald else VyroColors.CoralError,
                                )
                                Text(
                                    text = "${report.summary} (${report.elapsedMs} ms)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            item {
                val pb = state.playbackTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 4 Decoder & Clock Sync", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = pb.activeDecoderBackend.name,
                                accent = if (pb.withinAvTolerance) VyroColors.ElectricCyan else VyroColors.StudioAmber,
                            )
                        }
                        Text(
                            text = "State: ${pb.playbackState.name}  •  Surface Attached: ${pb.surfaceAttached}  •  Seek Gen: ${pb.seekGeneration}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Queue: ${pb.queueDepth}/${pb.queueCapacity}  •  Decoded: ${pb.decodedFrames}  •  Dropped: ${pb.droppedFrames}  •  Stale Rejected: ${pb.rejectedStaleFrames}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "A/V Drift: ${pb.avDriftUs / 1000L} ms (Max: ${pb.maxObservedAbsDriftUs / 1000L} ms / Tolerance: ${PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US / 1000L} ms)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pb.withinAvTolerance) VyroColors.VideoEmerald else VyroColors.CoralError,
                        )
                    }
                }
            }

            item {
                val r = state.renderTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 5 GPU Composition & Shaders", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = r.backendType.name,
                                accent = if (r.leakedGpuResources == 0) VyroColors.VideoEmerald else VyroColors.CoralError,
                            )
                        }
                        Text(
                            text = "Renderer: ${r.gpuCapabilities.rendererName}  •  MaxTex: ${r.gpuCapabilities.maxTextureSize}px",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Layers: ${r.activeLayerCount}  •  Programs: ${r.activeProgramCount}  •  Textures: ${r.activeTextureCount}  •  Leaked: ${r.leakedGpuResources}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Frame Time: ${r.lastFrameRenderTimeUs} us (Avg: ${r.averageFrameRenderTimeUs} us / Budget: ${r.frameBudgetUs} us)  •  Recreated: ${r.surfaceRecreatedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (r.withinFrameBudget) VyroColors.VideoEmerald else VyroColors.StudioAmber,
                        )
                        Text(
                            text = r.gpuCapabilities.vulkanPrototypeReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                val a = state.audioTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth().testTag("diagnostics_phase6_audio_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 6 Audio Mixer & Clock", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = a.backendType.name,
                                accent = if (a.underrunCount == 0 && a.softwareStarvationCount == 0L) {
                                    VyroColors.VideoEmerald
                                } else {
                                    VyroColors.StudioAmber
                                },
                            )
                        }
                        Text(
                            text = "Format: ${a.sampleRateHz / 1000}kHz • ${a.channelCount}ch • Buffer ${a.framesPerBuffer} frames",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Mixed Clips: ${a.activeMixedClipCount}  •  HW Underruns: ${a.underrunCount}  •  Starvations: ${a.softwareStarvationCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Peak: ${(a.peakLevelLinear * 100).toInt()}%  •  RMS: ${(a.rmsLevelLinear * 100).toInt()}%  •  Audio Clock: ${a.audioClockUs / 1000L} ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = a.deviceCapabilities.oboeEvaluationNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                val c = state.cacheTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth().testTag("diagnostics_phase7_cache_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 7 Cache, Proxy & Memory", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = "PRESSURE: ${c.memoryPressureLevel.name}",
                                accent = if (c.memoryPressureLevel == MemoryPressureLevel.NONE) {
                                    VyroColors.VideoEmerald
                                } else {
                                    VyroColors.StudioAmber
                                },
                            )
                        }
                        Text(
                            text = "Resident Caches: ${c.totalResidentBytes / 1024L} KB / ${c.totalBudgetBytes / 1024L} KB  •  Trims: ${c.trimEventCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Frames: ${c.frameCache.entryCount} (${c.frameCache.hitRatePercent}% hit)  •  Thumbs: ${c.thumbnailCache.entryCount}  •  Waveforms: ${c.waveformCache.entryCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Proxy Mode: ${if (c.proxyEnabled) "ON" else "OFF"}  •  Ready: ${c.proxyReadyCount}/${c.proxyMappingsCount}  •  Disk: ${c.proxyDiskBytes / 1024L} KB",
                            style = MaterialTheme.typography.labelSmall,
                            color = VyroColors.ElectricCyan,
                        )
                        Text(
                            text = "Scheduler Pending: ${c.scheduler.pendingTasks}  •  Completed: ${c.scheduler.completedTasks}  •  Cancelled: ${c.scheduler.cancelledTasks} (Freed: ${c.scheduler.releasedCancelledResources})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = viewModel::triggerMemoryTrim,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diagnostics_trim_memory_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VyroColors.SlateControl,
                                contentColor = VyroColors.TextPrimary,
                            ),
                        ) {
                            Text(stringResource(R.string.diagnostics_trim_caches))
                        }
                    }
                }
            }

            item {
                val p = state.persistenceTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth().testTag("diagnostics_phase8_persistence_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 8 Persistence & Recovery", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = p.saveStatus.name,
                                accent = when (p.saveStatus) {
                                    PersistenceSaveStatus.FAILED -> VyroColors.CoralError
                                    PersistenceSaveStatus.RECOVERED_SNAPSHOT,
                                    PersistenceSaveStatus.RECOVERED_BACKUP -> VyroColors.StudioAmber
                                    else -> VyroColors.VideoEmerald
                                },
                            )
                        }
                        Text(
                            text = "Schema v${p.schemaVersion} (Migrated: ${p.migratedFromVersion?.let { "v$it" } ?: "None"})  •  Rev: ${p.lastSavedRevision}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Autosaves: ${p.autosaveCount}  •  Snapshots: ${p.recoverySnapshotCount}  •  Recovered: ${p.recoveredProjectCount}  •  Corrupt: ${p.corruptProjectCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Undo Policy: ${p.undoPolicy.displayLabel} (In-Mem Undo: ${p.inMemoryUndoDepth}, Redo: ${p.inMemoryRedoDepth})",
                            style = MaterialTheme.typography.labelSmall,
                            color = VyroColors.ElectricCyan,
                        )
                        Text(
                            text = "Source: ${p.recoverySource.name}  •  ${p.lastRecoveryDetail}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                val ex = state.exportTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth().testTag("diagnostics_phase9_export_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 9 Export Foundation", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = ex.lastPhase.name,
                                accent = when (ex.lastPhase) {
                                    ExportPhase.FINALIZED -> VyroColors.VideoEmerald
                                    ExportPhase.FAILED -> VyroColors.CoralError
                                    ExportPhase.CANCELLED -> VyroColors.StudioAmber
                                    else -> VyroColors.ElectricCyan
                                },
                            )
                        }
                        Text(
                            text = "Encoders: H264=${ex.codecCapabilities.h264Encoders.size}  •  HEVC=${ex.codecCapabilities.hevcEncoders.size}  •  AAC=${ex.codecCapabilities.aacEncoders.size}  •  Max=${ex.codecCapabilities.maxSupportedWidth}x${ex.codecCapabilities.maxSupportedHeight}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Completed: ${ex.completedExportsCount}  •  Cancelled: ${ex.cancelledExportsCount}  •  Failed: ${ex.failedExportsCount}  •  Leaked Resources: ${ex.leakedExportResources}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ex.lastValidation?.let { v ->
                            Text(
                                text = "Last Validated MP4: ${v.verifiedWidth}×${v.verifiedHeight} • ${v.verifiedDurationUs / 1000L} ms • A/V Δ ${v.avTimingDeltaUs / 1000L} ms • ${v.backendUsed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VyroColors.VideoEmerald,
                            )
                        } ?: Text(
                            text = ex.codecCapabilities.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = VyroColors.ElectricCyan,
                        )
                    }
                }
            }

            item {
                val h = state.hardeningTelemetry
                VyroStudioPanel(modifier = Modifier.fillMaxWidth().testTag("diagnostics_phase12_hardening_card")) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Phase 12 Production Hardening & Device Matrix", style = MaterialTheme.typography.titleMedium)
                            VyroBadge(
                                label = if (h.playPolicyCompliant) "PLAY READY" else "AUDIT",
                                accent = if (h.playPolicyCompliant) VyroColors.VideoEmerald else VyroColors.CoralError,
                            )
                        }
                        Text(
                            text = "Tier: ${h.deviceTier.label}  •  RAM: ${h.availableRamMb}/${h.totalRamMb} MB  •  Thermal: ${h.thermalStatus.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (h.thermalStatus.severityRank >= ThermalStatusLevel.SEVERE.severityRank) {
                                VyroColors.StudioAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = "Cap: ${h.recommendedMaxExportPreset.label} @ ${h.recommendedPreviewFpsCap}fps  •  Proxy Advised: ${h.adaptiveProxyRecommended}  •  Battery Saver: ${h.isPowerSaveMode}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = h.policyComplianceSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = VyroColors.ElectricCyan,
                        )
                        Button(
                            onClick = viewModel::runProductionHardeningSuite,
                            enabled = !state.isRunningHardeningSuite,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diagnostics_run_hardening_stress_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (state.isRunningHardeningSuite) {
                                    stringResource(R.string.diagnostics_running_hardening)
                                } else {
                                    stringResource(R.string.diagnostics_run_hardening_stress)
                                },
                            )
                        }
                        state.hardeningReport?.let { report ->
                            Row(
                                modifier = Modifier.testTag("diagnostics_phase12_stress_result"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = if (report.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (report.passed) VyroColors.VideoEmerald else VyroColors.CoralError,
                                )
                                Text(
                                    text = "${report.summary} (Leaks: ${report.leakedSessions})",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            state.capabilities?.let { caps ->
                item {
                    VyroStudioPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Hardware & Codec Capabilities", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Android SDK ${caps.sdkInt}  •  OpenGL ES ${caps.openGlEsVersion}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "ABIs: ${caps.supportedAbis.joinToString(", ").ifEmpty { "JVM Host" }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Video Decoders (${caps.hardwareVideoDecoders.size}): ${caps.hardwareVideoDecoders.take(3).joinToString(", ").ifEmpty { "None reported" }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Video Encoders (${caps.hardwareVideoEncoders.size}): ${caps.hardwareVideoEncoders.take(3).joinToString(", ").ifEmpty { "None reported" }}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Structured Engine Log (${state.recentLogs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.recentLogs) { entry ->
                VyroStudioPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${entry.severity.name} • ${entry.module}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = entry.correlationId,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        }
    }
}

package com.vyro.feature.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vyro.core.contracts.ExportConfig
import com.vyro.core.contracts.PersistenceSaveStatus
import com.vyro.core.contracts.RecoverySource
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.designsystem.VyroStudioPanel
import com.vyro.core.designsystem.VyroTimecodeStyle
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.MediaKind
import com.vyro.core.model.Sequence
import com.vyro.core.model.TimecodeFormatter
import com.vyro.core.model.TrackType
import com.vyro.core.model.TransitionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ActiveEditorModal {
    NONE,
    HELP,
    EXPORT_STATUS,
    MEDIA_ASSETS,
    EDIT_TEXT,
    FONT_PICKER,
    COLOR_PICKER,
    ANIMATION_PICKER,
    COVER_EDIT,
    TRANSFORM_BLEND,
    AUDIO_MIXER,
}

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onAddMedia: (projectId: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var activeModal by remember { mutableStateOf(ActiveEditorModal.NONE) }
    var toolMode by remember { mutableStateOf(EditorToolMode.ROOT) }
    var pendingRelinkAssetId by remember { mutableStateOf<String?>(null) }

    val relinkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val targetId = pendingRelinkAssetId ?: state.missingAssets.firstOrNull()?.assetId
        if (uri != null && targetId != null) {
            viewModel.relinkAsset(targetId, uri.toString())
        }
        pendingRelinkAssetId = null
    }

    val handleExit: () -> Unit = {
        coroutineScope.launch {
            viewModel.saveBeforeExit()
            withContext(Dispatchers.Main.immediate) {
                onClose()
            }
        }
    }

    BackHandler {
        if (state.isFullscreen) {
            viewModel.toggleFullscreen()
        } else if (toolMode != EditorToolMode.ROOT) {
            toolMode = EditorToolMode.ROOT
        } else {
            handleExit()
        }
    }

    // Automatically exit text sub-toolbar if selection moves away from a text clip
    LaunchedEffect(state.selectedClipId, state.isSelectedClipText) {
        if (toolMode == EditorToolMode.TEXT && !state.isSelectedClipText) {
            toolMode = EditorToolMode.ROOT
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.onLifecyclePauseOrStop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar("${err.code.name}: ${err.userMessage}")
        viewModel.clearError()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = VyroColors.Obsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = VyroColors.CrimsonAccent)
            }
            return@Scaffold
        }

        if (state.sequence == null) {
            CorruptProjectFallbackView(
                diagnosticDetail = state.loadError?.diagnosticDetail.orEmpty(),
                onRetry = viewModel::reloadProject,
                onBackToDrafts = onClose,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }

        val sequence = state.sequence ?: return@Scaffold

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val isExpandedLayout = availableWidth >= 720.dp && !state.isFullscreen
            val isCompactHeight = availableHeight < 620.dp

            Column(modifier = Modifier.fillMaxSize()) {
                if (!state.isFullscreen) {
                    EditorTopBar(
                        projectTitle = state.projectTitle,
                        saveStatus = state.saveStatus,
                        showTitle = availableWidth >= 380.dp,
                        hasMissingMedia = state.missingAssets.isNotEmpty(),
                        compact = isCompactHeight,
                        onClose = handleExit,
                        onOpenAssets = { activeModal = ActiveEditorModal.MEDIA_ASSETS },
                        onHelp = { activeModal = ActiveEditorModal.HELP },
                        onDiagnostics = onOpenDiagnostics,
                        onExport = {
                            viewModel.prepareExportSheet()
                            activeModal = ActiveEditorModal.EXPORT_STATUS
                        },
                    )

                    if (state.showRecoveryBanner) {
                        RecoverySnapshotBanner(
                            recoverySource = state.recoverySource,
                            recoveredCommands = state.recoveredCommandLabels,
                            canRevert = state.hasCommittedBaseline,
                            onKeep = viewModel::dismissRecoveryBanner,
                            onRevert = viewModel::revertToCommittedBaseline,
                        )
                    }

                    if (state.missingAssets.isNotEmpty()) {
                        MissingMediaBanner(
                            missingCount = state.missingAssets.size,
                            isRelinking = state.isRelinking,
                            onRelink = {
                                pendingRelinkAssetId = state.missingAssets.first().assetId
                                relinkLauncher.launch(arrayOf("video/*", "image/*", "audio/*"))
                            },
                            onInspect = { activeModal = ActiveEditorModal.MEDIA_ASSETS },
                        )
                    }
                }

                if (isExpandedLayout) {
                    // Medium/Expanded Tablet Studio Layout: Split Preview + Side Inspector Rail, full-width bottom timeline
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("editor_expanded_layout"),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            EditorPreviewCanvas(
                                sequence = sequence,
                                activeClips = state.activeClips,
                                selectedClipId = state.selectedClipId,
                                playheadUs = state.playheadUs,
                                isFullscreen = state.isFullscreen,
                                playbackDiagnostics = state.playbackDiagnostics,
                                renderGraph = state.renderGraph,
                                onSurfaceAttached = viewModel::attachPreviewSurface,
                                onSurfaceDetached = viewModel::detachPreviewSurface,
                                onExitFullscreen = viewModel::toggleFullscreen,
                                onSelectClip = viewModel::selectClip,
                                onUpdateTransform = viewModel::updateTransform,
                                onRequestTextEdit = { clipId ->
                                    viewModel.selectClip(clipId)
                                    activeModal = ActiveEditorModal.EDIT_TEXT
                                },
                                onOpenTransformBlend = { activeModal = ActiveEditorModal.TRANSFORM_BLEND },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )

                            TransportControlRow(
                                playheadUs = state.playheadUs,
                                durationUs = sequence.durationUs,
                                isPlaying = state.isPlaying,
                                isFullscreen = state.isFullscreen,
                                canUndo = state.canUndo,
                                canRedo = state.canRedo,
                                onJumpStart = viewModel::jumpToStart,
                                onTogglePlay = viewModel::togglePlayPause,
                                onUndo = viewModel::undo,
                                onRedo = viewModel::redo,
                                onToggleFullscreen = viewModel::toggleFullscreen,
                            )
                        }

                        VerticalDivider(color = VyroColors.SteelDivider, thickness = 1.dp)

                        Column(
                            modifier = Modifier
                                .width(300.dp)
                                .fillMaxHeight()
                                .background(VyroColors.Obsidian),
                        ) {
                            ContextualSelectionStrip(
                                selectedClip = state.selectedClip,
                                selectedTrack = state.selectedTrack,
                                zoomScale = state.zoomScale,
                                onToggleTrackLock = viewModel::toggleSelectedTrackLock,
                                onClearSelection = { viewModel.selectClip(null) },
                                onQuickEditText = { activeModal = ActiveEditorModal.EDIT_TEXT },
                                onQuickOpenMixer = { activeModal = ActiveEditorModal.AUDIO_MIXER },
                                onZoomOut = { viewModel.adjustZoom(-0.25f) },
                                onZoomReset = viewModel::resetZoom,
                                onZoomIn = { viewModel.adjustZoom(0.25f) },
                            )
                            HorizontalDivider(color = VyroColors.SteelDivider, thickness = 1.dp)
                            BottomEditorToolStrip(
                                toolMode = toolMode,
                                isSideRail = true,
                                isTextClipSelected = state.isSelectedClipText,
                                hasSelection = state.selectedClip != null || state.primaryVideoClip != null,
                                hasAudioTarget = state.primaryAudioTargetClip != null,
                                isTrackLocked = state.isSelectedTrackLocked,
                                proxyEnabled = state.proxyEnabled,
                                keyframeActive = state.keyframeTargetClip?.keyframesEnabled == true,
                                hasKeyframeAtPlayhead = state.hasKeyframeNearPlayhead,
                                hasKeyframesOnClip = state.keyframeTargetClip?.keyframes?.isNotEmpty() == true,
                                currentTransition = state.keyframeTargetClip?.transitionIn?.preset ?: TransitionPreset.NONE,
                                currentFilter = state.primaryVideoClip?.filterPreset ?: com.vyro.core.model.ColorFilterPreset.ORIGINAL,
                                currentEffect = state.primaryVideoClip?.effectPreset ?: com.vyro.core.model.VisualEffectPreset.NONE,
                                currentWidth = sequence.width,
                                currentHeight = sequence.height,
                                onSelectMode = { toolMode = it },
                                onAddTextAndOpenSubbar = {
                                    if (!state.isSelectedClipText) viewModel.addTextClip()
                                    toolMode = EditorToolMode.TEXT
                                },
                                onAddText = viewModel::addTextClip,
                                onAddAudio = viewModel::addAudioClip,
                                onToggleKeyframe = viewModel::toggleSelectedKeyframe,
                                onSetKeyframeAtPlayhead = { viewModel.addOrUpdateKeyframeAtPlayhead() },
                                onStepKeyframeScale = { viewModel.stepKeyframeScale() },
                                onRemoveKeyframeAtPlayhead = viewModel::removeKeyframeAtPlayhead,
                                onJumpPrevKeyframe = { viewModel.jumpToAdjacentKeyframe(false) },
                                onJumpNextKeyframe = { viewModel.jumpToAdjacentKeyframe(true) },
                                onSelectTransition = viewModel::setSelectedClipTransition,
                                onEditText = { activeModal = ActiveEditorModal.EDIT_TEXT },
                                onFont = { activeModal = ActiveEditorModal.FONT_PICKER },
                                onColor = { activeModal = ActiveEditorModal.COLOR_PICKER },
                                onAnimation = { activeModal = ActiveEditorModal.ANIMATION_PICKER },
                                onSplit = viewModel::splitSelectedClip,
                                onTrimShorter = { viewModel.nudgeSelectedClipEnd(-500_000L) },
                                onTrimLonger = { viewModel.nudgeSelectedClipEnd(500_000L) },
                                onStepBackward = { viewModel.stepFrame(false) },
                                onStepForward = { viewModel.stepFrame(true) },
                                onToggleTrackLock = viewModel::toggleSelectedTrackLock,
                                onDelete = {
                                    viewModel.deleteSelectedClip()
                                    toolMode = EditorToolMode.ROOT
                                },
                                onSelectFilter = viewModel::setFilter,
                                onSelectEffect = viewModel::setEffect,
                                onSelectAspect = viewModel::setCanvasAspect,
                                onOpenTransformBlend = { activeModal = ActiveEditorModal.TRANSFORM_BLEND },
                                onOpenAudioMixer = { activeModal = ActiveEditorModal.AUDIO_MIXER },
                                onToggleProxy = viewModel::toggleProxyPlayback,
                                onRotateClip = viewModel::rotateSelectedClipQuarterTurn,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    HorizontalDivider(color = VyroColors.SteelDivider, thickness = 1.dp)

                    TimelineStageDock(
                        sequence = sequence,
                        state = state,
                        onScrub = viewModel::scrubTo,
                        onSelectClip = viewModel::selectClip,
                        onMoveClip = viewModel::moveClip,
                        onTrimClip = viewModel::trimClip,
                        onZoomScaleChanged = viewModel::setZoom,
                        onLockedTrackInteraction = viewModel::notifyLockedTrackInteraction,
                        onToggleMute = viewModel::toggleMuteClipAudio,
                        onEditCover = { activeModal = ActiveEditorModal.COVER_EDIT },
                        onAddAudio = viewModel::addAudioClip,
                        onAddMediaClick = {
                            coroutineScope.launch {
                                viewModel.saveBeforeExit()
                                withContext(Dispatchers.Main.immediate) {
                                    onAddMedia(state.projectId)
                                }
                            }
                        },
                    )
                } else {
                    // Compact / Phone Portrait Layout matching studio visual reference
                    EditorPreviewCanvas(
                        sequence = sequence,
                        activeClips = state.activeClips,
                        selectedClipId = state.selectedClipId,
                        playheadUs = state.playheadUs,
                        isFullscreen = state.isFullscreen,
                        playbackDiagnostics = state.playbackDiagnostics,
                        renderGraph = state.renderGraph,
                        onSurfaceAttached = viewModel::attachPreviewSurface,
                        onSurfaceDetached = viewModel::detachPreviewSurface,
                        onExitFullscreen = viewModel::toggleFullscreen,
                        onSelectClip = viewModel::selectClip,
                        onUpdateTransform = viewModel::updateTransform,
                        onRequestTextEdit = { clipId ->
                            viewModel.selectClip(clipId)
                            activeModal = ActiveEditorModal.EDIT_TEXT
                        },
                        onOpenTransformBlend = { activeModal = ActiveEditorModal.TRANSFORM_BLEND },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )

                    TransportControlRow(
                        playheadUs = state.playheadUs,
                        durationUs = sequence.durationUs,
                        isPlaying = state.isPlaying,
                        isFullscreen = state.isFullscreen,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        compact = isCompactHeight,
                        onJumpStart = viewModel::jumpToStart,
                        onTogglePlay = viewModel::togglePlayPause,
                        onUndo = viewModel::undo,
                        onRedo = viewModel::redo,
                        onToggleFullscreen = viewModel::toggleFullscreen,
                    )

                    if (!state.isFullscreen) {
                        TimelineStageDock(
                            sequence = sequence,
                            state = state,
                            compact = isCompactHeight,
                            onScrub = viewModel::scrubTo,
                            onSelectClip = viewModel::selectClip,
                            onMoveClip = viewModel::moveClip,
                            onTrimClip = viewModel::trimClip,
                            onZoomScaleChanged = viewModel::setZoom,
                            onLockedTrackInteraction = viewModel::notifyLockedTrackInteraction,
                            onToggleMute = viewModel::toggleMuteClipAudio,
                            onEditCover = { activeModal = ActiveEditorModal.COVER_EDIT },
                            onAddAudio = viewModel::addAudioClip,
                            onAddMediaClick = {
                                coroutineScope.launch {
                                    viewModel.saveBeforeExit()
                                    withContext(Dispatchers.Main.immediate) {
                                        onAddMedia(state.projectId)
                                    }
                                }
                            },
                        )

                        ContextualSelectionStrip(
                            selectedClip = state.selectedClip,
                            selectedTrack = state.selectedTrack,
                            zoomScale = state.zoomScale,
                            onToggleTrackLock = viewModel::toggleSelectedTrackLock,
                            onClearSelection = { viewModel.selectClip(null) },
                            onQuickEditText = { activeModal = ActiveEditorModal.EDIT_TEXT },
                            onQuickOpenMixer = { activeModal = ActiveEditorModal.AUDIO_MIXER },
                            onZoomOut = { viewModel.adjustZoom(-0.25f) },
                            onZoomReset = viewModel::resetZoom,
                            onZoomIn = { viewModel.adjustZoom(0.25f) },
                        )

                        BottomEditorToolStrip(
                            toolMode = toolMode,
                            isSideRail = false,
                            isCompactHeight = isCompactHeight,
                            isTextClipSelected = state.isSelectedClipText,
                            hasSelection = state.selectedClip != null || state.primaryVideoClip != null,
                            hasAudioTarget = state.primaryAudioTargetClip != null,
                            isTrackLocked = state.isSelectedTrackLocked,
                            proxyEnabled = state.proxyEnabled,
                            keyframeActive = state.keyframeTargetClip?.keyframesEnabled == true,
                            hasKeyframeAtPlayhead = state.hasKeyframeNearPlayhead,
                            hasKeyframesOnClip = state.keyframeTargetClip?.keyframes?.isNotEmpty() == true,
                            currentTransition = state.keyframeTargetClip?.transitionIn?.preset ?: TransitionPreset.NONE,
                            currentFilter = state.primaryVideoClip?.filterPreset ?: com.vyro.core.model.ColorFilterPreset.ORIGINAL,
                            currentEffect = state.primaryVideoClip?.effectPreset ?: com.vyro.core.model.VisualEffectPreset.NONE,
                            currentWidth = sequence.width,
                            currentHeight = sequence.height,
                            onSelectMode = { toolMode = it },
                            onAddTextAndOpenSubbar = {
                                if (!state.isSelectedClipText) viewModel.addTextClip()
                                toolMode = EditorToolMode.TEXT
                            },
                            onAddText = viewModel::addTextClip,
                            onAddAudio = viewModel::addAudioClip,
                            onToggleKeyframe = viewModel::toggleSelectedKeyframe,
                            onSetKeyframeAtPlayhead = { viewModel.addOrUpdateKeyframeAtPlayhead() },
                            onStepKeyframeScale = { viewModel.stepKeyframeScale() },
                            onRemoveKeyframeAtPlayhead = viewModel::removeKeyframeAtPlayhead,
                            onJumpPrevKeyframe = { viewModel.jumpToAdjacentKeyframe(false) },
                            onJumpNextKeyframe = { viewModel.jumpToAdjacentKeyframe(true) },
                            onSelectTransition = viewModel::setSelectedClipTransition,
                            onEditText = { activeModal = ActiveEditorModal.EDIT_TEXT },
                            onFont = { activeModal = ActiveEditorModal.FONT_PICKER },
                            onColor = { activeModal = ActiveEditorModal.COLOR_PICKER },
                            onAnimation = { activeModal = ActiveEditorModal.ANIMATION_PICKER },
                            onSplit = viewModel::splitSelectedClip,
                            onTrimShorter = { viewModel.nudgeSelectedClipEnd(-500_000L) },
                            onTrimLonger = { viewModel.nudgeSelectedClipEnd(500_000L) },
                            onStepBackward = { viewModel.stepFrame(false) },
                            onStepForward = { viewModel.stepFrame(true) },
                            onToggleTrackLock = viewModel::toggleSelectedTrackLock,
                            onDelete = {
                                viewModel.deleteSelectedClip()
                                toolMode = EditorToolMode.ROOT
                            },
                            onSelectFilter = viewModel::setFilter,
                            onSelectEffect = viewModel::setEffect,
                            onSelectAspect = viewModel::setCanvasAspect,
                            onOpenTransformBlend = { activeModal = ActiveEditorModal.TRANSFORM_BLEND },
                            onOpenAudioMixer = { activeModal = ActiveEditorModal.AUDIO_MIXER },
                            onToggleProxy = viewModel::toggleProxyPlayback,
                            onRotateClip = viewModel::rotateSelectedClipQuarterTurn,
                        )
                    }
                }
            }
        }
    }

    val selectedTextSpec = state.selectedClip?.textSpec
    when (activeModal) {
        ActiveEditorModal.EDIT_TEXT -> if (selectedTextSpec != null) {
            TextEditDialog(
                initialText = selectedTextSpec.text,
                onDismiss = { activeModal = ActiveEditorModal.NONE },
                onConfirm = { newText ->
                    viewModel.updateSelectedTextSpec { it.copy(text = newText) }
                    activeModal = ActiveEditorModal.NONE
                },
            )
        }
        ActiveEditorModal.FONT_PICKER -> if (selectedTextSpec != null) {
            FontPickerDialog(
                currentFontId = selectedTextSpec.fontId,
                onDismiss = { activeModal = ActiveEditorModal.NONE },
                onSelectFont = { fontId ->
                    viewModel.updateSelectedTextSpec { it.copy(fontId = fontId) }
                    activeModal = ActiveEditorModal.NONE
                },
            )
        }
        ActiveEditorModal.COLOR_PICKER -> if (selectedTextSpec != null) {
            ColorPickerDialog(
                currentColorArgb = selectedTextSpec.colorArgb,
                onDismiss = { activeModal = ActiveEditorModal.NONE },
                onSelectColor = { color ->
                    viewModel.updateSelectedTextSpec { it.copy(colorArgb = color) }
                    activeModal = ActiveEditorModal.NONE
                },
            )
        }
        ActiveEditorModal.ANIMATION_PICKER -> if (selectedTextSpec != null) {
            AnimationPickerDialog(
                currentSpec = selectedTextSpec,
                onDismiss = { activeModal = ActiveEditorModal.NONE },
                onSelectPreset = { preset ->
                    viewModel.updateSelectedTextSpec { it.copy(animationPreset = preset) }
                    activeModal = ActiveEditorModal.NONE
                },
            )
        }
        ActiveEditorModal.COVER_EDIT -> state.sequence?.let { seq ->
            CoverEditDialog(
                currentCover = seq.coverSpec,
                onDismiss = { activeModal = ActiveEditorModal.NONE },
                onConfirm = { nextCover ->
                    viewModel.updateCover(nextCover)
                    activeModal = ActiveEditorModal.NONE
                },
            )
        }
        ActiveEditorModal.HELP -> {
            AlertDialog(
                onDismissRequest = { activeModal = ActiveEditorModal.NONE },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(stringResource(R.string.editor_help_dialog_title)) },
                text = { Text(stringResource(R.string.editor_help_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = { activeModal = ActiveEditorModal.NONE }) {
                        Text(stringResource(R.string.editor_ok))
                    }
                },
            )
        }
        ActiveEditorModal.EXPORT_STATUS -> state.sequence?.let { seq ->
            val exportCfg = state.exportConfig ?: ExportConfig(
                projectId = state.projectId,
                outputTitle = state.projectTitle,
                width = seq.width,
                height = seq.height,
                frameRate = seq.frameRate,
            )
            ExportStudioDialog(
                config = exportCfg,
                capabilities = state.exportCodecCapabilities,
                progress = state.exportProgress,
                sequenceDurationUs = seq.durationUs,
                onSelectPreset = viewModel::selectExportPreset,
                onSelectCodec = viewModel::selectExportCodec,
                onToggleIncludeAudio = viewModel::toggleExportIncludeAudio,
                onToggleSaveToGallery = viewModel::toggleExportSaveToGallery,
                onStartExport = viewModel::startExport,
                onCancelExport = viewModel::cancelExport,
                onResetAndReconfigure = viewModel::resetExportState,
                onDismiss = {
                    viewModel.resetExportState()
                    activeModal = ActiveEditorModal.NONE
                },
            )
        }
        ActiveEditorModal.MEDIA_ASSETS -> {
            MediaAssetsDialog(
                assets = state.assets,
                isRelinking = state.isRelinking,
                onRelinkClick = { asset ->
                    pendingRelinkAssetId = asset.assetId
                    val mimeFilter = when (asset.mediaKind) {
                        MediaKind.AUDIO -> arrayOf("audio/*")
                        MediaKind.PHOTO -> arrayOf("image/*")
                        else -> arrayOf("video/*", "image/*", "audio/*")
                    }
                    relinkLauncher.launch(mimeFilter)
                },
                onDismiss = { activeModal = ActiveEditorModal.NONE },
            )
        }
        ActiveEditorModal.TRANSFORM_BLEND -> {
            val targetClip = state.selectedClip ?: state.primaryVideoClip
            if (targetClip != null) {
                TransformBlendDialog(
                    clipLabel = targetClip.label,
                    currentTransform = targetClip.transform,
                    onApplyTransform = { next -> viewModel.updateTransform(targetClip.id, next) },
                    onDismiss = { activeModal = ActiveEditorModal.NONE },
                )
            }
        }
        ActiveEditorModal.AUDIO_MIXER -> {
            val targetClip = state.primaryAudioTargetClip
            if (targetClip != null) {
                AudioMixerDialog(
                    clipLabel = targetClip.label,
                    currentSpec = targetClip.audioSpec ?: AudioClipSpec(),
                    waveform = state.waveforms[targetClip.id],
                    clipDurationUs = targetClip.timing.timelineDurationUs,
                    onApplyAudioSpec = { nextSpec -> viewModel.updateAudioSpec(targetClip.id, nextSpec) },
                    onDismiss = { activeModal = ActiveEditorModal.NONE },
                )
            }
        }
        ActiveEditorModal.NONE -> Unit
    }
}

@Composable
private fun TimelineStageDock(
    sequence: Sequence,
    state: EditorUiState,
    compact: Boolean = false,
    onScrub: (Long) -> Unit,
    onSelectClip: (String?) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onTrimClip: (String, Long, Long, Long) -> Unit,
    onZoomScaleChanged: (Float) -> Unit,
    onLockedTrackInteraction: (String) -> Unit,
    onToggleMute: () -> Unit,
    onEditCover: () -> Unit,
    onAddAudio: () -> Unit,
    onAddMediaClick: () -> Unit,
) {
    val hasSecondaryClips = sequence.tracks.any {
        it.type != TrackType.VIDEO && it.clips.isNotEmpty()
    }
    val dockHeight = if (compact) {
        if (hasSecondaryClips) 156.dp else 138.dp
    } else {
        178.dp
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(dockHeight)
            .background(Color(0xFF13151B))
            .padding(vertical = if (compact) 2.dp else 4.dp),
    ) {
        val density = LocalDensity.current
        val centerXPx = with(density) { (maxWidth / 2f).toPx() }
        val pxPerUs = with(density) {
            (TimelineSurfaceView.BASE_DP_PER_SECOND.dp.toPx() * state.zoomScale) / 1_000_000f
        }
        val scrollOffsetPx = state.playheadUs * pxPerUs

        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("timeline_surface_view"),
            factory = { ctx ->
                TimelineSurfaceView(ctx).apply {
                    onScrubPlayheadUs = onScrub
                    this.onSelectClip = onSelectClip
                    this.onMoveClip = onMoveClip
                    this.onTrimClip = onTrimClip
                    this.onZoomScaleChanged = onZoomScaleChanged
                    this.onLockedTrackInteraction = onLockedTrackInteraction
                }
            },
            update = { view ->
                view.frameRate = sequence.frameRate
                view.durationUs = if (sequence.durationUs > 0L) sequence.durationUs else 4_000_000L
                view.tracks = sequence.tracks
                view.selectedClipId = state.selectedClipId
                view.playheadUs = state.playheadUs
                view.zoomScale = state.zoomScale
                view.waveforms = state.waveforms
            },
        )

        Box(
            modifier = Modifier.offset {
                IntOffset(
                    x = (centerXPx - scrollOffsetPx - 128.dp.roundToPx()).roundToInt(),
                    y = (if (compact) 34.dp else 40.dp).roundToPx(),
                )
            },
        ) {
            TimelineLeftColumn(
                isMuted = state.isClipAudioMuted,
                coverSpec = sequence.coverSpec,
                onToggleMute = onToggleMute,
                onEditCover = onEditCover,
            )
        }

        Surface(
            modifier = Modifier
                .offset {
                    val pillY = when {
                        compact && hasSecondaryClips -> 114.dp
                        compact -> 86.dp
                        hasSecondaryClips -> 132.dp
                        else -> 98.dp
                    }
                    IntOffset(
                        x = (centerXPx - scrollOffsetPx).roundToInt(),
                        y = pillY.roundToPx(),
                    )
                }
                .minimumInteractiveComponentSize()
                .widthIn(min = 148.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = onAddAudio)
                .testTag("add_audio_pill_button"),
            shape = RoundedCornerShape(6.dp),
            color = VyroColors.SlateControl,
        ) {
            Text(
                text = stringResource(R.string.editor_add_audio_pill),
                fontSize = 12.sp,
                color = VyroColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        // Right-side pinned crimson + button with 48dp accessible touch target
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (compact) 32.dp else 38.dp, end = 4.dp)
                .minimumInteractiveComponentSize()
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClick = onAddMediaClick)
                .testTag("add_media_button")
                .semantics { contentDescription = "Add video or photo clip" },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(8.dp),
                color = VyroColors.CrimsonAccent,
                border = BorderStroke(1.5.dp, Color(0xFF13151B)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Fixed horizontal-center crimson playhead needle
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp, bottom = 4.dp)
                .width(2.5.dp)
                .fillMaxHeight()
                .background(VyroColors.CrimsonAccent)
                .testTag("fixed_center_playhead"),
        )
    }
}

@Composable
private fun RecoverySnapshotBanner(
    recoverySource: RecoverySource,
    recoveredCommands: List<String>,
    canRevert: Boolean,
    onKeep: () -> Unit,
    onRevert: () -> Unit,
) {
    val cmdSummary = if (recoveredCommands.isNotEmpty()) {
        " (${recoveredCommands.takeLast(3).joinToString(", ")})"
    } else {
        ""
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("recovery_snapshot_banner"),
        shape = RoundedCornerShape(10.dp),
        color = VyroColors.ElectricCyan.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, VyroColors.ElectricCyan.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (recoverySource == RecoverySource.BACKUP_FILE) {
                    stringResource(R.string.editor_recovery_backup_banner)
                } else {
                    stringResource(R.string.editor_recovery_snapshot_banner, cmdSummary)
                },
                style = MaterialTheme.typography.labelSmall,
                color = VyroColors.TextPrimary,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            if (canRevert && recoverySource == RecoverySource.RECOVERY_SNAPSHOT) {
                TextButton(
                    onClick = onRevert,
                    modifier = Modifier.testTag("recovery_revert_button"),
                ) {
                    Text(
                        text = stringResource(R.string.editor_recovery_revert),
                        color = VyroColors.StudioAmber,
                        fontSize = 11.sp,
                    )
                }
            }
            TextButton(
                onClick = onKeep,
                modifier = Modifier.testTag("recovery_keep_button"),
            ) {
                Text(
                    text = stringResource(R.string.editor_recovery_keep),
                    color = VyroColors.ElectricCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CorruptProjectFallbackView(
    diagnosticDetail: String,
    onRetry: () -> Unit,
    onBackToDrafts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        VyroStudioPanel(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .testTag("corrupt_project_panel"),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = VyroColors.CoralError,
                    )
                    Text(
                        text = stringResource(R.string.editor_corrupt_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = VyroColors.TextPrimary,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Text(
                    text = stringResource(R.string.editor_corrupt_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VyroColors.TextSecondary,
                )
                if (diagnosticDetail.isNotBlank()) {
                    Text(
                        text = diagnosticDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = VyroColors.CoralError,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("corrupt_retry_button"),
                    ) {
                        Text(stringResource(R.string.editor_corrupt_retry))
                    }
                    Button(
                        onClick = onBackToDrafts,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("corrupt_back_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VyroColors.CrimsonAccent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(stringResource(R.string.editor_corrupt_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingMediaBanner(
    missingCount: Int,
    isRelinking: Boolean,
    onRelink: () -> Unit,
    onInspect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("missing_media_banner"),
        shape = RoundedCornerShape(10.dp),
        color = VyroColors.StudioAmber.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, VyroColors.StudioAmber.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = VyroColors.StudioAmber,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (missingCount == 1) {
                    stringResource(R.string.editor_missing_banner_single)
                } else {
                    stringResource(R.string.editor_missing_banner_plural, missingCount)
                },
                style = MaterialTheme.typography.labelSmall,
                color = VyroColors.TextPrimary,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onInspect,
                modifier = Modifier.testTag("inspect_missing_media_button"),
            ) {
                Text(
                    text = stringResource(R.string.editor_inspect_action),
                    color = VyroColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
            TextButton(
                onClick = onRelink,
                enabled = !isRelinking,
                modifier = Modifier.testTag("relink_missing_media_button"),
            ) {
                Text(
                    text = stringResource(
                        if (isRelinking) R.string.editor_relinking_action else R.string.editor_relink_action,
                    ),
                    color = VyroColors.StudioAmber,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    projectTitle: String,
    saveStatus: PersistenceSaveStatus,
    showTitle: Boolean,
    hasMissingMedia: Boolean,
    compact: Boolean = false,
    onClose: () -> Unit,
    onOpenAssets: () -> Unit,
    onHelp: () -> Unit,
    onDiagnostics: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VyroColors.Obsidian)
            .padding(horizontal = 6.dp, vertical = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("editor_close_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.editor_close_desc),
                    tint = VyroColors.TextPrimary,
                )
            }

            if (showTitle && projectTitle.isNotBlank()) {
                Text(
                    text = projectTitle,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = VyroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val statusColor = when (saveStatus) {
                    PersistenceSaveStatus.SAVED -> VyroColors.VideoEmerald
                    PersistenceSaveStatus.DIRTY, PersistenceSaveStatus.AUTOSAVING -> VyroColors.StudioAmber
                    PersistenceSaveStatus.FAILED -> VyroColors.CoralError
                    else -> VyroColors.ElectricCyan
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor)
                        .semantics { contentDescription = "Save status ${saveStatus.name}" },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onOpenAssets,
                modifier = Modifier.testTag("editor_assets_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.PermMedia,
                    contentDescription = stringResource(R.string.editor_assets_desc),
                    tint = if (hasMissingMedia) VyroColors.StudioAmber else VyroColors.TextPrimary,
                )
            }
            IconButton(
                onClick = onHelp,
                modifier = Modifier.testTag("editor_help_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = stringResource(R.string.editor_help_desc),
                    tint = VyroColors.TextPrimary,
                )
            }
            IconButton(
                onClick = onDiagnostics,
                modifier = Modifier.testTag("editor_diagnostics_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = stringResource(R.string.editor_diagnostics_desc),
                    tint = VyroColors.TextPrimary,
                )
            }
            IconButton(
                onClick = onExport,
                modifier = Modifier.testTag("editor_export_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = stringResource(R.string.editor_export_action),
                    tint = VyroColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun TransportControlRow(
    playheadUs: Long,
    durationUs: Long,
    isPlaying: Boolean,
    isFullscreen: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    compact: Boolean = false,
    onJumpStart: () -> Unit,
    onTogglePlay: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val currentStr = TimecodeFormatter.formatCompactTenths(playheadUs)
    val durationStr = TimecodeFormatter.formatCompactTenths(durationUs)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF13151B))
            .padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onJumpStart,
                modifier = Modifier.testTag("step_back_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.editor_jump_start_desc),
                    tint = VyroColors.TextPrimary,
                )
            }
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .testTag("play_pause_button")
                    .semantics {
                        stateDescription = if (isPlaying) "Playing" else "Paused"
                    },
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.editor_pause_desc else R.string.editor_play_desc,
                    ),
                    tint = VyroColors.TextPrimary,
                )
            }
        }

        Text(
            text = "$currentStr / $durationStr",
            style = VyroTimecodeStyle.copy(fontSize = 12.sp),
            color = VyroColors.TextSecondary,
            modifier = Modifier
                .testTag("timecode_readout")
                .semantics { contentDescription = "Timecode $currentStr of $durationStr" },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag("undo_button")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.editor_undo_desc),
                    tint = if (canUndo) VyroColors.TextPrimary else VyroColors.SteelMuted,
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag("redo_button")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.editor_redo_desc),
                    tint = if (canRedo) VyroColors.TextPrimary else VyroColors.SteelMuted,
                )
            }
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.testTag("fullscreen_button"),
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = stringResource(R.string.editor_fullscreen_desc),
                    tint = VyroColors.TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun TimelineLeftColumn(
    isMuted: Boolean,
    coverSpec: CoverSpec,
    onToggleMute: () -> Unit,
    onEditCover: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 6.dp)
            .width(120.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onToggleMute)
                .padding(vertical = 6.dp)
                .testTag("mute_clip_audio_button")
                .semantics {
                    stateDescription = if (isMuted) "Muted" else "Unmuted"
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(
                    if (isMuted) R.string.editor_unmute_clip_audio else R.string.editor_mute_clip_audio,
                ),
                tint = if (isMuted) VyroColors.TextSecondary else VyroColors.ElectricCyan,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(
                    if (isMuted) R.string.editor_unmute_clip_audio else R.string.editor_mute_clip_audio,
                ),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = VyroColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(coverSpec.accentColorArgb))
                    .clickable(role = Role.Button, onClick = onEditCover)
                    .testTag("cover_edit_button")
                    .semantics { contentDescription = "Edit project cover" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = coverSpec.label.ifBlank { stringResource(R.string.editor_cover_label) },
                fontSize = 10.sp,
                color = VyroColors.TextSecondary,
            )
        }
    }
}

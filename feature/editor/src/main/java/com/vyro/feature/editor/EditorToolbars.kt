package com.vyro.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.Clip
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.core.model.TransitionPreset
import com.vyro.core.model.VisualEffectPreset
import kotlin.math.roundToInt

internal enum class EditorToolMode {
    ROOT,
    TRIM,
    TEXT,
    KEYFRAME,
    TRANSITION,
    EFFECT,
    CANVAS,
    FILTER,
}

@Composable
internal fun ContextualSelectionStrip(
    selectedClip: Clip?,
    selectedTrack: Track?,
    zoomScale: Float,
    onToggleTrackLock: () -> Unit,
    onClearSelection: () -> Unit,
    onQuickEditText: () -> Unit,
    onQuickOpenMixer: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackType = selectedTrack?.type
    val isLocked = selectedTrack?.isLocked == true
    val accent = when (trackType) {
        TrackType.TEXT -> VyroColors.TextViolet
        TrackType.AUDIO -> VyroColors.AudioTeal
        TrackType.VIDEO, null -> VyroColors.ElectricCyan
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(VyroColors.SlatePanel)
            .padding(horizontal = 8.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                modifier = Modifier
                    .testTag("contextual_selection_chip")
                    .semantics {
                        contentDescription = if (selectedClip != null) {
                            "Selected clip ${selectedClip.label}"
                        } else {
                            "No clip selected"
                        }
                    },
                shape = RoundedCornerShape(8.dp),
                color = VyroColors.SlateControl,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (selectedClip != null) {
                            "${trackType?.name ?: "CLIP"} • ${selectedClip.label}"
                        } else {
                            stringResource(R.string.editor_selection_empty)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedClip != null) accent else VyroColors.SteelMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isLocked) {
                        VyroBadge(
                            label = stringResource(R.string.editor_track_locked_badge),
                            accent = VyroColors.StudioAmber,
                        )
                    }
                }
            }

            if (selectedClip != null) {
                if (selectedClip.textSpec != null) {
                    CompactActionChip(
                        label = stringResource(R.string.editor_tool_edit),
                        accent = VyroColors.TextViolet,
                        testTag = "contextual_quick_edit_text",
                        onClick = onQuickEditText,
                    )
                } else if (trackType == TrackType.AUDIO) {
                    CompactActionChip(
                        label = stringResource(R.string.editor_tab_audio_mix),
                        accent = VyroColors.AudioTeal,
                        testTag = "contextual_quick_audio_mix",
                        onClick = onQuickOpenMixer,
                    )
                }

                CompactIconAction(
                    icon = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    description = stringResource(
                        if (isLocked) R.string.editor_tool_unlock_track else R.string.editor_tool_lock_track,
                    ),
                    tint = if (isLocked) VyroColors.StudioAmber else VyroColors.TextSecondary,
                    testTag = "contextual_lock_button",
                    onClick = onToggleTrackLock,
                )

                CompactIconAction(
                    icon = Icons.Default.Close,
                    description = stringResource(R.string.editor_clear_selection_desc),
                    tint = VyroColors.TextSecondary,
                    testTag = "contextual_clear_selection_button",
                    onClick = onClearSelection,
                )
            }
        }

        // Timeline zoom controls (- 1.0x +)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CompactIconAction(
                icon = Icons.Default.ZoomOut,
                description = stringResource(R.string.editor_zoom_out_desc),
                tint = VyroColors.TextSecondary,
                testTag = "timeline_zoom_out_button",
                onClick = onZoomOut,
            )
            val zoomLabel = "${((zoomScale * 10f).roundToInt() / 10f)}x"
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onZoomReset)
                    .padding(horizontal = 6.dp, vertical = 10.dp)
                    .testTag("timeline_zoom_reset_button")
                    .semantics {
                        contentDescription = "Timeline zoom $zoomLabel. Tap to reset."
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = zoomLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = VyroColors.ElectricCyan,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            CompactIconAction(
                icon = Icons.Default.ZoomIn,
                description = stringResource(R.string.editor_zoom_in_desc),
                tint = VyroColors.TextSecondary,
                testTag = "timeline_zoom_in_button",
                onClick = onZoomIn,
            )
        }
    }
}

@Composable
private fun CompactActionChip(
    label: String,
    accent: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CompactIconAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BottomEditorToolStrip(
    toolMode: EditorToolMode,
    isSideRail: Boolean = false,
    isCompactHeight: Boolean = false,
    isTextClipSelected: Boolean,
    hasSelection: Boolean,
    hasAudioTarget: Boolean,
    isTrackLocked: Boolean,
    proxyEnabled: Boolean,
    keyframeActive: Boolean,
    hasKeyframeAtPlayhead: Boolean,
    hasKeyframesOnClip: Boolean,
    currentTransition: TransitionPreset,
    currentFilter: ColorFilterPreset,
    currentEffect: VisualEffectPreset,
    currentWidth: Int,
    currentHeight: Int,
    onSelectMode: (EditorToolMode) -> Unit,
    onAddTextAndOpenSubbar: () -> Unit,
    onAddText: () -> Unit,
    onAddAudio: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onSetKeyframeAtPlayhead: () -> Unit,
    onStepKeyframeScale: () -> Unit,
    onRemoveKeyframeAtPlayhead: () -> Unit,
    onJumpPrevKeyframe: () -> Unit,
    onJumpNextKeyframe: () -> Unit,
    onSelectTransition: (TransitionPreset) -> Unit,
    onEditText: () -> Unit,
    onFont: () -> Unit,
    onColor: () -> Unit,
    onAnimation: () -> Unit,
    onSplit: () -> Unit,
    onTrimShorter: () -> Unit,
    onTrimLonger: () -> Unit,
    onStepBackward: () -> Unit,
    onStepForward: () -> Unit,
    onToggleTrackLock: () -> Unit,
    onDelete: () -> Unit,
    onSelectFilter: (ColorFilterPreset) -> Unit,
    onSelectEffect: (VisualEffectPreset) -> Unit,
    onSelectAspect: (AspectRatioPreset) -> Unit,
    onOpenTransformBlend: () -> Unit,
    onOpenAudioMixer: () -> Unit,
    onToggleProxy: () -> Unit,
    onRotateClip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolItems: @Composable () -> Unit = {
        when (toolMode) {
            EditorToolMode.ROOT -> {
                BottomToolItem(
                    icon = Icons.Default.ContentCut,
                    label = stringResource(R.string.editor_tab_trim),
                    testTag = "tab_trim",
                    onClick = { onSelectMode(EditorToolMode.TRIM) },
                )
                BottomToolItem(
                    icon = Icons.Default.MusicNote,
                    label = stringResource(R.string.editor_tab_music),
                    testTag = "tool_add_audio",
                    onClick = onAddAudio,
                )
                BottomToolItem(
                    icon = Icons.Default.TextFields,
                    label = stringResource(R.string.editor_tab_text),
                    testTag = "tool_add_text",
                    onClick = onAddTextAndOpenSubbar,
                )
                BottomToolItem(
                    icon = Icons.Default.Diamond,
                    label = stringResource(R.string.editor_tab_keyframe),
                    selectedState = keyframeActive,
                    accent = if (keyframeActive) VyroColors.StudioAmber else VyroColors.TextPrimary,
                    testTag = "keyframe_button",
                    onClick = { onSelectMode(EditorToolMode.KEYFRAME) },
                )
                BottomToolItem(
                    icon = Icons.Default.Animation,
                    label = stringResource(R.string.editor_tab_transition),
                    enabled = hasSelection && !isTrackLocked,
                    selectedState = currentTransition != TransitionPreset.NONE,
                    accent = if (currentTransition != TransitionPreset.NONE) VyroColors.ElectricCyan else VyroColors.TextPrimary,
                    testTag = "tab_transition",
                    onClick = { onSelectMode(EditorToolMode.TRANSITION) },
                )
                BottomToolItem(
                    icon = Icons.Default.AutoFixHigh,
                    label = stringResource(R.string.editor_tab_effect),
                    selectedState = currentEffect != VisualEffectPreset.NONE,
                    accent = if (currentEffect != VisualEffectPreset.NONE) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                    testTag = "tab_effect",
                    onClick = { onSelectMode(EditorToolMode.EFFECT) },
                )
                BottomToolItem(
                    icon = Icons.Default.AspectRatio,
                    label = stringResource(R.string.editor_tab_canvas),
                    testTag = "tab_canvas",
                    onClick = { onSelectMode(EditorToolMode.CANVAS) },
                )
                BottomToolItem(
                    icon = Icons.Default.FilterVintage,
                    label = stringResource(R.string.editor_tab_filter),
                    selectedState = currentFilter != ColorFilterPreset.ORIGINAL,
                    accent = if (currentFilter != ColorFilterPreset.ORIGINAL) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                    testTag = "tab_filter",
                    onClick = { onSelectMode(EditorToolMode.FILTER) },
                )
                BottomToolItem(
                    icon = Icons.Default.AspectRatio,
                    label = stringResource(R.string.editor_tab_transform),
                    enabled = hasSelection && !isTrackLocked,
                    testTag = "tab_transform_blend",
                    onClick = onOpenTransformBlend,
                )
                BottomToolItem(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = stringResource(R.string.editor_tab_audio_mix),
                    enabled = hasAudioTarget,
                    selectedState = hasAudioTarget,
                    accent = if (hasAudioTarget) VyroColors.VideoEmerald else VyroColors.TextPrimary,
                    testTag = "tab_audio_mix",
                    onClick = onOpenAudioMixer,
                )
                BottomToolItem(
                    icon = Icons.Default.Memory,
                    label = stringResource(if (proxyEnabled) R.string.editor_tab_proxy_on else R.string.editor_tab_proxy_off),
                    selectedState = proxyEnabled,
                    accent = if (proxyEnabled) VyroColors.ElectricCyan else VyroColors.TextPrimary,
                    testTag = "tab_proxy_mode",
                    onClick = onToggleProxy,
                )
            }

            EditorToolMode.TRIM -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                BottomToolItem(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.editor_tool_prev_frame), testTag = "tool_step_prev_frame", onClick = onStepBackward)
                BottomToolItem(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.editor_tool_next_frame), testTag = "tool_step_next_frame", onClick = onStepForward)
                BottomToolItem(Icons.Default.ContentCut, stringResource(R.string.editor_tool_split), enabled = hasSelection && !isTrackLocked, testTag = "tool_split", onClick = onSplit)
                BottomToolItem(Icons.Default.ContentCut, stringResource(R.string.editor_tool_trim_shorter), enabled = hasSelection && !isTrackLocked, testTag = "tool_trim_minus", onClick = onTrimShorter)
                BottomToolItem(Icons.Default.Add, stringResource(R.string.editor_tool_trim_longer), enabled = hasSelection && !isTrackLocked, testTag = "tool_trim_plus", onClick = onTrimLonger)
                BottomToolItem(Icons.Default.AspectRatio, stringResource(R.string.editor_tool_rotate), enabled = hasSelection && !isTrackLocked, testTag = "tool_rotate_clip", onClick = onRotateClip)
                BottomToolItem(
                    icon = if (isTrackLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    label = stringResource(if (isTrackLocked) R.string.editor_tool_unlock_track else R.string.editor_tool_lock_track),
                    enabled = hasSelection,
                    selectedState = isTrackLocked,
                    accent = if (isTrackLocked) VyroColors.StudioAmber else VyroColors.TextPrimary,
                    testTag = "tool_lock_track",
                    onClick = onToggleTrackLock,
                )
                BottomToolItem(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.editor_tool_delete),
                    enabled = hasSelection && !isTrackLocked,
                    accent = VyroColors.CoralError,
                    testTag = "tool_delete",
                    onClick = onDelete,
                )
            }

            EditorToolMode.TEXT -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                BottomToolItem(Icons.Default.Add, stringResource(R.string.editor_tool_add), testTag = "tool_add_text_sub", onClick = onAddText)
                BottomToolItem(Icons.Default.Edit, stringResource(R.string.editor_tool_edit), enabled = isTextClipSelected && !isTrackLocked, testTag = "tool_edit_text", onClick = onEditText)
                BottomToolItem(Icons.Default.FontDownload, stringResource(R.string.editor_tool_font), enabled = isTextClipSelected && !isTrackLocked, testTag = "tool_font", onClick = onFont)
                BottomToolItem(Icons.Default.ColorLens, stringResource(R.string.editor_tool_color), enabled = isTextClipSelected && !isTrackLocked, testTag = "tool_color", onClick = onColor)
                BottomToolItem(Icons.Default.Animation, stringResource(R.string.editor_tool_animation), enabled = isTextClipSelected && !isTrackLocked, testTag = "tool_animate", onClick = onAnimation)
                BottomToolItem(Icons.Default.ContentCut, stringResource(R.string.editor_tool_split), enabled = isTextClipSelected && !isTrackLocked, testTag = "tool_split", onClick = onSplit)
                BottomToolItem(Icons.Default.DeleteOutline, stringResource(R.string.editor_tool_delete), enabled = isTextClipSelected && !isTrackLocked, accent = VyroColors.CoralError, testTag = "tool_delete", onClick = onDelete)
            }

            EditorToolMode.KEYFRAME -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                BottomToolItem(
                    icon = Icons.Default.Diamond,
                    label = stringResource(R.string.editor_keyframe_set),
                    enabled = hasSelection && !isTrackLocked,
                    selectedState = hasKeyframeAtPlayhead,
                    accent = if (hasKeyframeAtPlayhead) VyroColors.StudioAmber else VyroColors.ElectricCyan,
                    testTag = "keyframe_add_button",
                    onClick = onSetKeyframeAtPlayhead,
                )
                BottomToolItem(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.editor_keyframe_remove),
                    enabled = hasSelection && hasKeyframeAtPlayhead && !isTrackLocked,
                    accent = VyroColors.CoralError,
                    testTag = "keyframe_remove_button",
                    onClick = onRemoveKeyframeAtPlayhead,
                )
                BottomToolItem(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    label = stringResource(R.string.editor_keyframe_prev),
                    enabled = hasKeyframesOnClip,
                    testTag = "keyframe_prev_button",
                    onClick = onJumpPrevKeyframe,
                )
                BottomToolItem(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    label = stringResource(R.string.editor_keyframe_next),
                    enabled = hasKeyframesOnClip,
                    testTag = "keyframe_next_button",
                    onClick = onJumpNextKeyframe,
                )
                BottomToolItem(
                    icon = Icons.Default.Add,
                    label = stringResource(R.string.editor_keyframe_scale_up),
                    enabled = hasSelection && !isTrackLocked,
                    accent = VyroColors.StudioAmber,
                    testTag = "keyframe_scale_up_button",
                    onClick = onStepKeyframeScale,
                )
                BottomToolItem(
                    icon = Icons.Default.AspectRatio,
                    label = stringResource(R.string.editor_tab_transform),
                    enabled = hasSelection && !isTrackLocked,
                    testTag = "keyframe_transform_button",
                    onClick = onOpenTransformBlend,
                )
                BottomToolItem(
                    icon = Icons.Default.Diamond,
                    label = stringResource(R.string.editor_keyframe_toggle),
                    enabled = hasSelection && !isTrackLocked,
                    selectedState = keyframeActive,
                    accent = if (keyframeActive) VyroColors.StudioAmber else VyroColors.TextPrimary,
                    testTag = "keyframe_toggle_button",
                    onClick = onToggleKeyframe,
                )
            }

            EditorToolMode.TRANSITION -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                TransitionPreset.entries.forEach { preset ->
                    BottomToolItem(
                        icon = Icons.Default.Animation,
                        label = preset.displayName.uppercase(),
                        enabled = hasSelection && !isTrackLocked,
                        selectedState = preset == currentTransition,
                        accent = if (preset == currentTransition) VyroColors.ElectricCyan else VyroColors.TextPrimary,
                        testTag = "transition_${preset.name.lowercase()}",
                        onClick = { onSelectTransition(preset) },
                    )
                }
            }

            EditorToolMode.FILTER -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                ColorFilterPreset.entries.forEach { preset ->
                    BottomToolItem(
                        icon = Icons.Default.FilterVintage,
                        label = preset.displayName.uppercase(),
                        selectedState = preset == currentFilter,
                        accent = if (preset == currentFilter) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                        testTag = "filter_${preset.name.lowercase()}",
                        onClick = { onSelectFilter(preset) },
                    )
                }
            }

            EditorToolMode.EFFECT -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                VisualEffectPreset.entries.forEach { preset ->
                    BottomToolItem(
                        icon = Icons.Default.AutoFixHigh,
                        label = preset.displayName.uppercase(),
                        selectedState = preset == currentEffect,
                        accent = if (preset == currentEffect) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                        testTag = "effect_${preset.name.lowercase()}",
                        onClick = { onSelectEffect(preset) },
                    )
                }
            }

            EditorToolMode.CANVAS -> {
                BackToolItem(onClick = { onSelectMode(EditorToolMode.ROOT) })
                AspectRatioPreset.entries.forEach { preset ->
                    val active = currentWidth == preset.width && currentHeight == preset.height
                    BottomToolItem(
                        icon = Icons.Default.AspectRatio,
                        label = preset.label,
                        selectedState = active,
                        accent = if (active) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                        testTag = "aspect_${preset.name.lowercase()}",
                        onClick = { onSelectAspect(preset) },
                    )
                }
            }
        }
    }

    if (isSideRail) {
        Column(
            modifier = modifier
                .background(VyroColors.Obsidian)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (toolMode == EditorToolMode.ROOT) "STUDIO TOOLS" else "${toolMode.name} TOOLS",
                style = MaterialTheme.typography.labelMedium,
                color = VyroColors.TextSecondary,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                toolItems()
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(VyroColors.Obsidian)
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 3.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolItems()
        }
    }
}

@Composable
private fun BackToolItem(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("tool_subbar_back"),
        color = VyroColors.SlateControl,
        shape = RoundedCornerShape(10.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.editor_tool_back),
                tint = VyroColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun BottomToolItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    selectedState: Boolean = false,
    accent: Color = VyroColors.TextPrimary,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selectedState && enabled) accent.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(testTag)
            .semantics {
                selected = selectedState
                stateDescription = if (selectedState) "Active" else "Inactive"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) accent else VyroColors.SteelMuted,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            color = if (enabled) {
                if (accent == VyroColors.TextPrimary) VyroColors.TextSecondary else accent
            } else {
                VyroColors.SteelMuted
            },
        )
    }
}

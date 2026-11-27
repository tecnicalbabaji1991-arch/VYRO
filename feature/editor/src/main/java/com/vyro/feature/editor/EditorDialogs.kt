package com.vyro.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import com.vyro.core.contracts.AudioMixEngine
import com.vyro.core.contracts.DeviceCapabilities
import com.vyro.core.contracts.WaveformProfile
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.BlendMode
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.CropRect
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TextClipSpec

@Composable
internal fun TextEditDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.editor_edit_text_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("text_layer_input"),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (value.isNotBlank()) onConfirm(value) },
                modifier = Modifier.testTag("confirm_text_edit_button"),
            ) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel))
            }
        },
    )
}

@Composable
internal fun FontPickerDialog(
    currentFontId: String,
    onDismiss: () -> Unit,
    onSelectFont: (String) -> Unit,
) {
    val fonts = listOf(
        "space_grotesk" to "Space Grotesk (Studio Display)",
        "plus_jakarta_sans" to "Plus Jakarta Sans (Editorial)",
        "jetbrains_mono" to "JetBrains Mono (Timecode)",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.editor_font_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fonts.forEach { (id, label) ->
                    val isSelected = id == currentFontId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .clickable(role = Role.RadioButton) { onSelectFont(id) }
                            .semantics { selected = isSelected },
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected) VyroColors.CrimsonAccent.copy(alpha = 0.16f) else VyroColors.SlateControl,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) VyroColors.CrimsonAccent else VyroColors.SteelDivider,
                        ),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

@Composable
internal fun ColorPickerDialog(
    currentColorArgb: Long,
    onDismiss: () -> Unit,
    onSelectColor: (Long) -> Unit,
) {
    val palette = listOf(
        0xFFFFFFFFL to "White",
        0xFF00E5FFL to "Cyan",
        0xFFFF3B6BL to "Crimson",
        0xFFF3B768L to "Amber",
        0xFF10B981L to "Emerald",
        0xFF8B5CF6L to "Violet",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.editor_color_title)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                palette.forEach { (argb, colorName) ->
                    val isSelected = argb == currentColorArgb
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.RadioButton) { onSelectColor(argb) }
                            .semantics {
                                contentDescription = "$colorName text color"
                                selected = isSelected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) VyroColors.CrimsonAccent else VyroColors.SteelDivider,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

@Composable
internal fun AnimationPickerDialog(
    currentSpec: TextClipSpec,
    onDismiss: () -> Unit,
    onSelectPreset: (TextAnimationPreset) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.editor_animation_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextAnimationPreset.entries.forEach { preset ->
                    val selected = preset == currentSpec.animationPreset
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPreset(preset) }
                            .testTag("anim_${preset.name.lowercase()}"),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) VyroColors.CrimsonAccent.copy(alpha = 0.16f) else VyroColors.SlateControl,
                        border = BorderStroke(
                            1.dp,
                            if (selected) VyroColors.CrimsonAccent else VyroColors.SteelDivider,
                        ),
                    ) {
                        Text(
                            text = preset.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

@Composable
internal fun CoverEditDialog(
    currentCover: CoverSpec,
    onDismiss: () -> Unit,
    onConfirm: (CoverSpec) -> Unit,
) {
    var label by remember { mutableStateOf(currentCover.label) }
    var colorArgb by remember { mutableLongStateOf(currentCover.accentColorArgb) }
    val palette = listOf(0xFF1E222AL, 0xFFF34F73L, 0xFFF3B768L, 0xFF0091A6L, 0xFF6D44C7L)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.editor_cover_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.editor_cover_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    palette.forEachIndexed { idx, argb ->
                        val isSelected = argb == colorArgb
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.RadioButton) { colorArgb = argb }
                                .semantics {
                                    contentDescription = "Cover swatch ${idx + 1}"
                                    selected = isSelected
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) VyroColors.CrimsonAccent else VyroColors.SteelDivider,
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(CoverSpec(label.ifBlank { "Cover" }, colorArgb)) }) {
                Text(stringResource(R.string.editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

@Composable
internal fun MediaAssetsDialog(
    assets: List<MediaAsset>,
    isRelinking: Boolean,
    onRelinkClick: (MediaAsset) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.editor_assets_dialog_title)) },
        text = {
            if (assets.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_assets_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VyroColors.TextSecondary,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    assets.forEach { asset ->
                        val statusAccent = when (asset.availability) {
                            AssetAvailability.AVAILABLE -> VyroColors.VideoEmerald
                            AssetAvailability.UNSUPPORTED_CODEC -> VyroColors.CoralError
                            AssetAvailability.MISSING, AssetAvailability.PERMISSION_REVOKED -> VyroColors.StudioAmber
                        }
                        val specSummary = buildString {
                            append(asset.mediaKind.name)
                            if (asset.width > 0 && asset.height > 0) {
                                append(" • ${asset.width}×${asset.height}")
                            }
                            if (asset.mediaKind == MediaKind.VIDEO) {
                                append(" • ${asset.nominalFps.toInt()}fps")
                                if (asset.isHdr) append(" HDR")
                                if (asset.isVfrSuspected) append(" VFR")
                            }
                            if (asset.audioSampleRateHz > 0) {
                                append(" • ${asset.audioSampleRateHz / 1000}kHz")
                            }
                            val codec = asset.videoCodec ?: asset.audioCodec
                            if (!codec.isNullOrBlank()) {
                                append(" • $codec")
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("asset_row_${asset.assetId}"),
                            shape = MaterialTheme.shapes.small,
                            color = VyroColors.SlateControl,
                            border = BorderStroke(1.dp, VyroColors.SteelDivider),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = asset.displayName,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = VyroColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    VyroBadge(
                                        label = asset.availability.name,
                                        accent = statusAccent,
                                    )
                                }
                                Text(
                                    text = specSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VyroColors.TextSecondary,
                                )
                                if (asset.diagnosticNote.isNotBlank()) {
                                    Text(
                                        text = asset.diagnosticNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusAccent,
                                    )
                                }
                                if (asset.mediaKind != MediaKind.MATERIAL) {
                                    OutlinedButton(
                                        onClick = { onRelinkClick(asset) },
                                        enabled = !isRelinking,
                                        modifier = Modifier.testTag("relink_asset_${asset.assetId}"),
                                    ) {
                                        Text(
                                            text = stringResource(
                                                if (isRelinking) R.string.editor_relinking_action else R.string.editor_relink_action,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_ok))
            }
        },
    )
}

@Composable
internal fun TransformBlendDialog(
    clipLabel: String,
    currentTransform: LayerTransform,
    onApplyTransform: (LayerTransform) -> Unit,
    onDismiss: () -> Unit,
) {
    val cropPresets = listOf(
        "Full Frame" to CropRect(0f, 0f, 1f, 1f),
        "90% Tight" to CropRect(0.05f, 0.05f, 0.95f, 0.95f),
        "75% Punch" to CropRect(0.125f, 0.125f, 0.875f, 0.875f),
        "60% Center" to CropRect(0.2f, 0.2f, 0.8f, 0.8f),
    )
    val opacityPresets = listOf(1.0f, 0.75f, 0.50f, 0.25f)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("${stringResource(R.string.editor_transform_dialog_title)} • $clipLabel") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.editor_crop_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cropPresets.forEach { (label, rect) ->
                        val selected = kotlin.math.abs(currentTransform.crop.left - rect.left) < 0.02f
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyTransform(currentTransform.copy(crop = rect)) }
                                .testTag("crop_preset_${label.lowercase().replace(' ', '_')}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.CrimsonAccent.copy(alpha = 0.2f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.CrimsonAccent else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.editor_opacity_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    opacityPresets.forEach { op ->
                        val selected = kotlin.math.abs(currentTransform.opacity - op) < 0.05f
                        val pct = (op * 100).toInt()
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyTransform(currentTransform.copy(opacity = op)) }
                                .testTag("opacity_preset_$pct"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.ElectricCyan.copy(alpha = 0.18f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.ElectricCyan else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) VyroColors.ElectricCyan else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.editor_blend_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlendMode.entries.forEach { mode ->
                        val selected = mode == currentTransform.blendMode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyTransform(currentTransform.copy(blendMode = mode)) }
                                .testTag("blend_mode_${mode.name.lowercase()}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.CrimsonAccent.copy(alpha = 0.18f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.CrimsonAccent else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = mode.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("transform_dialog_ok_button"),
            ) {
                Text(stringResource(R.string.editor_ok))
            }
        },
    )
}

@Composable
internal fun AudioMixerDialog(
    clipLabel: String,
    currentSpec: AudioClipSpec,
    waveform: WaveformProfile?,
    clipDurationUs: Long,
    onApplyAudioSpec: (AudioClipSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    val gainPresets = listOf(-18f to "-18dB", -6f to "-6dB", 0f to "0dB", 6f to "+6dB")
    val panPresets = listOf(-1.0f to "L", -0.5f to "Mid-L", 0.0f to "Center", 0.5f to "Mid-R", 1.0f to "R")
    val fadePresetsUs = listOf(0L to "0s", 250_000L to "0.25s", 500_000L to "0.5s", 1_000_000L to "1.0s")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("${stringResource(R.string.editor_audio_mixer_title)} • $clipLabel") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (waveform != null && waveform.peaks.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("audio_waveform_preview"),
                        shape = MaterialTheme.shapes.small,
                        color = VyroColors.SlateControl,
                        border = BorderStroke(1.dp, VyroColors.SteelDivider),
                    ) {
                        val gainScale = AudioMixEngine.dbToLinear(currentSpec.gainDb).coerceIn(0.15f, 1.5f)
                        Canvas(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                            val midY = size.height / 2f
                            val step = size.width / waveform.peaks.size.coerceAtLeast(1)
                            val durUs = clipDurationUs.coerceAtLeast(1_000_000L)
                            waveform.peaks.forEachIndexed { idx, pk ->
                                val norm = (idx + 0.5f) / waveform.peaks.size.toFloat()
                                val tUs = (norm * durUs).toLong()
                                val fade = AudioMixEngine.fadeMultiplier(
                                    clipElapsedUs = tUs,
                                    clipRemainingUs = (durUs - tUs).coerceAtLeast(0L),
                                    fadeInUs = currentSpec.fadeInUs,
                                    fadeOutUs = currentSpec.fadeOutUs,
                                )
                                val halfH = (pk * gainScale * fade * midY).coerceIn(2f, midY)
                                val x = (idx + 0.5f) * step
                                drawLine(
                                    color = Color(0xFF5EEAD4),
                                    start = Offset(x, midY - halfH),
                                    end = Offset(x, midY + halfH),
                                    strokeWidth = (step * 0.55f).coerceAtLeast(2f),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.editor_audio_gain_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    gainPresets.forEach { (db, label) ->
                        val selected = kotlin.math.abs(currentSpec.gainDb - db) < 0.5f
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyAudioSpec(currentSpec.copy(gainDb = db)) }
                                .testTag("gain_preset_${db.toInt()}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.VideoEmerald.copy(alpha = 0.2f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.VideoEmerald else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) VyroColors.VideoEmerald else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.editor_audio_pan_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    panPresets.forEach { (panVal, label) ->
                        val selected = kotlin.math.abs(currentSpec.pan - panVal) < 0.1f
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyAudioSpec(currentSpec.copy(pan = panVal)) }
                                .testTag("pan_preset_${label.lowercase().replace('-', '_')}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.ElectricCyan.copy(alpha = 0.18f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.ElectricCyan else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) VyroColors.ElectricCyan else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.editor_audio_fade_in_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    fadePresetsUs.forEach { (us, label) ->
                        val selected = currentSpec.fadeInUs == us
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyAudioSpec(currentSpec.copy(fadeInUs = us)) }
                                .testTag("fade_in_preset_${us / 1000L}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.CrimsonAccent.copy(alpha = 0.2f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.CrimsonAccent else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.editor_audio_fade_out_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = VyroColors.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    fadePresetsUs.forEach { (us, label) ->
                        val selected = currentSpec.fadeOutUs == us
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onApplyAudioSpec(currentSpec.copy(fadeOutUs = us)) }
                                .testTag("fade_out_preset_${us / 1000L}"),
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) VyroColors.StudioAmber.copy(alpha = 0.2f) else VyroColors.SlateControl,
                            border = BorderStroke(1.dp, if (selected) VyroColors.StudioAmber else VyroColors.SteelDivider),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) VyroColors.StudioAmber else VyroColors.TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("audio_mixer_ok_button"),
            ) {
                Text(stringResource(R.string.editor_ok))
            }
        },
    )
}


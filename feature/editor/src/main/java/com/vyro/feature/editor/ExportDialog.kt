package com.vyro.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vyro.core.contracts.ExportCodecCapabilityReport
import com.vyro.core.contracts.ExportConfig
import com.vyro.core.contracts.ExportPhase
import com.vyro.core.contracts.ExportProgressSnapshot
import com.vyro.core.contracts.ExportResolutionPreset
import com.vyro.core.contracts.ExportVideoCodec
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.model.TimecodeFormatter
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun ExportStudioDialog(
    config: ExportConfig,
    capabilities: ExportCodecCapabilityReport,
    progress: ExportProgressSnapshot,
    sequenceDurationUs: Long,
    onSelectPreset: (ExportResolutionPreset) -> Unit,
    onSelectCodec: (ExportVideoCodec) -> Unit,
    onToggleIncludeAudio: (Boolean) -> Unit,
    onToggleSaveToGallery: (Boolean) -> Unit,
    onStartExport: () -> Unit,
    onCancelExport: () -> Unit,
    onResetAndReconfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isActive = progress.phase in setOf(
        ExportPhase.VALIDATING,
        ExportPhase.PREPARING,
        ExportPhase.ENCODING,
        ExportPhase.MUXING,
        ExportPhase.VERIFYING,
    )
    val isFinalized = progress.phase == ExportPhase.FINALIZED
    val isFailedOrCancelled = progress.phase == ExportPhase.FAILED || progress.phase == ExportPhase.CANCELLED

    AlertDialog(
        modifier = Modifier.testTag("export_dialog"),
        onDismissRequest = { if (!isActive) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MovieCreation,
                    contentDescription = null,
                    tint = VyroColors.CrimsonAccent,
                )
                Text(
                    text = stringResource(R.string.export_studio_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Capability summary strip
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = VyroColors.SlateControl,
                    border = BorderStroke(1.dp, VyroColors.SteelDivider),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${config.width}×${config.height} @ ${config.frameRate.nominalFpsInt}fps • ${TimecodeFormatter.formatCompactTenths(sequenceDurationUs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = VyroColors.TextPrimary,
                            )
                            VyroBadge(
                                label = progress.phase.name,
                                accent = when {
                                    isFinalized -> VyroColors.VideoEmerald
                                    isActive -> VyroColors.ElectricCyan
                                    isFailedOrCancelled -> VyroColors.CoralError
                                    else -> VyroColors.StudioAmber
                                },
                            )
                        }
                        Text(
                            text = capabilities.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = VyroColors.TextSecondary,
                        )
                    }
                }

                if (!isActive && !isFinalized) {
                    Text(
                        text = stringResource(R.string.export_resolution_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = VyroColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExportResolutionPreset.entries.forEach { preset ->
                            val selected = preset == config.resolutionPreset
                            val tagSuffix = when (preset) {
                                ExportResolutionPreset.SD_480P -> "480p"
                                ExportResolutionPreset.HD_720P -> "720p"
                                ExportResolutionPreset.FHD_1080P -> "1080p"
                                ExportResolutionPreset.SEQUENCE_NATIVE -> "native"
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onSelectPreset(preset) }
                                    .testTag("export_res_$tagSuffix"),
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) VyroColors.CrimsonAccent.copy(alpha = 0.2f) else VyroColors.SlateControl,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) VyroColors.CrimsonAccent else VyroColors.SteelDivider,
                                ),
                            ) {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) VyroColors.CrimsonAccent else VyroColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.export_codec_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = VyroColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExportVideoCodec.entries.forEach { codec ->
                            val selected = codec == config.videoCodec
                            val tagSuffix = if (codec == ExportVideoCodec.H264_AVC) "h264" else "hevc"
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onSelectCodec(codec) }
                                    .testTag("export_codec_$tagSuffix"),
                                shape = MaterialTheme.shapes.small,
                                color = if (selected) VyroColors.ElectricCyan.copy(alpha = 0.18f) else VyroColors.SlateControl,
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) VyroColors.ElectricCyan else VyroColors.SteelDivider,
                                ),
                            ) {
                                Text(
                                    text = codec.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) VyroColors.ElectricCyan else VyroColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.export_options_section),
                        style = MaterialTheme.typography.labelMedium,
                        color = VyroColors.TextSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.export_include_audio),
                            style = MaterialTheme.typography.bodyMedium,
                            color = VyroColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = config.includeAudio,
                            onCheckedChange = onToggleIncludeAudio,
                            modifier = Modifier.testTag("export_include_audio_toggle"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.export_save_gallery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = VyroColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = config.saveToGallery,
                            onCheckedChange = onToggleSaveToGallery,
                            modifier = Modifier.testTag("export_save_gallery_toggle"),
                        )
                    }
                }

                if (isActive) {
                    Surface(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        shape = MaterialTheme.shapes.small,
                        color = VyroColors.SlateControl,
                        border = BorderStroke(1.dp, VyroColors.ElectricCyan.copy(alpha = 0.5f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Phase: ${progress.phase.name}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VyroColors.ElectricCyan,
                                )
                                Text(
                                    text = "${(progress.progressFraction * 100f).roundToInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VyroColors.TextPrimary,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("export_progress_bar"),
                                color = VyroColors.CrimsonAccent,
                                trackColor = VyroColors.SteelDivider,
                            )
                            Text(
                                text = "Frames: ${progress.renderedFrames}/${progress.totalFrames}  •  Allocated: ${progress.allocatedResources}  •  Released: ${progress.releasedResources}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VyroColors.TextSecondary,
                            )
                        }
                    }
                }

                val validation = progress.validationReport
                if (isFinalized && validation != null) {
                    val v = validation
                    val outputName = progress.outputFilePath?.let { File(it).name } ?: "output.mp4"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("export_validation_card"),
                        shape = MaterialTheme.shapes.small,
                        color = VyroColors.VideoEmerald.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, VyroColors.VideoEmerald.copy(alpha = 0.6f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = VyroColors.VideoEmerald,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "Finalized & Validated: $outputName",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VyroColors.TextPrimary,
                                )
                            }
                            Text(
                                text = "Verified ${v.verifiedWidth}×${v.verifiedHeight} @ ${v.verifiedFps.roundToInt()}fps  •  Duration ${v.verifiedDurationUs / 1000L} ms  •  Size ${v.fileSizeBytes / 1024L} KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = VyroColors.TextSecondary,
                            )
                            Text(
                                text = "Audio Track: ${v.hasAudioTrack}  •  A/V Delta: ${v.avTimingDeltaUs / 1000L} ms  •  Backend: ${v.backendUsed}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VyroColors.VideoEmerald,
                            )
                            Text(
                                text = v.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = VyroColors.TextSecondary,
                            )
                        }
                    }
                }

                if (isFailedOrCancelled) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("export_error_card"),
                        shape = MaterialTheme.shapes.small,
                        color = VyroColors.CoralError.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, VyroColors.CoralError.copy(alpha = 0.6f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = VyroColors.CoralError,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = if (progress.phase == ExportPhase.CANCELLED) {
                                        "Export Cancelled Safely"
                                    } else {
                                        progress.error?.userMessage ?: "Export Failed Safely"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VyroColors.TextPrimary,
                                )
                            }
                            progress.error?.diagnosticDetail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VyroColors.TextSecondary,
                                )
                            }
                            Text(
                                text = "Resources released: ${progress.releasedResources}/${progress.allocatedResources}",
                                style = MaterialTheme.typography.labelSmall,
                                color = VyroColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (isActive) {
                OutlinedButton(
                    onClick = onCancelExport,
                    modifier = Modifier.testTag("export_cancel_button"),
                ) {
                    Text(stringResource(R.string.export_cancel_action), color = VyroColors.CoralError)
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("export_close_button"),
                ) {
                    Text(stringResource(R.string.export_close_action))
                }
            }
        },
        confirmButton = {
            when {
                isActive -> Unit
                isFinalized -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onResetAndReconfigure,
                            modifier = Modifier.testTag("export_retry_button"),
                        ) {
                            Text(stringResource(R.string.export_retry_action))
                        }
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("export_done_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VyroColors.VideoEmerald,
                                contentColor = Color.Black,
                            ),
                        ) {
                            Text(stringResource(R.string.export_done_action))
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = onStartExport,
                        modifier = Modifier.testTag("export_start_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VyroColors.CrimsonAccent,
                            contentColor = Color.White,
                        ),
                    ) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.export_start_action))
                    }
                }
            }
        },
    )
}

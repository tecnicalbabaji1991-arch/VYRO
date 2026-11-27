package com.vyro.feature.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.vyro.core.contracts.DecoderBackendType
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.RenderGraph
import com.vyro.core.designsystem.JetBrainsMonoFamily
import com.vyro.core.designsystem.MediaPreviewCache
import com.vyro.core.designsystem.PlusJakartaSansFamily
import com.vyro.core.designsystem.SpaceGroteskFamily
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.designsystem.VyroDesignAssets
import com.vyro.core.model.BlendMode
import com.vyro.core.model.Clip
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.MediaKind
import com.vyro.core.model.Sequence
import com.vyro.core.model.TrackType
import com.vyro.core.model.VisualEffectPreset
import kotlin.math.roundToInt

@Composable
fun EditorPreviewCanvas(
    sequence: Sequence,
    activeClips: List<Clip>,
    selectedClipId: String?,
    playheadUs: Long,
    isFullscreen: Boolean,
    playbackDiagnostics: PlaybackDiagnosticsSnapshot = PlaybackDiagnosticsSnapshot(),
    renderGraph: RenderGraph? = null,
    onSurfaceAttached: (Surface, Int, Int) -> Unit = { _, _, _ -> },
    onSurfaceDetached: (Surface) -> Unit = {},
    onExitFullscreen: () -> Unit,
    onSelectClip: (String) -> Unit,
    onUpdateTransform: (String, LayerTransform) -> Unit,
    onRequestTextEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ratio = (sequence.width.toFloat() / sequence.height.coerceAtLeast(1).toFloat()).coerceIn(0.45f, 2.0f)
    val activeVideo = activeClips.firstOrNull { clip ->
        sequence.tracks.any { it.id == clip.trackId && it.type == TrackType.VIDEO }
    }
    val textClips = activeClips.filter { it.textSpec != null }

    val context = LocalContext.current
    var frameBitmap by remember(activeVideo?.mediaSource?.uriString) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(activeVideo?.mediaSource?.uriString) {
        val uri = activeVideo?.mediaSource?.uriString
        frameBitmap = if (uri != null) {
            MediaPreviewCache.loadThumbnail(context, uri)
        } else {
            null
        }
    }

    val colorFilter = remember(activeVideo?.filterPreset) {
        when (activeVideo?.filterPreset ?: ColorFilterPreset.ORIGINAL) {
            ColorFilterPreset.ORIGINAL -> null
            ColorFilterPreset.NOIR_MONO -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            ColorFilterPreset.VIVID_POP -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.45f) })
            ColorFilterPreset.WARM_AMBER -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        1.12f, 0f, 0f, 0f, 14f,
                        0f, 1.02f, 0f, 0f, 6f,
                        0f, 0f, 0.86f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            ColorFilterPreset.CYBER_CYAN -> ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.84f, 0f, 0f, 0f, 0f,
                        0f, 1.08f, 0f, 0f, 10f,
                        0f, 0f, 1.18f, 0f, 18f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(VyroColors.Obsidian)
            .padding(horizontal = if (isFullscreen) 8.dp else 0.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .testTag("preview_canvas"),
            shape = RoundedCornerShape(2.dp),
            color = Color(0xFF070A0F),
            border = BorderStroke(0.5.dp, VyroColors.SteelDivider.copy(alpha = 0.5f)),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val density = LocalDensity.current

                if (activeVideo != null) {
                    val source = activeVideo.mediaSource
                    val crop = activeVideo.transform.crop.normalized()
                    val cropZoom = (1f / minOf(crop.widthFraction, crop.heightFraction)).coerceIn(1f, 4f)
                    val layerScale = activeVideo.transform.scale.coerceIn(0.35f, 3f) * cropZoom
                    val layerAlpha = activeVideo.transform.opacity.coerceIn(0f, 1f)
                    val layerRot = activeVideo.transform.rotationDegrees
                    val isExternalVideo = source.mediaKind == MediaKind.VIDEO && !source.uriString.isNullOrBlank()
                    val usingSurfaceVideo = isExternalVideo &&
                        playbackDiagnostics.activeDecoderBackend == DecoderBackendType.MEDIA_CODEC_SURFACE &&
                        playbackDiagnostics.surfaceAttached &&
                        playbackDiagnostics.decodedFrames > 0L

                    val videoLayerModifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer {
                            scaleX = layerScale
                            scaleY = layerScale
                            rotationZ = layerRot
                            alpha = layerAlpha
                        }

                    if (isExternalVideo) {
                        PreviewSurfaceStage(
                            onSurfaceAttached = onSurfaceAttached,
                            onSurfaceDetached = onSurfaceDetached,
                            modifier = videoLayerModifier.testTag("preview_surface_view"),
                        )
                    }

                    if (!usingSurfaceVideo) {
                        when {
                            frameBitmap != null -> {
                                Image(
                                    bitmap = frameBitmap!!,
                                    contentDescription = activeVideo.label,
                                    contentScale = ContentScale.Crop,
                                    colorFilter = colorFilter,
                                    modifier = videoLayerModifier,
                                )
                            }
                            source.materialId == "studio_reel" -> {
                                Image(
                                    painter = painterResource(id = VyroDesignAssets.studioReelDrawableRes),
                                    contentDescription = activeVideo.label,
                                    contentScale = ContentScale.Crop,
                                    colorFilter = colorFilter,
                                    modifier = videoLayerModifier,
                                )
                            }
                            else -> {
                                Box(
                                    modifier = videoLayerModifier.background(Color(source.backdropColorArgb)),
                                )
                            }
                        }
                    }

                    // Filter & Blend tint overlay
                    val tintOverlay = when (activeVideo.filterPreset) {
                        ColorFilterPreset.ORIGINAL -> Color.Transparent
                        ColorFilterPreset.CYBER_CYAN -> VyroColors.ElectricCyan.copy(alpha = 0.16f)
                        ColorFilterPreset.WARM_AMBER -> VyroColors.HeroAmberStart.copy(alpha = 0.22f)
                        ColorFilterPreset.NOIR_MONO -> Color.Black.copy(alpha = 0.35f)
                        ColorFilterPreset.VIVID_POP -> VyroColors.HeroCoralEnd.copy(alpha = 0.14f)
                    }
                    if (tintOverlay != Color.Transparent) {
                        Box(modifier = Modifier.fillMaxSize().background(tintOverlay))
                    }
                    val blendTint = when (activeVideo.transform.blendMode) {
                        BlendMode.NORMAL -> Color.Transparent
                        BlendMode.SCREEN -> Color.White.copy(alpha = 0.12f)
                        BlendMode.MULTIPLY -> Color.Black.copy(alpha = 0.18f)
                        BlendMode.ADD -> VyroColors.ElectricCyan.copy(alpha = 0.14f)
                        BlendMode.OVERLAY -> VyroColors.CrimsonAccent.copy(alpha = 0.10f)
                    }
                    if (blendTint != Color.Transparent) {
                        Box(modifier = Modifier.fillMaxSize().background(blendTint))
                    }

                    // Visual effect preview overlays
                    when (activeVideo.effectPreset) {
                        VisualEffectPreset.NONE -> Unit
                        VisualEffectPreset.VIGNETTE -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                                        ),
                                    ),
                            )
                        }
                        VisualEffectPreset.SOFT_GLOW -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                                        ),
                                    ),
                            )
                        }
                        VisualEffectPreset.SCANLINES -> {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                var y = 0f
                                val step = 10f
                                while (y < size.height) {
                                    drawLine(
                                        color = Color.Black.copy(alpha = 0.16f),
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = 2f,
                                    )
                                    y += step
                                }
                            }
                        }
                    }

                    if (activeVideo.filterPreset != ColorFilterPreset.ORIGINAL || activeVideo.effectPreset != VisualEffectPreset.NONE) {
                        val badgeLabel = buildString {
                            if (activeVideo.filterPreset != ColorFilterPreset.ORIGINAL) append(activeVideo.filterPreset.displayName)
                            if (activeVideo.effectPreset != VisualEffectPreset.NONE) {
                                if (isNotEmpty()) append(" • ")
                                append(activeVideo.effectPreset.displayName)
                            }
                        }
                        VyroBadge(
                            label = badgeLabel,
                            accent = VyroColors.ElectricCyan,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "NO VIDEO CLIP AT PLAYHEAD",
                            style = MaterialTheme.typography.labelMedium,
                            color = VyroColors.SteelMuted,
                        )
                    }
                }

                // Interactive text overlays driven by RenderGraph evaluation
                textClips.forEach { clip ->
                    val textSpec = clip.textSpec ?: return@forEach
                    val graphNode = renderGraph?.layers?.firstOrNull { it.clipId == clip.id }
                    val isSelected = clip.id == selectedClipId
                    val fontFamily = when (textSpec.fontId) {
                        "jetbrains_mono" -> JetBrainsMonoFamily
                        "plus_jakarta_sans" -> PlusJakartaSansFamily
                        "space_grotesk" -> SpaceGroteskFamily
                        else -> FontFamily.Default
                    }

                    var dragX by remember(clip.id, clip.transform.normalizedX) {
                        mutableFloatStateOf(clip.transform.normalizedX)
                    }
                    var dragY by remember(clip.id, clip.transform.normalizedY) {
                        mutableFloatStateOf(clip.transform.normalizedY)
                    }
                    var currentScale by remember(clip.id, clip.transform.scale) {
                        mutableFloatStateOf(clip.transform.scale)
                    }
                    var currentRot by remember(clip.id, clip.transform.rotationDegrees) {
                        mutableFloatStateOf(clip.transform.rotationDegrees)
                    }
                    var isDragging by remember { mutableStateOf(false) }

                    val animOffsetY = graphNode?.animatedTranslationY ?: 0f
                    val animScale = graphNode?.animatedScale ?: 1f
                    val animAlpha = graphNode?.animatedAlpha ?: 1f
                    val centerXPx = (if (isDragging) dragX else clip.transform.normalizedX) * stageW
                    val centerYPx = ((if (isDragging) dragY else clip.transform.normalizedY) - animOffsetY) * stageH

                    val boxModifier = Modifier
                        .offset {
                            IntOffset(
                                x = (centerXPx - with(density) { 80.dp.toPx() }).roundToInt(),
                                y = (centerYPx - with(density) { 24.dp.toPx() }).roundToInt(),
                            )
                        }
                        .scale((if (isDragging) currentScale else clip.transform.scale) * animScale)
                        .rotate(if (isDragging) currentRot else clip.transform.rotationDegrees)
                        .alpha((clip.transform.opacity * animAlpha).coerceIn(0f, 1f))
                        .pointerInput(clip.id) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    onSelectClip(clip.id)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    onUpdateTransform(
                                        clip.id,
                                        clip.transform.copy(
                                            normalizedX = dragX.coerceIn(0.1f, 0.9f),
                                            normalizedY = dragY.coerceIn(0.1f, 0.9f),
                                            scale = currentScale,
                                            rotationDegrees = currentRot,
                                        ),
                                    )
                                },
                                onDragCancel = { isDragging = false },
                            ) { change, dragAmount ->
                                change.consume()
                                dragX = (dragX + dragAmount.x / stageW).coerceIn(0.1f, 0.9f)
                                dragY = (dragY + dragAmount.y / stageH).coerceIn(0.1f, 0.9f)
                            }
                        }
                        .clickable { onSelectClip(clip.id) }

                    Box(modifier = boxModifier) {
                        Box(
                            modifier = Modifier
                                .then(
                                    if (isSelected) {
                                        Modifier
                                            .border(1.5.dp, VyroColors.CrimsonAccent, RoundedCornerShape(6.dp))
                                            .background(VyroColors.Obsidian.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = textSpec.text,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = textSpec.fontSizeSp.sp,
                                color = Color(textSpec.colorArgb),
                            )
                        }

                        if (isSelected) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 8.dp, y = (-14).dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                HandlePill(
                                    icon = Icons.Default.Edit,
                                    description = "Edit text content",
                                    onClick = { onRequestTextEdit(clip.id) },
                                )
                                HandlePill(
                                    icon = Icons.AutoMirrored.Filled.RotateRight,
                                    description = "Rotate 15 degrees",
                                    onClick = {
                                        val nextRot = (clip.transform.rotationDegrees + 15f) % 360f
                                        onUpdateTransform(clip.id, clip.transform.copy(rotationDegrees = nextRot))
                                    },
                                )
                                HandlePill(
                                    icon = Icons.Default.OpenWith,
                                    description = "Cycle scale",
                                    onClick = {
                                        val nextScale = if (clip.transform.scale >= 1.35f) 0.85f else clip.transform.scale + 0.15f
                                        onUpdateTransform(clip.id, clip.transform.copy(scale = nextScale))
                                    },
                                )
                            }
                        }
                    }
                }

                if (isFullscreen) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        shape = CircleShape,
                        color = VyroColors.Obsidian.copy(alpha = 0.72f),
                    ) {
                        IconButton(onClick = onExitFullscreen) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = "Exit fullscreen",
                                tint = VyroColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSurfaceStage(
    onSurfaceAttached: (Surface, Int, Int) -> Unit,
    onSurfaceDetached: (Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnAttach by rememberUpdatedState(onSurfaceAttached)
    val currentOnDetach by rememberUpdatedState(onSurfaceDetached)
    var boundSurface by remember { mutableStateOf<Surface?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            boundSurface?.let { surface ->
                currentOnDetach(surface)
                boundSurface = null
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val surface = holder.surface
                            if (surface != null && surface.isValid) {
                                boundSurface = surface
                                currentOnAttach(surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            val surface = holder.surface
                            if (surface != null && surface.isValid) {
                                boundSurface = surface
                                currentOnAttach(surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
                            }
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            val surface = holder.surface
                            if (surface != null) {
                                currentOnDetach(surface)
                            }
                            if (boundSurface === surface) {
                                boundSurface = null
                            }
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun HandlePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = VyroColors.CrimsonAccent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

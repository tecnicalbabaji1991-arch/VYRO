package com.vyro.feature.editor

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Crop
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlin.math.abs
import kotlin.math.roundToInt

private const val STAGE_SNAP_THRESHOLD = 0.025f

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
    onOpenTransformBlend: () -> Unit = {},
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(VyroColors.Obsidian)
            .clipToBounds()
            .padding(horizontal = if (isFullscreen) 8.dp else 0.dp, vertical = if (isFullscreen) 4.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val safeMaxW = maxWidth.coerceAtLeast(1.dp)
        val safeMaxH = maxHeight.coerceAtLeast(1.dp)
        val targetH = minOf(safeMaxH, safeMaxW / ratio)
        val targetW = (targetH * ratio).coerceAtMost(safeMaxW)

        Surface(
            modifier = Modifier
                .size(width = targetW, height = targetH)
                .testTag("preview_canvas"),
            shape = RoundedCornerShape(2.dp),
            color = Color(0xFF070A0F),
            border = BorderStroke(0.5.dp, VyroColors.SteelDivider.copy(alpha = 0.5f)),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val stageWidthDp = maxWidth
                val stageW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val stageH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                var showSnapX by remember { mutableStateOf(false) }
                var showSnapY by remember { mutableStateOf(false) }

                if (activeVideo != null) {
                    val source = activeVideo.mediaSource
                    val videoNode = renderGraph?.layers?.firstOrNull { it.clipId == activeVideo.id }
                    val resolvedCrop = (videoNode?.crop ?: activeVideo.transform.crop).normalized()
                    val cropZoom = (1f / minOf(resolvedCrop.widthFraction, resolvedCrop.heightFraction)).coerceIn(1f, 4f)
                    val isVideoSelected = activeVideo.id == selectedClipId

                    var dragVideoX by remember(activeVideo.id, activeVideo.transform.normalizedX) {
                        mutableFloatStateOf(activeVideo.transform.normalizedX)
                    }
                    var dragVideoY by remember(activeVideo.id, activeVideo.transform.normalizedY) {
                        mutableFloatStateOf(activeVideo.transform.normalizedY)
                    }
                    var dragVideoScale by remember(activeVideo.id, activeVideo.transform.scale) {
                        mutableFloatStateOf(activeVideo.transform.scale)
                    }
                    var dragVideoRot by remember(activeVideo.id, activeVideo.transform.rotationDegrees) {
                        mutableFloatStateOf(activeVideo.transform.rotationDegrees)
                    }
                    var isDraggingVideo by remember { mutableStateOf(false) }

                    val baseScale = if (isDraggingVideo) dragVideoScale else (videoNode?.scale ?: activeVideo.transform.scale)
                    val animScale = videoNode?.animatedScale ?: 1f
                    val layerScale = (baseScale.coerceIn(0.35f, 3f) * animScale) * cropZoom
                    val layerAlpha = ((videoNode?.opacity ?: activeVideo.transform.opacity) * (videoNode?.animatedAlpha ?: 1f)).coerceIn(0f, 1f)
                    val layerRot = if (isDraggingVideo) dragVideoRot else (videoNode?.rotationDegrees ?: activeVideo.transform.rotationDegrees)
                    val normX = if (isDraggingVideo) dragVideoX else (videoNode?.normalizedX ?: activeVideo.transform.normalizedX)
                    val normY = if (isDraggingVideo) dragVideoY else (videoNode?.normalizedY ?: activeVideo.transform.normalizedY)
                    val layerTx = ((normX - 0.5f) + (videoNode?.animatedTranslationX ?: 0f)) * stageW
                    val layerTy = ((normY - 0.5f) - (videoNode?.animatedTranslationY ?: 0f)) * stageH
                    val isExternalVideo = source.mediaKind == MediaKind.VIDEO && !source.uriString.isNullOrBlank()
                    val usingSurfaceVideo = isExternalVideo &&
                        playbackDiagnostics.activeDecoderBackend == DecoderBackendType.MEDIA_CODEC_SURFACE &&
                        playbackDiagnostics.surfaceAttached &&
                        playbackDiagnostics.decodedFrames > 0L

                    val stageGestureModifier = Modifier
                        .fillMaxSize()
                        .pointerInput(activeVideo.id, isVideoSelected) {
                            detectTapGestures(
                                onTap = { onSelectClip(activeVideo.id) },
                            )
                        }
                        .pointerInput(activeVideo.id, activeVideo.transform) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                if (!isDraggingVideo) {
                                    isDraggingVideo = true
                                    onSelectClip(activeVideo.id)
                                }
                                val rawX = (dragVideoX + pan.x / stageW).coerceIn(0.05f, 0.95f)
                                val rawY = (dragVideoY + pan.y / stageH).coerceIn(0.05f, 0.95f)
                                val snappedX = if (abs(rawX - 0.5f) <= STAGE_SNAP_THRESHOLD) 0.5f else rawX
                                val snappedY = if (abs(rawY - 0.5f) <= STAGE_SNAP_THRESHOLD) 0.5f else rawY
                                showSnapX = snappedX == 0.5f
                                showSnapY = snappedY == 0.5f
                                dragVideoX = snappedX
                                dragVideoY = snappedY
                                dragVideoScale = (dragVideoScale * zoom).coerceIn(0.35f, 3.0f)
                                dragVideoRot = (dragVideoRot + rotation) % 360f
                                onUpdateTransform(
                                    activeVideo.id,
                                    activeVideo.transform.copy(
                                        normalizedX = dragVideoX,
                                        normalizedY = dragVideoY,
                                        scale = dragVideoScale,
                                        rotationDegrees = dragVideoRot,
                                    ),
                                )
                            }
                        }

                    val videoLayerModifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer {
                            translationX = layerTx
                            translationY = layerTy
                            scaleX = layerScale
                            scaleY = layerScale
                            rotationZ = layerRot
                            alpha = layerAlpha
                        }

                    Box(modifier = stageGestureModifier) {
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

                    // Stage selection overlay when active video clip is selected
                    if (isVideoSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)
                                .border(1.5.dp, VyroColors.ElectricCyan.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                .testTag("stage_selection_overlay"),
                        ) {
                            SelectionCornerMarkers(color = VyroColors.ElectricCyan)
                            val isOffset = abs(activeVideo.transform.normalizedX - 0.5f) > 0.01f ||
                                abs(activeVideo.transform.normalizedY - 0.5f) > 0.01f ||
                                abs(activeVideo.transform.scale - 1.0f) > 0.01f
                            val handleContent: @Composable () -> Unit = {
                                HandlePill(
                                    icon = Icons.Default.Crop,
                                    description = stringResource(R.string.editor_stage_crop_blend_desc),
                                    containerColor = VyroColors.ElectricCyan,
                                    iconTint = VyroColors.Obsidian,
                                    testTag = "stage_handle_crop",
                                    onClick = onOpenTransformBlend,
                                )
                                HandlePill(
                                    icon = Icons.AutoMirrored.Filled.RotateRight,
                                    description = stringResource(R.string.editor_stage_rotate_desc),
                                    containerColor = VyroColors.ElectricCyan,
                                    iconTint = VyroColors.Obsidian,
                                    testTag = "stage_handle_rotate",
                                    onClick = {
                                        val nextRot = (activeVideo.transform.rotationDegrees + 90f) % 360f
                                        onUpdateTransform(activeVideo.id, activeVideo.transform.copy(rotationDegrees = nextRot))
                                    },
                                )
                                if (isOffset) {
                                    HandlePill(
                                        icon = Icons.Default.CenterFocusStrong,
                                        description = stringResource(R.string.editor_stage_reset_center_desc),
                                        containerColor = VyroColors.CrimsonAccent,
                                        iconTint = Color.White,
                                        testTag = "stage_handle_reset",
                                        onClick = {
                                            isDraggingVideo = false
                                            showSnapX = false
                                            showSnapY = false
                                            onUpdateTransform(
                                                activeVideo.id,
                                                activeVideo.transform.copy(
                                                    normalizedX = 0.5f,
                                                    normalizedY = 0.5f,
                                                    scale = 1.0f,
                                                    rotationDegrees = 0f,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                            if (stageWidthDp < 120.dp) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    handleContent()
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    handleContent()
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.editor_timeline_no_video).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = VyroColors.SteelMuted,
                        )
                    }
                }

                // Center alignment crosshairs during transform snap
                if (showSnapX || showSnapY) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (showSnapX) {
                            drawLine(
                                color = VyroColors.ElectricCyan.copy(alpha = 0.85f),
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                                strokeWidth = 2f,
                            )
                        }
                        if (showSnapY) {
                            drawLine(
                                color = VyroColors.ElectricCyan.copy(alpha = 0.85f),
                                start = Offset(0f, size.height / 2f),
                                end = Offset(size.width, size.height / 2f),
                                strokeWidth = 2f,
                            )
                        }
                    }
                }

                // Interactive text overlays driven by RenderGraph evaluation + multi-touch arbitration
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
                    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

                    val animOffsetX = graphNode?.animatedTranslationX ?: 0f
                    val animOffsetY = graphNode?.animatedTranslationY ?: 0f
                    val animScale = graphNode?.animatedScale ?: 1f
                    val animAlpha = graphNode?.animatedAlpha ?: 1f
                    val resolvedNormX = if (isDragging) dragX else (graphNode?.normalizedX ?: clip.transform.normalizedX)
                    val resolvedNormY = if (isDragging) dragY else (graphNode?.normalizedY ?: clip.transform.normalizedY)
                    val resolvedScale = if (isDragging) currentScale else (graphNode?.scale ?: clip.transform.scale)
                    val resolvedRotation = if (isDragging) currentRot else (graphNode?.rotationDegrees ?: clip.transform.rotationDegrees)
                    val resolvedOpacity = graphNode?.opacity ?: clip.transform.opacity
                    val visibleText = graphNode?.visibleTextCharCount?.let { count ->
                        textSpec.text.take(count.coerceIn(0, textSpec.text.length))
                    } ?: textSpec.text

                    val centerXPx = (resolvedNormX + animOffsetX) * stageW
                    val centerYPx = (resolvedNormY - animOffsetY) * stageH
                    val halfW = if (overlaySize.width > 0) overlaySize.width / 2f else 90f
                    val halfH = if (overlaySize.height > 0) overlaySize.height / 2f else 32f

                    val boxModifier = Modifier
                        .offset {
                            IntOffset(
                                x = (centerXPx - halfW).roundToInt(),
                                y = (centerYPx - halfH).roundToInt(),
                            )
                        }
                        .onSizeChanged { overlaySize = it }
                        .scale(resolvedScale * animScale)
                        .rotate(resolvedRotation)
                        .alpha((resolvedOpacity * animAlpha).coerceIn(0f, 1f))
                        .pointerInput(clip.id) {
                            detectTapGestures(
                                onTap = { onSelectClip(clip.id) },
                                onDoubleTap = {
                                    onSelectClip(clip.id)
                                    onRequestTextEdit(clip.id)
                                },
                            )
                        }
                        .pointerInput(clip.id, clip.transform) {
                            detectTransformGestures { _, pan, zoom, rotation ->
                                if (!isDragging) {
                                    isDragging = true
                                    onSelectClip(clip.id)
                                }
                                val rawX = (dragX + pan.x / stageW).coerceIn(0.08f, 0.92f)
                                val rawY = (dragY + pan.y / stageH).coerceIn(0.08f, 0.92f)
                                val snappedX = if (abs(rawX - 0.5f) <= STAGE_SNAP_THRESHOLD) 0.5f else rawX
                                val snappedY = if (abs(rawY - 0.5f) <= STAGE_SNAP_THRESHOLD) 0.5f else rawY
                                showSnapX = snappedX == 0.5f
                                showSnapY = snappedY == 0.5f
                                dragX = snappedX
                                dragY = snappedY
                                currentScale = (currentScale * zoom).coerceIn(0.4f, 2.8f)
                                currentRot = (currentRot + rotation) % 360f
                                onUpdateTransform(
                                    clip.id,
                                    clip.transform.copy(
                                        normalizedX = dragX,
                                        normalizedY = dragY,
                                        scale = currentScale,
                                        rotationDegrees = currentRot,
                                    ),
                                )
                            }
                        }
                        .testTag("text_overlay_${clip.id}")

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
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = visibleText,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = textSpec.fontSizeSp.sp,
                                color = Color(textSpec.colorArgb),
                            )
                        }

                        if (isSelected) {
                            SelectionCornerMarkers(color = VyroColors.CrimsonAccent)
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 12.dp, y = (-26).dp),
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                HandlePill(
                                    icon = Icons.Default.Edit,
                                    description = stringResource(R.string.editor_stage_edit_text_desc),
                                    testTag = "text_handle_edit_${clip.id}",
                                    onClick = { onRequestTextEdit(clip.id) },
                                )
                                HandlePill(
                                    icon = Icons.AutoMirrored.Filled.RotateRight,
                                    description = stringResource(R.string.editor_stage_rotate_desc),
                                    testTag = "text_handle_rotate_${clip.id}",
                                    onClick = {
                                        val nextRot = (clip.transform.rotationDegrees + 15f) % 360f
                                        onUpdateTransform(clip.id, clip.transform.copy(rotationDegrees = nextRot))
                                    },
                                )
                                HandlePill(
                                    icon = Icons.Default.OpenWith,
                                    description = stringResource(R.string.editor_stage_scale_desc),
                                    testTag = "text_handle_scale_${clip.id}",
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
                        IconButton(
                            onClick = onExitFullscreen,
                            modifier = Modifier.testTag("stage_exit_fullscreen_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FullscreenExit,
                                contentDescription = stringResource(R.string.editor_fullscreen_desc),
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
private fun SelectionCornerMarkers(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val dotRadius = 3.5.dp.toPx()
        val corners = listOf(
            Offset(0f, 0f),
            Offset(size.width, 0f),
            Offset(0f, size.height),
            Offset(size.width, size.height),
        )
        corners.forEach { corner ->
            drawCircle(color = color, radius = dotRadius, center = corner)
            drawCircle(color = VyroColors.Obsidian, radius = dotRadius * 0.45f, center = corner)
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
    icon: ImageVector,
    description: String,
    containerColor: Color = VyroColors.CrimsonAccent,
    iconTint: Color = Color.White,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = containerColor,
            border = BorderStroke(1.dp, VyroColors.Obsidian.copy(alpha = 0.7f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

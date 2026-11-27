package com.vyro.feature.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vyro.core.designsystem.MediaPreviewCache
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.designsystem.VyroDesignAssets
import com.vyro.core.model.MediaKind
import com.vyro.core.model.TimecodeFormatter

@Composable
fun MediaSelectorScreen(
    viewModel: MediaSelectorViewModel,
    onClose: () -> Unit,
    onComplete: (projectId: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
    ) { uris ->
        viewModel.importPickedUris(uris)
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importPickedUris(listOf(uri))
        }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val adaptiveColumns = if (maxWidth >= 720.dp) (state.gridColumns + 2).coerceAtMost(6) else state.gridColumns
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 960.dp),
            ) {
                // Top Bar matching reference Screenshot 3
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("media_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.media_close_desc),
                            tint = VyroColors.TextPrimary,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (state.sourceTab != MediaSourceTab.RECENT) {
                                        viewModel.setSourceTab(MediaSourceTab.RECENT)
                                    } else {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                        )
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.media_tab_recent),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (state.sourceTab == MediaSourceTab.RECENT) {
                                    VyroColors.TextPrimary
                                } else {
                                    VyroColors.SteelMuted
                                },
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = if (state.sourceTab == MediaSourceTab.RECENT) {
                                    VyroColors.TextPrimary
                                } else {
                                    VyroColors.SteelMuted
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        Text(
                            text = stringResource(R.string.media_tab_material),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state.sourceTab == MediaSourceTab.MATERIAL) {
                                VyroColors.TextPrimary
                            } else {
                                VyroColors.SteelMuted
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.setSourceTab(MediaSourceTab.MATERIAL) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                )
                            },
                            modifier = Modifier.testTag("media_device_picker_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.media_pick_device_cta),
                                tint = VyroColors.TextPrimary,
                            )
                        }
                        IconButton(onClick = viewModel::toggleGridColumns) {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = stringResource(R.string.media_grid_toggle_desc),
                                tint = VyroColors.TextSecondary,
                            )
                        }
                    }
                }

                // Sub-tabs: VIDEO | PHOTO | ALL with crimson underline
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                ) {
                    val tabs = listOf(
                        MediaFilterTab.VIDEO to stringResource(R.string.media_filter_video),
                        MediaFilterTab.PHOTO to stringResource(R.string.media_filter_photo),
                        MediaFilterTab.ALL to stringResource(R.string.media_filter_all),
                    )
                    tabs.forEach { (tab, label) ->
                        val active = state.filterTab == tab
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { viewModel.setFilterTab(tab) }
                                .padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (active) VyroColors.TextPrimary else VyroColors.SteelMuted,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (active) VyroColors.CrimsonAccent else Color.Transparent),
                            )
                        }
                    }
                }

                if (state.isInspecting) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = VyroColors.CrimsonAccent,
                    )
                }

                // Device Photo & Audio Picker actions inside Recent tab
                if (state.sourceTab == MediaSourceTab.RECENT) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                    )
                                }
                                .testTag("launch_photo_picker_card"),
                            shape = MaterialTheme.shapes.medium,
                            color = VyroColors.SlateControl,
                            border = BorderStroke(1.dp, VyroColors.SteelDivider),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = VyroColors.CrimsonAccent,
                                    modifier = Modifier.size(28.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.media_pick_device_cta),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.media_pick_device_subtitle),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = VyroColors.TextSecondary,
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = { audioPickerLauncher.launch(arrayOf("audio/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("launch_audio_picker_button"),
                            border = BorderStroke(1.dp, VyroColors.SteelDivider),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AudioFile,
                                contentDescription = null,
                                tint = VyroColors.AudioTeal,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.media_pick_audio_cta))
                        }
                    }
                }

                // Thumbnail Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(adaptiveColumns),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 92.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.visibleItems, key = { it.id }) { item ->
                        val selectionOrder = state.selectedIds.indexOf(item.id)
                        MediaGridTile(
                            item = item,
                            selectionNumber = if (selectionOrder >= 0) selectionOrder + 1 else null,
                            onClick = { viewModel.toggleSelect(item.id) },
                        )
                    }
                }
            }

            // Translucent Bottom Prompt Bar + Circular Arrow Confirm FAB
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                VyroColors.Obsidian.copy(alpha = 0.88f),
                                VyroColors.Obsidian,
                            ),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = if (state.isInspecting) {
                        stringResource(R.string.media_inspecting_status)
                    } else if (state.selectedIds.isEmpty()) {
                        stringResource(R.string.media_footer_hint)
                    } else {
                        "${state.selectedIds.size} clip(s) selected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VyroColors.TextPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )

                FloatingActionButton(
                    onClick = { viewModel.confirmSelection(onComplete) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(52.dp)
                        .testTag("media_confirm_fab"),
                    shape = CircleShape,
                    containerColor = if (state.selectedIds.isNotEmpty()) {
                        VyroColors.CrimsonAccent
                    } else {
                        VyroColors.TextSecondary
                    },
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.media_confirm_desc),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaGridTile(
    item: MediaCatalogItem,
    selectionNumber: Int?,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var thumbBitmap by remember(item.uriString) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(item.uriString, item.kind) {
        if (item.uriString != null && item.kind != MediaKind.AUDIO) {
            thumbBitmap = MediaPreviewCache.loadThumbnail(context, item.uriString)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(Color(item.colorArgb))
            .then(
                if (selectionNumber != null) {
                    Modifier.border(2.dp, VyroColors.CrimsonAccent)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .testTag("media_tile_${item.id}"),
    ) {
        when {
            thumbBitmap != null -> {
                Image(
                    bitmap = thumbBitmap!!,
                    contentDescription = item.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.materialId == "studio_reel" -> {
                Image(
                    painter = painterResource(id = VyroDesignAssets.studioReelDrawableRes),
                    contentDescription = item.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            item.kind == MediaKind.AUDIO -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            else -> {
                val isLight = item.colorArgb == 0xFFFFFFFFL || item.colorArgb == 0xFFF5EFE6L || item.colorArgb == 0xFFF3B768L
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLight) VyroColors.Obsidian else VyroColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 6.dp),
                )
            }
        }

        // Top-left Phase 2 metadata badge for validated device media
        item.metadataBadge?.let { badge ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badge,
                    fontSize = 9.sp,
                    color = VyroColors.ElectricCyan,
                    maxLines = 1,
                )
            }
        }

        // Bottom-left duration badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(5.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = TimecodeFormatter.formatClipBadge(item.durationUs),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }

        // Top-right selection circle
        if (selectionNumber != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(VyroColors.CrimsonAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = selectionNumber.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
    }
}

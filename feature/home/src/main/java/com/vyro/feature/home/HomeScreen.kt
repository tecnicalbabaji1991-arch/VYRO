package com.vyro.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Crop169
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.vyro.core.designsystem.VyroBadge
import com.vyro.core.designsystem.VyroColors
import com.vyro.core.designsystem.VyroDesignAssets
import com.vyro.core.designsystem.VyroStudioPanel
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.ProjectSummary
import com.vyro.core.model.Rational
import com.vyro.core.model.TimecodeFormatter
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNewProjectClick: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDraftsInfo by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }

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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
        ) {
            val gridColumns = if (maxWidth >= 720.dp) 5 else 3
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                // 1. Brand Top Bar: VYRO + STUDIO pill + Settings/Diagnostics gear icon
                HomeTopBar(
                    onOpenDiagnostics = onOpenDiagnostics,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 2. Warm Amber-to-Coral Gradient "New Project" Hero Banner
                NewProjectHeroCard(
                    onClick = onNewProjectClick,
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 3. Five Circular Quick-Action Studio Shortcuts (all functional)
                QuickActionsRow(
                    onQuickAspect = { preset ->
                        viewModel.createQuickProject(preset, onOpenProject)
                    },
                    onOpenMediaPicker = onNewProjectClick,
                    onOpenCustomDialog = { showCreateDialog = true },
                )

                Spacer(modifier = Modifier.height(22.dp))

                // 4. Drafts Header with (i) Info + Grid/List View Toggle
                DraftsHeaderRow(
                    isGridView = state.isGridView,
                    onInfoClick = { showDraftsInfo = true },
                    onToggleViewMode = viewModel::toggleDraftsViewMode,
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 5. Drafts Grid / List
                if (state.projects.isEmpty()) {
                    EmptyDraftsCard(
                        onCreateClick = onNewProjectClick,
                        modifier = Modifier.weight(1f),
                    )
                } else if (state.isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.projects, key = { it.projectId }) { project ->
                            DraftSquareTile(
                                project = project,
                                onOpen = { onOpenProject(project.projectId) },
                                onRename = { renameTarget = project },
                                onDuplicate = { viewModel.duplicateProject(project.projectId) },
                                onDelete = { viewModel.deleteProject(project.projectId) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(state.projects, key = { it.projectId }) { project ->
                            DraftListRow(
                                project = project,
                                onOpen = { onOpenProject(project.projectId) },
                                onRename = { renameTarget = project },
                                onDuplicate = { viewModel.duplicateProject(project.projectId) },
                                onDelete = { viewModel.deleteProject(project.projectId) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            isCreating = state.isCreating,
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, aspect, fps, seed ->
                viewModel.createProject(title, aspect, fps, seed) { newProjectId ->
                    showCreateDialog = false
                    onOpenProject(newProjectId)
                }
            },
        )
    }

    if (showDraftsInfo) {
        AlertDialog(
            onDismissRequest = { showDraftsInfo = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.home_drafts_info_title)) },
            text = { Text(stringResource(R.string.home_drafts_info_body)) },
            confirmButton = {
                TextButton(onClick = { showDraftsInfo = false }) {
                    Text(stringResource(R.string.home_ok))
                }
            },
        )
    }

    renameTarget?.let { target ->
        RenameProjectDialog(
            initialTitle = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                viewModel.renameProject(target.projectId, newTitle)
                renameTarget = null
            },
        )
    }
}

@Composable
private fun HomeTopBar(
    onOpenDiagnostics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_brand_title),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp,
                ),
                color = VyroColors.TextPrimary,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(VyroColors.HeroAmberStart, VyroColors.HeroCoralEnd),
                        ),
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_brand_badge),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VyroColors.Obsidian,
                )
            }
        }

        IconButton(
            onClick = onOpenDiagnostics,
            modifier = Modifier.testTag("open_diagnostics_button"),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.home_diagnostics_action),
                tint = VyroColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun NewProjectHeroCard(
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        VyroColors.HeroAmberStart,
                        VyroColors.HeroCoralEnd,
                    ),
                ),
            )
            .clickable(onClick = onClick)
            .testTag("new_project_hero_card"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(11.dp),
                color = Color.White,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = VyroColors.HeroCoralEnd,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_new_project_cta),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun QuickActionsRow(
    onQuickAspect: (AspectRatioPreset) -> Unit,
    onOpenMediaPicker: () -> Unit,
    onOpenCustomDialog: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        QuickActionCircle(
            icon = Icons.Default.Crop169,
            label = stringResource(R.string.home_quick_16_9),
            testTag = "quick_action_16_9",
            onClick = { onQuickAspect(AspectRatioPreset.LANDSCAPE_16_9) },
        )
        QuickActionCircle(
            icon = Icons.Default.CropPortrait,
            label = stringResource(R.string.home_quick_9_16),
            testTag = "quick_action_9_16",
            onClick = { onQuickAspect(AspectRatioPreset.PORTRAIT_9_16) },
        )
        QuickActionCircle(
            icon = Icons.Default.CropSquare,
            label = stringResource(R.string.home_quick_1_1),
            testTag = "quick_action_1_1",
            onClick = { onQuickAspect(AspectRatioPreset.SQUARE_1_1) },
        )
        QuickActionCircle(
            icon = Icons.Default.PermMedia,
            label = stringResource(R.string.home_quick_media),
            testTag = "quick_action_media",
            onClick = onOpenMediaPicker,
        )
        QuickActionCircle(
            icon = Icons.Default.Tune,
            label = stringResource(R.string.home_quick_custom),
            testTag = "quick_action_custom",
            onClick = onOpenCustomDialog,
        )
    }
}

@Composable
private fun QuickActionCircle(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = VyroColors.SlateControl,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = VyroColors.TextPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VyroColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DraftsHeaderRow(
    isGridView: Boolean,
    onInfoClick: () -> Unit,
    onToggleViewMode: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.home_drafts_heading),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = VyroColors.TextPrimary,
            )
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.home_drafts_info_desc),
                    tint = VyroColors.SteelMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        IconButton(
            onClick = onToggleViewMode,
            modifier = Modifier.testTag("drafts_view_toggle"),
        ) {
            Icon(
                imageVector = if (isGridView) {
                    Icons.AutoMirrored.Filled.FormatListBulleted
                } else {
                    Icons.Default.GridView
                },
                contentDescription = stringResource(R.string.home_drafts_view_toggle_desc),
                tint = VyroColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun DraftSquareTile(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var thumbBitmap by remember(project.previewUriString) { mutableStateOf<ImageBitmap?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(project.previewUriString) {
        if (project.previewUriString != null) {
            thumbBitmap = MediaPreviewCache.loadThumbnail(context, project.previewUriString)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(project.previewColorArgb))
            .clickable(onClick = onOpen)
            .testTag("project_card_${project.projectId}"),
    ) {
        when {
            thumbBitmap != null -> {
                Image(
                    bitmap = thumbBitmap!!,
                    contentDescription = project.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            project.previewColorArgb == 0xFF1A2838L -> {
                Image(
                    painter = painterResource(id = VyroDesignAssets.studioReelDrawableRes),
                    contentDescription = project.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                val isLight = project.previewColorArgb == 0xFFFFFFFFL ||
                    project.previewColorArgb == 0xFFF5EFE6L ||
                    project.previewColorArgb == 0xFFF3B768L
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLight) VyroColors.Obsidian else VyroColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                )
            }
        }

        // Bottom dark scrim for duration & overflow menu legibility
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(38.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                ),
        )

        if (project.isCorrupt) {
            VyroBadge(
                label = stringResource(R.string.home_damaged_badge),
                accent = VyroColors.CoralError,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .testTag("draft_corrupt_badge_${project.projectId}"),
            )
        } else if (project.hasPendingRecoverySnapshot) {
            VyroBadge(
                label = stringResource(R.string.home_snapshot_badge),
                accent = VyroColors.ElectricCyan,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .testTag("draft_snapshot_badge_${project.projectId}"),
            )
        } else if (project.recoveredFromBackup) {
            VyroBadge(
                label = stringResource(R.string.home_recovered_badge),
                accent = VyroColors.VideoEmerald,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
            )
        }
        if (project.missingAssetCount > 0) {
            VyroBadge(
                label = stringResource(R.string.home_missing_badge),
                accent = VyroColors.StudioAmber,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .testTag("draft_missing_badge_${project.projectId}"),
            )
        }

        // Bottom-left duration badge
        Text(
            text = TimecodeFormatter.formatCompactTenths(project.durationUs),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 6.dp),
        )

        // Bottom-right ••• menu trigger
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd),
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = stringResource(R.string.home_draft_more_desc),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_open)) },
                    onClick = {
                        menuExpanded = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_rename)) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_duplicate)) },
                    onClick = {
                        menuExpanded = false
                        onDuplicate()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.home_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun DraftListRow(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val formattedDate = remember(project.updatedAtEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(project.updatedAtEpochMs))
    }
    var menuExpanded by remember { mutableStateOf(false) }

    VyroStudioPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag("project_card_${project.projectId}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(project.previewColorArgb)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = project.aspectLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (project.previewColorArgb == 0xFFFFFFFFL) VyroColors.Obsidian else VyroColors.TextPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (project.isCorrupt) {
                        VyroBadge(
                            label = stringResource(R.string.home_damaged_badge),
                            accent = VyroColors.CoralError,
                        )
                    } else if (project.hasPendingRecoverySnapshot) {
                        VyroBadge(
                            label = stringResource(R.string.home_snapshot_badge),
                            accent = VyroColors.ElectricCyan,
                        )
                    }
                    if (project.missingAssetCount > 0) {
                        VyroBadge(
                            label = stringResource(R.string.home_missing_badge),
                            accent = VyroColors.StudioAmber,
                        )
                    }
                }
                Text(
                    text = "${TimecodeFormatter.formatCompactTenths(project.durationUs)} • ${project.clipCount} clips • $formattedDate",
                    style = MaterialTheme.typography.labelSmall,
                    color = VyroColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.home_draft_more_desc),
                        tint = VyroColors.TextSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_open)) },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_rename)) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_duplicate)) },
                        onClick = {
                            menuExpanded = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDraftsCard(
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        VyroStudioPanel(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCreateClick)
                .testTag("empty_create_project_button"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AspectRatio,
                    contentDescription = null,
                    tint = VyroColors.HeroCoralEnd,
                    modifier = Modifier.size(30.dp),
                )
                Text(
                    text = stringResource(R.string.home_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.home_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VyroColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, AspectRatioPreset, Rational, Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("Studio Sequence") }
    var aspectPreset by remember { mutableStateOf(AspectRatioPreset.PORTRAIT_9_16) }
    var frameRate by remember { mutableStateOf(Rational.FPS_30) }
    var seedTimeline by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.home_create_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.home_project_name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("project_name_input"),
                )

                Text(
                    text = stringResource(R.string.home_aspect_ratio_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectRatioPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = aspectPreset == preset,
                            onClick = { aspectPreset = preset },
                            label = { Text(preset.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VyroColors.HeroCoralEnd.copy(alpha = 0.2f),
                                selectedLabelColor = VyroColors.HeroCoralEnd,
                            ),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.home_frame_rate_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Rational.FPS_24, Rational.FPS_30, Rational.FPS_60).forEach { fps ->
                        FilterChip(
                            selected = frameRate == fps,
                            onClick = { frameRate = fps },
                            label = { Text("${fps.nominalFpsInt} fps") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VyroColors.ElectricCyan.copy(alpha = 0.2f),
                                selectedLabelColor = VyroColors.ElectricCyan,
                            ),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { seedTimeline = !seedTimeline },
                ) {
                    Checkbox(
                        checked = seedTimeline,
                        onCheckedChange = { seedTimeline = it },
                    )
                    Text(
                        text = stringResource(R.string.home_seed_timeline_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, aspectPreset, frameRate, seedTimeline) },
                enabled = !isCreating,
                modifier = Modifier.testTag("confirm_create_project_button"),
            ) {
                Text(stringResource(R.string.home_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel))
            }
        },
    )
}

@Composable
private fun RenameProjectDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.home_rename_dialog_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(title) }) {
                Text(stringResource(R.string.home_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_cancel))
            }
        },
    )
}

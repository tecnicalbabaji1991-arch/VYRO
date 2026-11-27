package com.vyro.feature.media

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.EditCommand
import com.vyro.core.contracts.MediaInspectorContract
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.contracts.TimelineEvaluator
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.Rational
import com.vyro.core.model.TrackType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class MediaSourceTab {
    RECENT,
    MATERIAL,
}

enum class MediaFilterTab {
    VIDEO,
    PHOTO,
    ALL,
}

data class MediaCatalogItem(
    val id: String,
    val label: String,
    val kind: MediaKind,
    val durationUs: Long,
    val colorArgb: Long,
    val uriString: String? = null,
    val materialId: String? = null,
    val metadataBadge: String? = null,
    val mediaAsset: MediaAsset? = null,
)

data class MediaSelectorUiState(
    val sourceTab: MediaSourceTab = MediaSourceTab.MATERIAL,
    val filterTab: MediaFilterTab = MediaFilterTab.ALL,
    val gridColumns: Int = 4,
    val items: List<MediaCatalogItem> = defaultMaterials(),
    val selectedIds: List<String> = emptyList(),
    val isInspecting: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: EngineError? = null,
) {
    val visibleItems: List<MediaCatalogItem>
        get() = items.filter { item ->
            val tabMatch = when (sourceTab) {
                MediaSourceTab.RECENT -> item.uriString != null
                MediaSourceTab.MATERIAL -> item.uriString == null
            }
            val kindMatch = when (filterTab) {
                MediaFilterTab.VIDEO -> item.kind == MediaKind.VIDEO ||
                    (item.kind == MediaKind.MATERIAL && item.durationUs >= 4_000_000L)
                MediaFilterTab.PHOTO -> item.kind == MediaKind.PHOTO ||
                    (item.kind == MediaKind.MATERIAL && item.durationUs < 4_000_000L)
                MediaFilterTab.ALL -> true
            }
            tabMatch && kindMatch
        }
}

private fun defaultMaterials(): List<MediaCatalogItem> = listOf(
    MediaCatalogItem("mat_white", "White Canvas", MediaKind.MATERIAL, 4_000_000L, 0xFFFFFFFFL, materialId = "white_canvas"),
    MediaCatalogItem("mat_obsidian", "Obsidian", MediaKind.MATERIAL, 4_000_000L, 0xFF10131AL, materialId = "obsidian_matte"),
    MediaCatalogItem("mat_reel", "Studio Reel", MediaKind.MATERIAL, 6_000_000L, 0xFF1A2838L, materialId = "studio_reel"),
    MediaCatalogItem("mat_coral", "Coral Sunset", MediaKind.MATERIAL, 5_000_000L, 0xFFF34F73L, materialId = "coral_sunset"),
    MediaCatalogItem("mat_amber", "Amber Gold", MediaKind.MATERIAL, 3_000_000L, 0xFFF3B768L, materialId = "amber_gold"),
    MediaCatalogItem("mat_cyan", "Electric Cyan", MediaKind.MATERIAL, 5_000_000L, 0xFF00B8D4L, materialId = "electric_cyan"),
    MediaCatalogItem("mat_emerald", "Emerald Cut", MediaKind.MATERIAL, 4_000_000L, 0xFF0D9468L, materialId = "emerald_cut"),
    MediaCatalogItem("mat_violet", "Studio Violet", MediaKind.MATERIAL, 3_000_000L, 0xFF6D44C7L, materialId = "studio_violet"),
    MediaCatalogItem("mat_slate", "Slate Title", MediaKind.MATERIAL, 3_000_000L, 0xFF283142L, materialId = "slate_title"),
    MediaCatalogItem("mat_crimson", "Crimson", MediaKind.MATERIAL, 4_000_000L, 0xFFB81D45L, materialId = "crimson"),
    MediaCatalogItem("mat_teal", "Deep Teal", MediaKind.MATERIAL, 6_000_000L, 0xFF0F766EL, materialId = "deep_teal"),
    MediaCatalogItem("mat_cream", "Warm Paper", MediaKind.MATERIAL, 2_000_000L, 0xFFF5EFE6L, materialId = "warm_paper"),
)

class MediaSelectorViewModel(
    private val targetProjectId: String?,
    private val projectRepository: ProjectRepository,
    private val mediaInspector: MediaInspectorContract,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaSelectorUiState())
    val uiState: StateFlow<MediaSelectorUiState> = _uiState.asStateFlow()

    fun setSourceTab(tab: MediaSourceTab) {
        _uiState.update { it.copy(sourceTab = tab) }
    }

    fun setFilterTab(tab: MediaFilterTab) {
        _uiState.update { it.copy(filterTab = tab) }
    }

    fun toggleGridColumns() {
        _uiState.update { it.copy(gridColumns = if (it.gridColumns == 4) 3 else 4) }
    }

    fun toggleSelect(itemId: String) {
        _uiState.update { state ->
            val next = if (state.selectedIds.contains(itemId)) {
                state.selectedIds - itemId
            } else {
                state.selectedIds + itemId
            }
            state.copy(selectedIds = next)
        }
    }

    fun importPickedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isInspecting = true) }
            val importedItems = mutableListOf<MediaCatalogItem>()
            var lastError: EngineError? = null

            for (uri in uris) {
                when (val result = mediaInspector.inspectUri(uri.toString())) {
                    is EngineResult.Failure -> {
                        lastError = result.error
                    }
                    is EngineResult.Success -> {
                        val asset = result.value
                        val badge = buildString {
                            if (asset.width > 0 && asset.height > 0) {
                                append("${asset.width}x${asset.height}")
                            } else if (asset.mediaKind == MediaKind.AUDIO && asset.audioSampleRateHz > 0) {
                                append("${asset.audioSampleRateHz / 1000}kHz")
                            }
                            if (asset.isHdr) {
                                if (isNotEmpty()) append(" ")
                                append("HDR")
                            }
                        }.ifBlank { asset.containerFormat }

                        importedItems += MediaCatalogItem(
                            id = asset.assetId,
                            label = asset.displayName,
                            kind = asset.mediaKind,
                            durationUs = asset.durationUs.coerceAtLeast(1_000_000L),
                            colorArgb = if (asset.mediaKind == MediaKind.AUDIO) 0xFF0F766EL else 0xFF1F2430L,
                            uriString = asset.uriString,
                            metadataBadge = badge,
                            mediaAsset = asset,
                        )
                    }
                }
            }

            _uiState.update { state ->
                val existingUris = state.items.mapNotNull { it.uriString }.toSet()
                val fresh = importedItems.filterNot { it.uriString in existingUris }
                val newIds = fresh.map { it.id }
                state.copy(
                    sourceTab = if (fresh.isNotEmpty()) MediaSourceTab.RECENT else state.sourceTab,
                    items = fresh + state.items,
                    selectedIds = state.selectedIds + newIds,
                    isInspecting = false,
                    error = lastError,
                )
            }
        }
    }

    fun confirmSelection(onComplete: (projectId: String) -> Unit) {
        if (_uiState.value.isSubmitting || _uiState.value.isInspecting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            val state = _uiState.value
            val chosen = state.selectedIds.mapNotNull { id -> state.items.firstOrNull { it.id == id } }
                .ifEmpty { listOf(state.items.first()) }

            val assetsToRegister = chosen.mapNotNull { it.mediaAsset }
            val clipsToAdd = chosen.map { item ->
                val isAudio = item.kind == MediaKind.AUDIO
                Clip(
                    id = "clip_${UUID.randomUUID().toString().take(8)}",
                    trackId = if (isAudio) "track_audio" else "track_video",
                    label = item.label.take(22),
                    timing = ClipTiming(
                        timelineStartUs = 0L,
                        sourceInUs = 0L,
                        sourceOutUs = item.durationUs.coerceAtLeast(1_000_000L),
                    ),
                    mediaSource = MediaSourceRef(
                        assetId = item.mediaAsset?.assetId,
                        mediaKind = item.kind,
                        uriString = item.uriString,
                        materialId = item.materialId,
                        backdropColorArgb = item.colorArgb,
                    ),
                    audioSpec = if (isAudio) AudioClipSpec(gainDb = 0.0f) else null,
                    accentColorArgb = item.colorArgb,
                )
            }

            if (targetProjectId == null) {
                when (
                    val res = projectRepository.createProject(
                        title = chosen.first().label.take(24),
                        aspectPreset = AspectRatioPreset.PORTRAIT_9_16,
                        frameRate = Rational.FPS_30,
                        seedStarterTimeline = true,
                        initialClips = clipsToAdd,
                        initialAssets = assetsToRegister,
                    )
                ) {
                    is EngineResult.Success -> {
                        _uiState.update { it.copy(isSubmitting = false) }
                        onComplete(res.value.projectId)
                    }
                    is EngineResult.Failure -> {
                        _uiState.update { it.copy(isSubmitting = false, error = res.error) }
                    }
                }
            } else {
                when (val loaded = projectRepository.loadProject(targetProjectId)) {
                    is EngineResult.Failure -> {
                        _uiState.update { it.copy(isSubmitting = false, error = loaded.error) }
                    }
                    is EngineResult.Success -> {
                        var seq = loaded.value.sequence
                        val videoTrack = seq.tracks.firstOrNull { it.type == TrackType.VIDEO }
                        val audioTrack = seq.tracks.firstOrNull { it.type == TrackType.AUDIO }
                        var videoCursor = videoTrack?.clips?.maxOfOrNull { it.timing.timelineEndUs } ?: 0L
                        var audioCursor = audioTrack?.clips?.maxOfOrNull { it.timing.timelineEndUs } ?: 0L

                        clipsToAdd.forEach { clip ->
                            val isAudio = clip.mediaSource.mediaKind == MediaKind.AUDIO
                            val targetTrackId = if (isAudio) (audioTrack?.id ?: "track_audio") else (videoTrack?.id ?: "track_video")
                            val cursor = if (isAudio) audioCursor else videoCursor
                            val placed = clip.copy(
                                trackId = targetTrackId,
                                timing = clip.timing.copy(timelineStartUs = cursor),
                            )
                            val applied = TimelineEvaluator.apply(
                                seq,
                                EditCommand.AddClip(targetTrackId, placed),
                            )
                            if (applied is EngineResult.Success) {
                                seq = applied.value
                                if (isAudio) {
                                    audioCursor = placed.timing.timelineEndUs
                                } else {
                                    videoCursor = placed.timing.timelineEndUs
                                }
                            }
                        }

                        val mergedAssets = (loaded.value.assets + assetsToRegister).distinctBy { it.assetId }
                        val saveRes = projectRepository.saveProject(
                            loaded.value.copy(sequence = seq, assets = mergedAssets),
                        )
                        _uiState.update { it.copy(isSubmitting = false) }
                        if (saveRes is EngineResult.Success) {
                            onComplete(targetProjectId)
                        } else if (saveRes is EngineResult.Failure) {
                            _uiState.update { it.copy(error = saveRes.error) }
                        }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(
        private val targetProjectId: String?,
        private val projectRepository: ProjectRepository,
        private val mediaInspector: MediaInspectorContract,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MediaSelectorViewModel(targetProjectId, projectRepository, mediaInspector) as T
        }
    }
}

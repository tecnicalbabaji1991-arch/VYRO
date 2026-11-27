package com.vyro.core.database

import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.PersistenceDiagnosticsSnapshot
import com.vyro.core.contracts.PersistenceSaveStatus
import com.vyro.core.contracts.ProjectHealthStatus
import com.vyro.core.contracts.ProjectLoadResult
import com.vyro.core.contracts.ProjectRepository
import com.vyro.core.contracts.RecoverySource
import com.vyro.core.contracts.UndoHistoryPolicy
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.ProjectSummary
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

class LocalProjectRepository(
    private val dao: ProjectIndexDao,
    private val assetDao: MediaAssetDao,
    private val storage: AtomicProjectStorage,
    private val dispatchers: DispatchersProvider,
) : ProjectRepository {

    private val _persistenceDiagnostics = MutableStateFlow(PersistenceDiagnosticsSnapshot())
    override val persistenceDiagnostics: StateFlow<PersistenceDiagnosticsSnapshot> =
        _persistenceDiagnostics.asStateFlow()

    override fun observeProjects(): Flow<List<ProjectSummary>> {
        return dao.observeAllProjects().map { rows ->
            rows.map { entity ->
                ProjectSummary(
                    projectId = entity.projectId,
                    title = entity.title,
                    width = entity.width,
                    height = entity.height,
                    fpsNumerator = entity.fpsNumerator,
                    fpsDenominator = entity.fpsDenominator,
                    durationUs = entity.durationUs,
                    clipCount = entity.clipCount,
                    updatedAtEpochMs = entity.updatedAtEpochMs,
                    recoveredFromBackup = entity.recoveredFromBackup,
                    previewColorArgb = entity.previewColorArgb,
                    previewUriString = entity.previewUriString,
                    missingAssetCount = entity.missingAssetCount,
                    schemaVersion = entity.schemaVersion,
                    hasPendingRecoverySnapshot = entity.hasPendingRecoverySnapshot,
                    isCorrupt = entity.isCorrupt,
                )
            }
        }
    }

    override suspend fun createProject(
        title: String,
        aspectPreset: AspectRatioPreset,
        frameRate: Rational,
        seedStarterTimeline: Boolean,
        initialClips: List<Clip>,
        initialAssets: List<MediaAsset>,
    ): EngineResult<ProjectDocument> = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val projectId = "proj_${UUID.randomUUID().toString().take(8)}"
        val sanitizedTitle = title.trim().ifEmpty { "Draft ${projectId.takeLast(4).uppercase()}" }

        val textTrackId = "track_text"
        val videoTrackId = "track_video"
        val audioTrackId = "track_audio"

        val videoClips = mutableListOf<Clip>()
        val audioClips = mutableListOf<Clip>()

        if (initialClips.isNotEmpty()) {
            var videoCursorUs = 0L
            var audioCursorUs = 0L
            initialClips.forEach { incoming ->
                val dur = incoming.timing.timelineDurationUs.coerceAtLeast(1_000_000L)
                if (incoming.mediaSource.mediaKind == MediaKind.AUDIO) {
                    val placed = incoming.copy(
                        trackId = audioTrackId,
                        timing = incoming.timing.copy(timelineStartUs = audioCursorUs),
                    )
                    audioCursorUs += dur
                    audioClips += placed
                } else {
                    val placed = incoming.copy(
                        trackId = videoTrackId,
                        timing = incoming.timing.copy(timelineStartUs = videoCursorUs),
                    )
                    videoCursorUs += dur
                    videoClips += placed
                }
            }
        } else if (seedStarterTimeline) {
            videoClips += Clip(
                id = "clip_vid_01",
                trackId = videoTrackId,
                label = "White Canvas",
                timing = ClipTiming(0L, 0L, 4_000_000L),
                mediaSource = MediaSourceRef(
                    mediaKind = MediaKind.MATERIAL,
                    materialId = "white_canvas",
                    backdropColorArgb = 0xFFFFFFFFL,
                ),
                accentColorArgb = 0xFFFFFFFFL,
            )
        }

        val tracks = listOf(
            Track(id = textTrackId, name = "Text", type = TrackType.TEXT, zIndex = 2),
            Track(id = videoTrackId, name = "Video", type = TrackType.VIDEO, zIndex = 1, clips = videoClips),
            Track(id = audioTrackId, name = "Audio", type = TrackType.AUDIO, zIndex = 0, clips = audioClips),
        )

        val doc = ProjectDocument(
            projectId = projectId,
            title = sanitizedTitle,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            sequence = Sequence(
                id = "seq_main",
                width = aspectPreset.width,
                height = aspectPreset.height,
                frameRate = frameRate,
                tracks = tracks,
            ),
            assets = initialAssets.distinctBy { it.assetId },
            revision = 1L,
        )

        when (val writeResult = storage.writeAtomic(doc)) {
            is EngineResult.Failure -> writeResult
            is EngineResult.Success -> {
                dao.upsert(doc.toEntity(recovered = false, hasSnapshot = false, corrupt = false))
                assetDao.replaceAssetsForProject(projectId, doc.toAssetEntities())
                VyroLogger.log(LogSeverity.INFO, "core:database", "Created project $sanitizedTitle", projectId)
                _persistenceDiagnostics.update {
                    it.copy(
                        saveStatus = PersistenceSaveStatus.SAVED,
                        recoverySource = RecoverySource.CLEAN_PRIMARY,
                        lastSavedRevision = doc.revision,
                        lastSavedEpochMs = now,
                        hasUnsavedChanges = false,
                        lastRecoveryDetail = "Created project $sanitizedTitle (v${ProjectDocument.CURRENT_SCHEMA_VERSION})",
                    )
                }
                EngineResult.Success(doc)
            }
        }
    }

    override suspend fun loadProject(projectId: String): EngineResult<ProjectDocument> {
        return when (val detailed = loadProjectWithDetails(projectId)) {
            is EngineResult.Failure -> detailed
            is EngineResult.Success -> EngineResult.Success(detailed.value.document)
        }
    }

    override suspend fun loadProjectWithDetails(projectId: String): EngineResult<ProjectLoadResult> =
        withContext(dispatchers.io) {
            when (val outcome = storage.readWithRecovery(projectId)) {
                is EngineResult.Failure -> {
                    dao.updateRecoveryHealth(
                        projectId = projectId,
                        hasSnapshot = false,
                        isCorrupt = true,
                        recoveredFromBackup = false,
                    )
                    _persistenceDiagnostics.update {
                        it.copy(
                            saveStatus = PersistenceSaveStatus.FAILED,
                            corruptProjectCount = it.corruptProjectCount + 1,
                            lastRecoveryDetail = outcome.error.diagnosticDetail,
                        )
                    }
                    EngineResult.Failure(outcome.error)
                }
                is EngineResult.Success -> {
                    val value = outcome.value
                    val doc = value.document
                    val isRecovered = value.recoverySource == RecoverySource.BACKUP_FILE ||
                        value.recoverySource == RecoverySource.RECOVERY_SNAPSHOT

                    dao.upsert(
                        doc.toEntity(
                            recovered = value.recoverySource == RecoverySource.BACKUP_FILE,
                            hasSnapshot = value.recoverySource == RecoverySource.RECOVERY_SNAPSHOT,
                            corrupt = false,
                        ),
                    )
                    assetDao.replaceAssetsForProject(projectId, doc.toAssetEntities())

                    _persistenceDiagnostics.update {
                        it.copy(
                            schemaVersion = doc.schemaVersion,
                            migratedFromVersion = value.migratedFromVersion ?: it.migratedFromVersion,
                            undoPolicy = UndoHistoryPolicy.RESET_ON_PROCESS_RESTART,
                            inMemoryUndoDepth = 0,
                            inMemoryRedoDepth = 0,
                            saveStatus = when (value.recoverySource) {
                                RecoverySource.RECOVERY_SNAPSHOT -> PersistenceSaveStatus.RECOVERED_SNAPSHOT
                                RecoverySource.BACKUP_FILE -> PersistenceSaveStatus.RECOVERED_BACKUP
                                else -> PersistenceSaveStatus.SAVED
                            },
                            recoverySource = value.recoverySource,
                            recoveredProjectCount = if (isRecovered) it.recoveredProjectCount + 1 else it.recoveredProjectCount,
                            lastSavedRevision = doc.revision,
                            lastSavedEpochMs = doc.updatedAtEpochMs,
                            hasUnsavedChanges = value.recoverySource == RecoverySource.RECOVERY_SNAPSHOT,
                            lastRecoveryDetail = when (value.recoverySource) {
                                RecoverySource.RECOVERY_SNAPSHOT ->
                                    "Recovered unsaved snapshot (${value.recoveredCommandLabels.takeLast(3).joinToString(", ").ifBlank { "latest cut" }})"
                                RecoverySource.BACKUP_FILE -> "Recovered from backup file after primary corruption"
                                else -> value.migratedFromVersion?.let { v ->
                                    "Migrated cleanly from v$v to v${doc.schemaVersion}"
                                } ?: "Clean atomic load (rev ${doc.revision})"
                            },
                        )
                    }

                    EngineResult.Success(
                        ProjectLoadResult(
                            document = doc,
                            recoverySource = value.recoverySource,
                            migratedFromVersion = value.migratedFromVersion,
                            recoveredCommandLabels = value.recoveredCommandLabels,
                            hasCommittedBaseline = value.hasCommittedBaseline,
                        ),
                    )
                }
            }
        }

    override suspend fun saveProject(document: ProjectDocument): EngineResult<Unit> = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val updated = document.copy(
            updatedAtEpochMs = now,
            revision = document.revision.coerceAtLeast(1L),
        )
        when (val writeResult = storage.writeAtomic(updated)) {
            is EngineResult.Failure -> {
                _persistenceDiagnostics.update {
                    it.copy(
                        saveStatus = PersistenceSaveStatus.FAILED,
                        lastRecoveryDetail = writeResult.error.diagnosticDetail,
                    )
                }
                writeResult
            }
            is EngineResult.Success -> {
                dao.upsert(updated.toEntity(recovered = false, hasSnapshot = false, corrupt = false))
                assetDao.replaceAssetsForProject(updated.projectId, updated.toAssetEntities())
                _persistenceDiagnostics.update {
                    it.copy(
                        saveStatus = PersistenceSaveStatus.SAVED,
                        recoverySource = RecoverySource.CLEAN_PRIMARY,
                        autosaveCount = it.autosaveCount + 1L,
                        lastSavedRevision = updated.revision,
                        lastSavedEpochMs = now,
                        hasUnsavedChanges = false,
                        lastRecoveryDetail = "Atomic save rev ${updated.revision} committed",
                    )
                }
                EngineResult.Success(Unit)
            }
        }
    }

    override suspend fun saveRecoverySnapshot(
        document: ProjectDocument,
        recentCommandLabels: List<String>,
    ): EngineResult<Unit> = withContext(dispatchers.io) {
        val updated = document.copy(updatedAtEpochMs = System.currentTimeMillis())
        when (val res = storage.writeRecoverySnapshot(updated, recentCommandLabels)) {
            is EngineResult.Failure -> res
            is EngineResult.Success -> {
                dao.updateRecoveryHealth(
                    projectId = updated.projectId,
                    hasSnapshot = true,
                    isCorrupt = false,
                    recoveredFromBackup = false,
                )
                _persistenceDiagnostics.update {
                    it.copy(
                        recoverySnapshotCount = it.recoverySnapshotCount + 1L,
                        lastRecoveryDetail = "Crash snapshot written (rev ${updated.revision})",
                    )
                }
                EngineResult.Success(Unit)
            }
        }
    }

    override suspend fun discardRecoverySnapshot(projectId: String): EngineResult<ProjectLoadResult> =
        withContext(dispatchers.io) {
            when (val outcome = storage.discardRecoverySnapshot(projectId)) {
                is EngineResult.Failure -> EngineResult.Failure(outcome.error)
                is EngineResult.Success -> {
                    val valOut = outcome.value
                    dao.upsert(
                        valOut.document.toEntity(
                            recovered = valOut.recoverySource == RecoverySource.BACKUP_FILE,
                            hasSnapshot = false,
                            corrupt = false,
                        ),
                    )
                    assetDao.replaceAssetsForProject(projectId, valOut.document.toAssetEntities())
                    _persistenceDiagnostics.update {
                        it.copy(
                            saveStatus = PersistenceSaveStatus.SAVED,
                            recoverySource = valOut.recoverySource,
                            hasUnsavedChanges = false,
                            lastSavedRevision = valOut.document.revision,
                            lastRecoveryDetail = "Discarded crash snapshot; restored committed baseline",
                        )
                    }
                    EngineResult.Success(
                        ProjectLoadResult(
                            document = valOut.document,
                            recoverySource = valOut.recoverySource,
                            migratedFromVersion = valOut.migratedFromVersion,
                            recoveredCommandLabels = emptyList(),
                            hasCommittedBaseline = true,
                        ),
                    )
                }
            }
        }

    override suspend fun clearRecoverySnapshot(projectId: String) = withContext(dispatchers.io) {
        storage.clearRecoverySnapshot(projectId)
        dao.updateRecoveryHealth(
            projectId = projectId,
            hasSnapshot = false,
            isCorrupt = false,
            recoveredFromBackup = false,
        )
    }

    override suspend fun verifyAllProjectsHealth(): List<ProjectHealthStatus> = withContext(dispatchers.io) {
        val entities = dao.getAllProjectsSnapshot()
        val statuses = entities.map { entity ->
            val health = storage.inspectProjectHealth(entity.projectId)
            if (entity.hasPendingRecoverySnapshot != health.hasPendingSnapshot ||
                entity.isCorrupt != health.isCorrupt
            ) {
                dao.updateRecoveryHealth(
                    projectId = entity.projectId,
                    hasSnapshot = health.hasPendingSnapshot,
                    isCorrupt = health.isCorrupt,
                    recoveredFromBackup = !health.isCorrupt && health.hasBackup && entity.recoveredFromBackup,
                )
            }
            health
        }
        val corruptCount = statuses.count { it.isCorrupt }
        val pendingCount = statuses.count { it.hasPendingSnapshot }
        _persistenceDiagnostics.update {
            it.copy(
                corruptProjectCount = corruptCount,
                lastRecoveryDetail = if (corruptCount > 0 || pendingCount > 0) {
                    "Index scan: $pendingCount snapshot(s), $corruptCount corrupt"
                } else {
                    it.lastRecoveryDetail
                },
            )
        }
        statuses
    }

    override fun updatePersistenceRuntimeStats(
        saveStatus: PersistenceSaveStatus,
        hasUnsavedChanges: Boolean,
        undoDepth: Int,
        redoDepth: Int,
    ) {
        _persistenceDiagnostics.update {
            it.copy(
                saveStatus = saveStatus,
                hasUnsavedChanges = hasUnsavedChanges,
                inMemoryUndoDepth = undoDepth,
                inMemoryRedoDepth = redoDepth,
            )
        }
    }

    override suspend fun renameProject(projectId: String, newTitle: String): EngineResult<Unit> =
        withContext(dispatchers.io) {
            val trimmed = newTitle.trim()
            if (trimmed.isEmpty()) {
                return@withContext EngineResult.Failure(
                    EngineError(
                        code = EngineErrorCode.INVALID_STATE,
                        sourceModule = "core:database",
                        userMessage = "Project title cannot be empty.",
                        diagnosticDetail = "Empty rename requested for $projectId",
                    ),
                )
            }
            val loaded = storage.readWithRecovery(projectId)
            if (loaded is EngineResult.Success) {
                val updated = loaded.value.document.copy(
                    title = trimmed,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                storage.writeAtomic(updated)
            }
            dao.updateTitle(projectId, trimmed, System.currentTimeMillis())
            EngineResult.Success(Unit)
        }

    override suspend fun duplicateProject(projectId: String): EngineResult<ProjectDocument> =
        withContext(dispatchers.io) {
            when (val loaded = storage.readWithRecovery(projectId)) {
                is EngineResult.Failure -> loaded
                is EngineResult.Success -> {
                    val now = System.currentTimeMillis()
                    val newId = "proj_${UUID.randomUUID().toString().take(8)}"
                    val copyDoc = loaded.value.document.copy(
                        projectId = newId,
                        title = "${loaded.value.document.title} Copy",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                        revision = 1L,
                    )
                    when (val writeRes = storage.writeAtomic(copyDoc)) {
                        is EngineResult.Failure -> writeRes
                        is EngineResult.Success -> {
                            dao.upsert(copyDoc.toEntity(recovered = false, hasSnapshot = false, corrupt = false))
                            assetDao.replaceAssetsForProject(newId, copyDoc.toAssetEntities())
                            EngineResult.Success(copyDoc)
                        }
                    }
                }
            }
        }

    override suspend fun relinkProjectAsset(
        projectId: String,
        oldAssetId: String,
        updatedAsset: MediaAsset,
    ): EngineResult<ProjectDocument> = withContext(dispatchers.io) {
        when (val loaded = storage.readWithRecovery(projectId)) {
            is EngineResult.Failure -> loaded
            is EngineResult.Success -> {
                val doc = loaded.value.document
                val replaced = updatedAsset.copy(
                    assetId = oldAssetId,
                    availability = AssetAvailability.AVAILABLE,
                )
                val nextAssets = if (doc.assets.any { it.assetId == oldAssetId }) {
                    doc.assets.map { if (it.assetId == oldAssetId) replaced else it }
                } else {
                    doc.assets + replaced
                }
                val nextTracks = doc.sequence.tracks.map { track ->
                    track.copy(
                        clips = track.clips.map { clip ->
                            if (clip.mediaSource.assetId == oldAssetId) {
                                clip.copy(
                                    label = replaced.displayName.take(22),
                                    mediaSource = clip.mediaSource.copy(
                                        uriString = replaced.uriString,
                                        mediaKind = replaced.mediaKind,
                                    ),
                                )
                            } else {
                                clip
                            }
                        },
                    )
                }
                val updatedDoc = doc.copy(
                    updatedAtEpochMs = System.currentTimeMillis(),
                    sequence = doc.sequence.copy(tracks = nextTracks),
                    assets = nextAssets,
                    revision = doc.revision + 1L,
                )
                when (val writeRes = storage.writeAtomic(updatedDoc)) {
                    is EngineResult.Failure -> writeRes
                    is EngineResult.Success -> {
                        dao.upsert(updatedDoc.toEntity(recovered = false, hasSnapshot = false, corrupt = false))
                        assetDao.replaceAssetsForProject(projectId, updatedDoc.toAssetEntities())
                        VyroLogger.log(LogSeverity.INFO, "core:database", "Relinked asset $oldAssetId", projectId)
                        EngineResult.Success(updatedDoc)
                    }
                }
            }
        }
    }

    override suspend fun updateProjectAssetStatuses(
        projectId: String,
        updatedAssets: List<MediaAsset>,
    ): EngineResult<ProjectDocument> = withContext(dispatchers.io) {
        when (val loaded = storage.readWithRecovery(projectId)) {
            is EngineResult.Failure -> loaded
            is EngineResult.Success -> {
                val doc = loaded.value.document
                if (doc.assets == updatedAssets) {
                    return@withContext EngineResult.Success(doc)
                }
                val updatedDoc = doc.copy(assets = updatedAssets)
                storage.writeAtomic(updatedDoc)
                dao.upsert(updatedDoc.toEntity(recovered = false, hasSnapshot = false, corrupt = false))
                assetDao.replaceAssetsForProject(projectId, updatedDoc.toAssetEntities())
                EngineResult.Success(updatedDoc)
            }
        }
    }

    override suspend fun deleteProject(projectId: String): EngineResult<Unit> = withContext(dispatchers.io) {
        storage.deleteProjectFiles(projectId)
        assetDao.deleteAssetsForProject(projectId)
        dao.deleteById(projectId)
        VyroLogger.log(LogSeverity.INFO, "core:database", "Deleted project $projectId", projectId)
        EngineResult.Success(Unit)
    }

    private fun ProjectDocument.toEntity(
        recovered: Boolean,
        hasSnapshot: Boolean,
        corrupt: Boolean,
    ): ProjectIndexEntity {
        val clipCount = sequence.tracks.sumOf { it.clips.size }
        val firstVisual = sequence.tracks.firstOrNull { it.type == TrackType.VIDEO }?.clips?.firstOrNull()
        val missingCount = assets.count { it.availability != AssetAvailability.AVAILABLE }
        return ProjectIndexEntity(
            projectId = projectId,
            title = title,
            width = sequence.width,
            height = sequence.height,
            fpsNumerator = sequence.frameRate.numerator,
            fpsDenominator = sequence.frameRate.denominator,
            durationUs = sequence.durationUs,
            clipCount = clipCount,
            updatedAtEpochMs = updatedAtEpochMs,
            recoveredFromBackup = recovered,
            previewColorArgb = firstVisual?.mediaSource?.backdropColorArgb ?: 0xFF1C2029L,
            previewUriString = firstVisual?.mediaSource?.uriString,
            missingAssetCount = missingCount,
            schemaVersion = schemaVersion,
            hasPendingRecoverySnapshot = hasSnapshot,
            isCorrupt = corrupt,
        )
    }

    private fun ProjectDocument.toAssetEntities(): List<MediaAssetEntity> = assets.map { asset ->
        MediaAssetEntity(
            projectId = projectId,
            assetId = asset.assetId,
            uriString = asset.uriString,
            displayName = asset.displayName,
            mediaKind = asset.mediaKind.name,
            mimeType = asset.mimeType,
            width = asset.width,
            height = asset.height,
            durationUs = asset.durationUs,
            availability = asset.availability.name,
            addedAtEpochMs = asset.addedAtEpochMs,
        )
    }
}

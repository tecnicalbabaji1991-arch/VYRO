package com.vyro.core.contracts

import com.vyro.core.model.ProjectDocument

/**
 * Policy governing undo/redo history across editing sessions and process termination.
 */
enum class UndoHistoryPolicy(val displayLabel: String) {
    /**
     * Live reversible undo/redo stack is bounded in memory during active editing and
     * resets cleanly across process restarts; committed documents and recovery snapshots
     * store full sequence state plus a bounded command-label breadcrumb trail.
     */
    RESET_ON_PROCESS_RESTART("Session-bounded (max 50), reset on restart");

    companion object {
        const val MAX_IN_MEMORY_UNDO_STEPS = 50
        const val MAX_PERSISTED_COMMAND_TRAIL = 15
    }
}

enum class PersistenceSaveStatus {
    IDLE,
    DIRTY,
    AUTOSAVING,
    SAVED,
    RECOVERED_SNAPSHOT,
    RECOVERED_BACKUP,
    FAILED,
}

enum class RecoverySource {
    NONE,
    CLEAN_PRIMARY,
    RECOVERY_SNAPSHOT,
    BACKUP_FILE,
}

data class ProjectLoadResult(
    val document: ProjectDocument,
    val recoverySource: RecoverySource,
    val migratedFromVersion: Int? = null,
    val recoveredCommandLabels: List<String> = emptyList(),
    val hasCommittedBaseline: Boolean = false,
)

data class ProjectHealthStatus(
    val projectId: String,
    val isReadable: Boolean,
    val hasBackup: Boolean,
    val hasPendingSnapshot: Boolean,
    val schemaVersion: Int,
    val isCorrupt: Boolean,
    val detail: String,
)

data class PersistenceDiagnosticsSnapshot(
    val schemaVersion: Int = ProjectDocument.CURRENT_SCHEMA_VERSION,
    val migratedFromVersion: Int? = null,
    val undoPolicy: UndoHistoryPolicy = UndoHistoryPolicy.RESET_ON_PROCESS_RESTART,
    val inMemoryUndoDepth: Int = 0,
    val inMemoryRedoDepth: Int = 0,
    val saveStatus: PersistenceSaveStatus = PersistenceSaveStatus.IDLE,
    val recoverySource: RecoverySource = RecoverySource.NONE,
    val autosaveCount: Long = 0L,
    val recoverySnapshotCount: Long = 0L,
    val recoveredProjectCount: Int = 0,
    val corruptProjectCount: Int = 0,
    val lastSavedRevision: Long = 0L,
    val hasUnsavedChanges: Boolean = false,
    val lastSavedEpochMs: Long = 0L,
    val lastRecoveryDetail: String = "Clean persistence baseline",
)

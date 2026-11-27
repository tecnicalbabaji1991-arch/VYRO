package com.vyro.core.database

import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.ProjectHealthStatus
import com.vyro.core.contracts.RecoverySource
import com.vyro.core.model.ProjectDocument
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class AtomicProjectStorage(private val rootDir: File) {

    data class LoadOutcome(
        val document: ProjectDocument,
        val recoveredFromBackup: Boolean,
        val recoverySource: RecoverySource = if (recoveredFromBackup) {
            RecoverySource.BACKUP_FILE
        } else {
            RecoverySource.CLEAN_PRIMARY
        },
        val migratedFromVersion: Int? = null,
        val recoveredCommandLabels: List<String> = emptyList(),
        val hasCommittedBaseline: Boolean = true,
    )

    private val projectLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(projectId: String): Any = projectLocks.computeIfAbsent(projectId) { Any() }

    fun projectDir(projectId: String): File = File(File(rootDir, "projects"), projectId)

    fun writeAtomic(
        document: ProjectDocument,
        recentCommandLabels: List<String> = emptyList(),
    ): EngineResult<Unit> = synchronized(lockFor(document.projectId)) {
        val dir = projectDir(document.projectId)
        val primary = File(dir, PRIMARY_FILE)
        val backup = File(dir, BACKUP_FILE)
        val backupTmp = File(dir, "$BACKUP_FILE.tmp")
        val temp = File(dir, "$PRIMARY_FILE.tmp")

        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                return@synchronized storageFailure("Failed to create project directory for ${document.projectId}")
            }

            val serialized = ProjectSchemaMigrator.serializeProject(document, recentCommandLabels).toString(2)
            writeAndSyncFile(temp, serialized)

            // Only rotate primary -> backup when primary parses cleanly (never overwrite good backup with corrupt primary)
            if (primary.exists() && primary.length() > 0L) {
                val primaryRaw = runCatching { primary.readText(Charsets.UTF_8) }.getOrNull()
                if (primaryRaw != null && runCatching { ProjectSchemaMigrator.deserializeProject(primaryRaw) }.isSuccess) {
                    writeAndSyncFile(backupTmp, primaryRaw)
                    atomicReplace(backupTmp, backup)
                }
            }

            atomicReplace(temp, primary)
            // Clean committed save supersedes pending crash recovery snapshot
            clearRecoverySnapshotLocked(dir)
            EngineResult.Success(Unit)
        } catch (ex: Exception) {
            runCatching { temp.delete() }
            runCatching { backupTmp.delete() }
            storageFailure(ex.message ?: "Atomic write failed")
        }
    }

    fun writeRecoverySnapshot(
        document: ProjectDocument,
        recentCommandLabels: List<String> = emptyList(),
    ): EngineResult<Unit> = synchronized(lockFor(document.projectId)) {
        val dir = projectDir(document.projectId)
        val snapshot = File(dir, SNAPSHOT_FILE)
        val temp = File(dir, "$SNAPSHOT_FILE.tmp")
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                return@synchronized storageFailure("Failed to create snapshot directory for ${document.projectId}")
            }
            val now = System.currentTimeMillis()
            val payload = ProjectSchemaMigrator.serializeRecoverySnapshot(
                doc = document,
                savedAtEpochMs = now,
                recentCommandLabels = recentCommandLabels,
            ).toString(2)
            writeAndSyncFile(temp, payload)
            atomicReplace(temp, snapshot)
            EngineResult.Success(Unit)
        } catch (ex: Exception) {
            runCatching { temp.delete() }
            storageFailure(ex.message ?: "Recovery snapshot write failed")
        }
    }

    fun discardRecoverySnapshot(projectId: String): EngineResult<LoadOutcome> = synchronized(lockFor(projectId)) {
        clearRecoverySnapshotLocked(projectDir(projectId))
        readPrimaryOrBackupLocked(projectId, allowSnapshot = false)
    }

    fun clearRecoverySnapshot(projectId: String) = synchronized(lockFor(projectId)) {
        clearRecoverySnapshotLocked(projectDir(projectId))
    }

    fun readWithRecovery(projectId: String): EngineResult<LoadOutcome> = synchronized(lockFor(projectId)) {
        readPrimaryOrBackupLocked(projectId, allowSnapshot = true)
    }

    fun inspectProjectHealth(projectId: String): ProjectHealthStatus = synchronized(lockFor(projectId)) {
        val dir = projectDir(projectId)
        val primary = File(dir, PRIMARY_FILE)
        val backup = File(dir, BACKUP_FILE)
        val snapshot = File(dir, SNAPSHOT_FILE)

        val primaryParsed = readParsedProjectIfValid(primary)
        val backupParsed = readParsedProjectIfValid(backup)
        val snapshotParsed = readParsedSnapshotIfValid(snapshot)

        val isReadable = primaryParsed != null || backupParsed != null || snapshotParsed != null
        val detectedSchema = primaryParsed?.migratedFromVersion
            ?: primaryParsed?.document?.schemaVersion
            ?: backupParsed?.document?.schemaVersion
            ?: snapshotParsed?.document?.schemaVersion
            ?: runCatching {
                if (primary.exists()) JSONObject(primary.readText(Charsets.UTF_8)).optInt("schemaVersion", 0) else 0
            }.getOrDefault(0)

        val detail = when {
            snapshotParsed != null -> "Crash recovery snapshot available (rev ${snapshotParsed.document.revision})"
            primaryParsed != null -> "Healthy (v${primaryParsed.document.schemaVersion}, rev ${primaryParsed.document.revision})"
            backupParsed != null -> "Primary unreadable; backup ready"
            else -> "Project document missing or damaged"
        }

        ProjectHealthStatus(
            projectId = projectId,
            isReadable = isReadable,
            hasBackup = backupParsed != null,
            hasPendingSnapshot = snapshotParsed != null,
            schemaVersion = detectedSchema,
            isCorrupt = !isReadable,
            detail = detail,
        )
    }

    fun deleteProjectFiles(projectId: String) = synchronized(lockFor(projectId)) {
        val dir = projectDir(projectId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun readPrimaryOrBackupLocked(
        projectId: String,
        allowSnapshot: Boolean,
    ): EngineResult<LoadOutcome> {
        val dir = projectDir(projectId)
        cleanupIncompleteTempsLocked(dir)

        val primary = File(dir, PRIMARY_FILE)
        val backup = File(dir, BACKUP_FILE)
        val snapshot = File(dir, SNAPSHOT_FILE)

        val primaryParsed = if (primary.exists()) {
            try {
                ProjectSchemaMigrator.deserializeProject(primary.readText(Charsets.UTF_8))
            } catch (primaryErr: Exception) {
                VyroLogger.log(
                    LogSeverity.WARN,
                    "core:database",
                    "Primary project.json invalid (${primaryErr.message}); checking recovery sources",
                    correlationId = projectId,
                    errorCode = EngineErrorCode.CORRUPT_PROJECT,
                )
                null
            }
        } else {
            null
        }

        val snapshotParsed = if (allowSnapshot && snapshot.exists()) {
            try {
                ProjectSchemaMigrator.deserializeRecoverySnapshot(snapshot.readText(Charsets.UTF_8))
            } catch (snapErr: Exception) {
                VyroLogger.log(
                    LogSeverity.WARN,
                    "core:database",
                    "Incomplete/corrupt recovery snapshot ignored: ${snapErr.message}",
                    correlationId = projectId,
                    errorCode = EngineErrorCode.CORRUPT_PROJECT,
                )
                runCatching { snapshot.delete() }
                null
            }
        } else {
            null
        }

        // Prefer recovery snapshot when it represents newer or uncommitted edits after forced termination
        if (snapshotParsed != null) {
            val shouldUseSnapshot = primaryParsed == null ||
                snapshotParsed.document.revision > primaryParsed.document.revision ||
                snapshotParsed.savedAtEpochMs >= primaryParsed.document.updatedAtEpochMs
            if (shouldUseSnapshot) {
                VyroLogger.log(
                    LogSeverity.INFO,
                    "core:database",
                    "Recovered project from crash snapshot (rev ${snapshotParsed.document.revision})",
                    correlationId = projectId,
                )
                return EngineResult.Success(
                    LoadOutcome(
                        document = snapshotParsed.document,
                        recoveredFromBackup = false,
                        recoverySource = RecoverySource.RECOVERY_SNAPSHOT,
                        migratedFromVersion = primaryParsed?.migratedFromVersion,
                        recoveredCommandLabels = snapshotParsed.recentCommandLabels,
                        hasCommittedBaseline = primaryParsed != null || backup.exists(),
                    ),
                )
            }
        }

        if (primaryParsed != null) {
            if (primaryParsed.migratedFromVersion != null) {
                VyroLogger.log(
                    LogSeverity.INFO,
                    "core:database",
                    "Migrated project schema v${primaryParsed.migratedFromVersion} -> v${ProjectDocument.CURRENT_SCHEMA_VERSION}",
                    correlationId = projectId,
                )
            }
            return EngineResult.Success(
                LoadOutcome(
                    document = primaryParsed.document,
                    recoveredFromBackup = false,
                    recoverySource = RecoverySource.CLEAN_PRIMARY,
                    migratedFromVersion = primaryParsed.migratedFromVersion,
                    recoveredCommandLabels = primaryParsed.recentCommandLabels,
                    hasCommittedBaseline = true,
                ),
            )
        }

        if (backup.exists()) {
            return try {
                val backupParsed = ProjectSchemaMigrator.deserializeProject(backup.readText(Charsets.UTF_8))
                VyroLogger.log(
                    LogSeverity.WARN,
                    "core:database",
                    "Recovered project automatically from backup file",
                    correlationId = projectId,
                )
                EngineResult.Success(
                    LoadOutcome(
                        document = backupParsed.document,
                        recoveredFromBackup = true,
                        recoverySource = RecoverySource.BACKUP_FILE,
                        migratedFromVersion = backupParsed.migratedFromVersion,
                        recoveredCommandLabels = backupParsed.recentCommandLabels,
                        hasCommittedBaseline = true,
                    ),
                )
            } catch (backupErr: Exception) {
                corruptError("Primary and backup project files are unreadable: ${backupErr.message}")
            }
        }

        return corruptError("Project document missing or damaged for $projectId")
    }

    private fun readParsedProjectIfValid(file: File): ProjectSchemaMigrator.ParsedDocument? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { ProjectSchemaMigrator.deserializeProject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun readParsedSnapshotIfValid(file: File): ProjectSchemaMigrator.ParsedSnapshot? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { ProjectSchemaMigrator.deserializeRecoverySnapshot(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun clearRecoverySnapshotLocked(dir: File) {
        File(dir, SNAPSHOT_FILE).takeIf { it.exists() }?.delete()
        File(dir, "$SNAPSHOT_FILE.tmp").takeIf { it.exists() }?.delete()
    }

    private fun cleanupIncompleteTempsLocked(dir: File) {
        if (!dir.exists()) return
        listOf("$PRIMARY_FILE.tmp", "$BACKUP_FILE.tmp", "$SNAPSHOT_FILE.tmp").forEach { name ->
            val tmp = File(dir, name)
            if (tmp.exists()) {
                VyroLogger.log(
                    LogSeverity.WARN,
                    "core:database",
                    "Cleaning abandoned partial write $name after interrupted session",
                    correlationId = dir.name,
                )
                runCatching { tmp.delete() }
            }
        }
    }

    private fun writeAndSyncFile(target: File, content: String) {
        FileOutputStream(target).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun storageFailure(detail: String) = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.STORAGE_FAILURE,
            sourceModule = "core:database",
            userMessage = "Could not save project file safely.",
            diagnosticDetail = detail,
            recoverable = true,
        ),
    )

    private fun corruptError(detail: String) = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.CORRUPT_PROJECT,
            sourceModule = "core:database",
            userMessage = "Project file is missing or damaged.",
            diagnosticDetail = detail,
            recoverable = false,
        ),
    )

    companion object {
        internal const val PRIMARY_FILE = "project.json"
        internal const val BACKUP_FILE = "project.backup.json"
        internal const val SNAPSHOT_FILE = "recovery_snapshot.json"
    }
}

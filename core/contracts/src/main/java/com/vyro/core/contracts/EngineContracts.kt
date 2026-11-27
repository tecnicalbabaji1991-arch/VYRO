package com.vyro.core.contracts

import com.vyro.core.common.EngineResult
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.Clip
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.ProjectSummary
import com.vyro.core.model.Rational
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EngineBackendMode {
    NATIVE_JNI,
    JVM_CONTRACT_FALLBACK,
}

data class EngineTelemetry(
    val backendMode: EngineBackendMode,
    val nativeHandle: Long,
    val activeProjectId: Long,
    val playheadUs: Long,
    val isPlaying: Boolean,
    val surfaceAttached: Boolean,
    val commandCount: Long,
    val activeSessionCount: Int,
)

data class DeviceCapabilities(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val openGlEsVersion: String,
    val hardwareVideoDecoders: List<String>,
    val hardwareVideoEncoders: List<String>,
    val nativeLibraryLoaded: Boolean,
)

data class SmokeTestReport(
    val iterations: Int,
    val passed: Boolean,
    val backendMode: EngineBackendMode,
    val elapsedMs: Long,
    val leakedSessions: Int,
    val summary: String,
)

interface EngineSessionContract {
    suspend fun initialize(): EngineResult<EngineTelemetry>
    suspend fun openProject(document: ProjectDocument): EngineResult<Long>
    suspend fun closeProject(nativeProjectId: Long): EngineResult<Unit>
    suspend fun submitCommand(nativeProjectId: Long, command: EditCommand): EngineResult<EngineTelemetry>
    suspend fun seek(nativeProjectId: Long, positionUs: Long): EngineResult<Long>
    suspend fun play(nativeProjectId: Long): EngineResult<Unit>
    suspend fun pause(nativeProjectId: Long): EngineResult<Unit>
    suspend fun telemetry(): EngineTelemetry
    suspend fun queryDeviceCapabilities(): DeviceCapabilities
    suspend fun runLifecycleSmokeTest(iterations: Int = 50): EngineResult<SmokeTestReport>
    fun release()
}

interface MediaInspectorContract {
    suspend fun inspectUri(uriString: String): EngineResult<MediaAsset>
    suspend fun revalidateAssets(assets: List<MediaAsset>): List<MediaAsset>
    suspend fun relinkAsset(oldAssetId: String, newUriString: String): EngineResult<MediaAsset>
}

interface ProjectRepository {
    val persistenceDiagnostics: StateFlow<PersistenceDiagnosticsSnapshot>

    fun observeProjects(): Flow<List<ProjectSummary>>
    suspend fun createProject(
        title: String,
        aspectPreset: AspectRatioPreset,
        frameRate: Rational,
        seedStarterTimeline: Boolean = true,
        initialClips: List<Clip> = emptyList(),
        initialAssets: List<MediaAsset> = emptyList(),
    ): EngineResult<ProjectDocument>
    suspend fun loadProject(projectId: String): EngineResult<ProjectDocument>
    suspend fun loadProjectWithDetails(projectId: String): EngineResult<ProjectLoadResult>
    suspend fun saveProject(document: ProjectDocument): EngineResult<Unit>
    suspend fun saveRecoverySnapshot(
        document: ProjectDocument,
        recentCommandLabels: List<String> = emptyList(),
    ): EngineResult<Unit>
    suspend fun discardRecoverySnapshot(projectId: String): EngineResult<ProjectLoadResult>
    suspend fun clearRecoverySnapshot(projectId: String)
    suspend fun verifyAllProjectsHealth(): List<ProjectHealthStatus>
    fun updatePersistenceRuntimeStats(
        saveStatus: PersistenceSaveStatus,
        hasUnsavedChanges: Boolean,
        undoDepth: Int,
        redoDepth: Int,
    )
    suspend fun renameProject(projectId: String, newTitle: String): EngineResult<Unit>
    suspend fun duplicateProject(projectId: String): EngineResult<ProjectDocument>
    suspend fun relinkProjectAsset(
        projectId: String,
        oldAssetId: String,
        updatedAsset: MediaAsset,
    ): EngineResult<ProjectDocument>
    suspend fun updateProjectAssetStatuses(
        projectId: String,
        updatedAssets: List<MediaAsset>,
    ): EngineResult<ProjectDocument>
    suspend fun deleteProject(projectId: String): EngineResult<Unit>
}

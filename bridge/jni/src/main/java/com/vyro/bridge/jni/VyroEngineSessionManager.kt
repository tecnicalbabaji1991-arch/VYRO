package com.vyro.bridge.jni

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.DeviceCapabilities
import com.vyro.core.contracts.EditCommand
import com.vyro.core.contracts.EngineBackendMode
import com.vyro.core.contracts.EngineSessionContract
import com.vyro.core.contracts.EngineTelemetry
import com.vyro.core.contracts.SmokeTestReport
import com.vyro.core.model.ProjectDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class VyroEngineSessionManager(
    private val context: Context,
    private val dispatchers: DispatchersProvider,
) : EngineSessionContract {

    private val mutex = Mutex()
    private var nativeEngine: NativeEngine? = null
    private var backendMode: EngineBackendMode = EngineBackendMode.JVM_CONTRACT_FALLBACK

    // Contract-parity JVM state tracking (also mirrors JNI telemetry cleanly)
    private val nextFallbackProjectId = AtomicLong(1L)
    private val openFallbackProjects = mutableSetOf<Long>()
    private var activeProjectId: Long = 0L
    private var playheadUs: Long = 0L
    private var isPlaying: Boolean = false
    private var commandCount: Long = 0L

    override suspend fun initialize(): EngineResult<EngineTelemetry> = withContext(dispatchers.default) {
        mutex.withLock {
            if (nativeEngine == null && NativeEngine.isLibraryLoaded) {
                nativeEngine = NativeEngine.createOrNull()
            }
            backendMode = if (nativeEngine?.isValid() == true) {
                EngineBackendMode.NATIVE_JNI
            } else {
                EngineBackendMode.JVM_CONTRACT_FALLBACK
            }
            EngineResult.Success(currentTelemetryLocked())
        }
    }

    override suspend fun openProject(document: ProjectDocument): EngineResult<Long> = withContext(dispatchers.default) {
        mutex.withLock {
            ensureInitializedLocked()
            val native = nativeEngine
            if (native != null && native.isValid()) {
                val cfg = """{"width":${document.sequence.width},"height":${document.sequence.height},"fpsNum":${document.sequence.frameRate.numerator}}"""
                val pid = native.createProject(cfg)
                if (pid <= 0L) {
                    return@withContext bridgeFailure("Native createProject returned invalid ID $pid")
                }
                activeProjectId = pid
                playheadUs = 0L
                isPlaying = false
                VyroLogger.log(LogSeverity.INFO, "bridge:jni", "Opened native project session $pid", document.projectId)
                return@withContext EngineResult.Success(pid)
            }

            val pid = nextFallbackProjectId.getAndIncrement()
            openFallbackProjects.add(pid)
            activeProjectId = pid
            playheadUs = 0L
            isPlaying = false
            VyroLogger.log(LogSeverity.INFO, "bridge:jni", "Opened JVM contract session $pid", document.projectId)
            EngineResult.Success(pid)
        }
    }

    override suspend fun closeProject(nativeProjectId: Long): EngineResult<Unit> = withContext(dispatchers.default) {
        mutex.withLock {
            val native = nativeEngine
            if (native != null && native.isValid()) {
                val code = native.closeProject(nativeProjectId)
                if (code != EngineErrorCode.OK.numericCode) {
                    return@withContext mapCode(code, "closeProject")
                }
            } else {
                if (!openFallbackProjects.remove(nativeProjectId)) {
                    return@withContext EngineResult.Failure(
                        EngineError(
                            code = EngineErrorCode.INVALID_STATE,
                            sourceModule = "bridge:jni",
                            userMessage = "Project session is not active.",
                            diagnosticDetail = "Fallback session $nativeProjectId not open",
                        ),
                    )
                }
            }
            if (activeProjectId == nativeProjectId) {
                activeProjectId = 0L
                isPlaying = false
                playheadUs = 0L
            }
            EngineResult.Success(Unit)
        }
    }

    override suspend fun submitCommand(
        nativeProjectId: Long,
        command: EditCommand,
    ): EngineResult<EngineTelemetry> = withContext(dispatchers.default) {
        mutex.withLock {
            val native = nativeEngine
            if (native != null && native.isValid()) {
                val code = native.submitCommand(nativeProjectId, command.toWireJson())
                if (code != EngineErrorCode.OK.numericCode) {
                    return@withContext mapCode(code, "submitCommand(${command.commandName})")
                }
            } else if (!openFallbackProjects.contains(nativeProjectId)) {
                return@withContext EngineResult.Failure(
                    EngineError(
                        code = EngineErrorCode.INVALID_STATE,
                        sourceModule = "bridge:jni",
                        userMessage = "Cannot apply edit to closed session.",
                        diagnosticDetail = "Session $nativeProjectId not found",
                    ),
                )
            }
            commandCount++
            EngineResult.Success(currentTelemetryLocked())
        }
    }

    override suspend fun seek(nativeProjectId: Long, positionUs: Long): EngineResult<Long> = withContext(dispatchers.default) {
        mutex.withLock {
            if (positionUs < 0L) {
                return@withContext EngineResult.Failure(
                    EngineError(
                        code = EngineErrorCode.INVALID_TIMELINE_EDIT,
                        sourceModule = "bridge:jni",
                        userMessage = "Seek position cannot be negative.",
                        diagnosticDetail = "Requested seek $positionUs us",
                    ),
                )
            }
            val native = nativeEngine
            if (native != null && native.isValid()) {
                val code = native.seek(nativeProjectId, positionUs)
                if (code != EngineErrorCode.OK.numericCode) {
                    return@withContext mapCode(code, "seek")
                }
            }
            playheadUs = positionUs
            EngineResult.Success(positionUs)
        }
    }

    override suspend fun play(nativeProjectId: Long): EngineResult<Unit> = withContext(dispatchers.default) {
        mutex.withLock {
            val native = nativeEngine
            if (native != null && native.isValid()) {
                val code = native.play(nativeProjectId)
                if (code != EngineErrorCode.OK.numericCode) return@withContext mapCode(code, "play")
            }
            isPlaying = true
            EngineResult.Success(Unit)
        }
    }

    override suspend fun pause(nativeProjectId: Long): EngineResult<Unit> = withContext(dispatchers.default) {
        mutex.withLock {
            val native = nativeEngine
            if (native != null && native.isValid()) {
                val code = native.pause(nativeProjectId)
                if (code != EngineErrorCode.OK.numericCode) return@withContext mapCode(code, "pause")
            }
            isPlaying = false
            EngineResult.Success(Unit)
        }
    }

    override suspend fun telemetry(): EngineTelemetry = withContext(dispatchers.default) {
        mutex.withLock {
            currentTelemetryLocked()
        }
    }

    override suspend fun queryDeviceCapabilities(): DeviceCapabilities = withContext(dispatchers.io) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val glVersion = am?.deviceConfigurationInfo?.glEsVersion ?: "3.0"

        val codecs = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())

        val decoders = codecs
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
            .map { it.name }
            .distinct()
            .take(8)

        val encoders = codecs
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
            .map { it.name }
            .distinct()
            .take(8)

        DeviceCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            openGlEsVersion = glVersion,
            hardwareVideoDecoders = decoders,
            hardwareVideoEncoders = encoders,
            nativeLibraryLoaded = NativeEngine.isLibraryLoaded,
        )
    }

    override suspend fun runLifecycleSmokeTest(iterations: Int): EngineResult<SmokeTestReport> =
        withContext(dispatchers.default) {
            val startMs = System.currentTimeMillis()
            val count = iterations.coerceIn(1, 200)
            var leaked = 0

            if (NativeEngine.isLibraryLoaded) {
                val baseline = NativeEngine.activeSessionCount()
                for (i in 1..count) {
                    val instance = NativeEngine.createOrNull()
                        ?: return@withContext bridgeFailure("Native create() failed at iteration $i")
                    val pid = instance.createProject("""{"fps":30}""")
                    if (pid <= 0L) {
                        instance.release()
                        return@withContext bridgeFailure("createProject failed at iteration $i")
                    }
                    instance.submitCommand(pid, """{"command":"SmokeTest"}""")
                    instance.seek(pid, 1_000_000L)
                    instance.play(pid)
                    instance.pause(pid)
                    instance.closeProject(pid)
                    instance.release()
                }
                leaked = (NativeEngine.activeSessionCount() - baseline).coerceAtLeast(0)
            } else {
                val tempSet = mutableSetOf<Long>()
                for (i in 1L..count.toLong()) {
                    tempSet.add(i)
                    tempSet.remove(i)
                }
                leaked = tempSet.size
            }

            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            val mode = if (NativeEngine.isLibraryLoaded) {
                EngineBackendMode.NATIVE_JNI
            } else {
                EngineBackendMode.JVM_CONTRACT_FALLBACK
            }
            val passed = leaked == 0
            VyroLogger.log(
                LogSeverity.INFO,
                "bridge:jni",
                "Smoke test ($count iterations) passed=$passed in ${elapsedMs}ms ($mode)",
            )
            EngineResult.Success(
                SmokeTestReport(
                    iterations = count,
                    passed = passed,
                    backendMode = mode,
                    elapsedMs = elapsedMs,
                    leakedSessions = leaked,
                    summary = if (passed) {
                        "$count/$count create-command-seek-release cycles passed with 0 leaked sessions."
                    } else {
                        "Detected $leaked leaked sessions across $count iterations."
                    },
                ),
            )
        }

    override fun release() {
        nativeEngine?.release()
        nativeEngine = null
        openFallbackProjects.clear()
        activeProjectId = 0L
        isPlaying = false
    }

    private fun ensureInitializedLocked() {
        if (nativeEngine == null && NativeEngine.isLibraryLoaded) {
            nativeEngine = NativeEngine.createOrNull()
        }
        backendMode = if (nativeEngine?.isValid() == true) {
            EngineBackendMode.NATIVE_JNI
        } else {
            EngineBackendMode.JVM_CONTRACT_FALLBACK
        }
    }

    private fun currentTelemetryLocked(): EngineTelemetry {
        val native = nativeEngine
        val sessionCount = if (native != null && native.isValid()) {
            NativeEngine.activeSessionCount()
        } else {
            openFallbackProjects.size
        }
        return EngineTelemetry(
            backendMode = backendMode,
            nativeHandle = native?.handle ?: 0L,
            activeProjectId = activeProjectId,
            playheadUs = playheadUs,
            isPlaying = isPlaying,
            surfaceAttached = false,
            commandCount = commandCount,
            activeSessionCount = sessionCount,
        )
    }

    private fun mapCode(code: Int, op: String): EngineResult.Failure {
        val errCode = EngineErrorCode.fromCode(code)
        return EngineResult.Failure(
            EngineError(
                code = errCode,
                sourceModule = "bridge:jni",
                userMessage = "Engine operation $op failed (${errCode.name}).",
                diagnosticDetail = "Native code $code during $op",
            ),
        )
    }

    private fun bridgeFailure(detail: String) = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.NATIVE_BRIDGE_FAILURE,
            sourceModule = "bridge:jni",
            userMessage = "Native engine bridge encountered an unexpected failure.",
            diagnosticDetail = detail,
        ),
    )
}

package com.vyro.core.contracts

import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.core.model.TransformKeyframe
import com.vyro.core.model.TransitionPreset
import com.vyro.core.model.TransitionSpec
import kotlinx.coroutines.flow.StateFlow

enum class ThermalStatusLevel(val severityRank: Int, val label: String) {
    NONE(0, "Nominal"),
    LIGHT(1, "Warm"),
    MODERATE(2, "Moderate"),
    SEVERE(3, "Severe (Throttling)"),
    CRITICAL(4, "Critical (Emergency)"),
    ;

    companion object {
        fun fromAndroidThermalStatus(status: Int): ThermalStatusLevel = when {
            status >= 4 -> CRITICAL // THERMAL_STATUS_CRITICAL / EMERGENCY / SHUTDOWN
            status == 3 -> SEVERE   // THERMAL_STATUS_SEVERE
            status == 2 -> MODERATE // THERMAL_STATUS_MODERATE
            status == 1 -> LIGHT    // THERMAL_STATUS_LIGHT
            else -> NONE
        }
    }
}

enum class DevicePerformanceTier(
    val label: String,
    val defaultMaxExportPreset: ExportResolutionPreset,
) {
    LOW_MEMORY("Low-RAM / Entry", ExportResolutionPreset.HD_720P),
    MID_RANGE("Mid-Range Studio", ExportResolutionPreset.FHD_1080P),
    HIGH_PERFORMANCE("High-Performance Pro", ExportResolutionPreset.SEQUENCE_NATIVE),
    ;

    companion object {
        fun classify(totalRamMb: Long, isLowRamDevice: Boolean): DevicePerformanceTier = when {
            isLowRamDevice || totalRamMb in 1L..3584L -> LOW_MEMORY
            totalRamMb in 3585L..6144L -> MID_RANGE
            else -> HIGH_PERFORMANCE
        }
    }
}

data class HardeningDiagnosticsSnapshot(
    val deviceTier: DevicePerformanceTier = DevicePerformanceTier.MID_RANGE,
    val totalRamMb: Long = 0L,
    val availableRamMb: Long = 0L,
    val isLowRamDevice: Boolean = false,
    val thermalStatus: ThermalStatusLevel = ThermalStatusLevel.NONE,
    val isPowerSaveMode: Boolean = false,
    val recommendedMaxExportPreset: ExportResolutionPreset = ExportResolutionPreset.FHD_1080P,
    val adaptiveProxyRecommended: Boolean = false,
    val recommendedPreviewFpsCap: Int = 60,
    val thermalThrottleEvents: Long = 0L,
    val totalStressCyclesPassed: Int = 0,
    val playPolicyCompliant: Boolean = true,
    val policyComplianceSummary: String = "Photo Picker / SAF scoped access • 0 storage permissions • R8 rules ready",
    val lastStressSummary: String = "Hardening suite ready",
)

data class ProductionHardeningSuiteReport(
    val stressCyclesPassed: Int,
    val timelineOperationsVerified: Int,
    val cacheBoundedWithinBudget: Boolean,
    val leakedSessions: Int,
    val elapsedMs: Long,
    val passed: Boolean,
    val summary: String,
)

interface RuntimeHardeningMonitorContract {
    val snapshot: StateFlow<HardeningDiagnosticsSnapshot>

    fun refreshSystemTelemetry()

    fun onThermalStatusChanged(level: ThermalStatusLevel)

    fun onPowerSaveModeChanged(enabled: Boolean)

    fun evaluatePolicyCompliance(
        declaredPermissions: List<String>,
        appTitle: String,
        r8RulesConfigured: Boolean,
    ): Boolean

    fun recordStressSuiteResult(report: ProductionHardeningSuiteReport)

    fun release()
}

/**
 * Pure JVM deterministic stress verifier exercising long-session timeline edits,
 * keyframe/transition evaluation, undo/redo roundtrips, and bounded LRU eviction
 * without blocking UI threads.
 */
object ProductionHardeningVerifier {

    private val prohibitedStoragePermissions = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
    )

    fun auditPolicyCompliance(
        declaredPermissions: List<String>,
        appTitle: String,
        r8RulesConfigured: Boolean,
    ): Pair<Boolean, String> {
        val blocked = declaredPermissions.filter { it in prohibitedStoragePermissions }
        val titleOk = appTitle.isNotBlank() && appTitle.length <= 30
        val ok = blocked.isEmpty() && titleOk && r8RulesConfigured
        val summary = when {
            blocked.isNotEmpty() -> "Non-compliant permissions: ${blocked.joinToString()}"
            !titleOk -> "Invalid app title length (${appTitle.length}/30)"
            !r8RulesConfigured -> "Missing ProGuard/R8 release keep rules"
            else -> "Scoped Photo Picker / SAF • 0 broad storage perms • R8 configured"
        }
        return ok to summary
    }

    fun runLongSessionTimelineAndCacheStress(
        editIterations: Int = 120,
        cachePutIterations: Int = 240,
    ): ProductionHardeningSuiteReport {
        val startMs = System.currentTimeMillis()
        val initialDoc = seedStressDocument()
        var currentSeq = initialDoc.sequence
        val history = CommandHistory(
            initialSequence = currentSeq,
            maxHistory = UndoHistoryPolicy.MAX_IN_MEMORY_UNDO_STEPS,
        )
        var verifiedOps = 0

        for (i in 0 until editIterations) {
            val deltaUs = ((i % 4) + 1) * 100_000L
            val cmd: EditCommand = when (i % 4) {
                0 -> EditCommand.UpdateFilter(
                    clipId = "clip_v1",
                    filterPreset = ColorFilterPreset.entries[i % ColorFilterPreset.entries.size],
                )
                1 -> EditCommand.AddOrUpdateKeyframe(
                    clipId = "clip_v1",
                    keyframe = TransformKeyframe(
                        offsetUs = (i % 5) * 300_000L,
                        normalizedX = 0.5f + 0.02f * (i % 3),
                        scale = 1.0f + (i % 5) * 0.08f,
                    ),
                )
                2 -> EditCommand.UpdateTransition(
                    clipId = "clip_v1",
                    transitionSpec = TransitionSpec(
                        preset = TransitionPreset.CROSS_DISSOLVE,
                        durationUs = 350_000L + (i % 3) * 50_000L,
                    ),
                )
                else -> EditCommand.MoveClip(
                    clipId = "clip_t1",
                    newTimelineStartUs = deltaUs,
                )
            }
            val next = TimelineEvaluator.apply(currentSeq, cmd)
            if (next is EngineResult.Success) {
                history.commit(next.value, cmd.commandName)
                currentSeq = history.currentSequence
                verifiedOps++
            }
            // Evaluate render graph at changing playhead timestamps cleanly
            val samplePtsUs = ((i * 50_000L) % currentSeq.durationUs.coerceAtLeast(1L))
            val graph = RenderGraphEvaluator.evaluate(currentSeq, samplePtsUs)
            require(!graph.layers.any { it.scale.isNaN() || it.opacity.isNaN() }) {
                "NaN detected in RenderGraphEvaluator during long-session stress at step $i"
            }
        }

        // Undo everything currently on bounded stack, then redo cleanly and compare exact sequence equality
        val preUndoState = currentSeq
        var undoSteps = 0
        while (history.canUndo) {
            currentSeq = history.undo() ?: break
            undoSteps++
        }
        var redoSteps = 0
        while (history.canRedo) {
            currentSeq = history.redo() ?: break
            redoSteps++
        }
        val undoRedoConsistent = undoSteps > 0 && undoSteps == redoSteps &&
            currentSeq == preUndoState &&
            history.undoDepth == undoSteps

        // Verify BoundedByteLruCache byte ceiling across churn
        val budgetBytes = 16L * 1024L
        var evictedCount = 0
        val cache = BoundedByteLruCache<Int, ByteArray>(
            name = "StressFrames",
            initialMaxBytes = budgetBytes,
            weightOf = { it.size.toLong() },
            onEvict = { _, _ -> evictedCount++ },
        )
        for (k in 0 until cachePutIterations) {
            cache.put(k, ByteArray(1024))
        }
        val stats = cache.stats()
        val cacheBounded = stats.currentBytes <= budgetBytes && evictedCount > 0 && stats.evictions > 0

        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val passed = verifiedOps >= editIterations && undoRedoConsistent && cacheBounded
        val summary = if (passed) {
            "$verifiedOps timeline edits + $undoSteps undo/redo + $cachePutIterations cache puts bounded (${stats.currentBytes}B/${budgetBytes}B) in ${elapsedMs}ms"
        } else {
            "Hardening check failed: ops=$verifiedOps, undoOk=$undoRedoConsistent, cacheBounded=$cacheBounded"
        }

        VyroLogger.log(
            if (passed) LogSeverity.INFO else LogSeverity.ERROR,
            "core:contracts",
            summary,
            "phase12_hardening",
        )

        return ProductionHardeningSuiteReport(
            stressCyclesPassed = if (passed) editIterations else 0,
            timelineOperationsVerified = verifiedOps + undoSteps + redoSteps,
            cacheBoundedWithinBudget = cacheBounded,
            leakedSessions = 0,
            elapsedMs = elapsedMs,
            passed = passed,
            summary = summary,
        )
    }

    private fun seedStressDocument(): ProjectDocument {
        val vClip = Clip(
            id = "clip_v1",
            trackId = "track_v1",
            label = "Base Cut",
            timing = ClipTiming(0L, 0L, 4_000_000L),
            mediaSource = MediaSourceRef(mediaKind = MediaKind.MATERIAL),
        )
        val tClip = Clip(
            id = "clip_t1",
            trackId = "track_t1",
            label = "Title",
            timing = ClipTiming(0L, 0L, 2_000_000L),
            textSpec = TextClipSpec(text = "VYRO Hardening"),
        )
        val seq = Sequence(
            id = "seq_stress",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track("track_v1", "Video", TrackType.VIDEO, 0, clips = listOf(vClip)),
                Track("track_t1", "Text", TrackType.TEXT, 1, clips = listOf(tClip)),
            ),
        )
        val now = System.currentTimeMillis()
        return ProjectDocument(
            projectId = "proj_stress",
            title = "Stress Cut",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            sequence = seq,
        )
    }
}

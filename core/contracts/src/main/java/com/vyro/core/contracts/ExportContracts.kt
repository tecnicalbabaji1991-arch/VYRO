package com.vyro.core.contracts

import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineResult
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.roundToInt

enum class ExportResolutionPreset(val label: String, val targetShortEdge: Int) {
    SD_480P("480p", 480),
    HD_720P("720p", 720),
    FHD_1080P("1080p", 1080),
    SEQUENCE_NATIVE("Native", 0),
}

enum class ExportVideoCodec(val mimeType: String, val displayName: String) {
    H264_AVC("video/avc", "H.264 / AVC"),
    H265_HEVC("video/hevc", "H.265 / HEVC"),
}

enum class ExportPhase {
    IDLE,
    VALIDATING,
    PREPARING,
    ENCODING,
    MUXING,
    VERIFYING,
    FINALIZED,
    CANCELLED,
    FAILED,
}

data class ExportConfig(
    val projectId: String,
    val outputTitle: String,
    val resolutionPreset: ExportResolutionPreset = ExportResolutionPreset.FHD_1080P,
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Rational = Rational.FPS_30,
    val videoCodec: ExportVideoCodec = ExportVideoCodec.H264_AVC,
    val videoBitrateBps: Int = 10_000_000,
    val audioSampleRateHz: Int = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
    val audioChannelCount: Int = AudioStreamFormat.CANONICAL_CHANNEL_COUNT,
    val audioBitrateBps: Int = 192_000,
    val includeAudio: Boolean = true,
    val saveToGallery: Boolean = false,
) {
    fun estimatedRequiredBytes(durationUs: Long): Long {
        val sec = (durationUs.coerceAtLeast(1_000_000L) / 1_000_000.0)
        val totalBps = videoBitrateBps.toLong() + if (includeAudio) audioBitrateBps.toLong() else 0L
        val mediaBytes = ((totalBps * sec) / 8.0).toLong()
        return mediaBytes + MIN_FREE_DISK_HEADROOM_BYTES
    }

    companion object {
        const val MIN_FREE_DISK_HEADROOM_BYTES = 2L * 1024L * 1024L

        fun fromSequence(
            doc: ProjectDocument,
            preset: ExportResolutionPreset = ExportResolutionPreset.FHD_1080P,
            frameRate: Rational = doc.sequence.frameRate,
            videoCodec: ExportVideoCodec = ExportVideoCodec.H264_AVC,
            videoBitrateBps: Int = defaultBitrateForPreset(preset),
            includeAudio: Boolean = true,
            saveToGallery: Boolean = false,
        ): ExportConfig {
            val (w, h) = computeAlignedDimensions(
                srcWidth = doc.sequence.width.coerceAtLeast(16),
                srcHeight = doc.sequence.height.coerceAtLeast(16),
                preset = preset,
            )
            return ExportConfig(
                projectId = doc.projectId,
                outputTitle = doc.title,
                resolutionPreset = preset,
                width = w,
                height = h,
                frameRate = frameRate,
                videoCodec = videoCodec,
                videoBitrateBps = videoBitrateBps,
                includeAudio = includeAudio,
                saveToGallery = saveToGallery,
            )
        }

        fun defaultBitrateForPreset(preset: ExportResolutionPreset): Int = when (preset) {
            ExportResolutionPreset.SD_480P -> 4_000_000
            ExportResolutionPreset.HD_720P -> 7_500_000
            ExportResolutionPreset.FHD_1080P -> 12_000_000
            ExportResolutionPreset.SEQUENCE_NATIVE -> 14_000_000
        }

        fun computeAlignedDimensions(
            srcWidth: Int,
            srcHeight: Int,
            preset: ExportResolutionPreset,
        ): Pair<Int, Int> {
            val safeW = max(16, srcWidth)
            val safeH = max(16, srcHeight)
            val targetShort = preset.targetShortEdge
            val rawPair = if (targetShort <= 0) {
                safeW to safeH
            } else if (safeW <= safeH) {
                val aspect = safeH.toFloat() / safeW.toFloat()
                targetShort to (targetShort * aspect).roundToInt()
            } else {
                val aspect = safeW.toFloat() / safeH.toFloat()
                (targetShort * aspect).roundToInt() to targetShort
            }
            // Hardware video encoders require even dimensions divisible by 2
            val alignedW = (max(16, rawPair.first) / 2) * 2
            val alignedH = (max(16, rawPair.second) / 2) * 2
            return alignedW to alignedH
        }
    }
}

data class ExportCodecCapabilityReport(
    val deviceSdkInt: Int = 30,
    val h264Encoders: List<String> = emptyList(),
    val hevcEncoders: List<String> = emptyList(),
    val aacEncoders: List<String> = emptyList(),
    val maxSupportedWidth: Int = 1920,
    val maxSupportedHeight: Int = 1920,
    val hardwareBackedEncoding: Boolean = false,
    val note: String = "Codec probe pending",
)

data class ExportValidationReport(
    val isValidContainer: Boolean,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val verifiedWidth: Int,
    val verifiedHeight: Int,
    val verifiedDurationUs: Long,
    val expectedDurationUs: Long,
    val verifiedFps: Float,
    val avTimingDeltaUs: Long,
    val fileSizeBytes: Long,
    val backendUsed: String,
    val detail: String,
)

data class ExportProgressSnapshot(
    val sessionId: String = "",
    val phase: ExportPhase = ExportPhase.IDLE,
    val progressFraction: Float = 0f,
    val renderedFrames: Int = 0,
    val totalFrames: Int = 0,
    val currentTimeUs: Long = 0L,
    val durationUs: Long = 0L,
    val elapsedMs: Long = 0L,
    val outputFilePath: String? = null,
    val galleryUriString: String? = null,
    val validationReport: ExportValidationReport? = null,
    val allocatedResources: Int = 0,
    val releasedResources: Int = 0,
    val error: EngineError? = null,
)

data class ExportDiagnosticsSnapshot(
    val lastSessionId: String = "",
    val lastPhase: ExportPhase = ExportPhase.IDLE,
    val codecCapabilities: ExportCodecCapabilityReport = ExportCodecCapabilityReport(),
    val lastValidation: ExportValidationReport? = null,
    val completedExportsCount: Long = 0L,
    val cancelledExportsCount: Long = 0L,
    val failedExportsCount: Long = 0L,
    val leakedExportResources: Int = 0,
    val lastOutputPath: String? = null,
    val lastError: String? = null,
)

interface ExportCoordinatorContract {
    val progress: StateFlow<ExportProgressSnapshot>
    val diagnostics: StateFlow<ExportDiagnosticsSnapshot>

    suspend fun queryCodecCapabilities(): ExportCodecCapabilityReport

    fun validateConfiguration(
        sequence: Sequence,
        config: ExportConfig,
        availableBytes: Long,
    ): EngineResult<ExportConfig>

    fun startExport(
        document: ProjectDocument,
        config: ExportConfig,
    ): String

    fun cancelExport()

    fun resetCompletedState()

    fun release()
}

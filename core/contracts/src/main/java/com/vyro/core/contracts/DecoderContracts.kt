package com.vyro.core.contracts

import android.view.Surface
import com.vyro.core.common.EngineResult
import com.vyro.core.model.Sequence
import kotlinx.coroutines.flow.StateFlow

enum class DecoderBackendType {
    MEDIA_CODEC_SURFACE,
    MEDIA_METADATA_FALLBACK,
    MATERIAL_SYNTHETIC,
}

enum class PlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

data class DecodedStreamInfo(
    val uriString: String,
    val videoCodec: String,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationUs: Long,
    val nominalFps: Float,
    val isHardwareAccelerated: Boolean,
)

data class DecodedFrameToken(
    val clipId: String,
    val uriString: String,
    val sourcePtsUs: Long,
    val timelineUs: Long,
    val seekGeneration: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val isKeyFrame: Boolean,
    val backend: DecoderBackendType,
)

data class PlaybackDiagnosticsSnapshot(
    val timelinePositionUs: Long = 0L,
    val audioClockUs: Long? = null,
    val avDriftUs: Long = 0L,
    val maxObservedAbsDriftUs: Long = 0L,
    val withinAvTolerance: Boolean = true,
    val seekGeneration: Long = 0L,
    val droppedFrames: Long = 0L,
    val rejectedStaleFrames: Long = 0L,
    val decodedFrames: Long = 0L,
    val queueDepth: Int = 0,
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    val surfaceAttached: Boolean = false,
    val activeDecoderBackend: DecoderBackendType = DecoderBackendType.MATERIAL_SYNTHETIC,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val lastError: String? = null,
) {
    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 3
        const val AV_SYNC_TOLERANCE_US = 45_000L
    }
}

interface VideoFrameDecoderContract {
    val backendType: DecoderBackendType

    fun openSource(uriString: String): EngineResult<DecodedStreamInfo>

    fun attachSurface(surface: Surface?, width: Int, height: Int)

    fun decodeFrameAt(
        clipId: String,
        uriString: String,
        sourcePtsUs: Long,
        timelineUs: Long,
        seekGeneration: Long,
        isCurrentGeneration: (Long) -> Boolean,
        targetWidth: Int = 720,
        targetHeight: Int = 720,
    ): EngineResult<DecodedFrameToken>

    fun releaseSource()

    fun release()
}

interface PlaybackControllerContract {
    val telemetry: StateFlow<PlaybackDiagnosticsSnapshot>

    fun updateSequence(sequence: Sequence)

    fun attachPreviewSurface(surface: Surface, width: Int, height: Int)

    fun detachPreviewSurface(surface: Surface)

    fun seekTo(timelineUs: Long, frameAccurate: Boolean = true): Long

    fun play()

    fun pause()

    fun stepFrame(forward: Boolean): Long

    fun advanceFrameTick(nowNanos: Long = System.nanoTime())

    fun reportAudioClockUs(audioTimeUs: Long)

    fun release()
}

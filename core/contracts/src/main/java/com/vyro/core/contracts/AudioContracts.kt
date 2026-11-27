package com.vyro.core.contracts

import com.vyro.core.common.EngineResult
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.MediaKind
import com.vyro.core.model.Sequence
import com.vyro.core.model.TrackType
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

enum class AudioBackendType {
    AUDIOTRACK_LOW_LATENCY,
    SYNTHETIC_CLOCK_FALLBACK,
}

data class AudioStreamFormat(
    val sampleRateHz: Int = CANONICAL_SAMPLE_RATE_HZ,
    val channelCount: Int = CANONICAL_CHANNEL_COUNT,
) {
    companion object {
        const val CANONICAL_SAMPLE_RATE_HZ = 48_000
        const val CANONICAL_CHANNEL_COUNT = 2
        val CANONICAL = AudioStreamFormat(CANONICAL_SAMPLE_RATE_HZ, CANONICAL_CHANNEL_COUNT)
    }
}

/**
 * Interleaved float PCM buffer normalized to [-1f, 1f].
 */
data class PcmAudioBuffer(
    val sampleRateHz: Int,
    val channelCount: Int,
    val samples: FloatArray,
    val startTimeUs: Long = 0L,
) {
    val frameCount: Int
        get() = if (channelCount <= 0) 0 else samples.size / channelCount

    val durationUs: Long
        get() = if (sampleRateHz <= 0) 0L else (frameCount * 1_000_000L) / sampleRateHz

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmAudioBuffer) return false
        return sampleRateHz == other.sampleRateHz &&
            channelCount == other.channelCount &&
            startTimeUs == other.startTimeUs &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sampleRateHz
        result = 31 * result + channelCount
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + startTimeUs.hashCode()
        return result
    }
}

data class WaveformProfile(
    val clipId: String,
    val peaks: List<Float>,
    val peakRms: Float,
    val durationUs: Long,
)

data class AudioDeviceCapabilities(
    val nativeOutputSampleRateHz: Int = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
    val framesPerBuffer: Int = 256,
    val lowLatencySupported: Boolean = false,
    val proAudioSupported: Boolean = false,
    val outputDeviceNames: List<String> = emptyList(),
    val oboeEvaluationNote: String =
        "Platform AudioTrack(PERFORMANCE_MODE_LOW_LATENCY) selected for universal Android 11+ (API 30+) builds without NDK Oboe C++ toolchain dependency.",
)

data class AudioDiagnosticsSnapshot(
    val backendType: AudioBackendType = AudioBackendType.SYNTHETIC_CLOCK_FALLBACK,
    val sampleRateHz: Int = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
    val channelCount: Int = AudioStreamFormat.CANONICAL_CHANNEL_COUNT,
    val framesPerBuffer: Int = 256,
    val audioClockUs: Long = 0L,
    val activeMixedClipCount: Int = 0,
    val underrunCount: Int = 0,
    val softwareStarvationCount: Long = 0L,
    val peakLevelLinear: Float = 0f,
    val rmsLevelLinear: Float = 0f,
    val isPlaying: Boolean = false,
    val deviceCapabilities: AudioDeviceCapabilities = AudioDeviceCapabilities(),
    val lastError: String? = null,
)

/**
 * Pure PCM channel conversion and linear sample-rate resampler targeting canonical 48kHz stereo.
 */
object PcmAudioResampler {

    fun convertToCanonical(buffer: PcmAudioBuffer): PcmAudioBuffer {
        if (buffer.channelCount <= 0 || buffer.sampleRateHz <= 0 || buffer.samples.isEmpty()) {
            return PcmAudioBuffer(
                sampleRateHz = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
                channelCount = AudioStreamFormat.CANONICAL_CHANNEL_COUNT,
                samples = FloatArray(0),
                startTimeUs = buffer.startTimeUs,
            )
        }
        val stereo = toStereo(buffer)
        return resample(stereo, AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ)
    }

    fun toStereo(buffer: PcmAudioBuffer): PcmAudioBuffer {
        val frames = buffer.frameCount
        if (buffer.channelCount == 2) return buffer
        val out = FloatArray(frames * 2)
        when (buffer.channelCount) {
            1 -> {
                for (i in 0 until frames) {
                    val v = buffer.samples[i]
                    out[i * 2] = v
                    out[i * 2 + 1] = v
                }
            }
            else -> {
                val ch = buffer.channelCount.coerceAtLeast(1)
                for (i in 0 until frames) {
                    val base = i * ch
                    out[i * 2] = buffer.samples[base]
                    out[i * 2 + 1] = buffer.samples[base + 1]
                }
            }
        }
        return buffer.copy(channelCount = 2, samples = out)
    }

    fun resample(stereoBuffer: PcmAudioBuffer, targetSampleRateHz: Int): PcmAudioBuffer {
        val normalized = if (stereoBuffer.channelCount == 2) stereoBuffer else toStereo(stereoBuffer)
        val inRate = normalized.sampleRateHz.coerceAtLeast(1)
        if (inRate == targetSampleRateHz || normalized.frameCount == 0) {
            return normalized.copy(sampleRateHz = targetSampleRateHz)
        }
        val inFrames = normalized.frameCount
        val ratio = targetSampleRateHz.toDouble() / inRate.toDouble()
        val outFrames = max(1, (inFrames * ratio).roundToInt())
        val out = FloatArray(outFrames * 2)
        val step = inRate.toDouble() / targetSampleRateHz.toDouble()

        for (i in 0 until outFrames) {
            val srcPos = i * step
            val idx0 = srcPos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inFrames - 1)
            val frac = (srcPos - idx0).toFloat()

            val l0 = normalized.samples[idx0 * 2]
            val r0 = normalized.samples[idx0 * 2 + 1]
            val l1 = normalized.samples[idx1 * 2]
            val r1 = normalized.samples[idx1 * 2 + 1]

            out[i * 2] = l0 + (l1 - l0) * frac
            out[i * 2 + 1] = r0 + (r1 - r0) * frac
        }
        return PcmAudioBuffer(
            sampleRateHz = targetSampleRateHz,
            channelCount = 2,
            samples = out,
            startTimeUs = normalized.startTimeUs,
        )
    }

    fun pcm16ToFloatArray(pcm16: ShortArray): FloatArray {
        val out = FloatArray(pcm16.size)
        for (i in pcm16.indices) {
            out[i] = (pcm16[i] / 32768.0f).coerceIn(-1f, 1f)
        }
        return out
    }

    fun floatArrayToPcm16(floats: FloatArray): ShortArray {
        val out = ShortArray(floats.size)
        for (i in floats.indices) {
            val clamped = floats[i].coerceIn(-1f, 1f)
            out[i] = (clamped * 32767.0f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}

/**
 * Provides decoded or synthesized audio PCM segments for timeline clips.
 */
interface AudioSourceContract {
    fun decodeSegment(
        clip: Clip,
        sourceStartUs: Long,
        durationUs: Long,
        targetSampleRateHz: Int = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
    ): EngineResult<PcmAudioBuffer>

    fun extractWaveform(clip: Clip, bucketCount: Int = 48): WaveformProfile

    fun release()
}

interface AudioPlaybackEngineContract {
    val diagnostics: StateFlow<AudioDiagnosticsSnapshot>

    fun updateSequence(sequence: Sequence)

    fun start(timelinePositionUs: Long)

    fun pause(): Long

    fun seek(timelinePositionUs: Long)

    fun stop()

    /**
     * Advances and outputs one audio buffer slice synchronously (primarily for deterministic JVM verification).
     * Returns updated audio clock in microseconds.
     */
    fun stepMixWindowForTest(frameCount: Int = 1024): Long

    fun release()
}

/**
 * Deterministic multi-track audio mixer applying gain dB, equal-power stereo pan,
 * fade-in/fade-out envelopes, track mutes, and soft limiting.
 */
object AudioMixEngine {

    private val SQRT_2 = sqrt(2.0)

    data class MixResult(
        val buffer: PcmAudioBuffer,
        val activeClipCount: Int,
        val peakLinear: Float,
        val rmsLinear: Float,
    )

    fun dbToLinear(gainDb: Float): Float {
        if (gainDb <= -60.0f) return 0f
        return 10.0.pow((gainDb.coerceIn(-60.0f, 12.0f) / 20.0f).toDouble()).toFloat()
    }

    /**
     * Equal-power stereo pan gains in [pan] = -1 (hard left) .. 0 (center unity) .. +1 (hard right).
     */
    fun panGains(pan: Float): Pair<Float, Float> {
        val clamped = pan.coerceIn(-1.0f, 1.0f).toDouble()
        val angle = (clamped + 1.0) * (PI / 4.0)
        val left = (cos(angle) * SQRT_2).toFloat()
        val right = (sin(angle) * SQRT_2).toFloat()
        return left to right
    }

    fun fadeMultiplier(
        clipElapsedUs: Long,
        clipRemainingUs: Long,
        fadeInUs: Long,
        fadeOutUs: Long,
    ): Float {
        val inGain = if (fadeInUs > 0L) {
            (clipElapsedUs.toFloat() / fadeInUs.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        val outGain = if (fadeOutUs > 0L) {
            (clipRemainingUs.toFloat() / fadeOutUs.toFloat()).coerceIn(0f, 1f)
        } else {
            1f
        }
        return min(inGain, outGain)
    }

    fun isAudioBearingClip(trackType: TrackType, clip: Clip): Boolean {
        return when (trackType) {
            TrackType.AUDIO -> true
            TrackType.VIDEO -> clip.mediaSource.mediaKind == MediaKind.VIDEO || clip.audioSpec != null
            TrackType.TEXT -> false
        }
    }

    fun mixWindow(
        sequence: Sequence,
        windowStartUs: Long,
        frameCount: Int,
        sampleRateHz: Int = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
        sourceContract: AudioSourceContract,
    ): MixResult {
        val safeFrames = frameCount.coerceAtLeast(1)
        val windowDurationUs = (safeFrames * 1_000_000L) / sampleRateHz.coerceAtLeast(1)
        val windowEndUs = windowStartUs + windowDurationUs
        val mixAccum = FloatArray(safeFrames * 2)
        var activeCount = 0

        for (track in sequence.tracks) {
            if (track.isMuted) continue
            for (clip in track.clips) {
                if (!isAudioBearingClip(track.type, clip)) continue
                val overlapStartUs = max(windowStartUs, clip.timing.timelineStartUs)
                val overlapEndUs = min(windowEndUs, clip.timing.timelineEndUs)
                if (overlapEndUs <= overlapStartUs) continue

                val clipSourceStartUs = clip.timing.timelineToSourceUs(overlapStartUs) ?: continue
                val overlapDurUs = overlapEndUs - overlapStartUs
                val decodedRes = sourceContract.decodeSegment(
                    clip = clip,
                    sourceStartUs = clipSourceStartUs,
                    durationUs = overlapDurUs,
                    targetSampleRateHz = sampleRateHz,
                )
                val canonical = when (decodedRes) {
                    is EngineResult.Success -> PcmAudioResampler.resample(decodedRes.value, sampleRateHz)
                    is EngineResult.Failure -> continue
                }
                if (canonical.frameCount == 0) continue

                activeCount++
                val spec = clip.audioSpec ?: AudioClipSpec()
                val baseGain = dbToLinear(spec.gainDb)
                val (panL, panR) = panGains(spec.pan)

                val startFrameOffset = (((overlapStartUs - windowStartUs) * sampleRateHz) / 1_000_000L)
                    .toInt()
                    .coerceIn(0, safeFrames)
                val framesToWrite = min(canonical.frameCount, safeFrames - startFrameOffset)

                for (i in 0 until framesToWrite) {
                    val sampleTimeUs = overlapStartUs + ((i * 1_000_000L) / sampleRateHz)
                    val elapsedUs = (sampleTimeUs - clip.timing.timelineStartUs).coerceAtLeast(0L)
                    val remainingUs = (clip.timing.timelineEndUs - sampleTimeUs).coerceAtLeast(0L)
                    val env = fadeMultiplier(elapsedUs, remainingUs, spec.fadeInUs, spec.fadeOutUs)
                    val totalGainL = baseGain * panL * env
                    val totalGainR = baseGain * panR * env

                    val outIdx = (startFrameOffset + i) * 2
                    mixAccum[outIdx] += canonical.samples[i * 2] * totalGainL
                    mixAccum[outIdx + 1] += canonical.samples[i * 2 + 1] * totalGainR
                }
            }
        }

        // Soft limiter + peak/RMS telemetry
        var peak = 0f
        var sumSq = 0.0
        for (i in mixAccum.indices) {
            val raw = mixAccum[i]
            val limited = if (abs(raw) > 0.95f) {
                tanh(raw.toDouble()).toFloat()
            } else {
                raw
            }
            mixAccum[i] = limited
            val a = abs(limited)
            if (a > peak) peak = a
            sumSq += limited * limited
        }
        val rms = if (mixAccum.isEmpty()) 0f else sqrt(sumSq / mixAccum.size.toDouble()).toFloat()

        return MixResult(
            buffer = PcmAudioBuffer(
                sampleRateHz = sampleRateHz,
                channelCount = 2,
                samples = mixAccum,
                startTimeUs = windowStartUs,
            ),
            activeClipCount = activeCount,
            peakLinear = peak,
            rmsLinear = rms,
        )
    }
}

/**
 * Computes normalized peak buckets for UI waveform strips & audio diagnostics.
 */
object WaveformExtractor {

    fun fromPcmBuffer(
        clipId: String,
        buffer: PcmAudioBuffer,
        bucketCount: Int = 48,
    ): WaveformProfile {
        val safeBuckets = bucketCount.coerceIn(8, 256)
        if (buffer.samples.isEmpty()) {
            return WaveformProfile(
                clipId = clipId,
                peaks = List(safeBuckets) { 0f },
                peakRms = 0f,
                durationUs = buffer.durationUs,
            )
        }
        val ch = buffer.channelCount.coerceAtLeast(1)
        val totalFrames = buffer.frameCount.coerceAtLeast(1)
        val peaks = FloatArray(safeBuckets)
        var sumSq = 0.0

        for (b in 0 until safeBuckets) {
            val startFrame = (b * totalFrames) / safeBuckets
            val endFrame = max(startFrame + 1, ((b + 1) * totalFrames) / safeBuckets).coerceAtMost(totalFrames)
            var bucketMax = 0f
            for (f in startFrame until endFrame) {
                var monoSum = 0f
                for (c in 0 until ch) {
                    val sample = abs(buffer.samples[f * ch + c])
                    monoSum += sample
                }
                val avg = monoSum / ch.toFloat()
                if (avg > bucketMax) bucketMax = avg
            }
            peaks[b] = bucketMax.coerceIn(0f, 1f)
        }
        for (sample in buffer.samples) {
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / buffer.samples.size.toDouble()).toFloat().coerceIn(0f, 1f)
        return WaveformProfile(
            clipId = clipId,
            peaks = peaks.toList(),
            peakRms = rms,
            durationUs = buffer.durationUs,
        )
    }

    /**
     * Generates deterministic studio beat PCM for bundled audio clips that do not carry a file URI.
     */
    fun synthesizeStudioBeatPcm(
        seedKey: String,
        startUs: Long,
        durationUs: Long,
        sampleRateHz: Int = AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ,
    ): PcmAudioBuffer {
        val clampedDurUs = durationUs.coerceIn(1_000L, 10_000_000L)
        val frames = max(1, ((clampedDurUs * sampleRateHz) / 1_000_000L).toInt())
        val out = FloatArray(frames * 2)
        val baseFreq = 110.0 + (abs(seedKey.hashCode()) % 5) * 27.5
        val beatPeriodSec = 0.5 // 120 BPM

        for (i in 0 until frames) {
            val tSec = (startUs.toDouble() / 1_000_000.0) + (i.toDouble() / sampleRateHz.toDouble())
            val beatPhase = (tSec % beatPeriodSec) / beatPeriodSec
            val kickEnv = kotlin.math.exp(-beatPhase * 9.0)
            val kick = sin(2.0 * PI * (55.0 + 90.0 * kotlin.math.exp(-beatPhase * 18.0)) * tSec) * kickEnv * 0.55
            val pad = sin(2.0 * PI * baseFreq * tSec) * 0.18 +
                sin(2.0 * PI * (baseFreq * 1.5) * tSec) * 0.12
            val stereoSpread = sin(2.0 * PI * 0.75 * tSec) * 0.08
            val left = (kick + pad + stereoSpread).toFloat().coerceIn(-0.95f, 0.95f)
            val right = (kick + pad - stereoSpread).toFloat().coerceIn(-0.95f, 0.95f)
            out[i * 2] = left
            out[i * 2 + 1] = right
        }
        return PcmAudioBuffer(
            sampleRateHz = sampleRateHz,
            channelCount = 2,
            samples = out,
            startTimeUs = startUs,
        )
    }
}

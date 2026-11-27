package com.vyro.core.contracts

import com.vyro.core.common.EngineResult
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioMixEngineTest {

    private class ConstantToneSource(
        private val sampleValue: Float = 0.25f,
    ) : AudioSourceContract {
        override fun decodeSegment(
            clip: Clip,
            sourceStartUs: Long,
            durationUs: Long,
            targetSampleRateHz: Int,
        ): EngineResult<PcmAudioBuffer> {
            val frames = ((durationUs * targetSampleRateHz) / 1_000_000L).toInt().coerceAtLeast(1)
            val samples = FloatArray(frames * 2) { sampleValue }
            return EngineResult.Success(
                PcmAudioBuffer(
                    sampleRateHz = targetSampleRateHz,
                    channelCount = 2,
                    samples = samples,
                    startTimeUs = sourceStartUs,
                ),
            )
        }

        override fun extractWaveform(clip: Clip, bucketCount: Int): WaveformProfile {
            return WaveformExtractor.fromPcmBuffer(
                clip.id,
                PcmAudioBuffer(48_000, 2, FloatArray(200) { sampleValue }),
                bucketCount,
            )
        }

        override fun release() = Unit
    }

    @Test
    fun `resampler converts 44100hz mono to canonical 48000hz stereo and preserves pcm16 roundtrip`() {
        val mono44k = PcmAudioBuffer(
            sampleRateHz = 44_100,
            channelCount = 1,
            samples = FloatArray(441) { i -> (i / 441f) * 0.5f },
        )
        val canonical = PcmAudioResampler.convertToCanonical(mono44k)
        assertEquals(48_000, canonical.sampleRateHz)
        assertEquals(2, canonical.channelCount)
        assertTrue(canonical.frameCount in 478..482)
        // Left and right channels must be identical from mono upmix
        for (f in 0 until canonical.frameCount) {
            assertEquals(canonical.samples[f * 2], canonical.samples[f * 2 + 1], 0.0001f)
        }

        val pcm16 = PcmAudioResampler.floatArrayToPcm16(floatArrayOf(-0.5f, 0.0f, 0.5f))
        val back = PcmAudioResampler.pcm16ToFloatArray(pcm16)
        assertEquals(-0.5f, back[0], 0.001f)
        assertEquals(0.0f, back[1], 0.001f)
        assertEquals(0.5f, back[2], 0.001f)
    }

    @Test
    fun `gain pan and fade math applies accurate equal power panning and envelopes`() {
        assertEquals(1.0f, AudioMixEngine.dbToLinear(0f), 0.001f)
        assertEquals(0.501f, AudioMixEngine.dbToLinear(-6f), 0.01f)
        assertEquals(0.0f, AudioMixEngine.dbToLinear(-60f), 0.0001f)

        val (cL, cR) = AudioMixEngine.panGains(0.0f)
        assertEquals(1.0f, cL, 0.01f)
        assertEquals(1.0f, cR, 0.01f)

        val (lL, lR) = AudioMixEngine.panGains(-1.0f)
        assertTrue(lL > 1.3f)
        assertEquals(0.0f, lR, 0.001f)

        val (rL, rR) = AudioMixEngine.panGains(1.0f)
        assertEquals(0.0f, rL, 0.001f)
        assertTrue(rR > 1.3f)

        // Halfway into 500ms fade-in -> 0.5x
        assertEquals(0.5f, AudioMixEngine.fadeMultiplier(250_000L, 1_000_000L, 500_000L, 0L), 0.001f)
        // Halfway through 400ms fade-out -> 0.5x
        assertEquals(0.5f, AudioMixEngine.fadeMultiplier(1_000_000L, 200_000L, 0L, 400_000L), 0.001f)
    }

    @Test
    fun `mixWindow sums multi-track clips excludes muted track and generates waveforms`() {
        val clip1 = Clip(
            id = "aud_1",
            trackId = "t_aud_1",
            label = "Bed",
            timing = ClipTiming(0L, 0L, 2_000_000L),
            audioSpec = AudioClipSpec(gainDb = 0f, pan = 0f),
        )
        val clip2 = Clip(
            id = "aud_2",
            trackId = "t_aud_2",
            label = "Lead",
            timing = ClipTiming(500_000L, 0L, 1_500_000L),
            audioSpec = AudioClipSpec(gainDb = 0f, pan = 1.0f),
        )
        val mutedClip = Clip(
            id = "aud_muted",
            trackId = "t_aud_muted",
            label = "Muted",
            timing = ClipTiming(0L, 0L, 2_000_000L),
            audioSpec = AudioClipSpec(gainDb = 0f),
        )
        val sequence = Sequence(
            id = "seq_mix",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track("t_aud_1", "Audio 1", TrackType.AUDIO, zIndex = 1, clips = listOf(clip1)),
                Track("t_aud_2", "Audio 2", TrackType.AUDIO, zIndex = 2, clips = listOf(clip2)),
                Track("t_aud_muted", "Audio Muted", TrackType.AUDIO, zIndex = 3, isMuted = true, clips = listOf(mutedClip)),
            ),
        )
        val src = ConstantToneSource(0.25f)

        // At 100ms only clip1 is active
        val winSingle = AudioMixEngine.mixWindow(sequence, 100_000L, 480, 48_000, src)
        assertEquals(1, winSingle.activeClipCount)
        assertEquals(0.25f, winSingle.buffer.samples[0], 0.01f)

        // At 600ms clip1 + clip2 (panned right) overlap -> right channel > left channel
        val winOverlap = AudioMixEngine.mixWindow(sequence, 600_000L, 480, 48_000, src)
        assertEquals(2, winOverlap.activeClipCount)
        assertTrue(winOverlap.buffer.samples[1] > winOverlap.buffer.samples[0] + 0.1f)
        assertTrue(winOverlap.rmsLinear > winSingle.rmsLinear)

        val synth = WaveformExtractor.synthesizeStudioBeatPcm("beat_1", 0L, 1_000_000L, 8_000)
        val wf = WaveformExtractor.fromPcmBuffer("aud_1", synth, bucketCount = 32)
        assertEquals(32, wf.peaks.size)
        assertTrue(wf.peakRms > 0.05f)
        assertTrue(wf.peaks.any { abs(it) > 0.1f })
    }

    @Test
    fun `timeline evaluator clamps audio spec updates`() {
        val clip = Clip(
            id = "aud_1",
            trackId = "t_aud",
            label = "Beat",
            timing = ClipTiming(0L, 0L, 2_000_000L),
            audioSpec = AudioClipSpec(),
        )
        val seq = Sequence(
            id = "seq",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(Track("t_aud", "Audio", TrackType.AUDIO, 1, clips = listOf(clip))),
        )
        val res = TimelineEvaluator.apply(
            seq,
            EditCommand.UpdateAudioSpec("aud_1", AudioClipSpec(gainDb = 24f, pan = -3f, fadeInUs = -50L, fadeOutUs = 400_000L)),
        )
        assertTrue(res is EngineResult.Success)
        val updated = (res as EngineResult.Success).value.tracks.first().clips.first().audioSpec!!
        assertEquals(12.0f, updated.gainDb, 0.001f)
        assertEquals(-1.0f, updated.pan, 0.001f)
        assertEquals(0L, updated.fadeInUs)
        assertEquals(400_000L, updated.fadeOutUs)
    }
}

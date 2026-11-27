package com.vyro.platform.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.PlaybackState
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAudioPlaybackEngineTest {

    private fun immediateDispatchers(): DispatchersProvider = object : DispatchersProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun testAudioSequence(): Sequence {
        return Sequence(
            id = "seq_audio_p6",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "track_video",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 0,
                    clips = listOf(
                        Clip(
                            id = "clip_v",
                            trackId = "track_video",
                            label = "White Canvas",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                        ),
                    ),
                ),
                Track(
                    id = "track_audio",
                    name = "Audio",
                    type = TrackType.AUDIO,
                    zIndex = 1,
                    clips = listOf(
                        Clip(
                            id = "clip_a1",
                            trackId = "track_audio",
                            label = "Studio Beat 1",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                            audioSpec = AudioClipSpec(gainDb = -2f, pan = 0.25f, fadeInUs = 250_000L),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `audio playback engine measures drift within tolerance and stays stable across pause seek stop cycles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nowNanos = AtomicLong(1_000_000_000L)
        val audioSource = AndroidMediaCodecAudioDecoder(context)
        var coordinator: AndroidPlaybackCoordinator? = null
        val audioEngine = AndroidAudioPlaybackEngine(
            context = context,
            audioSource = audioSource,
            dispatchers = immediateDispatchers(),
            onAudioClockAdvanced = { clockUs -> coordinator?.reportAudioClockUs(clockUs) },
            autoScheduleAudioThread = false,
        )
        coordinator = AndroidPlaybackCoordinator(
            decoder = AndroidMediaCodecDecoder(context),
            dispatchers = immediateDispatchers(),
            nanoTimeProvider = { nowNanos.get() },
            autoSchedulePlayback = false,
            audioEngine = audioEngine,
        )

        val seq = testAudioSequence()
        coordinator.updateSequence(seq)

        // Capabilities & Oboe evaluation telemetry populated cleanly
        val initialAudio = audioEngine.diagnostics.value
        assertEquals(48_000, initialAudio.sampleRateHz)
        assertEquals(2, initialAudio.channelCount)
        assertTrue(initialAudio.deviceCapabilities.oboeEvaluationNote.contains("AudioTrack"))

        // Start playback & mix multiple windows in step with video clock
        coordinator.play()
        assertTrue(audioEngine.diagnostics.value.isPlaying)
        assertEquals(PlaybackState.PLAYING, coordinator.telemetry.value.playbackState)

        for (i in 1..10) {
            val sliceFrames = 1024
            val sliceNs = (sliceFrames * 1_000_000_000L) / 48_000L
            nowNanos.addAndGet(sliceNs)
            coordinator.advanceFrameTick(nowNanos.get())
            audioEngine.stepMixWindowForTest(sliceFrames)
        }

        val pbSnap = coordinator.telemetry.value
        val audSnap = audioEngine.diagnostics.value
        assertEquals(1, audSnap.activeMixedClipCount)
        assertTrue(audSnap.rmsLevelLinear > 0f)
        assertTrue(
            "Expected AV drift <= 45ms, got ${pbSnap.avDriftUs}us",
            abs(pbSnap.avDriftUs) <= PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US,
        )
        assertTrue(pbSnap.withinAvTolerance)

        // 60-cycle pause / seek / play / stop stability test
        for (cycle in 1..60) {
            coordinator.pause()
            assertFalse(audioEngine.diagnostics.value.isPlaying)
            coordinator.seekTo((cycle * 40_000L) % 3_500_000L, frameAccurate = true)
            coordinator.play()
            assertTrue(audioEngine.diagnostics.value.isPlaying)
            audioEngine.stepMixWindowForTest(512)
        }
        audioEngine.stop()
        assertFalse(audioEngine.diagnostics.value.isPlaying)
        assertEquals(0L, audioEngine.diagnostics.value.audioClockUs)

        coordinator.release()
    }
}

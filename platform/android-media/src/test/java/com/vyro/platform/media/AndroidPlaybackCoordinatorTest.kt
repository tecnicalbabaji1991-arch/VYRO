package com.vyro.platform.media

import android.graphics.SurfaceTexture
import android.view.Surface
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.DecodedFrameToken
import com.vyro.core.contracts.DecodedStreamInfo
import com.vyro.core.contracts.DecoderBackendType
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.PlaybackState
import com.vyro.core.contracts.VideoFrameDecoderContract
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidPlaybackCoordinatorTest {

    private class FakeSurfaceDecoder : VideoFrameDecoderContract {
        override val backendType: DecoderBackendType = DecoderBackendType.MEDIA_CODEC_SURFACE

        @Volatile
        var currentSurface: Surface? = null
        val surfaceAttachCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        val renderedGenerations = mutableListOf<Long>()
        val lock = Any()

        override fun openSource(uriString: String): EngineResult<DecodedStreamInfo> {
            return EngineResult.Success(
                DecodedStreamInfo(
                    uriString = uriString,
                    videoCodec = "video/avc",
                    width = 1920,
                    height = 1080,
                    rotationDegrees = 0,
                    durationUs = 8_000_000L,
                    nominalFps = 30f,
                    isHardwareAccelerated = true,
                ),
            )
        }

        override fun attachSurface(surface: Surface?, width: Int, height: Int) {
            currentSurface = surface
            if (surface != null) {
                surfaceAttachCount.incrementAndGet()
            }
        }

        override fun decodeFrameAt(
            clipId: String,
            uriString: String,
            sourcePtsUs: Long,
            timelineUs: Long,
            seekGeneration: Long,
            isCurrentGeneration: (Long) -> Boolean,
            targetWidth: Int,
            targetHeight: Int,
        ): EngineResult<DecodedFrameToken> {
            if (!isCurrentGeneration(seekGeneration)) {
                return EngineResult.Success(
                    DecodedFrameToken(
                        clipId = clipId,
                        uriString = uriString,
                        sourcePtsUs = sourcePtsUs,
                        timelineUs = timelineUs,
                        seekGeneration = seekGeneration,
                        width = 1920,
                        height = 1080,
                        rotationDegrees = 0,
                        isKeyFrame = true,
                        backend = backendType,
                    ),
                )
            }
            synchronized(lock) {
                if (isCurrentGeneration(seekGeneration)) {
                    renderedGenerations.add(seekGeneration)
                }
            }
            return EngineResult.Success(
                DecodedFrameToken(
                    clipId = clipId,
                    uriString = uriString,
                    sourcePtsUs = sourcePtsUs,
                    timelineUs = timelineUs,
                    seekGeneration = seekGeneration,
                    width = 1920,
                    height = 1080,
                    rotationDegrees = 0,
                    isKeyFrame = true,
                    backend = backendType,
                ),
            )
        }

        override fun releaseSource() = Unit

        override fun release() {
            currentSurface = null
            releaseCount.incrementAndGet()
        }
    }

    private fun immediateDispatchers(): DispatchersProvider = object : DispatchersProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun testVideoSequence(): Sequence {
        return Sequence(
            id = "seq_phase4",
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
                            id = "clip_v1",
                            trackId = "track_video",
                            label = "Main",
                            timing = ClipTiming(0L, 0L, 6_000_000L),
                            mediaSource = MediaSourceRef(
                                assetId = "asset_v1",
                                mediaKind = MediaKind.VIDEO,
                                uriString = "content://media/external/video/media/42",
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `seek cancellation rejects stale frames and 100 cycle play pause seek stress test passes`() {
        val nowNanos = AtomicLong(10_000_000_000L)
        val decoder = FakeSurfaceDecoder()
        val coordinator = AndroidPlaybackCoordinator(
            decoder = decoder,
            dispatchers = immediateDispatchers(),
            nanoTimeProvider = { nowNanos.get() },
            autoSchedulePlayback = false,
        )
        coordinator.updateSequence(testVideoSequence())

        val tex = SurfaceTexture(1)
        val surface = Surface(tex)
        coordinator.attachPreviewSurface(surface, 1080, 1920)

        // Burst of 50 rapid seeks -> every seek cancels older generation
        var lastGen = 0L
        for (i in 1..50) {
            lastGen = coordinator.seekTo(i * 100_000L, frameAccurate = true)
        }
        assertEquals(lastGen, coordinator.telemetry.value.seekGeneration)
        assertTrue(coordinator.telemetry.value.queueDepth <= PlaybackDiagnosticsSnapshot.DEFAULT_QUEUE_CAPACITY)

        // 100-cycle play -> advance -> step -> pause -> seek stress test
        for (cycle in 1..100) {
            coordinator.play()
            assertEquals(PlaybackState.PLAYING, coordinator.telemetry.value.playbackState)
            nowNanos.addAndGet(33_333_000L)
            coordinator.advanceFrameTick(nowNanos.get())
            coordinator.stepFrame(forward = cycle % 2 == 0)
            assertEquals(PlaybackState.PAUSED, coordinator.telemetry.value.playbackState)
            coordinator.seekTo(((cycle * 50_000L) % 5_500_000L), frameAccurate = true)
            assertTrue(coordinator.telemetry.value.queueDepth <= PlaybackDiagnosticsSnapshot.DEFAULT_QUEUE_CAPACITY)
        }

        coordinator.release()
        surface.release()
        tex.release()
        assertEquals(1, decoder.releaseCount.get())
    }

    @Test
    fun `surface recreation attaches replacement before detaching previous without dropping attachment`() {
        val decoder = FakeSurfaceDecoder()
        val coordinator = AndroidPlaybackCoordinator(
            decoder = decoder,
            dispatchers = immediateDispatchers(),
            autoSchedulePlayback = false,
        )
        coordinator.updateSequence(testVideoSequence())

        val tex1 = SurfaceTexture(10)
        val surface1 = Surface(tex1)
        val tex2 = SurfaceTexture(11)
        val surface2 = Surface(tex2)

        coordinator.attachPreviewSurface(surface1, 720, 1280)
        assertTrue(coordinator.telemetry.value.surfaceAttached)
        assertEquals(surface1, decoder.currentSurface)

        // Swap directly to surface2 before Android calls surfaceDestroyed on surface1
        coordinator.attachPreviewSurface(surface2, 1080, 1920)
        assertEquals(surface2, decoder.currentSurface)

        // Stale surface1 destroy callback MUST NOT detach active surface2
        coordinator.detachPreviewSurface(surface1)
        assertTrue(coordinator.telemetry.value.surfaceAttached)
        assertEquals(surface2, decoder.currentSurface)

        // Destroying surface2 cleanly detaches
        coordinator.detachPreviewSurface(surface2)
        assertFalse(coordinator.telemetry.value.surfaceAttached)
        assertEquals(null, decoder.currentSurface)

        coordinator.release()
        surface1.release()
        surface2.release()
        tex1.release()
        tex2.release()
    }

    @Test
    fun `audio clock synchronization measures and holds drift within 45ms tolerance`() {
        val nowNanos = AtomicLong(1_000_000_000L)
        val decoder = FakeSurfaceDecoder()
        val coordinator = AndroidPlaybackCoordinator(
            decoder = decoder,
            dispatchers = immediateDispatchers(),
            nanoTimeProvider = { nowNanos.get() },
            autoSchedulePlayback = false,
        )
        coordinator.updateSequence(testVideoSequence())
        coordinator.play()

        nowNanos.addAndGet(1_000_000_000L) // +1.0s
        coordinator.advanceFrameTick(nowNanos.get())
        // Report audio clock slightly behind by 80ms (> 45ms tolerance)
        coordinator.reportAudioClockUs(920_000L)

        val snapshot = coordinator.telemetry.value
        assertTrue(
            "Expected AV drift <= 45ms after sync correction, got ${snapshot.avDriftUs}us",
            abs(snapshot.avDriftUs) <= PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US,
        )
        assertTrue(snapshot.withinAvTolerance)
        assertEquals(920_000L, snapshot.audioClockUs)

        coordinator.release()
    }
}

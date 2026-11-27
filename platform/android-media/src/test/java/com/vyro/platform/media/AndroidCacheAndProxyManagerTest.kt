package com.vyro.platform.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.CacheBudgetConfig
import com.vyro.core.contracts.CommandHistory
import com.vyro.core.contracts.DecodedFrameToken
import com.vyro.core.contracts.DecodedStreamInfo
import com.vyro.core.contracts.DecoderBackendType
import com.vyro.core.contracts.EditCommand
import com.vyro.core.contracts.FrameCacheEntry
import com.vyro.core.contracts.FrameCacheKey
import com.vyro.core.contracts.MemoryPressureLevel
import com.vyro.core.contracts.ThumbnailCacheEntry
import com.vyro.core.contracts.ThumbnailCacheKey
import com.vyro.core.contracts.TimelineEvaluator
import com.vyro.core.contracts.VideoFrameDecoderContract
import com.vyro.core.contracts.WaveformCacheEntry
import com.vyro.core.contracts.WaveformCacheKey
import com.vyro.core.contracts.WaveformProfile
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidCacheAndProxyManagerTest {

    private class RecordingUriDecoder : VideoFrameDecoderContract {
        override val backendType: DecoderBackendType = DecoderBackendType.MEDIA_CODEC_SURFACE
        val requestedUris: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun openSource(uriString: String) = EngineResult.Success(
            DecodedStreamInfo(uriString, "video/avc", 540, 960, 0, 4_000_000L, 30f, true),
        )

        override fun attachSurface(surface: android.view.Surface?, width: Int, height: Int) = Unit

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
            requestedUris.add(uriString)
            return EngineResult.Success(
                DecodedFrameToken(
                    clipId = clipId,
                    uriString = uriString,
                    sourcePtsUs = sourcePtsUs,
                    timelineUs = timelineUs,
                    seekGeneration = seekGeneration,
                    width = 540,
                    height = 960,
                    rotationDegrees = 0,
                    isKeyFrame = true,
                    backend = backendType,
                ),
            )
        }

        override fun releaseSource() = Unit
        override fun release() = Unit
    }

    private fun immediateDispatchers(): DispatchersProvider = object : DispatchersProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Test
    fun `proxy switching preserves edit state and switches decoder uri seamlessly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheMgr = AndroidCacheAndProxyManager(
            context = context,
            dispatchers = immediateDispatchers(),
            budget = CacheBudgetConfig(frameCacheMaxBytes = 128 * 1024L),
        )
        val decoder = RecordingUriDecoder()
        val coordinator = AndroidPlaybackCoordinator(
            decoder = decoder,
            dispatchers = immediateDispatchers(),
            autoSchedulePlayback = false,
            cacheManager = cacheMgr,
        )

        val originalUri = "content://media/external/video/media/99"
        val proxyFile = File(context.cacheDir, "test_asset_proxy.mp4").apply {
            writeBytes(ByteArray(128) { 1 })
        }
        cacheMgr.registerReadyProxyForTest("asset_99", originalUri, proxyFile)

        val initialSeq = Sequence(
            id = "seq_p7",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "t_v",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 0,
                    clips = listOf(
                        Clip(
                            id = "c_v1",
                            trackId = "t_v",
                            label = "Clip 99",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                            mediaSource = MediaSourceRef(
                                assetId = "asset_99",
                                mediaKind = MediaKind.VIDEO,
                                uriString = originalUri,
                            ),
                        ),
                    ),
                ),
            ),
        )

        // Apply timeline edits and verify canonical sequence + undo history remain untouched across proxy toggle
        val history = CommandHistory(initialSeq)
        val filtered = (TimelineEvaluator.apply(initialSeq, EditCommand.UpdateFilter("c_v1", ColorFilterPreset.CYBER_CYAN)) as EngineResult.Success).value
        history.commit(filtered)
        coordinator.updateSequence(filtered)

        // Original mode decodes originalUri
        cacheMgr.setProxyPlaybackEnabled(false)
        coordinator.seekTo(100_000L)
        assertEquals(originalUri, decoder.requestedUris.last())

        // Enable Proxy mode -> decoder receives proxy URI while edit state (filter + history) is preserved
        cacheMgr.setProxyPlaybackEnabled(true)
        assertTrue(cacheMgr.proxyEnabled.value)
        coordinator.seekTo(200_000L)
        val proxyUriUsed = decoder.requestedUris.last()
        assertNotEquals(originalUri, proxyUriUsed)
        assertTrue(proxyUriUsed.contains("test_asset_proxy.mp4"))
        assertEquals(ColorFilterPreset.CYBER_CYAN, history.currentSequence.tracks.first().clips.first().filterPreset)
        assertTrue(history.canUndo)

        // Disable Proxy mode -> immediately back to original URI
        cacheMgr.setProxyPlaybackEnabled(false)
        assertFalse(cacheMgr.proxyEnabled.value)
        coordinator.seekTo(300_000L)
        assertEquals(originalUri, decoder.requestedUris.last())

        proxyFile.delete()
        coordinator.release()
        cacheMgr.release()
    }

    @Test
    fun `memory pressure trims caches within retained fraction and long sessions never grow unbounded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val budget = CacheBudgetConfig(
            frameCacheMaxBytes = 40_000L,
            thumbnailCacheMaxBytes = 20_000L,
            waveformCacheMaxBytes = 8_000L,
        )
        val cacheMgr = AndroidCacheAndProxyManager(
            context = context,
            dispatchers = immediateDispatchers(),
            budget = budget,
        )

        // Long session: populate 400 frames, 200 thumbs, 100 waveforms
        for (i in 0 until 400) {
            val fk = FrameCacheKey("file:///vid.mp4", i * 33_333L, 540, 960, false)
            val tok = DecodedFrameToken("c1", "file:///vid.mp4", i * 33_333L, i * 33_333L, 1L, 540, 960, 0, true, DecoderBackendType.MEDIA_CODEC_SURFACE)
            cacheMgr.putFrame(FrameCacheEntry(fk, tok, byteSize = 2_000L))
        }
        for (i in 0 until 200) {
            val tk = ThumbnailCacheKey("file:///vid.mp4", i * 250_000L, 64, 64)
            cacheMgr.putThumbnail(ThumbnailCacheEntry(tk, IntArray(100), 10, 10, byteSize = 1_000L))
        }
        for (i in 0 until 100) {
            val wk = WaveformCacheKey("aud_$i", 0L, 1_000_000L, 32)
            cacheMgr.putWaveform(WaveformCacheEntry(wk, WaveformProfile("aud_$i", List(32) { 0.5f }, 0.5f, 1_000_000L), byteSize = 512L))
        }

        val beforeTrim = cacheMgr.diagnostics.value
        assertTrue(beforeTrim.frameCache.currentBytes <= budget.frameCacheMaxBytes)
        assertTrue(beforeTrim.thumbnailCache.currentBytes <= budget.thumbnailCacheMaxBytes)
        assertTrue(beforeTrim.waveformCache.currentBytes <= budget.waveformCacheMaxBytes)

        // Simulate Android LOW memory pressure (25% retain fraction)
        cacheMgr.onTrimMemory(MemoryPressureLevel.LOW)
        val afterLow = cacheMgr.diagnostics.value
        assertEquals(MemoryPressureLevel.LOW, afterLow.memoryPressureLevel)
        assertEquals(1, afterLow.trimEventCount)
        assertTrue(afterLow.frameCache.currentBytes <= (budget.frameCacheMaxBytes * MemoryPressureLevel.LOW.retainFraction).toLong())
        assertTrue(afterLow.thumbnailCache.currentBytes <= (budget.thumbnailCacheMaxBytes * MemoryPressureLevel.LOW.retainFraction).toLong())

        // Simulate CRITICAL memory pressure -> thumbnails cleared, frames retained <= 10%
        cacheMgr.onTrimMemory(MemoryPressureLevel.CRITICAL)
        val afterCritical = cacheMgr.diagnostics.value
        assertEquals(MemoryPressureLevel.CRITICAL, afterCritical.memoryPressureLevel)
        assertEquals(0, afterCritical.thumbnailCache.entryCount)
        assertTrue(afterCritical.frameCache.currentBytes <= (budget.frameCacheMaxBytes * MemoryPressureLevel.CRITICAL.retainFraction).toLong())

        cacheMgr.release()
    }
}

package com.vyro.core.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CacheAndSchedulerTest {

    private fun dummyToken(ptsUs: Long, proxy: Boolean) = DecodedFrameToken(
        clipId = "c1",
        uriString = if (proxy) "file:///proxy.mp4" else "file:///orig.mp4",
        sourcePtsUs = ptsUs,
        timelineUs = ptsUs,
        seekGeneration = 1L,
        width = 540,
        height = 960,
        rotationDegrees = 0,
        isKeyFrame = true,
        backend = DecoderBackendType.MEDIA_CODEC_SURFACE,
    )

    @Test
    fun `bounded lru cache enforces strict byte budget over 2000 long session insertions`() {
        val evictedKeys = AtomicInteger(0)
        val maxBytes = 64L * 1024L // 64 KB tight budget
        val cache = BoundedByteLruCache<FrameCacheKey, FrameCacheEntry>(
            name = "TestFrames",
            initialMaxBytes = maxBytes,
            weightOf = { it.byteSize },
            onEvict = { _, _ -> evictedKeys.incrementAndGet() },
        )

        // Simulate 2,000 frame inserts (2000 * 4 KB = 8 MB attempted -> must stay <= 64 KB)
        for (i in 0 until 2000) {
            val key = FrameCacheKey("file:///orig.mp4", i * 33_333L, 540, 960, false)
            cache.put(key, FrameCacheEntry(key, dummyToken(i * 33_333L, false), byteSize = 4096L))
            assertTrue("Cache exceeded byte budget at i=$i", cache.stats().currentBytes <= maxBytes)
        }

        val stats = cache.stats()
        assertEquals(16, stats.entryCount)
        assertEquals(maxBytes, stats.currentBytes)
        assertTrue(evictedKeys.get() >= 1984)

        // Single oversized entry (> maxBytes) must be rejected cleanly without exceeding budget
        val hugeKey = FrameCacheKey("file:///huge.mp4", 0L, 4096, 4096, false)
        cache.put(hugeKey, FrameCacheEntry(hugeKey, dummyToken(0L, false), byteSize = maxBytes * 2))
        assertNull(cache.get(hugeKey))
        assertTrue(cache.stats().currentBytes <= maxBytes)
    }

    @Test
    fun `priority scheduler runs urgent tasks first and cleans up cancelled resources`() = runBlocking {
        val scheduler = PriorityTaskScheduler(Dispatchers.Default, maxConcurrentWorkers = 1)
        val gate = CountDownLatch(1)
        val releasedCount = AtomicInteger(0)
        val executionOrder = Collections.synchronizedList(mutableListOf<String>())

        // Hold single worker on blocker task briefly while queuing prioritized work
        scheduler.enqueue("blocker", TaskPriority.PLAYBACK_DECODE) {
            gate.await(2, TimeUnit.SECONDS)
        }
        scheduler.enqueue("low_wf", TaskPriority.WAVEFORM_PREFETCH) {
            executionOrder.add("low_wf")
        }
        scheduler.enqueue("med_thumb", TaskPriority.THUMBNAIL_PREFETCH) {
            executionOrder.add("med_thumb")
        }
        scheduler.enqueue("high_scrub", TaskPriority.URGENT_SCRUB) {
            executionOrder.add("high_scrub")
        }
        val toCancel = scheduler.enqueue("cancel_proxy", TaskPriority.PROXY_GENERATION) { handle ->
            handle.registerCleanup { releasedCount.incrementAndGet() }
            delay(500)
        }
        toCancel.registerCleanup { releasedCount.incrementAndGet() }
        assertTrue(scheduler.cancelTask("cancel_proxy"))
        assertEquals(1, releasedCount.get())

        // Unblock worker and wait briefly for priority queue to drain
        gate.countDown()
        val waitStart = System.currentTimeMillis()
        while (executionOrder.size < 3 && System.currentTimeMillis() - waitStart < 2_000L) {
            delay(10)
        }

        assertEquals(listOf("high_scrub", "med_thumb", "low_wf"), executionOrder)
        assertTrue(scheduler.diagnostics.value.cancelledTasks >= 1L)
        assertTrue(scheduler.diagnostics.value.releasedCancelledResources >= 1L)
        scheduler.release()
    }
}

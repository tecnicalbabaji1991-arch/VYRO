package com.vyro.core.contracts

import com.vyro.core.model.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlaybackFoundationTest {

    @Test
    fun `seek generation tracker cancels older seeks and rejects stale frames`() {
        val tracker = SeekGenerationTracker()
        val gen1 = tracker.beginSeek(1_000_000L)
        val gen2 = tracker.beginSeek(2_500_000L)
        val gen3 = tracker.beginSeek(4_000_000L)

        assertFalse(tracker.isCurrent(gen1))
        assertFalse(tracker.isCurrent(gen2))
        assertTrue(tracker.isCurrent(gen3))

        assertFalse(tracker.acceptOrReject(gen1))
        assertFalse(tracker.acceptOrReject(gen2))
        assertTrue(tracker.acceptOrReject(gen3))
        assertEquals(2L, tracker.rejectedStaleFrames())
        assertEquals(4_000_000L, tracker.lastTargetTimelineUs)
    }

    @Test
    fun `bounded frame queue enforces fixed capacity and flushes older generations cleanly`() {
        val releasedTokens = mutableListOf<Long>()
        val queue = BoundedFrameQueue<DecodedFrameToken>(
            capacity = 3,
            generationOf = { it.seekGeneration },
            onEvictOrDiscard = { releasedTokens.add(it.sourcePtsUs) },
        )

        fun token(ptsUs: Long, gen: Long) = DecodedFrameToken(
            clipId = "clip_1",
            uriString = "content://vyro/clip_1",
            sourcePtsUs = ptsUs,
            timelineUs = ptsUs,
            seekGeneration = gen,
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            isKeyFrame = true,
            backend = DecoderBackendType.MEDIA_CODEC_SURFACE,
        )

        assertTrue(queue.enqueue(token(100L, 1L)))
        assertTrue(queue.enqueue(token(200L, 1L)))
        assertTrue(queue.enqueue(token(300L, 1L)))
        // 4th frame must evict oldest (100L) to stay strictly bounded at capacity 3
        assertTrue(queue.enqueue(token(400L, 1L)))

        val stats = queue.stats()
        assertEquals(3, stats.size)
        assertEquals(1L, stats.droppedCount)
        assertEquals(listOf(100L), releasedTokens)

        // Flush on seek generation 2
        val flushedCount = queue.flushForGeneration(2L)
        assertEquals(3, flushedCount)
        assertEquals(0, queue.stats().size)
        assertEquals(listOf(100L, 200L, 300L, 400L), releasedTokens)

        // Stale frame from generation 1 must be rejected and released immediately
        assertFalse(queue.enqueue(token(500L, 1L)))
        assertEquals(1L, queue.stats().staleRejectedCount)
        assertTrue(releasedTokens.contains(500L))
        assertNull(queue.dequeue())
    }

    @Test
    fun `master playback clock keeps av drift within 45ms tolerance and steps frames accurately`() {
        val clock = MasterPlaybackClock(avToleranceUs = PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US)
        clock.configure(frameRate = Rational.FPS_29_97, maxDurationUs = 10_000_000L)

        var nowNanos = 1_000_000_000L
        val seekAnchorUs = clock.seek(1_000_000L, nowNanos = nowNanos)
        clock.start(nowNanos = nowNanos)

        // Advance 500 ms cleanly
        nowNanos += 500_000_000L
        val midReading = clock.advance(nowNanos)
        assertEquals(seekAnchorUs + 500_000L, midReading.positionUs)
        assertFalse(midReading.reachedEnd)

        // Inject 60ms drift (> 45ms tolerance), verify clock disciplines drift back within tolerance
        var reading = clock.reportAudioClock(audioClockUs = seekAnchorUs + 440_000L, nowNanos = nowNanos)
        assertTrue(
            "Expected AV drift <= 45000us after correction, was ${reading.signedDriftUs}",
            abs(reading.signedDriftUs) <= PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US,
        )
        assertTrue(reading.withinTolerance)

        // Repeated small drift updates keep drift bounded within tolerance
        for (i in 1..10) {
            nowNanos += 33_366_000L
            val audioPos = seekAnchorUs + 440_000L + i * 33_366L
            reading = clock.reportAudioClock(audioClockUs = audioPos, nowNanos = nowNanos)
        }
        assertTrue(
            "Expected steady-state AV drift within tolerance, was ${reading.signedDriftUs}",
            abs(reading.signedDriftUs) <= PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US,
        )

        // Frame stepping forward and backward stops playback and moves exact 1-frame step
        val beforeStep = clock.seek(2_000_000L, nowNanos = nowNanos)
        val forwardPos = clock.stepFrame(forward = true, nowNanos = nowNanos)
        assertTrue(forwardPos > beforeStep)
        assertFalse(clock.isPlaying())
        val backPos = clock.stepFrame(forward = false, nowNanos = nowNanos)
        assertEquals(beforeStep, backPos)
    }
}

package com.vyro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimebaseTest {

    @Test
    fun `rational 29_97 and 24 fps step forward and backward without cumulative drift`() {
        val ntsc = Rational.FPS_29_97
        var pos = 0L
        repeat(30_000) {
            pos = ntsc.stepForward(pos, maxDurationUs = 0L)
        }
        // 30,000 frames at 30000/1001 fps is exactly 1001 seconds = 1_001_000_000 us
        assertEquals(1_001_000_000L, pos)

        repeat(30_000) {
            pos = ntsc.stepBackward(pos)
        }
        assertEquals(0L, pos)
    }

    @Test
    fun `rational snapToFrame handles zero negative and multi-hour timestamps without overflow`() {
        val fps60 = Rational.FPS_60
        assertEquals(0L, fps60.snapToFrame(-50_000L))
        assertEquals(0L, fps60.snapToFrame(0L))

        val tenHoursUs = 10L * 3600L * 1_000_000L
        val frameIndex = fps60.timeUsToFrameIndex(tenHoursUs)
        assertEquals(10L * 3600L * 60L, frameIndex)
        assertEquals(tenHoursUs, fps60.frameIndexToTimeUs(frameIndex))
    }

    @Test
    fun `clip timing maps timeline to source and source to timeline across playback speeds`() {
        val halfSpeed = ClipTiming(
            timelineStartUs = 1_000_000L,
            sourceInUs = 500_000L,
            sourceOutUs = 2_500_000L,
            playbackSpeed = Rational.SPEED_0_5X,
        )
        // 2s of source at 0.5x = 4s on timeline
        assertEquals(4_000_000L, halfSpeed.timelineDurationUs)
        assertEquals(5_000_000L, halfSpeed.timelineEndUs)

        // Midpoint at 3_000_000us (2s into timeline clip) -> 1s into source -> 1_500_000us
        assertEquals(1_500_000L, halfSpeed.timelineToSourceUs(3_000_000L))
        assertEquals(3_000_000L, halfSpeed.sourceToTimelineUs(1_500_000L))

        // Boundary containment: start inclusive, end exclusive
        assertEquals(500_000L, halfSpeed.timelineToSourceUs(1_000_000L))
        assertNull(halfSpeed.timelineToSourceUs(5_000_000L))
        assertNull(halfSpeed.timelineToSourceUs(999_999L))

        val doubleSpeed = ClipTiming(
            timelineStartUs = 0L,
            sourceInUs = 0L,
            sourceOutUs = 4_000_000L,
            playbackSpeed = Rational.SPEED_2X,
        )
        assertEquals(2_000_000L, doubleSpeed.timelineDurationUs)
        assertEquals(2_000_000L, doubleSpeed.timelineToSourceUs(1_000_000L))
        assertEquals(1_000_000L, doubleSpeed.sourceToTimelineUs(2_000_000L))
    }

    @Test
    fun `half-open clip overlap detects collision only on true intersection`() {
        val left = ClipTiming(0L, 0L, 2_000_000L)
        val adjacentRight = ClipTiming(2_000_000L, 0L, 2_000_000L)
        val overlappingRight = ClipTiming(1_999_999L, 0L, 2_000_000L)

        assertFalse(left.overlaps(adjacentRight))
        assertFalse(adjacentRight.overlaps(left))
        assertTrue(left.overlaps(overlappingRight))
        assertTrue(overlappingRight.overlaps(left))
    }

    @Test
    fun `timecode formatter formats sub-second frames hours and negative clamps`() {
        assertEquals("00:00:00", TimecodeFormatter.formatUs(-100L, Rational.FPS_30))
        assertEquals("01:02:03:15", TimecodeFormatter.formatUs(3_723_500_000L, Rational.FPS_30))
        assertEquals("0:04.5", TimecodeFormatter.formatCompactTenths(4_500_000L))
        assertEquals("2:05", TimecodeFormatter.formatClipBadge(125_000_000L))
    }
}

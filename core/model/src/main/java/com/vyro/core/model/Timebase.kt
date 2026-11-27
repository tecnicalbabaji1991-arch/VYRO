package com.vyro.core.model

import java.math.BigInteger
import kotlin.math.max

/**
 * Rational number representation for frame rates and playback speeds.
 * Uses exact BigInteger rational scaling to prevent cumulative rounding drift or 64-bit overflow.
 */
data class Rational(
    val numerator: Long,
    val denominator: Long = 1L,
) {
    init {
        require(numerator > 0L) { "Rational numerator must be positive: $numerator" }
        require(denominator > 0L) { "Rational denominator must be positive: $denominator" }
    }

    val frameDurationUs: Long
        get() = (1_000_000L * denominator) / numerator

    val nominalFpsInt: Int
        get() = ((numerator + denominator / 2L) / denominator).toInt()

    fun toFloat(): Float = numerator.toFloat() / denominator.toFloat()

    fun frameIndexToTimeUs(frameIndex: Long): Long {
        if (frameIndex <= 0L) return 0L
        val num = BigInteger.valueOf(numerator)
        val total = BigInteger.valueOf(frameIndex)
            .multiply(MICROS_PER_SEC)
            .multiply(BigInteger.valueOf(denominator))
        return total.add(num.divide(BigInteger.TWO)).divide(num).toLong()
    }

    fun timeUsToFrameIndex(positionUs: Long): Long {
        if (positionUs <= 0L) return 0L
        val denomScaled = MICROS_PER_SEC.multiply(BigInteger.valueOf(denominator))
        val scaled = BigInteger.valueOf(positionUs).multiply(BigInteger.valueOf(numerator))
        return scaled.add(denomScaled.divide(BigInteger.TWO)).divide(denomScaled).toLong()
    }

    fun stepForward(positionUs: Long, maxDurationUs: Long): Long {
        val nextIndex = timeUsToFrameIndex(positionUs.coerceAtLeast(0L)) + 1L
        val next = frameIndexToTimeUs(nextIndex)
        return if (maxDurationUs > 0L) next.coerceAtMost(maxDurationUs) else next
    }

    fun stepBackward(positionUs: Long): Long {
        val currentIndex = timeUsToFrameIndex(positionUs.coerceAtLeast(0L))
        if (currentIndex <= 0L) return 0L
        return frameIndexToTimeUs(currentIndex - 1L)
    }

    fun snapToFrame(positionUs: Long): Long {
        if (positionUs <= 0L) return 0L
        return frameIndexToTimeUs(timeUsToFrameIndex(positionUs))
    }

    companion object {
        private val MICROS_PER_SEC = BigInteger.valueOf(1_000_000L)

        val FPS_23_976 = Rational(24_000L, 1_001L)
        val FPS_24 = Rational(24L, 1L)
        val FPS_29_97 = Rational(30_000L, 1_001L)
        val FPS_30 = Rational(30L, 1L)
        val FPS_59_94 = Rational(60_000L, 1_001L)
        val FPS_60 = Rational(60L, 1L)

        val SPEED_0_5X = Rational(1L, 2L)
        val SPEED_1X = Rational(1L, 1L)
        val SPEED_2X = Rational(2L, 1L)
    }
}

data class TimeRange(
    val startUs: Long,
    val durationUs: Long,
) {
    val endUs: Long get() = startUs + durationUs

    fun isValid(): Boolean = startUs >= 0L && durationUs >= 0L

    fun contains(positionUs: Long): Boolean = positionUs in startUs until endUs

    fun overlaps(other: TimeRange): Boolean = startUs < other.endUs && other.startUs < endUs
}

data class ClipTiming(
    val timelineStartUs: Long,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val playbackSpeed: Rational = Rational.SPEED_1X,
) {
    val timelineDurationUs: Long
        get() {
            val raw = (sourceOutUs - sourceInUs).coerceAtLeast(0L)
            if (raw == 0L) return 0L
            return BigInteger.valueOf(raw)
                .multiply(BigInteger.valueOf(playbackSpeed.denominator))
                .divide(BigInteger.valueOf(playbackSpeed.numerator))
                .toLong()
        }

    val timelineEndUs: Long
        get() = timelineStartUs + timelineDurationUs

    fun isValid(): Boolean =
        timelineStartUs >= 0L &&
            sourceInUs >= 0L &&
            sourceOutUs > sourceInUs &&
            timelineDurationUs > 0L

    fun containsTimelinePosition(positionUs: Long): Boolean =
        positionUs in timelineStartUs until timelineEndUs

    fun overlaps(other: ClipTiming): Boolean =
        timelineStartUs < other.timelineEndUs && other.timelineStartUs < timelineEndUs

    fun timelineToSourceUs(timelinePositionUs: Long): Long? {
        if (!containsTimelinePosition(timelinePositionUs)) return null
        val offsetTimelineUs = timelinePositionUs - timelineStartUs
        val offsetSourceUs = BigInteger.valueOf(offsetTimelineUs)
            .multiply(BigInteger.valueOf(playbackSpeed.numerator))
            .divide(BigInteger.valueOf(playbackSpeed.denominator))
            .toLong()
        return (sourceInUs + offsetSourceUs).coerceIn(sourceInUs, sourceOutUs)
    }

    fun sourceToTimelineUs(sourcePositionUs: Long): Long? {
        if (sourcePositionUs < sourceInUs || sourcePositionUs > sourceOutUs) return null
        val offsetSourceUs = sourcePositionUs - sourceInUs
        val offsetTimelineUs = BigInteger.valueOf(offsetSourceUs)
            .multiply(BigInteger.valueOf(playbackSpeed.denominator))
            .divide(BigInteger.valueOf(playbackSpeed.numerator))
            .toLong()
        return (timelineStartUs + offsetTimelineUs).coerceIn(timelineStartUs, timelineEndUs)
    }
}

object TimecodeFormatter {
    fun formatUs(positionUs: Long, frameRate: Rational): String {
        val clamped = positionUs.coerceAtLeast(0L)
        val totalSeconds = clamped / 1_000_000L
        val remainderUs = clamped % 1_000_000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val frameDuration = frameRate.frameDurationUs.coerceAtLeast(1L)
        val frames = (remainderUs / frameDuration).coerceIn(0L, max(0, frameRate.nominalFpsInt - 1).toLong())
        return if (hours > 0L) {
            "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
        } else {
            "%02d:%02d:%02d".format(minutes, seconds, frames)
        }
    }

    fun formatCompactTenths(positionUs: Long): String {
        val clampedMs = positionUs.coerceAtLeast(0L) / 100_000L
        val tenths = clampedMs % 10L
        val totalSeconds = clampedMs / 10L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d.%d".format(minutes, seconds, tenths)
    }

    fun formatClipBadge(positionUs: Long): String {
        val totalSeconds = positionUs.coerceAtLeast(0L) / 1_000_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }
}

package com.vyro.core.contracts

import com.vyro.core.model.Rational
import kotlin.math.abs

/**
 * Deterministic master timeline & A/V synchronization clock.
 *
 * Uses monotonic elapsed wall time when video-only, or disciplines against an external
 * audio master clock within [PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US] (45 ms).
 */
class MasterPlaybackClock(
    val avToleranceUs: Long = PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US,
) {
    private val lock = Any()

    private var frameRate: Rational = Rational.FPS_30
    private var maxDurationUs: Long = 4_000_000L
    private var timelineAnchorUs: Long = 0L
    private var lastPositionUs: Long = 0L
    private var wallAnchorNanos: Long = 0L
    private var isPlaying: Boolean = false

    private var lastAudioClockUs: Long? = null
    private var lastSignedDriftUs: Long = 0L
    private var maxAbsDriftUs: Long = 0L

    data class ClockReading(
        val positionUs: Long,
        val audioClockUs: Long?,
        val signedDriftUs: Long,
        val maxAbsDriftUs: Long,
        val withinTolerance: Boolean,
        val reachedEnd: Boolean,
    )

    fun configure(frameRate: Rational, maxDurationUs: Long) = synchronized(lock) {
        this.frameRate = frameRate
        this.maxDurationUs = maxDurationUs.coerceAtLeast(frameRate.frameDurationUs)
        lastPositionUs = lastPositionUs.coerceIn(0L, this.maxDurationUs)
        timelineAnchorUs = lastPositionUs
    }

    fun seek(positionUs: Long, nowNanos: Long = System.nanoTime(), snapToFrame: Boolean = true): Long =
        synchronized(lock) {
            val bounded = positionUs.coerceIn(0L, maxDurationUs)
            val resolved = if (snapToFrame) frameRate.snapToFrame(bounded) else bounded
            lastPositionUs = resolved
            timelineAnchorUs = resolved
            wallAnchorNanos = nowNanos
            lastAudioClockUs = null
            lastSignedDriftUs = 0L
            resolved
        }

    fun start(nowNanos: Long = System.nanoTime()): Long = synchronized(lock) {
        if (lastPositionUs >= maxDurationUs) {
            lastPositionUs = 0L
        }
        timelineAnchorUs = lastPositionUs
        wallAnchorNanos = nowNanos
        isPlaying = true
        lastPositionUs
    }

    fun pause(nowNanos: Long = System.nanoTime()): Long = synchronized(lock) {
        if (isPlaying) {
            val reading = advanceLocked(nowNanos)
            isPlaying = false
            timelineAnchorUs = reading.positionUs
            reading.positionUs
        } else {
            lastPositionUs
        }
    }

    fun stepFrame(forward: Boolean, nowNanos: Long = System.nanoTime()): Long = synchronized(lock) {
        isPlaying = false
        val next = if (forward) {
            frameRate.stepForward(lastPositionUs, maxDurationUs)
        } else {
            frameRate.stepBackward(lastPositionUs)
        }
        lastPositionUs = next
        timelineAnchorUs = next
        wallAnchorNanos = nowNanos
        lastAudioClockUs = null
        lastSignedDriftUs = 0L
        next
    }

    fun reportAudioClock(audioClockUs: Long, nowNanos: Long = System.nanoTime()): ClockReading =
        synchronized(lock) {
            val currentVideoUs = if (isPlaying) advanceLocked(nowNanos).positionUs else lastPositionUs
            val boundedAudio = audioClockUs.coerceIn(0L, maxDurationUs)
            lastAudioClockUs = boundedAudio
            val rawDriftUs = currentVideoUs - boundedAudio
            maxAbsDriftUs = maxOf(maxAbsDriftUs, abs(rawDriftUs))

            // When drift exceeds half tolerance, softly re-anchor video clock toward audio master
            val adjustedVideoUs = if (isPlaying && abs(rawDriftUs) > avToleranceUs / 2L) {
                val correction = rawDriftUs / 2L
                val corrected = (currentVideoUs - correction).coerceIn(0L, maxDurationUs)
                timelineAnchorUs = corrected
                wallAnchorNanos = nowNanos
                lastPositionUs = corrected
                corrected
            } else {
                currentVideoUs
            }
            val remainingDrift = adjustedVideoUs - boundedAudio
            lastSignedDriftUs = remainingDrift
            ClockReading(
                positionUs = adjustedVideoUs,
                audioClockUs = boundedAudio,
                signedDriftUs = remainingDrift,
                maxAbsDriftUs = maxAbsDriftUs,
                withinTolerance = abs(remainingDrift) <= avToleranceUs,
                reachedEnd = adjustedVideoUs >= maxDurationUs,
            )
        }

    fun advance(nowNanos: Long = System.nanoTime()): ClockReading = synchronized(lock) {
        advanceLocked(nowNanos)
    }

    fun currentPositionUs(): Long = synchronized(lock) { lastPositionUs }

    fun isPlaying(): Boolean = synchronized(lock) { isPlaying }

    private fun advanceLocked(nowNanos: Long): ClockReading {
        if (!isPlaying) {
            return ClockReading(
                positionUs = lastPositionUs,
                audioClockUs = lastAudioClockUs,
                signedDriftUs = lastSignedDriftUs,
                maxAbsDriftUs = maxAbsDriftUs,
                withinTolerance = abs(lastSignedDriftUs) <= avToleranceUs,
                reachedEnd = lastPositionUs >= maxDurationUs,
            )
        }
        val elapsedUs = ((nowNanos - wallAnchorNanos).coerceAtLeast(0L)) / 1_000L
        val rawTarget = timelineAnchorUs + elapsedUs
        val reachedEnd = rawTarget >= maxDurationUs
        val resolved = rawTarget.coerceIn(0L, maxDurationUs)
        lastPositionUs = resolved
        if (reachedEnd) {
            isPlaying = false
            timelineAnchorUs = resolved
        }
        val audioUs = lastAudioClockUs
        val drift = if (audioUs != null) resolved - audioUs else 0L
        if (audioUs != null) {
            lastSignedDriftUs = drift
            maxAbsDriftUs = maxOf(maxAbsDriftUs, abs(drift))
        }
        return ClockReading(
            positionUs = resolved,
            audioClockUs = audioUs,
            signedDriftUs = drift,
            maxAbsDriftUs = maxAbsDriftUs,
            withinTolerance = abs(drift) <= avToleranceUs,
            reachedEnd = reachedEnd,
        )
    }
}

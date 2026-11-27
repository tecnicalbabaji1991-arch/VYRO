package com.vyro.core.contracts

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe generation gate for seek cancellation and stale-frame rejection.
 * Any decode result stamped with an older generation is discarded before touching the preview surface.
 */
class SeekGenerationTracker {
    private val currentGen = AtomicLong(1L)
    private val rejectedCount = AtomicLong(0L)

    @Volatile
    var lastTargetTimelineUs: Long = 0L
        private set

    fun currentGeneration(): Long = currentGen.get()

    fun rejectedStaleFrames(): Long = rejectedCount.get()

    fun beginSeek(targetTimelineUs: Long): Long {
        lastTargetTimelineUs = targetTimelineUs.coerceAtLeast(0L)
        return currentGen.incrementAndGet()
    }

    fun isCurrent(generation: Long): Boolean = generation == currentGen.get()

    fun acceptOrReject(frameGeneration: Long): Boolean {
        return if (frameGeneration == currentGen.get()) {
            true
        } else {
            rejectedCount.incrementAndGet()
            false
        }
    }
}

/**
 * Fixed-capacity thread-safe decoded frame queue with deterministic oldest-frame eviction
 * and seek-generation filtering so playback memory remains strictly bounded across API 30+ devices.
 */
class BoundedFrameQueue<T>(
    val capacity: Int = PlaybackDiagnosticsSnapshot.DEFAULT_QUEUE_CAPACITY,
    private val generationOf: (T) -> Long,
    private val onEvictOrDiscard: (T) -> Unit = {},
) {
    init {
        require(capacity in 1..16) { "BoundedFrameQueue capacity must be 1..16, got $capacity" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<T>(capacity)
    private var activeGeneration: Long = 1L
    private var droppedCount: Long = 0L
    private var staleRejectedCount: Long = 0L
    private var enqueuedCount: Long = 0L
    private var dequeuedCount: Long = 0L

    data class QueueStats(
        val size: Int,
        val capacity: Int,
        val activeGeneration: Long,
        val droppedCount: Long,
        val staleRejectedCount: Long,
        val enqueuedCount: Long,
        val dequeuedCount: Long,
    )

    fun enqueue(item: T): Boolean {
        var displaced: T? = null
        val accepted: Boolean
        synchronized(lock) {
            val itemGen = generationOf(item)
            if (itemGen < activeGeneration) {
                staleRejectedCount++
                accepted = false
            } else {
                if (buffer.size >= capacity) {
                    displaced = buffer.removeFirst()
                    droppedCount++
                }
                buffer.addLast(item)
                enqueuedCount++
                accepted = true
            }
        }
        displaced?.let(onEvictOrDiscard)
        if (!accepted) {
            onEvictOrDiscard(item)
        }
        return accepted
    }

    fun dequeue(): T? = synchronized(lock) {
        if (buffer.isEmpty()) {
            null
        } else {
            dequeuedCount++
            buffer.removeFirst()
        }
    }

    fun peekLatest(): T? = synchronized(lock) { buffer.lastOrNull() }

    fun flushForGeneration(newGeneration: Long): Int {
        val flushed = mutableListOf<T>()
        synchronized(lock) {
            if (newGeneration > activeGeneration) {
                activeGeneration = newGeneration
            }
            while (buffer.isNotEmpty()) {
                flushed.add(buffer.removeFirst())
            }
        }
        flushed.forEach(onEvictOrDiscard)
        return flushed.size
    }

    fun clear() {
        val cleared = mutableListOf<T>()
        synchronized(lock) {
            while (buffer.isNotEmpty()) {
                cleared.add(buffer.removeFirst())
            }
        }
        cleared.forEach(onEvictOrDiscard)
    }

    fun stats(): QueueStats = synchronized(lock) {
        QueueStats(
            size = buffer.size,
            capacity = capacity,
            activeGeneration = activeGeneration,
            droppedCount = droppedCount,
            staleRejectedCount = staleRejectedCount,
            enqueuedCount = enqueuedCount,
            dequeuedCount = dequeuedCount,
        )
    }
}

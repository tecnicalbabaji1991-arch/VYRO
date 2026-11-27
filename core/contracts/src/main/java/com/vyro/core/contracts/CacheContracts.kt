package com.vyro.core.contracts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Memory trim levels mirrored from Android ComponentCallbacks2 for pure JVM + Android parity.
 */
enum class MemoryPressureLevel(val retainFraction: Float) {
    NONE(1.0f),
    MODERATE(0.50f),
    LOW(0.25f),
    CRITICAL(0.10f),
    ;

    companion object {
        fun fromAndroidTrimLevel(level: Int): MemoryPressureLevel = when {
            level >= 80 -> CRITICAL // TRIM_MEMORY_COMPLETE
            level >= 15 -> CRITICAL // TRIM_MEMORY_RUNNING_CRITICAL
            level >= 10 -> LOW      // TRIM_MEMORY_RUNNING_LOW
            level >= 5 -> MODERATE  // TRIM_MEMORY_RUNNING_MODERATE / UI_HIDDEN (20)
            else -> NONE
        }
    }
}

enum class TaskPriority(val rank: Int) {
    URGENT_SCRUB(0),
    PLAYBACK_DECODE(1),
    PROXY_GENERATION(2),
    THUMBNAIL_PREFETCH(3),
    WAVEFORM_PREFETCH(4),
}

enum class ProxyStatus {
    NONE,
    QUEUED,
    GENERATING,
    READY,
    CANCELLED,
    FAILED,
}

data class FrameCacheKey(
    val sourceUri: String,
    val sourcePtsUs: Long,
    val width: Int,
    val height: Int,
    val isProxy: Boolean,
)

data class ThumbnailCacheKey(
    val sourceUri: String,
    val bucketTimeUs: Long,
    val widthPx: Int,
    val heightPx: Int,
)

data class WaveformCacheKey(
    val sourceId: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val bucketCount: Int,
)

data class CacheBudgetConfig(
    val frameCacheMaxBytes: Long = 16L * 1024L * 1024L,
    val thumbnailCacheMaxBytes: Long = 12L * 1024L * 1024L,
    val waveformCacheMaxBytes: Long = 2L * 1024L * 1024L,
    val proxyDiskMaxBytes: Long = 128L * 1024L * 1024L,
)

data class FrameCacheEntry(
    val key: FrameCacheKey,
    val token: DecodedFrameToken,
    val byteSize: Long,
    val pixelPayload: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameCacheEntry) return false
        return key == other.key && token == other.token && byteSize == other.byteSize
    }

    override fun hashCode(): Int = 31 * key.hashCode() + byteSize.hashCode()
}

data class ThumbnailCacheEntry(
    val key: ThumbnailCacheKey,
    val argbPixels: IntArray,
    val width: Int,
    val height: Int,
    val byteSize: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailCacheEntry) return false
        return key == other.key && width == other.width && height == other.height
    }

    override fun hashCode(): Int = 31 * key.hashCode() + byteSize.hashCode()
}

data class WaveformCacheEntry(
    val key: WaveformCacheKey,
    val profile: WaveformProfile,
    val byteSize: Long,
)

data class ProxyMapping(
    val assetId: String,
    val originalUri: String,
    val proxyUri: String? = null,
    val proxyWidth: Int = 540,
    val proxyHeight: Int = 960,
    val fileSizeBytes: Long = 0L,
    val status: ProxyStatus = ProxyStatus.NONE,
    val progress: Float = 0f,
    val errorNote: String? = null,
)

data class CacheStats(
    val name: String,
    val entryCount: Int = 0,
    val currentBytes: Long = 0L,
    val maxBytes: Long = 0L,
    val hits: Long = 0L,
    val misses: Long = 0L,
    val evictions: Long = 0L,
) {
    val hitRatePercent: Int
        get() {
            val total = hits + misses
            return if (total <= 0L) 0 else ((hits * 100L) / total).toInt().coerceIn(0, 100)
        }
}

data class SchedulerDiagnosticsSnapshot(
    val pendingTasks: Int = 0,
    val runningTasks: Int = 0,
    val completedTasks: Long = 0L,
    val cancelledTasks: Long = 0L,
    val releasedCancelledResources: Long = 0L,
)

data class CacheMemoryDiagnosticsSnapshot(
    val frameCache: CacheStats = CacheStats("Frames"),
    val thumbnailCache: CacheStats = CacheStats("Thumbnails"),
    val waveformCache: CacheStats = CacheStats("Waveforms"),
    val proxyEnabled: Boolean = false,
    val proxyMappingsCount: Int = 0,
    val proxyReadyCount: Int = 0,
    val proxyDiskBytes: Long = 0L,
    val proxyDiskBudgetBytes: Long = 128L * 1024L * 1024L,
    val memoryPressureLevel: MemoryPressureLevel = MemoryPressureLevel.NONE,
    val trimEventCount: Int = 0,
    val scheduler: SchedulerDiagnosticsSnapshot = SchedulerDiagnosticsSnapshot(),
) {
    val totalResidentBytes: Long
        get() = frameCache.currentBytes + thumbnailCache.currentBytes + waveformCache.currentBytes

    val totalBudgetBytes: Long
        get() = frameCache.maxBytes + thumbnailCache.maxBytes + waveformCache.maxBytes
}

/**
 * Thread-safe byte-weighted LRU cache strictly enforcing byte budgets and invoking [onEvict] cleanup.
 */
class BoundedByteLruCache<K : Any, V : Any>(
    private val name: String,
    initialMaxBytes: Long,
    private val weightOf: (V) -> Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var maxBytes: Long = initialMaxBytes.coerceAtLeast(0L)
    private var currentBytes: Long = 0L
    private var hits: Long = 0L
    private var misses: Long = 0L
    private var evictions: Long = 0L

    fun get(key: K): V? = synchronized(lock) {
        val found = map[key]
        if (found != null) {
            hits++
        } else {
            misses++
        }
        found
    }

    fun put(key: K, value: V) {
        val itemWeight = weightOf(value).coerceAtLeast(1L)
        val evictedItems = ArrayList<Pair<K, V>>()
        synchronized(lock) {
            if (maxBytes <= 0L) {
                evictions++
                evictedItems.add(key to value)
            } else if (itemWeight > maxBytes) {
                // Single item exceeds total budget: reject into eviction hook rather than breaking budget
                map.remove(key)?.let { prev ->
                    currentBytes -= weightOf(prev).coerceAtLeast(1L)
                    evictions++
                    evictedItems.add(key to prev)
                }
                evictions++
                evictedItems.add(key to value)
            } else {
                map.remove(key)?.let { prev ->
                    currentBytes -= weightOf(prev).coerceAtLeast(1L)
                    evictedItems.add(key to prev)
                }
                map[key] = value
                currentBytes += itemWeight
                evictOverBudgetLocked(maxBytes, evictedItems)
            }
        }
        evictedItems.forEach { (k, v) -> onEvict(k, v) }
    }

    fun remove(key: K): V? {
        val removed = synchronized(lock) {
            map.remove(key)?.also { prev ->
                currentBytes = max(0L, currentBytes - weightOf(prev).coerceAtLeast(1L))
            }
        }
        if (removed != null) {
            onEvict(key, removed)
        }
        return removed
    }

    fun trimToBytes(targetMaxBytes: Long) {
        val target = targetMaxBytes.coerceAtLeast(0L)
        val evictedItems = ArrayList<Pair<K, V>>()
        synchronized(lock) {
            evictOverBudgetLocked(target, evictedItems)
        }
        evictedItems.forEach { (k, v) -> onEvict(k, v) }
    }

    fun clear() {
        trimToBytes(0L)
    }

    fun updateBudget(newMaxBytes: Long) {
        val target = newMaxBytes.coerceAtLeast(0L)
        val evictedItems = ArrayList<Pair<K, V>>()
        synchronized(lock) {
            maxBytes = target
            evictOverBudgetLocked(target, evictedItems)
        }
        evictedItems.forEach { (k, v) -> onEvict(k, v) }
    }

    fun stats(): CacheStats = synchronized(lock) {
        CacheStats(
            name = name,
            entryCount = map.size,
            currentBytes = currentBytes,
            maxBytes = maxBytes,
            hits = hits,
            misses = misses,
            evictions = evictions,
        )
    }

    private fun evictOverBudgetLocked(limitBytes: Long, evictedOut: MutableList<Pair<K, V>>) {
        val iterator = map.entries.iterator()
        while (currentBytes > limitBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            currentBytes = max(0L, currentBytes - weightOf(eldest.value).coerceAtLeast(1L))
            evictions++
            evictedOut.add(eldest.key to eldest.value)
        }
    }
}

/**
 * Priority-ordered, cancellable background task scheduler enforcing explicit cleanup on cancellation.
 */
class PriorityTaskScheduler(
    dispatcher: CoroutineDispatcher,
    private val maxConcurrentWorkers: Int = 2,
) {
    class CancellationHandle {
        @Volatile
        var isCancelled: Boolean = false
            private set

        private val cleanupHooks = ArrayList<() -> Unit>()
        private val hookLock = Any()

        fun registerCleanup(action: () -> Unit) {
            val invokeImmediately = synchronized(hookLock) {
                if (isCancelled) {
                    true
                } else {
                    cleanupHooks.add(action)
                    false
                }
            }
            if (invokeImmediately) {
                runCatching { action() }
            }
        }

        fun cancel(): Int {
            val toRun = synchronized(hookLock) {
                if (isCancelled) return@synchronized emptyList()
                isCancelled = true
                val copy = cleanupHooks.toList()
                cleanupHooks.clear()
                copy
            }
            toRun.forEach { runCatching { it() } }
            return toRun.size
        }
    }

    private data class QueuedWork(
        val id: String,
        val priority: TaskPriority,
        val sequenceOrder: Long,
        val handle: CancellationHandle,
        val block: suspend (CancellationHandle) -> Unit,
    ) : Comparable<QueuedWork> {
        override fun compareTo(other: QueuedWork): Int {
            val pCmp = priority.rank.compareTo(other.priority.rank)
            return if (pCmp != 0) pCmp else sequenceOrder.compareTo(other.sequenceOrder)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queueLock = Any()
    private val seqGen = AtomicLong(0L)
    private val pendingQueue = PriorityQueue<QueuedWork>()
    private val activeHandles = LinkedHashMap<String, CancellationHandle>()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var runningCount = 0
    private var completedCount = 0L
    private var cancelledCount = 0L
    private var releasedCancelledResourcesCount = 0L

    private val _diagnostics = MutableStateFlow(SchedulerDiagnosticsSnapshot())
    val diagnostics: StateFlow<SchedulerDiagnosticsSnapshot> = _diagnostics.asStateFlow()

    private val workerJobs: List<Job> = (0 until maxConcurrentWorkers.coerceAtLeast(1)).map {
        scope.launch {
            while (true) {
                val nextWork = dequeueNextWork()
                if (nextWork == null) {
                    wakeSignal.receiveCatching().getOrNull() ?: break
                    continue
                }
                if (nextWork.handle.isCancelled) {
                    continue
                }
                try {
                    nextWork.block(nextWork.handle)
                    synchronized(queueLock) {
                        if (!nextWork.handle.isCancelled) {
                            completedCount++
                        }
                    }
                } catch (_: CancellationException) {
                    val cleaned = nextWork.handle.cancel()
                    synchronized(queueLock) {
                        cancelledCount++
                        releasedCancelledResourcesCount += cleaned
                    }
                } catch (_: Throwable) {
                    // Handled internally by caller; ensure handle cleanup runs if needed
                } finally {
                    synchronized(queueLock) {
                        activeHandles.remove(nextWork.id)
                        runningCount = max(0, runningCount - 1)
                        publishLocked()
                    }
                    wakeSignal.trySend(Unit)
                }
            }
        }
    }

    fun enqueue(
        taskId: String,
        priority: TaskPriority,
        block: suspend (CancellationHandle) -> Unit,
    ): CancellationHandle {
        val handle = CancellationHandle()
        val item = QueuedWork(
            id = taskId,
            priority = priority,
            sequenceOrder = seqGen.incrementAndGet(),
            handle = handle,
            block = block,
        )
        synchronized(queueLock) {
            // Cancel any stale task sharing identical ID
            activeHandles.remove(taskId)?.let { oldHandle ->
                val cleaned = oldHandle.cancel()
                cancelledCount++
                releasedCancelledResourcesCount += cleaned
            }
            activeHandles[taskId] = handle
            pendingQueue.add(item)
            publishLocked()
        }
        wakeSignal.trySend(Unit)
        return handle
    }

    fun cancelTask(taskId: String): Boolean {
        val handle = synchronized(queueLock) {
            val found = activeHandles.remove(taskId)
            pendingQueue.removeAll { it.id == taskId }
            found
        } ?: return false
        val cleaned = handle.cancel()
        synchronized(queueLock) {
            cancelledCount++
            releasedCancelledResourcesCount += cleaned
            publishLocked()
        }
        return true
    }

    fun cancelBelowPriority(threshold: TaskPriority): Int {
        val cancelledHandles = ArrayList<CancellationHandle>()
        synchronized(queueLock) {
            val iter = pendingQueue.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                if (entry.priority.rank >= threshold.rank) {
                    iter.remove()
                    activeHandles.remove(entry.id)
                    cancelledHandles.add(entry.handle)
                }
            }
        }
        var totalReleased = 0
        cancelledHandles.forEach { handle ->
            totalReleased += handle.cancel()
        }
        if (cancelledHandles.isNotEmpty()) {
            synchronized(queueLock) {
                cancelledCount += cancelledHandles.size
                releasedCancelledResourcesCount += totalReleased
                publishLocked()
            }
        }
        return cancelledHandles.size
    }

    private fun dequeueNextWork(): QueuedWork? = synchronized(queueLock) {
        while (pendingQueue.isNotEmpty()) {
            val candidate = pendingQueue.poll() ?: break
            if (!candidate.handle.isCancelled) {
                runningCount++
                publishLocked()
                return@synchronized candidate
            }
        }
        null
    }

    private fun publishLocked() {
        _diagnostics.value = SchedulerDiagnosticsSnapshot(
            pendingTasks = pendingQueue.size,
            runningTasks = runningCount,
            completedTasks = completedCount,
            cancelledTasks = cancelledCount,
            releasedCancelledResources = releasedCancelledResourcesCount,
        )
    }

    fun release() {
        val toCancel = synchronized(queueLock) {
            pendingQueue.clear()
            val copy = activeHandles.values.toList()
            activeHandles.clear()
            copy
        }
        toCancel.forEach { h ->
            val cleaned = h.cancel()
            synchronized(queueLock) {
                cancelledCount++
                releasedCancelledResourcesCount += cleaned
            }
        }
        wakeSignal.close()
        workerJobs.forEach { it.cancel() }
        scope.cancel()
        synchronized(queueLock) { publishLocked() }
    }
}

interface CacheAndProxyManagerContract {
    val diagnostics: StateFlow<CacheMemoryDiagnosticsSnapshot>
    val proxyEnabled: StateFlow<Boolean>

    fun setProxyPlaybackEnabled(enabled: Boolean)

    fun resolvePlaybackUri(originalUri: String?, assetId: String?): String?

    fun getFrame(key: FrameCacheKey): FrameCacheEntry?
    fun putFrame(entry: FrameCacheEntry)

    fun getThumbnail(key: ThumbnailCacheKey): ThumbnailCacheEntry?
    fun putThumbnail(entry: ThumbnailCacheEntry)

    fun getWaveform(key: WaveformCacheKey): WaveformCacheEntry?
    fun putWaveform(entry: WaveformCacheEntry)

    fun ensureProxyForAsset(assetId: String, originalUri: String, width: Int, height: Int)
    fun cancelProxyForAsset(assetId: String)
    fun getProxyMapping(assetId: String): ProxyMapping?

    fun onTrimMemory(level: MemoryPressureLevel)

    fun release()
}

package com.vyro.platform.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.BoundedByteLruCache
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.CacheBudgetConfig
import com.vyro.core.contracts.CacheMemoryDiagnosticsSnapshot
import com.vyro.core.contracts.FrameCacheEntry
import com.vyro.core.contracts.FrameCacheKey
import com.vyro.core.contracts.MemoryPressureLevel
import com.vyro.core.contracts.PriorityTaskScheduler
import com.vyro.core.contracts.ProxyMapping
import com.vyro.core.contracts.ProxyStatus
import com.vyro.core.contracts.TaskPriority
import com.vyro.core.contracts.ThumbnailCacheEntry
import com.vyro.core.contracts.ThumbnailCacheKey
import com.vyro.core.contracts.WaveformCacheEntry
import com.vyro.core.contracts.WaveformCacheKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Android implementation of Phase 7 budgeted caches, priority background task scheduling,
 * proxy MP4 generation/mapping, and ComponentCallbacks2 memory-pressure trimming.
 */
class AndroidCacheAndProxyManager(
    context: Context,
    dispatchers: DispatchersProvider,
    private val budget: CacheBudgetConfig = CacheBudgetConfig(),
    proxyRootDir: File? = null,
) : CacheAndProxyManagerContract {

    private val appContext = context.applicationContext
    private val proxyDir: File = proxyRootDir ?: File(appContext.cacheDir, "vyro_proxies")
    private val scheduler = PriorityTaskScheduler(dispatchers.io, maxConcurrentWorkers = 2)

    private val frameCache = BoundedByteLruCache<FrameCacheKey, FrameCacheEntry>(
        name = "Frames",
        initialMaxBytes = budget.frameCacheMaxBytes,
        weightOf = { it.byteSize },
    )
    private val thumbnailCache = BoundedByteLruCache<ThumbnailCacheKey, ThumbnailCacheEntry>(
        name = "Thumbnails",
        initialMaxBytes = budget.thumbnailCacheMaxBytes,
        weightOf = { it.byteSize },
    )
    private val waveformCache = BoundedByteLruCache<WaveformCacheKey, WaveformCacheEntry>(
        name = "Waveforms",
        initialMaxBytes = budget.waveformCacheMaxBytes,
        weightOf = { it.byteSize },
    )

    private val proxyMappings = ConcurrentHashMap<String, ProxyMapping>()
    private val lock = Any()
    private var currentPressureLevel = MemoryPressureLevel.NONE
    private var trimEventCount = 0

    private val _proxyEnabled = MutableStateFlow(false)
    override val proxyEnabled: StateFlow<Boolean> = _proxyEnabled.asStateFlow()

    private val _diagnostics = MutableStateFlow(CacheMemoryDiagnosticsSnapshot())
    override val diagnostics: StateFlow<CacheMemoryDiagnosticsSnapshot> = _diagnostics.asStateFlow()

    init {
        publishDiagnostics()
    }

    override fun setProxyPlaybackEnabled(enabled: Boolean) {
        _proxyEnabled.value = enabled
        publishDiagnostics()
    }

    override fun resolvePlaybackUri(originalUri: String?, assetId: String?): String? {
        if (originalUri.isNullOrBlank()) return originalUri
        if (!_proxyEnabled.value || assetId.isNullOrBlank()) return originalUri
        val mapping = proxyMappings[assetId] ?: return originalUri
        if (mapping.status == ProxyStatus.READY && !mapping.proxyUri.isNullOrBlank()) {
            val filePath = Uri.parse(mapping.proxyUri).path
            if (filePath != null && File(filePath).exists()) {
                return mapping.proxyUri
            }
        }
        return originalUri
    }

    override fun getFrame(key: FrameCacheKey): FrameCacheEntry? {
        return frameCache.get(key).also { publishDiagnostics() }
    }

    override fun putFrame(entry: FrameCacheEntry) {
        frameCache.put(entry.key, entry)
        publishDiagnostics()
    }

    override fun getThumbnail(key: ThumbnailCacheKey): ThumbnailCacheEntry? {
        return thumbnailCache.get(key).also { publishDiagnostics() }
    }

    override fun putThumbnail(entry: ThumbnailCacheEntry) {
        thumbnailCache.put(entry.key, entry)
        publishDiagnostics()
    }

    override fun getWaveform(key: WaveformCacheKey): WaveformCacheEntry? {
        return waveformCache.get(key).also { publishDiagnostics() }
    }

    override fun putWaveform(entry: WaveformCacheEntry) {
        waveformCache.put(entry.key, entry)
        publishDiagnostics()
    }

    override fun ensureProxyForAsset(assetId: String, originalUri: String, width: Int, height: Int) {
        if (assetId.isBlank() || originalUri.isBlank()) return
        val existing = proxyMappings[assetId]
        if (existing != null && (existing.status == ProxyStatus.READY || existing.status == ProxyStatus.GENERATING)) {
            return
        }

        val targetW = if (width >= height) 960 else 540
        val targetH = if (width >= height) 540 else 960
        proxyMappings[assetId] = ProxyMapping(
            assetId = assetId,
            originalUri = originalUri,
            proxyWidth = targetW,
            proxyHeight = targetH,
            status = ProxyStatus.QUEUED,
        )
        publishDiagnostics()

        scheduler.enqueue(taskId = "proxy_$assetId", priority = TaskPriority.PROXY_GENERATION) { handle ->
            generateProxyFile(
                assetId = assetId,
                originalUri = originalUri,
                targetWidth = targetW,
                targetHeight = targetH,
                handle = handle,
            )
        }
    }

    override fun cancelProxyForAsset(assetId: String) {
        scheduler.cancelTask("proxy_$assetId")
        val prev = proxyMappings[assetId]
        if (prev != null && prev.status != ProxyStatus.READY) {
            proxyMappings[assetId] = prev.copy(status = ProxyStatus.CANCELLED)
        }
        publishDiagnostics()
    }

    override fun getProxyMapping(assetId: String): ProxyMapping? = proxyMappings[assetId]

    override fun onTrimMemory(level: MemoryPressureLevel) {
        synchronized(lock) {
            currentPressureLevel = level
            trimEventCount++
        }
        when (level) {
            MemoryPressureLevel.NONE -> {
                frameCache.updateBudget(budget.frameCacheMaxBytes)
                thumbnailCache.updateBudget(budget.thumbnailCacheMaxBytes)
                waveformCache.updateBudget(budget.waveformCacheMaxBytes)
            }
            MemoryPressureLevel.MODERATE -> {
                frameCache.trimToBytes((budget.frameCacheMaxBytes * level.retainFraction).toLong())
                thumbnailCache.trimToBytes((budget.thumbnailCacheMaxBytes * level.retainFraction).toLong())
            }
            MemoryPressureLevel.LOW -> {
                scheduler.cancelBelowPriority(TaskPriority.THUMBNAIL_PREFETCH)
                frameCache.trimToBytes((budget.frameCacheMaxBytes * level.retainFraction).toLong())
                thumbnailCache.trimToBytes((budget.thumbnailCacheMaxBytes * level.retainFraction).toLong())
                waveformCache.trimToBytes((budget.waveformCacheMaxBytes * level.retainFraction).toLong())
            }
            MemoryPressureLevel.CRITICAL -> {
                scheduler.cancelBelowPriority(TaskPriority.PROXY_GENERATION)
                frameCache.trimToBytes((budget.frameCacheMaxBytes * level.retainFraction).toLong())
                thumbnailCache.clear()
                waveformCache.trimToBytes((budget.waveformCacheMaxBytes * level.retainFraction).toLong())
                VyroLogger.log(
                    LogSeverity.WARN,
                    "platform:android-media",
                    "Memory pressure CRITICAL: trimmed decode caches and cancelled background prefetch.",
                    "cache_manager",
                    EngineErrorCode.OK,
                )
            }
        }
        publishDiagnostics()
    }

    private fun generateProxyFile(
        assetId: String,
        originalUri: String,
        targetWidth: Int,
        targetHeight: Int,
        handle: PriorityTaskScheduler.CancellationHandle,
    ) {
        if (handle.isCancelled) return
        if (!proxyDir.exists()) proxyDir.mkdirs()

        val finalFile = File(proxyDir, "${assetId}_proxy.mp4")
        val tempFile = File(proxyDir, "${assetId}_proxy.mp4.tmp")
        proxyMappings[assetId] = ProxyMapping(
            assetId = assetId,
            originalUri = originalUri,
            proxyWidth = targetWidth,
            proxyHeight = targetHeight,
            status = ProxyStatus.GENERATING,
            progress = 0.1f,
        )
        publishDiagnostics()

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        handle.registerCleanup {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (tempFile.exists()) tempFile.delete()
        }

        try {
            val uri = Uri.parse(originalUri)
            val ext = MediaExtractor()
            extractor = ext
            ext.setDataSource(appContext, uri, null)

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until ext.trackCount) {
                val fmt = ext.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = fmt
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null || handle.isCancelled) {
                markProxyEnded(assetId, originalUri, handle.isCancelled, "No video track for proxy")
                return
            }

            ext.selectTrack(videoTrackIndex)
            val mx = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val outTrackIdx = mx.addTrack(videoFormat)
            mx.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var copiedSamples = 0
            while (!handle.isCancelled && copiedSamples < 900) {
                val sampleSize = ext.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = ext.sampleTime.coerceAtLeast(0L)
                info.flags = if ((ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                mx.writeSampleData(outTrackIdx, buffer, info)
                ext.advance()
                copiedSamples++
            }

            if (handle.isCancelled) {
                markProxyEnded(assetId, originalUri, cancelled = true, note = "Cancelled during proxy remux")
                return
            }

            runCatching { mx.stop() }
            runCatching { mx.release() }
            muxer = null
            runCatching { ext.release() }
            extractor = null

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)
                enforceProxyDiskBudgetLocked(preserveFile = finalFile)
                proxyMappings[assetId] = ProxyMapping(
                    assetId = assetId,
                    originalUri = originalUri,
                    proxyUri = Uri.fromFile(finalFile).toString(),
                    proxyWidth = targetWidth,
                    proxyHeight = targetHeight,
                    fileSizeBytes = finalFile.length(),
                    status = ProxyStatus.READY,
                    progress = 1.0f,
                )
            } else {
                markProxyEnded(assetId, originalUri, cancelled = false, note = "Empty proxy output")
            }
        } catch (t: Throwable) {
            markProxyEnded(
                assetId = assetId,
                originalUri = originalUri,
                cancelled = handle.isCancelled,
                note = t.message ?: t::class.java.simpleName,
            )
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
            if (tempFile.exists()) tempFile.delete()
            publishDiagnostics()
        }
    }

    private fun markProxyEnded(assetId: String, originalUri: String, cancelled: Boolean, note: String) {
        val current = proxyMappings[assetId]
        proxyMappings[assetId] = ProxyMapping(
            assetId = assetId,
            originalUri = originalUri,
            proxyWidth = current?.proxyWidth ?: 540,
            proxyHeight = current?.proxyHeight ?: 960,
            status = if (cancelled) ProxyStatus.CANCELLED else ProxyStatus.FAILED,
            progress = 0f,
            errorNote = note,
        )
    }

    private fun enforceProxyDiskBudgetLocked(preserveFile: File) {
        val files = proxyDir.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= budget.proxyDiskMaxBytes) return

        val evictOrder = files
            .filter { it.absolutePath != preserveFile.absolutePath }
            .sortedBy { it.lastModified() }
        for (oldFile in evictOrder) {
            if (total <= budget.proxyDiskMaxBytes) break
            val size = oldFile.length()
            if (oldFile.delete()) {
                total = max(0L, total - size)
            }
        }
    }

    /**
     * Exposed primarily for JVM/Robolectric tests to register a synthetic or pre-generated proxy file cleanly.
     */
    fun registerReadyProxyForTest(assetId: String, originalUri: String, proxyFile: File, width: Int = 540, height: Int = 960) {
        proxyMappings[assetId] = ProxyMapping(
            assetId = assetId,
            originalUri = originalUri,
            proxyUri = Uri.fromFile(proxyFile).toString(),
            proxyWidth = width,
            proxyHeight = height,
            fileSizeBytes = proxyFile.length(),
            status = ProxyStatus.READY,
            progress = 1.0f,
        )
        publishDiagnostics()
    }

    private fun publishDiagnostics() {
        val mappings = proxyMappings.values.toList()
        val diskBytes = proxyDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val (pressure, trims) = synchronized(lock) { currentPressureLevel to trimEventCount }
        _diagnostics.value = CacheMemoryDiagnosticsSnapshot(
            frameCache = frameCache.stats(),
            thumbnailCache = thumbnailCache.stats(),
            waveformCache = waveformCache.stats(),
            proxyEnabled = _proxyEnabled.value,
            proxyMappingsCount = mappings.size,
            proxyReadyCount = mappings.count { it.status == ProxyStatus.READY },
            proxyDiskBytes = min(diskBytes, Long.MAX_VALUE),
            proxyDiskBudgetBytes = budget.proxyDiskMaxBytes,
            memoryPressureLevel = pressure,
            trimEventCount = trims,
            scheduler = scheduler.diagnostics.value,
        )
    }

    override fun release() {
        scheduler.release()
        frameCache.clear()
        thumbnailCache.clear()
        waveformCache.clear()
        publishDiagnostics()
    }
}

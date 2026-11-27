package com.vyro.platform.media

import android.view.Surface
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.AudioPlaybackEngineContract
import com.vyro.core.contracts.BoundedFrameQueue
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.CompositionRendererContract
import com.vyro.core.contracts.DecodedFrameToken
import com.vyro.core.contracts.DecoderBackendType
import com.vyro.core.contracts.FrameCacheEntry
import com.vyro.core.contracts.FrameCacheKey
import com.vyro.core.contracts.MasterPlaybackClock
import com.vyro.core.contracts.PlaybackControllerContract
import com.vyro.core.contracts.PlaybackDiagnosticsSnapshot
import com.vyro.core.contracts.PlaybackState
import com.vyro.core.contracts.RenderGraphEvaluator
import com.vyro.core.contracts.SeekGenerationTracker
import com.vyro.core.contracts.TimelineEvaluator
import com.vyro.core.contracts.VideoFrameDecoderContract
import com.vyro.core.model.MediaKind
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.TrackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates MasterPlaybackClock, SeekGenerationTracker, BoundedFrameQueue,
 * and VideoFrameDecoderContract with safe preview Surface attach/detach lifecycles.
 */
class AndroidPlaybackCoordinator(
    private val decoder: VideoFrameDecoderContract,
    private val dispatchers: DispatchersProvider,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() },
    private val autoSchedulePlayback: Boolean = true,
    private val compositionRenderer: CompositionRendererContract? = null,
    private val audioEngine: AudioPlaybackEngineContract? = null,
    private val cacheManager: CacheAndProxyManagerContract? = null,
) : PlaybackControllerContract {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val stateLock = Any()

    private val seekTracker = SeekGenerationTracker()
    private val clock = MasterPlaybackClock(PlaybackDiagnosticsSnapshot.AV_SYNC_TOLERANCE_US)
    private val frameQueue = BoundedFrameQueue<DecodedFrameToken>(
        capacity = PlaybackDiagnosticsSnapshot.DEFAULT_QUEUE_CAPACITY,
        generationOf = { it.seekGeneration },
    )

    private var currentSequence: Sequence? = null
    private var attachedSurface: Surface? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var activeDecodeJob: Job? = null
    private var playbackLoopJob: Job? = null
    private var decodedFrameCount: Long = 0L
    private var lastFrameTickScheduledUs: Long = -1L

    private val _telemetry = MutableStateFlow(PlaybackDiagnosticsSnapshot())
    override val telemetry: StateFlow<PlaybackDiagnosticsSnapshot> = _telemetry.asStateFlow()

    override fun updateSequence(sequence: Sequence) {
        val duration = sequence.durationUs.coerceAtLeast(sequence.frameRate.frameDurationUs)
        synchronized(stateLock) {
            currentSequence = sequence
            clock.configure(sequence.frameRate, duration)
        }
        audioEngine?.updateSequence(sequence)
        scheduleDecodeForPosition(clock.currentPositionUs(), seekTracker.currentGeneration())
        publishSnapshot(playbackState = if (clock.isPlaying()) PlaybackState.PLAYING else PlaybackState.PAUSED)
    }

    override fun attachPreviewSurface(surface: Surface, width: Int, height: Int) {
        synchronized(stateLock) {
            attachedSurface = surface
            surfaceWidth = width
            surfaceHeight = height
        }
        decoder.attachSurface(surface, width, height)
        compositionRenderer?.attachSurface(surface, width, height)
        val gen = seekTracker.beginSeek(clock.currentPositionUs())
        frameQueue.flushForGeneration(gen)
        scheduleDecodeForPosition(clock.currentPositionUs(), gen)
        publishSnapshot()
    }

    override fun detachPreviewSurface(surface: Surface) {
        val shouldDetach = synchronized(stateLock) {
            if (attachedSurface === surface) {
                attachedSurface = null
                surfaceWidth = 0
                surfaceHeight = 0
                true
            } else {
                // New surface was already swapped in (common during SurfaceView recreation); ignore stale destroy.
                false
            }
        }
        if (shouldDetach) {
            activeDecodeJob?.cancel()
            decoder.attachSurface(null, 0, 0)
            compositionRenderer?.detachSurface(surface)
            publishSnapshot()
        }
    }

    override fun seekTo(timelineUs: Long, frameAccurate: Boolean): Long {
        val resolved = clock.seek(
            positionUs = timelineUs,
            nowNanos = nanoTimeProvider(),
            snapToFrame = frameAccurate,
        )
        val gen = seekTracker.beginSeek(resolved)
        activeDecodeJob?.cancel()
        frameQueue.flushForGeneration(gen)
        lastFrameTickScheduledUs = resolved
        audioEngine?.seek(resolved)
        scheduleDecodeForPosition(resolved, gen)
        publishSnapshot(
            playbackState = if (clock.isPlaying()) PlaybackState.PLAYING else PlaybackState.PAUSED,
        )
        return gen
    }

    override fun play() {
        val seq = synchronized(stateLock) { currentSequence }
        val startedUs = clock.start(nanoTimeProvider())
        val gen = seekTracker.beginSeek(startedUs)
        frameQueue.flushForGeneration(gen)
        lastFrameTickScheduledUs = -1L
        audioEngine?.start(startedUs)
        scheduleDecodeForPosition(startedUs, gen)
        publishSnapshot(playbackState = PlaybackState.PLAYING)

        if (autoSchedulePlayback) {
            playbackLoopJob?.cancel()
            val stepMs = ((seq?.frameRate ?: Rational.FPS_30).frameDurationUs / 1_000L).coerceIn(16L, 42L)
            playbackLoopJob = scope.launch {
                while (isActive && clock.isPlaying()) {
                    delay(stepMs)
                    advanceFrameTick(nanoTimeProvider())
                }
            }
        }
    }

    override fun pause() {
        playbackLoopJob?.cancel()
        playbackLoopJob = null
        clock.pause(nanoTimeProvider())
        audioEngine?.pause()
        publishSnapshot(playbackState = PlaybackState.PAUSED)
    }

    override fun stepFrame(forward: Boolean): Long {
        playbackLoopJob?.cancel()
        playbackLoopJob = null
        val steppedUs = clock.stepFrame(forward, nanoTimeProvider())
        val gen = seekTracker.beginSeek(steppedUs)
        activeDecodeJob?.cancel()
        frameQueue.flushForGeneration(gen)
        lastFrameTickScheduledUs = steppedUs
        audioEngine?.pause()
        audioEngine?.seek(steppedUs)
        scheduleDecodeForPosition(steppedUs, gen)
        publishSnapshot(playbackState = PlaybackState.PAUSED)
        return steppedUs
    }

    override fun advanceFrameTick(nowNanos: Long) {
        val reading = clock.advance(nowNanos)
        if (reading.reachedEnd) {
            playbackLoopJob?.cancel()
            playbackLoopJob = null
            val resetGen = seekTracker.beginSeek(0L)
            clock.seek(0L, nowNanos = nowNanos, snapToFrame = true)
            frameQueue.flushForGeneration(resetGen)
            audioEngine?.pause()
            audioEngine?.seek(0L)
            scheduleDecodeForPosition(0L, resetGen)
            publishSnapshot(playbackState = PlaybackState.PAUSED, clockReading = reading.copy(positionUs = 0L))
            return
        }

        val seq = synchronized(stateLock) { currentSequence }
        val minStepUs = (seq?.frameRate?.frameDurationUs ?: 33_333L) / 2L
        if (lastFrameTickScheduledUs < 0L || kotlin.math.abs(reading.positionUs - lastFrameTickScheduledUs) >= minStepUs) {
            lastFrameTickScheduledUs = reading.positionUs
            scheduleDecodeForPosition(reading.positionUs, seekTracker.currentGeneration())
        }
        publishSnapshot(
            playbackState = if (clock.isPlaying()) PlaybackState.PLAYING else PlaybackState.PAUSED,
            clockReading = reading,
        )
    }

    override fun reportAudioClockUs(audioTimeUs: Long) {
        val reading = clock.reportAudioClock(audioTimeUs, nanoTimeProvider())
        publishSnapshot(
            playbackState = if (clock.isPlaying()) PlaybackState.PLAYING else PlaybackState.PAUSED,
            clockReading = reading,
        )
    }

    private fun scheduleDecodeForPosition(timelineUs: Long, seekGeneration: Long) {
        val seq = synchronized(stateLock) { currentSequence } ?: return
        compositionRenderer?.let { renderer ->
            val graph = RenderGraphEvaluator.evaluate(seq, timelineUs)
            renderer.renderGraph(graph)
        }
        val activeClips = TimelineEvaluator.activeClipsAt(seq, timelineUs)
        val videoClip = activeClips.firstOrNull { clip ->
            seq.tracks.any { it.id == clip.trackId && it.type == TrackType.VIDEO }
        } ?: return

        val rawUriString = videoClip.mediaSource.uriString
        if (videoClip.mediaSource.mediaKind != MediaKind.VIDEO || rawUriString.isNullOrBlank()) {
            // Material or static photo layer — deterministic synthetic token without MediaCodec overhead
            if (seekTracker.acceptOrReject(seekGeneration)) {
                val synthetic = DecodedFrameToken(
                    clipId = videoClip.id,
                    uriString = rawUriString.orEmpty(),
                    sourcePtsUs = videoClip.timing.timelineToSourceUs(timelineUs) ?: 0L,
                    timelineUs = timelineUs,
                    seekGeneration = seekGeneration,
                    width = seq.width,
                    height = seq.height,
                    rotationDegrees = 0,
                    isKeyFrame = true,
                    backend = DecoderBackendType.MATERIAL_SYNTHETIC,
                )
                frameQueue.enqueue(synthetic)
                decodedFrameCount++
            }
            return
        }

        val resolvedUri = cacheManager?.resolvePlaybackUri(rawUriString, videoClip.mediaSource.assetId) ?: rawUriString
        val usingProxy = resolvedUri != rawUriString
        val sourcePtsUs = videoClip.timing.timelineToSourceUs(timelineUs) ?: return
        val targetW = synchronized(stateLock) { surfaceWidth }.takeIf { it > 0 } ?: 720
        val targetH = synchronized(stateLock) { surfaceHeight }.takeIf { it > 0 } ?: 720

        val cacheKey = FrameCacheKey(
            sourceUri = resolvedUri,
            sourcePtsUs = sourcePtsUs,
            width = targetW,
            height = targetH,
            isProxy = usingProxy,
        )
        cacheManager?.getFrame(cacheKey)?.let { cachedEntry ->
            if (seekTracker.acceptOrReject(seekGeneration)) {
                val retagged = cachedEntry.token.copy(
                    clipId = videoClip.id,
                    timelineUs = timelineUs,
                    seekGeneration = seekGeneration,
                )
                frameQueue.enqueue(retagged)
                synchronized(stateLock) { decodedFrameCount++ }
                publishSnapshot(lastError = null)
                return
            }
        }

        activeDecodeJob?.cancel()
        activeDecodeJob = scope.launch {
            if (!seekTracker.isCurrent(seekGeneration)) {
                seekTracker.acceptOrReject(seekGeneration)
                return@launch
            }
            val res = decoder.decodeFrameAt(
                clipId = videoClip.id,
                uriString = resolvedUri,
                sourcePtsUs = sourcePtsUs,
                timelineUs = timelineUs,
                seekGeneration = seekGeneration,
                isCurrentGeneration = { gen -> seekTracker.isCurrent(gen) },
                targetWidth = targetW,
                targetHeight = targetH,
            )
            when (res) {
                is EngineResult.Success -> {
                    val token = res.value
                    cacheManager?.putFrame(
                        FrameCacheEntry(
                            key = cacheKey,
                            token = token,
                            byteSize = 4096L,
                        ),
                    )
                    if (seekTracker.acceptOrReject(token.seekGeneration)) {
                        frameQueue.enqueue(token)
                        synchronized(stateLock) { decodedFrameCount++ }
                        publishSnapshot(lastError = null)
                    } else {
                        publishSnapshot()
                    }
                }
                is EngineResult.Failure -> {
                    if (seekTracker.isCurrent(seekGeneration)) {
                        VyroLogger.log(
                            LogSeverity.WARN,
                            "platform:android-media",
                            res.error.userMessage,
                            videoClip.id,
                            res.error.code,
                        )
                        publishSnapshot(lastError = res.error.userMessage)
                    } else {
                        // Cancelled stale seek
                        seekTracker.acceptOrReject(seekGeneration)
                        publishSnapshot()
                    }
                }
            }
        }
    }

    private fun publishSnapshot(
        playbackState: PlaybackState = if (clock.isPlaying()) PlaybackState.PLAYING else PlaybackState.PAUSED,
        clockReading: MasterPlaybackClock.ClockReading? = null,
        lastError: String? = _telemetry.value.lastError,
    ) {
        val reading = clockReading ?: clock.advance(nanoTimeProvider())
        val queueStats = frameQueue.stats()
        val hasSurface = synchronized(stateLock) { attachedSurface?.isValid == true }
        val decodedCount = synchronized(stateLock) { decodedFrameCount }
        val seq = synchronized(stateLock) { currentSequence }
        val hasVideoClip = seq?.tracks?.any { track ->
            track.type == TrackType.VIDEO && track.clips.any { it.mediaSource.mediaKind == MediaKind.VIDEO }
        } == true

        _telemetry.value = PlaybackDiagnosticsSnapshot(
            timelinePositionUs = reading.positionUs,
            audioClockUs = reading.audioClockUs,
            avDriftUs = reading.signedDriftUs,
            maxObservedAbsDriftUs = reading.maxAbsDriftUs,
            withinAvTolerance = reading.withinTolerance,
            seekGeneration = seekTracker.currentGeneration(),
            droppedFrames = queueStats.droppedCount,
            rejectedStaleFrames = seekTracker.rejectedStaleFrames() + queueStats.staleRejectedCount,
            decodedFrames = decodedCount,
            queueDepth = queueStats.size,
            queueCapacity = queueStats.capacity,
            surfaceAttached = hasSurface,
            activeDecoderBackend = if (hasVideoClip) decoder.backendType else DecoderBackendType.MATERIAL_SYNTHETIC,
            playbackState = playbackState,
            lastError = lastError,
        )
    }

    override fun release() {
        playbackLoopJob?.cancel()
        playbackLoopJob = null
        activeDecodeJob?.cancel()
        activeDecodeJob = null
        synchronized(stateLock) {
            attachedSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
        }
        frameQueue.clear()
        decoder.release()
        compositionRenderer?.release()
        audioEngine?.release()
        scope.cancel()
    }
}

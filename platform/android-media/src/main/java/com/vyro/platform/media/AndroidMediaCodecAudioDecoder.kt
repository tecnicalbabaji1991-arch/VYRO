package com.vyro.platform.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.AudioSourceContract
import com.vyro.core.contracts.AudioStreamFormat
import com.vyro.core.contracts.BoundedByteLruCache
import com.vyro.core.contracts.CacheAndProxyManagerContract
import com.vyro.core.contracts.PcmAudioBuffer
import com.vyro.core.contracts.PcmAudioResampler
import com.vyro.core.contracts.WaveformCacheEntry
import com.vyro.core.contracts.WaveformCacheKey
import com.vyro.core.contracts.WaveformExtractor
import com.vyro.core.contracts.WaveformProfile
import com.vyro.core.model.Clip
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Android 11+ MediaExtractor + MediaCodec PCM audio decoder for external media URIs,
 * plus deterministic studio-beat synthesis for bundled audio clips without external URIs.
 */
class AndroidMediaCodecAudioDecoder(
    private val context: Context,
    private val cacheManager: CacheAndProxyManagerContract? = null,
) : AudioSourceContract {

    private val appContext = context.applicationContext
    private val localWaveformFallbackCache = BoundedByteLruCache<WaveformCacheKey, WaveformCacheEntry>(
        name = "LocalWaveforms",
        initialMaxBytes = 2L * 1024L * 1024L,
        weightOf = { it.byteSize },
    )
    private val decodedPcmCache = BoundedByteLruCache<String, PcmAudioBuffer>(
        name = "DecodedPcm",
        initialMaxBytes = 24L * 1024L * 1024L,
        weightOf = { (it.samples.size.toLong() * 4L).coerceAtLeast(1024L) },
    )

    override fun decodeSegment(
        clip: Clip,
        sourceStartUs: Long,
        durationUs: Long,
        targetSampleRateHz: Int,
    ): EngineResult<PcmAudioBuffer> {
        val safeDurUs = durationUs.coerceAtLeast(1_000L)
        val uriString = clip.mediaSource.uriString
        if (uriString.isNullOrBlank()) {
            val synthesized = WaveformExtractor.synthesizeStudioBeatPcm(
                seedKey = clip.label.ifBlank { clip.id },
                startUs = sourceStartUs,
                durationUs = safeDurUs,
                sampleRateHz = targetSampleRateHz,
            )
            return EngineResult.Success(synthesized)
        }

        val fullBuffer = getOrDecodeUriBuffer(clip.id, uriString, maxDurationUs = 30_000_000L)
        if (fullBuffer == null || fullBuffer.frameCount == 0) {
            return EngineResult.Failure(
                EngineError(
                    code = EngineErrorCode.UNSUPPORTED_MEDIA,
                    sourceModule = "platform:android-media",
                    userMessage = "Audio stream could not be decoded for ${clip.label}.",
                    diagnosticDetail = "uri=$uriString",
                    recoverable = true,
                ),
            )
        }

        val startFrame = ((sourceStartUs.coerceAtLeast(0L) * fullBuffer.sampleRateHz) / 1_000_000L)
            .toInt()
            .coerceIn(0, fullBuffer.frameCount)
        val neededFrames = ((safeDurUs * fullBuffer.sampleRateHz) / 1_000_000L)
            .toInt()
            .coerceAtLeast(1)
        val endFrame = min(fullBuffer.frameCount, startFrame + neededFrames)
        val sliceFrames = max(0, endFrame - startFrame)
        val sliceSamples = FloatArray(sliceFrames * fullBuffer.channelCount)
        if (sliceFrames > 0) {
            System.arraycopy(
                fullBuffer.samples,
                startFrame * fullBuffer.channelCount,
                sliceSamples,
                0,
                sliceSamples.size,
            )
        }
        val slice = PcmAudioBuffer(
            sampleRateHz = fullBuffer.sampleRateHz,
            channelCount = fullBuffer.channelCount,
            samples = sliceSamples,
            startTimeUs = sourceStartUs,
        )
        return EngineResult.Success(PcmAudioResampler.resample(PcmAudioResampler.toStereo(slice), targetSampleRateHz))
    }

    override fun extractWaveform(clip: Clip, bucketCount: Int): WaveformProfile {
        val cacheKey = WaveformCacheKey(
            sourceId = clip.mediaSource.uriString ?: clip.id,
            sourceInUs = clip.timing.sourceInUs,
            sourceOutUs = clip.timing.sourceOutUs,
            bucketCount = bucketCount,
        )
        val cached = cacheManager?.getWaveform(cacheKey)?.profile
            ?: localWaveformFallbackCache.get(cacheKey)?.profile
        if (cached != null) return cached

        val rawSourceDurUs = (clip.timing.sourceOutUs - clip.timing.sourceInUs).coerceAtLeast(0L)
        val uriString = clip.mediaSource.uriString
        val profile = if (uriString.isNullOrBlank()) {
            val dur = rawSourceDurUs.coerceIn(500_000L, 8_000_000L)
            val synth = WaveformExtractor.synthesizeStudioBeatPcm(
                seedKey = clip.label.ifBlank { clip.id },
                startUs = clip.timing.sourceInUs,
                durationUs = dur,
                sampleRateHz = 8_000, // Low analysis rate keeps UI waveform extraction fast
            )
            WaveformExtractor.fromPcmBuffer(clip.id, synth, bucketCount)
        } else {
            val decoded = getOrDecodeUriBuffer(clip.id, uriString, maxDurationUs = 20_000_000L)
            if (decoded != null && decoded.frameCount > 0) {
                WaveformExtractor.fromPcmBuffer(clip.id, decoded, bucketCount)
            } else {
                val fallback = WaveformExtractor.synthesizeStudioBeatPcm(
                    seedKey = clip.label.ifBlank { clip.id },
                    startUs = 0L,
                    durationUs = rawSourceDurUs.coerceIn(500_000L, 4_000_000L),
                    sampleRateHz = 8_000,
                )
                WaveformExtractor.fromPcmBuffer(clip.id, fallback, bucketCount)
            }
        }
        val entry = WaveformCacheEntry(
            key = cacheKey,
            profile = profile,
            byteSize = (profile.peaks.size.toLong() * 4L + 128L),
        )
        if (cacheManager != null) {
            cacheManager.putWaveform(entry)
        } else {
            localWaveformFallbackCache.put(cacheKey, entry)
        }
        return profile
    }

    private fun getOrDecodeUriBuffer(
        clipId: String,
        uriString: String,
        maxDurationUs: Long,
    ): PcmAudioBuffer? {
        decodedPcmCache.get(uriString)?.let { return it }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, uri, null)
            var audioIndex = -1
            var audioFormat: MediaFormat? = null
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val m = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("audio/")) {
                    audioIndex = i
                    audioFormat = fmt
                    mime = m
                    break
                }
            }
            if (audioIndex < 0 || audioFormat == null) {
                return null
            }
            extractor.selectTrack(audioIndex)

            var outSampleRate = runCatching { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(AudioStreamFormat.CANONICAL_SAMPLE_RATE_HZ)
            var outChannels = runCatching { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(AudioStreamFormat.CANONICAL_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val chunks = ArrayList<ShortArray>()
            var totalSamples = 0
            val maxFrames = ((maxDurationUs * outSampleRate) / 1_000_000L).toInt().coerceAtLeast(4096)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var iterations = 0

            while (!outputDone && iterations < 2000) {
                iterations++
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        outSampleRate = runCatching { newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                            .getOrDefault(outSampleRate)
                        outChannels = runCatching { newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                            .getOrDefault(outChannels)
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val arr = ShortArray(shortBuf.remaining())
                            shortBuf.get(arr)
                            chunks.add(arr)
                            totalSamples += arr.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            (totalSamples / outChannels.coerceAtLeast(1)) >= maxFrames
                        ) {
                            outputDone = true
                        }
                    }
                }
            }

            if (totalSamples == 0) return null
            val mergedShorts = ShortArray(totalSamples)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, mergedShorts, offset, chunk.size)
                offset += chunk.size
            }
            val rawBuffer = PcmAudioBuffer(
                sampleRateHz = outSampleRate.coerceAtLeast(8_000),
                channelCount = outChannels.coerceAtLeast(1),
                samples = PcmAudioResampler.pcm16ToFloatArray(mergedShorts),
                startTimeUs = 0L,
            )
            val canonical = PcmAudioResampler.convertToCanonical(rawBuffer)
            decodedPcmCache.put(uriString, canonical)
            return canonical
        } catch (t: Throwable) {
            VyroLogger.log(
                LogSeverity.WARN,
                "platform:android-media",
                "Audio decode fallback for $clipId: ${t.message ?: t::class.java.simpleName}",
                clipId,
                EngineErrorCode.UNSUPPORTED_MEDIA,
            )
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    override fun release() {
        decodedPcmCache.clear()
        localWaveformFallbackCache.clear()
    }
}

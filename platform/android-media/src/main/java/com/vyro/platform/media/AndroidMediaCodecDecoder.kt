package com.vyro.platform.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Surface
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.DecodedFrameToken
import com.vyro.core.contracts.DecodedStreamInfo
import com.vyro.core.contracts.DecoderBackendType
import com.vyro.core.contracts.VideoFrameDecoderContract
import java.io.IOException

/**
 * Android 11+ (API 30-36) hardware/software MediaExtractor + MediaCodec Surface video decoder
 * with seek-generation cancellation, stale-frame rejection, and MediaMetadataRetriever fallback.
 */
class AndroidMediaCodecDecoder(
    private val context: Context,
) : VideoFrameDecoderContract {

    private val appContext = context.applicationContext
    private val codecLock = Any()

    private var activeUriString: String? = null
    private var activeStreamInfo: DecodedStreamInfo? = null
    private var extractor: MediaExtractor? = null
    private var videoTrackIndex: Int = -1
    private var videoFormat: MediaFormat? = null
    private var codec: MediaCodec? = null
    private var attachedSurface: Surface? = null
    private var attachedSurfaceWidth: Int = 0
    private var attachedSurfaceHeight: Int = 0
    private var codecConfiguredForSurface: Boolean = false
    private var lastDecodedSourcePtsUs: Long = -1L

    override var backendType: DecoderBackendType = DecoderBackendType.MEDIA_CODEC_SURFACE
        private set

    override fun openSource(uriString: String): EngineResult<DecodedStreamInfo> = synchronized(codecLock) {
        if (uriString == activeUriString && activeStreamInfo != null) {
            return EngineResult.Success(activeStreamInfo!!)
        }
        releaseSourceLocked()

        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return decoderFailure("Invalid media URI for playback decoder.", uriString)

        val newExtractor = MediaExtractor()
        try {
            newExtractor.setDataSource(appContext, uri, null)
            var foundIndex = -1
            var foundFormat: MediaFormat? = null
            var mime = ""
            for (i in 0 until newExtractor.trackCount) {
                val fmt = newExtractor.getTrackFormat(i)
                val trackMime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("video/")) {
                    foundIndex = i
                    foundFormat = fmt
                    mime = trackMime
                    break
                }
            }
            if (foundIndex < 0 || foundFormat == null) {
                runCatching { newExtractor.release() }
                return decoderFailure("No video track available in source.", uriString)
            }
            newExtractor.selectTrack(foundIndex)

            val width = readIntSafe(foundFormat, MediaFormat.KEY_WIDTH, 1280)
            val height = readIntSafe(foundFormat, MediaFormat.KEY_HEIGHT, 720)
            val rotation = readIntSafe(foundFormat, MediaFormat.KEY_ROTATION, 0)
            val durationUs = readLongSafe(foundFormat, MediaFormat.KEY_DURATION, 4_000_000L).coerceAtLeast(1_000_000L)
            val fps = readFloatSafe(foundFormat, MediaFormat.KEY_FRAME_RATE)?.takeIf { it > 0f } ?: 30f
            val hardwareSupported = hasHardwareDecoder(mime)

            val info = DecodedStreamInfo(
                uriString = uriString,
                videoCodec = mime,
                width = width,
                height = height,
                rotationDegrees = rotation,
                durationUs = durationUs,
                nominalFps = fps,
                isHardwareAccelerated = hardwareSupported,
            )

            extractor = newExtractor
            videoTrackIndex = foundIndex
            videoFormat = foundFormat
            activeUriString = uriString
            activeStreamInfo = info
            lastDecodedSourcePtsUs = -1L
            backendType = DecoderBackendType.MEDIA_CODEC_SURFACE
            return EngineResult.Success(info)
        } catch (ex: SecurityException) {
            runCatching { newExtractor.release() }
            return EngineResult.Failure(
                EngineError(
                    code = EngineErrorCode.PERMISSION_REVOKED,
                    sourceModule = "platform:android-media",
                    userMessage = "Read permission missing for preview playback.",
                    diagnosticDetail = ex.message ?: uriString,
                    recoverable = true,
                ),
            )
        } catch (ex: IOException) {
            runCatching { newExtractor.release() }
            return decoderFailure("Unable to read video stream for playback.", ex.message ?: uriString)
        } catch (ex: Exception) {
            runCatching { newExtractor.release() }
            return decoderFailure("Playback extractor initialization failed.", ex.message ?: uriString)
        }
    }

    override fun attachSurface(surface: Surface?, width: Int, height: Int) = synchronized(codecLock) {
        val validSurface = surface?.takeIf { it.isValid }
        val surfaceChanged = attachedSurface !== validSurface
        attachedSurface = validSurface
        attachedSurfaceWidth = width
        attachedSurfaceHeight = height

        if (surfaceChanged) {
            // Never call MediaCodec.setOutputSurface(null) on API 30+ vendor HALs; tear down cleanly instead.
            releaseCodecOnlyLocked()
            lastDecodedSourcePtsUs = -1L
        }
    }

    override fun decodeFrameAt(
        clipId: String,
        uriString: String,
        sourcePtsUs: Long,
        timelineUs: Long,
        seekGeneration: Long,
        isCurrentGeneration: (Long) -> Boolean,
        targetWidth: Int,
        targetHeight: Int,
    ): EngineResult<DecodedFrameToken> = synchronized(codecLock) {
        if (!isCurrentGeneration(seekGeneration)) {
            return staleSeekResult(seekGeneration)
        }

        val info = when (val opened = openSource(uriString)) {
            is EngineResult.Success -> opened.value
            is EngineResult.Failure -> return opened
        }

        if (!isCurrentGeneration(seekGeneration)) {
            return staleSeekResult(seekGeneration)
        }

        val surface = attachedSurface?.takeIf { it.isValid }
        if (surface != null) {
            val codecResult = decodeViaMediaCodecLocked(
                clipId = clipId,
                uriString = uriString,
                info = info,
                surface = surface,
                targetSourceUs = sourcePtsUs.coerceIn(0L, info.durationUs),
                timelineUs = timelineUs,
                seekGeneration = seekGeneration,
                isCurrentGeneration = isCurrentGeneration,
            )
            if (codecResult != null) {
                return codecResult
            }
        }

        // Safe fallback when headless or if a device-specific vendor MediaCodec stalls
        backendType = DecoderBackendType.MEDIA_METADATA_FALLBACK
        if (!isCurrentGeneration(seekGeneration)) {
            return staleSeekResult(seekGeneration)
        }

        val fallbackOk = verifyFrameViaRetriever(uriString, sourcePtsUs, targetWidth, targetHeight)
        if (!isCurrentGeneration(seekGeneration)) {
            return staleSeekResult(seekGeneration)
        }

        return if (fallbackOk) {
            EngineResult.Success(
                DecodedFrameToken(
                    clipId = clipId,
                    uriString = uriString,
                    sourcePtsUs = sourcePtsUs,
                    timelineUs = timelineUs,
                    seekGeneration = seekGeneration,
                    width = info.width,
                    height = info.height,
                    rotationDegrees = info.rotationDegrees,
                    isKeyFrame = true,
                    backend = DecoderBackendType.MEDIA_METADATA_FALLBACK,
                ),
            )
        } else {
            decoderFailure("No valid preview frame decoded at ${sourcePtsUs}us", uriString)
        }
    }

    private fun decodeViaMediaCodecLocked(
        clipId: String,
        uriString: String,
        info: DecodedStreamInfo,
        surface: Surface,
        targetSourceUs: Long,
        timelineUs: Long,
        seekGeneration: Long,
        isCurrentGeneration: (Long) -> Boolean,
    ): EngineResult<DecodedFrameToken>? {
        val ext = extractor ?: return null
        val fmt = videoFormat ?: return null

        val activeCodec = ensureConfiguredCodecLocked(fmt, info.videoCodec, surface) ?: return null
        backendType = DecoderBackendType.MEDIA_CODEC_SURFACE

        return try {
            val frameWindowUs = (1_000_000f / info.nominalFps.coerceAtLeast(1f)).toLong().coerceAtLeast(8_000L)
            val needSeek = lastDecodedSourcePtsUs < 0L ||
                targetSourceUs < lastDecodedSourcePtsUs ||
                (targetSourceUs - lastDecodedSourcePtsUs) > 750_000L

            if (needSeek) {
                activeCodec.flush()
                ext.seekTo(targetSourceUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var decodedToken: DecodedFrameToken? = null
            var loopGuard = 0

            while (!sawOutputEos && decodedToken == null && loopGuard++ < MAX_DECODE_ITERATIONS) {
                if (!isCurrentGeneration(seekGeneration) || attachedSurface !== surface || !surface.isValid) {
                    return staleSeekResult(seekGeneration)
                }

                if (!sawInputEos) {
                    val inIdx = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = activeCodec.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            activeCodec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        } else {
                            val sampleSize = ext.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                activeCodec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                val ptsUs = ext.sampleTime.coerceAtLeast(0L)
                                val flags = if ((ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                                } else {
                                    0
                                }
                                activeCodec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, flags)
                                ext.advance()
                            }
                        }
                    }
                }

                val outIdx = activeCodec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                        val ptsUs = bufferInfo.presentationTimeUs
                        val isTargetReached = ptsUs + (frameWindowUs / 2L) >= targetSourceUs || sawOutputEos
                        val stillValid = isCurrentGeneration(seekGeneration) && attachedSurface === surface && surface.isValid

                        if (isTargetReached && stillValid && bufferInfo.size >= 0) {
                            activeCodec.releaseOutputBuffer(outIdx, true)
                            lastDecodedSourcePtsUs = ptsUs
                            decodedToken = DecodedFrameToken(
                                clipId = clipId,
                                uriString = uriString,
                                sourcePtsUs = ptsUs,
                                timelineUs = timelineUs,
                                seekGeneration = seekGeneration,
                                width = info.width,
                                height = info.height,
                                rotationDegrees = info.rotationDegrees,
                                isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                backend = DecoderBackendType.MEDIA_CODEC_SURFACE,
                            )
                        } else {
                            activeCodec.releaseOutputBuffer(outIdx, false)
                            if (!stillValid) {
                                return staleSeekResult(seekGeneration)
                            }
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Surface scales automatically via MediaCodec video scaling mode
                    }
                }
            }

            decodedToken?.let { EngineResult.Success(it) }
        } catch (ex: Exception) {
            VyroLogger.log(
                LogSeverity.WARN,
                "platform:android-media",
                "MediaCodec decode fallback triggered: ${ex.message}",
                clipId,
            )
            releaseCodecOnlyLocked()
            null
        }
    }

    private fun ensureConfiguredCodecLocked(
        format: MediaFormat,
        mime: String,
        surface: Surface,
    ): MediaCodec? {
        if (codec != null && codecConfiguredForSurface) {
            return codec
        }
        releaseCodecOnlyLocked()

        // Prefer standard decoder first, then explicitly fall back to software decoder on API 30+ devices
        val candidates = buildList {
            add(null) // default createDecoderByType
            findSoftwareDecoderName(mime)?.let { add(it) }
        }

        for (explicitName in candidates) {
            var candidateCodec: MediaCodec? = null
            try {
                candidateCodec = if (explicitName == null) {
                    MediaCodec.createDecoderByType(mime)
                } else {
                    MediaCodec.createByCodecName(explicitName)
                }
                candidateCodec.configure(format, surface, null, 0)
                candidateCodec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                candidateCodec.start()
                codec = candidateCodec
                codecConfiguredForSurface = true
                return candidateCodec
            } catch (_: Exception) {
                runCatching { candidateCodec?.release() }
            }
        }
        return null
    }

    private fun verifyFrameViaRetriever(
        uriString: String,
        sourcePtsUs: Long,
        targetWidth: Int,
        targetHeight: Int,
    ): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val bmp = retriever.getScaledFrameAtTime(
                sourcePtsUs.coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth.coerceIn(128, 720),
                targetHeight.coerceIn(128, 720),
            ) ?: retriever.getFrameAtTime(sourcePtsUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp != null) {
                bmp.recycle()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun hasHardwareDecoder(mime: String): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun findSoftwareDecoderName(mime: String): String? {
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder &&
                    info.isSoftwareOnly &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }?.name
        }.getOrNull()
    }

    override fun releaseSource() = synchronized(codecLock) {
        releaseSourceLocked()
    }

    override fun release() = synchronized(codecLock) {
        attachedSurface = null
        releaseSourceLocked()
    }

    private fun releaseSourceLocked() {
        releaseCodecOnlyLocked()
        runCatching { extractor?.release() }
        extractor = null
        videoTrackIndex = -1
        videoFormat = null
        activeUriString = null
        activeStreamInfo = null
        lastDecodedSourcePtsUs = -1L
    }

    private fun releaseCodecOnlyLocked() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecConfiguredForSurface = false
    }

    private fun readIntSafe(format: MediaFormat, key: String, fallback: Int): Int {
        if (!format.containsKey(key)) return fallback
        return runCatching {
            when (format.getValueTypeForKey(key)) {
                MediaFormat.TYPE_INTEGER -> format.getInteger(key)
                MediaFormat.TYPE_LONG -> format.getLong(key).toInt()
                MediaFormat.TYPE_FLOAT -> format.getFloat(key).toInt()
                else -> format.getInteger(key)
            }
        }.getOrDefault(fallback)
    }

    private fun readLongSafe(format: MediaFormat, key: String, fallback: Long): Long {
        if (!format.containsKey(key)) return fallback
        return runCatching {
            when (format.getValueTypeForKey(key)) {
                MediaFormat.TYPE_LONG -> format.getLong(key)
                MediaFormat.TYPE_INTEGER -> format.getInteger(key).toLong()
                MediaFormat.TYPE_FLOAT -> format.getFloat(key).toLong()
                else -> format.getLong(key)
            }
        }.getOrDefault(fallback)
    }

    private fun readFloatSafe(format: MediaFormat, key: String): Float? {
        if (!format.containsKey(key)) return null
        return runCatching {
            when (format.getValueTypeForKey(key)) {
                MediaFormat.TYPE_FLOAT -> format.getFloat(key)
                MediaFormat.TYPE_INTEGER -> format.getInteger(key).toFloat()
                MediaFormat.TYPE_LONG -> format.getLong(key).toFloat()
                else -> format.getFloat(key)
            }
        }.getOrNull()
    }

    private fun staleSeekResult(seekGeneration: Long): EngineResult.Failure = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.INVALID_STATE,
            sourceModule = "platform:android-media",
            userMessage = "Superseded by newer seek",
            diagnosticDetail = "Seek generation $seekGeneration cancelled",
            recoverable = true,
        ),
    )

    private fun decoderFailure(message: String, detail: String): EngineResult.Failure = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.UNSUPPORTED_MEDIA,
            sourceModule = "platform:android-media",
            userMessage = message,
            diagnosticDetail = detail,
            recoverable = true,
        ),
    )

    companion object {
        private const val CODEC_TIMEOUT_US = 8_000L
        private const val MAX_DECODE_ITERATIONS = 180
    }
}

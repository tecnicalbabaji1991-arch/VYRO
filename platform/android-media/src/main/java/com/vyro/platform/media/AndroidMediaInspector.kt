package com.vyro.platform.media

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.common.LogSeverity
import com.vyro.core.common.VyroLogger
import com.vyro.core.contracts.MediaInspectorContract
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Android platform media inspection, capability validation, and URI permission adapter.
 * Uses scoped Photo Picker / SAF URI grants without broad storage permissions.
 */
class AndroidMediaInspector(
    private val context: Context,
    private val dispatchers: DispatchersProvider,
) : MediaInspectorContract {

    private val appContext = context.applicationContext

    override suspend fun inspectUri(uriString: String): EngineResult<MediaAsset> = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext unsupportedMedia("Malformed media URI.", uriString)

        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

        // Probe actual readability first to surface permission loss or missing files immediately
        try {
            val afd = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd == null) {
                return@withContext missingMedia("Selected media file could not be opened.", uriString)
            }
            afd.use { descriptor ->
                runCatching { descriptor.parcelFileDescriptor?.close() }
            }
        } catch (sec: SecurityException) {
            return@withContext permissionRevoked("Read permission revoked for $uriString: ${sec.message}")
        } catch (fnf: FileNotFoundException) {
            return@withContext missingMedia("Media file no longer exists at $uriString: ${fnf.message}", uriString)
        } catch (ex: Exception) {
            return@withContext missingMedia("Unable to access media URI $uriString: ${ex.message}", uriString)
        }

        var displayName = "Imported Media"
        var fileSizeBytes = 0L
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx)?.substringBeforeLast('.')?.ifBlank { displayName } ?: displayName
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        fileSizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        }

        val resolverMime = appContext.contentResolver.getType(uri).orEmpty()
        if (resolverMime.startsWith("image/")) {
            return@withContext inspectImageAsset(uri, uriString, resolverMime, displayName, fileSizeBytes)
        }

        // Inspect actual container tracks via MediaExtractor rather than relying only on filename extension
        val extracted = extractMediaTrackMetadata(uri, resolverMime)
        if (extracted.videoCodec == null && extracted.audioCodec == null) {
            return@withContext unsupportedMedia(
                "No supported video or audio tracks found in $displayName.",
                "mime=$resolverMime uri=$uriString",
            )
        }

        val kind = if (extracted.videoCodec != null) MediaKind.VIDEO else MediaKind.AUDIO
        val codecToValidate = extracted.videoCodec ?: extracted.audioCodec.orEmpty()
        val codecOk = isDecoderSupported(codecToValidate)
        if (!codecOk) {
            return@withContext unsupportedMedia(
                "Device lacks a decoder for $codecToValidate.",
                "mime=$codecToValidate uri=$uriString",
            )
        }

        val asset = MediaAsset(
            assetId = "asset_${UUID.randomUUID().toString().take(8)}",
            uriString = uriString,
            displayName = displayName,
            mediaKind = kind,
            mimeType = resolverMime.ifBlank { codecToValidate },
            containerFormat = deriveContainerLabel(resolverMime, extracted.videoCodec ?: extracted.audioCodec),
            videoCodec = extracted.videoCodec,
            audioCodec = extracted.audioCodec,
            width = extracted.width,
            height = extracted.height,
            rotationDegrees = extracted.rotationDegrees,
            durationUs = extracted.durationUs.coerceAtLeast(1_000_000L),
            nominalFps = extracted.nominalFps,
            isVfrSuspected = extracted.isVfrSuspected,
            audioSampleRateHz = extracted.audioSampleRateHz,
            audioChannelCount = extracted.audioChannelCount,
            bitrateBps = extracted.bitrateBps,
            fileSizeBytes = fileSizeBytes,
            colorInfo = extracted.colorInfo,
            isHdr = extracted.isHdr,
            codecSupported = true,
            availability = AssetAvailability.AVAILABLE,
            diagnosticNote = "Validated on device",
            addedAtEpochMs = System.currentTimeMillis(),
        )
        VyroLogger.log(LogSeverity.INFO, "platform:android-media", "Registered asset ${asset.displayName} (${asset.mimeType})", asset.assetId)
        EngineResult.Success(asset)
    }

    override suspend fun revalidateAssets(assets: List<MediaAsset>): List<MediaAsset> = withContext(dispatchers.io) {
        assets.map { asset ->
            val uriStr = asset.uriString
            if (asset.mediaKind == MediaKind.MATERIAL || uriStr.isNullOrBlank()) {
                return@map asset.copy(availability = AssetAvailability.AVAILABLE)
            }
            val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
                ?: return@map asset.copy(
                    availability = AssetAvailability.MISSING,
                    diagnosticNote = "Invalid URI syntax",
                )
            val status = try {
                val afd = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                if (afd == null) {
                    AssetAvailability.MISSING to "File descriptor unavailable"
                } else {
                    afd.use { descriptor ->
                        runCatching { descriptor.parcelFileDescriptor?.close() }
                    }
                    val requiredMime = asset.videoCodec ?: asset.audioCodec
                    if (asset.mediaKind != MediaKind.PHOTO && !requiredMime.isNullOrBlank() && !isDecoderSupported(requiredMime)) {
                        AssetAvailability.UNSUPPORTED_CODEC to "No decoder for $requiredMime"
                    } else {
                        AssetAvailability.AVAILABLE to "Accessible"
                    }
                }
            } catch (sec: SecurityException) {
                AssetAvailability.PERMISSION_REVOKED to (sec.message ?: "Read grant revoked")
            } catch (fnf: FileNotFoundException) {
                AssetAvailability.MISSING to (fnf.message ?: "Source file removed or moved")
            } catch (ex: Exception) {
                AssetAvailability.MISSING to (ex.message ?: "Source unreachable")
            }
            asset.copy(availability = status.first, diagnosticNote = status.second)
        }
    }

    override suspend fun relinkAsset(oldAssetId: String, newUriString: String): EngineResult<MediaAsset> {
        return when (val inspected = inspectUri(newUriString)) {
            is EngineResult.Failure -> inspected
            is EngineResult.Success -> EngineResult.Success(inspected.value.copy(assetId = oldAssetId))
        }
    }

    private fun inspectImageAsset(
        uri: Uri,
        uriString: String,
        mimeType: String,
        displayName: String,
        fileSizeBytes: Long,
    ): EngineResult<MediaAsset> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
        val w = options.outWidth.coerceAtLeast(0)
        val h = options.outHeight.coerceAtLeast(0)
        if (w == 0 || h == 0) {
            return unsupportedMedia("Unreadable or corrupt image stream.", "mime=$mimeType uri=$uriString")
        }
        return EngineResult.Success(
            MediaAsset(
                assetId = "asset_${UUID.randomUUID().toString().take(8)}",
                uriString = uriString,
                displayName = displayName,
                mediaKind = MediaKind.PHOTO,
                mimeType = mimeType,
                containerFormat = mimeType.substringAfter('/').uppercase(),
                width = w,
                height = h,
                durationUs = 4_000_000L,
                fileSizeBytes = fileSizeBytes,
                codecSupported = true,
                availability = AssetAvailability.AVAILABLE,
                diagnosticNote = "Validated image bounds ${w}x$h",
                addedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private data class ExtractedTrackInfo(
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val rotationDegrees: Int = 0,
        val durationUs: Long = 4_000_000L,
        val nominalFps: Float = 30f,
        val isVfrSuspected: Boolean = false,
        val audioSampleRateHz: Int = 0,
        val audioChannelCount: Int = 0,
        val bitrateBps: Long = 0L,
        val colorInfo: String = "SDR",
        val isHdr: Boolean = false,
    )

    private fun extractMediaTrackMetadata(uri: Uri, resolverMime: String): ExtractedTrackInfo {
        var videoCodec: String? = null
        var audioCodec: String? = null
        var width = 0
        var height = 0
        var durationUs = 0L
        var fps = 30f
        var sampleRate = 0
        var channels = 0
        var isHdr = false
        var colorInfo = "SDR"
        var rotation = 0
        var bitrate = 0L
        var isVfrSuspected = false

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoCodec == null) {
                    videoCodec = mime
                    width = readIntSafe(format, MediaFormat.KEY_WIDTH, width)
                    height = readIntSafe(format, MediaFormat.KEY_HEIGHT, height)
                    durationUs = maxOf(durationUs, readLongSafe(format, MediaFormat.KEY_DURATION, 0L))
                    val parsedFps = readFloatSafe(format, MediaFormat.KEY_FRAME_RATE)
                    if (parsedFps != null && parsedFps > 0f) {
                        fps = parsedFps.coerceAtLeast(1f)
                    } else {
                        isVfrSuspected = true
                    }
                    val transfer = readIntSafe(format, MediaFormat.KEY_COLOR_TRANSFER, -1)
                    if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
                        isHdr = true
                        colorInfo = if (transfer == MediaFormat.COLOR_TRANSFER_HLG) "HLG HDR" else "PQ HDR10"
                    }
                } else if (mime.startsWith("audio/") && audioCodec == null) {
                    audioCodec = mime
                    sampleRate = readIntSafe(format, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = readIntSafe(format, MediaFormat.KEY_CHANNEL_COUNT, channels)
                    durationUs = maxOf(durationUs, readLongSafe(format, MediaFormat.KEY_DURATION, 0L))
                }
            }
        } catch (_: Exception) {
            // Fall through to MediaMetadataRetriever fallback
        } finally {
            runCatching { extractor.release() }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            if (durationUs <= 0L) {
                val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durMs > 0L) durationUs = durMs * 1000L
            }
            if (width == 0) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            }
            if (height == 0) {
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            }
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L

            if (videoCodec == null && resolverMime.startsWith("video/")) {
                videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC
            }
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (audioCodec == null && (hasAudio || resolverMime.startsWith("audio/"))) {
                audioCodec = resolverMime.takeIf { it.startsWith("audio/") } ?: MediaFormat.MIMETYPE_AUDIO_AAC
            }
        } catch (_: Exception) {
            // Handled by caller track presence check
        } finally {
            runCatching { retriever.release() }
        }

        return ExtractedTrackInfo(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            width = width,
            height = height,
            rotationDegrees = rotation,
            durationUs = if (durationUs > 0L) durationUs else 4_000_000L,
            nominalFps = fps,
            isVfrSuspected = isVfrSuspected,
            audioSampleRateHz = sampleRate,
            audioChannelCount = channels,
            bitrateBps = bitrate,
            colorInfo = colorInfo,
            isHdr = isHdr,
        )
    }

    internal fun isDecoderSupported(mimeType: String): Boolean {
        if (mimeType.isBlank()) return false
        return runCatching {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            codecs.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(true)
    }

    private fun deriveContainerLabel(mime: String, fallbackCodec: String?): String {
        return when {
            mime.contains("mp4") -> "MP4"
            mime.contains("webm") -> "WebM"
            mime.contains("matroska") -> "MKV"
            mime.contains("quicktime") -> "MOV"
            mime.contains("mpeg") -> "MP3/MPEG"
            mime.contains("ogg") -> "OGG"
            !fallbackCodec.isNullOrBlank() -> fallbackCodec.substringAfter('/').uppercase()
            else -> "Media"
        }
    }

    internal fun readIntSafe(format: MediaFormat, key: String, fallback: Int): Int {
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

    internal fun readLongSafe(format: MediaFormat, key: String, fallback: Long): Long {
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

    internal fun readFloatSafe(format: MediaFormat, key: String): Float? {
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

    private fun permissionRevoked(detail: String): EngineResult.Failure = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.PERMISSION_REVOKED,
            sourceModule = "platform:android-media",
            userMessage = "Access permission to this media file was revoked. Relink to restore.",
            diagnosticDetail = detail,
            recoverable = true,
        ),
    )

    private fun missingMedia(message: String, detail: String): EngineResult.Failure = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.MISSING_MEDIA,
            sourceModule = "platform:android-media",
            userMessage = message,
            diagnosticDetail = detail,
            recoverable = true,
        ),
    )

    private fun unsupportedMedia(message: String, detail: String): EngineResult.Failure = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.UNSUPPORTED_MEDIA,
            sourceModule = "platform:android-media",
            userMessage = message,
            diagnosticDetail = detail,
            recoverable = false,
        ),
    )
}

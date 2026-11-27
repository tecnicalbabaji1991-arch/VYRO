package com.vyro.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bounded in-memory thumbnail cache for imported Photo Picker / SAF media URIs.
 * Never retains unbounded bitmaps.
 */
object MediaPreviewCache {
    private val cache = object : LruCache<String, ImageBitmap>(24) {}

    suspend fun loadThumbnail(context: Context, uriString: String?): ImageBitmap? {
        if (uriString.isNullOrBlank()) return null
        cache.get(uriString)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bitmap: Bitmap? = runCatching {
                val uri = Uri.parse(uriString)
                val mime = context.contentResolver.getType(uri).orEmpty()
                if (mime.startsWith("video/")) {
                    decodeVideoThumbnail(context, uri)
                } else {
                    val decodedImage = runCatching {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val maxSide = 720
                            val w = info.size.width
                            val h = info.size.height
                            if (w > maxSide || h > maxSide) {
                                val scale = minOf(maxSide.toFloat() / w, maxSide.toFloat() / h)
                                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                            }
                        }
                    }.getOrNull()
                    decodedImage ?: decodeVideoThumbnail(context, uri)
                }
            }.getOrNull()

            bitmap?.asImageBitmap()?.also { img ->
                cache.put(uriString, img)
            }
        }
    }

    private fun decodeVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                720,
                720,
            ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

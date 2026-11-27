package com.vyro.platform.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.DefaultDispatchersProvider
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidMediaInspectorTest {

    @Test
    fun `missing file URI returns MISSING_MEDIA error and revalidates as MISSING`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidMediaInspector(context, DefaultDispatchersProvider)

        val missingUri = "file://${context.filesDir.absolutePath}/does_not_exist.mp4"
        val res = inspector.inspectUri(missingUri)
        assertTrue(res is EngineResult.Failure)
        assertEquals(EngineErrorCode.MISSING_MEDIA, (res as EngineResult.Failure).error.code)

        val revalidated = inspector.revalidateAssets(
            listOf(
                MediaAsset(
                    assetId = "asset_missing_1",
                    uriString = missingUri,
                    displayName = "Lost Clip",
                    mediaKind = MediaKind.VIDEO,
                    videoCodec = "video/avc",
                ),
            ),
        )
        assertEquals(1, revalidated.size)
        assertEquals(AssetAvailability.MISSING, revalidated.first().availability)
    }

    @Test
    fun `material asset without URI always revalidates as AVAILABLE`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidMediaInspector(context, DefaultDispatchersProvider)
        val revalidated = inspector.revalidateAssets(
            listOf(
                MediaAsset(
                    assetId = "asset_mat",
                    uriString = null,
                    materialId = "white_canvas",
                    displayName = "White Canvas",
                    mediaKind = MediaKind.MATERIAL,
                ),
            ),
        )
        assertEquals(AssetAvailability.AVAILABLE, revalidated.first().availability)
    }

    @Test
    fun `unreadable corrupt file returns UNSUPPORTED_MEDIA cleanly without throwing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidMediaInspector(context, DefaultDispatchersProvider)
        val corruptFile = File(context.cacheDir, "corrupt_sample.bin").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        }
        val res = inspector.inspectUri(corruptFile.toURI().toString())
        assertTrue(res is EngineResult.Failure)
        assertEquals(EngineErrorCode.UNSUPPORTED_MEDIA, (res as EngineResult.Failure).error.code)
        corruptFile.delete()
    }

    @Test
    fun `media format helpers handle both float and integer frame rates on Android 11 plus`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidMediaInspector(context, DefaultDispatchersProvider)

        val floatFpsFormat = android.media.MediaFormat().apply {
            setFloat(android.media.MediaFormat.KEY_FRAME_RATE, 29.97f)
            setInteger(android.media.MediaFormat.KEY_WIDTH, 1920)
            setLong(android.media.MediaFormat.KEY_DURATION, 5_000_000L)
        }
        assertEquals(29.97f, inspector.readFloatSafe(floatFpsFormat, android.media.MediaFormat.KEY_FRAME_RATE) ?: 0f, 0.01f)
        assertEquals(1920, inspector.readIntSafe(floatFpsFormat, android.media.MediaFormat.KEY_WIDTH, 0))
        assertEquals(5_000_000L, inspector.readLongSafe(floatFpsFormat, android.media.MediaFormat.KEY_DURATION, 0L))

        val intFpsFormat = android.media.MediaFormat().apply {
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 60)
        }
        assertEquals(60f, inspector.readFloatSafe(intFpsFormat, android.media.MediaFormat.KEY_FRAME_RATE) ?: 0f, 0.01f)
    }
}

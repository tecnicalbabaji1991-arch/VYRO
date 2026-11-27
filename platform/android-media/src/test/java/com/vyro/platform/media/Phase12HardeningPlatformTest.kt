package com.vyro.platform.media

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.DefaultDispatchersProvider
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.ExportConfig
import com.vyro.core.contracts.ExportPhase
import com.vyro.core.contracts.ExportResolutionPreset
import com.vyro.core.contracts.ExportVideoCodec
import com.vyro.core.contracts.FrameCacheEntry
import com.vyro.core.contracts.FrameCacheKey
import com.vyro.core.contracts.DecodedFrameToken
import com.vyro.core.contracts.DecoderBackendType
import com.vyro.core.contracts.MemoryPressureLevel
import com.vyro.core.contracts.ThermalStatusLevel
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase12HardeningPlatformTest {

    private val testDispatchers = object : DispatchersProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private fun sampleDocument(durationUs: Long = 2_000_000L): ProjectDocument {
        val clip = Clip(
            id = "clip_v1",
            trackId = "track_v1",
            label = "Base Cut",
            timing = ClipTiming(0L, 0L, durationUs),
            mediaSource = MediaSourceRef(mediaKind = MediaKind.MATERIAL),
        )
        val seq = Sequence(
            id = "seq_hardening",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(Track("track_v1", "Video", TrackType.VIDEO, 0, clips = listOf(clip))),
        )
        val now = System.currentTimeMillis()
        return ProjectDocument(
            projectId = "proj_hardening",
            title = "Hardening Export",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            sequence = seq,
        )
    }

    @Test
    fun `mid-export storage exhaustion cancels cleanly cleans temp files and never emits FINALIZED`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rootDir = File(context.cacheDir, "export_mid_disk_fail_${System.nanoTime()}").apply { mkdirs() }
        val spaceCalls = AtomicInteger(0)
        val engine = AndroidExportEngine(
            context = context,
            audioSource = AndroidMediaCodecAudioDecoder(context, cacheManager = null),
            dispatchers = testDispatchers,
            outputRootDir = rootDir,
            availableBytesProvider = {
                // Succeed during preflight & initial frames, then drop to 0 bytes mid-encode!
                val call = spaceCalls.incrementAndGet()
                if (call <= 2) 256L * 1024L * 1024L else 256L
            },
            forceFallbackForTest = true,
        )

        try {
            val doc = sampleDocument(durationUs = 3_000_000L)
            val config = ExportConfig.fromSequence(doc, preset = ExportResolutionPreset.HD_720P)
            engine.startExport(doc, config)

            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                val p = engine.progress.value.phase
                if (p == ExportPhase.FAILED || p == ExportPhase.FINALIZED) break
                Thread.sleep(20L)
            }

            val snap = engine.progress.value
            assertEquals(ExportPhase.FAILED, snap.phase)
            assertEquals(EngineErrorCode.STORAGE_FAILURE, snap.error?.code)
            val leftoverFinal = rootDir.listFiles()?.filter { it.extension == "mp4" }.orEmpty()
            val leftoverTmp = File(rootDir, "tmp").listFiles()?.filter { it.name.endsWith(".tmp.mp4") }.orEmpty()
            assertTrue("Expected no partial final mp4, found $leftoverFinal", leftoverFinal.isEmpty())
            assertTrue("Expected no leftover tmp mp4, found $leftoverTmp", leftoverTmp.isEmpty())
            assertEquals(0, engine.diagnostics.value.leakedExportResources)
        } finally {
            engine.release()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `export preset and codec matrix completes 480p h264 and 1080p hevc with zero leaked resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rootDir = File(context.cacheDir, "export_matrix_${System.nanoTime()}").apply { mkdirs() }
        val engine = AndroidExportEngine(
            context = context,
            audioSource = AndroidMediaCodecAudioDecoder(context, cacheManager = null),
            dispatchers = testDispatchers,
            outputRootDir = rootDir,
            availableBytesProvider = { 512L * 1024L * 1024L },
            forceFallbackForTest = true,
        )

        try {
            val doc = sampleDocument(durationUs = 1_000_000L)
            val matrixConfigs = listOf(
                ExportConfig.fromSequence(doc, ExportResolutionPreset.SD_480P, videoCodec = ExportVideoCodec.H264_AVC, includeAudio = false),
                ExportConfig.fromSequence(doc, ExportResolutionPreset.FHD_1080P, videoCodec = ExportVideoCodec.H265_HEVC, includeAudio = true),
            )
            matrixConfigs.forEachIndexed { idx, config ->
                engine.startExport(doc, config)
                val deadline = System.currentTimeMillis() + 8_000L
                while (System.currentTimeMillis() < deadline) {
                    val p = engine.progress.value.phase
                    if (p == ExportPhase.FINALIZED || p == ExportPhase.FAILED) break
                    Thread.sleep(20L)
                }
                val snap = engine.progress.value
                assertEquals("Matrix item $idx phase", ExportPhase.FINALIZED, snap.phase)
                assertEquals(config.width, snap.validationReport?.verifiedWidth)
                assertEquals(config.height, snap.validationReport?.verifiedHeight)
                assertEquals(0, engine.diagnostics.value.leakedExportResources)
                engine.resetCompletedState()
            }
            assertEquals(2L, engine.diagnostics.value.completedExportsCount)
        } finally {
            engine.release()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `malformed zero byte and truncated ftyp media files fail cleanly with UNSUPPORTED_MEDIA`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidMediaInspector(context, DefaultDispatchersProvider)

        val emptyFile = File(context.cacheDir, "zero_byte_clip_${System.nanoTime()}.mp4").apply {
            createNewFile()
        }
        val truncatedMp4 = File(context.cacheDir, "truncated_ftyp_${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(0, 0, 0, 20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()))
        }
        try {
            val zeroRes = inspector.inspectUri(emptyFile.toURI().toString())
            assertTrue(zeroRes is EngineResult.Failure)
            assertEquals(EngineErrorCode.UNSUPPORTED_MEDIA, (zeroRes as EngineResult.Failure).error.code)

            val truncRes = inspector.inspectUri(truncatedMp4.toURI().toString())
            assertTrue(truncRes is EngineResult.Failure)
            assertEquals(EngineErrorCode.UNSUPPORTED_MEDIA, (truncRes as EngineResult.Failure).error.code)
        } finally {
            emptyFile.delete()
            truncatedMp4.delete()
        }
    }

    private class RevokedContentProvider : android.content.ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): android.database.Cursor? = null
        override fun getType(uri: Uri): String = "video/mp4"
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: android.content.ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
            throw SecurityException("Revoked SAF permission grant for $uri")
        }
        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: android.os.Bundle?,
        ): AssetFileDescriptor? {
            throw SecurityException("Revoked SAF permission grant for $uri")
        }
    }

    @Test
    fun `permission revocation SecurityException returns PERMISSION_REVOKED during inspect and revalidate`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "com.vyro.test.revoked"
        org.robolectric.Robolectric.buildContentProvider(RevokedContentProvider::class.java)
            .create(authority)

        val inspector = AndroidMediaInspector(context, DefaultDispatchersProvider)
        val uri = "content://$authority/document/video%3A999"

        val inspected = inspector.inspectUri(uri)
        assertTrue(inspected is EngineResult.Failure)
        assertEquals(EngineErrorCode.PERMISSION_REVOKED, (inspected as EngineResult.Failure).error.code)

        val revalidated = inspector.revalidateAssets(
            listOf(
                MediaAsset(
                    assetId = "asset_revoked_1",
                    uriString = uri,
                    displayName = "Revoked Clip",
                    mediaKind = MediaKind.VIDEO,
                    availability = AssetAvailability.AVAILABLE,
                ),
            ),
        )
        assertEquals(1, revalidated.size)
        assertEquals(AssetAvailability.PERMISSION_REVOKED, revalidated.first().availability)
    }

    @Test
    fun `thermal severe critical and battery saver states trigger cache trims and cap recommendations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheManager = AndroidCacheAndProxyManager(context, testDispatchers)
        val monitor = AndroidRuntimeHardeningMonitor(context, cacheManager)

        try {
            repeat(5) { idx ->
                cacheManager.putFrame(
                    FrameCacheEntry(
                        key = FrameCacheKey("file://test.mp4", idx * 33_333L, 1080, 1920, isProxy = false),
                        token = DecodedFrameToken(
                            clipId = "clip_$idx",
                            uriString = "file://test.mp4",
                            sourcePtsUs = idx * 33_333L,
                            timelineUs = idx * 33_333L,
                            seekGeneration = 1L,
                            width = 1080,
                            height = 1920,
                            rotationDegrees = 0,
                            isKeyFrame = true,
                            backend = DecoderBackendType.MATERIAL_SYNTHETIC,
                        ),
                        byteSize = 4096L,
                    ),
                )
            }
            assertTrue(cacheManager.diagnostics.value.trimEventCount == 0)

            monitor.onThermalStatusChanged(ThermalStatusLevel.SEVERE)
            val severeSnap = monitor.snapshot.value
            assertEquals(ThermalStatusLevel.SEVERE, severeSnap.thermalStatus)
            assertEquals(ExportResolutionPreset.HD_720P, severeSnap.recommendedMaxExportPreset)
            assertEquals(30, severeSnap.recommendedPreviewFpsCap)
            assertTrue(severeSnap.adaptiveProxyRecommended)
            assertTrue(severeSnap.thermalThrottleEvents >= 1L)
            assertEquals(MemoryPressureLevel.LOW, cacheManager.diagnostics.value.memoryPressureLevel)

            monitor.onThermalStatusChanged(ThermalStatusLevel.CRITICAL)
            assertEquals(MemoryPressureLevel.CRITICAL, cacheManager.diagnostics.value.memoryPressureLevel)
            assertEquals(24, monitor.snapshot.value.recommendedPreviewFpsCap)

            monitor.onPowerSaveModeChanged(true)
            assertTrue(monitor.snapshot.value.isPowerSaveMode)
            monitor.onThermalStatusChanged(ThermalStatusLevel.NONE)
            monitor.onPowerSaveModeChanged(false)
            assertFalse(monitor.snapshot.value.isPowerSaveMode)
        } finally {
            monitor.release()
            cacheManager.release()
        }
    }
}

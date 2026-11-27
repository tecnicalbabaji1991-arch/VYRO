package com.vyro.platform.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.DispatchersProvider
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.AudioMixEngine
import com.vyro.core.contracts.ExportConfig
import com.vyro.core.contracts.ExportPhase
import com.vyro.core.contracts.ExportResolutionPreset
import com.vyro.core.contracts.ExportVideoCodec
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.ProjectDocument
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidExportEngineTest {

    private val testDispatchers = object : DispatchersProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private fun sampleDocument(durationUs: Long = 2_000_000L): ProjectDocument {
        val videoClip = Clip(
            id = "clip_v1",
            trackId = "track_v1",
            label = "Base Cut",
            timing = ClipTiming(0L, 0L, durationUs),
            filterPreset = ColorFilterPreset.CYBER_CYAN,
            mediaSource = MediaSourceRef(
                mediaKind = MediaKind.MATERIAL,
                uriString = null,
            ),
        )
        val textClip = Clip(
            id = "clip_t1",
            trackId = "track_t1",
            label = "Overlay Title",
            timing = ClipTiming(0L, 0L, durationUs),
            textSpec = TextClipSpec(text = "VYRO"),
        )
        val audioClip = Clip(
            id = "clip_a1",
            trackId = "track_a1",
            label = "Beat",
            timing = ClipTiming(0L, 0L, durationUs),
            audioSpec = AudioClipSpec(gainDb = -3f),
        )
        val sequence = Sequence(
            id = "seq_exp",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(id = "track_v1", name = "Video", type = TrackType.VIDEO, zIndex = 0, clips = listOf(videoClip)),
                Track(id = "track_t1", name = "Text", type = TrackType.TEXT, zIndex = 1, clips = listOf(textClip)),
                Track(id = "track_a1", name = "Audio", type = TrackType.AUDIO, zIndex = 2, clips = listOf(audioClip)),
            ),
        )
        val now = System.currentTimeMillis()
        return ProjectDocument(
            projectId = "proj_export_test",
            title = "Vyro Export Demo",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            sequence = sequence,
        )
    }

    @Test
    fun `export config aligns odd source dimensions and validates capability constraints`() {
        val doc = sampleDocument().let {
            it.copy(sequence = it.sequence.copy(width = 721, height = 1281))
        }
        val aligned = ExportConfig.fromSequence(doc, ExportResolutionPreset.SEQUENCE_NATIVE)
        assertEquals(0, aligned.width % 2)
        assertEquals(0, aligned.height % 2)

        val inspector = AndroidExportCapabilityInspector()
        val oddRejected = inspector.validateConfiguration(
            sequence = doc.sequence,
            config = aligned.copy(width = 721),
            availableBytes = 200L * 1024L * 1024L,
        )
        assertTrue(oddRejected is EngineResult.Failure)
        assertEquals(EngineErrorCode.UNSUPPORTED_CAPABILITY, (oddRejected as EngineResult.Failure).error.code)

        val lowDiskRejected = inspector.validateConfiguration(
            sequence = doc.sequence,
            config = aligned,
            availableBytes = 1024L,
        )
        assertTrue(lowDiskRejected is EngineResult.Failure)
        assertEquals(EngineErrorCode.STORAGE_FAILURE, (lowDiskRejected as EngineResult.Failure).error.code)

        val emptySeqRejected = inspector.validateConfiguration(
            sequence = doc.sequence.copy(tracks = emptyList()),
            config = aligned,
            availableBytes = 200L * 1024L * 1024L,
        )
        assertTrue(emptySeqRejected is EngineResult.Failure)
        assertEquals(EngineErrorCode.INVALID_TIMELINE_EDIT, (emptySeqRejected as EngineResult.Failure).error.code)
    }

    @Test
    fun `full export pipeline validates MP4 container dimensions duration and AV sync before finalizing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rootDir = File(context.cacheDir, "export_test_full_${System.nanoTime()}").apply { mkdirs() }
        val engine = AndroidExportEngine(
            context = context,
            audioSource = AndroidMediaCodecAudioDecoder(context, cacheManager = null),
            dispatchers = testDispatchers,
            outputRootDir = rootDir,
            availableBytesProvider = { 256L * 1024L * 1024L },
            forceFallbackForTest = true,
        )

        try {
            val doc = sampleDocument(durationUs = 1_500_000L)
            val config = ExportConfig.fromSequence(
                doc = doc,
                preset = ExportResolutionPreset.HD_720P,
                videoCodec = ExportVideoCodec.H264_AVC,
                includeAudio = true,
                saveToGallery = false,
            )

            runBlocking {
                val capReport = engine.queryCodecCapabilities()
                assertTrue(capReport.deviceSdkInt >= 30)
            }

            engine.startExport(doc, config)

            val deadline = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadline) {
                val p = engine.progress.value.phase
                if (p == ExportPhase.FINALIZED || p == ExportPhase.FAILED || p == ExportPhase.CANCELLED) break
                Thread.sleep(25L)
            }

            val snap = engine.progress.value
            assertEquals(ExportPhase.FINALIZED, snap.phase)
            val outPath = snap.outputFilePath
            assertNotNull(outPath)
            val outFile = File(outPath!!)
            assertTrue(outFile.exists())
            assertTrue(outFile.length() > 64L)

            // Temp artifacts must be promoted / cleaned up
            val tmpDir = File(rootDir, "tmp")
            val leftoverTmp = tmpDir.listFiles()?.filter { it.name.endsWith(".tmp.mp4") }.orEmpty()
            assertTrue("Expected no leftover tmp files, found $leftoverTmp", leftoverTmp.isEmpty())

            val report = snap.validationReport
            assertNotNull(report)
            assertTrue(report!!.isValidContainer)
            assertTrue(report.hasVideoTrack)
            assertTrue(report.hasAudioTrack)
            assertEquals(config.width, report.verifiedWidth)
            assertEquals(config.height, report.verifiedHeight)
            assertTrue(report.avTimingDeltaUs <= 65_000L)

            val diag = engine.diagnostics.value
            assertEquals(1L, diag.completedExportsCount)
            assertEquals(0, diag.leakedExportResources)
            assertTrue(snap.releasedResources >= snap.allocatedResources)
        } finally {
            engine.release()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cancel export cleans temporary files and reports zero leaked resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rootDir = File(context.cacheDir, "export_test_cancel_${System.nanoTime()}").apply { mkdirs() }
        val engine = AndroidExportEngine(
            context = context,
            audioSource = AndroidMediaCodecAudioDecoder(context, cacheManager = null),
            dispatchers = testDispatchers,
            outputRootDir = rootDir,
            availableBytesProvider = { 256L * 1024L * 1024L },
            forceFallbackForTest = true,
        )

        try {
            // Long sequence so cancel triggers deterministically mid-encode
            val doc = sampleDocument(durationUs = 12_000_000L)
            val config = ExportConfig.fromSequence(doc, preset = ExportResolutionPreset.FHD_1080P)
            engine.startExport(doc, config)

            Thread.sleep(20L)
            engine.cancelExport()

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                val p = engine.progress.value.phase
                if (p == ExportPhase.CANCELLED || p == ExportPhase.FINALIZED || p == ExportPhase.FAILED) break
                Thread.sleep(20L)
            }

            val diag = engine.diagnostics.value
            assertEquals(0, diag.leakedExportResources)
            val tmpFiles = File(rootDir, "tmp").listFiles()?.filter { it.name.endsWith(".tmp.mp4") }.orEmpty()
            assertTrue(tmpFiles.isEmpty())
            assertTrue(diag.cancelledExportsCount + diag.completedExportsCount >= 1L)
        } finally {
            engine.release()
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `export storage failure leaves no final MP4 and never emits FINALIZED`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rootDir = File(context.cacheDir, "export_test_fail_${System.nanoTime()}").apply { mkdirs() }
        val engine = AndroidExportEngine(
            context = context,
            audioSource = AndroidMediaCodecAudioDecoder(context, cacheManager = null),
            dispatchers = testDispatchers,
            outputRootDir = rootDir,
            availableBytesProvider = { 512L }, // Insufficient disk space
            forceFallbackForTest = true,
        )

        try {
            val doc = sampleDocument(durationUs = 1_000_000L)
            val config = ExportConfig.fromSequence(doc, preset = ExportResolutionPreset.HD_720P)
            engine.startExport(doc, config)

            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline) {
                val p = engine.progress.value.phase
                if (p == ExportPhase.FAILED || p == ExportPhase.FINALIZED) break
                Thread.sleep(20L)
            }

            val snap = engine.progress.value
            assertEquals(ExportPhase.FAILED, snap.phase)
            assertEquals(EngineErrorCode.STORAGE_FAILURE, snap.error?.code)
            val finalizedFiles = rootDir.listFiles()?.filter { it.extension == "mp4" }.orEmpty()
            assertTrue(finalizedFiles.isEmpty())
            assertEquals(0, engine.diagnostics.value.leakedExportResources)
            assertFalse(AudioMixEngine.dbToLinear(0f) <= 0f)
        } finally {
            engine.release()
            rootDir.deleteRecursively()
        }
    }
}

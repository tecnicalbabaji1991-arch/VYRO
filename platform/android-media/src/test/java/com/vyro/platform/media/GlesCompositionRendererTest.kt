package com.vyro.platform.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.RenderGraphEvaluator
import com.vyro.core.model.BlendMode
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CropRect
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.core.model.VisualEffectPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlesCompositionRendererTest {

    @Test
    fun `gpu resource tracker releases all allocated handles with zero leaks`() {
        val tracker = GpuResourceTracker()
        val deletedTextures = mutableListOf<Int>()
        val deletedFbos = mutableListOf<Int>()
        val deletedPrograms = mutableListOf<Int>()

        tracker.registerTexture(11)
        tracker.registerTexture(12)
        tracker.registerFbo(21)
        tracker.registerProgram(31)

        assertEquals(4, tracker.snapshot().totalActive)
        tracker.releaseTexture(11) { deletedTextures.add(it) }
        assertEquals(listOf(11), deletedTextures)
        assertEquals(3, tracker.snapshot().totalActive)

        val freed = tracker.releaseAll(
            onDeleteTexture = { deletedTextures.add(it) },
            onDeleteFbo = { deletedFbos.add(it) },
            onDeleteProgram = { deletedPrograms.add(it) },
        )
        assertEquals(3, freed)
        assertEquals(0, tracker.snapshot().totalActive)
        assertEquals(listOf(11, 12), deletedTextures)
        assertEquals(listOf(21), deletedFbos)
        assertEquals(listOf(31), deletedPrograms)
    }

    @Test
    fun `gles renderer composes multi-layer graph within budget and survives surface recreation cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var fakeNanos = 1_000_000_000L
        val renderer = GlesCompositionRenderer(
            context = context,
            nanoTimeProvider = {
                val current = fakeNanos
                fakeNanos += 2_400_000L // 2.4 ms per clock read (< 16.6 ms budget)
                current
            },
        )

        val seq = Sequence(
            id = "seq_gles",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "track_video",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 0,
                    clips = listOf(
                        Clip(
                            id = "clip_bg",
                            trackId = "track_video",
                            label = "BG",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                            mediaSource = MediaSourceRef(mediaKind = MediaKind.MATERIAL, backdropColorArgb = 0xFF102030L),
                            transform = LayerTransform(
                                crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f),
                                opacity = 0.95f,
                                blendMode = BlendMode.NORMAL,
                            ),
                            filterPreset = ColorFilterPreset.VIVID_POP,
                            effectPreset = VisualEffectPreset.SOFT_GLOW,
                        ),
                    ),
                ),
                Track(
                    id = "track_text",
                    name = "Text",
                    type = TrackType.TEXT,
                    zIndex = 1,
                    clips = listOf(
                        Clip(
                            id = "clip_title",
                            trackId = "track_text",
                            label = "Title",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                            transform = LayerTransform(
                                normalizedX = 0.5f,
                                normalizedY = 0.4f,
                                scale = 1.25f,
                                rotationDegrees = 15f,
                                opacity = 0.8f,
                                blendMode = BlendMode.SCREEN,
                            ),
                            textSpec = TextClipSpec("PHASE 5", animationPreset = TextAnimationPreset.SCALE_POP),
                        ),
                    ),
                ),
            ),
        )

        val tex1 = SurfaceTexture(101)
        val surface1 = Surface(tex1)
        val tex2 = SurfaceTexture(102)
        val surface2 = Surface(tex2)

        renderer.attachSurface(surface1, 1080, 1920)
        assertTrue(renderer.renderTelemetry.value.surfaceAttached)

        val graph = RenderGraphEvaluator.evaluate(seq, timelineUs = 250_000L)
        val res = renderer.renderGraph(graph)
        assertTrue(res is EngineResult.Success)
        val snapshot = (res as EngineResult.Success).value
        assertEquals(2, snapshot.activeLayerCount)
        assertEquals(2_400L, snapshot.lastFrameRenderTimeUs)
        assertTrue(snapshot.withinFrameBudget)

        // Surface recreation stress: swap surface2 in before surface1 destroyed
        renderer.attachSurface(surface2, 1920, 1080)
        assertEquals(1, renderer.renderTelemetry.value.surfaceRecreatedCount)
        renderer.detachSurface(surface1) // stale destroy ignored
        assertTrue(renderer.renderTelemetry.value.surfaceAttached)

        // Clean final detach releases GPU resources with zero leaks
        renderer.detachSurface(surface2)
        assertFalse(renderer.renderTelemetry.value.surfaceAttached)
        assertEquals(0, renderer.renderTelemetry.value.leakedGpuResources)
        assertEquals(0, renderer.renderTelemetry.value.activeProgramCount)

        val caps = renderer.queryGpuCapabilities()
        assertTrue(caps.vulkanPrototypeReason.isNotBlank())

        renderer.release()
        surface1.release()
        surface2.release()
        tex1.release()
        tex2.release()
    }

    @Test
    fun `gles renderer flags over-budget frames accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var fakeNanos = 0L
        val renderer = GlesCompositionRenderer(
            context = context,
            nanoTimeProvider = {
                val current = fakeNanos
                fakeNanos += 20_000_000L // 20 ms > 16.666 ms budget
                current
            },
        )
        val graph = RenderGraphEvaluator.evaluate(
            Sequence(
                id = "seq_over",
                width = 1080,
                height = 1920,
                frameRate = Rational.FPS_30,
                tracks = emptyList(),
            ),
            timelineUs = 0L,
        )
        val snapshot = (renderer.renderGraph(graph) as EngineResult.Success).value
        assertEquals(20_000L, snapshot.lastFrameRenderTimeUs)
        assertFalse(snapshot.withinFrameBudget)
        renderer.release()
    }
}

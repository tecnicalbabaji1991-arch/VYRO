package com.vyro.core.contracts

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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RenderGraphEvaluatorTest {

    @Test
    fun `render graph evaluates z-order crop opacity blend mode and filters deterministically`() {
        val sequence = Sequence(
            id = "seq_comp",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                // Put higher zIndex first in list to prove evaluator sorts strictly by zIndex
                Track(
                    id = "track_text",
                    name = "Text",
                    type = TrackType.TEXT,
                    zIndex = 2,
                    clips = listOf(
                        Clip(
                            id = "clip_txt",
                            trackId = "track_text",
                            label = "Title",
                            timing = ClipTiming(1_000_000L, 0L, 3_000_000L),
                            transform = LayerTransform(
                                normalizedX = 0.5f,
                                normalizedY = 0.25f,
                                scale = 1.2f,
                                rotationDegrees = 0f,
                                opacity = 0.85f,
                                blendMode = BlendMode.SCREEN,
                            ),
                            textSpec = TextClipSpec(
                                text = "VYRO",
                                animationPreset = TextAnimationPreset.FADE_IN,
                            ),
                        ),
                    ),
                ),
                Track(
                    id = "track_audio",
                    name = "Audio",
                    type = TrackType.AUDIO,
                    zIndex = 3,
                    clips = listOf(
                        Clip(
                            id = "clip_aud",
                            trackId = "track_audio",
                            label = "Beat",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                        ),
                    ),
                ),
                Track(
                    id = "track_muted_overlay",
                    name = "Muted Overlay",
                    type = TrackType.VIDEO,
                    zIndex = 1,
                    isMuted = true,
                    clips = listOf(
                        Clip(
                            id = "clip_hidden",
                            trackId = "track_muted_overlay",
                            label = "Hidden",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                        ),
                    ),
                ),
                Track(
                    id = "track_video",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 0,
                    clips = listOf(
                        Clip(
                            id = "clip_base",
                            trackId = "track_video",
                            label = "Base",
                            timing = ClipTiming(0L, 500_000L, 4_500_000L, playbackSpeed = Rational.SPEED_2X),
                            mediaSource = MediaSourceRef(mediaKind = MediaKind.MATERIAL, materialId = "studio_reel"),
                            transform = LayerTransform(
                                normalizedX = 0.75f,
                                normalizedY = 0.5f,
                                scale = 1.5f,
                                rotationDegrees = 90f,
                                opacity = 0.9f,
                                crop = CropRect(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f),
                                blendMode = BlendMode.MULTIPLY,
                            ),
                            filterPreset = ColorFilterPreset.CYBER_CYAN,
                            effectPreset = VisualEffectPreset.VIGNETTE,
                        ),
                    ),
                ),
            ),
        )

        val graph = RenderGraphEvaluator.evaluate(sequence, timelineUs = 1_225_000L)

        // Audio & muted tracks excluded; base video (z=0) before text (z=2)
        assertEquals(2, graph.layers.size)
        assertEquals(listOf("clip_base", "clip_txt"), graph.layers.map { it.clipId })

        val base = graph.layers[0]
        // At 1_225_000us with 2x playback speed starting at sourceIn 500_000us -> 500_000 + 2_450_000 = 2_950_000us
        assertEquals(2_950_000L, base.sourcePtsUs)
        assertEquals(BlendMode.MULTIPLY, base.blendMode)
        assertEquals(ColorFilterPreset.CYBER_CYAN, base.filterPreset)
        assertEquals(VisualEffectPreset.VIGNETTE, base.effectPreset)
        assertEquals(0.8f, base.crop.widthFraction, 0.001f)
        assertEquals(0.8f, base.crop.heightFraction, 0.001f)

        // Check 4x4 column-major transform matrix: tx = (0.75 - 0.5)*2 = +0.5, rotation = 90 deg -> cos=0, sin=1
        val matrix = base.toColumnMajorMatrix()
        assertEquals(16, matrix.size)
        assertTrue(abs(matrix[0]) < 0.001f)
        assertEquals(1.5f * 0.8f, matrix[1], 0.001f)
        assertEquals(-1.5f * 0.8f, matrix[4], 0.001f)
        assertEquals(0.5f, matrix[12], 0.001f)
        assertEquals(0.0f, matrix[13], 0.001f)

        // Text clip elapsed 225ms out of 450ms fade-in window -> 0.5 animatedAlpha
        val textNode = graph.layers[1]
        assertEquals(BlendMode.SCREEN, textNode.blendMode)
        assertEquals(0.85f, textNode.opacity, 0.001f)
        assertEquals(0.5f, textNode.animatedAlpha, 0.01f)
    }

    @Test
    fun `zero opacity layers are culled from render graph`() {
        val sequence = Sequence(
            id = "seq_culled",
            width = 1920,
            height = 1080,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "track_v",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 0,
                    clips = listOf(
                        Clip(
                            id = "clip_invisible",
                            trackId = "track_v",
                            label = "Invisible",
                            timing = ClipTiming(0L, 0L, 2_000_000L),
                            transform = LayerTransform(opacity = 0f),
                        ),
                    ),
                ),
            ),
        )

        val graph = RenderGraphEvaluator.evaluate(sequence, timelineUs = 500_000L)
        assertTrue(graph.layers.isEmpty())
    }
}

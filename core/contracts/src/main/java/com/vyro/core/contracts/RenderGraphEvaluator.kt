package com.vyro.core.contracts

import com.vyro.core.model.Sequence
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TrackType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Evaluates a timeline [Sequence] at an authoritative timestamp [timelineUs] into an
 * ordered [RenderGraph] containing z-sorted layers, 4x4 column-major transform matrices,
 * UV crop rectangles, opacity, blend modes, and text animation states.
 */
object RenderGraphEvaluator {

    private const val TEXT_ANIMATION_WINDOW_US = 450_000L

    fun evaluate(sequence: Sequence, timelineUs: Long): RenderGraph {
        val clampedTimeUs = timelineUs.coerceAtLeast(0L)
        val visualTracks = sequence.tracks
            .withIndex()
            .filter { (_, track) -> !track.isMuted && track.type != TrackType.AUDIO }
            .sortedWith(compareBy({ it.value.zIndex }, { it.index }))

        val nodes = mutableListOf<CompositionNode>()
        for ((_, track) in visualTracks) {
            val activeInTrack = track.clips
                .filter { it.timing.containsTimelinePosition(clampedTimeUs) }
                .sortedBy { it.timing.timelineStartUs }

            for (clip in activeInTrack) {
                val effectiveOpacity = clip.transform.opacity.coerceIn(0f, 1f)
                if (effectiveOpacity <= 0.001f) continue

                val sourcePtsUs = clip.timing.timelineToSourceUs(clampedTimeUs) ?: continue
                val crop = clip.transform.crop.normalized()

                val elapsedUs = (clampedTimeUs - clip.timing.timelineStartUs).coerceAtLeast(0L)
                val progress = (elapsedUs.toFloat() / TEXT_ANIMATION_WINDOW_US.toFloat()).coerceIn(0f, 1f)
                var animAlpha = 1f
                var animTranslateY = 0f
                var animScale = 1f
                when (clip.textSpec?.animationPreset) {
                    TextAnimationPreset.FADE_IN -> {
                        animAlpha = progress
                    }
                    TextAnimationPreset.SLIDE_UP -> {
                        animAlpha = (progress * 1.25f).coerceIn(0f, 1f)
                        animTranslateY = (1f - progress) * 0.14f
                    }
                    TextAnimationPreset.SCALE_POP -> {
                        animScale = 0.72f + (0.28f * progress)
                    }
                    else -> Unit
                }

                val finalScale = (clip.transform.scale.coerceIn(0.1f, 5f) * animScale)
                val matrix = buildTransformMatrix(
                    normalizedX = clip.transform.normalizedX.coerceIn(0.05f, 0.95f),
                    normalizedY = (clip.transform.normalizedY - animTranslateY).coerceIn(0.05f, 0.95f),
                    scaleX = finalScale * crop.widthFraction,
                    scaleY = finalScale * crop.heightFraction,
                    rotationDegrees = clip.transform.rotationDegrees,
                )

                nodes.add(
                    CompositionNode(
                        clipId = clip.id,
                        trackId = track.id,
                        zIndex = track.zIndex,
                        mediaKind = clip.mediaSource.mediaKind,
                        sourceUri = clip.mediaSource.uriString,
                        materialId = clip.mediaSource.materialId,
                        backdropColorArgb = clip.mediaSource.backdropColorArgb,
                        sourcePtsUs = sourcePtsUs,
                        normalizedX = clip.transform.normalizedX,
                        normalizedY = clip.transform.normalizedY,
                        scale = clip.transform.scale,
                        rotationDegrees = clip.transform.rotationDegrees,
                        opacity = effectiveOpacity,
                        crop = crop,
                        blendMode = clip.transform.blendMode,
                        filterPreset = clip.filterPreset,
                        effectPreset = clip.effectPreset,
                        textSpec = clip.textSpec,
                        animatedAlpha = animAlpha,
                        animatedTranslationY = animTranslateY,
                        animatedScale = animScale,
                        modelMatrix = matrix,
                    ),
                )
            }
        }

        return RenderGraph(
            sequenceId = sequence.id,
            timelineUs = clampedTimeUs,
            outputWidth = sequence.width.coerceAtLeast(1),
            outputHeight = sequence.height.coerceAtLeast(1),
            layers = nodes,
        )
    }

    /**
     * Builds a 4x4 column-major OpenGL transformation matrix:
     * Translate to NDC center -> Rotate Z -> Scale.
     */
    fun buildTransformMatrix(
        normalizedX: Float,
        normalizedY: Float,
        scaleX: Float,
        scaleY: Float,
        rotationDegrees: Float,
    ): List<Float> {
        val tx = (normalizedX - 0.5f) * 2f
        val ty = (0.5f - normalizedY) * 2f
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()

        // Column-major 4x4: M = T * Rz * S
        return listOf(
            c * scaleX, s * scaleX, 0f, 0f,
            -s * scaleY, c * scaleY, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, 0f, 1f,
        )
    }
}

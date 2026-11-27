package com.vyro.core.contracts

import com.vyro.core.model.Clip
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TrackType
import com.vyro.core.model.TransitionPreset
import kotlin.math.cos
import kotlin.math.sin

/**
 * Evaluates a timeline [Sequence] at an authoritative timestamp [timelineUs] into an
 * ordered [RenderGraph] containing z-sorted layers, 4x4 column-major transform matrices,
 * keyframe interpolation, clip transitions, UV crop rectangles, opacity, blend modes, and text animation states.
 */
object RenderGraphEvaluator {

    private const val TEXT_ANIMATION_WINDOW_US = 450_000L
    private const val TEXT_TYPEWRITER_WINDOW_US = 900_000L

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
                val elapsedUs = (clampedTimeUs - clip.timing.timelineStartUs).coerceAtLeast(0L)
                val resolvedTransform = resolveClipTransform(clip, elapsedUs)
                val effectiveOpacity = resolvedTransform.opacity.coerceIn(0f, 1f)
                if (effectiveOpacity <= 0.001f) continue

                val sourcePtsUs = clip.timing.timelineToSourceUs(clampedTimeUs) ?: continue
                val crop = resolvedTransform.crop.normalized()

                var animAlpha = 1f
                var animTranslateX = 0f
                var animTranslateY = 0f
                var animScale = 1f
                var visibleCharCount: Int? = null

                val transitionPreset = clip.transitionIn.preset
                if (transitionPreset != TransitionPreset.NONE) {
                    val clipDur = clip.timing.timelineDurationUs.coerceAtLeast(1L)
                    val transitionWindowUs = minOf(
                        clip.transitionIn.durationUs.coerceIn(100_000L, 2_000_000L),
                        clipDur,
                    )
                    if (elapsedUs < transitionWindowUs) {
                        val tProg = (elapsedUs.toFloat() / transitionWindowUs.toFloat()).coerceIn(0f, 1f)
                        when (transitionPreset) {
                            TransitionPreset.CROSS_DISSOLVE -> {
                                animAlpha *= tProg
                            }
                            TransitionPreset.SLIDE_LEFT -> {
                                animTranslateX += (1f - tProg) * 0.28f
                                animAlpha *= (0.35f + 0.65f * tProg)
                            }
                            TransitionPreset.ZOOM_IN -> {
                                animScale *= (0.72f + 0.28f * tProg)
                                animAlpha *= (0.40f + 0.60f * tProg)
                            }
                            TransitionPreset.NONE -> Unit
                        }
                    }
                }

                val textSpec = clip.textSpec
                if (textSpec != null) {
                    val textProgress = (elapsedUs.toFloat() / TEXT_ANIMATION_WINDOW_US.toFloat()).coerceIn(0f, 1f)
                    when (textSpec.animationPreset) {
                        TextAnimationPreset.FADE_IN -> {
                            animAlpha *= textProgress
                        }
                        TextAnimationPreset.SLIDE_UP -> {
                            animAlpha *= (textProgress * 1.25f).coerceIn(0f, 1f)
                            animTranslateY += (1f - textProgress) * 0.14f
                        }
                        TextAnimationPreset.SCALE_POP -> {
                            animScale *= (0.72f + (0.28f * textProgress))
                        }
                        TextAnimationPreset.TYPEWRITER -> {
                            val twWindow = minOf(
                                TEXT_TYPEWRITER_WINDOW_US,
                                clip.timing.timelineDurationUs.coerceAtLeast(1L),
                            )
                            val twProgress = (elapsedUs.toFloat() / twWindow.toFloat()).coerceIn(0f, 1f)
                            val fullLen = textSpec.text.length
                            visibleCharCount = if (fullLen == 0) {
                                0
                            } else {
                                (twProgress * fullLen).toInt().coerceIn(1, fullLen)
                            }
                        }
                        TextAnimationPreset.NONE -> Unit
                    }
                }

                val finalScale = (resolvedTransform.scale.coerceIn(0.1f, 5f) * animScale)
                val matrix = buildTransformMatrix(
                    normalizedX = (resolvedTransform.normalizedX + animTranslateX).coerceIn(0.05f, 1.35f),
                    normalizedY = (resolvedTransform.normalizedY - animTranslateY).coerceIn(0.05f, 0.95f),
                    scaleX = finalScale * crop.widthFraction,
                    scaleY = finalScale * crop.heightFraction,
                    rotationDegrees = resolvedTransform.rotationDegrees,
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
                        normalizedX = resolvedTransform.normalizedX,
                        normalizedY = resolvedTransform.normalizedY,
                        scale = resolvedTransform.scale,
                        rotationDegrees = resolvedTransform.rotationDegrees,
                        opacity = effectiveOpacity,
                        crop = crop,
                        blendMode = resolvedTransform.blendMode,
                        filterPreset = clip.filterPreset,
                        effectPreset = clip.effectPreset,
                        textSpec = clip.textSpec,
                        animatedAlpha = animAlpha.coerceIn(0f, 1f),
                        animatedTranslationX = animTranslateX,
                        animatedTranslationY = animTranslateY,
                        animatedScale = animScale,
                        visibleTextCharCount = visibleCharCount,
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

    fun resolveClipTransform(clip: Clip, elapsedUs: Long): LayerTransform {
        if (!clip.keyframesEnabled || clip.keyframes.isEmpty()) {
            return clip.transform
        }
        val sorted = clip.keyframes.sortedBy { it.offsetUs }
        if (elapsedUs <= sorted.first().offsetUs) {
            val k = sorted.first()
            return clip.transform.copy(
                normalizedX = k.normalizedX,
                normalizedY = k.normalizedY,
                scale = k.scale,
                rotationDegrees = k.rotationDegrees,
                opacity = k.opacity,
            )
        }
        if (elapsedUs >= sorted.last().offsetUs) {
            val k = sorted.last()
            return clip.transform.copy(
                normalizedX = k.normalizedX,
                normalizedY = k.normalizedY,
                scale = k.scale,
                rotationDegrees = k.rotationDegrees,
                opacity = k.opacity,
            )
        }
        for (i in 0 until sorted.size - 1) {
            val k0 = sorted[i]
            val k1 = sorted[i + 1]
            if (elapsedUs in k0.offsetUs..k1.offsetUs) {
                val span = (k1.offsetUs - k0.offsetUs).coerceAtLeast(1L)
                val t = ((elapsedUs - k0.offsetUs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                return clip.transform.copy(
                    normalizedX = lerp(k0.normalizedX, k1.normalizedX, t),
                    normalizedY = lerp(k0.normalizedY, k1.normalizedY, t),
                    scale = lerp(k0.scale, k1.scale, t),
                    rotationDegrees = lerp(k0.rotationDegrees, k1.rotationDegrees, t),
                    opacity = lerp(k0.opacity, k1.opacity, t).coerceIn(0f, 1f),
                )
            }
        }
        return clip.transform
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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

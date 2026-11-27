package com.vyro.feature.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.vyro.core.contracts.AudioMixEngine
import com.vyro.core.contracts.WaveformProfile
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.Rational
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * High-frequency custom Android View for timeline ruler and clip rendering.
 * Keeps the playhead anchored horizontally at the center while scrolling the timeline tracks underneath.
 */
class TimelineSurfaceView(context: Context) : View(context) {

    companion object {
        const val BASE_DP_PER_SECOND = 40f
    }

    var tracks: List<Track> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectedClipId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var playheadUs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }

    var durationUs: Long = 4_000_000L
        set(value) {
            field = value
            invalidate()
        }

    var frameRate: Rational = Rational.FPS_30

    var zoomScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.4f, 3.0f)
            invalidate()
        }

    var waveforms: Map<String, WaveformProfile> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    var onScrubPlayheadUs: ((Long) -> Unit)? = null
    var onSelectClip: ((String?) -> Unit)? = null
    var onMoveClip: ((clipId: String, newStartUs: Long) -> Unit)? = null
    var onTrimClip: ((clipId: String, newStartUs: Long, newSourceInUs: Long, newSourceOutUs: Long) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val rulerHeightPx = 28f * density
    private val primaryTrackHeightPx = 48f * density
    private val secondaryTrackHeightPx = 30f * density
    private val trackGapPx = 8f * density
    private val basePxPerSecond = BASE_DP_PER_SECOND * density
    private val handleTouchHalfWidthPx = 14f * density

    private enum class DragMode {
        NONE,
        SCROLL_TIMELINE,
        MOVE_CLIP,
        TRIM_LEFT,
        TRIM_RIGHT,
    }

    private var dragMode = DragMode.NONE
    private var activeDragClip: Clip? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartPlayheadUs = 0L
    private var hasDraggedBeyondSlop = false
    private var previewStartUs = 0L
    private var previewSourceInUs = 0L
    private var previewSourceOutUs = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#13151B") }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B7487") }
    private val rulerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7D8596")
        textSize = 10f * density
    }
    private val clipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val clipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * density
        isFakeBoldText = true
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B6B")
    }
    private val waveformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5EEAD4")
        strokeCap = Paint.Cap.ROUND
    }
    private val tempRect = RectF()

    init {
        contentDescription = "VYRO multi-lane video, text, and audio timeline"
    }

    private fun centerAnchorX(): Float = width / 2f

    private fun pxPerUs(): Float = (basePxPerSecond * zoomScale) / 1_000_000f

    private fun usToScreenX(us: Long): Float =
        centerAnchorX() + ((us - playheadUs) * pxPerUs())

    private fun orderedTracks(): List<Track> {
        val video = tracks.filter { it.type == TrackType.VIDEO }
        val audio = tracks.filter { it.type == TrackType.AUDIO }
        val text = tracks.filter { it.type == TrackType.TEXT }
        return (video + audio + text).filterIndexed { idx, t -> idx == 0 || t.clips.isNotEmpty() }
    }

    private fun laneTopAndHeight(index: Int): Pair<Float, Float> {
        var top = rulerHeightPx + 14f * density
        for (i in 0 until index) {
            val h = if (i == 0) primaryTrackHeightPx else secondaryTrackHeightPx
            top += h + trackGapPx
        }
        val height = if (index == 0) primaryTrackHeightPx else secondaryTrackHeightPx
        return top to height
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = max(1, orderedTracks().size)
        val desiredHeight = (rulerHeightPx + 18f * density + primaryTrackHeightPx +
            max(0, count - 1) * (secondaryTrackHeightPx + trackGapPx)).toInt()
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, resolvedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Scrolling ruler ticks (0:00 • 0:02 • 0:04 • 0:06) relative to fixed center playhead
        val maxSec = max(12L, (durationUs / 1_000_000L) + 6L)
        for (sec in 0..maxSec.toInt()) {
            val x = usToScreenX(sec * 1_000_000L)
            if (x < -30f * density || x > w + 30f * density) continue
            if (sec % 2 == 0) {
                val mins = sec / 60
                val remSec = sec % 60
                val label = "%d:%02d".format(mins, remSec)
                canvas.drawText(label, x - 9f * density, 16f * density, rulerTextPaint)
            } else {
                canvas.drawCircle(x, 12f * density, 1.3f * density, dotPaint)
            }
        }

        // Scrolling lanes & clips under stationary center playhead
        val visibleTracks = orderedTracks()
        visibleTracks.forEachIndexed { index, track ->
            val (top, laneH) = laneTopAndHeight(index)
            val bottom = top + laneH

            track.clips.forEach { clip ->
                val isDraggingThis = activeDragClip?.id == clip.id &&
                    (dragMode == DragMode.MOVE_CLIP || dragMode == DragMode.TRIM_LEFT || dragMode == DragMode.TRIM_RIGHT)
                val startUs = if (isDraggingThis) previewStartUs else clip.timing.timelineStartUs
                val durUs = if (isDraggingThis) {
                    ((previewSourceOutUs - previewSourceInUs) * clip.timing.playbackSpeed.denominator) /
                        clip.timing.playbackSpeed.numerator
                } else {
                    clip.timing.timelineDurationUs
                }
                val left = usToScreenX(startUs)
                val right = usToScreenX(startUs + durUs)
                if (right < 0f || left > w) return@forEach

                tempRect.set(left, top, max(left + 12f * density, right), bottom)
                val rawColor = when (track.type) {
                    TrackType.VIDEO -> clip.mediaSource.backdropColorArgb.toInt()
                    TrackType.TEXT -> Color.parseColor("#5433A8")
                    TrackType.AUDIO -> Color.parseColor("#0F766E")
                }
                clipFillPaint.color = if (track.isMuted && track.type == TrackType.AUDIO) {
                    Color.parseColor("#2A303D")
                } else {
                    rawColor
                }
                val corner = 6f * density
                canvas.drawRoundRect(tempRect, corner, corner, clipFillPaint)

                val isSelected = clip.id == selectedClipId
                if (isSelected) {
                    clipBorderPaint.color = Color.parseColor("#FF3B6B")
                    canvas.drawRoundRect(tempRect, corner, corner, clipBorderPaint)
                    if (tempRect.width() > 22f * density) {
                        val gripW = 5f * density
                        canvas.drawRoundRect(
                            tempRect.left,
                            tempRect.top,
                            tempRect.left + gripW,
                            tempRect.bottom,
                            3f * density,
                            3f * density,
                            handlePaint,
                        )
                        canvas.drawRoundRect(
                            tempRect.right - gripW,
                            tempRect.top,
                            tempRect.right,
                            tempRect.bottom,
                            3f * density,
                            3f * density,
                            handlePaint,
                        )
                    }
                } else {
                    clipBorderPaint.color = Color.parseColor("#2A303D")
                    canvas.drawRoundRect(tempRect, corner, corner, clipBorderPaint)
                }

                if (track.type == TrackType.AUDIO && tempRect.width() > 28f * density) {
                    val profile = waveforms[clip.id]
                    if (profile != null && profile.peaks.isNotEmpty()) {
                        val spec = clip.audioSpec ?: AudioClipSpec()
                        val gainFactor = AudioMixEngine.dbToLinear(spec.gainDb).coerceIn(0.15f, 1.5f)
                        val barAreaLeft = tempRect.left + 6f * density
                        val barAreaRight = tempRect.right - 6f * density
                        val barSpan = (barAreaRight - barAreaLeft).coerceAtLeast(4f)
                        val stepX = (barSpan / profile.peaks.size.toFloat()).coerceAtLeast(2f)
                        waveformPaint.strokeWidth = (stepX * 0.55f).coerceIn(1.2f * density, 3f * density)
                        waveformPaint.alpha = if (track.isMuted) 90 else 210
                        val midY = tempRect.centerY()
                        val maxHalfH = (tempRect.height() * 0.38f)

                        canvas.save()
                        canvas.clipRect(barAreaLeft, tempRect.top + 2f, barAreaRight, tempRect.bottom - 2f)
                        profile.peaks.forEachIndexed { bIdx, peak ->
                            val normPos = (bIdx + 0.5f) / profile.peaks.size.toFloat()
                            val sampleUs = (normPos * clip.timing.timelineDurationUs).toLong()
                            val fadeEnv = AudioMixEngine.fadeMultiplier(
                                clipElapsedUs = sampleUs,
                                clipRemainingUs = (clip.timing.timelineDurationUs - sampleUs).coerceAtLeast(0L),
                                fadeInUs = spec.fadeInUs,
                                fadeOutUs = spec.fadeOutUs,
                            )
                            val barHalfH = (peak * gainFactor * fadeEnv * maxHalfH).coerceIn(1.5f * density, maxHalfH)
                            val x = barAreaLeft + (bIdx + 0.5f) * stepX
                            if (x <= barAreaRight) {
                                canvas.drawLine(x, midY - barHalfH, x, midY + barHalfH, waveformPaint)
                            }
                        }
                        canvas.restore()
                    }
                }

                if (tempRect.width() > 38f * density && track.type != TrackType.VIDEO) {
                    clipTextPaint.color = Color.WHITE
                    canvas.save()
                    canvas.clipRect(tempRect.left + 6f * density, tempRect.top, tempRect.right - 6f * density, tempRect.bottom)
                    canvas.drawText(
                        clip.label,
                        tempRect.left + 8f * density,
                        tempRect.centerY() + 4f * density,
                        clipTextPaint,
                    )
                    canvas.restore()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = x
                dragStartY = y
                dragStartPlayheadUs = playheadUs
                hasDraggedBeyondSlop = false

                val hit = hitTestClip(x, y)
                if (hit != null && hit.id == selectedClipId) {
                    val leftX = usToScreenX(hit.timing.timelineStartUs)
                    val rightX = usToScreenX(hit.timing.timelineEndUs)
                    val isSecondaryClip = tracks.firstOrNull { it.id == hit.trackId }?.type != TrackType.VIDEO
                    when {
                        abs(x - leftX) <= handleTouchHalfWidthPx -> {
                            activeDragClip = hit
                            previewStartUs = hit.timing.timelineStartUs
                            previewSourceInUs = hit.timing.sourceInUs
                            previewSourceOutUs = hit.timing.sourceOutUs
                            dragMode = DragMode.TRIM_LEFT
                            return true
                        }
                        abs(x - rightX) <= handleTouchHalfWidthPx -> {
                            activeDragClip = hit
                            previewStartUs = hit.timing.timelineStartUs
                            previewSourceInUs = hit.timing.sourceInUs
                            previewSourceOutUs = hit.timing.sourceOutUs
                            dragMode = DragMode.TRIM_RIGHT
                            return true
                        }
                        isSecondaryClip -> {
                            activeDragClip = hit
                            previewStartUs = hit.timing.timelineStartUs
                            previewSourceInUs = hit.timing.sourceInUs
                            previewSourceOutUs = hit.timing.sourceOutUs
                            dragMode = DragMode.MOVE_CLIP
                            return true
                        }
                    }
                }
                activeDragClip = hit
                dragMode = DragMode.SCROLL_TIMELINE
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - dragStartX
                val dy = y - dragStartY
                if (!hasDraggedBeyondSlop && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    hasDraggedBeyondSlop = true
                }
                val deltaUs = (dx / pxPerUs()).roundToLong()
                when (dragMode) {
                    DragMode.SCROLL_TIMELINE -> {
                        // Moving timeline left (dx < 0) advances playhead forward under stationary center needle
                        val maxDur = max(durationUs, 1_000_000L)
                        val scrubbed = frameRate.snapToFrame((dragStartPlayheadUs - deltaUs).coerceIn(0L, maxDur))
                        playheadUs = scrubbed
                        onScrubPlayheadUs?.invoke(scrubbed)
                        invalidate()
                    }
                    DragMode.MOVE_CLIP -> {
                        val clip = activeDragClip ?: return true
                        val track = tracks.firstOrNull { it.id == clip.trackId }
                        val clipDur = clip.timing.timelineDurationUs
                        val prevEnd = track?.clips
                            ?.filter { it.id != clip.id && it.timing.timelineEndUs <= clip.timing.timelineStartUs }
                            ?.maxOfOrNull { it.timing.timelineEndUs } ?: 0L
                        val nextStart = track?.clips
                            ?.filter { it.id != clip.id && it.timing.timelineStartUs >= clip.timing.timelineEndUs }
                            ?.minOfOrNull { it.timing.timelineStartUs } ?: Long.MAX_VALUE
                        val maxStart = if (nextStart == Long.MAX_VALUE) Long.MAX_VALUE else (nextStart - clipDur).coerceAtLeast(prevEnd)
                        val candidate = (clip.timing.timelineStartUs + deltaUs).coerceIn(prevEnd, maxStart)
                        previewStartUs = frameRate.snapToFrame(candidate).coerceIn(prevEnd, maxStart)
                        invalidate()
                    }
                    DragMode.TRIM_LEFT -> {
                        val clip = activeDragClip ?: return true
                        val track = tracks.firstOrNull { it.id == clip.trackId }
                        val prevEnd = track?.clips
                            ?.filter { it.id != clip.id && it.timing.timelineEndUs <= clip.timing.timelineStartUs }
                            ?.maxOfOrNull { it.timing.timelineEndUs } ?: 0L
                        val minDelta = (prevEnd - clip.timing.timelineStartUs).coerceAtLeast(-clip.timing.sourceInUs)
                        val maxDelta = clip.timing.sourceOutUs - clip.timing.sourceInUs - 200_000L
                        val clampedDelta = deltaUs.coerceIn(minDelta, maxDelta.coerceAtLeast(minDelta))
                        previewStartUs = frameRate.snapToFrame((clip.timing.timelineStartUs + clampedDelta).coerceAtLeast(prevEnd))
                        previewSourceInUs = (clip.timing.sourceInUs + clampedDelta).coerceAtLeast(0L)
                        invalidate()
                    }
                    DragMode.TRIM_RIGHT -> {
                        val clip = activeDragClip ?: return true
                        val track = tracks.firstOrNull { it.id == clip.trackId }
                        val nextStart = track?.clips
                            ?.filter { it.id != clip.id && it.timing.timelineStartUs >= clip.timing.timelineEndUs }
                            ?.minOfOrNull { it.timing.timelineStartUs } ?: Long.MAX_VALUE
                        val maxSourceOut = if (nextStart == Long.MAX_VALUE) {
                            Long.MAX_VALUE
                        } else {
                            val maxTimelineDur = (nextStart - clip.timing.timelineStartUs).coerceAtLeast(200_000L)
                            clip.timing.sourceInUs + (maxTimelineDur * clip.timing.playbackSpeed.numerator) / clip.timing.playbackSpeed.denominator
                        }
                        val minOut = clip.timing.sourceInUs + 200_000L
                        previewSourceOutUs = (clip.timing.sourceOutUs + deltaUs).coerceIn(minOut, maxSourceOut.coerceAtLeast(minOut))
                        invalidate()
                    }
                    DragMode.NONE -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val clip = activeDragClip
                when (dragMode) {
                    DragMode.SCROLL_TIMELINE -> {
                        if (!hasDraggedBeyondSlop) {
                            val tapped = hitTestClip(x, y)
                            if (tapped != null) {
                                selectedClipId = tapped.id
                                onSelectClip?.invoke(tapped.id)
                            }
                        }
                    }
                    DragMode.MOVE_CLIP -> if (clip != null && previewStartUs != clip.timing.timelineStartUs) {
                        onMoveClip?.invoke(clip.id, previewStartUs)
                    }
                    DragMode.TRIM_LEFT, DragMode.TRIM_RIGHT -> if (clip != null) {
                        onTrimClip?.invoke(clip.id, previewStartUs, previewSourceInUs, previewSourceOutUs)
                    }
                    DragMode.NONE -> Unit
                }
                dragMode = DragMode.NONE
                activeDragClip = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTestClip(x: Float, y: Float): Clip? {
        orderedTracks().forEachIndexed { index, track ->
            val (top, laneH) = laneTopAndHeight(index)
            val bottom = top + laneH
            if (y in top..bottom) {
                return track.clips.firstOrNull { clip ->
                    val left = usToScreenX(clip.timing.timelineStartUs) - handleTouchHalfWidthPx * 0.5f
                    val right = usToScreenX(clip.timing.timelineEndUs) + handleTouchHalfWidthPx * 0.5f
                    x in left..right
                }
            }
        }
        return null
    }
}

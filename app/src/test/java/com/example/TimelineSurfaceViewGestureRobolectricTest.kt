package com.example

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.Rational
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.feature.editor.TimelineSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimelineSurfaceViewGestureRobolectricTest {

    private fun buildTestTimeline(locked: Boolean = false): TimelineSurfaceView {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return TimelineSurfaceView(context).apply {
            frameRate = Rational.FPS_30
            durationUs = 6_000_000L
            tracks = listOf(
                Track(
                    id = "track_video",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 0,
                    isLocked = locked,
                    clips = listOf(
                        Clip(
                            id = "clip_v1",
                            trackId = "track_video",
                            label = "Main Clip",
                            timing = ClipTiming(0L, 0L, 4_000_000L),
                            mediaSource = MediaSourceRef("mat_1", MediaKind.MATERIAL),
                        ),
                    ),
                ),
            )
            selectedClipId = "clip_v1"
            playheadUs = 0L
            val widthSpec = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            measure(widthSpec, heightSpec)
            layout(0, 0, 1000, 400)
        }
    }

    @Test
    fun `locked track blocks clip trim gestures and notifies locked handler`() {
        val timeline = buildTestTimeline(locked = true)
        var trimmed = false
        var lockedNotifiedTrackId: String? = null
        timeline.onTrimClip = { _, _, _, _ -> trimmed = true }
        timeline.onLockedTrackInteraction = { lockedNotifiedTrackId = it }

        val density = timeline.resources.displayMetrics.density
        // Left trim handle is at centerX (500px) when playheadUs == 0 and startUs == 0
        val handleX = 500f
        val laneY = (28f + 14f + 24f) * density
        val now = SystemClock.uptimeMillis()

        timeline.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, handleX, laneY, 0))
        timeline.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, handleX + 80f, laneY, 0))
        timeline.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, handleX + 80f, laneY, 0))

        assertEquals(false, trimmed)
        assertEquals("track_video", lockedNotifiedTrackId)
    }

    @Test
    fun `tapping empty lane area deselects current clip and updates accessibility text`() {
        val timeline = buildTestTimeline(locked = false)
        var selectedCallback: String? = "clip_v1"
        timeline.onSelectClip = { selectedCallback = it }

        val density = timeline.resources.displayMetrics.density
        // Empty space far to the right past 4s clip end at playhead=0
        val emptyX = 950f
        val laneY = (28f + 14f + 24f) * density
        val now = SystemClock.uptimeMillis()

        timeline.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, emptyX, laneY, 0))
        timeline.dispatchTouchEvent(MotionEvent.obtain(now, now + 15, MotionEvent.ACTION_UP, emptyX, laneY, 0))

        assertNull(selectedCallback)
        assertNull(timeline.selectedClipId)
        assertNotNull(timeline.contentDescription)
        assertTrue(timeline.contentDescription.toString().contains("no clip selected"))
    }

    @Test
    fun `accessibility forward and backward actions step playhead deterministically`() {
        val timeline = buildTestTimeline(locked = false)
        val scrubHistory = mutableListOf<Long>()
        timeline.onScrubPlayheadUs = { scrubHistory.add(it) }

        val forwardHandled = timeline.performAccessibilityAction(
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
            null,
        )
        val backwardHandled = timeline.performAccessibilityAction(
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,
            null,
        )

        assertTrue(forwardHandled)
        assertTrue(backwardHandled)
        assertEquals(listOf(1_000_000L, 0L), scrubHistory)
    }
}

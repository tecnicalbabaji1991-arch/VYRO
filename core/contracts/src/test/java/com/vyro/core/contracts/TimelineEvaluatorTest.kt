package com.vyro.core.contracts

import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.core.model.VisualEffectPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEvaluatorTest {

    private fun baseSequence(): Sequence {
        return Sequence(
            id = "seq_test",
            width = 1920,
            height = 1080,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "track_v",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 1,
                    clips = listOf(
                        Clip(
                            id = "clip_1",
                            trackId = "track_v",
                            label = "Clip One",
                            timing = ClipTiming(0L, 0L, 6_000_000L),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `split clip produces deterministic halves and undo restores exact original`() {
        val initial = baseSequence()
        val history = CommandHistory(initial)

        val splitRes = TimelineEvaluator.apply(
            history.currentSequence,
            EditCommand.SplitClip(clipId = "clip_1", splitAtUs = 3_000_000L, newRightClipId = "clip_2"),
        )
        assertTrue(splitRes is EngineResult.Success)
        val splitSeq = (splitRes as EngineResult.Success).value
        history.commit(splitSeq)

        val clips = splitSeq.tracks.first().clips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].timing.timelineStartUs)
        assertEquals(clips[0].timing.timelineEndUs, clips[1].timing.timelineStartUs)
        assertEquals(6_000_000L, splitSeq.durationUs)

        val undone = history.undo()
        assertNotNull(undone)
        assertEquals(initial, undone)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun `split at invalid boundary fails cleanly without corrupting state`() {
        val initial = baseSequence()
        val res = TimelineEvaluator.apply(
            initial,
            EditCommand.SplitClip(clipId = "clip_1", splitAtUs = 20_000L, newRightClipId = "clip_2"),
        )
        assertTrue(res is EngineResult.Failure)
        assertEquals(EngineErrorCode.INVALID_TIMELINE_EDIT, (res as EngineResult.Failure).error.code)
    }

    @Test
    fun `locked track rejects clip deletion`() {
        val initial = baseSequence()
        val lockedRes = TimelineEvaluator.apply(initial, EditCommand.ToggleTrackLock("track_v"))
        val lockedSeq = (lockedRes as EngineResult.Success).value

        val delRes = TimelineEvaluator.apply(lockedSeq, EditCommand.DeleteClip("clip_1"))
        assertTrue(delRes is EngineResult.Failure)
        assertEquals(EngineErrorCode.INVALID_TIMELINE_EDIT, (delRes as EngineResult.Failure).error.code)
    }

    @Test
    fun `filter effect canvas aspect and cover commands update sequence cleanly`() {
        var seq = baseSequence()
        seq = (TimelineEvaluator.apply(seq, EditCommand.UpdateFilter("clip_1", ColorFilterPreset.CYBER_CYAN)) as EngineResult.Success).value
        seq = (TimelineEvaluator.apply(seq, EditCommand.UpdateEffect("clip_1", VisualEffectPreset.VIGNETTE)) as EngineResult.Success).value
        seq = (TimelineEvaluator.apply(seq, EditCommand.UpdateCanvasAspect(AspectRatioPreset.PORTRAIT_9_16)) as EngineResult.Success).value
        seq = (TimelineEvaluator.apply(seq, EditCommand.UpdateCover(CoverSpec("Intro", 0xFFF34F73L))) as EngineResult.Success).value

        val updatedClip = seq.tracks.first().clips.first()
        assertEquals(ColorFilterPreset.CYBER_CYAN, updatedClip.filterPreset)
        assertEquals(VisualEffectPreset.VIGNETTE, updatedClip.effectPreset)
        assertEquals(1080, seq.width)
        assertEquals(1920, seq.height)
        assertEquals("Intro", seq.coverSpec.label)
    }

    @Test
    fun `add move and trim reject overlaps on same track without corrupting prior state`() {
        var seq = baseSequence()
        val secondClip = Clip(
            id = "clip_2",
            trackId = "track_v",
            label = "Clip Two",
            timing = ClipTiming(6_000_000L, 0L, 3_000_000L),
        )
        seq = (TimelineEvaluator.apply(seq, EditCommand.AddClip("track_v", secondClip)) as EngineResult.Success).value

        // Overlapping add rejected
        val badAdd = TimelineEvaluator.apply(
            seq,
            EditCommand.AddClip(
                "track_v",
                Clip("clip_overlap", "track_v", "Overlap", ClipTiming(5_000_000L, 0L, 2_000_000L)),
            ),
        )
        assertTrue(badAdd is EngineResult.Failure)
        assertEquals(2, seq.tracks.first().clips.size)

        // Overlapping move rejected
        val badMove = TimelineEvaluator.apply(seq, EditCommand.MoveClip("clip_2", 4_000_000L))
        assertTrue(badMove is EngineResult.Failure)

        // Overlapping trim rejected (extending clip_1 past 6_000_000us)
        val badTrim = TimelineEvaluator.apply(seq, EditCommand.TrimClip("clip_1", 0L, 0L, 7_000_000L))
        assertTrue(badTrim is EngineResult.Failure)
        assertEquals(EngineErrorCode.INVALID_TIMELINE_EDIT, (badTrim as EngineResult.Failure).error.code)
        assertTrue(TimelineEvaluator.validateSequence(seq) is EngineResult.Success)
    }

    @Test
    fun `duplicate clip ID on add or split is rejected cleanly`() {
        val seq = baseSequence()
        val dupAdd = TimelineEvaluator.apply(
            seq,
            EditCommand.AddClip(
                "track_v",
                Clip("clip_1", "track_v", "Duplicate", ClipTiming(6_000_000L, 0L, 2_000_000L)),
            ),
        )
        assertTrue(dupAdd is EngineResult.Failure)

        val dupSplit = TimelineEvaluator.apply(
            seq,
            EditCommand.SplitClip("clip_1", 2_000_000L, newRightClipId = "clip_1"),
        )
        assertTrue(dupSplit is EngineResult.Failure)
    }

    @Test
    fun `split on 29_97fps with 2x playback speed stays contiguous with zero gap or overlap`() {
        val seq = Sequence(
            id = "seq_ntsc",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_29_97,
            tracks = listOf(
                Track(
                    id = "track_v",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 1,
                    clips = listOf(
                        Clip(
                            id = "clip_fast",
                            trackId = "track_v",
                            label = "Fast Clip",
                            timing = ClipTiming(0L, 0L, 8_000_000L, Rational.SPEED_2X),
                        ),
                    ),
                ),
            ),
        )
        val splitRes = TimelineEvaluator.apply(
            seq,
            EditCommand.SplitClip("clip_fast", 2_000_000L, "clip_fast_2"),
        )
        assertTrue(splitRes is EngineResult.Success)
        val splitSeq = (splitRes as EngineResult.Success).value
        val clips = splitSeq.tracks.first().clips
        assertEquals(2, clips.size)
        assertEquals(clips[0].timing.timelineEndUs, clips[1].timing.timelineStartUs)
        assertEquals(clips[0].timing.sourceOutUs, clips[1].timing.sourceInUs)
        assertTrue(TimelineEvaluator.validateSequence(splitSeq) is EngineResult.Success)
    }

    @Test
    fun `activeClipsAt respects zIndex ordering and excludes muted tracks`() {
        val seq = Sequence(
            id = "seq_z",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "t_txt",
                    name = "Text",
                    type = TrackType.TEXT,
                    zIndex = 2,
                    clips = listOf(Clip("c_txt", "t_txt", "Title", ClipTiming(0L, 0L, 3_000_000L))),
                ),
                Track(
                    id = "t_aud",
                    name = "Audio",
                    type = TrackType.AUDIO,
                    zIndex = 0,
                    isMuted = true,
                    clips = listOf(Clip("c_aud", "t_aud", "Beat", ClipTiming(0L, 0L, 3_000_000L))),
                ),
                Track(
                    id = "t_vid",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 1,
                    clips = listOf(Clip("c_vid", "t_vid", "Scene", ClipTiming(0L, 0L, 3_000_000L))),
                ),
            ),
        )
        val active = TimelineEvaluator.activeClipsAt(seq, 1_500_000L)
        assertEquals(listOf("c_vid", "c_txt"), active.map { it.id })
    }

    @Test
    fun `multi-step undo and redo restore exact state and enforce history cap`() {
        val initial = baseSequence()
        val history = CommandHistory(initial)
        val steps = mutableListOf(initial)

        var current = initial
        for (i in 1..5) {
            val next = (TimelineEvaluator.apply(
                current,
                EditCommand.MoveClip("clip_1", i * 1_000_000L),
            ) as EngineResult.Success).value
            history.commit(next)
            steps += next
            current = next
        }

        for (i in 4 downTo 0) {
            assertEquals(steps[i], history.undo())
        }
        assertNull(history.undo())
        for (i in 1..5) {
            assertEquals(steps[i], history.redo())
        }
        assertNull(history.redo())
    }
}

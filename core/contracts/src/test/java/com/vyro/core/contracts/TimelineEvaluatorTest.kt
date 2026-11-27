package com.vyro.core.contracts

import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.Rational
import com.vyro.core.model.Sequence
import com.vyro.core.model.TextAnimationPreset
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.Track
import com.vyro.core.model.TrackType
import com.vyro.core.model.TransformKeyframe
import com.vyro.core.model.TransitionPreset
import com.vyro.core.model.TransitionSpec
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
    fun `locked track rejects clip deletion transitions and keyframe edits`() {
        val initial = baseSequence()
        val lockedRes = TimelineEvaluator.apply(initial, EditCommand.ToggleTrackLock("track_v"))
        val lockedSeq = (lockedRes as EngineResult.Success).value

        val delRes = TimelineEvaluator.apply(lockedSeq, EditCommand.DeleteClip("clip_1"))
        assertTrue(delRes is EngineResult.Failure)
        assertEquals(EngineErrorCode.INVALID_TIMELINE_EDIT, (delRes as EngineResult.Failure).error.code)

        val trRes = TimelineEvaluator.apply(
            lockedSeq,
            EditCommand.UpdateTransition("clip_1", TransitionSpec(TransitionPreset.CROSS_DISSOLVE)),
        )
        assertTrue(trRes is EngineResult.Failure)

        val kfRes = TimelineEvaluator.apply(
            lockedSeq,
            EditCommand.AddOrUpdateKeyframe("clip_1", TransformKeyframe(500_000L, scale = 1.4f)),
        )
        assertTrue(kfRes is EngineResult.Failure)
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

        val badAdd = TimelineEvaluator.apply(
            seq,
            EditCommand.AddClip(
                "track_v",
                Clip("clip_overlap", "track_v", "Overlap", ClipTiming(5_000_000L, 0L, 2_000_000L)),
            ),
        )
        assertTrue(badAdd is EngineResult.Failure)
        assertEquals(2, seq.tracks.first().clips.size)

        val badMove = TimelineEvaluator.apply(seq, EditCommand.MoveClip("clip_2", 4_000_000L))
        assertTrue(badMove is EngineResult.Failure)

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
    fun `keyframe add update remove and split partition preserve sorted timeline offsets`() {
        var seq = baseSequence()
        seq = (TimelineEvaluator.apply(
            seq,
            EditCommand.UpdateTransition("clip_1", TransitionSpec(TransitionPreset.SLIDE_LEFT, 400_000L)),
        ) as EngineResult.Success).value
        seq = (TimelineEvaluator.apply(
            seq,
            EditCommand.AddOrUpdateKeyframe("clip_1", TransformKeyframe(1_000_000L, scale = 1.2f)),
        ) as EngineResult.Success).value
        seq = (TimelineEvaluator.apply(
            seq,
            EditCommand.AddOrUpdateKeyframe("clip_1", TransformKeyframe(4_500_000L, scale = 1.8f)),
        ) as EngineResult.Success).value

        val splitRes = TimelineEvaluator.apply(
            seq,
            EditCommand.SplitClip("clip_1", splitAtUs = 3_000_000L, newRightClipId = "clip_2"),
        )
        assertTrue(splitRes is EngineResult.Success)
        val clips = (splitRes as EngineResult.Success).value.tracks.first().clips
        assertEquals(TransitionPreset.SLIDE_LEFT, clips[0].transitionIn.preset)
        assertEquals(TransitionPreset.NONE, clips[1].transitionIn.preset)
        assertEquals(listOf(1_000_000L), clips[0].keyframes.map { it.offsetUs })
        assertEquals(listOf(1_500_000L), clips[1].keyframes.map { it.offsetUs })
        assertTrue(clips[0].keyframesEnabled)
        assertTrue(clips[1].keyframesEnabled)

        val removed = TimelineEvaluator.apply(
            (splitRes).value,
            EditCommand.RemoveKeyframe("clip_1", 1_000_000L),
        ) as EngineResult.Success
        val leftClipAfter = removed.value.tracks.first().clips.first()
        assertTrue(leftClipAfter.keyframes.isEmpty())
        assertFalse(leftClipAfter.keyframesEnabled)
    }

    @Test
    fun `multi-step undo and redo cover every EditCommand type cleanly`() {
        val initial = Sequence(
            id = "seq_full_undo",
            width = 1080,
            height = 1920,
            frameRate = Rational.FPS_30,
            tracks = listOf(
                Track(
                    id = "track_v",
                    name = "Video",
                    type = TrackType.VIDEO,
                    zIndex = 1,
                    clips = listOf(Clip("clip_1", "track_v", "Clip 1", ClipTiming(0L, 0L, 4_000_000L))),
                ),
                Track("track_t", "Text", TrackType.TEXT, zIndex = 2),
                Track("track_a", "Audio", TrackType.AUDIO, zIndex = 0),
            ),
        )
        val history = CommandHistory(initial)
        val snapshots = mutableListOf(initial)

        val commands: List<EditCommand> = listOf(
            EditCommand.AddClip("track_t", Clip("clip_t", "track_t", "Title", ClipTiming(0L, 0L, 2_000_000L), textSpec = TextClipSpec("Hello"))),
            EditCommand.MoveClip("clip_t", 500_000L),
            EditCommand.TrimClip("clip_t", 500_000L, 0L, 2_500_000L),
            EditCommand.UpdateTransform("clip_1", LayerTransform(scale = 1.25f, rotationDegrees = 90f)),
            EditCommand.UpdateTextSpec("clip_t", TextClipSpec("VYRO Pro", animationPreset = TextAnimationPreset.TYPEWRITER)),
            EditCommand.UpdateFilter("clip_1", ColorFilterPreset.VIVID_POP),
            EditCommand.UpdateEffect("clip_1", VisualEffectPreset.SOFT_GLOW),
            EditCommand.UpdateTransition("clip_1", TransitionSpec(TransitionPreset.ZOOM_IN, 500_000L)),
            EditCommand.AddClip("track_a", Clip("clip_a", "track_a", "Beat", ClipTiming(0L, 0L, 3_000_000L), audioSpec = AudioClipSpec())),
            EditCommand.UpdateAudioSpec("clip_a", AudioClipSpec(gainDb = -4.5f, pan = 0.25f, fadeInUs = 250_000L)),
            EditCommand.UpdateCanvasAspect(AspectRatioPreset.SQUARE_1_1),
            EditCommand.UpdateCover(CoverSpec("Phase 10", 0xFF00E5FFL)),
            EditCommand.AddOrUpdateKeyframe("clip_1", TransformKeyframe(0L, scale = 1.0f)),
            EditCommand.AddOrUpdateKeyframe("clip_1", TransformKeyframe(1_500_000L, scale = 1.6f)),
            EditCommand.RemoveKeyframe("clip_1", 0L),
            EditCommand.ToggleKeyframe("clip_1"),
            EditCommand.SplitClip("clip_1", 2_000_000L, "clip_1_b"),
            EditCommand.DeleteClip("clip_1_b"),
            EditCommand.ToggleTrackMute("track_a"),
            EditCommand.ToggleTrackLock("track_t"),
            EditCommand.ToggleTrackLock("track_t"),
        )

        var current = initial
        commands.forEach { cmd ->
            val res = TimelineEvaluator.apply(current, cmd)
            assertTrue("Command failed: ${cmd.commandName}", res is EngineResult.Success)
            current = (res as EngineResult.Success).value
            history.commit(current, cmd.commandName)
            snapshots += current
        }

        for (i in snapshots.size - 2 downTo 0) {
            assertEquals("Undo step $i mismatch", snapshots[i], history.undo())
        }
        assertNull(history.undo())

        for (i in 1 until snapshots.size) {
            assertEquals("Redo step $i mismatch", snapshots[i], history.redo())
        }
        assertNull(history.redo())
    }
}

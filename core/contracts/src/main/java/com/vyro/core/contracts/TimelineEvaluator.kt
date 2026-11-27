package com.vyro.core.contracts

import com.vyro.core.common.EngineError
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.Sequence
import com.vyro.core.model.Track

/**
 * Authoritative deterministic timeline evaluator & command reducer.
 * Preview, timeline UI, and native bridge synchronization derive state from these rules.
 */
object TimelineEvaluator {

    private const val MIN_CLIP_DURATION_US = 100_000L // 100ms minimum slice

    fun apply(sequence: Sequence, command: EditCommand): EngineResult<Sequence> {
        return when (command) {
            is EditCommand.AddClip -> applyAddClip(sequence, command)
            is EditCommand.MoveClip -> applyMoveClip(sequence, command)
            is EditCommand.TrimClip -> applyTrimClip(sequence, command)
            is EditCommand.SplitClip -> applySplitClip(sequence, command)
            is EditCommand.DeleteClip -> applyDeleteClip(sequence, command)
            is EditCommand.UpdateTransform -> applyUpdateTransform(sequence, command)
            is EditCommand.UpdateTextSpec -> applyUpdateTextSpec(sequence, command)
            is EditCommand.UpdateFilter -> applyUpdateFilter(sequence, command)
            is EditCommand.UpdateEffect -> applyUpdateEffect(sequence, command)
            is EditCommand.UpdateAudioSpec -> applyUpdateAudioSpec(sequence, command)
            is EditCommand.UpdateCanvasAspect -> EngineResult.Success(
                sequence.copy(width = command.preset.width, height = command.preset.height),
            )
            is EditCommand.UpdateCover -> EngineResult.Success(sequence.copy(coverSpec = command.coverSpec))
            is EditCommand.ToggleKeyframe -> applyToggleKeyframe(sequence, command)
            is EditCommand.ToggleTrackMute -> applyToggleTrackMute(sequence, command)
            is EditCommand.ToggleTrackLock -> applyToggleTrackLock(sequence, command)
        }
    }

    fun validateSequence(sequence: Sequence): EngineResult<Sequence> {
        if (sequence.width <= 0 || sequence.height <= 0) {
            return invalidEdit("Sequence dimensions must be positive.")
        }
        val trackIds = HashSet<String>()
        val clipIds = HashSet<String>()
        for (track in sequence.tracks) {
            if (!trackIds.add(track.id)) {
                return invalidEdit("Duplicate track ID: ${track.id}")
            }
            val sorted = track.clips.sortedBy { it.timing.timelineStartUs }
            var previousEndUs = 0L
            for (clip in sorted) {
                if (!clipIds.add(clip.id)) {
                    return invalidEdit("Duplicate clip ID: ${clip.id}")
                }
                if (clip.trackId != track.id) {
                    return invalidEdit("Clip ${clip.id} belongs to ${clip.trackId}, expected ${track.id}.")
                }
                if (!clip.timing.isValid()) {
                    return invalidEdit("Clip ${clip.id} has invalid timing bounds.")
                }
                if (clip.timing.timelineStartUs < previousEndUs) {
                    return invalidEdit("Clip ${clip.id} overlaps an earlier clip on ${track.name}.")
                }
                previousEndUs = clip.timing.timelineEndUs
            }
        }
        return EngineResult.Success(sequence)
    }

    fun activeClipsAt(sequence: Sequence, positionUs: Long): List<Clip> {
        return sequence.tracks
            .sortedBy { it.zIndex }
            .filterNot { it.isMuted }
            .flatMap { track ->
                track.clips.filter { it.timing.containsTimelinePosition(positionUs) }
            }
    }

    private fun applyAddClip(sequence: Sequence, cmd: EditCommand.AddClip): EngineResult<Sequence> {
        val snappedStart = sequence.frameRate.snapToFrame(cmd.clip.timing.timelineStartUs)
        val normalizedTiming = cmd.clip.timing.copy(timelineStartUs = snappedStart)
        if (!normalizedTiming.isValid() || normalizedTiming.timelineDurationUs < MIN_CLIP_DURATION_US) {
            return invalidEdit("Clip timing bounds are invalid.")
        }
        if (sequence.tracks.any { t -> t.clips.any { it.id == cmd.clip.id } }) {
            return invalidEdit("Clip ID ${cmd.clip.id} already exists.")
        }
        val targetTrack = sequence.tracks.firstOrNull { it.id == cmd.trackId }
            ?: return invalidEdit("Target track ${cmd.trackId} not found.")
        if (targetTrack.isLocked) {
            return invalidEdit("Track ${targetTrack.name} is locked.")
        }
        val overlap = findOverlappingClip(targetTrack, normalizedTiming, excludeClipId = null)
        if (overlap != null) {
            return invalidEdit("Clip overlaps existing clip ${overlap.label} on ${targetTrack.name}.")
        }
        val placed = cmd.clip.copy(trackId = targetTrack.id, timing = normalizedTiming)
        val updatedTracks = sequence.tracks.map { track ->
            if (track.id == cmd.trackId) {
                track.copy(clips = (track.clips + placed).sortedBy { it.timing.timelineStartUs })
            } else {
                track
            }
        }
        return EngineResult.Success(sequence.copy(tracks = updatedTracks))
    }

    private fun applyMoveClip(sequence: Sequence, cmd: EditCommand.MoveClip): EngineResult<Sequence> {
        if (cmd.newTimelineStartUs < 0L) {
            return invalidEdit("Timeline start cannot be negative.")
        }
        val snappedStart = sequence.frameRate.snapToFrame(cmd.newTimelineStartUs)
        var found = false
        var locked = false
        var overlappingClip: Clip? = null
        val updatedTracks = sequence.tracks.map { track ->
            val target = track.clips.firstOrNull { it.id == cmd.clipId }
            if (target != null) {
                found = true
                if (track.isLocked) {
                    locked = true
                    track
                } else {
                    val candidateTiming = target.timing.copy(timelineStartUs = snappedStart)
                    val overlap = findOverlappingClip(track, candidateTiming, excludeClipId = cmd.clipId)
                    if (overlap != null) {
                        overlappingClip = overlap
                        track
                    } else {
                        val clips = track.clips.map { clip ->
                            if (clip.id == cmd.clipId) {
                                clip.copy(timing = candidateTiming)
                            } else {
                                clip
                            }
                        }.sortedBy { it.timing.timelineStartUs }
                        track.copy(clips = clips)
                    }
                }
            } else {
                track
            }
        }
        if (!found) return invalidEdit("Clip ${cmd.clipId} not found.")
        if (locked) return invalidEdit("Cannot move clip on a locked track.")
        overlappingClip?.let { return invalidEdit("Move overlaps existing clip ${it.label}.") }
        return EngineResult.Success(sequence.copy(tracks = updatedTracks))
    }

    private fun applyTrimClip(sequence: Sequence, cmd: EditCommand.TrimClip): EngineResult<Sequence> {
        if (cmd.newTimelineStartUs < 0L || cmd.newSourceInUs < 0L ||
            cmd.newSourceOutUs <= cmd.newSourceInUs
        ) {
            return invalidEdit("Trim range violates minimum clip duration.")
        }
        var found = false
        var locked = false
        var overlappingClip: Clip? = null
        var invalidBounds = false
        val updatedTracks = sequence.tracks.map { track ->
            val target = track.clips.firstOrNull { it.id == cmd.clipId }
            if (target != null) {
                found = true
                if (track.isLocked) {
                    locked = true
                    track
                } else {
                    val newTiming = target.timing.copy(
                        timelineStartUs = sequence.frameRate.snapToFrame(cmd.newTimelineStartUs),
                        sourceInUs = cmd.newSourceInUs,
                        sourceOutUs = cmd.newSourceOutUs,
                    )
                    if (!newTiming.isValid() || newTiming.timelineDurationUs < MIN_CLIP_DURATION_US) {
                        invalidBounds = true
                        track
                    } else {
                        val overlap = findOverlappingClip(track, newTiming, excludeClipId = cmd.clipId)
                        if (overlap != null) {
                            overlappingClip = overlap
                            track
                        } else {
                            track.copy(
                                clips = track.clips
                                    .map { if (it.id == cmd.clipId) it.copy(timing = newTiming) else it }
                                    .sortedBy { it.timing.timelineStartUs },
                            )
                        }
                    }
                }
            } else {
                track
            }
        }
        if (!found) return invalidEdit("Clip ${cmd.clipId} not found.")
        if (locked) return invalidEdit("Track is locked.")
        if (invalidBounds) return invalidEdit("Trim range violates minimum clip duration.")
        overlappingClip?.let { return invalidEdit("Trim overlaps existing clip ${it.label}.") }
        return EngineResult.Success(sequence.copy(tracks = updatedTracks))
    }

    private fun applySplitClip(sequence: Sequence, cmd: EditCommand.SplitClip): EngineResult<Sequence> {
        if (sequence.tracks.any { t -> t.clips.any { it.id == cmd.newRightClipId } }) {
            return invalidEdit("Split target ID ${cmd.newRightClipId} already exists.")
        }
        val snappedSplitUs = sequence.frameRate.snapToFrame(cmd.splitAtUs)
        var targetTrack: Track? = null
        var targetClip: Clip? = null
        for (track in sequence.tracks) {
            val found = track.clips.firstOrNull { it.id == cmd.clipId }
            if (found != null) {
                targetTrack = track
                targetClip = found
                break
            }
        }
        val track = targetTrack ?: return invalidEdit("Clip ${cmd.clipId} not found.")
        val clip = targetClip ?: return invalidEdit("Clip ${cmd.clipId} not found.")
        if (track.isLocked) return invalidEdit("Cannot split clip on a locked track.")

        val leftDuration = snappedSplitUs - clip.timing.timelineStartUs
        val rightDuration = clip.timing.timelineEndUs - snappedSplitUs
        if (leftDuration < MIN_CLIP_DURATION_US || rightDuration < MIN_CLIP_DURATION_US) {
            return invalidEdit("Playhead must be inside clip away from boundaries to split.")
        }

        val splitSourceUs = clip.timing.timelineToSourceUs(snappedSplitUs)
            ?: return invalidEdit("Split point maps outside valid source bounds.")
        if (splitSourceUs <= clip.timing.sourceInUs || splitSourceUs >= clip.timing.sourceOutUs) {
            return invalidEdit("Split point maps outside valid source bounds.")
        }

        val leftClip = clip.copy(
            timing = clip.timing.copy(sourceOutUs = splitSourceUs),
        )
        val rightTiming = ClipTiming(
            timelineStartUs = leftClip.timing.timelineEndUs,
            sourceInUs = splitSourceUs,
            sourceOutUs = clip.timing.sourceOutUs,
            playbackSpeed = clip.timing.playbackSpeed,
        )
        if (leftClip.timing.timelineDurationUs < MIN_CLIP_DURATION_US ||
            rightTiming.timelineDurationUs < MIN_CLIP_DURATION_US
        ) {
            return invalidEdit("Split slices must be at least ${MIN_CLIP_DURATION_US / 1000}ms.")
        }
        val rightClip = clip.copy(
            id = cmd.newRightClipId,
            label = "${clip.label} (2)",
            timing = rightTiming,
        )

        val updatedTracks = sequence.tracks.map { t ->
            if (t.id == track.id) {
                val nextClips = t.clips.flatMap { c ->
                    if (c.id == clip.id) listOf(leftClip, rightClip) else listOf(c)
                }.sortedBy { it.timing.timelineStartUs }
                t.copy(clips = nextClips)
            } else {
                t
            }
        }
        return EngineResult.Success(sequence.copy(tracks = updatedTracks))
    }

    private fun findOverlappingClip(
        track: Track,
        candidate: ClipTiming,
        excludeClipId: String?,
    ): Clip? {
        return track.clips.firstOrNull { existing ->
            existing.id != excludeClipId && candidate.overlaps(existing.timing)
        }
    }

    private fun applyDeleteClip(sequence: Sequence, cmd: EditCommand.DeleteClip): EngineResult<Sequence> {
        var found = false
        var locked = false
        val updatedTracks = sequence.tracks.map { track ->
            if (track.clips.any { it.id == cmd.clipId }) {
                found = true
                if (track.isLocked) {
                    locked = true
                    track
                } else {
                    track.copy(clips = track.clips.filterNot { it.id == cmd.clipId })
                }
            } else {
                track
            }
        }
        if (!found) return invalidEdit("Clip ${cmd.clipId} not found.")
        if (locked) return invalidEdit("Cannot delete clip on a locked track.")
        return EngineResult.Success(sequence.copy(tracks = updatedTracks))
    }

    private fun applyUpdateTransform(sequence: Sequence, cmd: EditCommand.UpdateTransform): EngineResult<Sequence> {
        var found = false
        val clamped = cmd.transform.copy(
            normalizedX = cmd.transform.normalizedX.coerceIn(0.05f, 0.95f),
            normalizedY = cmd.transform.normalizedY.coerceIn(0.05f, 0.95f),
            scale = cmd.transform.scale.coerceIn(0.35f, 3.0f),
            opacity = cmd.transform.opacity.coerceIn(0.0f, 1.0f),
            crop = cmd.transform.crop.normalized(),
        )
        val updatedTracks = sequence.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == cmd.clipId) {
                        found = true
                        clip.copy(transform = clamped)
                    } else {
                        clip
                    }
                },
            )
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updatedTracks))
        else invalidEdit("Clip ${cmd.clipId} not found.")
    }

    private fun applyUpdateTextSpec(sequence: Sequence, cmd: EditCommand.UpdateTextSpec): EngineResult<Sequence> {
        if (cmd.textSpec.text.isBlank()) {
            return invalidEdit("Text content cannot be blank.")
        }
        var found = false
        val updatedTracks = sequence.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == cmd.clipId) {
                        found = true
                        clip.copy(
                            label = cmd.textSpec.text.take(24),
                            textSpec = cmd.textSpec,
                        )
                    } else {
                        clip
                    }
                },
            )
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updatedTracks))
        else invalidEdit("Text clip ${cmd.clipId} not found.")
    }

    private fun applyUpdateFilter(sequence: Sequence, cmd: EditCommand.UpdateFilter): EngineResult<Sequence> {
        var found = false
        val updatedTracks = sequence.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == cmd.clipId) {
                        found = true
                        clip.copy(filterPreset = cmd.filterPreset)
                    } else {
                        clip
                    }
                },
            )
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updatedTracks))
        else invalidEdit("Clip ${cmd.clipId} not found.")
    }

    private fun applyUpdateEffect(sequence: Sequence, cmd: EditCommand.UpdateEffect): EngineResult<Sequence> {
        var found = false
        val updatedTracks = sequence.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == cmd.clipId) {
                        found = true
                        clip.copy(effectPreset = cmd.effectPreset)
                    } else {
                        clip
                    }
                },
            )
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updatedTracks))
        else invalidEdit("Clip ${cmd.clipId} not found.")
    }

    private fun applyUpdateAudioSpec(sequence: Sequence, cmd: EditCommand.UpdateAudioSpec): EngineResult<Sequence> {
        var found = false
        var locked = false
        val clamped = cmd.audioSpec.copy(
            gainDb = cmd.audioSpec.gainDb.coerceIn(-60.0f, 12.0f),
            pan = cmd.audioSpec.pan.coerceIn(-1.0f, 1.0f),
            fadeInUs = cmd.audioSpec.fadeInUs.coerceAtLeast(0L),
            fadeOutUs = cmd.audioSpec.fadeOutUs.coerceAtLeast(0L),
        )
        val updatedTracks = sequence.tracks.map { track ->
            val hasTarget = track.clips.any { it.id == cmd.clipId }
            if (hasTarget) {
                found = true
                if (track.isLocked) {
                    locked = true
                    track
                } else {
                    track.copy(
                        clips = track.clips.map { clip ->
                            if (clip.id == cmd.clipId) {
                                clip.copy(audioSpec = clamped)
                            } else {
                                clip
                            }
                        },
                    )
                }
            } else {
                track
            }
        }
        if (!found) return invalidEdit("Audio clip ${cmd.clipId} not found.")
        if (locked) return invalidEdit("Cannot edit audio on a locked track.")
        return EngineResult.Success(sequence.copy(tracks = updatedTracks))
    }

    private fun applyToggleKeyframe(sequence: Sequence, cmd: EditCommand.ToggleKeyframe): EngineResult<Sequence> {
        var found = false
        val updatedTracks = sequence.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    if (clip.id == cmd.clipId) {
                        found = true
                        clip.copy(keyframesEnabled = !clip.keyframesEnabled)
                    } else {
                        clip
                    }
                },
            )
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updatedTracks))
        else invalidEdit("Clip ${cmd.clipId} not found.")
    }

    private fun applyToggleTrackMute(sequence: Sequence, cmd: EditCommand.ToggleTrackMute): EngineResult<Sequence> {
        var found = false
        val updated = sequence.tracks.map { track ->
            if (track.id == cmd.trackId) {
                found = true
                track.copy(isMuted = !track.isMuted)
            } else {
                track
            }
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updated))
        else invalidEdit("Track ${cmd.trackId} not found.")
    }

    private fun applyToggleTrackLock(sequence: Sequence, cmd: EditCommand.ToggleTrackLock): EngineResult<Sequence> {
        var found = false
        val updated = sequence.tracks.map { track ->
            if (track.id == cmd.trackId) {
                found = true
                track.copy(isLocked = !track.isLocked)
            } else {
                track
            }
        }
        return if (found) EngineResult.Success(sequence.copy(tracks = updated))
        else invalidEdit("Track ${cmd.trackId} not found.")
    }

    private fun invalidEdit(message: String): EngineResult.Failure = EngineResult.Failure(
        EngineError(
            code = EngineErrorCode.INVALID_TIMELINE_EDIT,
            sourceModule = "core:contracts",
            userMessage = message,
            diagnosticDetail = message,
            recoverable = true,
        ),
    )
}

/**
 * Deterministic Undo/Redo history stack storing Sequence snapshots per committed edit.
 * Enforces [UndoHistoryPolicy.RESET_ON_PROCESS_RESTART] with bounded in-memory depth.
 */
class CommandHistory(
    initialSequence: Sequence,
    val undoPolicy: UndoHistoryPolicy = UndoHistoryPolicy.RESET_ON_PROCESS_RESTART,
    private val maxHistory: Int = UndoHistoryPolicy.MAX_IN_MEMORY_UNDO_STEPS,
) {
    private val undoStack = ArrayDeque<Sequence>()
    private val redoStack = ArrayDeque<Sequence>()
    private val commandTrail = ArrayDeque<String>()
    private var current: Sequence = initialSequence

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val redoDepth: Int get() = redoStack.size
    val currentSequence: Sequence get() = current

    fun commit(nextSequence: Sequence, commandLabel: String? = null) {
        if (nextSequence == current) return
        undoStack.addLast(current)
        while (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        current = nextSequence
        redoStack.clear()
        if (!commandLabel.isNullOrBlank()) {
            recordTrail(commandLabel)
        }
    }

    fun undo(): Sequence? {
        if (undoStack.isEmpty()) return null
        redoStack.addLast(current)
        while (redoStack.size > maxHistory) {
            redoStack.removeFirst()
        }
        current = undoStack.removeLast()
        recordTrail("Undo")
        return current
    }

    fun redo(): Sequence? {
        if (redoStack.isEmpty()) return null
        undoStack.addLast(current)
        while (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
        current = redoStack.removeLast()
        recordTrail("Redo")
        return current
    }

    fun recentCommandLabels(): List<String> = commandTrail.toList()

    fun reset(sequence: Sequence) {
        undoStack.clear()
        redoStack.clear()
        commandTrail.clear()
        current = sequence
    }

    private fun recordTrail(label: String) {
        commandTrail.addLast(label)
        while (commandTrail.size > UndoHistoryPolicy.MAX_PERSISTED_COMMAND_TRAIL) {
            commandTrail.removeFirst()
        }
    }
}

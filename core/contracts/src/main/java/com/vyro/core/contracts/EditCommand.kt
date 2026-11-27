package com.vyro.core.contracts

import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AudioClipSpec
import com.vyro.core.model.Clip
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CoverSpec
import com.vyro.core.model.LayerTransform
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.VisualEffectPreset

sealed interface EditCommand {
    val commandName: String

    data class AddClip(
        val trackId: String,
        val clip: Clip,
    ) : EditCommand {
        override val commandName: String = "AddClip"
    }

    data class MoveClip(
        val clipId: String,
        val newTimelineStartUs: Long,
    ) : EditCommand {
        override val commandName: String = "MoveClip"
    }

    data class TrimClip(
        val clipId: String,
        val newTimelineStartUs: Long,
        val newSourceInUs: Long,
        val newSourceOutUs: Long,
    ) : EditCommand {
        override val commandName: String = "TrimClip"
    }

    data class SplitClip(
        val clipId: String,
        val splitAtUs: Long,
        val newRightClipId: String,
    ) : EditCommand {
        override val commandName: String = "SplitClip"
    }

    data class DeleteClip(
        val clipId: String,
    ) : EditCommand {
        override val commandName: String = "DeleteClip"
    }

    data class UpdateTransform(
        val clipId: String,
        val transform: LayerTransform,
    ) : EditCommand {
        override val commandName: String = "UpdateTransform"
    }

    data class UpdateTextSpec(
        val clipId: String,
        val textSpec: TextClipSpec,
    ) : EditCommand {
        override val commandName: String = "UpdateTextSpec"
    }

    data class UpdateFilter(
        val clipId: String,
        val filterPreset: ColorFilterPreset,
    ) : EditCommand {
        override val commandName: String = "UpdateFilter"
    }

    data class UpdateEffect(
        val clipId: String,
        val effectPreset: VisualEffectPreset,
    ) : EditCommand {
        override val commandName: String = "UpdateEffect"
    }

    data class UpdateAudioSpec(
        val clipId: String,
        val audioSpec: AudioClipSpec,
    ) : EditCommand {
        override val commandName: String = "UpdateAudioSpec"
    }

    data class UpdateCanvasAspect(
        val preset: AspectRatioPreset,
    ) : EditCommand {
        override val commandName: String = "UpdateCanvasAspect"
    }

    data class UpdateCover(
        val coverSpec: CoverSpec,
    ) : EditCommand {
        override val commandName: String = "UpdateCover"
    }

    data class ToggleKeyframe(
        val clipId: String,
    ) : EditCommand {
        override val commandName: String = "ToggleKeyframe"
    }

    data class ToggleTrackMute(
        val trackId: String,
    ) : EditCommand {
        override val commandName: String = "ToggleTrackMute"
    }

    data class ToggleTrackLock(
        val trackId: String,
    ) : EditCommand {
        override val commandName: String = "ToggleTrackLock"
    }

    fun toWireJson(): String = """{"command":"$commandName"}"""
}

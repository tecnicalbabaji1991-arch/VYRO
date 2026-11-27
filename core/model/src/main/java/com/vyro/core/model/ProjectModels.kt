package com.vyro.core.model

enum class TrackType {
    VIDEO,
    TEXT,
    AUDIO,
}

enum class MediaKind {
    MATERIAL,
    PHOTO,
    VIDEO,
    AUDIO,
}

enum class AssetAvailability {
    AVAILABLE,
    MISSING,
    PERMISSION_REVOKED,
    UNSUPPORTED_CODEC,
}

enum class AspectRatioPreset(val label: String, val width: Int, val height: Int) {
    LANDSCAPE_16_9("16:9", 1920, 1080),
    PORTRAIT_9_16("9:16", 1080, 1920),
    SQUARE_1_1("1:1", 1080, 1080),
}

enum class TextAnimationPreset(val displayName: String) {
    NONE("None"),
    FADE_IN("Fade In"),
    SLIDE_UP("Slide Up"),
    TYPEWRITER("Typewriter"),
    SCALE_POP("Scale Pop"),
}

enum class ColorFilterPreset(val displayName: String) {
    ORIGINAL("Original"),
    CYBER_CYAN("Cyber"),
    WARM_AMBER("Warm"),
    NOIR_MONO("Noir"),
    VIVID_POP("Vivid"),
}

enum class VisualEffectPreset(val displayName: String) {
    NONE("None"),
    VIGNETTE("Vignette"),
    SOFT_GLOW("Glow"),
    SCANLINES("Studio"),
}

enum class BlendMode(val displayName: String) {
    NORMAL("Normal"),
    SCREEN("Screen"),
    MULTIPLY("Multiply"),
    ADD("Add"),
    OVERLAY("Overlay"),
}

enum class TransitionPreset(val displayName: String) {
    NONE("None"),
    CROSS_DISSOLVE("Dissolve"),
    SLIDE_LEFT("Slide"),
    ZOOM_IN("Zoom"),
}

data class TransitionSpec(
    val preset: TransitionPreset = TransitionPreset.NONE,
    val durationUs: Long = 500_000L,
)

data class TransformKeyframe(
    val offsetUs: Long,
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotationDegrees: Float = 0.0f,
    val opacity: Float = 1.0f,
)

data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val widthFraction: Float
        get() = (right - left).coerceIn(0.05f, 1f)

    val heightFraction: Float
        get() = (bottom - top).coerceIn(0.05f, 1f)

    fun isFullFrame(): Boolean =
        left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    fun isValid(): Boolean =
        left in 0f..0.95f &&
            top in 0f..0.95f &&
            right in 0.05f..1f &&
            bottom in 0.05f..1f &&
            (right - left) >= 0.05f &&
            (bottom - top) >= 0.05f

    fun normalized(): CropRect {
        val l = left.coerceIn(0f, 0.95f)
        val t = top.coerceIn(0f, 0.95f)
        val r = right.coerceIn(l + 0.05f, 1f)
        val b = bottom.coerceIn(t + 0.05f, 1f)
        return CropRect(left = l, top = t, right = r, bottom = b)
    }
}

data class MediaAsset(
    val assetId: String,
    val uriString: String? = null,
    val materialId: String? = null,
    val displayName: String,
    val mediaKind: MediaKind,
    val mimeType: String = "",
    val containerFormat: String = "",
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val durationUs: Long = 0L,
    val nominalFps: Float = 30f,
    val isVfrSuspected: Boolean = false,
    val audioSampleRateHz: Int = 0,
    val audioChannelCount: Int = 0,
    val bitrateBps: Long = 0L,
    val fileSizeBytes: Long = 0L,
    val colorInfo: String = "SDR",
    val isHdr: Boolean = false,
    val codecSupported: Boolean = true,
    val availability: AssetAvailability = AssetAvailability.AVAILABLE,
    val diagnosticNote: String = "",
    val addedAtEpochMs: Long = 0L,
)

data class MediaSourceRef(
    val assetId: String? = null,
    val mediaKind: MediaKind = MediaKind.MATERIAL,
    val uriString: String? = null,
    val materialId: String? = "white_canvas",
    val backdropColorArgb: Long = 0xFFFFFFFFL,
)

data class LayerTransform(
    val normalizedX: Float = 0.5f,
    val normalizedY: Float = 0.5f,
    val scale: Float = 1.0f,
    val rotationDegrees: Float = 0.0f,
    val opacity: Float = 1.0f,
    val crop: CropRect = CropRect(),
    val blendMode: BlendMode = BlendMode.NORMAL,
)

data class TextClipSpec(
    val text: String,
    val fontId: String = "space_grotesk",
    val colorArgb: Long = 0xFFFFFFFFL,
    val fontSizeSp: Float = 28f,
    val animationPreset: TextAnimationPreset = TextAnimationPreset.NONE,
)

data class AudioClipSpec(
    val gainDb: Float = 0.0f,
    val pan: Float = 0.0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
)

data class CoverSpec(
    val label: String = "Cover",
    val accentColorArgb: Long = 0xFF1E222AL,
)

data class Clip(
    val id: String,
    val trackId: String,
    val label: String,
    val timing: ClipTiming,
    val mediaSource: MediaSourceRef = MediaSourceRef(),
    val transform: LayerTransform = LayerTransform(),
    val textSpec: TextClipSpec? = null,
    val audioSpec: AudioClipSpec? = null,
    val filterPreset: ColorFilterPreset = ColorFilterPreset.ORIGINAL,
    val effectPreset: VisualEffectPreset = VisualEffectPreset.NONE,
    val transitionIn: TransitionSpec = TransitionSpec(),
    val keyframesEnabled: Boolean = false,
    val keyframes: List<TransformKeyframe> = emptyList(),
    val accentColorArgb: Long = 0xFFFFFFFFL,
)

data class Track(
    val id: String,
    val name: String,
    val type: TrackType,
    val zIndex: Int,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
    val clips: List<Clip> = emptyList(),
)

data class Sequence(
    val id: String,
    val width: Int,
    val height: Int,
    val frameRate: Rational,
    val tracks: List<Track>,
    val coverSpec: CoverSpec = CoverSpec(),
) {
    val durationUs: Long
        get() = tracks.flatMap { it.clips }.maxOfOrNull { it.timing.timelineEndUs } ?: 0L
}

data class ProjectDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val projectId: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val sequence: Sequence,
    val assets: List<MediaAsset> = emptyList(),
    val revision: Long = 1L,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

data class ProjectSummary(
    val projectId: String,
    val title: String,
    val width: Int,
    val height: Int,
    val fpsNumerator: Long,
    val fpsDenominator: Long,
    val durationUs: Long,
    val clipCount: Int,
    val updatedAtEpochMs: Long,
    val recoveredFromBackup: Boolean = false,
    val previewColorArgb: Long = 0xFF1C2029L,
    val previewUriString: String? = null,
    val missingAssetCount: Int = 0,
    val schemaVersion: Int = ProjectDocument.CURRENT_SCHEMA_VERSION,
    val hasPendingRecoverySnapshot: Boolean = false,
    val isCorrupt: Boolean = false,
) {
    val aspectLabel: String
        get() = when {
            width > height -> "16:9"
            height > width -> "9:16"
            else -> "1:1"
        }
}

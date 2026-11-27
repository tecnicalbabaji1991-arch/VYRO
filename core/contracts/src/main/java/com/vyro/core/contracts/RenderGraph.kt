package com.vyro.core.contracts

import android.view.Surface
import com.vyro.core.common.EngineResult
import com.vyro.core.model.BlendMode
import com.vyro.core.model.ColorFilterPreset
import com.vyro.core.model.CropRect
import com.vyro.core.model.MediaKind
import com.vyro.core.model.TextClipSpec
import com.vyro.core.model.VisualEffectPreset
import kotlinx.coroutines.flow.StateFlow

enum class CompositionBackendType {
    GLES_30,
    GLES_20,
    SOFTWARE_CANVAS,
}

data class GpuCapabilities(
    val backendType: CompositionBackendType = CompositionBackendType.GLES_20,
    val rendererName: String = "Software Fallback",
    val vendorName: String = "Android",
    val glesVersion: String = "OpenGL ES 2.0",
    val maxTextureSize: Int = 4096,
    val maxTextureUnits: Int = 8,
    val supportsExternalOesTexture: Boolean = false,
    val supportsVulkan: Boolean = false,
    val vulkanPrototypeReason: String = "GLES 3.0 covers API 30+ layer composition within 16.6ms budget; Vulkan deferred until compute-shader effects.",
)

data class CompositionNode(
    val clipId: String,
    val trackId: String,
    val zIndex: Int,
    val mediaKind: MediaKind,
    val sourceUri: String?,
    val materialId: String?,
    val backdropColorArgb: Long,
    val sourcePtsUs: Long,
    val normalizedX: Float,
    val normalizedY: Float,
    val scale: Float,
    val rotationDegrees: Float,
    val opacity: Float,
    val crop: CropRect,
    val blendMode: BlendMode,
    val filterPreset: ColorFilterPreset,
    val effectPreset: VisualEffectPreset,
    val textSpec: TextClipSpec? = null,
    val animatedAlpha: Float = 1.0f,
    val animatedTranslationY: Float = 0.0f,
    val animatedScale: Float = 1.0f,
    val modelMatrix: List<Float>,
) {
    fun toColumnMajorMatrix(): FloatArray = FloatArray(16) { idx -> modelMatrix.getOrElse(idx) { if (idx % 5 == 0) 1f else 0f } }
}

data class RenderGraph(
    val sequenceId: String,
    val timelineUs: Long,
    val outputWidth: Int,
    val outputHeight: Int,
    val backgroundArgb: Long = 0xFF070A0FL,
    val layers: List<CompositionNode> = emptyList(),
)

data class RenderDiagnosticsSnapshot(
    val backendType: CompositionBackendType = CompositionBackendType.GLES_20,
    val lastFrameRenderTimeUs: Long = 0L,
    val averageFrameRenderTimeUs: Long = 0L,
    val frameBudgetUs: Long = DEFAULT_FRAME_BUDGET_US,
    val withinFrameBudget: Boolean = true,
    val activeLayerCount: Int = 0,
    val activeTextureCount: Int = 0,
    val activeFboCount: Int = 0,
    val activeProgramCount: Int = 0,
    val leakedGpuResources: Int = 0,
    val surfaceRecreatedCount: Int = 0,
    val surfaceAttached: Boolean = false,
    val gpuCapabilities: GpuCapabilities = GpuCapabilities(),
    val lastError: String? = null,
) {
    companion object {
        const val DEFAULT_FRAME_BUDGET_US = 16_666L
    }
}

interface CompositionRendererContract {
    val renderTelemetry: StateFlow<RenderDiagnosticsSnapshot>

    fun attachSurface(surface: Surface?, width: Int, height: Int)

    fun detachSurface(surface: Surface?)

    fun renderGraph(graph: RenderGraph): EngineResult<RenderDiagnosticsSnapshot>

    fun queryGpuCapabilities(): GpuCapabilities

    fun releaseGpuResources()

    fun release()
}

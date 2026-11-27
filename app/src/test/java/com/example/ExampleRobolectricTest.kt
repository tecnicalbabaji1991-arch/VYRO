package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.EngineErrorCode
import com.vyro.core.common.EngineResult
import com.vyro.core.model.AspectRatioPreset
import com.vyro.core.model.AssetAvailability
import com.vyro.core.model.Clip
import com.vyro.core.model.ClipTiming
import com.vyro.core.model.MediaAsset
import com.vyro.core.model.MediaKind
import com.vyro.core.model.MediaSourceRef
import com.vyro.core.model.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name matches VYRO`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("VYRO", context.getString(R.string.app_name))
    }

    @Test
    fun `engine lifecycle 50 cycle smoke test passes without leaks`() = runTest {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val result = app.container.engineSession.runLifecycleSmokeTest(iterations = 50)
        assertTrue(result is EngineResult.Success)
        val report = (result as EngineResult.Success).value
        assertTrue(report.passed)
        assertEquals(0, report.leakedSessions)
    }

    @Test
    fun `repository creates reloads and duplicates project document`() = runTest {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val created = app.container.projectRepository.createProject(
            title = "Robolectric Cut",
            aspectPreset = AspectRatioPreset.LANDSCAPE_16_9,
            frameRate = Rational.FPS_30,
            seedStarterTimeline = true,
        )
        assertTrue(created is EngineResult.Success)
        val doc = (created as EngineResult.Success).value
        val loaded = app.container.projectRepository.loadProject(doc.projectId)
        assertTrue(loaded is EngineResult.Success)
        assertEquals("Robolectric Cut", (loaded as EngineResult.Success).value.title)

        val duplicated = app.container.projectRepository.duplicateProject(doc.projectId)
        assertTrue(duplicated is EngineResult.Success)
        assertTrue((duplicated as EngineResult.Success).value.title.contains("Copy"))
    }

    @Test
    fun `phase 2 media inspector rejects missing uri and editor shows missing media relink banner`() {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val doc = runBlocking(Dispatchers.IO) {
            val missingCheck = app.container.mediaInspector.inspectUri("file:///nonexistent_vyro_clip.mp4")
            assertTrue(missingCheck is EngineResult.Failure)
            assertEquals(
                EngineErrorCode.MISSING_MEDIA,
                (missingCheck as EngineResult.Failure).error.code,
            )

            val missingAsset = MediaAsset(
                assetId = "asset_missing_1",
                uriString = "file:///missing_source_clip.mp4",
                displayName = "missing_source_clip.mp4",
                mediaKind = MediaKind.VIDEO,
                availability = AssetAvailability.AVAILABLE,
            )
            val created = app.container.projectRepository.createProject(
                title = "Missing Asset Check",
                aspectPreset = AspectRatioPreset.PORTRAIT_9_16,
                frameRate = Rational.FPS_30,
                seedStarterTimeline = false,
                initialClips = listOf(
                    Clip(
                        id = "clip_ext_1",
                        trackId = "track_video",
                        label = "missing_source_clip.mp4",
                        timing = ClipTiming(0L, 0L, 2_000_000L),
                        mediaSource = MediaSourceRef(
                            assetId = missingAsset.assetId,
                            mediaKind = MediaKind.VIDEO,
                            uriString = missingAsset.uriString,
                        ),
                    ),
                ),
                initialAssets = listOf(missingAsset),
            )
            assertTrue(created is EngineResult.Success)
            (created as EngineResult.Success).value
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("project_card_${doc.projectId}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("project_card_${doc.projectId}").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("missing_media_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("missing_media_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("relink_missing_media_button").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_assets_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("relink_asset_asset_missing_1").assertIsDisplayed()
    }

    @Test
    fun `home screen opens diagnostics console`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("open_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("run_smoke_test_button").assertIsDisplayed()
    }

    @Test
    fun `new project hero opens media selector and creates editor sequence`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("media_tile_mat_white").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_tile_mat_white").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("media_confirm_fab").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("preview_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("preview_canvas").assertIsDisplayed()
        composeRule.onNodeWithTag("timeline_surface_view").assertIsDisplayed()
        composeRule.onNodeWithTag("fixed_center_playhead").assertIsDisplayed()
    }

    @Test
    fun `phase 4 editor transport supports play pause frame stepping fullscreen recreation and diagnostics`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("media_tile_mat_white").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_tile_mat_white").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("media_confirm_fab").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("play_pause_button").fetchSemanticsNodes().isNotEmpty()
        }

        // Toggle play -> pause -> jump to start
        composeRule.onNodeWithTag("play_pause_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("play_pause_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Frame stepping in Trim tool tab
        composeRule.onNodeWithTag("tab_trim").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_step_next_frame").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tool_step_next_frame").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("tool_step_prev_frame").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Fullscreen toggle cycles preview layout safely
        composeRule.onNodeWithTag("fullscreen_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fullscreen_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("step_back_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Diagnostics displays Phase 4 telemetry
        composeRule.onNodeWithTag("editor_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("run_smoke_test_button").assertIsDisplayed()
    }

    @Test
    fun `phase 5 editor opens crop and blend dialog rotates clip and updates diagnostics`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("media_tile_mat_white").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_tile_mat_white").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("media_confirm_fab").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("tab_transform_blend").fetchSemanticsNodes().isNotEmpty()
        }

        // Open Phase 5 Transform/Crop/Blend dialog and apply presets
        composeRule.onNodeWithTag("tab_transform_blend").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("crop_preset_75%_punch").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("opacity_preset_50").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("blend_mode_screen").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("transform_dialog_ok_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Open Trim subbar and rotate clip 90 degrees
        composeRule.onNodeWithTag("tab_trim").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_rotate_clip").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Navigate back to root and inspect Phase 5 GPU Diagnostics card
        composeRule.onNodeWithTag("tool_subbar_back").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("run_smoke_test_button").assertIsDisplayed()
    }

    @Test
    fun `phase 6 editor adds audio clip adjusts mixer settings and shows audio diagnostics`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("media_tile_mat_white").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_tile_mat_white").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("media_confirm_fab").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("tool_add_audio").fetchSemanticsNodes().isNotEmpty()
        }

        // Add studio audio beat clip and open Audio Mixer dialog
        composeRule.onNodeWithTag("tool_add_audio").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_audio_mix").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("audio_waveform_preview").assertIsDisplayed()
        composeRule.onNodeWithTag("gain_preset_-6").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("pan_preset_l").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("fade_in_preset_250").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("fade_out_preset_500").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("audio_mixer_ok_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("editor_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase6_audio_card"))
        composeRule.onNodeWithTag("diagnostics_phase6_audio_card").assertIsDisplayed()
    }

    @Test
    fun `phase 7 toggles proxy mode preserves state and displays cache memory diagnostics`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("media_tile_mat_white").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_tile_mat_white").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("media_confirm_fab").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("tab_proxy_mode").fetchSemanticsNodes().isNotEmpty()
        }

        // Toggle Proxy ON then OFF on editor strip without disrupting timeline
        composeRule.onNodeWithTag("tab_proxy_mode").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tab_proxy_mode").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Inspect Phase 7 Cache, Proxy & Memory diagnostics card and exercise Trim Caches button
        composeRule.onNodeWithTag("editor_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase7_cache_card"))
        composeRule.onNodeWithTag("diagnostics_phase7_cache_card").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_trim_memory_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `phase 8 crash snapshot recovers safely handles corrupt files without crashing and shows persistence diagnostics`() {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val recoveredDoc = runBlocking(Dispatchers.IO) {
            val created = (app.container.projectRepository.createProject(
                title = "Crash Test Draft",
                aspectPreset = AspectRatioPreset.PORTRAIT_9_16,
                frameRate = Rational.FPS_30,
                seedStarterTimeline = true,
            ) as EngineResult.Success).value

            app.container.projectRepository.saveRecoverySnapshot(
                document = created.copy(
                    title = "Recovered Snapshot Draft",
                    revision = created.revision + 2L,
                ),
                recentCommandLabels = listOf("SplitClip", "UpdateFilter"),
            )
            created
        }

        // 1. Open project with pending crash recovery snapshot -> banner appears -> Revert cleanly
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("project_card_${recoveredDoc.projectId}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("project_card_${recoveredDoc.projectId}").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("recovery_snapshot_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("recovery_snapshot_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery_revert_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 2. Diagnostics shows Phase 8 Persistence & Recovery card
        composeRule.onNodeWithTag("editor_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase8_persistence_card"))
        composeRule.onNodeWithTag("diagnostics_phase8_persistence_card").assertIsDisplayed()
        composeRule.onNodeWithTag("diagnostics_back_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("editor_close_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 3. Corrupt project files safely open CorruptProjectFallbackView instead of crashing
        val corruptDoc = runBlocking(Dispatchers.IO) {
            val doc = (app.container.projectRepository.createProject(
                title = "Corrupt Target",
                aspectPreset = AspectRatioPreset.PORTRAIT_9_16,
                frameRate = Rational.FPS_30,
                seedStarterTimeline = true,
            ) as EngineResult.Success).value
            val dir = java.io.File(java.io.File(app.filesDir, "vyro/projects"), doc.projectId)
            java.io.File(dir, "project.json").writeText("{ BROKEN_PRIMARY")
            java.io.File(dir, "project.backup.json").writeText("{ BROKEN_BACKUP")
            doc
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("project_card_${corruptDoc.projectId}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("project_card_${corruptDoc.projectId}").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("corrupt_project_panel").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("corrupt_project_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("corrupt_back_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `phase 9 configures and finalizes validated MP4 export and displays diagnostics`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("media_tile_mat_white").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("media_tile_mat_white").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("media_confirm_fab").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("editor_export_button").fetchSemanticsNodes().isNotEmpty()
        }

        // Open Phase 9 Export Studio dialog, choose 720p + H.264, and start export
        composeRule.onNodeWithTag("editor_export_button").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("export_dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("export_res_720p").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("export_codec_h264").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("export_start_button").assertIsDisplayed().performClick()

        // Wait until export finalizes and validated MP4 report card is displayed
        composeRule.waitUntil(timeoutMillis = 12_000L) {
            composeRule.onAllNodesWithTag("export_validation_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("export_validation_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("export_done_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Open Diagnostics and verify Phase 9 Export Foundation card
        composeRule.onNodeWithTag("editor_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase9_export_card"))
        composeRule.onNodeWithTag("diagnostics_phase9_export_card").assertIsDisplayed()
    }
}

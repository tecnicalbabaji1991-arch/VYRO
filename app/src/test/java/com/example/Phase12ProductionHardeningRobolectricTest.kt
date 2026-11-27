package com.example

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.vyro.core.common.EngineResult
import com.vyro.core.contracts.ThermalStatusLevel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase12ProductionHardeningRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `play policy compliance verifies zero broad storage permissions and valid metadata on live application`() {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val pm = app.packageManager
        val pkgInfo = pm.getPackageInfo(
            app.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
        )
        val requested = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse(requested.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(requested.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertFalse(requested.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(requested.contains("android.permission.READ_MEDIA_VIDEO"))
        assertFalse(requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))

        val title = app.getString(R.string.app_name)
        assertTrue(title.isNotBlank() && title.length <= 30)

        app.container.runtimeHardeningMonitor.refreshSystemTelemetry()
        val snap = app.container.runtimeHardeningMonitor.snapshot.value
        assertTrue(snap.playPolicyCompliant)
        assertTrue(snap.totalRamMb > 0L)
    }

    @Test
    fun `diagnostics phase 12 card reflects thermal throttling and runs 120-step hardening stress suite`() {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val pm = app.getSystemService(android.os.PowerManager::class.java)
        org.robolectric.Shadows.shadowOf(pm).setCurrentThermalStatus(android.os.PowerManager.THERMAL_STATUS_SEVERE)
        app.container.runtimeHardeningMonitor.onThermalStatusChanged(ThermalStatusLevel.SEVERE)

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("open_diagnostics_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("open_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase12_hardening_card"))
        composeRule.onNodeWithTag("diagnostics_phase12_hardening_card").assertIsDisplayed()

        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_run_hardening_stress_button"))
        composeRule.onNodeWithTag("diagnostics_run_hardening_stress_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 12_000L) {
            composeRule.onAllNodesWithTag("diagnostics_phase12_stress_result").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase12_stress_result"))
        composeRule.onNodeWithTag("diagnostics_phase12_stress_result").assertIsDisplayed()

        val snap = app.container.runtimeHardeningMonitor.snapshot.value
        assertTrue(snap.totalStressCyclesPassed >= 120)
        assertEquals(ThermalStatusLevel.SEVERE, snap.thermalStatus)

        org.robolectric.Shadows.shadowOf(pm).setCurrentThermalStatus(android.os.PowerManager.THERMAL_STATUS_NONE)
        app.container.runtimeHardeningMonitor.onThermalStatusChanged(ThermalStatusLevel.NONE)
    }

    @Test
    fun `permission revocation asset triggers relink banner and opens relink flow cleanly`() {
        val app = ApplicationProvider.getApplicationContext<VyroApplication>()
        val created = runBlocking(Dispatchers.IO) {
            val revokedAsset = MediaAsset(
                assetId = "asset_revoked_ui",
                uriString = "content://com.android.providers.media.documents/document/video%3A404",
                displayName = "revoked_clip.mp4",
                mediaKind = MediaKind.VIDEO,
                availability = AssetAvailability.PERMISSION_REVOKED,
                diagnosticNote = "Read permission revoked",
            )
            (app.container.projectRepository.createProject(
                title = "Revoked Asset UI Check",
                aspectPreset = AspectRatioPreset.PORTRAIT_9_16,
                frameRate = Rational.FPS_30,
                seedStarterTimeline = false,
                initialClips = listOf(
                    Clip(
                        id = "clip_revoked_1",
                        trackId = "track_video",
                        label = "revoked_clip.mp4",
                        timing = ClipTiming(0L, 0L, 2_000_000L),
                        mediaSource = MediaSourceRef(
                            assetId = revokedAsset.assetId,
                            mediaKind = MediaKind.VIDEO,
                            uriString = revokedAsset.uriString,
                        ),
                    ),
                ),
                initialAssets = listOf(revokedAsset),
            ) as EngineResult.Success).value
        }

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("project_card_${created.projectId}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("project_card_${created.projectId}").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 8_000L) {
            composeRule.onAllNodesWithTag("missing_media_banner").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("missing_media_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("relink_missing_media_button").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_assets_button").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("relink_asset_asset_revoked_ui").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("relink_asset_asset_revoked_ui").assertIsDisplayed()
        composeRule.waitForIdle()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w840dp-h480dp")
    fun `tablet landscape device matrix renders home and diagnostics hardening card cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.resources.configuration.screenWidthDp >= 800)

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag("open_diagnostics_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("new_project_hero_card").assertIsDisplayed()
        composeRule.onNodeWithTag("open_diagnostics_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("diagnostics_lazy_column")
            .performScrollToNode(hasTestTag("diagnostics_phase12_hardening_card"))
        composeRule.onNodeWithTag("diagnostics_phase12_hardening_card").assertIsDisplayed()
    }
}

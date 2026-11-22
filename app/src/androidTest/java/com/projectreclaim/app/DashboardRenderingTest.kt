package com.projectreclaim.app

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Run explicitly on an emulator; never use connectedAndroidTest against a personal device. */
class DashboardRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun emulatorOnly() {
        assumeTrue("Rendering fixtures are emulator-only", Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test
    fun populatedAndExpandableDetails() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.POPULATED) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("49m").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Daily average · previous 3 completed days").assertIsDisplayed()
        compose.onNodeWithText("52m").assertIsDisplayed()
        screenshot("populated-main")
        compose.onNodeWithText("View daily values").performScrollTo()
        compose.onNodeWithContentDescription("Oct 28. Instagram Reels: 12m. YouTube Shorts: 6m").assertExists()
        showHistoryScope()
        screenshot("populated-history")
        compose.onNodeWithText("View daily values").performClick()
        compose.onNodeWithText("Hide daily values").assertIsDisplayed()
        compose.onNodeWithText("View app breakdown").performScrollTo().performClick()
        compose.onNodeWithText("Instagram", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        screenshot("populated-app-details")
    }

    @Test
    fun emptyRetainsZeroAndHistoryScope() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.EMPTY) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("No short-form time recorded today.").assertIsDisplayed()
        screenshot("empty-main")
        compose.onNodeWithText("No saved Reels or Shorts time in these days.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Oct 28. Instagram Reels: 0s. YouTube Shorts: 0s").assertExists()
        showHistoryScope()
        screenshot("empty-history")
    }

    @Test
    fun pausedTrackingRetainsPartialWarningAndGuidance() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.PARTIAL) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("Partial · Reels and Shorts tracking is paused.").assertIsDisplayed()
        screenshot("partial-main")
        compose.onNodeWithText("Open Accessibility settings").performScrollTo().assertIsDisplayed()
        screenshot("partial-setup")
        compose.onNodeWithText("Tracking paused · saved sessions remain visible. Gaps are not reconstructed.")
            .performScrollTo().assertIsDisplayed()
        screenshot("partial-history")
    }

    @Test
    fun unavailableDoesNotBecomeZero() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.UNAVAILABLE) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("Couldn’t load the complete total.").assertIsDisplayed()
        compose.onNodeWithText("Average unavailable.").assertIsDisplayed()
        compose.onNodeWithText("No short-form time recorded today.").assertDoesNotExist()
        screenshot("unavailable-main")
        compose.onNodeWithText("Couldn’t load saved history. Return to the app to retry.")
            .performScrollTo().assertIsDisplayed()
        screenshot("unavailable-history")
    }

    @Test
    fun missingPermissionsAreActionable() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.PERMISSION_MISSING) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("Usage Access is needed to include TikTok foreground time.").assertIsDisplayed()
        compose.onNodeWithText("Needs Usage Access.").assertIsDisplayed()
        screenshot("permission-missing-main")
        compose.onNodeWithText("Open Usage Access settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Accessibility settings").performScrollTo().assertIsDisplayed()
        screenshot("permission-missing-setup")
    }

    @Test
    fun loadingIsExplicit() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.LOADING) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("Gathering today’s usage.").assertIsDisplayed()
        compose.onNodeWithText("Loading average…").assertIsDisplayed()
        compose.onNodeWithText("Checking setup…").assertExists()
        screenshot("loading-main")
    }

    @Test
    fun darkPalette() {
        compose.setContent { DashboardPreviewScreen(DashboardFixture.POPULATED, darkTheme = true) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("49m").assertIsDisplayed()
        screenshot("dark-main")
        compose.onNodeWithText("View daily values").performScrollTo()
        showHistoryScope()
        screenshot("dark-history")
    }

    @Test
    fun narrowLargeTextRemainsScrollable() {
        // Configure the emulator to 320dp and font_scale=2 before running this test.
        compose.setContent { DashboardPreviewScreen(DashboardFixture.POPULATED) }
        compose.onNodeWithText("PROJECT RECLAIM").performScrollTo()
        compose.onNodeWithText("49m").performScrollTo().assertIsDisplayed()
        screenshot("large-text-main")
        compose.onNodeWithText("View daily values").performScrollTo().performClick()
        compose.onNodeWithText("Oct 28").performScrollTo().assertIsDisplayed()
        screenshot("large-text-history")
        compose.onNodeWithText("View app breakdown").performScrollTo().performClick()
        compose.onNodeWithText("Instagram", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        screenshot("large-text-app-details")
    }

    private fun showHistoryScope() {
        compose.onNodeWithText("Saved Instagram Reels and YouTube Shorts sessions only. TikTok history is unavailable; this is not a combined short-form total.")
            .performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Allow the emulator to present a settled frame before the PixelCopy snapshot.
        SystemClock.sleep(300)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "dashboard-screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

package com.projectreclaim.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.projectreclaim.app.ui.theme.ProjectReclaimTheme
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ShareSummaryRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun emulatorOnly() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test
    fun selectsBothPeriodsAndSharesThePreviewThroughAReadOnlyContentUri() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val chooserIntents = LinkedBlockingQueue<Intent>()
        val summaryReads = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooserIntents.add(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent {
                ProjectReclaimTheme(darkTheme = false) {
                    ShareSummaryDialog(onDismiss = {}, readSummary = { period ->
                        summaryReads.incrementAndGet()
                        if (period == SharePeriod.TODAY) today else week
                    })
                }
            }
            compose.onNodeWithText("Share PNG").assertIsNotEnabled()
            verifyPreview(today, "Project Reclaim. Today. Nov 5, 2026. 38m.", chooserIntents)
            verifyPreview(week, "Project Reclaim. 3 eligible completed days. Nov 2, 2026 – Nov 4, 2026. 1h 51m.", chooserIntents)
            assertEquals(2, summaryReads.get())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun verifyPreview(
        summary: ShareSummary,
        expectedDescription: String,
        chooserIntents: LinkedBlockingQueue<Intent>,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(summary.period.label).performClick()
        val image = compose.onNodeWithContentDescription(expectedDescription, substring = true)
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { image.fetchSemanticsNode() }.isSuccess
        }
        image.assertIsDisplayed()
        val name = summary.period.label.lowercase()
        context.openFileOutput("share-$name-preview.png", 0).use {
            assertTrue(image.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }

        compose.onNodeWithText("Share PNG").performClick()
        val chooser = checkNotNull(chooserIntents.poll(10, TimeUnit.SECONDS))
        val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        val uri = checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.share", uri.authority)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        assertEquals("image/png", context.contentResolver.getType(uri))

        val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        val exported = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue(ShareCardImage.render(summary).sameAs(exported))
        context.openFileOutput("share-$name.png", 0).use { it.write(bytes) }
        image.assertIsDisplayed()
    }

    private val today = ShareSummary.today(
        LocalDate.of(2026, 11, 5),
        TrackedUsageState.Available(16 * 60_000L),
        TrackedUsageState.Available(8 * 60_000L),
        TikTokTodayState.Available(14 * 60_000L),
        accessibilityEnabled = false,
    )

    private val week = ShareSummary(
        SharePeriod.WEEK, "Nov 2, 2026 – Nov 4, 2026", "3 eligible completed days",
        111 * 60_000L, "Recorded short-form time", 44 * 60_000L, 26 * 60_000L, 41 * 60_000L,
    )
}

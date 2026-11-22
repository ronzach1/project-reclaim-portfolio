package com.projectreclaim.app

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.tooling.preview.Preview
import com.projectreclaim.app.data.repository.ShortFormHistoryDay
import com.projectreclaim.app.ui.theme.ProjectReclaimTheme
import java.time.LocalDate

/** Preview/test fixtures live only in the debug source set; the runtime screen never reads them. */
internal enum class DashboardFixture { POPULATED, EMPTY, PARTIAL, UNAVAILABLE, PERMISSION_MISSING, LOADING }

@Composable
internal fun DashboardPreviewScreen(fixture: DashboardFixture, darkTheme: Boolean = false) {
    val empty = fixture == DashboardFixture.EMPTY || fixture == DashboardFixture.PERMISSION_MISSING
    val loading = fixture == DashboardFixture.LOADING
    val permissionMissing = fixture == DashboardFixture.PERMISSION_MISSING
    val unavailable = fixture == DashboardFixture.UNAVAILABLE
    val reels = if (empty) 0L else 23 * 60_000L + 12_000L
    val shorts = if (empty) 0L else 14 * 60_000L + 48_000L
    val tikTok = if (empty) 0L else 11 * 60_000L
    val usage = listOf(
        AppUsage("com.instagram.android", "Instagram", if (empty) 0L else 42 * 60_000L),
        AppUsage("com.zhiliaoapp.musically", "TikTok", tikTok),
        AppUsage("com.facebook.katana", "Facebook", if (empty) 0L else 8 * 60_000L),
        AppUsage("com.twitter.android", "X", if (empty) 0L else 6 * 60_000L),
    )
    val history = (0..7).map { daysAgo ->
        ShortFormHistoryDay(
            date = LocalDate.of(2026, 11, 4).minusDays(daysAgo.toLong()),
            instagramReelsDurationMillis = if (empty) 0L else listOf(23, 18, 32, 0, 24, 16, 28, 12)[daysAgo] * 60_000L,
            tikTokDurationMillis = if (daysAgo == 0) tikTok else null,
            youTubeShortsDurationMillis = if (empty) 0L else listOf(14, 8, 14, 0, 12, 22, 10, 6)[daysAgo] * 60_000L,
        )
    }
    ProjectReclaimTheme(darkTheme = darkTheme) {
        Scaffold { padding ->
            UsageScreen(
                state = when {
                    loading -> UsageScreenState.Loading
                    permissionMissing -> UsageScreenState.PermissionRequired
                    else -> UsageScreenState.UsageAvailable(usage)
                },
                instagramReelsTodayState = when {
                    loading -> TrackedUsageState.Loading
                    unavailable -> TrackedUsageState.Unavailable
                    else -> TrackedUsageState.Available(reels)
                },
                youTubeShortsTodayState = if (loading) TrackedUsageState.Loading else TrackedUsageState.Available(shorts),
                tikTokTodayState = when {
                    loading -> TikTokTodayState.Loading
                    permissionMissing -> TikTokTodayState.PermissionRequired
                    else -> TikTokTodayState.Available(tikTok)
                },
                shortFormHistoryState = when {
                    loading -> ShortFormHistoryState.Loading
                    permissionMissing -> ShortFormHistoryState.PermissionRequired
                    unavailable -> ShortFormHistoryState.Unavailable
                    else -> ShortFormHistoryState.Available(history)
                },
                previousSevenDaysAverageState = when {
                    loading -> PreviousSevenDaysAverageState.Loading
                    permissionMissing -> PreviousSevenDaysAverageState.PermissionRequired
                    unavailable -> PreviousSevenDaysAverageState.Unavailable
                    else -> PreviousSevenDaysAverageState.Available(
                        PreviousSevenDaysAverages(if (empty) 0L else 52 * 60_000L, if (empty) 0L else 78 * 60_000L),
                        dayCount = 3,
                    )
                },
                accessibilityServiceEnabled = when {
                    loading -> null
                    permissionMissing || fixture == DashboardFixture.PARTIAL -> false
                    else -> true
                },
                onOpenUsageAccessSettings = {},
                onOpenAccessibilitySettings = {},
                modifier = Modifier.padding(padding),
                onShare = {},
            )
        }
    }
}

@Preview(name = "Populated", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun PopulatedDashboardPreview() = DashboardPreviewScreen(DashboardFixture.POPULATED)

@Preview(name = "Empty", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun EmptyDashboardPreview() = DashboardPreviewScreen(DashboardFixture.EMPTY)

@Preview(name = "Partial tracking", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun PartialDashboardPreview() = DashboardPreviewScreen(DashboardFixture.PARTIAL)

@Preview(name = "Unavailable", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun UnavailableDashboardPreview() = DashboardPreviewScreen(DashboardFixture.UNAVAILABLE)

@Preview(name = "Permissions missing", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun PermissionMissingDashboardPreview() = DashboardPreviewScreen(DashboardFixture.PERMISSION_MISSING)

@Preview(name = "Loading", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun LoadingDashboardPreview() = DashboardPreviewScreen(DashboardFixture.LOADING)

@Preview(name = "Narrow · large text", showBackground = true, widthDp = 320, heightDp = 1000, fontScale = 2f)
@Composable
internal fun LargeTextDashboardPreview() = DashboardPreviewScreen(DashboardFixture.POPULATED)

@Preview(name = "Dark", showBackground = true, widthDp = 390, heightDp = 1000)
@Composable
internal fun DarkDashboardPreview() = DashboardPreviewScreen(DashboardFixture.POPULATED, darkTheme = true)

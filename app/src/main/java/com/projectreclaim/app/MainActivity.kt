package com.projectreclaim.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.projectreclaim.app.detection.AccessibilityServiceStatus
import com.projectreclaim.app.data.local.ProjectReclaimDatabase
import com.projectreclaim.app.data.repository.ShortFormHistoryDay
import com.projectreclaim.app.data.repository.ShortFormHistoryReader
import com.projectreclaim.app.data.repository.TodayTrackedUsageReader
import com.projectreclaim.app.data.repository.UsageSessionRepository
import com.projectreclaim.app.ui.theme.ProjectReclaimTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val usageStatsReader by lazy { UsageStatsReader(this) }
    private val accessibilityServiceStatus by lazy { AccessibilityServiceStatus(this) }
    private val fullMeasurementSetupStore by lazy { FullMeasurementSetupStore(applicationContext) }
    private val usageSessionRepository by lazy {
        val database = ProjectReclaimDatabase.getInstance(applicationContext)
        UsageSessionRepository(database.usageSessionDao())
    }
    private val todayTrackedUsageReader by lazy {
        TodayTrackedUsageReader(usageSessionRepository)
    }
    private val shortFormHistoryReader by lazy {
        ShortFormHistoryReader(usageSessionRepository, usageStatsReader)
    }
    private val previousSevenDaysUsageReader by lazy {
        PreviousSevenDaysUsageReader(usageSessionRepository, usageStatsReader)
    }
    private var screenState by mutableStateOf<UsageScreenState>(UsageScreenState.Loading)
    private var instagramReelsTodayState by mutableStateOf<TrackedUsageState>(TrackedUsageState.Loading)
    private var youTubeShortsTodayState by mutableStateOf<TrackedUsageState>(TrackedUsageState.Loading)
    private var tikTokTodayState by mutableStateOf<TikTokTodayState>(TikTokTodayState.Loading)
    private var shortFormHistoryState by mutableStateOf<ShortFormHistoryState>(ShortFormHistoryState.Loading)
    private var previousSevenDaysAverageState by mutableStateOf<PreviousSevenDaysAverageState>(
        PreviousSevenDaysAverageState.Loading,
    )
    private var shareOpen by mutableStateOf(false)
    private var todayReadClock = Clock.systemDefaultZone()
    private var fullMeasurementSetupStartedAt: Long? = null
    private var accessibilityServiceEnabled by mutableStateOf<Boolean?>(null)
    private var trackedUsageReadJob: Job? = null
    private var trackedUsageRefreshGeneration = 0L
    private var shortFormHistoryReadJob: Job? = null
    private var shortFormHistoryRefreshGeneration = 0L
    private var previousSevenDaysReadJob: Job? = null
    private var previousSevenDaysRefreshGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProjectReclaimTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UsageScreen(
                        state = screenState,
                        instagramReelsTodayState = instagramReelsTodayState,
                        youTubeShortsTodayState = youTubeShortsTodayState,
                        tikTokTodayState = tikTokTodayState,
                        shortFormHistoryState = shortFormHistoryState,
                        previousSevenDaysAverageState = previousSevenDaysAverageState,
                        onOpenUsageAccessSettings = ::openUsageAccessSettings,
                        accessibilityServiceEnabled = accessibilityServiceEnabled,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        modifier = Modifier.padding(innerPadding),
                        onShare = { shareOpen = true },
                    )
                }
                if (shareOpen) {
                    ShareSummaryDialog(
                        onDismiss = { shareOpen = false },
                        readSummary = ::readShareSummary,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun refreshDashboard() {
        val clock = Clock.systemDefaultZone().let { Clock.fixed(it.instant(), it.zone) }
        todayReadClock = clock
        val hasUsageAccess = usageStatsReader.hasUsageAccess()
        val isAccessibilityServiceEnabled =
            accessibilityServiceStatus.isProjectReclaimServiceEnabled()
        val setupStartedAt = fullMeasurementSetupStore.recordIfFirstFullySetUp(
            usageAccessEnabled = hasUsageAccess,
            accessibilityServiceEnabled = isAccessibilityServiceEnabled,
            nowMillis = clock.millis(),
        )
        fullMeasurementSetupStartedAt = setupStartedAt
        accessibilityServiceEnabled = isAccessibilityServiceEnabled
        screenState = if (hasUsageAccess) {
            val usage = try {
                usageStatsReader.readTodayUsage(clock)
            } catch (_: SecurityException) {
                null
            }
            if (usage == null) {
                tikTokTodayState = TikTokTodayState.Unavailable
                UsageScreenState.Unavailable
            } else {
                tikTokTodayState = TikTokTodayState.Available(
                    usage.firstOrNull { it.appName == TIKTOK_APP_NAME }?.durationMillis ?: 0L,
                )
                UsageScreenState.UsageAvailable(usage)
            }
        } else {
            tikTokTodayState = TikTokTodayState.PermissionRequired
            UsageScreenState.PermissionRequired
        }
        refreshTrackedUsage(clock)
        refreshShortFormHistory(hasUsageAccess)
        refreshPreviousSevenDaysAverage(hasUsageAccess, setupStartedAt)
    }

    private suspend fun readShareSummary(period: SharePeriod): ShareSummary {
        val clock = Clock.systemDefaultZone().let { Clock.fixed(it.instant(), it.zone) }
        if (period == SharePeriod.WEEK) {
            val setupStartedAt = fullMeasurementSetupStartedAt
            return withContext(Dispatchers.IO) {
                ShareSummary.week(setupStartedAt, usageStatsReader.hasUsageAccess(), clock) { range ->
                    try {
                        previousSevenDaysUsageReader.readTotals(range)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }

        if (TodayUsageTime.range(clock).startMillis != TodayUsageTime.range(todayReadClock).startMillis) {
            refreshDashboard()
        }
        // A resume can replace an in-flight dashboard read while the dialog is open.
        while (instagramReelsTodayState == TrackedUsageState.Loading) {
            val job = trackedUsageReadJob ?: break
            job.join()
        }
        return ShareSummary.today(
            LocalDate.now(todayReadClock), instagramReelsTodayState, youTubeShortsTodayState,
            tikTokTodayState, accessibilityServiceEnabled,
        )
    }

    private fun refreshTrackedUsage(clock: Clock) {
        val refreshGeneration = ++trackedUsageRefreshGeneration
        trackedUsageReadJob?.cancel()
        instagramReelsTodayState = TrackedUsageState.Loading
        youTubeShortsTodayState = TrackedUsageState.Loading
        trackedUsageReadJob = lifecycleScope.launch {
            val instagramReelsState = withContext(Dispatchers.IO) {
                readTrackedUsageState(INSTAGRAM_PLATFORM, REELS_USAGE_TYPE, clock)
            }
            val youTubeShortsState = withContext(Dispatchers.IO) {
                readTrackedUsageState(YOUTUBE_PLATFORM, SHORTS_USAGE_TYPE, clock)
            }
            if (refreshGeneration == trackedUsageRefreshGeneration) {
                instagramReelsTodayState = instagramReelsState
                youTubeShortsTodayState = youTubeShortsState
            }
        }
    }

    private suspend fun readTrackedUsageState(
        platform: String,
        usageType: String,
        clock: Clock,
    ): TrackedUsageState = try {
        TrackedUsageState.Available(
            todayTrackedUsageReader.readDurationMillis(platform, usageType, clock),
        )
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        TrackedUsageState.Unavailable
    }

    private fun refreshShortFormHistory(hasUsageAccess: Boolean) {
        val refreshGeneration = ++shortFormHistoryRefreshGeneration
        shortFormHistoryReadJob?.cancel()
        shortFormHistoryState = ShortFormHistoryStateCalculator.initialState(hasUsageAccess)

        if (!hasUsageAccess) {
            shortFormHistoryReadJob = null
            return
        }

        shortFormHistoryReadJob = lifecycleScope.launch {
            val historyState = withContext(Dispatchers.IO) {
                readShortFormHistoryState()
            }
            if (refreshGeneration == shortFormHistoryRefreshGeneration) {
                shortFormHistoryState = historyState
            }
        }
    }

    private suspend fun readShortFormHistoryState(): ShortFormHistoryState = try {
        ShortFormHistoryState.Available(shortFormHistoryReader.read())
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        ShortFormHistoryState.Unavailable
    }

    private fun refreshPreviousSevenDaysAverage(
        hasUsageAccess: Boolean,
        fullMeasurementSetupStartedAt: Long?,
    ) {
        val refreshGeneration = ++previousSevenDaysRefreshGeneration
        previousSevenDaysReadJob?.cancel()
        previousSevenDaysAverageState = when {
            !hasUsageAccess -> PreviousSevenDaysAverageState.PermissionRequired
            fullMeasurementSetupStartedAt == null -> PreviousSevenDaysAverageState.SetupRequired
            else -> PreviousSevenDaysAverageState.Loading
        }

        if (!hasUsageAccess || fullMeasurementSetupStartedAt == null) {
            previousSevenDaysReadJob = null
            return
        }

        previousSevenDaysReadJob = lifecycleScope.launch {
            val averageState = withContext(Dispatchers.IO) {
                readPreviousSevenDaysAverageState(fullMeasurementSetupStartedAt)
            }
            if (refreshGeneration == previousSevenDaysRefreshGeneration) {
                previousSevenDaysAverageState = averageState
            }
        }
    }

    private suspend fun readPreviousSevenDaysAverageState(
        fullMeasurementSetupStartedAt: Long,
    ): PreviousSevenDaysAverageState = try {
        when (val result = previousSevenDaysUsageReader.read(fullMeasurementSetupStartedAt)) {
            PreviousSevenDaysUsageResult.NoCompletedDays ->
                PreviousSevenDaysAverageState.NoCompletedDays
            PreviousSevenDaysUsageResult.AggregatedUsageUnavailable ->
                PreviousSevenDaysAverageState.Unavailable
            is PreviousSevenDaysUsageResult.Available ->
                PreviousSevenDaysAverageState.Available(result.averages, result.dayCount)
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        PreviousSevenDaysAverageState.Unavailable
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onDestroy() {
        trackedUsageRefreshGeneration += 1L
        trackedUsageReadJob?.cancel()
        trackedUsageReadJob = null
        shortFormHistoryRefreshGeneration += 1L
        shortFormHistoryReadJob?.cancel()
        shortFormHistoryReadJob = null
        previousSevenDaysRefreshGeneration += 1L
        previousSevenDaysReadJob?.cancel()
        previousSevenDaysReadJob = null
        super.onDestroy()
    }

    private companion object {
        const val TIKTOK_APP_NAME = "TikTok"
        const val INSTAGRAM_PLATFORM = "instagram"
        const val REELS_USAGE_TYPE = "reels"
        const val YOUTUBE_PLATFORM = "youtube"
        const val SHORTS_USAGE_TYPE = "shorts"
    }
}

internal sealed interface UsageScreenState {
    data object Loading : UsageScreenState
    data object Unavailable : UsageScreenState
    data object PermissionRequired : UsageScreenState
    data class UsageAvailable(val usage: List<AppUsage>) : UsageScreenState
}

internal sealed interface TrackedUsageState {
    data object Loading : TrackedUsageState
    data object Unavailable : TrackedUsageState
    data class Available(val durationMillis: Long) : TrackedUsageState
}

internal sealed interface TikTokTodayState {
    data object Loading : TikTokTodayState
    data object Unavailable : TikTokTodayState
    data object PermissionRequired : TikTokTodayState
    data class Available(val durationMillis: Long) : TikTokTodayState
}

internal sealed interface ShortFormTodayState {
    data object Loading : ShortFormTodayState
    data object PermissionRequired : ShortFormTodayState
    data object Unavailable : ShortFormTodayState
    data class Available(val durationMillis: Long) : ShortFormTodayState
}

internal sealed interface ShortFormHistoryState {
    data object Loading : ShortFormHistoryState
    data object PermissionRequired : ShortFormHistoryState
    data object Unavailable : ShortFormHistoryState
    data class Available(val history: List<ShortFormHistoryDay>) : ShortFormHistoryState
}

internal sealed interface PreviousSevenDaysAverageState {
    data object Loading : PreviousSevenDaysAverageState
    data object PermissionRequired : PreviousSevenDaysAverageState
    data object SetupRequired : PreviousSevenDaysAverageState
    data object NoCompletedDays : PreviousSevenDaysAverageState
    data object Unavailable : PreviousSevenDaysAverageState
    data class Available(
        val averages: PreviousSevenDaysAverages,
        val dayCount: Int,
    ) : PreviousSevenDaysAverageState
}

internal object ShortFormHistoryStateCalculator {
    fun initialState(hasUsageAccess: Boolean): ShortFormHistoryState =
        if (hasUsageAccess) ShortFormHistoryState.Loading else ShortFormHistoryState.PermissionRequired
}

internal data class ShortFormHistoryDisplayRow(
    val dateLabel: String,
    val combinedDurationText: String,
    val instagramReelsDurationText: String,
    val tikTokDurationText: String,
    val youTubeShortsDurationText: String,
)

internal object ShortFormHistoryPresentation {
    fun rows(history: List<ShortFormHistoryDay>): List<ShortFormHistoryDisplayRow> =
        history.mapIndexed { index, day ->
            ShortFormHistoryDisplayRow(
                dateLabel = if (index == 0) "Today" else day.date.format(DATE_FORMATTER),
                combinedDurationText = day.combinedShortFormDurationMillis
                    ?.let(TrackedUsageDurationFormatter::format)
                    ?: INCOMPLETE_TEXT,
                instagramReelsDurationText = TrackedUsageDurationFormatter.format(
                    day.instagramReelsDurationMillis,
                ),
                tikTokDurationText = day.tikTokDurationMillis
                    ?.let(TrackedUsageDurationFormatter::format)
                    ?: UNAVAILABLE_TEXT,
                youTubeShortsDurationText = TrackedUsageDurationFormatter.format(
                    day.youTubeShortsDurationMillis,
                ),
            )
        }

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private const val INCOMPLETE_TEXT = "Not available"
    private const val UNAVAILABLE_TEXT = "Not available"
}

internal enum class CapabilityReadinessStatus(val displayText: String) {
    CHECKING("Checking…"),
    ENABLED("Enabled"),
    NEEDS_SETUP("Needs setup"),
}

internal fun capabilityReadinessStatus(enabled: Boolean?): CapabilityReadinessStatus = when (enabled) {
    null -> CapabilityReadinessStatus.CHECKING
    true -> CapabilityReadinessStatus.ENABLED
    false -> CapabilityReadinessStatus.NEEDS_SETUP
}

internal object ShortFormTodayStateCalculator {
    fun calculate(
        instagramReels: TrackedUsageState,
        tikTok: TikTokTodayState,
        youTubeShorts: TrackedUsageState,
    ): ShortFormTodayState = when {
        tikTok is TikTokTodayState.PermissionRequired -> ShortFormTodayState.PermissionRequired
        tikTok is TikTokTodayState.Unavailable ||
            instagramReels is TrackedUsageState.Unavailable ||
            youTubeShorts is TrackedUsageState.Unavailable -> ShortFormTodayState.Unavailable
        instagramReels is TrackedUsageState.Loading ||
            tikTok is TikTokTodayState.Loading ||
            youTubeShorts is TrackedUsageState.Loading ->
            ShortFormTodayState.Loading
        instagramReels is TrackedUsageState.Available &&
            tikTok is TikTokTodayState.Available &&
            youTubeShorts is TrackedUsageState.Available ->
            ShortFormTodayState.Available(
                instagramReels.durationMillis + tikTok.durationMillis + youTubeShorts.durationMillis,
            )
        else -> ShortFormTodayState.Unavailable
    }
}

internal sealed interface ShortFormTodayPresentation {
    data object Loading : ShortFormTodayPresentation
    data object PermissionRequired : ShortFormTodayPresentation
    data object Unavailable : ShortFormTodayPresentation
    data class Complete(val durationMillis: Long) : ShortFormTodayPresentation
    data class Partial(val durationMillis: Long) : ShortFormTodayPresentation
}

internal object ShortFormTodayPresentationCalculator {
    fun calculate(
        state: ShortFormTodayState,
        accessibilityServiceEnabled: Boolean?,
    ): ShortFormTodayPresentation = when (state) {
        ShortFormTodayState.Loading -> ShortFormTodayPresentation.Loading
        ShortFormTodayState.PermissionRequired -> ShortFormTodayPresentation.PermissionRequired
        ShortFormTodayState.Unavailable -> ShortFormTodayPresentation.Unavailable
        is ShortFormTodayState.Available ->
            if (accessibilityServiceEnabled == false) {
                ShortFormTodayPresentation.Partial(state.durationMillis)
            } else {
                ShortFormTodayPresentation.Complete(state.durationMillis)
            }
    }
}

internal fun socialAppUsageTotalMillis(usage: List<AppUsage>): Long =
    usage.sumOf(AppUsage::durationMillis)

internal fun TrackedUsageState.displayText(accessibilityServiceEnabled: Boolean? = true): String = when (this) {
    TrackedUsageState.Loading -> "Loading…"
    TrackedUsageState.Unavailable -> "Unavailable"
    is TrackedUsageState.Available -> {
        val durationText = TrackedUsageDurationFormatter.format(durationMillis)
        if (accessibilityServiceEnabled == false) "$durationText - Tracking paused" else durationText
    }
}

internal fun TikTokTodayState.displayText(): String = when (this) {
    TikTokTodayState.Loading -> "Loading…"
    TikTokTodayState.Unavailable -> "Unavailable"
    TikTokTodayState.PermissionRequired -> "Needs Usage Access"
    is TikTokTodayState.Available -> TrackedUsageDurationFormatter.format(durationMillis)
}

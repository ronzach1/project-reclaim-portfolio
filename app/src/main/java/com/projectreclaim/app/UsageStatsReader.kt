package com.projectreclaim.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.Clock
import java.time.ZoneId

interface AppUsageReader {
    /**
     * Calculates usage in [range] from the lifecycle events the source currently provides.
     * A result does not guarantee that the source retained every event in an older range.
     */
    fun readUsage(range: UsageTimeRange, zone: ZoneId): List<AppUsage>
}

/**
 * Reads the supported social apps' foreground time from Android UsageStats events that are still
 * available. Android does not guarantee that lifecycle events remain available for multi-day
 * historical reconstruction.
 */
class UsageStatsReader(private val context: Context) :
    AppUsageReader,
    AggregatedAppUsageReader {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = context.getSystemService(AppOpsManager::class.java)

    fun hasUsageAccess(): Boolean =
        appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED

    fun readTodayUsage(clock: Clock = Clock.systemDefaultZone()): List<AppUsage>? {
        val range = TodayUsageTime.range(clock)
        return readAvailableUsage(range, clock.zone)
    }

    override fun readUsage(range: UsageTimeRange, zone: ZoneId): List<AppUsage> =
        readAvailableUsage(range, zone) ?: appUsage(emptyMap())

    private fun readAvailableUsage(range: UsageTimeRange, zone: ZoneId): List<AppUsage>? {
        val events = readRelevantEvents(
            startMillis = TodayUsageTime.lookbackStart(range, zone),
            endMillis = range.endMillis,
        ) ?: return null
        return appUsage(ForegroundUsageCalculator.calculate(events, supportedPackageNames, range))
    }

    private fun appUsage(usageByPackage: Map<String, Long>): List<AppUsage> =
        supportedApps.map { app ->
            AppUsage(
                packageName = app.packageName,
                appName = app.name,
                durationMillis = usageByPackage[app.packageName] ?: 0L,
            )
        }

    override fun readAggregatedUsage(range: UsageTimeRange): AggregatedAppUsageReadResult {
        val stats = try {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                range.startMillis,
                range.endMillis,
            )
        } catch (_: SecurityException) {
            null
        } ?: return AggregatedAppUsageReadResult.Unavailable

        val durationByPackage = supportedPackageNames.associateWith { 0L }.toMutableMap()
        stats.forEach { usageStats ->
            val packageName = usageStats.packageName
            if (packageName in supportedPackageNames) {
                durationByPackage[packageName] =
                    durationByPackage.getValue(packageName) +
                    usageStats.totalTimeInForeground.coerceAtLeast(0L)
            }
        }

        return AggregatedAppUsageReadResult.Available(durationByPackage)
    }

    private fun readRelevantEvents(
        startMillis: Long,
        endMillis: Long,
    ): List<UsageLifecycleEvent>? {
        val usageEvents = usageStatsManager.queryEvents(startMillis, endMillis) ?: return null
        val relevantEvents = mutableListOf<UsageLifecycleEvent>()
        val androidEvent = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(androidEvent)
            val eventType = when (androidEvent.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageLifecycleEventType.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageLifecycleEventType.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageLifecycleEventType.STOPPED
                UsageEvents.Event.DEVICE_SHUTDOWN -> UsageLifecycleEventType.DEVICE_SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> UsageLifecycleEventType.DEVICE_STARTUP
                else -> null
            } ?: continue

            val packageName = androidEvent.packageName
            if (
                eventType.isActivityEvent &&
                (packageName == null || packageName !in supportedPackageNames)
            ) {
                continue
            }

            relevantEvents += UsageLifecycleEvent(
                packageName = packageName,
                activityId = if (eventType.isActivityEvent) {
                    androidEvent.className?.takeIf(String::isNotBlank)
                } else {
                    null
                },
                type = eventType,
                timestampMillis = androidEvent.timeStamp,
            )
        }

        return relevantEvents
    }

    private val supportedApps = SUPPORTED_SOCIAL_APPS
    private val supportedPackageNames = SUPPORTED_SOCIAL_PACKAGE_NAMES
}

data class AppUsage(
    val packageName: String,
    val appName: String,
    val durationMillis: Long,
)

internal data class SupportedSocialApp(val name: String, val packageName: String)

internal const val INSTAGRAM_PACKAGE_NAME = "com.instagram.android"
internal const val TIKTOK_PACKAGE_NAME = "com.zhiliaoapp.musically"
internal const val FACEBOOK_PACKAGE_NAME = "com.facebook.katana"
internal const val X_PACKAGE_NAME = "com.twitter.android"

internal val SUPPORTED_SOCIAL_APPS = listOf(
    SupportedSocialApp("Instagram", INSTAGRAM_PACKAGE_NAME),
    SupportedSocialApp("TikTok", TIKTOK_PACKAGE_NAME),
    SupportedSocialApp("Facebook", FACEBOOK_PACKAGE_NAME),
    SupportedSocialApp("X", X_PACKAGE_NAME),
)

internal val SUPPORTED_SOCIAL_PACKAGE_NAMES = SUPPORTED_SOCIAL_APPS.map(SupportedSocialApp::packageName).toSet()

internal enum class UsageLifecycleEventType(val isActivityEvent: Boolean) {
    RESUMED(true),
    PAUSED(true),
    STOPPED(true),
    DEVICE_SHUTDOWN(false),
    DEVICE_STARTUP(false),
}

internal data class UsageLifecycleEvent(
    val packageName: String?,
    val activityId: String?,
    val type: UsageLifecycleEventType,
    val timestampMillis: Long,
)

internal object ForegroundUsageCalculator {
    fun calculate(
        events: List<UsageLifecycleEvent>,
        supportedPackages: Set<String>,
        range: UsageTimeRange,
    ): Map<String, Long> {
        val states = supportedPackages.associateWith { PackageForegroundState() }
        val totals = supportedPackages.associateWith { 0L }.toMutableMap()
        val foregroundStartedAt = mutableMapOf<String, Long>()
        val chronologicalEvents = events.sortedBy(UsageLifecycleEvent::timestampMillis)

        chronologicalEvents
            .asSequence()
            .takeWhile { it.timestampMillis < range.startMillis }
            .forEach { event -> applyBeforeBoundary(event, states) }

        states.forEach { (packageName, state) ->
            if (state.isForeground) foregroundStartedAt[packageName] = range.startMillis
        }

        chronologicalEvents
            .asSequence()
            .dropWhile { it.timestampMillis < range.startMillis }
            .takeWhile { it.timestampMillis < range.endMillis }
            .forEach { event ->
                if (event.type.isDeviceReset) {
                    states.forEach { (packageName, state) ->
                        if (state.isForeground) {
                            closeInterval(
                                packageName = packageName,
                                endMillis = event.timestampMillis,
                                foregroundStartedAt = foregroundStartedAt,
                                totals = totals,
                            )
                        }
                        state.reset()
                    }
                    return@forEach
                }

                val packageName = event.packageName ?: return@forEach
                val state = states[packageName] ?: return@forEach
                val wasForeground = state.isForeground
                state.apply(event)

                when {
                    !wasForeground && state.isForeground ->
                        foregroundStartedAt[packageName] = event.timestampMillis
                    wasForeground && !state.isForeground ->
                        closeInterval(
                            packageName = packageName,
                            endMillis = event.timestampMillis,
                            foregroundStartedAt = foregroundStartedAt,
                            totals = totals,
                        )
                }
            }

        foregroundStartedAt.keys.toList().forEach { packageName ->
            closeInterval(
                packageName = packageName,
                endMillis = range.endMillis,
                foregroundStartedAt = foregroundStartedAt,
                totals = totals,
            )
        }

        return totals
    }

    private fun applyBeforeBoundary(
        event: UsageLifecycleEvent,
        states: Map<String, PackageForegroundState>,
    ) {
        if (event.type.isDeviceReset) {
            states.values.forEach(PackageForegroundState::reset)
            return
        }

        val packageName = event.packageName ?: return
        states[packageName]?.apply(event)
    }

    private fun closeInterval(
        packageName: String,
        endMillis: Long,
        foregroundStartedAt: MutableMap<String, Long>,
        totals: MutableMap<String, Long>,
    ) {
        val startMillis = foregroundStartedAt.remove(packageName) ?: return
        totals[packageName] = totals.getValue(packageName) +
            (endMillis - startMillis).coerceAtLeast(0L)
    }

    private val UsageLifecycleEventType.isDeviceReset: Boolean
        get() = this == UsageLifecycleEventType.DEVICE_SHUTDOWN ||
            this == UsageLifecycleEventType.DEVICE_STARTUP

    private class PackageForegroundState {
        private val activeActivityCounts = mutableMapOf<String, Int>()
        private val pausedAwaitingStopCounts = mutableMapOf<String, Int>()
        private var unknownActiveCount = 0
        private var unknownPausedAwaitingStopCount = 0

        val isForeground: Boolean
            get() = unknownActiveCount > 0 || activeActivityCounts.isNotEmpty()

        fun apply(event: UsageLifecycleEvent) {
            when (event.type) {
                UsageLifecycleEventType.RESUMED -> {
                    val activityId = event.activityId
                    if (activityId == null) {
                        if (unknownActiveCount < Int.MAX_VALUE) unknownActiveCount += 1
                    } else {
                        val activeCount = activeActivityCounts.getOrDefault(activityId, 0)
                        if (activeCount < Int.MAX_VALUE) {
                            activeActivityCounts[activityId] = activeCount + 1
                        }
                    }
                }
                UsageLifecycleEventType.PAUSED -> {
                    val activityId = event.activityId
                    if (activityId == null) {
                        if (unknownActiveCount > 0) {
                            unknownActiveCount -= 1
                            if (unknownPausedAwaitingStopCount < Int.MAX_VALUE) {
                                unknownPausedAwaitingStopCount += 1
                            }
                        }
                    } else {
                        if (decrementCount(activeActivityCounts, activityId)) {
                            incrementCount(pausedAwaitingStopCounts, activityId)
                        }
                    }
                }
                UsageLifecycleEventType.STOPPED -> {
                    val activityId = event.activityId
                    if (activityId == null) {
                        if (unknownActiveCount > 0) {
                            unknownActiveCount -= 1
                        } else if (unknownPausedAwaitingStopCount > 0) {
                            unknownPausedAwaitingStopCount -= 1
                        }
                    } else if (!decrementCount(activeActivityCounts, activityId)) {
                        decrementCount(pausedAwaitingStopCounts, activityId)
                    }
                }
                UsageLifecycleEventType.DEVICE_SHUTDOWN,
                UsageLifecycleEventType.DEVICE_STARTUP,
                -> reset()
            }
        }

        fun reset() {
            activeActivityCounts.clear()
            pausedAwaitingStopCounts.clear()
            unknownActiveCount = 0
            unknownPausedAwaitingStopCount = 0
        }

        private fun incrementCount(counts: MutableMap<String, Int>, activityId: String) {
            val count = counts.getOrDefault(activityId, 0)
            if (count < Int.MAX_VALUE) counts[activityId] = count + 1
        }

        private fun decrementCount(counts: MutableMap<String, Int>, activityId: String): Boolean {
            val count = counts[activityId] ?: return false
            if (count == 1) {
                counts.remove(activityId)
            } else {
                counts[activityId] = count - 1
            }
            return true
        }
    }
}

object UsageDurationFormatter {
    fun format(durationMillis: Long): String {
        val totalMinutes = (durationMillis.coerceAtLeast(0) / MILLIS_PER_MINUTE).toInt()
        val hours = totalMinutes / MINUTES_PER_HOUR
        val minutes = totalMinutes % MINUTES_PER_HOUR

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60
}

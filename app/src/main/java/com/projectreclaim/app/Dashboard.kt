package com.projectreclaim.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.projectreclaim.app.data.repository.ShortFormHistoryDay
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun UsageScreen(
    state: UsageScreenState,
    instagramReelsTodayState: TrackedUsageState,
    youTubeShortsTodayState: TrackedUsageState,
    tikTokTodayState: TikTokTodayState,
    shortFormHistoryState: ShortFormHistoryState,
    previousSevenDaysAverageState: PreviousSevenDaysAverageState,
    onOpenUsageAccessSettings: () -> Unit,
    accessibilityServiceEnabled: Boolean?,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
    onShare: (() -> Unit)? = null,
) {
    val today = ShortFormTodayPresentationCalculator.calculate(
        ShortFormTodayStateCalculator.calculate(
            instagramReelsTodayState, tikTokTodayState, youTubeShortsTodayState,
        ),
        accessibilityServiceEnabled,
    )
    val usageAccess = capabilityReadinessStatus(
        when (state) {
            UsageScreenState.Loading -> null
            UsageScreenState.PermissionRequired -> false
            UsageScreenState.Unavailable, is UsageScreenState.UsageAvailable -> true
        },
    )
    val accessibility = capabilityReadinessStatus(accessibilityServiceEnabled)
    val ready = usageAccess == CapabilityReadinessStatus.ENABLED &&
        accessibility == CapabilityReadinessStatus.ENABLED

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("PROJECT RECLAIM", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    if (onShare != null) TextButton(onClick = onShare) { Text("Share") }
                }
                Spacer(Modifier.height(6.dp))
                Text("Your attention, today", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Text("A little awareness. More room for life.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TodayCard(today, previousSevenDaysAverageState)
            if (!ready) {
                SetupSection(usageAccess, accessibility,
                    onOpenUsageAccessSettings, onOpenAccessibilitySettings)
            }
            DashboardCard {
                SectionTitle("Today’s breakdown")
                UsageValue("Instagram Reels", instagramReelsTodayState.displayText(accessibilityServiceEnabled),
                    marker = MaterialTheme.colorScheme.primary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UsageValue("YouTube Shorts", youTubeShortsTodayState.displayText(accessibilityServiceEnabled),
                    marker = MaterialTheme.colorScheme.secondary)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UsageValue("TikTok", tikTokTodayState.displayText())
                SupportingText("TikTok counts all foreground app time in this alpha.")
            }
            HistoryCard(shortFormHistoryState, accessibilityServiceEnabled)
            SocialUsageCard(state, previousSevenDaysAverageState as? PreviousSevenDaysAverageState.Available)
            if (ready) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Text("Setup complete", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    SupportingText("Usage Access and Accessibility enabled")
                }
            }
            SupportingText("On-device measurement\nUpdates when you return to the app.")
        }
    }
}

@Composable
private fun TodayCard(state: ShortFormTodayPresentation, averageState: PreviousSevenDaysAverageState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SHORT-FORM TODAY · SO FAR", style = MaterialTheme.typography.labelLarge)
            val duration = when (state) {
                is ShortFormTodayPresentation.Complete -> state.durationMillis
                is ShortFormTodayPresentation.Partial -> state.durationMillis
                else -> null
            }
            Text(
                duration?.let(TrackedUsageDurationFormatter::format) ?: when (state) {
                    ShortFormTodayPresentation.Loading -> "Loading…"
                    else -> "—"
                },
                style = if (state == ShortFormTodayPresentation.Loading)
                    MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
            )
            Text(
                when (state) {
                    ShortFormTodayPresentation.Loading -> "Gathering today’s usage."
                    ShortFormTodayPresentation.PermissionRequired ->
                        "Usage Access is needed to include TikTok foreground time."
                    ShortFormTodayPresentation.Unavailable -> "Couldn’t load the complete total."
                    is ShortFormTodayPresentation.Partial -> "Partial · Reels and Shorts tracking is paused."
                    is ShortFormTodayPresentation.Complete -> if (state.durationMillis == 0L)
                        "No short-form time recorded today." else "Instagram Reels + YouTube Shorts + TikTok"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.32f))
            val average = averageState.presentation()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(average.label, style = MaterialTheme.typography.labelMedium)
                Text(average.detail, style = MaterialTheme.typography.titleSmall)
                if (averageState is PreviousSevenDaysAverageState.Available) {
                    Text("Historical totals are approximate and may have tracking gaps.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Stack values before either label can be squeezed on narrow screens or at large font scales. */
@Composable
private fun UsageValue(label: String, value: String, marker: Color? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.3f
        val labelContent: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (marker != null) {
                    Box(Modifier.size(8.dp).background(marker, CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labelContent()
                Text(value, style = MaterialTheme.typography.titleSmall)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { labelContent() }
                Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

// The reader supplies today first, followed by seven completed local days. Never chart today.
internal fun completedHistoryDays(history: List<ShortFormHistoryDay>): List<ShortFormHistoryDay> =
    history.drop(1).take(7).reversed()

@Composable
private fun HistoryCard(state: ShortFormHistoryState, accessibilityServiceEnabled: Boolean?) {
    DashboardCard {
        SectionTitle("Reels & Shorts history")
        SupportingText("Last 7 completed local days · today excluded")
        Spacer(Modifier.height(16.dp))
        when (state) {
            ShortFormHistoryState.Loading -> SupportingText("Loading saved history…")
            ShortFormHistoryState.PermissionRequired ->
                SupportingText("Needs Usage Access to load history.")
            ShortFormHistoryState.Unavailable -> SupportingText("Couldn’t load saved history. Return to the app to retry.")
            is ShortFormHistoryState.Available -> {
                val days = completedHistoryDays(state.history)
                if (days.isEmpty()) {
                    SupportingText("No completed-day history available yet.")
                } else {
                    HistoryChart(days)
                    if (days.all { it.instagramReelsDurationMillis == 0L && it.youTubeShortsDurationMillis == 0L }) {
                        Spacer(Modifier.height(12.dp))
                        SupportingText("No saved Reels or Shorts time in these days.")
                    }
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        },
                    ) { Text(if (expanded) "Hide daily values" else "View daily values") }
                    if (expanded) days.forEach { day ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(day.date.format(historyDateFormat()), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 12.dp))
                        UsageValue("Instagram Reels", TrackedUsageDurationFormatter.format(day.instagramReelsDurationMillis))
                        UsageValue("YouTube Shorts", TrackedUsageDurationFormatter.format(day.youTubeShortsDurationMillis))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SupportingText("Saved Instagram Reels and YouTube Shorts sessions only. TikTok history is unavailable; this is not a combined short-form total.")
        if (accessibilityServiceEnabled == false) {
            Spacer(Modifier.height(8.dp))
            SupportingText("Tracking paused · saved sessions remain visible. Gaps are not reconstructed.")
        }
    }
}

private fun historyDateFormat() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

@Composable
private fun HistoryChart(days: List<ShortFormHistoryDay>) {
    val reelsColor = MaterialTheme.colorScheme.primary
    val shortsColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val maxDuration = days.maxOf { maxOf(it.instagramReelsDurationMillis, it.youTubeShortsDurationMillis) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Separate labeled legend lines remain readable at 200% font scale.
        Legend("Instagram Reels", reelsColor)
        Legend("YouTube Shorts", shortsColor)
        SupportingText("${days.first().date.format(historyDateFormat())} – ${days.last().date.format(historyDateFormat())}")
        SupportingText(if (maxDuration == 0L) "All saved values: 0s"
            else "Scale: 0s – ${TrackedUsageDurationFormatter.format(maxDuration)}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEach { day ->
                Column(
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = "${day.date.format(historyDateFormat())}. Instagram Reels: " +
                            "${TrackedUsageDurationFormatter.format(day.instagramReelsDurationMillis)}. YouTube Shorts: " +
                            TrackedUsageDurationFormatter.format(day.youTubeShortsDurationMillis)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        if (maxDuration > 0) {
                            val barWidth = size.width * 0.28f
                            fun bar(duration: Long, x: Float, color: Color) {
                                // True zero has no bar; never manufacture a minimum activity height.
                                if (duration <= 0) return
                                val height = (duration.toDouble() / maxDuration * size.height).toFloat()
                                drawRoundRect(color, Offset(x, size.height - height), Size(barWidth, height),
                                    CornerRadius(2.dp.toPx()))
                            }
                            bar(day.instagramReelsDurationMillis, size.width * 0.18f, reelsColor)
                            bar(day.youTubeShortsDurationMillis, size.width * 0.54f, shortsColor)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        SupportingText(label)
    }
}

@Composable
private fun SocialUsageCard(
    state: UsageScreenState,
    average: PreviousSevenDaysAverageState.Available?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DashboardCard {
        SectionTitle("Broader app usage")
        SupportingText("Whole-app foreground time · today")
        when (state) {
            UsageScreenState.Loading -> SupportingText("Loading app usage…")
            UsageScreenState.Unavailable -> SupportingText("App usage is unavailable.")
            UsageScreenState.PermissionRequired -> SupportingText("Needs Usage Access to show broader app usage.")
            is UsageScreenState.UsageAvailable -> {
                UsageValue("Social apps", UsageDurationFormatter.format(socialAppUsageTotalMillis(state.usage)))
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    },
                ) { Text(if (expanded) "Hide app breakdown" else "View app breakdown") }
                if (expanded) {
                    state.usage.forEach { app ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        UsageValue(app.appName, UsageDurationFormatter.format(app.durationMillis))
                    }
                }
            }
        }
        if (average != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            UsageValue(
                "Social apps daily average",
                UsageDurationFormatter.format(average.averages.socialAppsDailyAverageMillis),
            )
            SupportingText("Previous ${completedDayCountLabel(average.dayCount)} · approximate")
        }
    }
}

@Composable
private fun SetupSection(
    usageAccess: CapabilityReadinessStatus,
    accessibility: CapabilityReadinessStatus,
    openUsageAccess: () -> Unit,
    openAccessibility: () -> Unit,
) {
    DashboardCard {
        SectionTitle(if (usageAccess == CapabilityReadinessStatus.CHECKING &&
            accessibility == CapabilityReadinessStatus.CHECKING) "Checking setup…" else "Complete your setup")
        Spacer(Modifier.height(8.dp))
        SupportingText("Usage Access: ${usageAccess.displayText}\nAccessibility: ${accessibility.displayText}")
        if (usageAccess == CapabilityReadinessStatus.NEEDS_SETUP) {
            Spacer(Modifier.height(16.dp))
            Text("Allow Usage Access", style = MaterialTheme.typography.titleSmall)
            SupportingText("Shows TikTok foreground time today and broader app usage. This alpha counts all TikTok foreground time as short-form.")
            Button(onClick = openUsageAccess, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text("Open Usage Access settings")
            }
        }
        if (accessibility == CapabilityReadinessStatus.NEEDS_SETUP) {
            Spacer(Modifier.height(16.dp))
            Text("Enable Accessibility", style = MaterialTheme.typography.titleSmall)
            SupportingText("Recognizes Instagram Reels and YouTube Shorts. Android Accessibility can expose information shown on screen; relevant interface information is inspected transiently for detection. Screen content is not stored or transmitted.")
            Button(onClick = openAccessibility, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text("Open Accessibility settings")
            }
        }
    }
}

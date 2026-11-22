package com.projectreclaim.app

internal data class PreviousSevenDaysAveragePresentation(
    val detail: String,
    val label: String = "Daily average · completed days",
)

internal fun completedDayCountLabel(dayCount: Int): String =
    if (dayCount == 1) "1 completed day" else "$dayCount completed days"

internal fun PreviousSevenDaysAverageState.presentation(): PreviousSevenDaysAveragePresentation =
    when (this) {
        PreviousSevenDaysAverageState.Loading ->
            PreviousSevenDaysAveragePresentation("Loading average…")
        PreviousSevenDaysAverageState.PermissionRequired ->
            PreviousSevenDaysAveragePresentation("Needs Usage Access.")
        PreviousSevenDaysAverageState.SetupRequired ->
            PreviousSevenDaysAveragePresentation("Finish setup, then wait for a completed day.")
        PreviousSevenDaysAverageState.NoCompletedDays ->
            PreviousSevenDaysAveragePresentation("Waiting for the first eligible completed day.")
        PreviousSevenDaysAverageState.Unavailable ->
            PreviousSevenDaysAveragePresentation("Average unavailable.")
        is PreviousSevenDaysAverageState.Available -> PreviousSevenDaysAveragePresentation(
            detail = UsageDurationFormatter.format(averages.shortFormDailyAverageMillis),
            label = "Daily average · previous ${completedDayCountLabel(dayCount)}",
        )
    }

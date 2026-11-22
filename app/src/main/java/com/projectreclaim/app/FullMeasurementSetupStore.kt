package com.projectreclaim.app

import android.content.Context

/** Stores when both required measurement capabilities were first observed enabled. */
internal class FullMeasurementSetupStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun recordIfFirstFullySetUp(
        usageAccessEnabled: Boolean,
        accessibilityServiceEnabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long? {
        val existingValue = startedAtMillis()
        val valueToStore = FullMeasurementSetupStartedAt.resolve(
            existingValue = existingValue,
            usageAccessEnabled = usageAccessEnabled,
            accessibilityServiceEnabled = accessibilityServiceEnabled,
            nowMillis = nowMillis,
        )
        if (valueToStore == existingValue || valueToStore == null) {
            return existingValue
        }

        preferences.edit().putLong(FULL_MEASUREMENT_SETUP_STARTED_AT_KEY, valueToStore).apply()
        return valueToStore
    }

    private fun startedAtMillis(): Long? =
        if (preferences.contains(FULL_MEASUREMENT_SETUP_STARTED_AT_KEY)) {
            preferences.getLong(FULL_MEASUREMENT_SETUP_STARTED_AT_KEY, 0L)
        } else {
            null
        }

    private companion object {
        const val PREFERENCES_NAME = "measurement_coverage"
        const val FULL_MEASUREMENT_SETUP_STARTED_AT_KEY = "full_measurement_setup_started_at"
    }
}

internal object FullMeasurementSetupStartedAt {
    fun resolve(
        existingValue: Long?,
        usageAccessEnabled: Boolean,
        accessibilityServiceEnabled: Boolean,
        nowMillis: Long,
    ): Long? =
        existingValue ?: if (usageAccessEnabled && accessibilityServiceEnabled) nowMillis else null
}

package com.projectreclaim.app.detection

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

class AccessibilityServiceStatus(context: Context) {
    private val appContext = context.applicationContext
    private val accessibilityManager =
        appContext.getSystemService(AccessibilityManager::class.java)

    fun isProjectReclaimServiceEnabled(): Boolean {
        val expectedService = ComponentName(
            appContext,
            ShortFormDetectionAccessibilityService::class.java,
        )

        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                val enabledService = serviceInfo.resolveInfo.serviceInfo
                ComponentName(enabledService.packageName, enabledService.name) == expectedService
            }
    }
}

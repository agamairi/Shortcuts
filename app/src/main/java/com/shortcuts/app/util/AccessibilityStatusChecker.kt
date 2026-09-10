package com.shortcuts.app.util

import android.content.Context
import android.provider.Settings

object AccessibilityStatusChecker {
    fun isAccessibilityServiceEnabledFromSettingString(
        enabledServices: String?,
        packageName: String = "com.shortcuts.app",
        serviceClassName: String = "com.shortcuts.app.service.AutomationAccessibilityService"
    ): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        val shortComponent = "$packageName/.service.AutomationAccessibilityService"
        val fullComponent = "$packageName/$serviceClassName"
        return enabledServices.split(":").any { item ->
            val trimmed = item.trim()
            if (!trimmed.contains("/")) return@any false
            trimmed.equals(shortComponent, ignoreCase = true) ||
            trimmed.equals(fullComponent, ignoreCase = true) ||
            (trimmed.startsWith(packageName) && trimmed.contains("AutomationAccessibilityService"))
        }
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            null
        }
        return isAccessibilityServiceEnabledFromSettingString(enabledServices, context.packageName)
    }

    /**
     * Settings is the durable signal that the user enabled this component. A null service
     * instance can be a normal, momentary framework recycle and is not evidence of disablement.
     */
    fun isAccessibilityServiceActive(context: Context): Boolean {
        return isAccessibilityEnabled(context)
    }
}

package com.shortcuts.app.util

import android.content.Context
import android.provider.Settings

object AccessibilityStatusChecker {
    private const val SERVICE_CLASS_NAME = "com.shortcuts.app.service.AutomationAccessibilityService"

    /**
     * The service class keeps its Kotlin package even in the `.dev` build, whose application id
     * differs, so the component's package and class are compared separately and exactly. A prefix
     * match would let the normal install mistake RepeatKit Dev's enabled service for its own.
     */
    fun isAccessibilityServiceEnabledFromSettingString(
        enabledServices: String?,
        packageName: String,
        serviceClassName: String = SERVICE_CLASS_NAME
    ): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        return enabledServices.split(":").any { item ->
            val trimmed = item.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash == trimmed.lastIndex) return@any false
            val componentPackage = trimmed.substring(0, slash)
            val componentClass = trimmed.substring(slash + 1)
                .let { if (it.startsWith(".")) componentPackage + it else it }
            componentPackage == packageName && componentClass == serviceClassName
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

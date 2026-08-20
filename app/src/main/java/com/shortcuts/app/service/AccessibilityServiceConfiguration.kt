package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityServiceInfo

/**
 * The flags required for a service that records and replays interactions across apps.
 *
 * Keep this in sync with accessibility_service_config.xml. The XML attribute is a complete
 * bit mask, not an additive override of the platform defaults.
 */
object AccessibilityServiceConfiguration {
    fun requiredFlags(existingFlags: Int = 0): Int {
        return existingFlags or
            AccessibilityServiceInfo.DEFAULT or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
}

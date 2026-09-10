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
        // Selector lookup and traversal operate on the app's accessibility-important tree; no
        // replay or recording path requires hidden/not-important views. Clear this bit even when
        // upgrading from an older runtime configuration so the XML least-privilege policy wins.
        return (existingFlags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()) or
            AccessibilityServiceInfo.DEFAULT or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
}

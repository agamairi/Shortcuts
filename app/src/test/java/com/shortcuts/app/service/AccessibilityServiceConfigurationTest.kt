package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityServiceInfo
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceConfigurationTest {

    @Test
    fun `required flags retain default reporting and cross-window node discovery`() {
        val flags = AccessibilityServiceConfiguration.requiredFlags()

        assertTrue(flags and AccessibilityServiceInfo.DEFAULT != 0)
        assertTrue(flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        assertTrue(flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS != 0)
        assertTrue(flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
    }
}

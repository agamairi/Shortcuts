package com.shortcuts.app.widget

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWidgetConfigActivityTest {

    @Test
    fun `every legacy config activity relays successful unified setup with the same app widget id`() {
        val completion = legacyWidgetConfigCompletion(appWidgetId = 714, resultCode = Activity.RESULT_OK)

        assertEquals(LegacyWidgetConfigCompletion(714), completion)
        val legacyActivities = listOf(
            AutomationWidgetConfigActivity::class.java,
            CustomWidgetConfigActivity::class.java,
            GridWidgetConfigActivity::class.java,
            GreetingWidgetConfigActivity::class.java,
            ShortcutsListWidgetConfigActivity::class.java
        )
        assertTrue(legacyActivities.all { LegacyWidgetConfigActivity::class.java.isAssignableFrom(it) })
    }

    @Test
    fun `legacy config relay does not report success when unified setup is cancelled`() {
        assertEquals(null, legacyWidgetConfigCompletion(appWidgetId = 714, resultCode = Activity.RESULT_CANCELED))
    }
}

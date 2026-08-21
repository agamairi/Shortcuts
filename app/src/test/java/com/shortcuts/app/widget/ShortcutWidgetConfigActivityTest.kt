package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.WidgetLayoutKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutWidgetConfigActivityTest {

    @Test
    fun `unified config persists each layout as its enum name`() {
        WidgetLayoutKey.entries.forEach { layout ->
            val config = unifiedWidgetConfig(
                widgetId = 42,
                automationIds = listOf(1),
                layout = layout
            )

            assertEquals(layout.name, config.layoutKey)
            assertEquals(WidgetConfigSource.UNIFIED.name, config.sourceType)
        }
    }

    @Test
    fun `unified config defaults layout to auto and always identifies its source`() {
        val config = unifiedWidgetConfig(widgetId = 42, automationIds = listOf(1))

        assertEquals(WidgetLayoutKey.AUTO.name, config.layoutKey)
    }

    @Test
    fun `unified config keeps only the first six automation ids in order`() {
        val config = unifiedWidgetConfig(
            widgetId = 42,
            automationIds = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        )

        assertEquals(listOf(1, 2, 3, 4, 5, 6), Gson().fromJson(config.automationIdsJson, IntArray::class.java).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unified config rejects an empty automation id list`() {
        unifiedWidgetConfig(widgetId = 42, automationIds = emptyList())
    }
}

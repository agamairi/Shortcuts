package com.shortcuts.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import com.google.gson.Gson
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.WidgetLayoutKey
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPinCallbackTest {

    @Test
    fun `parsePinCallbackIntent parses valid pinned intent with automation and template`() {
        val intent = mockk<Intent>()
        every { intent.action } returns ShortcutWidgetPinRequest.ACTION_WIDGET_PINNED
        every { intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) } returns 123
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_AUTOMATION_ID, 0) } returns 42
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_TEMPLATE_ID, 0) } returns 7

        val parsed = parsePinCallbackIntent(intent)
        assertNotNull(parsed)
        assertEquals(123, parsed!!.appWidgetId)
        assertEquals(42, parsed.automationId)
        assertEquals(7, parsed.templateId)
    }

    @Test
    fun `parsePinCallbackIntent parses valid pinned intent without template`() {
        val intent = mockk<Intent>()
        every { intent.action } returns ShortcutWidgetPinRequest.ACTION_WIDGET_PINNED
        every { intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) } returns 123
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_AUTOMATION_ID, 0) } returns 42
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_TEMPLATE_ID, 0) } returns 0

        val parsed = parsePinCallbackIntent(intent)
        assertNotNull(parsed)
        assertEquals(123, parsed!!.appWidgetId)
        assertEquals(42, parsed.automationId)
        assertNull(parsed.templateId)
    }

    @Test
    fun `parsePinCallbackIntent returns null for wrong action`() {
        val intent = mockk<Intent>()
        every { intent.action } returns "android.appwidget.action.APPWIDGET_UPDATE"

        val parsed = parsePinCallbackIntent(intent)
        assertNull(parsed)
    }

    @Test
    fun `parsePinCallbackIntent returns null for invalid appWidgetId`() {
        val intent = mockk<Intent>()
        every { intent.action } returns ShortcutWidgetPinRequest.ACTION_WIDGET_PINNED
        every { intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) } returns AppWidgetManager.INVALID_APPWIDGET_ID
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_AUTOMATION_ID, 0) } returns 42
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_TEMPLATE_ID, 0) } returns 0

        val parsed = parsePinCallbackIntent(intent)
        assertNull(parsed)
    }

    @Test
    fun `parsePinCallbackIntent returns null for invalid automationId`() {
        val intent = mockk<Intent>()
        every { intent.action } returns ShortcutWidgetPinRequest.ACTION_WIDGET_PINNED
        every { intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) } returns 123
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_AUTOMATION_ID, 0) } returns 0
        every { intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_TEMPLATE_ID, 0) } returns 0

        val parsed = parsePinCallbackIntent(intent)
        assertNull(parsed)
    }

    @Test
    fun `createPinnedWidgetConfig with template copies visual attributes and SINGLE layout`() {
        val template = CustomWidgetTemplate(
            id = 5,
            label = "Morning Routine",
            colorKey = "AMBER",
            iconKey = "COFFEE",
            automationId = 10
        )

        val config = createPinnedWidgetConfig(
            appWidgetId = 77,
            automationId = 10,
            templateId = 5,
            template = template
        )

        assertEquals(77, config.widgetId)
        assertEquals(WidgetConfigSource.UNIFIED.name, config.sourceType)
        assertEquals(listOf(10), Gson().fromJson(config.automationIdsJson, IntArray::class.java).toList())
        assertEquals(5, config.templateId)
        assertEquals("Morning Routine", config.label)
        assertEquals("AMBER", config.colorKey)
        assertEquals("COFFEE", config.iconKey)
        assertEquals(WidgetLayoutKey.SINGLE.name, config.layoutKey)
    }

    @Test
    fun `createPinnedWidgetConfig without template creates clean default single widget`() {
        val config = createPinnedWidgetConfig(
            appWidgetId = 88,
            automationId = 20,
            templateId = null,
            template = null
        )

        assertEquals(88, config.widgetId)
        assertEquals(WidgetConfigSource.UNIFIED.name, config.sourceType)
        assertEquals(listOf(20), Gson().fromJson(config.automationIdsJson, IntArray::class.java).toList())
        assertNull(config.templateId)
        assertNull(config.label)
        assertNull(config.colorKey)
        assertNull(config.iconKey)
        assertEquals(WidgetLayoutKey.SINGLE.name, config.layoutKey)
    }
}

package com.shortcuts.app.util

import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationVisualsTest {

    @Test
    fun `colorForAutomation is deterministic`() {
        val id = 42
        val firstCall = AutomationVisuals.colorForAutomation(id)
        val secondCall = AutomationVisuals.colorForAutomation(id)
        assertEquals(firstCall, secondCall)
    }

    @Test
    fun `iconForAutomation is deterministic`() {
        val id = 42
        val firstCall = AutomationVisuals.iconForAutomation(id)
        val secondCall = AutomationVisuals.iconForAutomation(id)
        assertEquals(firstCall, secondCall)
    }

    @Test
    fun `colorForAutomation spreads across the palette`() {
        val colors = (1..50).map { AutomationVisuals.colorForAutomation(it) }.toSet()
        assertTrue(colors.size > 1)
        assertTrue(colors.size <= WidgetColorKey.entries.size)
    }

    @Test
    fun `iconForAutomation spreads across the palette`() {
        val icons = (1..50).map { AutomationVisuals.iconForAutomation(it) }.toSet()
        assertTrue(icons.size > 1)
        assertTrue(icons.size <= WidgetIconKey.entries.size)
    }
}

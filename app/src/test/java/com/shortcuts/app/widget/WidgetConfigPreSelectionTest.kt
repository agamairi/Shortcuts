package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.WidgetLayoutKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigPreSelectionTest {

    private val gson = Gson()
    private val availableShortcuts = listOf(
        Automation(id = 10, name = "Shortcut 10", actionsJson = "[]"),
        Automation(id = 20, name = "Shortcut 20", actionsJson = "[]"),
        Automation(id = 30, name = "Shortcut 30", actionsJson = "[]")
    )

    @Test
    fun `reconfiguring existing config preselects existing ids in exact saved order and preserves layout`() {
        val existingConfig = WidgetConfig(
            widgetId = 99,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(30, 10)),
            layoutKey = WidgetLayoutKey.GRID.name
        )

        val result = computeInitialSelection(
            existingConfig = existingConfig,
            intentAutomationId = null,
            availableShortcuts = availableShortcuts
        )

        assertEquals(listOf(30, 10), result.selectedIds)
        assertEquals(WidgetLayoutKey.GRID, result.layout)
    }

    @Test
    fun `reconfiguring existing config filters out deleted automation ids`() {
        val existingConfig = WidgetConfig(
            widgetId = 99,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(999, 20, 888)),
            layoutKey = WidgetLayoutKey.LIST.name
        )

        val result = computeInitialSelection(
            existingConfig = existingConfig,
            intentAutomationId = null,
            availableShortcuts = availableShortcuts
        )

        assertEquals(listOf(20), result.selectedIds)
        assertEquals(WidgetLayoutKey.LIST, result.layout)
    }

    @Test
    fun `reconfiguring existing config when all ids deleted falls back to empty selection or intent`() {
        val existingConfig = WidgetConfig(
            widgetId = 99,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(999, 888)),
            layoutKey = WidgetLayoutKey.LIST.name
        )

        val resultWithIntent = computeInitialSelection(
            existingConfig = existingConfig,
            intentAutomationId = 10,
            availableShortcuts = availableShortcuts
        )
        assertEquals(listOf(10), resultWithIntent.selectedIds)
        assertEquals(WidgetLayoutKey.AUTO, resultWithIntent.layout)

        val resultWithoutIntent = computeInitialSelection(
            existingConfig = existingConfig,
            intentAutomationId = null,
            availableShortcuts = availableShortcuts
        )
        assertTrue(resultWithoutIntent.selectedIds.isEmpty())
        assertEquals(WidgetLayoutKey.AUTO, resultWithoutIntent.layout)
    }

    @Test
    fun `new widget placement with intent automation id preselects single shortcut and defaults to AUTO layout`() {
        val result = computeInitialSelection(
            existingConfig = null,
            intentAutomationId = 20,
            availableShortcuts = availableShortcuts
        )

        assertEquals(listOf(20), result.selectedIds)
        assertEquals(WidgetLayoutKey.AUTO, result.layout)
    }

    @Test
    fun `new widget placement with invalid or non-existent intent id leaves selection empty`() {
        val result = computeInitialSelection(
            existingConfig = null,
            intentAutomationId = 9999,
            availableShortcuts = availableShortcuts
        )

        assertTrue(result.selectedIds.isEmpty())
        assertEquals(WidgetLayoutKey.AUTO, result.layout)
    }

    @Test
    fun `new widget placement from standard launcher picker has empty selection`() {
        val result = computeInitialSelection(
            existingConfig = null,
            intentAutomationId = null,
            availableShortcuts = availableShortcuts
        )

        assertTrue(result.selectedIds.isEmpty())
        assertEquals(WidgetLayoutKey.AUTO, result.layout)
    }
}

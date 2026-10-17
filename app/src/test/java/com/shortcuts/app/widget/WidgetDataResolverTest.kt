package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.WidgetLayoutKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDataResolverTest {

    private val gson = Gson()

    @Test
    fun `null config resolves to empty shortcuts and AUTO layout`() {
        val automations = listOf(Automation(id = 1, name = "Auto 1", actionsJson = "[]"))
        val snapshot = resolveWidgetSnapshot(null, automations, emptyList())

        assertTrue(snapshot.shortcuts.isEmpty())
        assertEquals(WidgetLayoutKey.AUTO, snapshot.layout)
    }

    @Test
    fun `empty automations list resolves to empty shortcuts`() {
        val config = WidgetConfig(
            widgetId = 1,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(1, 2)),
            layoutKey = WidgetLayoutKey.AUTO.name
        )
        val snapshot = resolveWidgetSnapshot(config, emptyList(), emptyList())

        assertTrue(snapshot.shortcuts.isEmpty())
        assertEquals(WidgetLayoutKey.AUTO, snapshot.layout)
    }

    @Test
    fun `valid config with automation ids maps shortcuts in exact specified order`() {
        val auto1 = Automation(id = 1, name = "First", actionsJson = "[]")
        val auto2 = Automation(id = 2, name = "Second", actionsJson = "[]")
        val auto3 = Automation(id = 3, name = "Third", actionsJson = "[]")
        val automations = listOf(auto1, auto2, auto3)

        // Config specifies reverse order: 3, 1
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(3, 1)),
            layoutKey = WidgetLayoutKey.LIST.name
        )
        val snapshot = resolveWidgetSnapshot(config, automations, emptyList())

        assertEquals(WidgetLayoutKey.LIST, snapshot.layout)
        assertEquals(2, snapshot.shortcuts.size)
        assertEquals(3, snapshot.shortcuts[0].automation.id)
        assertEquals("Third", snapshot.shortcuts[0].label)
        assertEquals(1, snapshot.shortcuts[1].automation.id)
        assertEquals("First", snapshot.shortcuts[1].label)
    }

    @Test
    fun `missing or deleted automation ids are omitted while preserving existing ones`() {
        val auto1 = Automation(id = 1, name = "Alive", actionsJson = "[]")
        val automations = listOf(auto1)

        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(99, 1, 88)),
            layoutKey = WidgetLayoutKey.AUTO.name
        )
        val snapshot = resolveWidgetSnapshot(config, automations, emptyList())

        assertEquals(1, snapshot.shortcuts.size)
        assertEquals(1, snapshot.shortcuts[0].automation.id)
        assertEquals("Alive", snapshot.shortcuts[0].label)
    }

    @Test
    fun `all automation ids deleted resolves to empty shortcut list triggering needs-setup`() {
        val automations = listOf(Automation(id = 5, name = "Unrelated", actionsJson = "[]"))
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(1, 2, 3)),
            layoutKey = WidgetLayoutKey.AUTO.name
        )
        val snapshot = resolveWidgetSnapshot(config, automations, emptyList())

        assertTrue(snapshot.shortcuts.isEmpty())
    }

    @Test
    fun `single layout label precedence prefers config label over template and automation name`() {
        val auto = Automation(id = 1, name = "AutoName", actionsJson = "[]")
        val template = CustomWidgetTemplate(id = 10, label = "TemplateLabel", colorKey = "BLUE", iconKey = "BOLT", automationId = 1)
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(1)),
            templateId = 10,
            label = "CustomConfigLabel",
            colorKey = "GREEN",
            iconKey = "STAR",
            layoutKey = WidgetLayoutKey.SINGLE.name
        )

        val snapshot = resolveWidgetSnapshot(config, listOf(auto), listOf(template))
        assertEquals(1, snapshot.shortcuts.size)
        val tile = snapshot.shortcuts[0]
        assertEquals("CustomConfigLabel", tile.label)
        assertEquals("GREEN", tile.colorKey)
        assertEquals("STAR", tile.iconKey)
    }

    @Test
    fun `single layout falls back to template label when config label is blank`() {
        val auto = Automation(id = 1, name = "AutoName", actionsJson = "[]")
        val template = CustomWidgetTemplate(id = 10, label = "TemplateLabel", colorKey = "BLUE", iconKey = "BOLT", automationId = 1)
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(1)),
            templateId = 10,
            label = "   ",
            layoutKey = WidgetLayoutKey.SINGLE.name
        )

        val snapshot = resolveWidgetSnapshot(config, listOf(auto), listOf(template))
        assertEquals(1, snapshot.shortcuts.size)
        val tile = snapshot.shortcuts[0]
        assertEquals("TemplateLabel", tile.label)
        assertEquals("BLUE", tile.colorKey)
        assertEquals("BOLT", tile.iconKey)
    }

    @Test
    fun `single layout falls back to automation name when config and template labels are absent`() {
        val auto = Automation(id = 1, name = "AutoName", actionsJson = "[]")
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(1)),
            layoutKey = WidgetLayoutKey.SINGLE.name
        )

        val snapshot = resolveWidgetSnapshot(config, listOf(auto), emptyList())
        assertEquals(1, snapshot.shortcuts.size)
        val tile = snapshot.shortcuts[0]
        assertEquals("AutoName", tile.label)
    }

    @Test
    fun `multi shortcut layout uses automation name for each tile even if config label is set`() {
        val auto1 = Automation(id = 1, name = "Auto One", actionsJson = "[]")
        val auto2 = Automation(id = 2, name = "Auto Two", actionsJson = "[]")
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(1, 2)),
            label = "IgnoredMultiLabel",
            layoutKey = WidgetLayoutKey.GRID.name
        )

        val snapshot = resolveWidgetSnapshot(config, listOf(auto1, auto2), emptyList())
        assertEquals(2, snapshot.shortcuts.size)
        assertEquals("Auto One", snapshot.shortcuts[0].label)
        assertEquals("Auto Two", snapshot.shortcuts[1].label)
    }

    @Test
    fun `grid source type resolves via templateIdsJson and custom widget templates`() {
        val auto1 = Automation(id = 100, name = "Base Auto", actionsJson = "[]")
        val template1 = CustomWidgetTemplate(id = 7, label = "Tile 7", colorKey = "RED", iconKey = "HEART", automationId = 100)
        val config = WidgetConfig(
            widgetId = 42,
            sourceType = WidgetConfigSource.GRID.name,
            automationIdsJson = "[]",
            templateIdsJson = gson.toJson(listOf(7)),
            layoutKey = WidgetLayoutKey.GRID.name
        )

        val snapshot = resolveWidgetSnapshot(config, listOf(auto1), listOf(template1))
        assertEquals(1, snapshot.shortcuts.size)
        val tile = snapshot.shortcuts[0]
        assertEquals("Tile 7", tile.label)
        assertEquals("RED", tile.colorKey)
        assertEquals("HEART", tile.iconKey)
        assertEquals(100, tile.automation.id)
    }
}

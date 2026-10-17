package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.pruneConfigs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPruneDeletedAutomationsTest {

    private val gson = Gson()

    @Test
    fun `configs with all ids present remain unchanged and are not returned as modified`() {
        val configs = listOf(
            WidgetConfig(
                widgetId = 1,
                sourceType = WidgetConfigSource.UNIFIED.name,
                automationIdsJson = gson.toJson(listOf(10, 20))
            )
        )
        val existingIds = setOf(10, 20, 30)

        val modified = pruneConfigs(configs, existingIds)
        assertTrue(modified.isEmpty())
    }

    @Test
    fun `config with partially deleted automation ids has deleted ids pruned while retaining valid ones`() {
        val config = WidgetConfig(
            widgetId = 1,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(10, 99, 20, 88))
        )
        val existingIds = setOf(10, 20)

        val modified = pruneConfigs(listOf(config), existingIds)
        assertEquals(1, modified.size)
        assertEquals(1, modified[0].widgetId)
        val remainingIds = gson.fromJson(modified[0].automationIdsJson, IntArray::class.java).toList()
        assertEquals(listOf(10, 20), remainingIds)
    }

    @Test
    fun `config with all automation ids deleted is pruned to empty list`() {
        val config = WidgetConfig(
            widgetId = 2,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(99, 88))
        )
        val existingIds = setOf(10, 20)

        val modified = pruneConfigs(listOf(config), existingIds)
        assertEquals(1, modified.size)
        val remainingIds = gson.fromJson(modified[0].automationIdsJson, IntArray::class.java).toList()
        assertTrue(remainingIds.isEmpty())
    }

    @Test
    fun `only configs that actually changed are returned in modified list`() {
        val configClean = WidgetConfig(
            widgetId = 1,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(10))
        )
        val configDirty = WidgetConfig(
            widgetId = 2,
            sourceType = WidgetConfigSource.UNIFIED.name,
            automationIdsJson = gson.toJson(listOf(10, 99))
        )
        val existingIds = setOf(10)

        val modified = pruneConfigs(listOf(configClean, configDirty), existingIds)
        assertEquals(1, modified.size)
        assertEquals(2, modified[0].widgetId)
        assertEquals(listOf(10), gson.fromJson(modified[0].automationIdsJson, IntArray::class.java).toList())
    }
}

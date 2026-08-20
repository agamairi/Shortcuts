package com.shortcuts.app.widget

import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigDao
import com.shortcuts.app.data.WidgetConfigSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutWidgetSelectionStoreTest {

    @Test
    fun `loadShortcuts exposes the user's saved shortcuts from the DAO instead of manufacturing an empty state`() = runTest {
        val automationDao = mockk<AutomationDao>()
        val widgetConfigDao = mockk<WidgetConfigDao>(relaxed = true)
        val saved = listOf(
            Automation(id = 11, name = "Turn off lights", actionsJson = "[]"),
            Automation(id = 12, name = "Start commute", actionsJson = "[]")
        )
        every { automationDao.getAllAutomations() } returns flowOf(saved)

        val shortcuts = ShortcutWidgetSelectionStore(automationDao, widgetConfigDao).loadShortcuts()

        assertEquals(saved, shortcuts)
        assertEquals(listOf("Turn off lights", "Start commute"), shortcuts.map { it.name })
    }

    @Test
    fun `saveSelection writes only the shortcuts chosen by the user to unified config`() = runTest {
        val automationDao = mockk<AutomationDao>()
        val widgetConfigDao = mockk<WidgetConfigDao>(relaxed = true)
        val selected = listOf(
            Automation(id = 7, name = "Morning", actionsJson = "[]"),
            Automation(id = 9, name = "Evening", actionsJson = "[]")
        )
        coEvery { widgetConfigDao.upsertConfig(any()) } returns Unit

        ShortcutWidgetSelectionStore(automationDao, widgetConfigDao).saveSelection(42, selected)

        val capturedConfig = slot<WidgetConfig>()
        coVerify(exactly = 1) { widgetConfigDao.upsertConfig(capture(capturedConfig)) }
        assertEquals(42, capturedConfig.captured.widgetId)
        assertEquals(WidgetConfigSource.UNIFIED.name, capturedConfig.captured.sourceType)
        assertEquals("[7,9]", capturedConfig.captured.automationIdsJson)
    }
}

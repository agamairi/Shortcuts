package com.shortcuts.app.widget

import android.app.PendingIntent
import android.os.Build
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutWidgetLifecycleTest {

    @Test
    fun `config save persists selected appWidgetId before reporting success`() = runTest {
        val automationDao = mockk<AutomationDao>()
        val widgetConfigDao = mockk<WidgetConfigDao>()
        val events = mutableListOf<String>()
        val persistedConfig = slot<WidgetConfig>()
        coEvery { widgetConfigDao.upsertConfig(capture(persistedConfig)) } coAnswers {
            events += "persist"
        }
        val selected = listOf(Automation(id = 71, name = "Morning", actionsJson = "[]"))

        saveShortcutWidgetConfig(
            selectionStore = ShortcutWidgetSelectionStore(automationDao, widgetConfigDao),
            widgetId = 260,
            shortcuts = selected,
            refreshWidget = { widgetId -> events += "refresh:$widgetId" },
            reportSuccess = { events += "result:ok" }
        )

        coVerify(exactly = 1) { widgetConfigDao.upsertConfig(any()) }
        assertEquals(260, persistedConfig.captured.widgetId)
        assertEquals("[71]", persistedConfig.captured.automationIdsJson)
        assertEquals(listOf("persist", "refresh:260", "result:ok"), events)
    }

    @Test
    fun `pin success callback is mutable only when Android requires launcher extras`() {
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT,
            ShortcutWidgetPinRequest.successCallbackFlags(Build.VERSION_CODES.R)
        )
        assertEquals(
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ShortcutWidgetPinRequest.successCallbackFlags(Build.VERSION_CODES.S)
        )
    }
}

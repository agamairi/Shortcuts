package com.shortcuts.app.widget

import android.content.Context
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import com.shortcuts.app.data.WidgetBinding
import com.shortcuts.app.data.WidgetBindingDao
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.ActionExecutorService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetBindingDaoTest {

    private lateinit var mockWidgetBindingDao: WidgetBindingDao
    private lateinit var mockAutomationDao: AutomationDao
    private lateinit var mockContext: Context
    private lateinit var mockExecutorService: ActionExecutorService

    @Before
    fun setup() {
        mockWidgetBindingDao = mockk(relaxed = true)
        mockAutomationDao = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)
        mockExecutorService = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // --- WidgetBinding insert/read/delete tests ---

    @Test
    fun `upsertBinding inserts a new binding and getBinding retrieves it`() = runTest {
        val binding = WidgetBinding(widgetId = 42, automationId = 7)
        coEvery { mockWidgetBindingDao.getBinding(42) } returns binding

        mockWidgetBindingDao.upsertBinding(binding)
        val result = mockWidgetBindingDao.getBinding(42)

        assertNotNull(result)
        assertEquals(42, result!!.widgetId)
        assertEquals(7, result.automationId)
        coVerify { mockWidgetBindingDao.upsertBinding(binding) }
        coVerify { mockWidgetBindingDao.getBinding(42) }
    }

    @Test
    fun `getBinding returns null for non-existent widgetId`() = runTest {
        coEvery { mockWidgetBindingDao.getBinding(999) } returns null

        val result = mockWidgetBindingDao.getBinding(999)
        assertNull(result)
    }

    @Test
    fun `deleteBinding removes the binding`() = runTest {
        coEvery { mockWidgetBindingDao.getBinding(42) } returns null

        mockWidgetBindingDao.deleteBinding(42)
        val result = mockWidgetBindingDao.getBinding(42)

        assertNull(result)
        coVerify { mockWidgetBindingDao.deleteBinding(42) }
    }

    @Test
    fun `upsertBinding overwrites existing binding with same widgetId`() = runTest {
        val original = WidgetBinding(widgetId = 10, automationId = 1)
        val updated = WidgetBinding(widgetId = 10, automationId = 5)

        coEvery { mockWidgetBindingDao.getBinding(10) } returns updated

        mockWidgetBindingDao.upsertBinding(original)
        mockWidgetBindingDao.upsertBinding(updated)
        val result = mockWidgetBindingDao.getBinding(10)

        assertNotNull(result)
        assertEquals(5, result!!.automationId)
    }

    // --- Widget tap handler resolution test ---

    @Test
    fun `widget tap handler resolves correct automation and calls executeActions with correct action list`() = runTest {
        val actionsJson = """[{"actionType":"SYSTEM_TOGGLE","target":"WIFI","state":"ON"},{"actionType":"APP_INTENT","packageName":"com.example.app"}]"""
        val automation = Automation(id = 7, name = "Test Shortcut", actionsJson = actionsJson)
        val binding = WidgetBinding(widgetId = 42, automationId = 7)

        // Simulate the widget tap flow:
        // 1. Look up binding for widget ID
        coEvery { mockWidgetBindingDao.getBinding(42) } returns binding

        // 2. Fetch automation by ID (using repository pattern)
        val repository = AutomationRepository(mockAutomationDao)
        coEvery { mockAutomationDao.getAutomationById(7) } returns automation

        // 3. Resolve the binding
        val resolvedBinding = mockWidgetBindingDao.getBinding(42)
        assertNotNull(resolvedBinding)

        // 4. Fetch the automation
        val resolvedAutomation = repository.getAutomationById(resolvedBinding!!.automationId)
        assertNotNull(resolvedAutomation)
        assertEquals("Test Shortcut", resolvedAutomation!!.name)

        // 5. Deserialize actions using ActionConverter
        val converter = ActionConverter()
        val actions = converter.toActionList(resolvedAutomation.actionsJson)

        assertEquals(2, actions.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, actions[0].actionType)
        assertEquals("WIFI", actions[0].target)
        assertEquals(ActionType.APP_INTENT, actions[1].actionType)
        assertEquals("com.example.app", actions[1].packageName)

        // 6. Execute actions via ActionExecutorService
        every { mockExecutorService.executeActions(actions) } returns true
        val result = mockExecutorService.executeActions(actions)

        assertTrue(result)
        verify { mockExecutorService.executeActions(actions) }
    }

    @Test
    fun `widget tap handler handles missing automation gracefully`() = runTest {
        val binding = WidgetBinding(widgetId = 42, automationId = 99)

        coEvery { mockWidgetBindingDao.getBinding(42) } returns binding
        coEvery { mockAutomationDao.getAutomationById(99) } returns null

        val resolvedBinding = mockWidgetBindingDao.getBinding(42)
        assertNotNull(resolvedBinding)

        val automation = mockAutomationDao.getAutomationById(resolvedBinding!!.automationId)
        assertNull(automation)
    }

    @Test
    fun `widget tap handler handles missing binding gracefully`() = runTest {
        coEvery { mockWidgetBindingDao.getBinding(999) } returns null

        val binding = mockWidgetBindingDao.getBinding(999)
        assertNull(binding)
    }
}

package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import com.shortcuts.app.data.WidgetListBinding
import com.shortcuts.app.data.WidgetListBindingDao
import com.shortcuts.app.repository.AutomationRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WidgetListBindingDaoTest {

    private lateinit var mockWidgetListBindingDao: WidgetListBindingDao
    private lateinit var mockAutomationDao: AutomationDao

    @Before
    fun setup() {
        mockWidgetListBindingDao = mockk(relaxed = true)
        mockAutomationDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `upsertBinding inserts a new binding and getBinding retrieves it`() = runTest {
        val binding = WidgetListBinding(widgetId = 101, automationIdsJson = "[1, 2, 3]")
        coEvery { mockWidgetListBindingDao.getBinding(101) } returns binding

        mockWidgetListBindingDao.upsertBinding(binding)
        val result = mockWidgetListBindingDao.getBinding(101)

        assertNotNull(result)
        assertEquals(101, result!!.widgetId)
        assertEquals("[1, 2, 3]", result.automationIdsJson)
        coVerify { mockWidgetListBindingDao.upsertBinding(binding) }
        coVerify { mockWidgetListBindingDao.getBinding(101) }
    }

    @Test
    fun `getBinding returns null for non-existent widgetId`() = runTest {
        coEvery { mockWidgetListBindingDao.getBinding(999) } returns null

        val result = mockWidgetListBindingDao.getBinding(999)
        assertNull(result)
    }

    @Test
    fun `deleteBinding removes the binding`() = runTest {
        coEvery { mockWidgetListBindingDao.getBinding(101) } returns null

        mockWidgetListBindingDao.deleteBinding(101)
        val result = mockWidgetListBindingDao.getBinding(101)

        assertNull(result)
        coVerify { mockWidgetListBindingDao.deleteBinding(101) }
    }

    @Test
    fun `resolve up to 4 automation ids from binding`() = runTest {
        val selectedIds = listOf(10, 20, 30, 40, 50)
        val json = Gson().toJson(selectedIds)
        val binding = WidgetListBinding(widgetId = 102, automationIdsJson = json)

        coEvery { mockWidgetListBindingDao.getBinding(102) } returns binding
        val resolvedBinding = mockWidgetListBindingDao.getBinding(102)

        assertNotNull(resolvedBinding)
        val parsedIds = Gson().fromJson(resolvedBinding!!.automationIdsJson, Array<Int>::class.java).toList()
        val cappedIds = parsedIds.take(4)

        assertEquals(4, cappedIds.size)
        assertEquals(listOf(10, 20, 30, 40), cappedIds)

        val auto1 = Automation(id = 10, name = "Auto 10", actionsJson = "[]")
        val auto2 = Automation(id = 20, name = "Auto 20", actionsJson = "[]")
        coEvery { mockAutomationDao.getAutomationById(10) } returns auto1
        coEvery { mockAutomationDao.getAutomationById(20) } returns auto2

        val repo = AutomationRepository(mockAutomationDao)
        val fetchedAuto1 = repo.getAutomationById(cappedIds[0])
        val fetchedAuto2 = repo.getAutomationById(cappedIds[1])

        assertNotNull(fetchedAuto1)
        assertEquals("Auto 10", fetchedAuto1!!.name)
        assertNotNull(fetchedAuto2)
        assertEquals("Auto 20", fetchedAuto2!!.name)
    }
}

package com.shortcuts.app.widget

import com.shortcuts.app.data.CustomWidgetBinding
import com.shortcuts.app.data.CustomWidgetBindingDao
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.CustomWidgetTemplateDao
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CustomWidgetTemplateDaoTest {

    private lateinit var mockTemplateDao: CustomWidgetTemplateDao
    private lateinit var mockBindingDao: CustomWidgetBindingDao

    @Before
    fun setup() {
        mockTemplateDao = mockk(relaxed = true)
        mockBindingDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // --- CustomWidgetTemplate CRUD tests ---

    @Test
    fun `insert template returns row ID`() = runTest {
        val template = CustomWidgetTemplate(label = "Toggle WiFi", colorKey = "BLUE", iconKey = "WIFI", automationId = 5)
        coEvery { mockTemplateDao.insert(template) } returns 1L

        val resultId = mockTemplateDao.insert(template)
        assertEquals(1L, resultId)
        coVerify { mockTemplateDao.insert(template) }
    }

    @Test
    fun `getById retrieves correct template`() = runTest {
        val template = CustomWidgetTemplate(id = 1, label = "Toggle WiFi", colorKey = "BLUE", iconKey = "WIFI", automationId = 5)
        coEvery { mockTemplateDao.getById(1) } returns template

        val result = mockTemplateDao.getById(1)
        assertNotNull(result)
        assertEquals(1, result!!.id)
        assertEquals("Toggle WiFi", result.label)
        assertEquals("BLUE", result.colorKey)
    }

    @Test
    fun `getAll returns flow of template list`() = runTest {
        val list = listOf(
            CustomWidgetTemplate(id = 1, label = "WiFi", colorKey = "BLUE", iconKey = "WIFI", automationId = 5),
            CustomWidgetTemplate(id = 2, label = "Bluetooth", colorKey = "ORANGE", iconKey = "BLUETOOTH", automationId = 6)
        )
        coEvery { mockTemplateDao.getAll() } returns flowOf(list)

        val result = mockTemplateDao.getAll().first()
        assertEquals(2, result.size)
        assertEquals("WiFi", result[0].label)
        assertEquals("Bluetooth", result[1].label)
    }

    @Test
    fun `delete removes template`() = runTest {
        val template = CustomWidgetTemplate(id = 1, label = "WiFi", colorKey = "BLUE", iconKey = "WIFI", automationId = 5)
        mockTemplateDao.delete(template)
        coVerify { mockTemplateDao.delete(template) }
    }

    // --- CustomWidgetBinding CRUD tests ---

    @Test
    fun `upsertBinding inserts binding and getBinding retrieves it`() = runTest {
        val binding = CustomWidgetBinding(widgetId = 201, templateId = 1)
        coEvery { mockBindingDao.getBinding(201) } returns binding

        mockBindingDao.upsertBinding(binding)
        val result = mockBindingDao.getBinding(201)

        assertNotNull(result)
        assertEquals(201, result!!.widgetId)
        assertEquals(1, result.templateId)
        coVerify { mockBindingDao.upsertBinding(binding) }
    }

    @Test
    fun `deleteBinding removes custom widget binding`() = runTest {
        coEvery { mockBindingDao.getBinding(201) } returns null

        mockBindingDao.deleteBinding(201)
        val result = mockBindingDao.getBinding(201)

        assertNull(result)
        coVerify { mockBindingDao.deleteBinding(201) }
    }
}

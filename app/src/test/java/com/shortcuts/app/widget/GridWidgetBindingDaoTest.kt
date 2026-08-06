package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.CustomWidgetTemplateDao
import com.shortcuts.app.data.GridWidgetBinding
import com.shortcuts.app.data.GridWidgetBindingDao
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

class GridWidgetBindingDaoTest {

    private lateinit var mockGridWidgetBindingDao: GridWidgetBindingDao
    private lateinit var mockCustomWidgetTemplateDao: CustomWidgetTemplateDao

    @Before
    fun setup() {
        mockGridWidgetBindingDao = mockk(relaxed = true)
        mockCustomWidgetTemplateDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `upsertBinding inserts a new binding and getBinding retrieves it`() = runTest {
        val binding = GridWidgetBinding(widgetId = 201, templateIdsJson = "[1, 2, 3]")
        coEvery { mockGridWidgetBindingDao.getBinding(201) } returns binding

        mockGridWidgetBindingDao.upsertBinding(binding)
        val result = mockGridWidgetBindingDao.getBinding(201)

        assertNotNull(result)
        assertEquals(201, result!!.widgetId)
        assertEquals("[1, 2, 3]", result.templateIdsJson)
        coVerify { mockGridWidgetBindingDao.upsertBinding(binding) }
        coVerify { mockGridWidgetBindingDao.getBinding(201) }
    }

    @Test
    fun `getBinding returns null for non-existent widgetId`() = runTest {
        coEvery { mockGridWidgetBindingDao.getBinding(999) } returns null

        val result = mockGridWidgetBindingDao.getBinding(999)
        assertNull(result)
    }

    @Test
    fun `deleteBinding removes the binding`() = runTest {
        coEvery { mockGridWidgetBindingDao.getBinding(201) } returns null

        mockGridWidgetBindingDao.deleteBinding(201)
        val result = mockGridWidgetBindingDao.getBinding(201)

        assertNull(result)
        coVerify { mockGridWidgetBindingDao.deleteBinding(201) }
    }

    @Test
    fun `resolve up to 6 template ids from binding`() = runTest {
        val selectedIds = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        val json = Gson().toJson(selectedIds)
        val binding = GridWidgetBinding(widgetId = 202, templateIdsJson = json)

        coEvery { mockGridWidgetBindingDao.getBinding(202) } returns binding
        val resolvedBinding = mockGridWidgetBindingDao.getBinding(202)

        assertNotNull(resolvedBinding)
        val parsedIds = Gson().fromJson(resolvedBinding!!.templateIdsJson, Array<Int>::class.java).toList()
        val cappedIds = parsedIds.take(6)

        assertEquals(6, cappedIds.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), cappedIds)

        val template1 = CustomWidgetTemplate(id = 1, label = "Tile 1", colorKey = "BLUE", iconKey = "STAR", automationId = 10)
        val template2 = CustomWidgetTemplate(id = 2, label = "Tile 2", colorKey = "RED", iconKey = "PLAY", automationId = 20)
        coEvery { mockCustomWidgetTemplateDao.getById(1) } returns template1
        coEvery { mockCustomWidgetTemplateDao.getById(2) } returns template2

        val fetched1 = mockCustomWidgetTemplateDao.getById(cappedIds[0])
        val fetched2 = mockCustomWidgetTemplateDao.getById(cappedIds[1])

        assertNotNull(fetched1)
        assertEquals("Tile 1", fetched1!!.label)
        assertEquals("BLUE", fetched1.colorKey)
        assertNotNull(fetched2)
        assertEquals("Tile 2", fetched2!!.label)
        assertEquals("RED", fetched2.colorKey)
    }
}

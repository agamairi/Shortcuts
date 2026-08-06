package com.shortcuts.app.widget

import com.shortcuts.app.data.GreetingWidgetBinding
import com.shortcuts.app.data.GreetingWidgetBindingDao
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

class GreetingWidgetBindingDaoTest {

    private lateinit var mockGreetingWidgetBindingDao: GreetingWidgetBindingDao

    @Before
    fun setup() {
        mockGreetingWidgetBindingDao = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `upsertBinding inserts a new binding and getBinding retrieves it`() = runTest {
        val binding = GreetingWidgetBinding(
            widgetId = 301,
            userName = "Alex",
            colorKey = "BLUE",
            automationId = 42
        )
        coEvery { mockGreetingWidgetBindingDao.getBinding(301) } returns binding

        mockGreetingWidgetBindingDao.upsertBinding(binding)
        val result = mockGreetingWidgetBindingDao.getBinding(301)

        assertNotNull(result)
        assertEquals(301, result!!.widgetId)
        assertEquals("Alex", result.userName)
        assertEquals("BLUE", result.colorKey)
        assertEquals(42, result.automationId)

        coVerify { mockGreetingWidgetBindingDao.upsertBinding(binding) }
        coVerify { mockGreetingWidgetBindingDao.getBinding(301) }
    }

    @Test
    fun `getBinding returns null for non-existent widgetId`() = runTest {
        coEvery { mockGreetingWidgetBindingDao.getBinding(999) } returns null

        val result = mockGreetingWidgetBindingDao.getBinding(999)
        assertNull(result)
    }

    @Test
    fun `deleteBinding removes the binding`() = runTest {
        coEvery { mockGreetingWidgetBindingDao.getBinding(301) } returns null

        mockGreetingWidgetBindingDao.deleteBinding(301)
        val result = mockGreetingWidgetBindingDao.getBinding(301)

        assertNull(result)
        coVerify { mockGreetingWidgetBindingDao.deleteBinding(301) }
    }
}

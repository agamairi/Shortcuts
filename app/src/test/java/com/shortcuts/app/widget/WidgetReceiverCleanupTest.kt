package com.shortcuts.app.widget

import com.shortcuts.app.data.CustomWidgetBindingDao
import com.shortcuts.app.data.WidgetBindingDao
import com.shortcuts.app.data.WidgetListBindingDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetReceiverCleanupTest {

    @Test
    fun `cleanupBindings deletes single binding for provided appWidgetId`() = runTest {
        val deletedIds = mutableListOf<Int>()
        val appWidgetIds = intArrayOf(101)

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            deletedIds.add(id)
        }

        assertEquals(listOf(101), deletedIds)
    }

    @Test
    fun `cleanupBindings deletes multiple bindings for array of appWidgetIds`() = runTest {
        val deletedIds = mutableListOf<Int>()
        val appWidgetIds = intArrayOf(101, 102, 103)

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            deletedIds.add(id)
        }

        assertEquals(listOf(101, 102, 103), deletedIds)
    }

    @Test
    fun `cleanupBindings handles empty appWidgetIds array gracefully`() = runTest {
        val deletedIds = mutableListOf<Int>()
        val appWidgetIds = intArrayOf()

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            deletedIds.add(id)
        }

        assertEquals(emptyList<Int>(), deletedIds)
    }

    @Test
    fun `cleanupBindings correctly invokes WidgetBindingDao deleteBinding`() = runTest {
        val mockDao = mockk<WidgetBindingDao>(relaxed = true)
        val appWidgetIds = intArrayOf(501, 502)

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            mockDao.deleteBinding(id)
        }

        coVerify(exactly = 1) { mockDao.deleteBinding(501) }
        coVerify(exactly = 1) { mockDao.deleteBinding(502) }
    }

    @Test
    fun `cleanupBindings correctly invokes WidgetListBindingDao deleteBinding`() = runTest {
        val mockDao = mockk<WidgetListBindingDao>(relaxed = true)
        val appWidgetIds = intArrayOf(601)

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            mockDao.deleteBinding(id)
        }

        coVerify(exactly = 1) { mockDao.deleteBinding(601) }
    }

    @Test
    fun `cleanupBindings correctly invokes CustomWidgetBindingDao deleteBinding`() = runTest {
        val mockDao = mockk<CustomWidgetBindingDao>(relaxed = true)
        val appWidgetIds = intArrayOf(701)

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            mockDao.deleteBinding(id)
        }

        coVerify(exactly = 1) { mockDao.deleteBinding(701) }
    }

    @Test
    fun `cleanupBindings correctly invokes GreetingWidgetBindingDao deleteBinding`() = runTest {
        val mockDao = mockk<com.shortcuts.app.data.GreetingWidgetBindingDao>(relaxed = true)
        val appWidgetIds = intArrayOf(801)

        WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
            mockDao.deleteBinding(id)
        }

        coVerify(exactly = 1) { mockDao.deleteBinding(801) }
    }
}

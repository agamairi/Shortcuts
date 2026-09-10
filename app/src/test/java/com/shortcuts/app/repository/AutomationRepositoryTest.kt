package com.shortcuts.app.repository

import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutomationRepositoryTest {

    private lateinit var mockDao: AutomationDao
    private lateinit var repository: AutomationRepository

    @Before
    fun setup() {
        mockDao = mockk(relaxed = true)
        every { mockDao.getAllAutomations() } returns flowOf(emptyList())
        repository = AutomationRepository(mockDao)
    }

    @Test
    fun `insert delegates to automationDao insertAutomation`() = runTest {
        val automation = Automation(id = 1, name = "Routine 1", actionsJson = "[]")

        repository.insert(automation)

        coVerify(exactly = 1) { mockDao.insertAutomation(automation) }
    }

    @Test
    fun `update delegates to automationDao updateAutomation`() = runTest {
        val automation = Automation(id = 1, name = "Routine 1 Updated", actionsJson = "[]")

        repository.update(automation)

        coVerify(exactly = 1) { mockDao.updateAutomation(automation) }
    }

    @Test
    fun `delete delegates to automationDao deleteAutomation`() = runTest {
        val automation = Automation(id = 1, name = "Routine 1", actionsJson = "[]")

        repository.delete(automation)

        coVerify(exactly = 1) { mockDao.deleteAutomation(automation) }
    }

    @Test
    fun `toggleActive flips isActive from true to false and updates DAO`() = runTest {
        val original = Automation(id = 5, name = "Active Shortcut", actionsJson = "[]", isActive = true)

        repository.toggleActive(original)

        coVerify(exactly = 1) {
            mockDao.updateAutomation(match { updated ->
                updated.id == 5 && !updated.isActive && updated.name == "Active Shortcut"
            })
        }
    }

    @Test
    fun `toggleActive flips isActive from false to true and updates DAO`() = runTest {
        val original = Automation(id = 6, name = "Inactive Shortcut", actionsJson = "[]", isActive = false)

        repository.toggleActive(original)

        coVerify(exactly = 1) {
            mockDao.updateAutomation(match { updated ->
                updated.id == 6 && updated.isActive && updated.name == "Inactive Shortcut"
            })
        }
    }

    @Test
    fun `getAutomationById delegates to automationDao getAutomationById`() = runTest {
        val expected = Automation(id = 42, name = "Found Shortcut", actionsJson = "[]")
        coEvery { mockDao.getAutomationById(42) } returns expected

        val result = repository.getAutomationById(42)

        assertEquals(expected, result)
        coVerify(exactly = 1) { mockDao.getAutomationById(42) }
    }

    @Test
    fun `allAutomations delegates to automationDao getAllAutomations`() = runTest {
        val list = listOf(
            Automation(id = 1, name = "Shortcut 1", actionsJson = "[]"),
            Automation(id = 2, name = "Shortcut 2", actionsJson = "[]")
        )
        every { mockDao.getAllAutomations() } returns flowOf(list)

        val repo = AutomationRepository(mockDao)
        val result = repo.allAutomations.first()

        assertEquals(2, result.size)
        assertEquals("Shortcut 1", result[0].name)
        assertEquals("Shortcut 2", result[1].name)
    }
}

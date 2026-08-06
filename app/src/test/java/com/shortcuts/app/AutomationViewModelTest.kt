package com.shortcuts.app

import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.AutomationViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AutomationRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.allAutomations } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState emits Success when repository returns automations`() = runTest {
        val automationsList = listOf(
            Automation(id = 1, name = "Routine 1", actionsJson = "[]", isActive = true, triggerType = "MANUAL"),
            Automation(id = 2, name = "Routine 2", actionsJson = "[]", isActive = false, triggerType = "WIDGET")
        )
        every { repository.allAutomations } returns flowOf(automationsList)

        val vm = AutomationViewModel(repository)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val currentState = vm.uiState.value
        assertTrue(currentState is UiState.Success)
        assertEquals(automationsList, (currentState as UiState.Success).data)
    }

    @Test
    fun `insert automation calls repository insert`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Test Insert", actionsJson = "[]")
        coEvery { repository.insert(automation) } returns Unit

        vm.insert(automation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.insert(automation) }
    }

    @Test
    fun `delete automation calls repository delete`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Test Delete", actionsJson = "[]")
        coEvery { repository.delete(automation) } returns Unit

        vm.delete(automation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.delete(automation) }
    }

    @Test
    fun `toggleActive calls repository toggleActive`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Test Toggle", actionsJson = "[]", isActive = true)
        coEvery { repository.toggleActive(automation) } returns Unit

        vm.toggleActive(automation)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.toggleActive(automation) }
    }

    @Test
    fun `updateAppearance calls repository update with copied automation`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Test Appearance", actionsJson = "[]", isActive = true)
        coEvery { repository.update(any()) } returns Unit

        vm.updateAppearance(automation, WidgetColorKey.PURPLE, WidgetIconKey.STAR)
        testDispatcher.scheduler.advanceUntilIdle()

        val expected = automation.copy(colorKey = WidgetColorKey.PURPLE.name, iconKey = WidgetIconKey.STAR.name)
        coVerify(exactly = 1) { repository.update(expected) }
    }

    @Test
    fun `uiState emits Error when repository throws exception`() = runTest {
        every { repository.allAutomations } returns flow { throw RuntimeException("DB Connection Failed") }

        val vm = AutomationViewModel(repository)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val currentState = vm.uiState.value
        assertTrue(currentState is UiState.Error)
        assertEquals("DB Connection Failed", (currentState as UiState.Error).message)
    }

    @Test
    fun `insert failure updates errorState`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Failed Insert", actionsJson = "[]")
        coEvery { repository.insert(automation) } throws RuntimeException("Insert Disk Full")

        vm.insert(automation)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Insert Disk Full", vm.errorState.value)

        vm.clearError()
        assertEquals(null, vm.errorState.value)
    }
}

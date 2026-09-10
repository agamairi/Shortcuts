package com.shortcuts.app.viewmodel

import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionalSaveViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AutomationRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `AutomationViewModel insert awaits repository persistence and returns success`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Test Transactional Insert", actionsJson = "[]")
        coEvery { repository.insert(automation) } returns Unit

        val result = vm.insert(automation)

        assertTrue(result.isSuccess)
        assertNull(vm.errorState.value)
        coVerify(exactly = 1) { repository.insert(automation) }
    }

    @Test
    fun `AutomationViewModel insert failure surfaces error and returns failure without clearing draft`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 1, name = "Failed Insert", actionsJson = "[{\"actionType\":\"WAIT\"}]")
        val exception = RuntimeException("Database write error")
        coEvery { repository.insert(automation) } throws exception

        val result = vm.insert(automation)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
        assertEquals("Database write error", vm.errorState.value)
        assertEquals("[{\"actionType\":\"WAIT\"}]", automation.actionsJson)
    }

    @Test
    fun `AutomationViewModel update awaits repository persistence and returns success`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 5, name = "Updated Shortcut", actionsJson = "[]")
        coEvery { repository.update(automation) } returns Unit

        val result = vm.update(automation)

        assertTrue(result.isSuccess)
        assertNull(vm.errorState.value)
        coVerify(exactly = 1) { repository.update(automation) }
    }

    @Test
    fun `AutomationViewModel update failure surfaces error and returns failure`() = runTest {
        val vm = AutomationViewModel(repository)
        val automation = Automation(id = 5, name = "Failed Update", actionsJson = "[]")
        val exception = RuntimeException("Update failed due to constraint")
        coEvery { repository.update(automation) } throws exception

        val result = vm.update(automation)

        assertTrue(result.isFailure)
        assertEquals("Update failed due to constraint", vm.errorState.value)
    }

    @Test
    fun `RecorderViewModel saveRecordedAutomation awaits persistence and manages saving state`() = runTest {
        val recorderVm = RecorderViewModel(repository)
        val automation = Automation(id = 0, name = "Recorded Shortcut", actionsJson = "[{\"actionType\":\"UI_AUTOMATION\"}]")
        coEvery { repository.insert(automation) } returns Unit

        val result = recorderVm.saveRecordedAutomation(automation)

        assertTrue(result.isSuccess)
        assertNull(recorderVm.errorState.value)
        assertEquals(false, recorderVm.isSaving.value)
        coVerify(exactly = 1) { repository.insert(automation) }
    }

    @Test
    fun `RecorderViewModel saveRecordedAutomation failure surfaces error and preserves draft state`() = runTest {
        val recorderVm = RecorderViewModel(repository)
        val draftActionsJson = "[{\"actionType\":\"UI_AUTOMATION\",\"targetText\":\"Submit\"}]"
        val automation = Automation(id = 0, name = "Failed Recording", actionsJson = draftActionsJson)
        val exception = RuntimeException("Disk full")
        coEvery { repository.insert(automation) } throws exception

        val result = recorderVm.saveRecordedAutomation(automation)

        assertTrue(result.isFailure)
        assertEquals(exception, result.exceptionOrNull())
        assertEquals("Disk full", recorderVm.errorState.value)
        assertEquals(false, recorderVm.isSaving.value)
        // Verify the original draft object was not mutated or lost
        assertEquals(draftActionsJson, automation.actionsJson)
    }
}

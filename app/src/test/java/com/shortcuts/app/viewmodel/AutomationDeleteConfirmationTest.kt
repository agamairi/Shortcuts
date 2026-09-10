package com.shortcuts.app.viewmodel

import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the delete-confirmation flow added in R-WIDGET-6.
 *
 * These tests run on the JVM against a stubbed android.jar, so they assert only on
 * ViewModel state (StateFlow values) and repository interactions — never on Android
 * framework objects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationDeleteConfirmationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AutomationRepository

    private val shortcut = Automation(
        id = 42,
        name = "Morning Routine",
        actionsJson = "[]",
        isActive = true,
        triggerType = "MANUAL"
    )

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

    // -------------------------------------------------------------------------
    // Test 1: zero bound widgets → delete immediately, no dialog
    // -------------------------------------------------------------------------

    @Test
    fun `requestDelete with zero bound widgets deletes immediately and shows no dialog`() = runTest {
        coEvery { repository.countWidgetsReferencingAutomation(shortcut.id) } returns 0
        coEvery { repository.delete(shortcut) } returns Unit

        val vm = AutomationViewModel(repository)
        vm.requestDelete(shortcut)
        testDispatcher.scheduler.advanceUntilIdle()

        // Dialog must NOT be shown
        assertNull("pendingDeletion should be null when no widgets are affected",
            vm.pendingDeletion.value)

        // Delete must have been called exactly once
        coVerify(exactly = 1) { repository.delete(shortcut) }
    }

    // -------------------------------------------------------------------------
    // Test 2: 2 bound widgets → NO immediate delete, dialog shown with count 2
    // -------------------------------------------------------------------------

    @Test
    fun `requestDelete with two bound widgets does not delete and exposes count 2`() = runTest {
        coEvery { repository.countWidgetsReferencingAutomation(shortcut.id) } returns 2

        val vm = AutomationViewModel(repository)
        vm.requestDelete(shortcut)
        testDispatcher.scheduler.advanceUntilIdle()

        // Dialog must be shown
        val pending = vm.pendingDeletion.value
        assertNotNull("pendingDeletion should be non-null when widgets are affected", pending)
        assertEquals(shortcut, pending!!.automation)
        assertEquals(2, pending.affectedWidgetCount)

        // Delete must NOT have been called
        coVerify(exactly = 0) { repository.delete(any()) }
    }

    @Test
    fun `dashboard delete request checks widget bindings before exposing confirmation`() = runTest {
        coEvery { repository.countWidgetsReferencingAutomation(shortcut.id) } returns 1

        val vm = AutomationViewModel(repository)
        vm.requestDelete(shortcut)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.countWidgetsReferencingAutomation(shortcut.id) }
        assertEquals(1, vm.pendingDeletion.value?.affectedWidgetCount)
        coVerify(exactly = 0) { repository.delete(shortcut) }
    }

    // -------------------------------------------------------------------------
    // Test 3: cancel clears pending state, deletes nothing
    // -------------------------------------------------------------------------

    @Test
    fun `cancelDelete clears pendingDeletion and does not delete`() = runTest {
        coEvery { repository.countWidgetsReferencingAutomation(shortcut.id) } returns 2

        val vm = AutomationViewModel(repository)
        vm.requestDelete(shortcut)
        testDispatcher.scheduler.advanceUntilIdle()

        // Sanity-check: dialog is showing
        assertNotNull(vm.pendingDeletion.value)

        vm.cancelDelete()

        // State must be cleared
        assertNull("pendingDeletion should be null after cancelDelete",
            vm.pendingDeletion.value)

        // Delete must never have been called
        coVerify(exactly = 0) { repository.delete(any()) }
    }

    // -------------------------------------------------------------------------
    // Test 4: confirm performs the delete and clears pending state
    // -------------------------------------------------------------------------

    @Test
    fun `confirmDelete performs delete and clears pendingDeletion`() = runTest {
        coEvery { repository.countWidgetsReferencingAutomation(shortcut.id) } returns 2
        coEvery { repository.delete(shortcut) } returns Unit

        val vm = AutomationViewModel(repository)
        vm.requestDelete(shortcut)
        testDispatcher.scheduler.advanceUntilIdle()

        // Dialog is showing
        assertNotNull(vm.pendingDeletion.value)

        vm.confirmDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        // State must be cleared
        assertNull("pendingDeletion should be null after confirmDelete",
            vm.pendingDeletion.value)

        // Delete must have been called exactly once for the original shortcut
        coVerify(exactly = 1) { repository.delete(shortcut) }
    }

    // -------------------------------------------------------------------------
    // Extra: legacy delete() still works unchanged (regression guard)
    // -------------------------------------------------------------------------

    @Test
    fun `direct delete() call still deletes immediately without touching pendingDeletion`() = runTest {
        coEvery { repository.delete(shortcut) } returns Unit

        val vm = AutomationViewModel(repository)
        vm.delete(shortcut)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull("pendingDeletion should remain null when delete() is called directly",
            vm.pendingDeletion.value)
        coVerify(exactly = 1) { repository.delete(shortcut) }
    }
}

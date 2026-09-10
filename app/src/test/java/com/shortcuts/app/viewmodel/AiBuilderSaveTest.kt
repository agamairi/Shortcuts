package com.shortcuts.app.viewmodel

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.planner.DraftShortcut
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.ui.state.UiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.delay

@OptIn(ExperimentalCoroutinesApi::class)
class AiBuilderSaveTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `successful save awaits persistence and updates state`() = runTest {
        val repository = mockk<AutomationRepository>()
        coEvery { repository.insert(any()) } coAnswers {
            delay(100) // Simulate DB delay
            1L
        }
        val viewModel = AiBuilderViewModel(repository = repository)
        
        // Setup draft
        val action = Action(actionType = ActionType.SYSTEM_TOGGLE)
        viewModel.addStep(action)
        viewModel.updateShortcutName("Test Shortcut")
        
        viewModel.saveGeneratedAutomation()
        
        // Before delay finishes, it should be in saving state
        val stateDuring = viewModel.uiState.value as UiState.Success
        assertTrue(stateDuring.data.isSaving)
        assertFalse(stateDuring.data.isSaved)
        
        advanceUntilIdle()
        
        // After delay finishes
        val stateAfter = viewModel.uiState.value as UiState.Success
        assertFalse(stateAfter.data.isSaving)
        assertTrue(stateAfter.data.isSaved)
        coVerify(exactly = 1) { repository.insert(any()) }
    }

    @Test
    fun `failed save clears saving state, surfaces error and preserves draft`() = runTest {
        val repository = mockk<AutomationRepository>()
        coEvery { repository.insert(any()) } throws RuntimeException("DB Error")
        val viewModel = AiBuilderViewModel(repository = repository)
        
        val action = Action(actionType = ActionType.SYSTEM_TOGGLE)
        viewModel.addStep(action)
        viewModel.updateShortcutName("Test Shortcut")
        
        val originalDraft = (viewModel.uiState.value as UiState.Success).data.draft
        
        viewModel.saveGeneratedAutomation()
        
        advanceUntilIdle()
        
        // It should transition to Error state, but when we inspect the underlying data (via clearError or just from the ViewModel's private state, wait we can't see currentData directly if it's error)
        // Wait, uiState will be UiState.Error
        val stateAfter = viewModel.uiState.value
        assertTrue(stateAfter is UiState.Error)
        assertEquals("Failed to save automation: DB Error", (stateAfter as UiState.Error).message)
        
        // Clear error to inspect data
        viewModel.clearError()
        val restoredState = viewModel.uiState.value as UiState.Success
        assertFalse(restoredState.data.isSaving)
        assertFalse(restoredState.data.isSaved)
        // Draft should be intact
        assertEquals(originalDraft, restoredState.data.draft)
    }
}

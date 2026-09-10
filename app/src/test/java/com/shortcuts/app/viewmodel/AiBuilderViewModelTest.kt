package com.shortcuts.app.viewmodel

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.planner.DraftShortcut
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.ui.state.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.shortcuts.app.service.OnDeviceInferenceService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
class AiBuilderViewModelTest {

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
    fun `reset clears state and resets to defaults`() {
        val viewModel = AiBuilderViewModel()
        
        viewModel.updatePrompt("test prompt")
        val stateBefore = viewModel.uiState.value as UiState.Success
        assertEquals("test prompt", stateBefore.data.prompt)
        
        viewModel.reset()
        
        val stateAfter = viewModel.uiState.value as UiState.Success
        assertEquals("", stateAfter.data.prompt)
        assertEquals("", viewModel.prompt.value)
        assertEquals(null, stateAfter.data.draft)
    }

    @Test
    fun `second turn appends steps rather than replacing`() = runTest {
        val mockInference = mockk<OnDeviceInferenceService>()
        every { mockInference.appLabelForPackage(any()) } returns "App"
        
        coEvery { mockInference.generateAutomationJson(any()) } answers {
            val prompt = it.invocation.args[0] as String
            if (prompt.contains("second")) {
                "{\"actions\": [{\"action_type\": \"SYSTEM_TOGGLE\", \"target\": \"bluetooth\", \"state\": \"on\"}]}"
            } else {
                "{\"actions\": [{\"action_type\": \"SYSTEM_TOGGLE\", \"target\": \"wifi\", \"state\": \"off\"}]}"
            }
        }
        
        val viewModel = AiBuilderViewModel(inferenceService = mockInference)
        
        viewModel.performInference("first")
        advanceUntilIdle()
        
        var draft = (viewModel.uiState.value as UiState.Success).data.draft
        assertEquals(1, draft?.steps?.size)
        assertEquals("first", draft?.steps?.get(0)?.sourceText)
        
        viewModel.performInference("second")
        advanceUntilIdle()
        
        draft = (viewModel.uiState.value as UiState.Success).data.draft
        assertEquals(2, draft?.steps?.size)
        assertEquals("first", draft?.steps?.get(0)?.sourceText)
        assertEquals("second", draft?.steps?.get(1)?.sourceText)
    }
}

package com.shortcuts.app.ui

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiBuilderEditingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `add update and delete mutate the reviewed draft`() = runTest(dispatcher) {
        val viewModel = readyViewModel()

        viewModel.addStep(Action(ActionType.HTTP_REQUEST, url = "https://example.com", method = "POST"))
        viewModel.updateStep(0, Action(ActionType.APP_INTENT, packageName = "com.spotify.music"))

        var steps = draftSteps(viewModel)
        assertEquals(2, steps.size)
        assertEquals("Added manually", steps[1].sourceText)
        assertEquals(
            "com.spotify.music",
            (steps[0] as DraftStep.Resolved).action.packageName
        )

        viewModel.deleteStep(1)
        steps = draftSteps(viewModel)
        assertEquals(1, steps.size)
        assertEquals(ActionType.APP_INTENT, (steps.single() as DraftStep.Resolved).action.actionType)
    }

    @Test
    fun `reorder changes valid positions and ignores list boundaries`() = runTest(dispatcher) {
        val viewModel = readyViewModel()
        viewModel.addStep(Action(ActionType.SYSTEM_TOGGLE, target = "wifi", state = "on"))
        viewModel.addStep(Action(ActionType.HTTP_REQUEST, url = "https://example.com"))

        viewModel.moveStep(0, -1)
        viewModel.moveStep(2, 3)
        assertEquals(listOf("com.example.first", "wifi", "https://example.com"), actionValues(viewModel))

        viewModel.moveStep(2, 0)
        assertEquals(listOf("https://example.com", "com.example.first", "wifi"), actionValues(viewModel))
    }

    private suspend fun readyViewModel(): AiBuilderViewModel {
        val inference = mockk<OnDeviceInferenceService>()
        coEvery { inference.generateAutomationJson("Open First") } returns """
            { "name": "First", "actions": [
                { "action_type": "APP_INTENT", "package_name": "com.example.first" }
            ] }
        """.trimIndent()
        return AiBuilderViewModel(
            inferenceService = inference,
            downloadStateFlow = MutableStateFlow<DownloadState>(DownloadState.Idle),
            startDownloadAction = null
        ).also { it.performInference("Open First") }
    }

    private fun draftSteps(viewModel: AiBuilderViewModel): List<DraftStep> {
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        return (state as UiState.Success).data.draft!!.steps
    }

    private fun actionValues(viewModel: AiBuilderViewModel): List<String> = draftSteps(viewModel).map {
        val action = (it as DraftStep.Resolved).action
        action.packageName ?: action.target ?: action.url.orEmpty()
    }
}

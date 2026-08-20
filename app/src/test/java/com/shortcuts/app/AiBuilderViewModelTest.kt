package com.shortcuts.app

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiBuilderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: AutomationRepository
    private lateinit var inferenceService: OnDeviceInferenceService
    private lateinit var downloadStateFlow: MutableStateFlow<DownloadState>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        inferenceService = mockk(relaxed = true)
        downloadStateFlow = MutableStateFlow(DownloadState.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `partial tier one batch does not mislabel which clause an action came from`() = runTest(testDispatcher) {
        // Tier 1 answers the WHOLE prompt, so a single returned call carries no clause label.
        // Pairing it positionally with clause[0] would attribute "open Spotify" to the
        // "play Simon and Garfunkel" step in the review editor. On partial coverage the
        // batch must be discarded and each clause generated individually.
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow, null)
        val fullPrompt = "turn on wifi and open Spotify"
        val oneCall = "<start_function_call>call:open_app{package_name:<escape>com.spotify.music<escape>}<end_function_call>"
        val wifiCall = "<start_function_call>call:toggle_system_setting{setting:<escape>wifi<escape>,state:<escape>on<escape>}<end_function_call>"

        coEvery { inferenceService.generateAutomationJson(fullPrompt) } returns oneCall
        coEvery { inferenceService.generateAutomationJson("turn on wifi") } returns wifiCall
        coEvery { inferenceService.generateAutomationJson("open Spotify") } returns oneCall

        viewModel.performInference(fullPrompt)

        val draft = (viewModel.uiState.value as UiState.Success).data.draft
        assertNotNull(draft)
        assertEquals(2, draft!!.steps.size)
        // Each clause must be paired with the action actually generated FOR that clause.
        val first = draft.steps[0] as com.shortcuts.app.planner.DraftStep.Resolved
        val second = draft.steps[1] as com.shortcuts.app.planner.DraftStep.Resolved
        assertEquals("turn on wifi", first.sourceText)
        assertEquals(ActionType.SYSTEM_TOGGLE, first.action.actionType)
        assertEquals("open Spotify", second.sourceText)
        assertEquals(ActionType.APP_INTENT, second.action.actionType)
    }

    @Test
    fun `every clause yields exactly one draft step even when the model fails`() = runTest(testDispatcher) {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow, null)
        val fullPrompt = "turn on wifi and open Spotify"
        val wifiCall = "<start_function_call>call:toggle_system_setting{setting:<escape>wifi<escape>,state:<escape>on<escape>}<end_function_call>"

        coEvery { inferenceService.generateAutomationJson(fullPrompt) } returns ""
        coEvery { inferenceService.generateAutomationJson("turn on wifi") } returns wifiCall
        coEvery { inferenceService.generateAutomationJson("open Spotify") } returns ""

        viewModel.performInference(fullPrompt)

        val draft = (viewModel.uiState.value as UiState.Success).data.draft
        assertNotNull(draft)
        // 2 clauses in, 2 steps out — the failed one is surfaced, never dropped.
        assertEquals(2, draft!!.steps.size)
        assertTrue(draft.steps[1] is com.shortcuts.app.planner.DraftStep.Unresolved)
    }

    @Test
    fun `updatePrompt updates prompt and uiState`() = runTest {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Turn on WiFi")

        assertEquals("Turn on WiFi", viewModel.prompt.value)
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals("Turn on WiFi", (state as UiState.Success).data.prompt)
    }

    @Test
    fun `downloadModelAndGenerate with empty prompt produces Error`() = runTest {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("   ")
        viewModel.downloadModelAndGenerate()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("Prompt cannot be empty", (state as UiState.Error).message)
    }

    @Test
    fun `downloadModelAndGenerate handles downloading state progress`() = runTest {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Open Spotify")
        viewModel.downloadModelAndGenerate()

        downloadStateFlow.value = DownloadState.Downloading(45)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals(45, (state as UiState.Success).data.downloadProgress)
    }

    @Test
    fun `downloadModelAndGenerate handles download failure`() = runTest {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Open Spotify")
        viewModel.downloadModelAndGenerate()

        downloadStateFlow.value = DownloadState.Failed("Network Connection Timeout")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertTrue((state as UiState.Error).message.contains("Network Connection Timeout"))
    }

    @Test
    fun `downloadModelAndGenerate completed download performs inference successfully`() = runTest {
        val jsonOutput = """
            {
              "automation_name": "Spotify Routine",
              "actions": [
                { "action_type": "APP_INTENT", "package_name": "com.spotify.music" }
              ]
            }
        """.trimIndent()

        coEvery { inferenceService.generateAutomationJson("Open Spotify") } returns jsonOutput

        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Open Spotify")
        viewModel.downloadModelAndGenerate()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertNotNull(data.generatedAutomation)
        assertEquals("Spotify Routine", data.generatedAutomation?.name)
    }

    @Test
    fun `downloadModelAndGenerate with null or empty inference output produces Error`() = runTest {
        coEvery { inferenceService.generateAutomationJson("Unknown prompt") } returns null

        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Unknown prompt")
        viewModel.downloadModelAndGenerate()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("AI model inference returned no valid output", (state as UiState.Error).message)
    }

    @Test
    fun `saveGeneratedAutomation inserts to repository and sets isSaved`() = runTest {
        val jsonOutput = """
            {
              "automation_name": "Toggle WiFi",
              "actions": [
                { "action_type": "SYSTEM_TOGGLE", "target": "WIFI", "state": "ON" }
              ]
            }
        """.trimIndent()
        coEvery { inferenceService.generateAutomationJson("Toggle WiFi") } returns jsonOutput

        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Toggle WiFi")
        
        viewModel.downloadModelAndGenerate()
        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveGeneratedAutomation()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.insert(any()) }
        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success).data.isSaved)
    }

    @Test
    fun `saveGeneratedAutomation persists the appearance selected in review`() = runTest(testDispatcher) {
        val saved = mutableListOf<Automation>()
        val recordingRepository = object : AutomationRepository(mockk(relaxed = true)) {
            override suspend fun insert(automation: Automation) {
                saved += automation
            }
        }
        val viewModel = AiBuilderViewModel(recordingRepository, inferenceService, downloadStateFlow, null)
        val jsonOutput = """
            {
              "automation_name": "Toggle WiFi",
              "actions": [
                { "action_type": "SYSTEM_TOGGLE", "target": "WIFI", "state": "ON" }
              ]
            }
        """.trimIndent()
        coEvery { inferenceService.generateAutomationJson("Toggle WiFi") } returns jsonOutput

        viewModel.performInference("Toggle WiFi")
        viewModel.updateAppearance(WidgetColorKey.NAVY, WidgetIconKey.COFFEE)
        viewModel.saveGeneratedAutomation()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, saved.size)
        assertEquals(WidgetColorKey.NAVY.name, saved.single().colorKey)
        assertEquals(WidgetIconKey.COFFEE.name, saved.single().iconKey)
    }
}

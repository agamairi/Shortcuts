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
    fun `saveGeneratedAutomation inserts to repository and sets isSaved`() = runTest {
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
}

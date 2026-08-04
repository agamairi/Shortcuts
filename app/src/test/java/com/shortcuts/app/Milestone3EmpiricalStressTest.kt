package com.shortcuts.app

import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import com.shortcuts.app.viewmodel.AutomationViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Milestone3EmpiricalStressTest {

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
        every { repository.allAutomations } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `verify prompt whitespace triggers Error state`() = runTest {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("    \n\t  ")
        viewModel.downloadModelAndGenerate()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("Prompt cannot be empty", (state as UiState.Error).message)
    }

    @Test
    fun `verify invalid JSON output from inference service results in Error state`() = runTest {
        coEvery { inferenceService.generateAutomationJson(any()) } returns "INVALID_JSON_CORRUPT{{"

        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Open Spotify")
        viewModel.downloadModelAndGenerate()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("Failed to parse automation JSON from AI output", (state as UiState.Error).message)
    }

    @Test
    fun `verify repository exception in saveGeneratedAutomation sets Error state`() = runTest {
        coEvery { repository.insert(any()) } throws RuntimeException("SQLite Full Exception")
        coEvery { inferenceService.generateAutomationJson("Open Spotify") } returns """
            {
              "automation_name": "Spotify Routine",
              "actions": [
                { "action_type": "APP_INTENT", "package_name": "com.spotify.music" }
              ]
            }
        """.trimIndent()

        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        viewModel.updatePrompt("Open Spotify")
        viewModel.downloadModelAndGenerate()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveGeneratedAutomation()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertTrue((state as UiState.Error).message.contains("SQLite Full Exception"))
    }

    @Test
    fun `verify AutomationViewModel error flow emissions`() = runTest {
        every { repository.allAutomations } returns flow { throw IllegalStateException("Database locked") }

        val vm = AutomationViewModel(repository)
        backgroundScope.launch { vm.uiState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val currentState = vm.uiState.value
        assertTrue(currentState is UiState.Error)
        assertEquals("Database locked", (currentState as UiState.Error).message)
    }

    @Test
    fun `verify errorState lifecycle in AutomationViewModel`() = runTest {
        coEvery { repository.delete(any()) } throws RuntimeException("Delete constraint failed")

        val vm = AutomationViewModel(repository)
        val testItem = Automation(id = 99, name = "Test", actionsJson = "[]")

        vm.delete(testItem)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Delete constraint failed", vm.errorState.value)

        vm.clearError()
        assertNull(vm.errorState.value)
    }

    @Test
    fun `verify stress parsing and creation of 100 automations`() = runTest {
        val viewModel = AiBuilderViewModel(repository, inferenceService, downloadStateFlow)
        val automations = (0 until 100).map { i ->
            val json = """
                {
                  "automation_name": "Routine $i",
                  "actions": [
                    { "action_type": "SYSTEM_TOGGLE", "target": "WIFI", "state": "ON" }
                  ]
                }
            """.trimIndent()
            viewModel.parseAutomationJson(json, "Test $i")
        }
        assertEquals(100, automations.filterNotNull().size)
        for (i in 0 until 100) {
            val auto = automations[i]
            assertNotNull(auto)
            assertEquals("Routine $i", auto!!.name)
        }
    }
}

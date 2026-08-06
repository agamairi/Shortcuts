package com.shortcuts.app.viewmodel

import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.CustomWidgetTemplateDao
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
class CustomWidgetViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: AutomationRepository
    private lateinit var mockTemplateDao: CustomWidgetTemplateDao
    private lateinit var mockInferenceService: OnDeviceInferenceService
    private lateinit var downloadStateFlow: MutableStateFlow<DownloadState>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mockk(relaxed = true)
        mockTemplateDao = mockk(relaxed = true)
        mockInferenceService = mockk(relaxed = true)
        downloadStateFlow = MutableStateFlow(DownloadState.Idle)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `manual save validation produces error when fields are missing`() = runTest {
        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)
        
        viewModel.saveTemplate()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("All fields must be selected before saving", (state as UiState.Error).message)
    }

    @Test
    fun `manual save succeeds when all required fields are set`() = runTest {
        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)

        viewModel.updateLabel("My Custom Tile")
        viewModel.selectColor(WidgetColorKey.GREEN)
        viewModel.selectIcon(WidgetIconKey.BOLT)
        viewModel.selectAutomation(10)

        viewModel.saveTemplate()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockTemplateDao.insert(any()) }

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success).data.isSaved)
    }

    @Test
    fun `generateWithAi with empty prompt produces Error`() = runTest {
        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)
        viewModel.updateAiPrompt("   ")
        viewModel.generateWithAi()

        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("Prompt cannot be empty", (state as UiState.Error).message)
    }

    @Test
    fun `generateWithAi handles downloading state progress`() = runTest {
        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)
        viewModel.updateAiPrompt("Blue WiFi Button")
        viewModel.generateWithAi()

        downloadStateFlow.value = DownloadState.Downloading(60)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        assertEquals(60, (state as UiState.Success).data.downloadProgress)
    }

    @Test
    fun `generateWithAi handles download failure`() = runTest {
        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)
        viewModel.updateAiPrompt("Blue WiFi Button")
        viewModel.generateWithAi()

        downloadStateFlow.value = DownloadState.Failed("Connection dropped")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertTrue((state as UiState.Error).message.contains("Connection dropped"))
    }

    @Test
    fun `AI generation happy path populates fields and matches existing automation after download completes`() = runTest {
        val jsonOutput = """
            {
              "label": "Quick WiFi Toggle",
              "color": "blue",
              "icon": "wifi",
              "automation_name": "Turn On WiFi"
            }
        """.trimIndent()

        val autoList = listOf(
            Automation(id = 42, name = "Turn On WiFi", actionsJson = "[]")
        )
        coEvery { mockRepository.allAutomations } returns flowOf(autoList)
        coEvery { mockInferenceService.generateWidgetSpecJson("WiFi toggle button") } returns jsonOutput

        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)

        viewModel.updateAiPrompt("WiFi toggle button")
        viewModel.generateWithAi()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data

        assertEquals("Quick WiFi Toggle", data.label)
        assertEquals(WidgetColorKey.BLUE, data.selectedColorKey)
        assertEquals(WidgetIconKey.WIFI, data.selectedIconKey)
        assertEquals(42, data.selectedAutomationId)
        assertNull(data.aiNoMatchMessage)
        assertNull(data.downloadProgress)
    }

    @Test
    fun `AI generation with unmatched automation name sets aiNoMatchMessage without crashing`() = runTest {
        val jsonOutput = """
            {
              "label": "Smart Home Hub",
              "color": "purple",
              "icon": "home",
              "automation_name": "NonExistentShortcut"
            }
        """.trimIndent()

        val autoList = listOf(
            Automation(id = 1, name = "WiFi Switch", actionsJson = "[]")
        )
        coEvery { mockRepository.allAutomations } returns flowOf(autoList)
        coEvery { mockInferenceService.generateWidgetSpecJson("Smart home shortcut") } returns jsonOutput

        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)

        viewModel.updateAiPrompt("Smart home shortcut")
        viewModel.generateWithAi()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data

        assertEquals("Smart Home Hub", data.label)
        assertEquals(WidgetColorKey.PURPLE, data.selectedColorKey)
        assertEquals(WidgetIconKey.HOME, data.selectedIconKey)
        assertNull(data.selectedAutomationId)
        assertNotNull(data.aiNoMatchMessage)
        assertTrue(data.aiNoMatchMessage!!.contains("NonExistentShortcut"))
    }

    @Test
    fun `AI generation with malformed JSON produces UiState Error`() = runTest {
        val malformedJson = "This is not a JSON object"
        coEvery { mockInferenceService.generateWidgetSpecJson("Broken prompt") } returns malformedJson

        val viewModel = CustomWidgetViewModel(mockRepository, mockTemplateDao, mockInferenceService, downloadStateFlow)

        viewModel.updateAiPrompt("Broken prompt")
        viewModel.generateWithAi()

        downloadStateFlow.value = DownloadState.Completed(mockk())
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is UiState.Error)
        assertEquals("Failed to parse widget spec JSON from AI output", (state as UiState.Error).message)
    }
}

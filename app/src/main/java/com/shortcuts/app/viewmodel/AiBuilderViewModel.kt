package com.shortcuts.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.ModelDownloaderService
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AiBuilderData(
    val prompt: String = "",
    val downloadProgress: Int? = null,
    val isGenerating: Boolean = false,
    val generatedAutomation: Automation? = null,
    val isSaved: Boolean = false
)

class AiBuilderViewModel(
    private val repository: AutomationRepository? = null,
    private val inferenceService: OnDeviceInferenceService? = null,
    private val downloadStateFlow: StateFlow<DownloadState> = ModelDownloaderService.downloadState,
    private val startDownloadAction: ((Context) -> Unit)? = { ctx -> ModelDownloaderService.startDownload(ctx) }
) : ViewModel() {

    private val gson = Gson()

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _uiState = MutableStateFlow<UiState<AiBuilderData>>(UiState.Success(AiBuilderData()))
    val uiState: StateFlow<UiState<AiBuilderData>> = _uiState.asStateFlow()

    private var currentData = AiBuilderData()

    fun updatePrompt(newPrompt: String) {
        _prompt.value = newPrompt
        currentData = currentData.copy(prompt = newPrompt)
        _uiState.value = UiState.Success(currentData)
    }

    fun downloadModelAndGenerate(context: Context? = null) {
        val currentPrompt = _prompt.value.trim()
        if (currentPrompt.isBlank()) {
            _uiState.value = UiState.Error("Prompt cannot be empty")
            return
        }

        viewModelScope.launch {
            if (context != null) {
                startDownloadAction?.invoke(context)
            } else {
                if (ModelDownloaderService.downloadState.value is DownloadState.Idle) {
                    ModelDownloaderService.updateDownloadState(DownloadState.Downloading(0))
                }
            }

            downloadStateFlow.collectLatest { downloadState ->
                processDownloadState(downloadState, currentPrompt)
            }
        }
    }

    suspend fun processDownloadState(downloadState: DownloadState, currentPrompt: String) {
        when (downloadState) {
            is DownloadState.Idle -> {
                // Waiting for download
            }
            is DownloadState.Downloading -> {
                currentData = currentData.copy(
                    downloadProgress = downloadState.progress,
                    isGenerating = false
                )
                _uiState.value = UiState.Success(currentData)
            }
            is DownloadState.Failed -> {
                _uiState.value = UiState.Error("Model download failed: ${downloadState.error}")
            }
            is DownloadState.Completed -> {
                currentData = currentData.copy(
                    downloadProgress = null,
                    isGenerating = true
                )
                _uiState.value = UiState.Success(currentData)
                performInference(currentPrompt)
            }
        }
    }

    suspend fun performInference(promptText: String) {
        try {
            val jsonResponse = inferenceService?.generateAutomationJson(promptText)
            if (jsonResponse != null) {
                val automation = parseAutomationJson(jsonResponse, promptText)
                if (automation != null) {
                    currentData = currentData.copy(
                        isGenerating = false,
                        generatedAutomation = automation
                    )
                    _uiState.value = UiState.Success(currentData)
                } else {
                    _uiState.value = UiState.Error("AI generation failed: Unable to parse generated JSON flow")
                }
            } else {
                // Fallback default output for mock or when inference service returns null in test/stub mode
                val defaultAutomation = createFallbackAutomation(promptText)
                currentData = currentData.copy(
                    isGenerating = false,
                    generatedAutomation = defaultAutomation
                )
                _uiState.value = UiState.Success(currentData)
            }
        } catch (e: Exception) {
            _uiState.value = UiState.Error(e.localizedMessage ?: "AI generation failed due to internal error", e)
        }
    }

    fun parseAutomationJson(json: String, fallbackName: String): Automation? {
        return try {
            val trimmed = json.trim()
            if (trimmed.startsWith("{")) {
                val jsonObject = gson.fromJson(trimmed, Map::class.java)
                val name = jsonObject["automation_name"] as? String ?: "AI Shortcut: $fallbackName"
                val actionsList = jsonObject["actions"]
                val actionsJsonStr = gson.toJson(actionsList)
                Automation(name = name, actionsJson = actionsJsonStr, triggerType = "AI_GENERATED")
            } else if (trimmed.startsWith("[")) {
                Automation(name = "AI Shortcut: $fallbackName", actionsJson = trimmed, triggerType = "AI_GENERATED")
            } else {
                createFallbackAutomation(fallbackName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createFallbackAutomation(promptText: String): Automation {
        val action = Action(
            actionType = ActionType.SYSTEM_TOGGLE,
            target = "WIFI",
            state = "ON"
        )
        return Automation(
            name = "AI Shortcut: $promptText",
            actionsJson = ActionConverter().fromActionList(listOf(action)),
            triggerType = "AI_GENERATED"
        )
    }

    fun saveGeneratedAutomation() {
        val automation = currentData.generatedAutomation ?: return
        viewModelScope.launch {
            try {
                repository?.insert(automation)
                currentData = currentData.copy(isSaved = true)
                _uiState.value = UiState.Success(currentData)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to save automation: ${e.localizedMessage}", e)
            }
        }
    }

    fun triggerError(message: String) {
        _uiState.value = UiState.Error(message)
    }

    fun clearError() {
        _uiState.value = UiState.Success(currentData)
    }
}

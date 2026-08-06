package com.shortcuts.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.CustomWidgetTemplateDao
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.ModelDownloaderService
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class CustomWidgetBuilderData(
    val label: String = "",
    val selectedColorKey: WidgetColorKey? = null,
    val selectedIconKey: WidgetIconKey? = null,
    val selectedAutomationId: Int? = null,
    val aiPrompt: String = "",
    val isGeneratingAi: Boolean = false,
    val downloadProgress: Int? = null,
    val aiNoMatchMessage: String? = null,
    val isSaved: Boolean = false
)

class CustomWidgetViewModel(
    private val repository: AutomationRepository? = null,
    private val templateDao: CustomWidgetTemplateDao? = null,
    private val inferenceService: OnDeviceInferenceService? = null,
    private val downloadStateFlow: StateFlow<DownloadState> = ModelDownloaderService.downloadState,
    private val startDownloadAction: ((Context) -> Unit)? = { ctx -> ModelDownloaderService.startDownload(ctx) }
) : ViewModel() {

    private val gson = Gson()

    private val _uiState = MutableStateFlow<UiState<CustomWidgetBuilderData>>(UiState.Success(CustomWidgetBuilderData()))
    val uiState: StateFlow<UiState<CustomWidgetBuilderData>> = _uiState.asStateFlow()

    private var currentData = CustomWidgetBuilderData()

    fun updateLabel(newLabel: String) {
        currentData = currentData.copy(label = newLabel)
        _uiState.value = UiState.Success(currentData)
    }

    fun selectColor(key: WidgetColorKey) {
        currentData = currentData.copy(selectedColorKey = key)
        _uiState.value = UiState.Success(currentData)
    }

    fun selectIcon(key: WidgetIconKey) {
        currentData = currentData.copy(selectedIconKey = key)
        _uiState.value = UiState.Success(currentData)
    }

    fun selectAutomation(id: Int) {
        currentData = currentData.copy(selectedAutomationId = id, aiNoMatchMessage = null)
        _uiState.value = UiState.Success(currentData)
    }

    fun updateAiPrompt(newPrompt: String) {
        currentData = currentData.copy(aiPrompt = newPrompt)
        _uiState.value = UiState.Success(currentData)
    }

    fun generateWithAi(context: Context? = null) {
        val promptText = currentData.aiPrompt.trim()
        if (promptText.isBlank()) {
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
                processDownloadState(downloadState, promptText)
            }
        }
    }

    suspend fun processDownloadState(downloadState: DownloadState, promptText: String) {
        when (downloadState) {
            is DownloadState.Idle -> {
                // Waiting for download
            }
            is DownloadState.Downloading -> {
                currentData = currentData.copy(
                    downloadProgress = downloadState.progress,
                    isGeneratingAi = false
                )
                _uiState.value = UiState.Success(currentData)
            }
            is DownloadState.Failed -> {
                _uiState.value = UiState.Error("Model download failed: ${downloadState.error}")
            }
            is DownloadState.Completed -> {
                currentData = currentData.copy(
                    downloadProgress = null,
                    isGeneratingAi = true
                )
                _uiState.value = UiState.Success(currentData)
                performInference(promptText)
            }
        }
    }

    suspend fun performInference(promptText: String) {
        try {
            val rawResponse = inferenceService?.generateWidgetSpecJson(promptText)
            if (rawResponse.isNullOrBlank()) {
                currentData = currentData.copy(isGeneratingAi = false)
                _uiState.value = UiState.Error("AI model inference returned no valid output")
                return
            }

            var cleaned = rawResponse.trim()
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substringAfter("\n").substringBeforeLast("```").trim()
            }

            if (!cleaned.startsWith("{")) {
                currentData = currentData.copy(isGeneratingAi = false)
                _uiState.value = UiState.Error("Failed to parse widget spec JSON from AI output")
                return
            }

            val jsonMap = gson.fromJson(cleaned, Map::class.java)
            if (jsonMap == null) {
                currentData = currentData.copy(isGeneratingAi = false)
                _uiState.value = UiState.Error("Failed to parse widget spec JSON from AI output")
                return
            }

            val label = (jsonMap["label"] as? String) ?: promptText
            val colorStr = jsonMap["color"] as? String ?: ""
            val iconStr = jsonMap["icon"] as? String ?: ""
            val automationName = jsonMap["automation_name"] as? String ?: ""

            val colorKey = WidgetColorKey.entries.firstOrNull {
                it.name.equals(colorStr, ignoreCase = true)
            } ?: WidgetColorKey.BLUE

            val iconKey = WidgetIconKey.entries.firstOrNull {
                it.name.equals(iconStr, ignoreCase = true) || it.displayLabel.equals(iconStr, ignoreCase = true)
            } ?: WidgetIconKey.STAR

            val automations: List<Automation> = repository?.allAutomations?.firstOrNull() ?: emptyList()
            val matchedAutomation = if (automationName.isNotBlank()) {
                automations.firstOrNull { auto ->
                    auto.name.equals(automationName, ignoreCase = true) ||
                            auto.name.contains(automationName, ignoreCase = true) ||
                            automationName.contains(auto.name, ignoreCase = true)
                }
            } else {
                null
            }

            if (matchedAutomation != null) {
                currentData = currentData.copy(
                    label = label,
                    selectedColorKey = colorKey,
                    selectedIconKey = iconKey,
                    selectedAutomationId = matchedAutomation.id,
                    isGeneratingAi = false,
                    aiNoMatchMessage = null
                )
            } else {
                val noMatchMsg = "No matching shortcut found for '$automationName'. Create it first in the AI Builder, or pick an existing shortcut below."
                currentData = currentData.copy(
                    label = label,
                    selectedColorKey = colorKey,
                    selectedIconKey = iconKey,
                    selectedAutomationId = null,
                    isGeneratingAi = false,
                    aiNoMatchMessage = noMatchMsg
                )
            }
            _uiState.value = UiState.Success(currentData)
        } catch (e: Exception) {
            currentData = currentData.copy(isGeneratingAi = false)
            _uiState.value = UiState.Error(e.localizedMessage ?: "AI generation failed due to internal error", e)
        }
    }

    fun saveTemplate() {
        val label = currentData.label.trim()
        val colorKey = currentData.selectedColorKey
        val iconKey = currentData.selectedIconKey
        val automationId = currentData.selectedAutomationId

        if (label.isBlank() || colorKey == null || iconKey == null || automationId == null) {
            _uiState.value = UiState.Error("All fields must be selected before saving")
            return
        }

        viewModelScope.launch {
            try {
                templateDao?.insert(
                    CustomWidgetTemplate(
                        label = label,
                        colorKey = colorKey.name,
                        iconKey = iconKey.name,
                        automationId = automationId
                    )
                )
                currentData = currentData.copy(isSaved = true)
                _uiState.value = UiState.Success(currentData)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to save template: ${e.localizedMessage}", e)
            }
        }
    }

    fun clearError() {
        _uiState.value = UiState.Success(currentData)
    }
}

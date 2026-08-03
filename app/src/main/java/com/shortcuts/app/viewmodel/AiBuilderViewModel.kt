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
            if (!jsonResponse.isNullOrBlank()) {
                val automation = parseAutomationJson(jsonResponse, promptText)
                if (automation != null) {
                    currentData = currentData.copy(
                        isGenerating = false,
                        generatedAutomation = automation
                    )
                    _uiState.value = UiState.Success(currentData)
                } else {
                    _uiState.value = UiState.Error("Failed to parse automation JSON from AI output")
                }
            } else {
                _uiState.value = UiState.Error("AI model inference returned no valid output")
            }
        } catch (e: Exception) {
            _uiState.value = UiState.Error(e.localizedMessage ?: "AI generation failed due to internal error", e)
        }
    }

    fun parseAutomationJson(json: String, fallbackName: String = ""): Automation? {
        return try {
            val trimmed = json.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith("{")) {
                val jsonObject = gson.fromJson(trimmed, Map::class.java) ?: return null
                val name = (jsonObject["automation_name"] as? String)
                    ?: (jsonObject["name"] as? String)
                    ?: if (fallbackName.isNotBlank()) "AI Shortcut: $fallbackName" else "AI Shortcut"

                val rawActions = jsonObject["actions"] as? List<*> ?: return null
                if (rawActions.isEmpty()) return null

                val normalizedActions = rawActions.mapNotNull { item ->
                    if (item is Map<*, *>) normalizeActionMap(item) else null
                }
                if (normalizedActions.size != rawActions.size) return null

                val actionsJsonStr = gson.toJson(normalizedActions)
                if (actionsJsonStr == "null" || actionsJsonStr.isBlank() || actionsJsonStr == "[]") {
                    return null
                }

                val parsedActions = ActionConverter().toActionList(actionsJsonStr)
                if (parsedActions.isNullOrEmpty() || parsedActions.any { (it.actionType as ActionType?) == null }) {
                    return null
                }

                Automation(
                    name = name,
                    actionsJson = actionsJsonStr,
                    triggerType = "AI_GENERATED"
                )
            } else if (trimmed.startsWith("[")) {
                if (trimmed == "[]") return null
                val rawList = gson.fromJson(trimmed, List::class.java) ?: return null
                if (rawList.isEmpty()) return null

                val normalizedActions = rawList.mapNotNull { item ->
                    if (item is Map<*, *>) normalizeActionMap(item) else null
                }
                if (normalizedActions.size != rawList.size) return null

                val actionsJsonStr = gson.toJson(normalizedActions)
                val parsedActions = ActionConverter().toActionList(actionsJsonStr)
                if (parsedActions.isNullOrEmpty() || parsedActions.any { (it.actionType as ActionType?) == null }) {
                    return null
                }

                val name = if (fallbackName.isNotBlank()) "AI Shortcut: $fallbackName" else "AI Shortcut"
                Automation(
                    name = name,
                    actionsJson = actionsJsonStr,
                    triggerType = "AI_GENERATED"
                )
            } else {
                android.util.Log.e("AiBuilderViewModel", "Invalid JSON format: $trimmed")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("AiBuilderViewModel", "Failed to parse automation JSON: ${e.message}", e)
            null
        }
    }

    private fun normalizeActionMap(map: Map<*, *>): Map<String, Any?> {
        val normalized = mutableMapOf<String, Any?>()
        for ((key, value) in map) {
            if (key is String) {
                val camelKey = when (key) {
                    "action_type" -> "actionType"
                    "package_name" -> "packageName"
                    "intent_action" -> "intentAction"
                    "target_node_id" -> "targetNodeId"
                    "text_input" -> "textInput"
                    "ui_action_type" -> "uiActionType"
                    "global_action" -> "globalAction"
                    "scroll_direction" -> "scrollDirection"
                    "target_text" -> "targetText"
                    else -> key
                }
                normalized[camelKey] = value
            }
        }
        return normalized
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

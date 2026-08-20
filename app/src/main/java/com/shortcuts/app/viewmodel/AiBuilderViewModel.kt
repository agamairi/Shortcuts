package com.shortcuts.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.planner.DraftShortcut
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.planner.ClauseAligner
import com.shortcuts.app.planner.FunctionCallParser
import com.shortcuts.app.planner.InstalledApp
import com.shortcuts.app.planner.PromptSegmenter
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.ActionExecutorService
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.ModelDownloaderService
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.service.RunResult
import com.shortcuts.app.service.StepResult
import com.shortcuts.app.ui.screens.MadlibSlot
import com.shortcuts.app.ui.screens.MadlibState
import com.shortcuts.app.ui.screens.MadlibTemplate
import com.shortcuts.app.ui.screens.defaultMadlibState
import com.shortcuts.app.ui.screens.withRandomSlots
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Whether the AI builder shows the madlib slot UI or the free-text prompt. */
enum class MadlibBuilderMode { MADLIB, FREE_TEXT }

data class AiBuilderData(
    val prompt: String = "",
    val downloadProgress: Int? = null,
    val isGenerating: Boolean = false,
    val generatedAutomation: Automation? = null,
    val isSaved: Boolean = false,
    /** Per-segment planning outcome; Unresolved steps are surfaced, never dropped. */
    val draft: DraftShortcut? = null,
    /** Editable title for the shortcut being reviewed. */
    val shortcutName: String = "",
    /** Results are aligned with [draft.steps], including visible unresolved placeholders. */
    val stepResults: List<StepResult>? = null,
    val isTestRunning: Boolean = false,
    /** Controls whether the madlib slot UI or the free-text prompt is shown. */
    val builderMode: MadlibBuilderMode = MadlibBuilderMode.MADLIB,
    /** Current state of the slot-based madlib builder. */
    val madlibState: MadlibState = defaultMadlibState(),
    /**
     * The tile-colour key chosen once for this editing session.
     * Used as both the builder/describe screen background tint and the saved Automation.colorKey
     * so the dashboard tile and homescreen widget use the same colour.
     * Null only until the ViewModel picks it on first creation.
     */
    val tileColorKey: String? = null,
    /** Icon chosen for the saved automation, defaulting to the standard bolt. */
    val tileIconKey: String? = WidgetIconKey.BOLT.name
)

class AiBuilderViewModel(
    private val repository: AutomationRepository? = null,
    private val inferenceService: OnDeviceInferenceService? = null,
    private val downloadStateFlow: StateFlow<DownloadState> = ModelDownloaderService.downloadState,
    private val startDownloadAction: ((Context) -> Unit)? = { ctx -> ModelDownloaderService.startDownload(ctx) },
    private val executeDraftActions: (Context, List<Action>, String) -> RunResult = { context, actions, name ->
        ActionExecutorService(context).executeActions(actions, name)
    },
    /** Seeded random for the tile-color pick — injectable so tests can be deterministic. */
    private val random: kotlin.random.Random = kotlin.random.Random.Default
) : ViewModel() {

    private val gson = Gson()

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    private val _uiState = MutableStateFlow<UiState<AiBuilderData>>(UiState.Success(AiBuilderData()))
    val uiState: StateFlow<UiState<AiBuilderData>> = _uiState.asStateFlow()

    private var currentData = AiBuilderData(
        tileColorKey = pickRandomTileColor(random),
        tileIconKey = WidgetIconKey.BOLT.name
    )

    init {
        _uiState.value = UiState.Success(currentData)
    }

    fun updatePrompt(newPrompt: String) {
        _prompt.value = newPrompt
        currentData = currentData.copy(prompt = newPrompt)
        _uiState.value = UiState.Success(currentData)
    }

    /** Updates the review-step appearance that will be persisted with the AI automation. */
    fun updateAppearance(colorKey: WidgetColorKey, iconKey: WidgetIconKey) {
        currentData = currentData.copy(tileColorKey = colorKey.name, tileIconKey = iconKey.name)
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

    /** Delegates quote- and speech-verb-aware deterministic decomposition to [PromptSegmenter]. */
    fun splitIntoSteps(promptText: String): List<String> = PromptSegmenter.split(promptText)

    suspend fun performInference(promptText: String) {
        try {
            val clauses = splitIntoSteps(promptText)
            // Tier 1: always give the complete request one chance. FunctionGemma is normally
            // single-call, but some outputs contain multiple valid calls and must not be discarded.
            val tierOneResponse = inferenceService?.generateAutomationJson(promptText)

            if (clauses.size <= 1) {
                if (tierOneResponse.isNullOrBlank()) {
                    _uiState.value = UiState.Error("AI model inference returned no valid output")
                    return
                }
                // A normal one-call response follows the exact same parser and output path as before.
                val automation = parseAutomationJson(tierOneResponse, promptText)
                if (automation == null) {
                    _uiState.value = UiState.Error("Failed to parse automation JSON from AI output")
                    return
                }
                val action = ActionConverter().toActionList(automation.actionsJson).firstOrNull()
                if (action == null) {
                    _uiState.value = UiState.Error("The AI produced no action for this step.")
                    return
                }
                val draft = DraftShortcut(
                    steps = listOf(DraftStep.Resolved(promptText, action, TIER_ONE_CONFIDENCE)),
                    originalPrompt = promptText
                )
                currentData = currentData.copy(
                    isGenerating = false,
                    generatedAutomation = automation.copy(actionsJson = gson.toJson(listOf(action))),
                    draft = draft,
                    shortcutName = automation.name,
                    stepResults = null
                )
                _uiState.value = UiState.Success(currentData)
                return
            }

            val tierOneActions = tierOneResponse
                ?.let { parseAutomationJson(it, promptText) }
                ?.let { ActionConverter().toActionList(it.actionsJson) }
                .orEmpty()

            // Tier 1 calls name their target but not their source clause. Align only the calls
            // with strong, unique evidence; every remaining clause uses the established Tier-2
            // recovery path, preserving one visible DraftStep for each deterministic clause.
            val alignedTierOneActions = ClauseAligner { packageName ->
                inferenceService?.appLabelForPackage(packageName)
            }.align(clauses, tierOneActions)
            val draftSteps = clauses.mapIndexed { index, clause ->
                val batched = alignedTierOneActions[index]
                batched?.let { action ->
                    DraftStep.Resolved(sourceText = clause, action = action, confidence = TIER_ONE_CONFIDENCE)
                } ?: planStep(clause, TIER_TWO_CONFIDENCE)
            }
            val draft = DraftShortcut(steps = draftSteps, originalPrompt = promptText)

            val allActions = draftSteps.filterIsInstance<DraftStep.Resolved>().map { it.action }

            if (allActions.isEmpty()) {
                currentData = currentData.copy(isGenerating = false, draft = draft)
                _uiState.value = UiState.Error("Failed to generate any actions from AI output")
                return
            }

            val automation = Automation(
                name = "AI Shortcut: $promptText",
                actionsJson = gson.toJson(allActions),
                triggerType = "AI_GENERATED"
            )
            currentData = currentData.copy(
                isGenerating = false,
                generatedAutomation = automation,
                draft = draft,
                shortcutName = automation.name,
                stepResults = null
            )
            _uiState.value = UiState.Success(currentData)
        } catch (e: Exception) {
            _uiState.value = UiState.Error(e.localizedMessage ?: "AI generation failed due to internal error", e)
        }
    }

    /**
     * Runs one segment through the model and maps it onto exactly one [DraftStep].
     * Never returns null and never throws — a failure becomes [DraftStep.Unresolved].
     */
    private suspend fun planStep(step: String, confidence: Float): DraftStep {
        val jsonResponse = try {
            inferenceService?.generateAutomationJson(step)
        } catch (e: Exception) {
            null
        }
        if (jsonResponse.isNullOrBlank()) {
            return DraftStep.Unresolved(step, "The AI model returned no output for this step.")
        }
        val stepAutomation = parseAutomationJson(jsonResponse, step)
            ?: return DraftStep.Unresolved(step, "The AI output for this step could not be understood.")

        val actions = ActionConverter().toActionList(stepAutomation.actionsJson)
        val action = actions.firstOrNull()
            ?: return DraftStep.Unresolved(step, "The AI produced no action for this step.")

        return DraftStep.Resolved(sourceText = step, action = action, confidence = confidence)
    }

    /**
     * FunctionGemma's native output is `<start_function_call>call:name{arg:<escape>value<escape>}<end_function_call>`,
     * not JSON. [FunctionCallParser] uses findAll so every supported call is retained in order.
     */
    fun parseFunctionCallResponse(response: String, fallbackName: String = ""): Automation? {
        val actions = FunctionCallParser { query -> inferenceService?.resolveApp(query) }
            .parseActions(response)
        if (actions.isEmpty()) return null

        val actionsJsonStr = gson.toJson(actions)
        val name = if (fallbackName.isNotBlank()) "AI Shortcut: $fallbackName" else "AI Shortcut"
        return Automation(
            name = name,
            actionsJson = actionsJsonStr,
            triggerType = "AI_GENERATED"
        )
    }

    fun parseAutomationJson(json: String, fallbackName: String = ""): Automation? {
        parseFunctionCallResponse(json, fallbackName)?.let { return it }
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



    fun updateShortcutName(name: String) {
        val automation = currentData.generatedAutomation?.copy(name = name)
        currentData = currentData.copy(shortcutName = name, generatedAutomation = automation)
        _uiState.value = UiState.Success(currentData)
    }

    fun updateStep(index: Int, action: Action) {
        updateDraftStep(index) { step ->
            if (step is DraftStep.Resolved) step.copy(action = action) else step
        }
    }

    /** Replaces a visible AI failure placeholder with an action authored by the user. */
    fun replaceUnresolvedStep(index: Int, action: Action) {
        updateDraftStep(index) { step ->
            if (step is DraftStep.Unresolved) {
                DraftStep.Resolved(step.sourceText, action, MANUAL_CONFIDENCE)
            } else {
                step
            }
        }
    }

    fun deleteStep(index: Int) {
        val draft = currentData.draft ?: return
        if (index !in draft.steps.indices) return
        updateDraft(draft.copy(steps = draft.steps.filterIndexed { itemIndex, _ -> itemIndex != index }))
    }

    fun moveStep(fromIndex: Int, toIndex: Int) {
        val draft = currentData.draft ?: return
        if (fromIndex !in draft.steps.indices || toIndex !in draft.steps.indices || fromIndex == toIndex) return
        val steps = draft.steps.toMutableList()
        val moved = steps.removeAt(fromIndex)
        steps.add(toIndex, moved)
        updateDraft(draft.copy(steps = steps))
    }

    fun addStep(action: Action, sourceText: String = "Added manually") {
        val draft = currentData.draft ?: DraftShortcut(emptyList(), currentData.prompt)
        updateDraft(draft.copy(steps = draft.steps + DraftStep.Resolved(sourceText, action, MANUAL_CONFIDENCE)))
    }

    /** Runs only executable actions, then restores result positions for unresolved draft cards. */
    fun testRun(context: Context) {
        val draft = currentData.draft ?: return
        val actions = draft.steps.filterIsInstance<DraftStep.Resolved>().map { it.action }
        val shortcutName = currentData.shortcutName
        currentData = currentData.copy(isTestRunning = true, stepResults = null)
        _uiState.value = UiState.Success(currentData)
        viewModelScope.launch(Dispatchers.IO) {
            val result = if (actions.isEmpty()) null else executeDraftActions(context, actions, shortcutName)
            val alignedResults = alignResults(draft.steps, result?.steps.orEmpty())
            currentData = if (currentData.draft == draft) {
                currentData.copy(isTestRunning = false, stepResults = alignedResults)
            } else {
                currentData.copy(isTestRunning = false)
            }
            _uiState.value = UiState.Success(currentData)
        }
    }

    fun saveGeneratedAutomation() {
        val draft = currentData.draft ?: return
        if (draft.steps.any { it is DraftStep.Unresolved }) {
            _uiState.value = UiState.Error("Fix or remove every step the AI could not complete before saving.")
            return
        }
        val actions = draft.steps.filterIsInstance<DraftStep.Resolved>().map { it.action }
        if (actions.isEmpty()) {
            _uiState.value = UiState.Error("Add at least one step before saving.")
            return
        }
        val shortcutName = currentData.shortcutName.trim()
        if (shortcutName.isBlank()) {
            _uiState.value = UiState.Error("Give this shortcut a name before saving.")
            return
        }
        val colorKey = currentData.tileColorKey
        val iconKey = currentData.tileIconKey
        val automation = (currentData.generatedAutomation ?: Automation(
            name = shortcutName,
            actionsJson = "[]",
            triggerType = "AI_GENERATED"
        )).copy(
            name = shortcutName,
            actionsJson = gson.toJson(actions),
            colorKey = colorKey,
            iconKey = iconKey
        )
        viewModelScope.launch {
            try {
                repository?.insert(automation)
                currentData = currentData.copy(isSaved = true, generatedAutomation = automation)
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
        currentData = currentData.copy(isGenerating = false, downloadProgress = null)
        _uiState.value = UiState.Success(currentData)
    }

    private fun updateDraftStep(index: Int, transform: (DraftStep) -> DraftStep) {
        val draft = currentData.draft ?: return
        if (index !in draft.steps.indices) return
        updateDraft(draft.copy(steps = draft.steps.mapIndexed { itemIndex, step ->
            if (itemIndex == index) transform(step) else step
        }))
    }

    private fun updateDraft(draft: DraftShortcut) {
        val actions = draft.steps.filterIsInstance<DraftStep.Resolved>().map { it.action }
        val name = currentData.shortcutName.ifBlank {
            currentData.generatedAutomation?.name ?: "AI Shortcut"
        }
        val automation = (currentData.generatedAutomation ?: Automation(
            name = name,
            actionsJson = "[]",
            triggerType = "AI_GENERATED"
        )).copy(name = name, actionsJson = gson.toJson(actions))
        currentData = currentData.copy(
            draft = draft,
            shortcutName = name,
            generatedAutomation = automation,
            stepResults = null
        )
        _uiState.value = UiState.Success(currentData)
    }

    private fun alignResults(draftSteps: List<DraftStep>, actionResults: List<StepResult>): List<StepResult> {
        var resolvedIndex = 0
        return draftSteps.map { step ->
            if (step is DraftStep.Resolved) {
                actionResults.getOrElse(resolvedIndex++) {
                    StepResult.Skipped("This step was not run.")
                }
            } else {
                StepResult.Skipped("Fix this step before testing it.")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Madlib slot-based builder functions
    // -----------------------------------------------------------------------

    fun switchToMadlib() {
        currentData = currentData.copy(builderMode = MadlibBuilderMode.MADLIB)
        _uiState.value = UiState.Success(currentData)
    }

    fun switchToFreeText() {
        currentData = currentData.copy(builderMode = MadlibBuilderMode.FREE_TEXT)
        _uiState.value = UiState.Success(currentData)
    }

    /** Updates the installed-app list available to app slots (called once on screen entry). */
    fun loadInstalledApps(apps: List<InstalledApp>) {
        val refreshed = currentData.madlibState.let { ms ->
            ms.copy(
                installedApps = apps,
                firstSlot = if (ms.firstSlot is MadlibSlot.App) ms.firstSlot.copy(apps = apps) else ms.firstSlot,
                secondSlot = if (ms.secondSlot is MadlibSlot.App) ms.secondSlot.copy(apps = apps) else ms.secondSlot
            )
        }
        currentData = currentData.copy(madlibState = refreshed)
        _uiState.value = UiState.Success(currentData)
    }

    /** Cycles to the next template and resets slots to their defaults. */
    fun updateMadlibTemplate(template: MadlibTemplate) {
        val newState = defaultMadlibState(template, currentData.madlibState.installedApps)
        currentData = currentData.copy(madlibState = newState)
        _uiState.value = UiState.Success(currentData)
    }

    /** Cycles the first slot to its next valid value. */
    fun advanceFirstSlot() {
        currentData = currentData.copy(
            madlibState = currentData.madlibState.copy(
                firstSlot = currentData.madlibState.firstSlot.next()
            )
        )
        _uiState.value = UiState.Success(currentData)
    }

    /** Cycles the second slot to its next valid value. */
    fun advanceSecondSlot() {
        currentData = currentData.copy(
            madlibState = currentData.madlibState.copy(
                secondSlot = currentData.madlibState.secondSlot.next()
            )
        )
        _uiState.value = UiState.Success(currentData)
    }

    fun setFirstSlotIndex(index: Int) {
        currentData = currentData.copy(
            madlibState = currentData.madlibState.copy(
                firstSlot = currentData.madlibState.firstSlot.withIndex(index)
            )
        )
        _uiState.value = UiState.Success(currentData)
    }

    fun setSecondSlotIndex(index: Int) {
        currentData = currentData.copy(
            madlibState = currentData.madlibState.copy(
                secondSlot = currentData.madlibState.secondSlot.withIndex(index)
            )
        )
        _uiState.value = UiState.Success(currentData)
    }

    /** Randomises both slots while keeping only valid values. */
    fun inspireMe() {
        currentData = currentData.copy(
            madlibState = currentData.madlibState.withRandomSlots()
        )
        _uiState.value = UiState.Success(currentData)
    }

    /**
     * Builds an [Automation] directly from the chosen slots — no model call needed —
     * then transitions to the draft review screen so the user can inspect before saving.
     */
    fun confirmMadlib() {
        val madlib = currentData.madlibState
        val automation = madlib.buildAutomation()
        if (automation == null) {
            _uiState.value = UiState.Error("Fill in every slot before saving.")
            return
        }
        val colorKey = currentData.tileColorKey
        val iconKey = currentData.tileIconKey
        val tintedAutomation = automation.copy(colorKey = colorKey, iconKey = iconKey)
        val actions = ActionConverter().toActionList(tintedAutomation.actionsJson)
        val draftSteps = actions.mapIndexed { idx, action ->
            DraftStep.Resolved(
                sourceText = "Step ${idx + 1}",
                action = action,
                confidence = MANUAL_CONFIDENCE
            )
        }
        val draft = DraftShortcut(steps = draftSteps, originalPrompt = tintedAutomation.name)
        currentData = currentData.copy(
            generatedAutomation = tintedAutomation,
            shortcutName = tintedAutomation.name,
            draft = draft,
            stepResults = null
        )
        _uiState.value = UiState.Success(currentData)
    }

    private companion object {
        const val TIER_ONE_CONFIDENCE = 0.95f
        const val TIER_TWO_CONFIDENCE = 0.65f
        const val MANUAL_CONFIDENCE = 0.95f
    }
}

// ---------------------------------------------------------------------------
// Tile-color randomisation — pure Kotlin, no Android/Compose dependencies,
// so it unit-tests cleanly on the JVM.
// ---------------------------------------------------------------------------

/**
 * Every supported colour key. Deriving this from the enum keeps AI defaults in sync with widgets.
 */
val TILE_COLOR_KEYS: List<String> = WidgetColorKey.entries.map { it.name }

/**
 * Picks one colour key at random from [TILE_COLOR_KEYS].
 *
 * @param random Inject a seeded [kotlin.random.Random] in tests so results are deterministic.
 * @return one supported widget colour key, never anything else.
 */
fun pickRandomTileColor(random: kotlin.random.Random = kotlin.random.Random.Default): String =
    TILE_COLOR_KEYS[random.nextInt(TILE_COLOR_KEYS.size)]

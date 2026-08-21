---
title: ViewModel Architecture & UiState Protocol
type: architecture
author: teamwork_preview_worker_m4
tags: [viewmodel, uistate, stateflow, mvvm, jetpack-compose]
---

# ViewModel Architecture & UiState Protocol

## Overview
The UI layer of **Shortcuts** strictly adheres to the MVVM pattern with unidirectional data flow (UDF). ViewModels expose immutable `StateFlow<UiState<T>>` properties observed by Jetpack Compose composable screens.

---

## 1. The `UiState` Sealed Class

Located in `com.shortcuts.app.ui.state.UiState`:

```kotlin
sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<out T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}
```

---

## 2. ViewModel Implementation & Responsibilities

### 1. `AutomationViewModel`
- **Responsibility**: Manages the list of active automations on the Dashboard screen.
- **Exposes**: `uiState: StateFlow<UiState<List<Automation>>>`
- **Operations**:
  - `loadAutomations()`
  - `toggleAutomation(automation: Automation)`
  - `deleteAutomation(automation: Automation)`
  - `runAutomation(automation: Automation)`

### 2. AiBuilderViewModel
- **Responsibility**: Processes natural-language prompts into an editable `DraftShortcut` and its executable `Action` list via on-device inference. It supports follow-up turns by retaining recent draft steps as model context. Scoped to its `NavBackStackEntry`, so backing out discards the route's in-progress builder state.
- **Exposes**: Separate `prompt: StateFlow<String>` and `uiState: StateFlow<UiState<AiBuilderData>>`. `AiBuilderData` carries download/generation progress, the nullable draft, save/test results, and selected tile appearance; it carries no AI-builder mode or slot-template state ([`AiBuilderViewModel.kt:34-56`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)).
- **Key Operations & State Transitions**:
  - `updatePrompt(prompt: String)` updates both prompt flows used by the initial prompt field and the review screen's follow-up field ([`AiBuilderViewModel.kt:101-105`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)).
  - `downloadModelAndGenerate(context)` rejects blank input, starts or observes model download, then invokes inference after `DownloadState.Completed` ([`AiBuilderViewModel.kt:113-158`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)).
  - `performInference(prompt)` segments the request, passes the response through the function-call/JSON parsing path, and, when it produces draft steps, appends resolved or unresolved steps to the existing draft and clears the prompt for the next turn ([`AiBuilderViewModel.kt:165-250`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)). It does not own a separate madlib/free-text mode.
  - `contextualizedPrompt(request)` supplies at most four prior step source texts when a draft exists, allowing references such as “then close it” without unbounded on-device-model context ([`AiBuilderViewModel.kt:261-272`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)).
  - Draft changes and persistence are review-stage operations: `updateStep`, `replaceUnresolvedStep`, `deleteStep`, `addStep`, `testRun`, and `saveGeneratedAutomation` ([`AiBuilderViewModel.kt:409-503`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)).

### 3. CustomWidgetViewModel
- **Responsibility**: Manages state for the custom widget builder, including AI generation of widget specifications. Scoped to its `NavBackStackEntry`.
- **Exposes**: `uiState: StateFlow<UiState<CustomWidgetBuilderData>>`
- **Operations**:
  - `generateWithAi(context)`: Gated on model-download completion.
  - Manual selection of label, color, icon, and automation ID.
  - `saveTemplate()`: Persists the configured template.

### 4. ModelDownloadViewModel
- **Responsibility**: Observes model download progress and binds download state to UI.
- **Exposes**: `downloadState: StateFlow<ModelDownloadState>`
- **Operations**: `startDownload()`, `cancelDownload()`.

---

## 3. UI State Lifecycle in Jetpack Compose
Composables collect state safely using `collectAsStateWithLifecycle()`:

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()

when (uiState) {
    is UiState.Idle -> IdleView()
    is UiState.Loading -> CircularProgressIndicator()
    is UiState.Success -> AutomationListView(data = (uiState as UiState.Success).data)
    is UiState.Error -> ErrorSnackbar(message = (uiState as UiState.Error).message)
}
```

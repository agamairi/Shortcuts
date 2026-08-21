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
- **Responsibility**: Processes natural language user prompts and converts them into executable `Action` lists via on-device AI model output. It supports multi-turn conversations by retaining recent steps as context. Scoped to its `NavBackStackEntry`, so backing out discards drafts.
- **Exposes**: `uiState: StateFlow<UiState<AiBuilderData>>`
- **Key Operations & Defenses**:
  - `updatePrompt(prompt: String)`
  - `downloadModelAndGenerate(context)`: Validates model status, triggers `OnDeviceInferenceService`, strips Markdown code block wrappers, parses JSON, and emits `UiState.Success` or `UiState.Error`.
  - Supports slot-based madlib builder mode alongside free-text mode.

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

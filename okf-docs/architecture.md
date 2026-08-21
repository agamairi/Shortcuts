---
title: System Architecture & Implementation Architecture
type: architecture
author: teamwork_preview_worker_m4
tags: [architecture, android, mvvm, room, accessibility, model-downloader, uistate, glance, widgets]
---

# System Architecture & Implementation Architecture

## Overview
The **Shortcuts** Android application is an automated widget and workflow engine. It allows users to build, save, and execute automation flows using both a manual visual builder and natural language AI prompts via an on-device AI model (`function-gemma`).

---

## 1. System Components & Layers

```
+-----------------------------------------------------------------------------------+
|                            UI Layer (Jetpack Compose M3)                          |
| DashboardScreen | AiBuilderScreen | ManualBuilderScreen | CreateWidgetScreen      |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                             ViewModel / UI State Layer                            |
| AutomationViewModel | AiBuilderViewModel | CustomWidgetViewModel | ModelDownloadVM|
|                       (Exposes Kotlin StateFlow<UiState<T>>)                      |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                                Repository Layer                                   |
|       AutomationRepository           |          ModelDownloaderRepository         |
+-----------------------------------------------------------------------------------+
             |                                                  |
             v                                                  v
+------------------------------------+             +--------------------------------+
|         Data / Database            |             |      Background Services       |
| Room DB v7 (7 DAOs & Entities)     |             | ModelDownloaderService         |
| ActionConverter & Migration 1->..->7|            | AutomationAccessibilityService |
+------------------------------------+             | AutomationExecutionService     |
                                                   | OnDeviceInferenceService       |
                                                   +--------------------------------+
```

---

## 2. ModelDownloaderService Architecture
- **Type**: Foreground Android Service (`Service`) with ongoing status notifications.
- **Purpose**: Manages asynchronous downloading of on-device AI models (`function-gemma`).
- **Key Capabilities**:
  - Validates available device storage before download initiation.
  - Broadcasts real-time download progress (0% - 100%) and status state updates (`IDLE`, `DOWNLOADING`, `VERIFYING`, `COMPLETED`, `FAILED`).
  - Verifies file integrity via SHA-256 checksum upon completion.
  - Provides graceful cancellation and resource cleanup.

---

## 3. AutomationAccessibilityService & Action Execution Pipeline
- **Type**: Android `AccessibilityService`.
- **Purpose**: Executes UI automation actions directly on target application node hierarchies without requiring root permissions.
- **Capabilities & Defenses**:
  - **Node Searching**: Locates target nodes by resource ID, view text, or recursive tree search.
  - **Gesture & Input Simulation**: Simulates click actions, long presses, scrolling, and text input injection into editable fields.
  - **System Actions**: Handles global navigation actions (Home, Back, Recent Apps, Notifications).
  - **Recursion & Cycle Limits**: Hardened with `maxParentDepth` cap (25) and `maxDepth` cap (20) to prevent infinite loops during cyclic accessibility node traversal.
  - **Action Executor Service**: Decodes `Action` JSON specifications into discrete service commands.

---

## 4. MVVM & UIState Architecture
- **State Management**: Built on `StateFlow` and immutable state models (`UiState<T>`).
- **State Definitions**:
  - `UiState.Idle`: Initial state or uninitiated state.
  - `UiState.Loading`: Operational or asynchronous work in progress.
  - `UiState.Success(data)`: Operation completed with data payload.
  - `UiState.Error(message)`: Error captured with user-friendly error message.
- **Strict JSON Parsing**: `AiBuilderViewModel` and `CustomWidgetViewModel` strip Markdown code blocks (e.g. ```json ... ```) and strictly validate JSON payloads against target schemas.
- **Shared Inference Service**: `ShortcutsNavigation` instantiates a single `OnDeviceInferenceService` and injects it into both `AiBuilderViewModel` and `CustomWidgetViewModel`.

---

## 5. Room Database Schema
- **Schema Version**: Version 7 with real, non-destructive migration objects (`MIGRATION_1_2` through `MIGRATION_6_7`) and exported KSP schema JSON files in `app/schemas/`. MIGRATION_6_7 supports the consolidated adaptive widget.
- **Entities**:
  - `Automation`: `id` (Int, PK, autoGenerate), `name` (String), `actionsJson` (String), `isActive` (Boolean), `triggerType` (String), `colorKey` (String?, default null), `iconKey` (String?, default null).
  - `WidgetBinding`: `widgetId` (Int, PK), `automationId` (Int).
  - `WidgetListBinding`: `widgetId` (Int, PK), `automationIdsJson` (String).
  - `CustomWidgetTemplate`: `id` (Int, PK, autoGenerate), `label` (String), `colorKey` (String), `iconKey` (String), `automationId` (Int).
  - `CustomWidgetBinding`: `widgetId` (Int, PK), `templateId` (Int).
  - `GridWidgetBinding`: `widgetId` (Int, PK), `templateIdsJson` (String).
  - `GreetingWidgetBinding`: `widgetId` (Int, PK), `userName` (String), `colorKey` (String), `automationId` (Int).
- **DAOs**: `AutomationDao`, `WidgetBindingDao`, `WidgetListBindingDao`, `CustomWidgetTemplateDao`, `CustomWidgetBindingDao`, `GridWidgetBindingDao`, `GreetingWidgetBindingDao`.

---

## 6. Home Screen Widgets & In-App Widget Builder

### 6.1 Architecture & Core Widgets
- **Technology**: Jetpack Glance (`glance-appwidget:1.0.0`, `glance-material3:1.0.0`).
- **Design Ethos**: Apple-Shortcuts-simple aesthetic without excessive Material chrome.
- **Widget Types**:
  - **Adaptive Shortcut Widget** (`ShortcutWidgetReceiver`): The current, unified widget provider supporting adaptive layouts. Configured via `ShortcutWidgetConfigActivity`.
  - **Legacy Widgets** (maintained for compatibility with existing home screens):
    1. **Quick Shortcut Tile** (`AutomationWidgetReceiver`)
    2. **Shortcuts List Widget** (`ShortcutsListWidgetReceiver`)
    3. **Custom Widget** (`CustomWidgetReceiver`)
    4. **Shortcuts Grid Widget** (`GridWidgetReceiver`)
    5. **Greeting Widget** (`GreetingWidgetReceiver`)

### 6.2 Execution Callback (`RunAutomationCallback`)
- Extended `RunAutomationCallback` (`ActionCallback`) accepts an optional `AutomationIdParamKey` parameter (`actionParametersOf(AutomationIdParamKey to id)`).
- When `AutomationIdParamKey` is present, it directly executes the specified automation; when absent, it falls back to single-tile `WidgetBinding` lookup.
- Executes action pipelines via `AutomationExecutionService.start()`, a foreground service ensuring multi-step chains survive the tap and returning step results.

### 6.3 AI Widget Builder Flow
- **Components**: `CreateWidgetScreen` and `CustomWidgetViewModel`.
- **FunctionGemma Integration**: Uses `OnDeviceInferenceService.generateWidgetSpecJson(prompt)` to generate strict JSON specs (`label`, `color`, `icon`, `automation_name`).
- **Fallback & Resolution**: Maps color and icon strings to enums, resolves target shortcuts against `repository.allAutomations`. If no matching shortcut exists, displays a warning banner (`aiNoMatchMessage`) while retaining tile customization.
- **Save & Pin**: Saves templates via `CustomWidgetTemplateDao` and exposes a direct "Pin to Home Screen" action via `AppWidgetManager.requestPinAppWidget`.

### 6.3.1 Shortcut Appearance Resolution
- **Single source of truth**: `WidgetColorKey` and `WidgetIconKey` define every supported appearance option.
- **Shared resolution**: `widget/WidgetAppearance.kt` provides `resolveWidgetColor`, `resolveWidgetColorKey`, `resolveWidgetIconKey`, and `resolveWidgetIconDrawable`. All persisted keys are matched case-insensitively and invalid or missing values fall back deterministically.
- **Picker coverage**: Manual, recorder, AI review, and custom-widget creation pickers enumerate their enum entries, so expanding either enum automatically exposes the new option.

### 6.4 Shortcuts Grid Widget (`GridWidget`)
- **Layout**: 2-column grid built with nested Glance `Row`/`Column` elements, rendering up to 6 `CustomWidgetTemplate` tiles.
- **Interactivity**: Each grid cell binds its own `actionRunCallback<RunAutomationCallback>(actionParametersOf(AutomationIdParamKey to template.automationId))`.
- **Persistence**: `GridWidgetBinding` entity storing JSON array of template IDs, persisted via `GridWidgetBindingDao` and migrated via `MIGRATION_3_4`.
- **Configuration**: `GridWidgetConfigActivity` allows selecting up to 6 custom widget templates with max-selection validation and empty state handling.

### 6.5 Greeting Widget (`GreetingWidget`)
- **Dynamic Content**: Computes greetings via pure helper `GreetingTextHelper.greetingFor(hour, name)` without Android framework dependencies.
- **Layout & Interactivity**: Outer rounded card displaying non-clickable greeting text (bold, larger), containing an inner tappable shortcut row bound to `RunAutomationCallback` via `AutomationIdParamKey`.
- **Persistence**: `GreetingWidgetBinding` entity storing `widgetId`, `userName`, `colorKey`, and `automationId`, persisted via `GreetingWidgetBindingDao` and migrated to Room DB v5 via `MIGRATION_4_5`.
- **Configuration**: `GreetingWidgetConfigActivity` allows setting a custom name, selecting a background color swatch, and choosing a bound shortcut.

---

## 7. Settings & Help Architecture

### 7.1 Settings Screen (`SettingsScreen` & `SettingsViewModel`)
- **AI Model Management**: Leverages `ModelDownloaderService.downloadState` to observe real-time download status, initiate downloads via `ModelDownloaderService.startDownload(context)`, or delete the local model file (`deleteModel(context)` targeting `File(context.filesDir, "functiongemma.litertlm")`) with confirmation prompt.
- **Accessibility Service Status**: Evaluates `ENABLED_ACCESSIBILITY_SERVICES` from `Settings.Secure` using pure component parsing helper `SettingsViewModel.isAccessibilityServiceEnabledFromSettingString`, providing direct navigation to Android system settings via `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)`.
- **App Meta & Onboarding**: Exposes dynamic package version inspection (`versionName`) and navigation link to the Help screen.

### 7.2 Help Screen (`HelpScreen`)
- **Documentation Engine**: Scrollable user reference breaking down:
  1. What a Shortcut/automation is in this engine.
  2. The 4 supported action types (`APP_INTENT`, `UI_ACTION`, `SYSTEM_TOGGLE`, `WEB_REQUEST`) with concrete examples.
  3. Overview of all 5 home-screen Glance widget types.
  4. Step-by-step walkthrough explaining multi-app action chaining (e.g. launching App A, tapping UI target, launching App B, tapping UI target) within a single shortcut execution pipeline.

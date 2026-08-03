---
title: System Architecture & Implementation Architecture
type: architecture
author: teamwork_preview_worker_m4
tags: [architecture, android, mvvm, room, accessibility, model-downloader, uistate]
---

# System Architecture & Implementation Architecture

## Overview
The **Shortcuts** Android application is an automated widget and workflow engine. It allows users to build, save, and execute automation flows using both a manual visual builder and natural language AI prompts via an on-device AI model (`function-gemma`).

---

## 1. System Components & Layers

```
+-----------------------------------------------------------------------+
|                           UI Layer (Jetpack Compose M3)               |
|   DashboardScreen   |   AiBuilderScreen   |   ManualBuilderScreen     |
+-----------------------------------------------------------------------+
                                   |
                                   v
+-----------------------------------------------------------------------+
|                        ViewModel / UI State Layer                     |
|  AutomationViewModel  |  AiBuilderViewModel  | ModelDownloadViewModel |
|                (Exposes Kotlin StateFlow<UiState<T>>)                  |
+-----------------------------------------------------------------------+
                                   |
                                   v
+-----------------------------------------------------------------------+
|                           Repository Layer                            |
|       AutomationRepository      |      ModelDownloaderRepository      |
+-----------------------------------------------------------------------+
             |                                       |
             v                                       v
+-------------------------+             +-------------------------------+
|      Data / Database    |             |      Background Services      |
| Room DB (AutomationDao) |             | ModelDownloaderService        |
| ActionConverter (Gson)  |             | AutomationAccessibilityService|
+-------------------------+             | ActionExecutorService         |
                                        +-------------------------------+
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
- **Strict JSON Parsing**: `AiBuilderViewModel` strips Markdown code blocks (e.g. ```json ... ```) and strictly validates JSON arrays against `Action` schema before updating state.

---

## 5. Room Database Schema
- **Entity**: `Automation`
  - `id`: Int (Primary Key, autoGenerate = true)
  - `name`: String
  - `actionsJson`: String (Serialized JSON array of `Action` objects via `ActionConverter`)
  - `isActive`: Boolean (default = true)
  - `triggerType`: String (default = "MANUAL")
- **DAO**: `AutomationDao` with `Flow<List<Automation>>` reactive streams for live UI updates.

---
title: Background Services & Accessibility Execution Engine
type: architecture
author: teamwork_preview_worker_m4
tags: [services, accessibility, model-downloader, execution-engine, android]
---

# Background Services & Accessibility Execution Engine

## Overview
This document details the background service infrastructure of the **Shortcuts** Android app, specifically focusing on `ModelDownloaderService`, `AutomationAccessibilityService`, and `ActionExecutorService`.

---

## 1. ModelDownloaderService

`ModelDownloaderService` is a foreground service responsible for downloading and managing local AI model assets (`function-gemma`) for on-device inference.

### Service Lifecycle & State Machine
- **IDLE**: Service waiting for download trigger.
- **DOWNLOADING**: Asynchronously fetching model bytes via HTTP client; updating notification progress bar.
- **VERIFYING**: Performing SHA-256 checksum verification against expected model hash.
- **COMPLETED**: File stored in app internal storage (`/models/function-gemma.bin`); service transitions to finished state.
- **FAILED**: Triggers cleanup on checksum failure, insufficient storage, or network failure.

### Key Methods
- `startDownload(modelUrl: String, checksum: String)`
- `cancelDownload()`
- `validateStorageSpace(requiredBytes: Long): Boolean`

---

## 2. AutomationAccessibilityService

`AutomationAccessibilityService` provides UI automation execution capabilities by interacting with the Android Accessibility Framework (`AccessibilityNodeInfo`).

### Capabilities
1. **Node Traversal & Search**:
   - `findNodeById(rootNode, resourceId)`
   - `findNodeByText(rootNode, text)`
   - `findNodeByTraversal(rootNode, depth = 0, maxDepth = 20)`
2. **Gesture & Input Simulation**:
   - `performClick(node)`
   - `performLongClick(node)`
   - `performScroll(node, direction)`
   - `performTextInput(node, text)`
3. **Global System Actions**:
   - Executes `performGlobalAction()` for `GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`, `GLOBAL_ACTION_RECENTS`.

### Boundary & Security Protection
- **Max Traversal Depth**: Capped at `maxDepth = 20` to prevent deep tree recursion stack overflows.
- **Parent Cycle Limit**: Parent chain traversal (`handleClickNode`) capped at `maxParentDepth = 25` with ancestor set tracking to prevent infinite loops on cyclic node trees.
- **Strict Key Validation**: Rejects unrecognized `globalAction` keys or unsupported `uiActionType` values.

---

## 3. ActionExecutorService

`ActionExecutorService` acts as the orchestrator between high-level `Action` data objects and lower-level execution services.

### Action Routing Pipeline
1. `ActionType.SYSTEM_TOGGLE` -> Invokes `SystemSettingsManager` to switch WiFi/Bluetooth/Flashlight.
2. `ActionType.APP_INTENT` -> Formulates Android `Intent` and invokes `startActivity()`.
3. `ActionType.HTTP_REQUEST` -> Dispatches async HTTP request via standard client.
4. `ActionType.UI_AUTOMATION` -> Formulates `AccessibilityNodeInfo` action and dispatches to `AutomationAccessibilityService`.

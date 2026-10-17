---
title: "Engine v2 Capture Bridge"
type: architecture
author: antigravity
tags: [engine-v2, capture, bridge, recording, touch-overlay, compiler]
---

# Engine v2 Capture Bridge

The Engine v2 Capture Bridge (`V2CaptureBridge`) connects Android runtime capture mechanisms
to the Engine v2 `RecordingCompiler`. It operates concurrently alongside the existing v0.7
recording session (`AutomationRecorder` and `RecorderSession`), supplying `RecordingCompiler`
with typed capture events and screen snapshots without modifying or destabilizing the v0.7
review-and-save pipeline.

## System Overview

```
                      +-----------------------------+
                      |     TouchCaptureOverlay     |
                      |   (ACTION_DOWN snapshot)    |
                      +--------------+--------------+
                                     |
                                     v
+--------------------------+  onGestureClassified   +--------------------------+
|    AutomationRecorder    |----------------------->|     V2CaptureBridge      |
|  - onKeyEvent            |  onImeAction           |  - RecordingCompiler     |
|  - onAccessibilityEvent  |  onAppSwitch           |  - V2CaptureStore        |
|  - onTextCommitListener  |  onTextCommit          |  - WorkflowCodec         |
+--------------------------+----------------------->+-------------+------------+
                                                                  |
                                                                  v
                                                           +---------------+
                                                           |  WorkflowV2   |
                                                           | (Engine v2)   |
                                                           +---------------+
```

## Core Components

### 1. Pure Mappings (`V2CapturePure.kt`)
To keep business logic isolated and testable without Android mocks or Robolectric, capture
heuristics and conversions are implemented as pure Kotlin functions:
- `Sha256TextTokenizer`: Injects a per-installation salt with SHA-256 hashing to tokenize window titles and element descriptions deterministically.
- `mapImeLabelToActionKind(label: String?)`: Translates IME action labels (`Search`, `Send`, `Done`, `Go`, `Next`) into typed `ImeActionKind`. Physical Enter maps to `UNSPECIFIED`.
- `mapKeyCodeToGlobalAction(keyCode: Int)`: Maps navigation key codes (`KEYCODE_BACK`, `KEYCODE_HOME`, `KEYCODE_APP_SWITCH`) to `GlobalActionKind` (`BACK`, `HOME`, `RECENTS`).
- `looksLikeActivityClass(className: CharSequence?)`: Identifies whether an accessibility class name represents an Android Activity (simple name ending with "Activity") rather than internal UI widgets or dialogs (`AlertDialog`).
- `resolveHomePackages(...)`: Filters package query results to assemble launcher package names.
- `selectSaveActionsJson(...)`: Deterministically selects whether to serialize as `WorkflowV2` JSON or legacy v0.7 JSON based on `EngineV2.enabled`, workflow presence, and review screen edits.
- Factory methods: `createCapturedInteraction`, `createCapturedTextCommit`, `createCapturedAppSwitch`, `createCapturedGlobalAction`.

### 2. Bridge Lifecycle & Resilience (`V2CaptureBridge.kt`)
The bridge lifecycle mirrors `AutomationRecorder`:
- **`startRecording(context)`**:
  1. Queries all installed launcher packages via `ACTION_MAIN` + `CATEGORY_HOME`.
  2. Resolves active IME packages from `InputMethodManager` and `Settings.Secure.DEFAULT_INPUT_METHOD`.
  3. Obtains device metadata (`MANUFACTURER`, `MODEL`, `SDK_INT`).
  4. Fetches an initial snapshot from `EngineV2.snapshotSource`.
  5. Instantiates `RecordingCompiler` with a `DefaultScreenFingerprinter` and salted `Sha256TextTokenizer`.
  6. Creates and persists an active session using `V2CaptureStore`.
- **`stopRecording()`**:
  1. Takes a final snapshot from `EngineV2.snapshotSource`.
  2. Invokes `compiler.finish(finalSnapshot)` to produce `WorkflowV2`.
  3. Encodes workflow into JSON via `WorkflowCodec` and persists it as `lastWorkflow`.
- **`clearRecording()`**: Clears active session, in-memory workflow, and stored cache.
- **`restoreSession(context)`**: Rehydrates in-progress capture events if the host process was killed during recording.
- **Degraded Mode**: If `EngineV2.snapshotSource` is null at any point, the bridge logs a warning (`V2Capture: Degraded mode: ...`) once and silently avoids adding steps, ensuring v0.7 recording remains completely unblocked.

### 3. Session Persistence (`V2CaptureStore.kt`)
To survive process death:
- `FileV2CaptureStore` maintains recording state under `filesDir/v2_capture/`:
  - `active_session.json`: In-flight events serialized as `PersistedV2Session`.
  - `last_workflow.json`: The most recently compiled `WorkflowV2` JSON.
- An in-memory fake (`InMemoryV2CaptureStore`) enables fast, isolated unit testing.

## Runtime Hooks

| Event | Source Location | Capture Delivery |
| --- | --- | --- |
| Gestures (tap, long press, swipe) | `TouchCaptureOverlay.kt` | At `ACTION_DOWN`, `EngineV2.snapshotSource?.snapshot()` is preserved. When classified on the main thread, `V2CaptureBridge.onGestureClassified` is called before dispatching relay events. |
| Text field commits | `RecorderSession.kt` & `AutomationRecorder.kt` | When `RecorderSession.flushPendingTextLocked()` commits a field edit (400ms finalizer or focus shift), `onTextCommitListener` invokes `V2CaptureBridge.onTextCommit`. Sensitive/password fields are excluded. |
| IME actions / Enter | `AutomationRecorder.kt` | In `resolveImeSubmitLabel`, `V2CaptureBridge.onImeAction` is delivered with the mapped `ImeActionKind`. |
| Window / App switches | `AutomationRecorder.kt` | When handling `TYPE_WINDOW_STATE_CHANGED` for application windows, `V2CaptureBridge.onAppSwitch` is delivered with the target package and observed activity class (filtering out non-activity dialog classes and preserving the package's last real activity). |
| Hardware / Nav keys | `AutomationRecorder.kt` | In `onKeyEvent(ACTION_UP)`, BACK, HOME, and APP_SWITCH (RECENTS) trigger `V2CaptureBridge.onGlobalAction`. |

## Saving and Review Screen Integration

The recorder review screen (`RecorderScreen.kt`) and debug screen (`DebugControlActivity.kt`) use `selectSaveActionsJson(...)`:
```kotlin
fun selectSaveActionsJson(
    v2Enabled: Boolean,
    v2WorkflowJson: String?,
    isEdited: Boolean,
    v07ActionsJson: () -> String
): String
```
- If `EngineV2.enabled == true`, `v2WorkflowJson != null`, and `isEdited == false`: Persists the Engine v2 `WorkflowV2` JSON.
- If the recording was edited (actions deleted, reordered, or edited) or if v2 was disabled/unavailable: Falls back cleanly to v0.7 JSON format.
- In `DebugControlActivity`: Dumps write `filesDir/debug/last_recording_v2.json` alongside `last_recording.json`.

## Privacy and Logging Guidelines

All logs from the capture bridge:
- Use the tag `V2Capture`.
- Log only structural metadata: gesture kind, package name, node class, resource IDs, coordinates, and booleans.
- **Never log user-entered text or element text contents.**

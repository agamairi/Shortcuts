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

### Capture hardening

Finger-down snapshotting is started on a single dedicated capture worker, never in the overlay
input callback. At lift the overlay gives that immutable pre-tap snapshot at most 120 ms before
starting relay; a late result is tagged as late evidence and a missing result leaves the legacy
coordinate fallback intact. The worker receives compiler acceptance and persistence only after
`dispatchGesture` accepted the relay.

In-progress persistence uses a versioned JSONL event log plus an atomic session manifest. Each
record is fsynced; manifests and finished workflows are written via temp file, fsync and rename.
The cache is capped at 500 events / 2 MiB, stores only target selector context rather than whole
screens, accepts a truncated final JSONL record, and reports any earlier corruption as an
unrecoverable v2 session so the save path can use legacy recording safely.

IME inference is deliberately narrow: a committed non-empty focused field with a real node ID may
yield an `INFERRED_IME_ACTION` only for a same-package *different Activity*, or a one-event clear
of that same still-focused field. Dialogs, popups, content updates and progressive deletion are not
submission evidence. Explicit IME actions require the focused editable field.

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
| Text changes & field clearing | `AutomationRecorder.kt` | On `TYPE_VIEW_TEXT_CHANGED`, `AutomationRecorder` forwards the event to `V2CaptureBridge.onTextChanged`, updating `ImeSubmitInference` for field clearing (the "sent" pattern) and in-progress typing. |
| Explicit IME actions / Enter | `AutomationRecorder.kt` | In `resolveImeSubmitLabel` and `onKeyEvent`, `V2CaptureBridge.onImeAction` delivers explicit actions with mapped `ImeActionKind`. |
| Inferred IME actions | `ImeSubmitInference.kt` & `V2CaptureBridge.kt` | Infers soft-keyboard submissions (Search, Send, Go, Done) invisible to accessibility from subsequent activity transitions, new windows/dialogs, or field clearing within 2 seconds. Delivers `CapturedImeAction` ordered directly after the text commit. |
| Window / App switches | `AutomationRecorder.kt` | When handling `TYPE_WINDOW_STATE_CHANGED` for application windows, `V2CaptureBridge.onAppSwitch` is delivered with the target package and observed activity class (filtering out non-activity dialog classes and preserving the package's last real activity). Triggers IME inference if transitioning following a commit. |
| Hardware / Nav keys | `AutomationRecorder.kt` | In `onKeyEvent(ACTION_UP)`, BACK, HOME, and APP_SWITCH (RECENTS) trigger `V2CaptureBridge.onGlobalAction` and cancel any pending IME inference. |

### 4. Inferred IME Submissions (`ImeSubmitInference.kt`)
Soft keyboard action keys (such as Gboard's Search, Send, Go, or Done) communicate through Android's `InputConnection` rather than accessibility input filters, meaning no key events or accessibility clicks are reported. The capture overlay shrinks above the keyboard to avoid blocking user input.

`ImeSubmitInference` provides pure, testable inference that detects submissions from their effects:
- **Triggers**: Within 2 seconds of a `CapturedTextCommit` (or active typing), an application transition to a different activity, a new window/dialog for that package, or the field's text clearing triggers an inferred `CapturedImeAction`.
- **Action Kind Resolution**: Resolved from field IME options if exposed, field hint/label/resourceId semantics ("Search…" -> SEARCH, "Message…"/"Send" -> SEND), nearby button semantics (clickable "Send" / "Search" buttons), or the "sent" pattern on field clearing.
- **Strict Suppression**: Inference is suppressed if an overlay-captured tap or key event occurred in between, if only the soft keyboard hides (which Back also causes), or if the screen simply updates content on the same screen (autocomplete suggestions).
- **Ordering**: The inferred `CapturedImeAction` is delivered immediately following the text commit and preceding any subsequent activity switch or interaction.
- **Logging**: Logged to logcat under tag `V2Capture` as `Inferred ImeAction (reason=...)`.

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

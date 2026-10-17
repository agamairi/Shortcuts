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

### Capture hardening & Background Queue Ordering

All bridge ingestion (`onGestureClassified`, `onTextCommit`, `onImeAction`, `onAppSwitch`,
`onTextChanged`, `onKeyEvent`) is dispatched to a single background `captureExecutor` queue.
Synchronous full-window snapshots are never taken on the Android main thread, preventing UI stalls
during typing or rapid gestures while strictly preserving causal ordering between touch gestures
and keyboard/window events.

Finger-down snapshotting is scheduled asynchronously on this dedicated queue. At finger lift,
the overlay waits at most 120 ms for the pre-tap snapshot before initiating relay:
- If the snapshot is late or unavailable, legacy capture falls back to an immediate bounded
  live-tree hit-test at `ACTION_DOWN` (walking at most 300 nodes of the topmost non-overlay window)
  and falls back to the topmost application window package (`OverlayRegionPolicy.topAppPackage`),
  guaranteeing legacy v0.7 sessions never drop the tap due to an empty package name.
- When `snapshotFuture.isDone` is already true, relay begins immediately without waiting.

### Session Persistence & Compaction (`V2CaptureStore.kt`)

In-progress persistence uses a versioned JSONL event log plus an atomic session manifest:
- **Compaction Window Selection**: When no specific target node is specified (e.g. `appSwitch` or `globalAction`),
  compaction chooses the primary `APPLICATION` window (`type == WindowType.APPLICATION`), ensuring
  higher-layer system bars (e.g. `StatusBar`, `NavigationBar` with `type == WindowType.SYSTEM`) never wipe
  the application node hierarchy.
- **Row Context Retention**: Compaction preserves all siblings within the nearest row/container ancestor
  (`depth <= 2`), regardless of class name, ensuring label and control pairs survive across process restoration.
- **ScreenFingerprint Stability**: `ScreenFingerprint` is computed and stored before node compaction occurs,
  preventing structural hash drift between live recording and restored sessions.
- **Append Performance**: Event appends write strictly to the append-only `v2_capture_events.v1.jsonl` log,
  updating event byte and count tallies in memory rather than re-serializing the full `initialSnapshot`
  manifest on every keystroke or tap.

### IME Inference & Activity Validation

- **Stable Field Tracking**: Fields are matched across snapshots by `(packageName, resourceId, boundsInScreen)`
  rather than sequential `nodeId` counters, surviving layout shifts and dynamic hierarchy redraws.
- **Accidental Clear / Backspace Guard**: Inferring submission on field clearing requires committed text
  length $\ge 2$ and checks that no touch interaction landed inside the field's bounds (e.g. tapping a clear "X" icon).
- **Migration Warnings**: Inferred IME actions set `inferred = true`, prompting `RecordingCompiler` to attach
  the `"INFERRED_IME_ACTION"` migration warning on the compiled `UiStep`.
- **Activity Class Confirmation**: Observed activity classes from `TYPE_WINDOW_STATE_CHANGED` are verified via
  `PackageManager.getActivityInfo(ComponentName(pkg, cls), 0)` (memoized in an activity cache) and filtered
  against transient UI components (Dialog, Popup, Snackbar, Toast, BottomSheet) so transient views never
  corrupt the recorded activity.

### Disconnect & Lifecycle Cleanup

When `AutomationRecorder.stopForAccessibilityDisconnect` executes, any pending scroll finalizer is immediately
cancelled, and `V2CaptureBridge.stop(compileWorkflow = false)` purges active capture cache files without
compiling incomplete workflows.

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
| Inferred IME actions | `ImeSubmitInference.kt` & `V2CaptureBridge.kt` | Infers soft-keyboard submissions (Search, Send, Go, Done) invisible to accessibility from subsequent activity transitions, new windows/dialogs, field clearing, or — for apps that show results in place — focus/keyboard loss plus a screen change, within 2 seconds. Delivers `CapturedImeAction` ordered directly after the text commit. |
| Screen changes | `AutomationRecorder.kt` → `V2CaptureBridge.onScreenChanged` | `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOWS_CHANGED` / selection changes. **Coalesced**: at most one check is queued, and it only snapshots when an IME candidate or a pending app switch is waiting. (Snapshotting every content change backed the queue up until `stop()` timed out and the recording fell back to the legacy format.) |
| Window / App switches | `AutomationRecorder.kt` | When handling `TYPE_WINDOW_STATE_CHANGED` for application windows, `V2CaptureBridge.onAppSwitch` is delivered with the target package and observed activity class (filtering out non-activity dialog classes and preserving the package's last real activity). Triggers IME inference and Back inference. **Input-method packages are never app switches** (enabled IMEs, the default IME, and owners of `INPUT_METHOD` windows). |
| Physical keys | `AutomationAccessibilityService.onKeyEvent` → `AutomationRecorder.kt` | Only while recording. BACK, HOME and APP_SWITCH from *physical* keys trigger `V2CaptureBridge.onKeyEvent`. The key is credited to the input-focused window (`KeyEventTarget`), never the active window, which is RepeatKit's own capture overlay after every tap. **The on-screen Back button, the back gesture and adb-injected keys are injected events that skip the accessibility key filter and never arrive here** — see Inferred Back. |
| Inferred Back | `BackNavigationInference.kt` & `V2CaptureBridge.kt` | See §5. |

### 5. Inferred Back (`BackNavigationInference.kt`)
Android gives accessibility services no signal for the on-screen Back button or the back gesture. `BackNavigationInference` (pure, JVM-tested) keeps a per-package stack of confirmed activities seen during the recording and infers a Back when:
- the app returns, in the same package, to the activity directly below the top of its stack (a pop), both activities known;
- no tap, gesture, text commit, IME action or key-delivered Back happened within `QUIET_MS` (600 ms) before the change — so "tap Save, the app closes the screen" is not mistaken for Back.

The bridge then delivers `CapturedGlobalAction("BACK", inferred = true)` *before* the AppSwitch, using the last snapshot of the screen Back was pressed on (kept per screen in `lastSnapshotsByScreen`). The compiler tags the step `INFERRED_BACK`; it reads "Go back (detected)". At replay, an inferred Back whose after-screen is already in front (and whose before-screen is not) is skipped as already satisfied, so a mis-inference after a slow self-closing screen cannot navigate one screen too far. Dialog dismissals and keyboard hiding never produce an inferred Back.

### 4. Inferred IME Submissions (`ImeSubmitInference.kt`)
Soft keyboard action keys (such as Gboard's Search, Send, Go, or Done) communicate through Android's `InputConnection` rather than accessibility input filters, meaning no key events or accessibility clicks are reported. The capture overlay shrinks above the keyboard to avoid blocking user input.

`ImeSubmitInference` provides pure, testable inference that detects submissions from their effects:
- **Triggers**: Within 2 seconds of a `CapturedTextCommit` (or active typing), an application transition to a different activity, a new window/dialog for that package, or the field's text clearing triggers an inferred `CapturedImeAction`.
- **Submit by effect** (reason `focus_loss_with_screen_change`): for apps that show results on the same screen (YouTube Music search), infer when the field loses input focus or a previously visible keyboard goes away **and** the app's screen changes substantially (fingerprint change) or the field's text is replaced (Chrome shows the URL), given IME evidence (`IME_ENTER` action or field semantics) and no intervening input. Keyboard dismissal alone, suggestion updates, and taps never trigger it.
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

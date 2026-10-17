---
title: "Engine v2 integration review #1 (Codex SOL, 2026-10-17)"
type: research
author: codex_gpt_5_6_sol
tags: [review, engine-v2, safety, privacy]
---

## Findings

### Critical

1. Snapshot-local node IDs are treated as stable, allowing actions and verification against a different node.

   [AccessibilitySnapshotAdapter.kt:48](../../app/src/main/java/com/shortcuts/app/automation/android/AccessibilitySnapshotAdapter.kt:48) assigns sequential IDs anew on every snapshot. After matching, [ReplayStateMachine.kt:239](../../app/src/main/java/com/shortcuts/app/automation/replay/ReplayStateMachine.kt:239) immediately takes another snapshot and looks up the old `nodeId`; postconditions do the same at [ReplayStateMachine.kt:745](../../app/src/main/java/com/shortcuts/app/automation/replay/ReplayStateMachine.kt:745). [AndroidUiEnvironment.kt:160](../../app/src/main/java/com/shortcuts/app/automation/android/replay/AndroidUiEnvironment.kt:160) then accepts a live node based only on class and overlapping bounds.

   Failure scenario: a live list inserts a row between matching and dispatch. The old ID now identifies another same-class row at the same viewport position, so replay taps it. The same error can falsely satisfy `checked`, `selected`, or text verification and continue.

   Minimal fix: make IDs explicitly snapshot-generation-scoped. After every fresh snapshot, rerun the selector match and carry the newly accepted node; never resolve a prior snapshot’s ID in a later snapshot. Dispatch should also validate package/resource ID and selector identity, not only class/bounds.

2. Window matching deliberately waives the required identity threshold.

   [WindowMatcher.kt:58](../../app/src/main/java/com/shortcuts/app/automation/core/matching/WindowMatcher.kt:58) selects the sole package/type candidate regardless of confidence. [WindowMatcher.kt:117](../../app/src/main/java/com/shortcuts/app/automation/core/matching/WindowMatcher.kt:117) also allows a presumed dialog below the 0.80 threshold. The dialog heuristic uses the center of the recorded window bounds, not the recorded element point.

   Failure scenario: the expected app is open on the wrong activity, or an unexpected same-package confirmation dialog covers it. Because there is only one candidate of that type—or the dialog covers the screen center—the wrong window is accepted. A common selector such as “Delete” can then act on the wrong screen.

   Minimal fix: remove both confidence waivers. Always enforce `>= 0.80` and the ambiguity margin. An unexpected modal window should cause a precise mismatch unless the recorded `WindowSelector` identifies that dialog.

3. Destructive taps can be classified reversible and automatically replayed twice.

   [RecordingCompiler.kt:857](../../app/src/main/java/com/shortcuts/app/automation/capture/RecordingCompiler.kt:857) marks a tap irreversible only when text/content-description matches a short regex. Resource IDs are ignored, and verbs such as “Place order,” “Transfer,” “Publish,” and “Charge” are absent. After failed verification, [ReplayStateMachine.kt:286](../../app/src/main/java/com/shortcuts/app/automation/replay/ReplayStateMachine.kt:286) retries a “reversible” semantic tap as a gesture. Its safety check at [ReplayStateMachine.kt:768](../../app/src/main/java/com/shortcuts/app/automation/replay/ReplayStateMachine.kt:768) checks only fingerprint and two boolean fields—and itself uses unstable node IDs.

   Failure scenario: an icon-only `send_button` or “Place order” succeeds, but the server response takes over five seconds. The visible structure remains unchanged, so replay gestures the control again and submits twice.

   Minimal fix: default captured taps to non-retryable/BEST_EFFORT unless reversibility is positively established. Require explicit user classification for destructive actions. Never infer retry safety merely from unchanged structure, `checked`, and `selected`.

### High

4. Every captured finger-down can synchronously traverse 3,000 nodes on the main thread.

   [TouchCaptureOverlay.kt:228](../../app/src/main/java/com/shortcuts/app/service/capture/TouchCaptureOverlay.kt:228) calls the full snapshot adapter directly from `View.onTouchEvent`, then performs a second live-tree hit test at line 232. [AccessibilitySnapshotAdapter.kt:52](../../app/src/main/java/com/shortcuts/app/automation/android/AccessibilitySnapshotAdapter.kt:52) recursively reads many properties for up to 3,000 nodes. On finger-up, compilation and persistence run before relay begins at [TouchCaptureOverlay.kt:254](../../app/src/main/java/com/shortcuts/app/service/capture/TouchCaptureOverlay.kt:254).

   Failure scenario: a large WebView or Compose tree stalls input dispatch on every touch. Later in a long recording, serializing the accumulated session further delays relay, producing visible freezes or an ANR.

   Minimal fix: move serialized accessibility querying/compiler work to a dedicated worker with a strict time budget, cache immutable event-driven snapshots, and make touch handling retain only a cheap snapshot reference. Never perform file I/O before relaying the gesture.

5. Capture persistence is unbounded, repeatedly rewrites the whole history, and is not crash-safe.

   Each event copies the entire event list and saves it at [V2CaptureBridge.kt:456](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureBridge.kt:456). Every event embeds a full snapshot, and [V2CaptureStore.kt:95](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureStore.kt:95) truncates and rewrites one JSON file. Corruption is silently converted to `null` at [V2CaptureStore.kt:101](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureStore.kt:101). Restore also does not rebuild `lastForegroundPackage`, activity history, or the IME-inference candidate at [V2CaptureBridge.kt:198](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureBridge.kt:198).

   Failure scenario: process death during `writeText` leaves truncated JSON. The legacy recorder session restores as active, but the v2 compiler does not; saving later silently falls back to legacy. Even a valid restoration loses pending IME/app-transition context.

   Minimal fix: append bounded, versioned event records or compact compiler state; write via temp file + fsync + atomic rename; enforce size/event limits and report corruption. Persist and restore all bridge state that affects compilation.

6. The persisted capture cache exposes raw screen and typed content to backup/device transfer.

   Persisted events contain entire window snapshots and `finalText` at [CapturedInteraction.kt:10](../../app/src/main/java/com/shortcuts/app/automation/capture/CapturedInteraction.kt:10); nodes contain raw text, descriptions, hints, and state at [NodeSnapshot.kt:19](../../app/src/main/java/com/shortcuts/app/automation/core/tree/NodeSnapshot.kt:19). The application permits backup at [AndroidManifest.xml:35](../../app/src/main/AndroidManifest.xml:35), but [full_backup_content.xml:2](../../app/src/main/res/xml/full_backup_content.xml:2) and [data_extraction_rules.xml:2](../../app/src/main/res/xml/data_extraction_rules.xml:2) do not exclude `files/v2_capture`.

   Failure scenario: cloud backup runs while recording or awaiting review and uploads typed messages, node text from other apps, window titles, and URLs.

   Minimal fix: immediately exclude `v2_capture/` from cloud backup and device transfer. Persist only the minimal redacted state needed for recovery; encrypt any unavoidable entered text.

7. Both legacy and v2 IME inference can invent submissions.

   V2 treats a cleared field as submission, including an unconditional match when identifiers are absent, at [ImeSubmitInference.kt:217](../../app/src/main/java/com/shortcuts/app/automation/android/capture/ImeSubmitInference.kt:217). The bridge deliberately passes a null node ID at [V2CaptureBridge.kt:386](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureBridge.kt:386). Any new same-package dialog/window can also trigger inference at [ImeSubmitInference.kt:180](../../app/src/main/java/com/shortcuts/app/automation/android/capture/ImeSubmitInference.kt:180). Explicit IME capture falls back to the first editable field when no field is focused at [V2CaptureBridge.kt:312](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureBridge.kt:312). Legacy capture still converts ordinary content/window changes into Enter at [RecorderSession.kt:465](../../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt:465).

   Failure scenario: the user manually clears a field, validation clears it, or an autofill/error dialog appears within two seconds. The saved shortcut gains an IME action the user never performed; after an edited recording falls back to legacy, autocomplete content changes can do the same.

   Minimal fix: remove effect-only synthesis. Record IME submission only from explicit labeled IME/key evidence tied to the exact focused editable node; otherwise emit an unresolved/manual-review marker. Delete the legacy content/window-change inference.

8. Manual-builder edits to existing v2 steps are silently discarded.

   For an existing step, [ShortcutSteps.kt:267](../../app/src/main/java/com/shortcuts/app/data/ShortcutSteps.kt:267) simply reinserts the original step; only `WaitStep` duration is updated. Manual Builder invokes this encoder when saving/running at [ManualBuilderScreen.kt:639](../../app/src/main/java/com/shortcuts/app/ui/screens/ManualBuilderScreen.kt:639).

   Failure scenario: the user changes recorded text from an old secret/message to a new value, saves, and later replay still enters the old value. Target, app, action type, and other visible edits are likewise ignored.

   Minimal fix: map every supported edited `Action` field back into the corresponding typed v2 step, with validation. Until that exists, make unsupported v2 fields read-only and clearly disclose it.

9. Editing a recording silently downgrades the entire workflow to v0.7.

   [V2CapturePure.kt:115](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CapturePure.kt:115) returns legacy JSON whenever the projected action list changes. Recorder review uses that path at [RecorderScreen.kt:285](../../app/src/main/java/com/shortcuts/app/ui/screens/RecorderScreen.kt:285).

   Failure scenario: deleting or reordering one step removes every v2 window selector, fingerprint, safety marker, and verification rule. Execution then intentionally routes the resulting array through the old engine, with no warning.

   Minimal fix: edit/reorder/delete the v2 steps by stable IDs and retain v2 JSON. If an operation cannot be represented, block it or require an explicit downgrade confirmation.

### Medium

10. Recorded window-title tokens can never match during replay.

   Capture uses a normalized, per-install salted token at [V2CaptureBridge.kt:81](../../app/src/main/java/com/shortcuts/app/automation/android/capture/V2CaptureBridge.kt:81) and [SelectorExtractor.kt:74](../../app/src/main/java/com/shortcuts/app/automation/capture/SelectorExtractor.kt:74). Replay uses unsalted SHA-256 over the raw title at [EngineV2RunRuntime.kt:40](../../app/src/main/java/com/shortcuts/app/automation/android/replay/EngineV2RunRuntime.kt:40).

   Failure scenario: two same-package windows need their title token to disambiguate. Both observed tokens fail, reducing confidence or causing ambiguity; the confidence-waiver bugs can instead choose the wrong one.

   Minimal fix: use one shared tokenizer that loads the same per-install key and applies identical normalization in capture and replay.

11. Deep-link URLs are logged in release builds on launch failure.

   [AndroidAppLauncher.kt:60](../../app/src/main/java/com/shortcuts/app/automation/android/replay/AndroidAppLauncher.kt:60) logs `${step.target}` and the exception without a debug guard. `LaunchTarget.DeepLink.toString()` includes its raw URI.

   Failure scenario: a private reset/auth/document URL has no handler or triggers a security exception; the complete URL and possibly exception message enter Logcat/bugreports.

   Minimal fix: log only target type and package, with a generic error code. Never stringify the target, intent, URI, or exception message.

12. Coroutine cancellation interrupts a potentially unrelated pooled thread.

   Execution runs on `Dispatchers.IO` at [AutomationExecutionService.kt:30](../../app/src/main/java/com/shortcuts/app/service/AutomationExecutionService.kt:30). [ShortcutRunner.kt:656](../../app/src/main/java/com/shortcuts/app/service/ShortcutRunner.kt:656) remembers the thread only once and interrupts it on cancel. After a coroutine suspension, execution may resume on a different worker; `leaveExecution` can fail to clear the stale reference.

   Failure scenario: Stop interrupts the original IO worker after it has begun unrelated database/file work, while the replay coroutine stops only at its next cooperative check.

   Minimal fix: cancel the execution coroutine `Job` and rely on structured cancellation. Keep thread interruption only around explicitly owned legacy blocking calls, clearing it in the same thread’s `finally`.

## Areas found clean

- The runtime decode boundary is fail-loud: valid v2 objects run v2, legacy arrays explicitly return to the legacy caller, and malformed v2 does not silently execute as v0.7 ([EngineV2RunRuntime.kt:154](../../app/src/main/java/com/shortcuts/app/automation/android/replay/EngineV2RunRuntime.kt:154)). The silent downgrade occurs earlier in recorder editing, as noted above.
- Normal-path node ownership/recycling is sound: snapshots are immutable values, live nodes are not retained across waits, intermediate path nodes and dispatched nodes are recycled below API 33. Android documents service-delivered nodes as immutable and recycling as obsolete/no-op from API 33 onward. [Android AccessibilityNodeInfo reference](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
- I found no ordinary stop/discard/disconnect path that leaves `TouchCaptureOverlay` attached: callbacks are removed and the view is removed at [TouchCaptureOverlay.kt:104](../../app/src/main/java/com/shortcuts/app/service/capture/TouchCaptureOverlay.kt:104), recorder stop invokes it, and accessibility destruction invokes it. Process death also removes process-owned windows.
- When a step is correctly marked `IRREVERSIBLE`, the state machine does suppress its relocation and verification retries. The correctness gap is the capture-side classification.
- V2 trace event types omit entered/node text, and legacy in-memory candidate traces redact text shapes. I found no raw typed/node text in the replay trace schema itself; the privacy defects are the capture cache/backup and deep-link Logcat path.
- V2 reordering/removal by unique `v2StepId` is deterministic. The defect is that field edits are ignored and recorder-review edits change the schema/engine.

This was a static, read-only review; no Gradle, ADB, or state-changing Git commands were run.
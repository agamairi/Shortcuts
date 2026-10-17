---
title: "Engine v2 — record→replay audit and specification"
type: architecture
author: codex_gpt_5_6_sol
tags: [engine-v2, recorder, replay, selectors, matching, spec]
---

> Source of truth for the Engine v2 rebuild (2026-10-17). Part 1 is the audit of the v0.7.4 pipeline; Part 2 is the spec every work package implements. Capture-side design (how touches are obtained) lives in `research/touch-capture.md`. Line references point at the v0.7.4 code (commit b0b27ea).

# RepeatKit record→replay audit and Engine v2 specification

## Executive finding

The current pipeline does not fail because of one recorder bug. Its contract is fundamentally too weak:

1. Recording reduces an interaction to a flat label/ID/class/coordinate tuple.
2. Replay searches only `rootInActiveWindow`.
3. It accepts whichever substring candidate has the largest score—even score `0`, with no ambiguity check.
4. It calls an accessibility action and treats a returned `true` as completion.
5. It has no model of the expected window, screen, navigation state, scroll destination, or observable postcondition.

That combination explains both reported symptoms: steps are missing or malformed during recording, and even apparently valid steps are replayed against the wrong app state, window, row, or ancestor.

---

# Part 1 — Failure-mode audit

Severity:

- **Critical:** can silently act on the wrong control/app or makes ordinary recorded flows structurally unreplayable.
- **High:** commonly stops otherwise normal flows.
- **Medium:** affects specific layouts/frameworks or produces misleading results.
- **Low:** compatibility, diagnostics, or uncommon corruption path.

## Ranked defects

| Rank | Defect and evidence | Concrete scenario | Severity |
|---:|---|---|---|
| 1 | **The starting app/screen is not deterministic.** An app step uses only `getLaunchIntentForPackage()` plus `FLAG_ACTIVITY_NEW_TASK`, which brings an existing task to the foreground with its prior back stack. It returns success immediately, with no package/screen wait. See [ActionExecutorService.kt:311–323](../app/src/main/java/com/shortcuts/app/service/ActionExecutorService.kt). Recorded app launches contain only a package and a 500 ms delay; no component, activity, URI, or entry-state contract is stored. See [RecorderSession.kt:296–304](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | Record Settings from its root, then leave Settings on “Battery.” Widget replay resumes “Battery”; the next “Network & internet” step searches the wrong tree. | **Critical** |
| 2 | **Many recordings contain no launch step at all.** The first foreground package merely establishes a baseline. Returning from Home to the same app deliberately discards the launch; only a transition classified as `launchedFromHome` produces `APP_INTENT`. See [RecorderSession.kt:267–312](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | Start recording while Chrome is already open, record a search, save, then run from a widget while Maps is foreground. The first UI step waits for a Chrome field in Maps. The same occurs when recording Home → return to the app that was already foreground. | **Critical** |
| 3 | **Matching accepts substring matches without a confidence floor or ambiguity rejection.** Candidate discovery uses `findAccessibilityNodeInfosByText()` and case-insensitive `contains()` traversal. It then unconditionally chooses `maxBy`; there is no minimum score and no best-versus-second margin. See [AutomationAccessibilityService.kt:673–745](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt) and [AutomationAccessibilityService.kt:794–819](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | Record “Internet.” On replay only “Internet & SIM” is visible. It is admitted as a substring candidate and can win even with score `0`; replay clicks it rather than reporting that “Internet” is missing. Two identical “Delete” rows are resolved by traversal order rather than rejected as ambiguous. | **Critical** |
| 4 | **The scoring function favors generic class over identity and has no mismatch penalties.** Exact class is +100, exact ID +50, exact text/description +50, position at most +40. Wrong text, wrong ID, hidden state, disabled state, and lack of the requested action are not penalized. See [AutomationAccessibilityService.kt:756–791](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | A recorded button class matches “Internet & SIM,” while the exact “Internet” match is exposed as a different class. The class match can outweigh exact text. Generic Compose `Button`/Flutter classes make this particularly weak. | **Critical** |
| 5 | **`targetNodeId` is incorrectly treated as text and substring data.** It is first used for exact ID lookup, then added to text search and traversal, where IDs are compared using `contains()`. See [AutomationAccessibilityService.kt:683–703](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | If `com.app:id/item` disappears, `com.app:id/item_subtitle` or visible text containing the identifier can become a candidate and be accepted instead of producing an ID miss. | **High** |
| 6 | **Replay selects only `rootInActiveWindow`, despite already requesting interactive-window access.** `getRootNode()` returns `rootInActiveWindow`; all semantic lookup begins there. `windows` is consulted only to detect an IME during coordinate validation. See [AutomationAccessibilityService.kt:251](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt), [AutomationAccessibilityService.kt:673–674](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt), and [AutomationAccessibilityService.kt:907–924](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). The service already enables `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` at [AccessibilityServiceConfiguration.kt:11–20](../app/src/main/java/com/shortcuts/app/service/AccessibilityServiceConfiguration.kt). | A dialog, popup, IME, or the installed Dynamic Island accessibility overlay becomes the active window. RepeatKit either searches that root and times out, or—on a legacy action without `targetPackageName`—can match a same-worded overlay control. It cannot deliberately select the application window behind a non-modal overlay. | **Critical** on affected devices |
| 7 | **Clicking is not equivalent to the recorded physical tap.** Replay walks up to 25 parents and calls `ACTION_CLICK` on the nearest clickable ancestor. If none is clickable, it still calls `ACTION_CLICK` on the original non-clickable node. It does not gesture-tap the matched node when `ACTION_CLICK` fails. See [AutomationAccessibilityService.kt:473–514](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | A label inside a clickable RecyclerView row resolves correctly, but its nearest clickable ancestor is the entire row rather than the trailing action icon the user touched. In a WebView/Flutter surface, a semantic node is found but rejects `ACTION_CLICK`; the valid physical tap fallback is never attempted because fallback happens only when no node is found. | **Critical** |
| 8 | **No UI action is verified.** A `true` return from `performAction()` or a completed gesture becomes `StepResult.Success`; there is no state change, screen change, target-state, focus, or text verification. See [AutomationAccessibilityService.kt:294–308](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt), [ActionExecutorService.kt:432–439](../app/src/main/java/com/shortcuts/app/service/ActionExecutorService.kt), and [ReplayUiActionExecutor.kt:36–43](../app/src/main/java/com/shortcuts/app/service/ReplayUiActionExecutor.kt). | `ACTION_CLICK` returns true while the app ignores it or shows a loading state. RepeatKit immediately executes the next step and reports the click as successful. Gesture completion only proves Android delivered the gesture, not that the target responded. | **Critical** |
| 9 | **Timing waits for generic quiet, not for the screen a step expects.** Target lookup has a fixed 5-second budget and 150 ms polling. `waitForScreenToSettle()` merely waits for 300 ms without any accessibility event and is capped at one second inside lookup. See [AutomationAccessibilityService.kt:30–46](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt) and [AutomationAccessibilityService.kt:558–625](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). Recorded 500/700 ms delays are also inconsistent: UI delays are interpreted as a maximum settle wait and may return immediately, while non-UI delays sleep. See [ActionExecutorService.kt:70–78](../app/src/main/java/com/shortcuts/app/service/ActionExecutorService.kt). | A screen becomes quiet for 300 ms while still showing a skeleton, then loads at 1.2 s. Replay searches the wrong screen. Conversely, a live ticker never becomes quiet, although the expected target has been visible the entire time. | **High** |
| 10 | **Scrolling reproduces neither distance nor intent.** A replayed scroll performs exactly one forward/backward accessibility action. If its selector misses, replay takes the first depth-first scrollable node. It never scrolls until the next target becomes visible and never detects no progress. See [AutomationAccessibilityService.kt:418–456](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt) and [AutomationAccessibilityService.kt:846–868](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | Record three swipes down a Settings list to “Developer options.” Recording collapses the burst; replay scrolls one page in the first scrollable container and then cannot find the target. In a nested carousel/list layout it may scroll the carousel instead. | **High** |
| 11 | **Two id-less text fields are treated as the same field and can overwrite one another.** Text identity is only package + view ID + class. Consecutive `TYPE_TEXT` actions with those same values replace the prior action. See [RecorderSession.kt:314–326](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt) and [RecorderSession.kt:603–607](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | Enter first and last name into two Compose `TextField`s. Both expose no resource ID and the same class, so the recorder buffers/replaces them as one field; replay types only the last value. | **Critical** |
| 12 | **Recorded selectors omit the structural information required to disambiguate real screens.** `Action` stores only text, description, ID, class, package, display point, and display metrics—no ancestor path, sibling index, row context, window type/title, activity, complete bounds, or screen fingerprint. See [Automation.kt:22–56](../app/src/main/java/com/shortcuts/app/data/Automation.kt). `windowId` is present in `RecorderEvent` but is discarded when actions are built. See [AutomationRecorder.kt:366–386](../app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt) and [RecorderSession.kt:366–420](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | A list has ten “Internet” or “Edit” controls. The row’s account name, ancestor IDs, sibling position, and dialog identity are all lost, leaving replay unable to select the recorded row reliably. | **High** |
| 13 | **Label derivation can describe a different node from the node whose class and bounds are stored.** If the event source has any ID/text/description, traversal stops—even an ID-only parent loses its useful child label. Otherwise, breadth-first search returns the first labeled descendant, independent of the hit point or row semantics. See [AutomationRecorder.kt:843–904](../app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt). The derived descendant ID/text is then combined with the original source node’s class and bounds. See [AutomationRecorder.kt:331–377](../app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt). | Tap a row whose first accessibility child is the title and whose trailing child is the switch actually touched. The action can record the title child’s text/ID, the row’s class, and the row centre. Those signals point to different elements. | **High** |
| 14 | **Coordinate replay is simultaneously too strict and too loose.** It rejects every coordinate when any IME window exists and requires the current app window to equal the physical display exactly, excluding dialogs, popup windows, split screen, freeform, and many inset layouts. See [AutomationAccessibilityService.kt:97–106](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt) and [AutomationAccessibilityService.kt:135–140](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). Conversely, `recordedDensityDpi` is stored but never checked; validation reads only width, height, and rotation. See [AutomationAccessibilityService.kt:79–127](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | With the Pixel’s density changed between 420 and 356, physical pixel dimensions can remain unchanged while controls move; the unsafe coordinate passes. A valid button above an open keyboard is refused merely because an IME window exists. | **High** |
| 15 | **Passive capture records a node centre, not the user’s point.** `screenX/Y` come from the source bounds’ centre. See [AutomationRecorder.kt:353–377](../app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt). | A WebView exposes one node covering a whole card with two touch regions. Both taps record the same centre; coordinate fallback cannot reproduce which region was touched. | **High**, addressed by the assumed new capture source |
| 16 | **Enter capture invents submissions from ordinary content/window changes.** After text has been quiet for 400 ms, any same-package content change can call `commitTextAndSubmitLocked()`. A same-package window-state event does so immediately. See [RecorderSession.kt:449–476](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | Type in a search field; autocomplete refreshes after 400 ms. The recorder appends `PRESS_ENTER` even though the user never submitted. The actual later search key can then produce another submit or be lost after the candidate was cleared. | **High** |
| 17 | **Text replay can choose a focusable non-editor and never establishes or verifies input focus.** `findEditableNode()` accepts `isEditable || isFocusable`, recursively returning the first match. `ACTION_SET_TEXT` is sent without `ACTION_FOCUS`, and the result/text is not re-read. See [AutomationAccessibilityService.kt:382–406](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt) and [AutomationAccessibilityService.kt:821–843](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). | A container contains a focusable clear icon before its EditText. Replay selects the icon as the “editable” descendant, or sets text in an unfocused field while the IME remains attached to another field. | **High** |
| 18 | **`PRESS_ENTER` does not require the located field to be focused.** On API 30+, it sends `ACTION_IME_ENTER` to whichever selector candidate won; Android exposes that action for the focused editable field. Before API 30, it merely clicks the field, which focuses it rather than submitting it. See [ReplayUiActionExecutor.kt:51–70](../app/src/main/java/com/shortcuts/app/service/ReplayUiActionExecutor.kt). | Two similar search boxes exist. Matching chooses the inactive one; `ACTION_IME_ENTER` fails. On Android 8–10, the “Enter” step clicks the field and the form never submits. | **High** on minSdk devices; **Medium** on Android 16 |
| 19 | **Recorder package filtering creates both missing steps and overlay pollution.** System UI, permission controllers, package installers, intent resolver, and Google Play Services are unconditionally ignored; arbitrary third-party overlay packages are not. See [RecorderSession.kt:546–584](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | A permission/chooser interaction needed every run is omitted. Meanwhile, an interactive Dynamic Island overlay can be interpreted as a foreground package change, flush a text/scroll session, or contribute its own click step. | **High** on affected flows |
| 20 | **App-switch classification recognizes only launcher-driven launches.** Transitions via Recents, notifications, links, chooser activities, or another app update the foreground package but do not create a launch step. Activity class is logged but discarded because `APP_CHANGE` is converted to an empty package-only event. See [AutomationRecorder.kt:266–291](../app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt), [AutomationRecorder.kt:696–708](../app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt), and [RecorderSession.kt:292–304](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | Record switching from Gmail to Chrome through Recents. No Chrome launch step is stored; replay assumes the preceding action will somehow produce the transition. | **High** |
| 21 | **Scroll capture destroys gesture magnitude and sometimes the gesture itself.** A whole burst becomes one direction-only `SCROLL`; no path, duration, distance, axis, or destination is retained. See [RecorderSession.kt:496–528](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt) and [RecorderSession.kt:555–565](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). While an editor is focused, a scroll from another target in the same window is discarded. See [RecorderSession.kt:530–533](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | User dismisses the keyboard by scrolling a form, then scrolls a list to the next field. The list scroll may be suppressed; if retained, several swipes become one page-forward action. | **High** |
| 22 | **The click debounce suppresses legitimate rapid repetition.** Two clicks with the same flat key inside 350 ms become one step. See [RecorderSession.kt:535–543](../app/src/main/java/com/shortcuts/app/service/RecorderSession.kt). | Double-tap a media control or increment button. Replay performs only one tap. | **Medium** |
| 23 | **“Mark a tap” records a different flow from the one the user performs.** Its full-screen overlay consumes the mark and explicitly does not relay it. The user must tap the application again to continue. See [TapMarkOverlay.kt:28–46](../app/src/main/java/com/shortcuts/app/service/TapMarkOverlay.kt) and [TapMarkOverlay.kt:173–252](../app/src/main/java/com/shortcuts/app/service/TapMarkOverlay.kt). Hit-testing also starts from the single active root and returns the first containing child path, not necessarily the topmost node at that point. See [TapMarkOverlay.kt:313–374](../app/src/main/java/com/shortcuts/app/service/TapMarkOverlay.kt). | In an app that does emit accessibility clicks, “Mark a tap” adds one step without navigating; the user’s second real tap navigates and may add a second step. In a silent Compose app, the real second tap changes the screen but is not captured. | **Medium**, superseded by the planned touch capture |
| 24 | **Long-press has its own independent weak matching and no parent-aware semantic action.** It waits with a separate 33×150 ms loop, performs `ACTION_LONG_CLICK` only on the matched node, then gestures at its centre. It does not select the correct window or verify the result. See [ReplayUiActionExecutor.kt:27–48](../app/src/main/java/com/shortcuts/app/service/ReplayUiActionExecutor.kt) and [ReplayUiActionExecutor.kt:82–95](../app/src/main/java/com/shortcuts/app/service/ReplayUiActionExecutor.kt). | The label child matches but the row parent owns the long-click action; replay gestures at the label centre, which may fall outside the row’s actual long-press hotspot. | **Medium** |
| 25 | **`preferCoordinateReplay` is persisted and exposed but ignored by production replay.** The model defines it at [Automation.kt:54–56](../app/src/main/java/com/shortcuts/app/data/Automation.kt), while click execution always runs semantic lookup first at [AutomationAccessibilityService.kt:473–480](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt). The test expects coordinate-first behavior at [AutomationAccessibilityServiceReplayTest.kt:20–72](../app/src/test/java/com/shortcuts/app/service/AutomationAccessibilityServiceReplayTest.kt), contradicting the implementation. | A repaired step configured to use its known-good coordinate still performs the unreliable text match and clicks a same-worded element. | **Medium** |
| 26 | **Malformed/unsupported persistence is not a fail-loud contract.** JSON exceptions become an empty action list; unknown action enums become `WAIT`. See [Automation.kt:72–93](../app/src/main/java/com/shortcuts/app/data/Automation.kt). Saved runs deserialize only after the foreground service starts, and the saved-shortcut path does not run `ShortcutPreflight`. See [AutomationExecutionService.kt:58–87](../app/src/main/java/com/shortcuts/app/service/AutomationExecutionService.kt) and [ShortcutRunner.kt:654–681](../app/src/main/java/com/shortcuts/app/service/ShortcutRunner.kt). | A partial/corrupt recorded JSON payload becomes an empty workflow or an invalid wait rather than “schema decode failed at step 4.” | **Low–Medium** |

## What the current tests miss

The tests mostly lock in implementation mechanics, not replay correctness:

- Substring traversal is explicitly treated as success in [AutomationAccessibilityServiceTest.kt:125–141](../app/src/test/java/com/shortcuts/app/service/AutomationAccessibilityServiceTest.kt).
- The 25-level parent-click behavior is tested as desirable in [AutomationAccessibilityServiceTest.kt:185–204](../app/src/test/java/com/shortcuts/app/service/AutomationAccessibilityServiceTest.kt).
- Scroll tests verify one `ACTION_SCROLL_*`, not that the intended next target becomes visible, at [AutomationAccessibilityServiceTest.kt:207–240](../app/src/test/java/com/shortcuts/app/service/AutomationAccessibilityServiceTest.kt).
- The matcher test verifies only that class and position choose one candidate; there are no low-confidence or ambiguity cases. See [AutomationAccessibilityServiceTest.kt:425–469](../app/src/test/java/com/shortcuts/app/service/AutomationAccessibilityServiceTest.kt).
- There are no multi-window, dialog-versus-overlay, wrong-screen, postcondition, near-identical-row, or end-to-end record→replay tests.
- The coordinate-first test and production implementation currently disagree, as noted above.

---

# Part 2 — Engine v2 specification

## 1. Versioned data model

Store one versioned JSON object in the existing `Automation.actionsJson` `TEXT` column:

```kotlin
data class WorkflowV2(
    val schemaVersion: Int = 2,
    val workflowId: String,
    val recordedDevice: RecordedDevice,
    val steps: List<Step>
)

sealed interface Step {
    val id: String
    val timeoutMs: Long
    val expectedBefore: ScreenFingerprint?
    val expectedAfter: ScreenFingerprint?
    val capturedDelayFromPreviousMs: Long?
}

data class LaunchAppStep(
    override val id: String,
    val target: LaunchTarget,
    val policy: LaunchPolicy,
    val expectedWindow: WindowSelector,
    override val timeoutMs: Long = 12_000,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint?,
    override val capturedDelayFromPreviousMs: Long?
) : Step

data class UiStep(
    override val id: String,
    val operation: UiOperation,
    val expectedWindow: WindowSelector,
    val element: ElementSelector?,
    val gesture: CapturedGesture?,
    val reveal: RevealPolicy? = null,
    val verification: VerificationSpec,
    val safety: StepSafety = StepSafety.REVERSIBLE,
    override val timeoutMs: Long = 7_000,
    override val expectedBefore: ScreenFingerprint?,
    override val expectedAfter: ScreenFingerprint?,
    override val capturedDelayFromPreviousMs: Long?
) : Step

data class WaitStep(
    override val id: String,
    val durationMs: Long,
    override val timeoutMs: Long = durationMs,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint? = null,
    override val capturedDelayFromPreviousMs: Long? = null
) : Step

data class LegacyActionStep(
    override val id: String,
    val action: Action,
    val migrationWarnings: List<String>,
    override val timeoutMs: Long = 7_000,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint? = null,
    override val capturedDelayFromPreviousMs: Long? = null
) : Step
```

Core selector types:

```kotlin
data class ElementSelector(
    val strategies: List<LocatorStrategy>, // ascending rank: 0 is preferred
    val expectedClass: String?,
    val expectedRole: NodeRole?,
    val ancestorChain: List<AncestorSelector>, // nearest parent first, max 6
    val siblingIndex: Int?,
    val rowContext: RowContext?,
    val boundsInWindow: NormalizedRect,
    val rawPoint: CapturedPoint?,
    val requiredActions: Set<NodeAction>,
    val scrollContainer: ElementSelector? = null
)

data class LocatorStrategy(
    val rank: Int,
    val kind: LocatorKind,
    val value: String,
    val stability: LocatorStability
)

enum class LocatorKind {
    RESOURCE_ID_EXACT,
    TEXT_EXACT,
    CONTENT_DESCRIPTION_EXACT,
    TEST_TAG_EXACT,
    WEB_DOM_ID_EXACT
}

data class AncestorSelector(
    val resourceId: String?,
    val className: String,
    val role: NodeRole?,
    val siblingIndex: Int?
)

data class RowContext(
    val containerClass: String?,
    val containerResourceId: String?,
    val siblingText: List<String>,
    val siblingContentDescriptions: List<String>,
    val collectionRow: Int?,
    val collectionColumn: Int?
)

data class WindowSelector(
    val packageName: String,
    val observedActivityClass: String?,
    val windowType: WindowType,
    val titleToken: String?,       // salted hash, never raw title
    val boundsOnDisplay: NormalizedRect,
    val displayId: Int,
    val fingerprint: ScreenFingerprint?
)

data class CapturedPoint(
    val displayPx: IntPoint,
    val pointInWindow: NormalizedPoint,
    val pointInNode: NormalizedPoint?,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val rotation: Int,
    val densityDpi: Int
)

data class CapturedGesture(
    val kind: GestureKind,
    val durationMs: Long,
    val points: List<TimedPoint>,  // normalized to selected window
    val pointerCount: Int = 1
)

data class ScreenFingerprint(
    val version: Int = 1,
    val packageName: String,
    val activityClass: String?,
    val windowType: WindowType,
    val structuralTokens: Set<Long>,
    val landmarkTokens: Set<Long>,
    val structureHash: Long
)
```

`UiOperation` must distinguish `TAP`, `LONG_PRESS`, `SWIPE`, `SET_TEXT`, and `IME_ACTION`. `IME_ACTION` stores the recorded semantic action (`SEARCH`, `SEND`, `DONE`, `NEXT`, `GO`, or `UNSPECIFIED`), not merely “Enter.”

### Persistence and migration

No Room schema bump is required because `actionsJson` remains a string:

```json
{
  "schemaVersion": 2,
  "workflowId": "...",
  "recordedDevice": { "...": "..." },
  "steps": [ ... ]
}
```

Replace `ActionConverter` with `WorkflowCodec`:

1. Inspect the JSON root token.
2. Object with `schemaVersion == 2` → strict `WorkflowV2` decode.
3. Array root → decode legacy `List<Action>` and pass through `LegacyWorkflowMigrator`.
4. Unknown version, malformed JSON, or unknown required enum → return `DecodeFailure`, never an empty list.
5. Rewrite to v2 only when the shortcut is next saved or through an explicit transactional migration; do not mutate on read.

Legacy coordinate actions become `DISPLAY_LEGACY` coordinates because recorded window bounds are unavailable. They retain the existing full-display safety restriction. Legacy text-only or ambiguous actions receive migration warnings surfaced before execution.

Non-UI actions can initially remain `LegacyActionStep`; migrate them into first-class step types later without blocking UI Engine v2.

---

## 2. Replay algorithm

### A. Snapshot and window selection

For every step:

1. Enumerate `AccessibilityService.getWindows()`/`getWindowsOnAllDisplays()`, already available because the service requests interactive-window access. Android reports these in descending Z-order and exposes each window’s root, layer, focus, active state, type, title, and bounds. [Android accessibility window documentation](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
2. Exclude `TYPE_ACCESSIBILITY_OVERLAY` and `TYPE_INPUT_METHOD` unless the recorded step explicitly expects that type.
3. Hard-reject windows whose root package differs from `WindowSelector.packageName`.
4. Score remaining windows using the recorded applicable evidence:

   - Activity class: 30
   - Window type: 15
   - Salted title token: 10
   - Normalized bounds: 10
   - Screen-fingerprint similarity: 35

   `windowConfidence = matchedWeight / applicableWeight`.

5. Require `windowConfidence >= 0.80` and a margin of at least `0.15` over the second window. Otherwise fail with `WINDOW_NOT_FOUND` or `WINDOW_AMBIGUOUS`.
6. Use Z-order only as the final tie-breaker, never as identity.

This prevents a Dynamic Island overlay, IME, popup, or unrelated dialog from silently becoming the search root.

### B. Expected-screen wait

Use a bounded event-driven state machine:

```text
snapshot immediately
while deadline not reached:
    if expected window exists and screen fingerprint >= 0.80:
        require either:
          - same fingerprint in two snapshots >=100 ms apart, or
          - 300 ms without a relevant event
        proceed
    await next WINDOW_STATE / WINDOWS_CHANGED / WINDOW_CONTENT /
          VIEW_SCROLLED / TEXT_CHANGED event
    safety-poll after 250 ms if no event arrived
fail EXPECTED_SCREEN_TIMEOUT with observed package/activity/fingerprint summary
```

Recommended defaults:

- App launch: 12 seconds.
- Pre-step screen: 7 seconds.
- Post-click/navigation: 5 seconds.
- Text/focus verification: 2 seconds.
- Scroll-to-find: 10 seconds or 12 scroll attempts.

Captured human pauses are diagnostic hints only. Replay delays happen only for explicit `WaitStep`s.

### C. Candidate location

Do not call substring text search. Traverse the selected window’s immutable snapshot once and index:

- exact resource ID;
- exact normalized text;
- exact content description;
- class/role;
- parent and sibling relationships;
- collection row/column;
- normalized bounds.

`targetNodeId` is never used as text.

Hard candidate filters:

- correct selected window and package;
- visible to user;
- enabled;
- non-empty bounds intersecting the visible window;
- editable for text;
- scrollable for a scroll container;
- requested action exposed, or gesture fallback is allowed.

### D. Exact element scoring

Start with the strongest matching anchor:

| Anchor | Points |
|---|---:|
| Exact full resource ID | 70 |
| Exact content description | 65 |
| Exact normalized text | 60 |
| ≥80% ancestor-chain match plus terminal class | 55 |
| Exact row context plus terminal class | 55 |
| Coordinate proximity alone | 35 |

Add corroboration:

- Exact class/role: +8
- Ancestor-chain similarity: `round(8 × similarity)`
- Row-context Jaccard similarity: `round(8 × similarity)`
- Exact sibling index: +5
- Bounds proximity: `round(6 × (1 - min(1, normalizedDistance / 0.25)))`
- Requested action supported: +5
- Each exact secondary text/description locator: +4
- Cap at 100.

Subtract contradictions:

- Different non-null resource ID: −35
- Mismatch of a locator marked `STABLE`: −15 each
- Class/role mismatch: −15
- Ancestor similarity below 0.5: −15
- Row-context similarity below 0.5: −10
- Candidate centre outside recorded bounds expanded by 15%: −10

Decision:

- Best score `< 75` → `TARGET_LOW_CONFIDENCE`.
- Second score `>= 75` and `best - second < 12` → `TARGET_AMBIGUOUS`.
- Otherwise accept the best candidate.

The trace records every component of each score.

### E. Scroll-to-find

A scroll is normally a reveal policy for the next target, not a blind independent action:

1. Try to locate the target.
2. Locate its recorded scroll container using the same strict matcher.
3. Prefer `ACTION_SHOW_ON_SCREEN` when an off-screen target node is present.
4. Otherwise use directional/page accessibility actions exposed by that exact container.
5. If unavailable, dispatch the recorded normalized swipe inside the container’s current visible bounds.
6. Await `TYPE_VIEW_SCROLLED` or a fingerprint change.
7. Re-locate the target.
8. Stop when found, after 12 attempts/10 seconds, or after two consecutive no-progress fingerprints.
9. Fail as `SCROLL_CONTAINER_NOT_FOUND`, `SCROLL_NO_PROGRESS`, or `TARGET_NOT_FOUND_AFTER_SCROLL`.

A genuinely standalone swipe replays its recorded path/duration and must verify that the selected container changed.

### F. Acting

Use this policy:

- **Semantic click:** only on the matched node or an ancestor explicitly present in the recorded ancestor chain that exposes `ACTION_CLICK`. Never climb 25 arbitrary parents.
- **Gesture tap:** use when the node lacks `ACTION_CLICK`, semantic action returns false, or the recorded point identifies a subregion in Compose, Flutter, or WebView. Tap the recorded point normalized within the node/window; otherwise choose a point in the node’s visible intersection, not blindly its centre.
- Before gesture dispatch, re-snapshot the selected window and confirm that the point still hit-tests to the chosen candidate and is not covered by a higher interactive window.
- **Long press:** prefer the recorded actionable node/ancestor; otherwise replay recorded duration and point.
- **Text:** perform `ACTION_FOCUS`, verify focus, perform `ACTION_SET_TEXT`, then re-read and compare actual text without logging it.
- **IME action:** require the exact field to be focused and editable. On API 30+, use `ACTION_IME_ENTER`; on earlier APIs use an explicitly captured keyboard action-button tap/gesture. Do not pretend clicking the field is submission.
- **Irreversible actions:** never automatically retry.
- For reversible taps, an alternative gesture retry is allowed only after the full effect deadline and only when recorded pre-state proves the control did not change.

### G. Verification

Every UI step needs one or more concrete postconditions:

- expected activity/window/fingerprint;
- target disappeared/appeared;
- `checked`, `selected`, `expanded`, or range value changed;
- text equals requested value;
- focus moved to expected field;
- IME became visible/hidden;
- next target reached confidence ≥75.

A returned `true` from `performAction()` is only “dispatch accepted,” not success. If capture produced no observable postcondition, preflight returns `UNVERIFIABLE_STEP`; the editor must ask the user to add an assertion or accept an explicitly marked best-effort step.

---

## 3. App-launch semantics

Define explicit policies:

```kotlin
enum class LaunchPolicy {
    RESET_TO_ROOT,
    RESUME_EXISTING_TASK,
    EXPLICIT_EXPORTED_ACTIVITY,
    DEEP_LINK
}

sealed interface LaunchTarget {
    data class Launcher(val packageName: String) : LaunchTarget
    data class Component(val packageName: String, val className: String) : LaunchTarget
    data class DeepLink(val packageName: String, val uri: String) : LaunchTarget
}
```

Rules:

- Default newly recorded launcher launches to `RESET_TO_ROOT`.
- Build the package’s launcher intent with `NEW_TASK | CLEAR_TASK`; Android documents that `NEW_TASK` alone may merely bring an existing task forward, while `CLEAR_TASK` clears its associated task when combined with `NEW_TASK`. [Android Intent flags](https://developer.android.com/reference/android/content/Intent).
- `RESUME_EXISTING_TASK` is retained only for migrated legacy actions or a user-selected “continue where I left off” mode.
- Record `TYPE_WINDOW_STATE_CHANGED.className` as `observedActivityClass`.
- An observed activity is not automatically a launchable component. Use an explicit component only when `PackageManager` confirms it is exported and resolvable.
- Accessibility cannot reconstruct the URI/extras that produced an arbitrary external deep link. Store a deep link only when RepeatKit initiated it, the capture source knows the actual Intent, or the user supplied it.
- After `startActivity`, wait through accessibility window events until:

  1. a selectable application window for the target package is active/focused;
  2. the expected activity matches when known;
  3. expected fingerprint similarity is at least 0.80;
  4. it is stable under the screen-wait rule.

No `ActivityManager` or `UsageStats` permission is required; accessibility window roots and window-state events provide the observable foreground contract. If an app restores its own internal navigation despite task reset, the expected-screen check fails precisely instead of continuing.

---

## 4. Diagnostics

Create a durable per-run trace:

```kotlin
data class RunTrace(
    val runId: String,
    val workflowId: String,
    val engineVersion: Int,
    val startedAtEpochMs: Long,
    val deviceSummary: DeviceSummary,
    val steps: List<StepTrace>,
    val outcome: RunOutcome
)

data class StepTrace(
    val stepId: String,
    val phaseTimingsMs: Map<ReplayPhase, Long>,
    val observedWindows: List<WindowTrace>,
    val selectedWindow: WindowDecision?,
    val candidates: List<CandidateTrace>,
    val decision: MatchDecision?,
    val actionUsed: ActionMethod?,
    val dispatchAccepted: Boolean?,
    val verification: VerificationTrace?,
    val failure: ReplayFailure?
)
```

Storage:

- Write incrementally as sanitized JSON Lines to `filesDir/replay-traces`.
- Gzip on completion.
- Retain the newest 20 runs or 2 MB, whichever limit is hit first.
- Keep an index containing run ID, shortcut display name, timestamp, result, failing step, and failure code.
- Add an in-app “Replay history” list and step-detail screen.
- Export through `FileProvider` as sanitized `.json.gz`.

Privacy:

- Never include entered text, node text, descriptions, window titles, URLs, phone numbers, or message bodies.
- Represent text-like values as per-install HMAC-SHA256 tokens plus length/character-class metadata.
- Candidate traces may include resource IDs, class/role, quantized normalized bounds, score components, and boolean locator-match results.
- Failure notifications use a human step label such as “Tap the second account row,” not raw captured text.

The current in-memory `AutomationTrace` retains only match/wait fragments and is described as test-only at [AutomationAccessibilityService.kt:185–244](../app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt); Engine v2 replaces it.

---

## 5. Package/module layout and interfaces

```text
com.shortcuts.app.automation
├── model/
│   ├── WorkflowV2.kt
│   ├── Step.kt
│   ├── ElementSelector.kt
│   ├── ScreenFingerprint.kt
│   ├── WorkflowCodec.kt
│   └── LegacyWorkflowMigrator.kt
├── core/
│   ├── tree/NodeSnapshot.kt
│   ├── tree/WindowSnapshot.kt
│   ├── matching/SelectorMatcher.kt
│   ├── matching/CandidateScorer.kt
│   ├── fingerprint/ScreenFingerprinter.kt
│   └── geometry/NormalizedGeometry.kt
├── capture/
│   ├── CaptureEngine.kt
│   ├── SelectorExtractor.kt
│   ├── GestureAssembler.kt
│   ├── RecordingCompiler.kt
│   └── CaptureSessionStore.kt
├── replay/
│   ├── ReplayEngine.kt
│   ├── ReplayStateMachine.kt
│   ├── WindowResolver.kt
│   ├── TargetResolver.kt
│   ├── ScrollToFind.kt
│   ├── UiActor.kt
│   ├── StepVerifier.kt
│   └── ReplayFailure.kt
├── android/
│   ├── AccessibilitySnapshotAdapter.kt
│   ├── AccessibilityEventSource.kt
│   ├── AndroidGestureDispatcher.kt
│   ├── AndroidAppLauncher.kt
│   └── AndroidUiEnvironment.kt
└── diagnostics/
    ├── TraceSink.kt
    ├── FileTraceStore.kt
    ├── TraceSanitizer.kt
    └── TraceExporter.kt
```

Public boundaries:

```kotlin
interface CaptureEngine {
    fun start(context: CaptureScreenContext)
    fun accept(event: CapturedInteraction)
    fun updateScreen(snapshot: WindowSetSnapshot)
    fun finish(): WorkflowV2
}

interface ReplayEngine {
    suspend fun run(workflow: WorkflowV2, sink: TraceSink): ReplayResult
}

interface UiEnvironment {
    fun snapshotWindows(): WindowSetSnapshot
    fun events(): Flow<UiEnvironmentEvent>
    suspend fun perform(request: UiActionRequest): DispatchResult
}

interface AppLauncher {
    suspend fun launch(step: LaunchAppStep): LaunchDispatchResult
}

interface TraceSink {
    fun append(event: TraceEvent)
}
```

`NodeSnapshot` and `WindowSnapshot` must be immutable pure-Kotlin values. Never retain `AccessibilityNodeInfo` across waits; the Android adapter creates a fresh snapshot for each decision.

### Existing classes

Keep, but narrow:

- `Automation` Room entity and `actionsJson`.
- `ShortcutRunner` as admission/preflight gateway.
- `AutomationExecutionService` as foreground lifetime owner.
- `ActionExecutorService` for non-UI legacy/business actions.
- `AutomationAccessibilityService` as a thin Android event, window, node, and gesture adapter.
- Recorder foreground service/session notification UI.

Replace/delete after migration:

- Replace `ActionConverter` with `WorkflowCodec`; keep `Action` only as a deprecated legacy DTO.
- Replace `AutomationRecorder`’s label/action construction with `CaptureEngine`.
- Replace `RecorderSessionOwner`’s action-collapsing rules with `RecordingCompiler`.
- Delete `ReplayUiActionExecutor`.
- Remove matching, scrolling, coordinate planning, and action execution from `AutomationAccessibilityService`.
- Delete `TapMarkOverlay` once the new capture mechanism supplies exact interactions; Engine v2 should not depend on how capture is obtained.
- Replace the in-memory `AutomationTrace` with the diagnostics package.

---

## 6. Test strategy

## JVM fake-tree tests

The fake model must support windows, Z-order, package/activity, classes, IDs, text/description, bounds, actions, focus, editable/scrollable state, collection metadata, parent/sibling relationships, and scripted tree mutations.

Required tests:

### Codec and migration

- v2 round-trip without field loss.
- Every legacy action type migrates deterministically.
- Legacy coordinates are marked `DISPLAY_LEGACY`.
- Unknown schema version fails loudly.
- Malformed JSON reports a decode path and never becomes an empty workflow.
- Unknown required enum fails rather than becoming `WAIT`.

### Matching

- Exact ID beats substring ID.
- Resource ID is never searched as text.
- Exact “Internet” does not match “Internet & SIM” without sufficient context.
- Equal “Delete” candidates fail as ambiguous.
- Row sibling text selects the correct near-identical row.
- Ancestor path disambiguates equal labels.
- Sibling index disambiguates repeated icons.
- Generic class cannot outweigh an identity contradiction.
- Best score below 75 fails.
- Best/second margin below 12 fails.
- Hidden, disabled, empty-bound, wrong-package, and wrong-window nodes are rejected.
- Candidate ordering does not change the decision.

### Windows

- Target app wins over a higher Dynamic Island accessibility overlay.
- Dialog wins when the step expects a dialog.
- Base activity is not selected through a modal dialog.
- Same-package popup versus activity is resolved by type/title/fingerprint.
- IME is ignored for normal UI targets but observed for IME verification.
- Two same-package windows with insufficient evidence fail as ambiguous.

### Capture/compiler

- Exact hit node, actionable ancestor, and descendant label remain distinct.
- ID-only parent still captures stable descendant label strategies.
- Two id-less Compose text fields remain separate by path/sibling/bounds.
- Legitimate double tap remains two interactions.
- Long-press followed by synthetic click becomes one interaction.
- Swipe preserves direction, path, duration, and container.
- App switch through launcher, Recents, link, chooser, and same-app return produces the specified launch/navigation model.
- Ordinary content changes never invent IME submission.
- Password fields store neither selector text nor input.

### Replay and verification

- Delayed target succeeds from an event rather than a fixed sleep.
- Wrong expected screen fails before matching.
- `performAction(true)` plus no state change fails verification.
- Semantic click succeeds when postcondition changes.
- Gesture fallback is used only after safe semantic failure.
- No retry for irreversible actions.
- `SET_TEXT` focuses, sets, and verifies.
- Wrong focused field fails `IME_ACTION`.
- Scroll-to-find reaches a target after multiple pages.
- Nested scrolling selects the recorded container.
- No-progress scrolling stops after two identical fingerprints.
- Cancellation interrupts every wait phase.

### Coordinates

- Window-normalized coordinates survive system-bar/inset changes.
- Density change is either safely transformed using window geometry or rejected.
- Rotation mismatch is rejected unless an explicit transform is supported.
- Popup/dialog coordinates map within that window.
- A higher interactive window covering the point blocks the gesture.
- Hit-test mismatch immediately before dispatch fails.

### Diagnostics

- No raw text/input/description/title appears in stored or exported traces.
- Candidate score components reproduce the final score.
- Trace survives mid-run process termination.
- Retention limits delete only the oldest completed traces.

## On-device fixture and harness

Create a separate `:replay-fixture` app with screens for:

- classic Views;
- Compose;
- RecyclerView and LazyColumn;
- repeated rows with identical “Edit/Delete” labels;
- nested vertical and horizontal scroll containers;
- dialog, popup menu, and bottom sheet;
- EditText and Compose TextField with Search/Send/Done/Next actions;
- WebView controls;
- delayed content at 250 ms, 1 s, 3 s, and 6 s;
- loading skeleton followed by final content;
- disabled/hidden duplicate nodes;
- task-restoration and deep-link entry points.

Every control writes a structured interaction to the fixture:

```json
{"sequence":12,"screen":"compose_form","control":"last_name","action":"SET_TEXT","valueHash":"..."}
```

Harness flow:

1. Reset fixture to a deterministic seed.
2. Drive recording with `adb shell input tap`, `swipe`, `text`, and key events.
3. Save the workflow.
4. Randomize the pre-replay task/back-stack state.
5. Enable the Dynamic Island overlay for the overlay scenarios.
6. Replay through the same widget/foreground-service entry path used by production.
7. Pull fixture interaction logs and RepeatKit’s sanitized trace.
8. Diff expected versus actual ordered interactions and final fixture state.
9. Repeat at density 356 and physical/default density 420, gesture navigation, portrait, and landscape where supported.

### Reliability metric

A scenario-run passes only when:

- all expected fixture interactions occur in exact order;
- no extra or wrong-target interaction occurs;
- final state matches;
- RepeatKit reports success;
- completion stays within the scenario timeout.

Release bar:

- At least 30 supported scenarios × 20 clean runs = **600 runs**.
- **≥95% overall pass rate** and **≥90% per scenario**.
- **Zero wrong-target actions**; a detected ambiguity/fail-loud result is preferable to a wrong action.
- All intentional negative scenarios must produce the expected precise failure code in 100% of runs.
- Any silent success with a fixture-log mismatch is a release blocker.

---

## 7. Ordered implementation work packages

| Order | Package | Owned files | Dependencies | JVM acceptance check |
|---:|---|---|---|---|
| 1 | **Schema, codec, migration** | `automation/model/**`; legacy portion of `data/Automation.kt` | None | `./gradlew :app:testDebugUnitTest --tests '*WorkflowCodecTest' --tests '*LegacyWorkflowMigratorTest'` |
| 2 | **Pure snapshot, fingerprint, matching core** | `automation/core/**` | WP1 selector types | `./gradlew :app:testDebugUnitTest --tests '*CandidateScorerTest' --tests '*SelectorMatcherTest' --tests '*ScreenFingerprinterTest'` |
| 3 | **Capture compiler** | `automation/capture/**` | WP1–2 | `./gradlew :app:testDebugUnitTest --tests '*SelectorExtractorTest' --tests '*GestureAssemblerTest' --tests '*RecordingCompilerTest'` |
| 4 | **Pure replay state machine** | `automation/replay/**` except Android implementations | WP1–2; fake `UiEnvironment` | `./gradlew :app:testDebugUnitTest --tests '*ReplayStateMachineTest' --tests '*ScrollToFindTest' --tests '*StepVerifierTest'` |
| 5 | **Android accessibility and launcher adapters** | `automation/android/**` | WP2 and WP4 interfaces | `./gradlew :app:testDebugUnitTest --tests '*WindowSelectionPolicyTest' --tests '*LaunchPolicyTest' --tests '*AccessibilitySnapshotAdapterTest'` |
| 6 | **Diagnostics and in-app trace model** | `automation/diagnostics/**`, `ui/replaydiagnostics/**` | WP1 and WP4 trace contracts | `./gradlew :app:testDebugUnitTest --tests '*TraceSanitizerTest' --tests '*FileTraceStoreTest' --tests '*TraceRetentionTest'` |
| 7 | **Product integration and legacy removal** | `AutomationAccessibilityService.kt`, `AutomationRecorder.kt`, `RecorderSession.kt`, `ActionExecutorService.kt`, `AutomationExecutionService.kt`, `ShortcutRunner.kt` | WP1–6 | `./gradlew :app:testDebugUnitTest --tests '*EngineV2IntegrationTest' --tests '*SavedWorkflowAdmissionTest' --tests '*LegacyWorkflowExecutionTest'` |
| 8 | **Fixture app and harness** | new `replay-fixture/**`, `replay-e2e/**` | WP3, WP5, WP7 | JVM protocol/diff check: `./gradlew :replay-e2e:test --tests '*InteractionLogDiffTest'`; separate device gate runs the 600-run matrix |

Engine v2 should not be enabled for saved workflows until WP7 can run both migrated legacy workflows and native v2 workflows through the same service entry point, and the WP8 reliability gate passes.

No files, Git state, Gradle tasks, or ADB state were changed during this audit.
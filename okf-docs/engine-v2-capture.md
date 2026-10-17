---
title: "Engine v2 capture compiler"
type: architecture
author: codex_gpt_5_6_sol
tags: [engine-v2, capture, compiler, selectors, gestures, verification]
---

# Engine v2 capture compiler

The capture compiler is a pure-Kotlin boundary between platform capture and the persisted
`WorkflowV2` model. It consumes immutable finger-down snapshots and explicit semantic capture
events. It never reads Android objects, infers keyboard submission from content changes, or
reduces targets to flat label/class tuples.

## Compiler rules

| Rule | Compiled behavior | Defect fixed |
| --- | --- | --- |
| Deterministic entry | The first real application produces `LaunchAppStep(Launcher(package), RESET_TO_ROOT)`, including recordings that begin inside an already-open app. | #2 |
| Non-application capture filtering | Launcher, RepeatKit, SystemUI, IME, and configured system packages do not produce UI steps. Their transitions still inform launch classification. | #19 |
| Explicit switch semantics | Launcher icon launches reset to root; Recents, notification launches, and Home returns without an icon tap resume the task. A switch within two seconds of a preceding real-app interaction is the effect of that interaction, not another launch. | #20 |
| Structural selectors | Selectors retain exact normalized locators, target class/role, up to six nearest ancestors, same-class sibling index, row context, normalized bounds, required actions, and the nearest scroll container. | #12 |
| Label provenance | The actionable target keeps its own identity. If it is unlabelled, the extractor records the exact descendant supplying the label, preferring the descendant under the touch point. | #13 |
| Stable locator ranking | Full resource IDs rank first and are marked likely-stable when generated-looking. Description, text, and Compose-style test-tag locators follow. Dynamic or long text is volatile. | #12, #13 |
| Window identity | Every step records package, observed activity, type, tokenized title, normalized display bounds, and an injected screen fingerprint. `observedActivityClass` accepts only classes whose simple name ends with `Activity` (excluding dialog classes like `AlertDialog`), preserving the last real activity for that package. Raw window titles never enter the workflow. | #12, #19, #20 |
| Window-relative gestures | The full captured point path and timing are preserved and normalized to the selected window. | #21 |
| Text field identity | Every commit is its own `SET_TEXT` step and retains structural selector identity, built from the field in the before-snapshot of the focusing tap (matched by hit node/bounds) so locators reflect empty hint/label rather than typed text. Empty commits and commits equaling the field's hint/label are dropped. Consecutive commits to the same field collapse into the last one. | #11 |
| Sensitive text | Password fields produce no `SET_TEXT`; their tap receives `SENSITIVE_TEXT_NOT_RECORDED`. Password text and hint text are not selector locators. | Privacy requirement |
| Explicit IME actions | Only `CapturedImeAction` creates `IME_ACTION`; screen, autocomplete, and window changes never synthesize submission. | #16 |
| Scroll intent | A run of consecutive SWIPEs in the same window followed by a non-swipe interaction collapses into `RevealPolicy(direction of dominant swipe vector)` unconditionally on the next step (without cross-snapshot node identity) and sets missing `scrollContainer`. A swipe run with no following interaction merges into ONE standalone SWIPE step targeting the scroll container, not the row under the finger. | #21 |
| Gesture cardinality | Two captured taps remain two steps. A tap identified as the synthetic click from the same long-press gesture is suppressed. | #22 |
| Global navigation | Global actions are explicit UI steps. A final Home, or Back that leaves the last app, is trimmed as recording teardown navigation. | #19, #20 |
| Diagnostic timing | `capturedDelayFromPreviousMs` stores the gap from the preceding captured item. Replay does not use it as a sleep instruction. | Timing defect #9 |
| Before/after evidence | Each UI step fingerprints its own before-window and the next item's before-window, or the final stop snapshot. | Verification defect #8 |
| Typed verification | Capture prefers boolean target-state changes, then cross-app expected windows, changed screen fingerprints, and finally confidence in the next target. `SET_TEXT` always verifies `TextEquals`. A last step is best-effort and retains a changed stop-screen assertion when available. | #8, Part 2 §2.G |
| Safety | Labels containing Delete, Remove, Send, Pay, Buy, Post, Submit, or Confirm mark the step irreversible. | Retry safety in Part 2 §2.F |

## Boundaries and injected dependencies

`SelectorExtractor` depends on `ScreenFingerprinter` and `TextTokenizer`; tests use deterministic
fakes while the Android adapter supplies production implementations. `RecordingCompiler` also
receives an ID generator and device metadata. Display width, height, density, and rotation always
come from the recording's first `WindowSetSnapshot`; manufacturer, model, and SDK are adapter
metadata because the pure snapshot contract does not contain them.

`SelectorExtraction` carries `targetNodeId` and `labelSourceNodeId` as capture-time provenance.
The shared `ElementSelector` contract has no label-source field, so provenance is used to assemble
consistent target geometry/class and label evidence without changing the shared Engine v2 model.

## Deterministic verification order

For a UI step, compilation chooses the first observable assertion in this order:

1. `TextEquals` for `SET_TEXT`.
2. `BooleanStateChanged` for checked, selected, or expanded state.
3. `ExpectedWindow` when the application package changed.
4. `ExpectedScreen` when fingerprint similarity is below `0.80`.
5. `NextTargetConfidence` when the next UI target is available.
6. Explicit best-effort only when no observable assertion exists.

Cross-package change is evaluated before generic fingerprint change. Different packages always
have dissimilar fingerprints, and retaining the stronger `ExpectedWindow` assertion preserves the
recorded link/notification navigation contract.

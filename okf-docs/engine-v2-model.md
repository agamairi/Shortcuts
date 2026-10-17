---
title: "Engine v2 versioned workflow model"
type: architecture
author: codex_gpt_5_6_sol
tags: [engine-v2, workflow, persistence, migration, accessibility]
---

# Engine v2 versioned workflow model

Engine v2 stores a `WorkflowV2` object in the existing `Automation.actionsJson` column. The object has an explicit `schemaVersion`, a stable workflow ID, the recording display context, and ordered immutable steps. No Room migration is needed.

Every step has a stable UUID string, an optional user title, a timeout, optional before/after screen fingerprints, and an optional delay captured from the preceding legacy action. The four step kinds are app launch, UI interaction, wait, and a compatibility wrapper for legacy non-UI actions.

UI steps separate intent from evidence:

- `UiOperation` says what to do: tap, long-press, swipe, set text, perform a semantic IME action, or invoke a system global action.
- `WindowSelector` identifies the expected package, window kind, display geometry, activity, privacy-preserving title token, and optional screen fingerprint.
- `ElementSelector` stores ranked exact locators plus class/role, ancestors, sibling and row context, normalized geometry, required actions, and an optional scroll container.
- `CapturedPoint` distinguishes window-normalized coordinates from migrated `DISPLAY_LEGACY` display coordinates.
- `VerificationSpec` is a list of typed observable postconditions: expected screen, target presence, boolean/range changes, exact resulting text, focus movement, IME visibility, or confidence in the next target.

Raw window titles are confined to the adapter-side `WindowSnapshot`. Persisted `WindowSelector.titleToken` and other derived tokens are produced by an injected `TextTokenizer`; the model stores only the resulting string or long. Exact replay locators may retain clear text and content descriptions, but later diagnostic packages must never copy those values into traces.

## JSON shape

All sealed values carry a `type` discriminator. Enum values use their uppercase model names. A compact workflow looks like this:

```json
{
  "schemaVersion": 2,
  "workflowId": "a41b...",
  "recordedDevice": {
    "manufacturer": "Google",
    "model": "Pixel 6a",
    "sdkInt": 36,
    "displayWidthPx": 1080,
    "displayHeightPx": 2400,
    "densityDpi": 420,
    "rotation": 0
  },
  "steps": [
    {
      "type": "launch_app",
      "id": "7cae...",
      "title": "Open Settings",
      "target": { "type": "launcher", "packageName": "com.android.settings" },
      "policy": "RESET_TO_ROOT",
      "expectedWindow": {
        "packageName": "com.android.settings",
        "observedActivityClass": null,
        "windowType": "APPLICATION",
        "titleToken": null,
        "boundsOnDisplay": { "left": 0, "top": 0, "right": 1, "bottom": 1 },
        "displayId": 0,
        "fingerprint": null
      },
      "timeoutMs": 12000,
      "expectedBefore": null,
      "expectedAfter": null,
      "capturedDelayFromPreviousMs": null,
      "migrationWarnings": []
    }
  ]
}
```

## Codec rules

`WorkflowCodec` inspects the JSON root before decoding:

1. An object with `schemaVersion == 2` is decoded strictly. Missing discriminators, unknown sealed variants, unknown required enum values, and missing required IDs return `DecodeResult.Failure` with a code and JSON path.
2. An array is read through the existing `ActionConverter` behavior and passed to `LegacyWorkflowMigrator`.
3. Any other root, malformed JSON, a missing version, or an unsupported version fails loudly. It never becomes an empty workflow.
4. Encoding accepts only schema v2 and always writes the versioned object. Reading legacy JSON has no persistence side effect; rewriting happens only when a later integration explicitly saves it.

## Legacy migration

Migration IDs are name-based UUIDs derived from the legacy payload and action index, so repeated reads produce identical workflows.

| Legacy action | Engine v2 result | Compatibility notes |
| --- | --- | --- |
| `APP_INTENT` | `LaunchAppStep(Launcher, RESUME_EXISTING_TASK)` | Warns that legacy launch behavior resumes the existing task. |
| UI `TAP` / `LONG_PRESS` | `UiStep` with the matching operation | Builds ranked exact resource-ID, text, description, and class evidence. |
| UI `TYPE_TEXT` | `UiStep(SET_TEXT)` | Keeps the requested input for replay. |
| UI `PRESS_ENTER` | `UiStep(IME_ACTION(UNSPECIFIED))` | The old format did not preserve the semantic IME kind. |
| UI `SCROLL` | `UiStep(SWIPE)` with a reveal direction | The old format did not preserve path, duration, or distance. |
| UI global action | Targetless `UiStep(GLOBAL_ACTION)` | Keeps the original global-action key. |
| `WAIT` | `WaitStep` | Its `delayMillis` is the wait duration, as in the legacy executor. |
| System toggle, HTTP, message, dial | `LegacyActionStep` | Wraps the original `Action` unchanged for a later first-class migration. |

For non-wait actions, `delayMillis` moves to `capturedDelayFromPreviousMs` on the next step. Coordinates become `DISPLAY_LEGACY` because the old record has no window bounds. `UNRESOLVED` target text creates a warning and is never promoted to a text locator. Migrated UI steps are explicitly best-effort and warn that no observable postcondition existed in the old format.

## Pure snapshot boundary

`NodeSnapshot`, `WindowSnapshot`, and `WindowSetSnapshot` contain no Android types. The Android adapter will copy accessibility state into these immutable values and release platform node handles immediately. Hit testing selects the highest eligible window, skips accessibility overlays and excluded packages, traverses children in reverse drawing order, ignores invisible nodes, and promotes the deepest hit leaf to its nearest reasonably sized actionable ancestor. An actionable node covering 80% or more of its window is rejected as a likely modal scrim.

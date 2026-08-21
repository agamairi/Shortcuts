---
title: Navigation & Screens
type: architecture
author: antigravity_gemini_3_1_pro
tags: [navigation, compose, viewmodel, screens, routes]
---

# Navigation & Screens

## Overview
The UI uses Jetpack Compose Navigation (`NavHost`) to route between the top-level screens and feature builders. The entry point is `ShortcutsNavigation()` found in `Navigation.kt`.

## Application Routes

- **`dashboard`**: The main entry point rendering `DashboardScreen`. Displays the list of all created shortcuts.
- **`ai_builder`**: Renders `AiBuilderScreen`, scoped to the route's `NavBackStackEntry` in `Navigation.kt`. It is a two-stage conversational AI builder: prompt entry before a draft exists, then draft review and editing.
- **`manual_builder?automationId={automationId}`**: Renders `ManualBuilderScreen`. Used both for creating new shortcuts from scratch and editing existing ones (via the `automationId` argument).
- **`create_widget`**: Renders `CreateWidgetScreen`. The custom widget creation flow (AI or manual).
- **`settings`**: Renders `SettingsScreen`.
- **`help`**: Renders `HelpScreen`.
- **`recorder`**: Renders `RecorderScreen`. Provides the UI for capturing UI interactions via the accessibility service.

## ViewModel Scoping & Lifecycle

A key design constraint in the builder flows is ensuring clean states.
- **`AiBuilderViewModel`** and **`CustomWidgetViewModel`** are scoped directly to their `NavBackStackEntry` in `Navigation.kt`.
- When a user backs out of a builder route, the `NavBackStackEntry` is popped and its ViewModel is cleared. This guarantees that in-progress drafts are discarded and pending model downloads/executions are cancelled. Builders therefore guarantee a clean start upon re-entry.
- Background tasks like accessibility recording are owned by services (`RecorderSessionService`), ensuring they survive configuration changes or the popping of a UI route.

## AI Builder Screen States

`AiBuilderScreen` does not have a mode selector. It selects its whole screen from one state boundary: `AiBuilderData.draft` ([`AiBuilderScreen.kt:135-170`](../app/src/main/java/com/shortcuts/app/ui/screens/AiBuilderScreen.kt)).

- **InitialBuilderScreen (`draft == null`)** is the only entry screen. Its `BasicTextField` is bound to `prompt` / `updatePrompt`, and an example chip replaces the field value; chips do not generate a step themselves ([`AiBuilderScreen.kt:333-396`](../app/src/main/java/com/shortcuts/app/ui/screens/AiBuilderScreen.kt)). The enabled “Add this step” control invokes the existing `downloadModelAndGenerate(context)` pipeline ([`AiBuilderScreen.kt:401-441`](../app/src/main/java/com/shortcuts/app/ui/screens/AiBuilderScreen.kt)). This screen intentionally has neither a save action nor a steps-so-far list: by definition, it has no draft to review.
- **ReviewStepsScreen (`draft != null`)** owns draft editing, saving, test runs, manual additions, unresolved-step repair, and the follow-up prompt. Its bottom inline `OutlinedTextField` (“Add another step...”) uses the same prompt/update/generate callbacks ([`AiBuilderScreen.kt:493-560`](../app/src/main/java/com/shortcuts/app/ui/screens/AiBuilderScreen.kt)); this is where users add turns after the first generated draft, not on `InitialBuilderScreen`.

This replaces the removed slot-template (“madlib”) and separate free-text screens. There is no `MadlibBuilderMode`, `builderMode`, or `madlibState` in the AI builder state, and no mode-switching API. The retired `MadlibSlotModel.kt` and its test are absent.

### Design Reference and Runtime Differences

[`docs/design/Builder.dc.html`](../docs/design/Builder.dc.html) remains the approved visual reference, with the rationale in [`docs/design/README.md`](../docs/design/README.md). Its tap-to-cycle sentence and local add/remove/save state simulate an interaction in a static mockup. The app deliberately uses a genuine editable text field plus tappable example chips instead, then transitions to `ReviewStepsScreen` when generation successfully publishes a draft. Consequently, the initial runtime screen has no mockup-local step accumulation, remove-last action, or save control; those responsibilities are in the review screen. This is an intentional interaction-model difference, not a defect in the visual design.

### Madlib Teardown Boundary

Only the AI-specific slot model was removed. `SUPPORTED_DEVICE_CONTROLS`, `DeviceControl`, and `ToggleStateOption` are still live declarations in `ManualBuilderScreen.kt` ([`ManualBuilderScreen.kt:870-894`](../app/src/main/java/com/shortcuts/app/ui/screens/ManualBuilderScreen.kt)). The unrelated manual device-toggle sentence and picker use them to resolve labels and choices ([`ManualBuilderScreen.kt:562-568`](../app/src/main/java/com/shortcuts/app/ui/screens/ManualBuilderScreen.kt), [`ManualBuilderScreen.kt:622-658`](../app/src/main/java/com/shortcuts/app/ui/screens/ManualBuilderScreen.kt)). Do not remove them as part of future AI/madlib cleanup.

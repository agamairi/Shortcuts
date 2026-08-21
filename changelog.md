# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.2] - 2026-08-21
### Changed
- **Merged the AI builder into a single screen.** The AI builder flow used to start with a slot-based template screen that could only branch into a separate free-text mode. The initial screen now combines the visual identity of the template layout with the real text input and quick-start example chips, removing the confusing two-screen split and dead ends.

### Fixed
- **Widget redraw was occasionally unreliable.** Sometimes, after configuring a widget or editing a shortcut, the widget on the home screen wouldn't visually update until the phone screen was turned off and on, or until several other apps were opened. This was caused by two compounding issues: first, if updating one widget failed (e.g. it pointed to a deleted shortcut), the system stopped updating the rest of the widgets in the batch. Second, some phone launchers aggressively cache the widget view and ignore background updates. We now update each widget individually so one broken widget can't block the rest, we log precisely which ones fail to help with future debugging, and we send a "nudge" to the launcher to force it to invalidate its cache and show the new widget right away.
- **Recorder app launches are now replayable.** Opening an app from the home screen was recorded as
  a brittle tap on that launcher's icon, which can move or differ between devices. It now records
  the app itself, so the shortcut opens the intended app by package name; simply going home or
  moving between screens within the same app does not add a fake step.
- **The recording controls no longer hide in Silent notifications.** The ongoing recorder
  notification and its Stop button now appear in the normal notification section while remaining
  free of sound and vibration.

## [0.7.1] - 2026-08-20
### Added
- **Choose a widget layout.** The widget setup screen showed three previews — Single tile, Grid of
  four, Scrolling list — that looked like options but had no click handler at all; the layout was
  only ever inferred from the widget's size. There is now a real **Layout** picker with
  **Automatic** (the old size-adaptive behaviour, still the default) plus the three explicit
  layouts, and an explicit choice stays put however you resize the widget. Tapping a preview
  selects that layout too. Stored via an additive Room migration 7→8; widgets placed before this
  keep adapting to their size.
- **Wait action.** "Wait" is now a step you can add like any other: pick 1, 2, 3, 5, 10, 15, 30 or 60
  seconds and the shortcut pauses there, so a following step can wait for a screen to finish
  loading. Previously a pause could only exist as an invisible property of another step, and the
  pause after the *last* step was skipped entirely.
- **Edit a shortcut.** The ⋮ menu on each shortcut now has an **Edit** item, which opens the builder
  preloaded with that shortcut's name, colour, icon and steps. Saving updates the existing shortcut
  rather than creating a duplicate, so any widget already pointing at it keeps working.
- Recording now captures **scrolls**. Previously only taps and typing were recorded, so a recording
  that involved scrolling could never replay correctly.

### Fixed
- **A pinned widget stayed stuck on "Tap to set up" forever.** Adding a widget from the app genuinely
  worked — Android bound the widget and the app wrote its config row — but the follow-up redraw asked
  Glance for the new widget's id before Glance knew about it. `getGlanceIdBy` *throws* in that window,
  the throw vanished inside the broadcast receiver's coroutine, and the widget never re-rendered. The
  refresh now retries briefly, falls back to redrawing every instance, and logs a failure instead of
  swallowing it. Placed widgets are also redrawn on app launch so ones already stuck recover.
- **"Place widget" appeared to do nothing.** Opening setup and pressing the button saved the
  configuration and then threw while redrawing the widget — before the code that closes the screen.
  So the button looked completely dead even though it had worked. The redraw is now best-effort and
  can never block the screen from closing, and any remaining failure surfaces as a message instead
  of silence.
- **Dropping a widget from the launcher's picker skipped setup.** The widget now declares itself
  `reconfigurable`, so the launcher initiates the setup flow when the widget is placed and the
  widget can be reconfigured later via its pencil icon.
- **The header always read "0 on homescreen".** The count was read once per change to the *shortcut
  list*, but adding a widget changes the widget table and not the shortcut list, so the count never
  refreshed. It now observes the widget table directly.
- **Recorded shortcuts were unusable on replay.** Replay looked for its target once, at the instant
  the step ran, and gave up if it wasn't there — but a replayed shortcut runs far faster than a
  person tapped it, so the target routinely hadn't appeared yet. Replay now waits up to 5 seconds
  for the target to show up.
- **Recording dropped steps.** Unlabelled taps were discarded outright, and the accessibility config
  throttled events (`notificationTimeout=100`) and never subscribed to scroll events. Each step now
  stores several selectors — text, content description, view id, class name, and the screen point
  actually tapped — and falls back through them at replay time, including a coordinate tap when
  every selector misses.
- **"This screen couldn't be automated" told you nothing.** A failed tap or type now says which
  target it was looking for and why it failed: not on screen, no readable screen, nothing
  scrollable, or found but unresponsive.
- **A normal run said far less than Test Run.** Running a shortcut from the dashboard reported only
  "Couldn't run 'X'", while Test Run named the failing step and the reason — backwards, since the
  dashboard is where shortcuts are actually used. Both now describe a failure in the same words,
  from one shared formatter.
- **Test Run could freeze the app.** It ran the shortcut on the main thread; with a Wait step that is
  an ANR. Execution now runs off the main thread.
- **Web requests reported success without evidence.** The HTTP status code and a truncated response
  body are now carried back from the request, so you can tell a webhook actually fired. The "Web
  Request" action also describes what it is for in plain language — smart-home scenes, IFTTT, your
  own server — instead of just "Call a URL or webhook".
- "Describe to AI" now accepts multiple successive prompts, allowing users to build up a shortcut
  turn by turn instead of starting over. Each turn is given the most recent steps as context so
  follow-ups like "then close it" resolve, without letting the prompt grow unbounded on a small
  on-device model.
- Backing out of a builder route (AI builder, manual builder, or recorder) now correctly discards
  that route's in-progress draft and cancels pending model downloads or executions. Builders now
  guarantee a clean start upon re-entry.

### Known issues
- The launcher widget-picker flow and the widget layout picker have not been verified on hardware
  for this release.

## [0.7.0] - 2026-08-20
### Fixed — execution correctness (the app's worst bugs)
- **Device toggles never toggled anything.** `ActionExecutorService.handleSystemToggle` compared the
  target to `"WIFI"` in uppercase while the AI emits lowercase, so EVERY toggle — including WiFi —
  fell through to a catch-all that opened the generic Settings app. The on/off `state` was parsed,
  stored, and then never read. Targets are now matched case-insensitively, `state` is honoured, and
  flashlight / Do Not Disturb / volume / ring mode perform real toggles. Verified on-device: a
  lowercase `wifi` shortcut now resolves to `SettingsPanelActivity`, not `SettingsHomepageActivity`.
- Where Android forbids a toggle (WiFi and Bluetooth since API 29/33, Airplane mode entirely), the
  app now says so in plain language instead of silently opening the wrong screen. An unrecognised
  target is an explicit failure rather than a catch-all.
- Widget taps discarded the execution result entirely. Execution now returns a per-step `StepResult`
  (Success / Failed / NeedsPermission / Skipped), runs in a foreground service so multi-step chains
  survive the tap, and reports which step failed and why.
- `AutomationWidget` ignored the user's chosen `colorKey`/`iconKey` and always drew a bolt.

### Fixed — the on-device AI pipeline
- The ~271 MB model was **closed and fully reloaded before every generation**, once per clause, so a
  three-clause prompt reloaded it three times. Now one long-lived `LlmInference` engine with a fresh
  `LlmInferenceSession` per generation, serialised behind a Mutex.
- Replaced the regex prompt splitter with a quote- and speech-verb-aware `PromptSegmenter`:
  "Text mom and tell her I'm running late" is one step again, "Play Simon and Garfunkel" is one step,
  "Turn on wifi and open Spotify" is two.
- Every clause now yields exactly one `DraftStep`; a clause the model cannot handle surfaces as
  `Unresolved` instead of being silently dropped (a 3-step request could previously save as 1).
- App references are grounded against the REAL installed-app list via `PackageManager` instead of the
  model recalling package names; ambiguous matches return null rather than launching the wrong app.
- `ClauseAligner` maps batched model output back to clauses by evidence, refusing to pair an action
  with a clause it has no evidence for.

### Added
- **Shortcut recorder.** Record taps and typing in other apps via the accessibility service, review
  the captured steps, and save them as a normal shortcut. Labels are derived from the node tree
  (view id → own text → descendants → ancestors), because the node that fires a click is usually an
  unlabelled container; unidentifiable taps appear as `UNRESOLVED` rather than vanishing.
- Slot-based ("madlib") AI builder and a matching manual builder — the template fixes the action
  type, so the model never chooses the function, which is the step it most often got wrong.
- Review-before-save editor with per-step plain-language descriptions, reordering, and Test Run.
- Messaging and calling via `ACTION_SENDTO` / `ACTION_DIAL` — no Play-restricted permissions.
- System / Light / Dark theme setting, applied immediately and persisted.

### Changed
- New visual design across the app: bundled Schibsted Grotesk (SIL OFL), a warm palette, and
  colour-coded shortcut tiles.
- Tile palette expanded from 6 to 14 colours and icons from 6 to 17, every colour verified at
  >= 3.0:1 contrast against white content. Orange was corrected from a FAILING 2.70:1 to 3.08:1.
  `WidgetColorKey` is now the single source of truth so app tiles and widgets cannot drift apart.
- Five widget types consolidated into one adaptive widget, with an additive Room migration 6→7 that
  keeps legacy tables intact; verified with an instrumented `MigrationTestHelper` test.
- Edge-to-edge insets handled app-wide (required by targetSdk 35 on Android 15+).
- `compileSdk` 36, `targetSdk` 35, release build minified with keep rules.
- Accessibility features gated behind an explicit opt-in and disclosure.

### Removed
- The `AppThemeAccent` accent-colour system, which clashed with the new palette.

### Known issues
- The Android 16 promoted-ongoing status bar chip does not render; the generated bitmap badge showing
  the live step count is used instead. `setShortCriticalText` alone does not promote a notification —
  `FLAG_PROMOTED_ONGOING` is set by the system — and the qualifying characteristics are unresolved.
- The recording notification is not re-posted when the app restarts with a session already active,
  so the Stop action is lost until recording is stopped from inside the app.
- Android revokes this app's accessibility service on force-stop, and ADB cannot re-grant it once the
  app is flagged under restricted settings; it must be granted from Settings.
- Test Run for UI-automation steps depends on the target app's layout and may not replay reliably.

## [0.6.0] - 2026-08-06
### Added
- Per-shortcut appearance customization and Grid/List view mode with sorting in `DashboardScreen` matching Apple Shortcuts' documented behavior (verified against support.apple.com/guide/shortcuts, not assumption):
  - **Manual Per-Shortcut Color and Icon Customization**: Added nullable `colorKey` and `iconKey` fields to `Automation` backed by Room DB migration `MIGRATION_5_6`. Tapping an automation's colored icon box opens a sheet displaying the 6-color swatch row and 6-icon grid from `CreateWidgetScreen`, calling `AutomationViewModel.updateAppearance` to persist custom styling while falling back to deterministic `AutomationVisuals` defaults if uncustomized.
  - **Grid / List View Mode Toggle**: Added a top-app-bar view mode toggle button switching between a 2-column `LazyVerticalGrid` (defaulting to grid view matching iOS) and `LazyColumn` list view.
  - **List View Sorting**: Added interactive `FilterChip` controls in list view supporting sorting by None (creation order), Name (A-Z), and Action Count, with toggleable ascending/descending order on re-selection. Pure sort logic extracted to `AutomationSorter`.
- Unit tests in `AutomationViewModelTest` for `updateAppearance` and new unit test suite `AutomationSorterTest` testing all 3 sort modes in both directions.
- Visual polish and visual widget-pinning gallery in `DashboardScreen`:
  - **Automation Item Visual Identity**: Redesigned `AutomationItemCard` to feature a deterministic colored rounded-square icon (`WidgetColorKey` and `WidgetIconKey`) based on automation ID, aligning with the visual language of the widget system while preserving all existing card controls.
  - **Visual Widget Gallery**: Replaced the plain-text overflow menu pinning items with a unified "Add Widget to Home Screen" entry point opening a visual `WidgetGalleryBottomSheet` displaying all 5 widget types (Quick Shortcut Tile, Shortcuts List, Custom Widget, Shortcuts Grid, Greeting Widget) with illustrative preview tiles, one-line descriptions from architecture documentation, direct pin actions, and secondary "Create Your Own Widget" links.
- Unit tests in `AutomationVisualsTest` verifying deterministic color/icon mapping and palette distribution, and updated `DashboardScreenTest` for the new gallery bottom sheet flow.
- New Settings screen (`SettingsScreen` + `SettingsViewModel`) reachable via a dedicated top-app-bar gear icon on the Dashboard:
  - **AI Model Management**: View model download state, start download, or delete model on disk (`deleteModel(context)`) with a confirmation dialog.
  - **Accessibility Service Status**: Live status check for `AutomationAccessibilityService` with single-tap navigation to Android Accessibility Settings.
  - **About & Documentation**: App version inspection and direct link to the new Help screen.
- New Help screen (`HelpScreen`) providing clear user documentation:
  - Definition of Shortcuts and automations.
  - Overview of all 4 action types ("Open an App", "Tap or Type on Screen", "Toggle a Setting", "Web Request") with one-line examples.
  - Explanation of all 5 home-screen widget types.
  - Concrete step-by-step example showing how a single Shortcut can chain actions across multiple apps (e.g. Alexa + Hue) in sequence using Accessibility.
- Redesigned action-adding UX in `ManualBuilderScreen`:
  - **Action Selection Bottom Sheet**: Replaced hardcoded default action creation with a `ModalBottomSheet` displaying friendly cards for all 4 action types ("Open an App", "Tap or Type on Screen", "Toggle a Setting", "Web Request") with icons and plain-language descriptions.
  - **App Picker**: Replaced raw package name text fields for `APP_INTENT` actions with an installed app picker (`AppPickerDialog`) querying launchable apps via `PackageManager`, displaying app icons and labels with real-time search filtering.
  - **Human-Readable Action Summaries**: Replaced raw `ActionType` enum names with one-line human-readable summaries (e.g. "Open Alexa", "Tap 'Living Room'") and expandable action cards.
  - **Accessibility Warning Banner**: Added a top-of-screen warning banner navigating directly to Settings if the Accessibility Service is disabled.
  - **Accessibility Utility**: Extracted Accessibility Service status checking logic into `AccessibilityStatusChecker` shared by `SettingsViewModel` and `ManualBuilderScreen`.
- Unit tests for `ManualBuilderScreenTest` covering app filtering/sorting, app label resolution, action summary formatting, and action type metadata.

## [0.5.0] - 2026-08-06
### Added
- Five home-screen widget types built on Jetpack Glance, all sharing one unified tap-to-run pipeline (`RunAutomationCallback` + `AutomationIdResolver`):
  - **Quick Shortcut** (`AutomationWidget`) — a single minimal tile bound to one automation.
  - **Shortcuts List** (`ShortcutsListWidget`) — up to 4 automations in one scrollable widget, each independently tappable.
  - **Custom Widget** (`CustomWidget`) — a single tile with a user-chosen color, icon, and label, bound to an automation via a saved `CustomWidgetTemplate`.
  - **Shortcuts Grid** (`GridWidget`) — up to 6 `CustomWidgetTemplate` tiles in a 2-column grid, for multiple personalized shortcuts in one widget.
  - **Greeting** (`GreetingWidget`) — a time-of-day-aware personalized greeting ("Good morning/afternoon/evening, {name}") with one tappable bound shortcut; the only widget with dynamic, context-based content.
- In-app "Create Your Own Widget" builder (`CreateWidgetScreen` + `CustomWidgetViewModel`), reachable from the Dashboard's overflow menu: pick a color, icon, and shortcut manually, or describe the widget in natural language and let the on-device FunctionGemma model (`OnDeviceInferenceService.generateWidgetSpecJson`) propose one.
- Room DB schema versions 2–5, each with a real non-destructive `Migration` (`MIGRATION_1_2` … `MIGRATION_4_5`) and schema export enabled (`app/schemas/`), backing the new `WidgetBinding`, `WidgetListBinding`, `CustomWidgetTemplate`, `CustomWidgetBinding`, `GridWidgetBinding`, and `GreetingWidgetBinding` entities.
- ~70 new unit tests covering every new DAO, the widget tap-resolution logic (`AutomationIdResolver`), widget-deletion cleanup (`WidgetCleanupHelper`), the greeting time-of-day boundaries (`GreetingTextHelper`), the AI widget-builder flow, and a new `AutomationRepositoryTest`.

### Fixed
- The AI Builder was silently non-functional in production: `AiBuilderViewModel` was never given a real `OnDeviceInferenceService`, so inference always failed after model download. Now wired properly in `ShortcutsNavigation()`, shared with the new widget-builder AI flow.
- `AppDatabase` no longer uses `fallbackToDestructiveMigration()` (which wiped the local DB on every schema change) — replaced with real, additive migrations throughout.
- `CustomWidgetViewModel.generateWithAi()` now gates on model-download completion (mirroring `AiBuilderViewModel`'s flow) instead of silently failing if the FunctionGemma model hadn't been downloaded yet.

## [0.4.0] - 2026-08-03
### Added
- End-to-End (E2E) testing framework and test suite readiness documentation (`TEST_READY.md`) covering Tier 1-4 test scenarios.
- Expanded unit & integration test coverage to 74 test cases across 11 test suites (100% pass rate).
- Open Knowledge Format (OKF) documentation system in `okf-docs/` with YAML frontmatter (`architecture.md`, `automation-schema.md`, `services.md`, `viewmodel-uistate.md`).
- Documented `ModelDownloaderService`, `AutomationAccessibilityService`, ViewModel/UiState architecture, Room DB schema, and action execution pipeline.
- Process integration and Git branch merging strategy.

## [0.3.0] - 2026-08-03
### Added
- Material You (M3 Expressive UI) design implementation including dynamic color scheme, M3 components, and responsive layouts.
- MVVM State Management architecture using `StateFlow` and immutable UI state wrappers across all primary screens (`AiBuilderViewModel`, `ModelDownloadViewModel`, `AutomationViewModel`, `SettingsViewModel`).
- Strict JSON parsing, error boundaries, and state recovery in `AiBuilderViewModel`.
- Comprehensive M3 UI State unit and integration tests including `Milestone3EmpiricalStressTest`.

## [0.2.0] - 2026-08-02
### Added
- `ModelDownloaderService` foreground service for asynchronous AI model downloads with status notifications and state tracking.
- `AutomationAccessibilityService` UI automation execution engine with node traversal, gesture simulation, text input, navigation, and robust recursion limit protection.

## [0.1.0] - 2026-08-01
### Added
- Project initialization and Git workflow setup.
- `AGENTS.md` guidelines and process rules for AI agents.
- Open Knowledge Format (OKF) structure and initial architecture specs in `okf-docs/`.
- Room Database schema, DAOs, and repository layer for Shortcuts & Automation workflows.

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Per-shortcut appearance customization and Grid/List view mode with sorting in `DashboardScreen` matching Apple Shortcuts' documented behavior:
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

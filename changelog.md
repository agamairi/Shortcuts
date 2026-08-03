# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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



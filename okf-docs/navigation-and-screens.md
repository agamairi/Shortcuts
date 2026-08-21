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
- **`ai_builder`**: Renders `AiBuilderScreen`. The conversational AI builder flow.
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


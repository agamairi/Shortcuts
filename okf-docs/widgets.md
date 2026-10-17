---
title: Widget Architecture
type: architecture
author: antigravity_gemini_3_1_pro
tags: [widgets, glance, appwidget, android, keyguard, lockscreen]
---

# Widget Architecture

## Overview
RepeatKit provides home-screen and lock-screen widgets powered by Jetpack Glance (`androidx.glance.appwidget`). The widget architecture consolidates single, list, grid, and custom AI-styled widgets into an adaptive unified provider (`UnifiedShortcutWidget` / `ShortcutWidgetReceiver`).

## Widget Hosts & Capabilities
The widget is declared in `res/xml/shortcut_widget_info.xml`:
- **Categories**: `home_screen|keyguard` (`android:widgetCategory="home_screen|keyguard"`), allowing placement on both Android home screen launchers and lock-screen widget hosts.
- **Preview & Initial Layouts**:
  - `initialLayout="@layout/shortcut_widget_preview"`
  - `initialKeyguardLayout="@layout/shortcut_widget_preview"`
- **Update Frequency**: `updatePeriodMillis="1800000"` (30 minutes, the platform minimum) as a defensive safety net.

## Component Breakdown

### 1. Unified Glance Widget (`UnifiedShortcutWidget`)
- **Reactive State Composition (D1)**: Inside `provideGlance`, the widget resolves an initial snapshot guarded by `runCatching` (falling back to a setup state). Inside `provideContent`, it observes a reactive Room `Flow` (`observeWidgetSnapshot`) combining `widget_configs`, `automations`, and `custom_widget_templates` via `collectAsState`.
- **Session State Nudge**: Reads `currentState<Preferences>()[PREF_KEY_LAST_UPDATE]` inside `provideContent`, ensuring Glance's 45-second worker session re-renders when `updateAppWidgetState` writes a timestamp.
- **Adaptive Layout Modes**: Supports `AUTO` (responsive size-based), `SINGLE` (1 prominent shortcut), `LIST` (vertical list), and `GRID` (2x2 grid). Grid cells use weighted heights to prevent label clipping (D7).

### 2. Configuration Activity (`ShortcutWidgetConfigActivity`)
- **Pre-selection & Layout Retention (D3)**: Computes initial selection (`computeInitialSelection`) from existing `WidgetConfig` on re-configuration, preserving shortcut order and layout. Supports single shortcut pre-selection from intent extras when launched from in-app shortcut cards.
- **State Preservation**: Persists `selectedIds` across rotation and configuration changes using Compose `Saver`.
- **Cancellation Contract (D8)**: Sets `RESULT_CANCELED` with `EXTRA_APPWIDGET_ID` upfront in `onCreate`.
- **Non-destructive Creation**: Opening the shortcut creator ("Create a shortcut") retains the configuration activity on the back stack instead of finishing, allowing seamless return to widget setup.

### 3. Pinning Flows (`ShortcutWidgetPinRequest` & `ShortcutWidgetPinReceiver`)
- **Custom Widget Template Pinning (D2)**: When pinning a custom-styled widget from `CreateWidgetScreen`, the inserted `CustomWidgetTemplate` ID is passed via `EXTRA_TEMPLATE_ID` in the success-callback `PendingIntent`.
- **Pin Receiver Binding**: `ShortcutWidgetPinReceiver` decodes `appWidgetId`, `automationId`, and `templateId`, populating `WidgetConfig` with template styling (label, colorKey, iconKey) before triggering a widget refresh.

### 4. Lock-Screen Safety & Keyguard Trampoline
- **Tap Routing (`WidgetTapRouter`)**: When a widget tile is tapped, `RunAutomationCallback` checks device lock status (`KeyguardManager.isKeyguardLocked`) and reversibility (`WidgetTapRouter.isIrreversible`).
- **Confirmation & Irreversibility**: Analyzed via `ShortcutRunSafety.confirmationEffects` (detects destructive/outward actions such as sending messages, making phone calls, or mutating HTTP POST/PUT/DELETE requests).
- **Keyguard Dismissal Trampoline (`WidgetUnlockTrampolineActivity`)**: If locked and irreversible, the tap launches a transparent activity that calls `KeyguardManager.requestDismissKeyguard` and executes the shortcut with `ShortcutRunner` only after successful authentication. Harmless shortcuts or unlocked taps run directly in the background.

### 5. Data Synchronization & Pruning (D6)
- **Room Invalidation Tracker**: `AppDatabase.installWidgetRefreshObserver` monitors `"automations"`, `"widget_configs"`, and `"custom_widget_templates"`. Changes trigger serialized widget refreshes.
- **Deleted Shortcut Pruning (`pruneConfigs`)**: When automations are deleted, stale automation IDs are pruned from `WidgetConfig` rows so widgets don't point at missing shortcuts.
- **Package Update**: `ShortcutWidgetReceiver` handles `ACTION_MY_PACKAGE_REPLACED` to refresh all widgets upon app install/update.

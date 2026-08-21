---
title: Widget Architecture
type: architecture
author: antigravity_gemini_3_1_pro
tags: [widgets, glance, appwidget, android]
---

# Widget Architecture

## Overview
The application supports adding widgets to the Android home screen via Jetpack Glance. There are currently six widget providers declared in the `AndroidManifest.xml`, consisting of five legacy widgets and one unified adaptive widget.

## Widget Providers

### Current Widget
- **`ShortcutWidgetReceiver`**: The current, unified adaptive widget. Consolidates multiple widget styles into a single receiver.
  - **Metadata**: `res/xml/shortcut_widget_info.xml`
  - **Config Activity**: `ShortcutWidgetConfigActivity`
  - **Room Binding Table**: `widget_configs` (`WidgetConfig`), with `sourceType = "UNIFIED"`.
    Verified on-device: pinning writes a row such as `(28, 'UNIFIED', '[2]')`. The legacy
    `widget_bindings` table is NOT used by this provider and stays empty for new widgets.

### Legacy Widgets
These are retained to avoid breaking users' existing home screen widgets, but are deprecated in favor of `ShortcutWidgetReceiver`.

- **`AutomationWidgetReceiver`**: Minimal single tile rendering a single bound automation.
  - **Metadata**: `res/xml/automation_widget_info.xml`
  - **Config Activity**: `AutomationWidgetConfigActivity`
  - **Room Binding Table**: `WidgetBinding`
- **`ShortcutsListWidgetReceiver`**: Multi-row scrollable Glance widget rendering up to 4 shortcuts.
  - **Metadata**: `res/xml/shortcuts_list_widget_info.xml`
  - **Config Activity**: `ShortcutsListWidgetConfigActivity`
  - **Room Binding Table**: `WidgetListBinding`
- **`CustomWidgetReceiver`**: User-styled single Glance tile supporting background color and icon customization.
  - **Metadata**: `res/xml/custom_widget_info.xml`
  - **Config Activity**: `CustomWidgetConfigActivity`
  - **Room Binding Table**: `CustomWidgetBinding`
- **`GridWidgetReceiver`**: 2-column grid Glance widget displaying up to 6 custom widget templates.
  - **Metadata**: `res/xml/grid_widget_info.xml`
  - **Config Activity**: `GridWidgetConfigActivity`
  - **Room Binding Table**: `GridWidgetBinding`
- **`GreetingWidgetReceiver`**: Personalized widget rendering a time-of-day-aware greeting with user name.
  - **Metadata**: `res/xml/greeting_widget_info.xml`
  - **Config Activity**: `GreetingWidgetConfigActivity`
  - **Room Binding Table**: `GreetingWidgetBinding`

## The Pin Flow
Users can pin the adaptive widget directly from the app via an explicit pin flow:
1. **`ShortcutWidgetPinRequest`**: Invoked to request a pin from the `AppWidgetManager`.
2. **`ShortcutWidgetPinReceiver`**: A broadcast receiver that listens for the pin result success callback.
3. **`refreshShortcutWidget`**: Once pinned, the app must bind the configuration and force a Glance refresh so the widget renders its payload.

### The Glance-Id Race Condition (Known Issue & Fix)
The single most confusing race condition in this layer occurs during the pin flow. When a widget is pinned, Android successfully binds the widget and the app writes its configuration row. However, the subsequent forced redraw often asks Glance for the new widget's `GlanceId` *before* Glance's internal registry has tracked it.

`getGlanceIdBy` throws an exception during this window. Previously, this exception vanished inside the broadcast receiver's coroutine and the widget remained stuck on "Tap to set up" forever.

**Fix:** `refreshShortcutWidget` now handles this by briefly retrying the `GlanceId` lookup. If it still fails, it falls back to redrawing *every* instance of that widget class, and logs the failure instead of swallowing the exception.

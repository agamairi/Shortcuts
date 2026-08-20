---
title: Theme Mode Preference
type: architecture
author: codex
tags: [android, datastore, settings, theme, compose]
---

# Theme Mode Preference

`ThemePreferences` persists a `ThemeMode` value (`system`, `light`, or `dark`) in the existing
Preferences DataStore. Missing or invalid values resolve safely to `SYSTEM`.

`SettingsViewModel` observes that flow as immutable `StateFlow<ThemeMode>` and writes changes
through `updateThemeMode`. `MainActivity` independently collects the persisted flow and passes
the current mode into `ui.theme.ShortcutsTheme`, so changing the setting re-composes the entire
navigation tree immediately and remains effective after process restart.

The existing accent preference remains stored separately under its original key and continues to
provide the Material primary accent; the approved warm palette remains the source for the
dashboard and widget-picker surfaces.

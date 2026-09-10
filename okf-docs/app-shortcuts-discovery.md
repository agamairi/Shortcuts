---
title: App Shortcuts Discovery
type: architecture
author: Antigravity
tags: [shortcuts, launcher, app-intent]
---

# App Shortcuts Discovery

RepeatKit can now read static and dynamic shortcuts provided by other apps (like Chrome's "New Incognito tab") and expose them as standalone actions when building a manual shortcut.

## Approach
Because RepeatKit is not a Launcher app, it cannot always read dynamic shortcuts via `LauncherApps` due to missing `hasShortcutHostPermission()`. 
Instead, we implemented a dual-path discovery in `AppShortcutCatalog`:
1. **Primary path (Static shortcuts)**: We query the package manager for an app's launcher intent, retrieve its `android.app.shortcuts` meta-data resource, obtain the `Resources` context for the package, and parse the XML file manually using `XmlResourceParser`. This bypasses the launcher permission and works universally for static shortcuts.
2. **Opportunistic path (Dynamic shortcuts)**: We opportunistically check `launcherApps.hasShortcutHostPermission()`. If granted (e.g. on a custom ROM or via root), we merge dynamic shortcuts into the list. Otherwise, we silently fallback to static-only.

## Limitations
- **Dynamic Shortcuts**: Without the `shortcutHostPermission`, we cannot discover dynamic shortcuts (shortcuts added programmatically by an app at runtime).
- **Target Class**: Android's `Intent` inside shortcuts typically contains a `targetClass` attribute. Our data layer's `Action` model does not currently have a field to represent an intent's ComponentName/Class. We populate the action with `packageName`, `intentAction`, and `url`, but omit `targetClass`. This might cause some shortcuts to fail if they rely exclusively on an implicit component routing that isn't handled by their primary action.

## UI Integration
The `ManualBuilderScreen` allows picking an App. If the selected app has discovered shortcuts, a sub-dialog allows the user to choose between standard launching or invoking one of the discovered shortcuts. 


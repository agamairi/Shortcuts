---
title: Shortcut Recorder Architecture
type: architecture
author: antigravity_gemini_3_1_pro
tags: [recorder, accessibility, compose, architecture, multi-selector]
---

# Shortcut Recorder Architecture

## Overview
The shortcut recorder captures user actions across other Android apps using the Accessibility Service and converts them into executable `Action` steps.

## Components

- **`AutomationRecorder`**: Process-level façade for the recording session. It receives raw `AccessibilityEvent` data from `AutomationAccessibilityService`, creates `RecorderEvent` values, derives labels from accessibility nodes, and forwards them to `RecorderSessionOwner`. It resolves the device's default home launcher once per process and passes that package name to the Android-free session owner.
- **`RecorderSessionOwner`** (`RecorderSession.kt`): Maintains the in-progress action sequence and recording state as state flows. It persists changes immediately through a `RecorderSessionStore`.
- **`RecorderSessionService`**: Foreground-lifetime owner for an active process-level session. It lets recording continue while the UI is backgrounded and posts/count-updates the recording notification.
- **`RecorderSessionStorePreferences`**: Synchronously persists the captured actions to private SharedPreferences so that the session can be restored on app or service restart.
- **`RecorderStopReceiver`**: A broadcast receiver triggered from the notification's "Stop" action, which stops the recording session and cleans up resources.
- **`RecorderScreen`**: The Jetpack Compose UI that manages the recorder workflow. It handles disclosure consent, accessibility enablement, and displays the recorded steps for review. It observes `AccessibilityStatusChecker` on `ON_RESUME`.
- **`RecorderSessionController`**: UI helper that checks whether the accessibility service is active before invoking a recorder start callback; it is a class, not the recorder interface.

## Foreground Notification Channel

On Android 8.0 and later, `RecorderSessionNotifier` creates the **Shortcut Recorder** channel with id `recorder_channel_v2`. The preceding `recorder_channel` used `IMPORTANCE_LOW`, which Android presents in the collapsed **Silent** notification section. The v2 channel uses `IMPORTANCE_DEFAULT` so the ongoing recording notification is eligible to be visibly presented outside that grouping, while `setSound(null, null)` and `enableVibration(false)` keep the channel audibly and haptically quiet by default. Users can still change a channel's settings in Android system settings.

The id change is required for existing installs: once Android has created a notification channel, its importance and alerting behaviour are immutable to the app. Reusing `recorder_channel` would retain its already-created low-importance settings; creating `recorder_channel_v2` makes Android create the intended channel configuration. Both the recording and accessibility-disconnected notifications use this channel.

Implementation: `app/src/main/java/com/shortcuts/app/service/RecorderSessionService.kt:97-175`.

## Action Capture & Multi-Selector Fallback
When a node is tapped, text is changed, or the screen is scrolled, the accessibility-based recorder captures it as `CLICK`, `TEXT_CHANGE`, or `SCROLL`. `TYPE_WINDOW_STATE_CHANGED` is captured as `APP_CHANGE` without requiring an accessibility node; it carries the foreground package and event time. `AutomationRecorder` determines the real home launcher once with `Intent(ACTION_MAIN).addCategory(CATEGORY_HOME)` and `PackageManager.resolveActivity(..., MATCH_DEFAULT_ONLY)`, caches that package for the process, and gives it to `RecorderSessionOwner` with every event.

### App-switch classification

`RecorderSessionOwner.processAppChangeLocked` uses three pieces of foreground state while recording: `lastSeenForegroundPackage`, `lastAppBeforeLauncherPackage`, and `pendingLauncherClick`. That state is reset when a session starts, stops, is cleared/restored, or is set up for a test.

- A normal launcher click is first appended as a `UI_AUTOMATION` tap and retained as `pendingLauncherClick`. On the next `APP_CHANGE`, the pending reference is consumed. For a non-empty, non-recorder, non-System-UI package that is not the launcher and is not the recorded app’s return from home, the owner records `ActionType.APP_INTENT` when this is the first foreground package, the prior package is the launcher, or a pending launcher click exists. If that pending tap is still the last recorded action, it is removed before the app intent is appended. This is what turns a launcher-icon tap followed by a genuinely new app into one durable app-launch step instead of a fragile launcher-coordinate/selector tap plus a duplicate step.
- When the foreground first changes to the launcher, the previous non-launcher package is saved in `lastAppBeforeLauncherPackage`. If the next non-launcher package equals that saved package, it is a return from home to the same app: no `APP_INTENT` is added, and a pending launcher tap is discarded when it is still the final action.
- A window/activity change whose package equals `lastSeenForegroundPackage` adds no app-launch action. Changes to this app or System UI are ignored, and going home alone only updates the foreground tracking state. A launcher tap with no qualifying subsequent app change remains its original normal tap.

Implementation: `app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt:108-209`; `app/src/main/java/com/shortcuts/app/service/RecorderSession.kt:107-221`.

To prevent replay failures due to dynamically changing node properties, the recorder captures multiple selectors for every UI element interacted with. A captured node becomes an `Action` with the following fields:
- `targetText`
- `targetContentDescription`
- `targetNodeId`
- `targetClassName`
- `screenX` and `screenY` (Coordinate tap fallback)

Unidentifiable taps without useful view metadata are still captured but marked as `UNRESOLVED` to prevent them from silently vanishing.

## Limitations / Known Bugs
- The recording notification is not re-posted when the app restarts with a session already active, so the Stop action is lost until recording is stopped from inside the app.
- Android revokes this app's accessibility service on force-stop, and ADB cannot re-grant it once the app is flagged under restricted settings; it must be granted from Settings.

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
- **`RecorderSessionService`**: Foreground-lifetime owner for an active process-level session. It lets recording continue while the UI is backgrounded and posts/count-updates the recording notification. It only posts the normal finished notification for an explicit user finish; disconnect and discard stops retain or discard data without falsely claiming a shortcut was recorded.
- **`RecorderSessionStorePreferences`**: Synchronously persists the captured actions to private SharedPreferences so that the session can be restored on app or service restart.
- **`RecorderStopReceiver`**: A broadcast receiver triggered from the notification's "Stop" action, which stops the recording session and cleans up resources.
- **`MarkTapReceiver`**: A broadcast receiver triggered from the notification's "Mark a tap" action, which arms `TapMarkOverlay` via `AutomationAccessibilityService.instance`.
- **`TapMarkOverlay`**: Manages a full-screen, semi-transparent `TYPE_ACCESSIBILITY_OVERLAY` window attached via `AutomationAccessibilityService`'s `WindowManager`. When armed, tapping anywhere on the overlay captures exact screen coordinates (`rawX`/`rawY`), queries the underlying accessibility node tree for target metadata, appends a manual tap action to the active recording session, and auto-dismisses immediately.
- **`RecordingHudOverlay`**: Shows a small live recording/step-count indicator. Its accessibility overlay window is `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`, so it cannot intercept taps, swipes, or keyboard focus from the foreground app.
- **`RecorderScreen`**: The Jetpack Compose UI that manages the recorder workflow. It handles disclosure consent, accessibility enablement, and displays the recorded steps for review. It observes `AccessibilityStatusChecker` on `ON_RESUME`.
- **`RecorderSessionController`**: UI helper that checks whether the accessibility service is active before invoking a recorder start callback; it is a class, not the recorder interface.

## Foreground Notification Channel

On Android 8.0 and later, `RecorderSessionNotifier` creates the **Shortcut Recorder** channel with id `recorder_channel_v2`. The preceding `recorder_channel` used `IMPORTANCE_LOW`, which Android presents in the collapsed **Silent** notification section. The v2 channel uses `IMPORTANCE_DEFAULT` so the ongoing recording notification is eligible to be visibly presented outside that grouping, while `setSound(null, null)` and `enableVibration(false)` keep the channel audibly and haptically quiet by default. Users can still change a channel's settings in Android system settings.

The id change is required for existing installs: once Android has created a notification channel, its importance and alerting behaviour are immutable to the app. Reusing `recorder_channel` would retain its already-created low-importance settings; creating `recorder_channel_v2` makes Android create the intended channel configuration. Both the recording and accessibility-disconnected notifications use this channel.

Implementation: `app/src/main/java/com/shortcuts/app/service/RecorderSessionService.kt:97-175`.

## Action Capture & Multi-Selector Fallback
The recorder is deliberately hybrid but non-intercepting. Normal capture remains semantic and passive through Android accessibility events. `TapMarkOverlay` is the explicit, one-tap fallback for apps that publish no semantic click. `RecordingHudOverlay` is display-only and never catches or relays input; a global catch-and-`dispatchGesture` overlay cannot follow a finger in real time and would break scrolling and the soft keyboard.

`AutomationRecorder` translates raw accessibility callbacks into Android-free `RecorderEvent` values. Click, long-click, text-change, focus, scroll, window-state, and window-content events are subscribed. Text-selection changes are recognized as cursor/focus noise and return before node text is inspected or a recorded action is created. `TYPE_WINDOW_STATE_CHANGED` remains an `APP_CHANGE` carrying the foreground package and event time. The default launcher and active IME package are resolved at the Android boundary and passed into `RecorderSessionOwner`.

### Field-edit sessions

`TYPE_VIEW_TEXT_CHANGED` contains the field's full new value, so `RecorderSessionOwner` buffers only the latest event in a burst. A field is identified by package name, view ID, and class name—not by its live text, which changes on every keystroke. The buffer is committed as one `UI_AUTOMATION` / `TYPE_TEXT` action after 400 ms of quiet, when focus moves to another field, before another interaction, on a window change, or when recording stops. `textInput` is therefore the final full field value. If a field's visible text equals that value, it is excluded as a replay selector; stable ID, package, class, and content-description selectors remain.

### Submit capture

The recorder emits `UI_AUTOMATION` / `PRESS_ENTER` with the selectors from the edited field when any of these signals occurs without an intervening click:

- a physical Enter or numpad-Enter key is released (the service observes but does not consume it);
- an accessibility click from the active IME has the semantic label Enter, Go, Search, Send, Done, or Next;
- the edited app reports a same-package window transition;
- the edited app reports a window-content transition after the text burst has been quiet for 400 ms.

The last two are fallbacks for soft keyboards that do not expose their action key. An ordinary click, scroll, manual tap, focus move, app switch, or stop closes the candidate without inventing Enter.

### Long-press capture

`TYPE_VIEW_LONG_CLICKED` becomes one `UI_AUTOMATION` / `LONG_PRESS` action with the same selectors as a tap. The shared click debounce prevents a follow-up click callback for the same gesture from creating a second step.

### Missing-tap suggestion

Some Compose-rendered apps transition between screens without emitting `TYPE_VIEW_CLICKED`. While a recording is active, `SilentTapSuggestionTracker` watches window-state changes for this pattern and shows a dismissible Toast suggesting **Mark a tap** when no click was captured in the preceding two seconds. It waits three seconds after recording starts, ignores transitions to or from RepeatKit itself, and uses a 12-second cooldown so normal window churn cannot spam the user. This is deliberately only a low-key heuristic: system dialogs and automatic navigation can still qualify.

### Mark-a-Tap Overlay Fallback

Certain UI frameworks (such as Jetpack Compose in Google Messages) do not emit `TYPE_VIEW_CLICKED` accessibility events when elements are tapped, causing passive event recording to capture nothing.

To overcome this limitation, the recorder provides an active fallback mechanism alongside the passive accessibility-event pipeline:
1. The user taps **Mark a tap** in the persistent recording notification (or via the missing-tap suggestion prompt).
2. `MarkTapReceiver` receives the broadcast intent and calls `TapMarkOverlay.arm(AutomationAccessibilityService.instance)`.
3. A full-screen, semi-transparent `TYPE_ACCESSIBILITY_OVERLAY` is presented on screen.
4. When the user taps target UI through the overlay, `TapMarkOverlay` captures the tap coordinates (`rawX`/`rawY`), best-effort queries the accessibility tree at that coordinate for node metadata (`text`, `contentDescription`, `viewId`, `className`), appends a manual tap step via `AutomationRecorder.appendManualTap`, and tearlessly removes itself.
5. If no tap occurs within 20 seconds (or if recording is stopped via `TapMarkOverlay.disarm()`), `TapMarkOverlay` auto-dismisses.

### App-switch classification

`RecorderSessionOwner.processAppChangeLocked` tracks `lastSeenForegroundPackage`, `lastAppBeforeLauncherPackage`, and the complete pending launcher-click burst. That state is reset when a session starts, stops, is cleared/restored, or is set up for a test.

- Launcher clicks are appended provisionally as normal taps. On the next qualifying `APP_CHANGE`, the entire contiguous pending launcher-click burst is removed before the app intent is appended. Tracking the burst, rather than only the most recent icon tap, removes launcher-surface events such as a reported “Home” click as well as the icon click that actually opened the app.
- When the foreground first changes to the launcher, the previous non-launcher package is saved in `lastAppBeforeLauncherPackage`. If the next non-launcher package equals that saved package, it is a return from home to the same app: no `APP_INTENT` is added, and a pending launcher tap is discarded when it is still the final action.
- A window/activity change whose package equals `lastSeenForegroundPackage` adds no app-launch action, but can close an active field-edit session as `PRESS_ENTER`. Changes to this app or System UI are ignored, and going home alone only updates the foreground tracking state. A launcher tap with no qualifying subsequent app change remains its original normal tap.

Implementation: `app/src/main/java/com/shortcuts/app/service/AutomationRecorder.kt:108-209`; `app/src/main/java/com/shortcuts/app/service/RecorderSession.kt:107-221`.

To prevent replay failures due to dynamically changing node properties, the recorder captures multiple selectors for every UI element interacted with. A captured node becomes an `Action` with the following fields:
- `targetText`
- `targetContentDescription`
- `targetNodeId`
- `targetClassName`
- `screenX` and `screenY` (Coordinate tap fallback)
- `recordedDisplayWidth`, `recordedDisplayHeight`, `recordedDisplayRotation`, and `recordedDensityDpi` (the display context required to validate a coordinate fallback before replay)

Unidentifiable taps without useful view metadata are still captured but marked as `UNRESOLVED` to prevent them from silently vanishing. Coordinate fallback is only attempted when its recorded display context still matches the current display; an older step without this metadata, or a changed rotation, density, or display size, asks the user to re-record rather than tapping an uncertain location.

## Recorder exit ownership

Recording remains active while the app is merely backgrounded, with its foreground notification as the visible control surface. It does not remain active after the user explicitly backs out of the recorder route: `RecorderScreen` intercepts system Back and `RecorderCleanupViewModel` provides a lifecycle fallback, both stopping the session as `DISCARDED` and disarming `TapMarkOverlay`. This prevents an invisible full-screen overlay or foreground recording from outliving the recording UI.

## Post-Recording Review

Once a recording session is stopped, the user reviews the captured steps in the editor UI. This screen provides two execution controls before saving:
- **Check**: Invokes `ShortcutRunner(context).preflight(...)` (as documented in `execution-engine.md`) on the captured steps and displays a `ShortcutPreflightDialog` with the results, without attempting to run the shortcut.
- **Run now**: Also runs preflight first. If preflight is clear, it checks `ShortcutRunSafety.confirmationEffects`. If there are no irreversible or outward-facing effects (see `execution-engine.md`'s Safety section), it runs immediately as an editor draft. If there are effects, it presents an `EditorRunConfirmationDialog` to gain explicit user consent before execution.

## Limitations / Known Bugs
- The recording notification is not re-posted when the app restarts with a session already active, so the Stop action is lost until recording is stopped from inside the app.
- Android revokes this app's accessibility service on force-stop, and ADB cannot re-grant it once the app is flagged under restricted settings; it must be granted from Settings.
- Capturing raw screen touches as a fallback for missing semantic tap events is currently not possible without significantly degrading user experience. The Android `TouchInteractionController` API (API 33+) requires enabling `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`, which forces the entire device into TalkBack-style "explore-by-touch" mode (requiring double-taps to activate elements), breaking normal single-tap interaction.

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
- **`TouchCaptureOverlay`**: The default transparent `TYPE_ACCESSIBILITY_OVERLAY` recorder. It buffers one completed touch, snapshots the app node below it, records a `RecorderEvent` through the same owner path as passive events, then steps aside and relays the finished gesture with `dispatchGesture`.
- **`MarkTapReceiver` / `TapMarkOverlay`**: The manual one-tap helper retained for the explicit passive fallback mode only. It is intentionally a no-op while overlay capture is active.
- **`RecordingHudOverlay`**: Shows a small live recording/step-count indicator. Its accessibility overlay window is `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`, so it cannot intercept taps, swipes, or keyboard focus from the foreground app.
- **`RecorderScreen`**: The Jetpack Compose UI that manages the recorder workflow. It handles disclosure consent, accessibility enablement, and displays the recorded steps for review. It observes `AccessibilityStatusChecker` on `ON_RESUME`.
- **`RecorderSessionController`**: UI helper that checks whether the accessibility service is active before invoking a recorder start callback; it is a class, not the recorder interface.

## Foreground Notification Channel

On Android 8.0 and later, `RecorderSessionNotifier` creates the **Shortcut Recorder** channel with id `recorder_channel_v2`. The preceding `recorder_channel` used `IMPORTANCE_LOW`, which Android presents in the collapsed **Silent** notification section. The v2 channel uses `IMPORTANCE_DEFAULT` so the ongoing recording notification is eligible to be visibly presented outside that grouping, while `setSound(null, null)` and `enableVibration(false)` keep the channel audibly and haptically quiet by default. Users can still change a channel's settings in Android system settings.

The id change is required for existing installs: once Android has created a notification channel, its importance and alerting behaviour are immutable to the app. Reusing `recorder_channel` would retain its already-created low-importance settings; creating `recorder_channel_v2` makes Android create the intended channel configuration. Both the recording and accessibility-disconnected notifications use this channel.

Implementation: `app/src/main/java/com/shortcuts/app/service/RecorderSessionService.kt:97-175`.

## Action Capture & Multi-Selector Fallback
The recorder is hybrid. Its default `OVERLAY` mode actively captures completed single-finger gestures and relays them after release; passive accessibility remains responsible for text, focus, keyboard submit, keys, and application/window changes. This records controls rendered by Compose, Flutter, React Native, WebView, and custom canvases even where no `TYPE_VIEW_CLICKED` is sent. `PASSIVE` is a stored fallback mode for troubleshooting and retains the previous semantic-event plus Mark-a-tap behavior.

`AutomationRecorder` translates raw accessibility callbacks into Android-free `RecorderEvent` values. Click, long-click, text-change, focus, scroll, window-state, and window-content events are subscribed. Text-selection changes are recognized as cursor/focus noise and return before node text is inspected or a recorded action is created. `TYPE_WINDOW_STATE_CHANGED` remains an `APP_CHANGE` carrying the foreground package and event time. The default launcher and active IME package are resolved at the Android boundary and passed into `RecorderSessionOwner`.

### Field-edit sessions

`TYPE_VIEW_TEXT_CHANGED` contains the field's full new value, so `RecorderSessionOwner` buffers only the latest event in a burst. A field is identified by package name, view ID, and class name—not by its live text, which changes on every keystroke. The buffer is committed as one `UI_AUTOMATION` / `TYPE_TEXT` action after 400 ms of quiet, when focus moves to another field, before another interaction, on a window change, or when recording stops. `textInput` is therefore the final full field value. If a field's visible text equals that value, it is excluded as a replay selector; stable ID, package, class, and content-description selectors remain.

### Submit capture

The recorder emits `UI_AUTOMATION` / `PRESS_ENTER` with the selectors from the edited field when any of these signals occurs without an intervening click:

- a physical Enter or numpad-Enter key is released (the service observes but does not consume it);
- an accessibility click from the active IME has the semantic label Enter, Go, Search, Send, Done, or Next;

Content and window changes are never legacy fallback evidence for Enter. An ordinary click, scroll,
manual tap, focus move, app switch, or stop closes the candidate without inventing Enter. Engine v2
may mark a narrowly evidenced soft-keyboard effect as `INFERRED_IME_ACTION`; it requires the exact
focused field after a committed value, not merely a dialog, popup, or a progressively emptied field.

### Long-press capture

`TYPE_VIEW_LONG_CLICKED` becomes one `UI_AUTOMATION` / `LONG_PRESS` action with the same selectors as a tap. The shared click debounce prevents a follow-up click callback for the same gesture from creating a second step.

### Capture-and-relay overlay

`TouchCaptureOverlay` is added through the live accessibility service's `WindowManager` while a recording is active in `OVERLAY` mode. It never covers the status bar or 3-button navigation bar. When an IME is visible its lower edge is moved to the IME window's top, so all keyboard interaction stays native. It becomes non-touchable for the notification shade, RepeatKit itself, and permission/package-install dialogs.

On `ACTION_DOWN`, it starts an immutable accessibility snapshot on the dedicated capture worker. That worker hit-tests the deepest visible node under the user's actual coordinate and selects a nearby actionable ancestor when it is not a near-full-window container, retaining the touch point as coordinate fallback. On release, it classifies the samples as a tap, long press, swipe, or cancelled multi-touch; taps become `CLICK`, holds become `LONG_PRESS`, and swipes become `SCROLL` with vector-derived deltas. It waits no more than 120 ms for the already-started snapshot before stepping aside; a late/missing result never blocks relay. These events enter `RecorderSessionOwner.processEvent`, preserving launcher-collapse, app-switch, text flush, review, save, and replay behavior.

Before relay, the overlay sets `FLAG_NOT_TOUCHABLE`, waits one Choreographer frame plus 20 ms (with a 35 ms watchdog), then injects the recorded path. Taps use 50 ms, long presses use 600–1500 ms, and swipes use an 80–700 ms bounded duration with at most twelve timing-ordered points. A 40 ms post-relay settle recomputes the safe region before touchability returns. The overlay draws a tap ripple or short fading swipe trail so delayed, post-release scrolling is intentional rather than surprising. While this mode is active, passive click/long-click/scroll callbacks are disabled to prevent relay duplicates; text, focus, IME submit, keys, and window changes remain enabled.

The notification intentionally omits **Mark a tap** in `OVERLAY` mode. In `PASSIVE` mode it returns, and `TapMarkOverlay` remains available as the manual fallback.

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
- Capture-and-relay intentionally cannot move a list under the user's physical finger: Android cancels injected gestures that overlap an active physical touch. A swipe therefore starts immediately after lift, and its velocity, IME bounds, shade handling, and relay step-aside timing need on-device validation on each target Android build.

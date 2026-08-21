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

- **`AutomationRecorder`**: Implements the `RecorderSessionController` interface. Responsible for receiving raw `AccessibilityEvent` data from the `AutomationAccessibilityService`, translating them into `RecorderEvent`, deriving labels from the accessibility node tree (view id → own text → descendants → ancestors), and dispatching them to the active `RecorderSession`.
- **`RecorderSession`**: Maintains the in-progress sequence of recorded actions and exposes them via a state flow. It persists the sequence in real-time to avoid data loss.
- **`RecorderSessionService`**: A foreground service that owns the `RecorderSession`. It allows recording to continue when the main app is backgrounded and handles posting the recording notification.
- **`RecorderSessionStorePreferences`**: Synchronously persists the captured actions to private SharedPreferences so that the session can be restored on app or service restart.
- **`RecorderStopReceiver`**: A broadcast receiver triggered from the notification's "Stop" action, which stops the recording session and cleans up resources.
- **`RecorderScreen`**: The Jetpack Compose UI that manages the recorder workflow. It handles disclosure consent, accessibility enablement, and displays the recorded steps for review. It observes `AccessibilityStatusChecker` on `ON_RESUME`.
- **`RecorderSessionController`**: Interface abstracting the recorder functionality for starting/stopping and processing events.

## Action Capture & Multi-Selector Fallback
When a node is tapped, text is changed, or the screen is scrolled, the accessibility-based recorder captures it. A `RecorderEventType` (`CLICK`, `TEXT_CHANGE`, `SCROLL`) is matched with the context.

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

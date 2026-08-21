---
title: Execution Engine Architecture
type: architecture
author: antigravity_gemini_3_1_pro
tags: [execution, services, architecture, actions]
---

# Execution Engine Architecture

## Overview
The Execution Engine is responsible for running a sequence of `Action` steps. It provides isolation from the UI lifecycle by running in a foreground service, ensuring that multi-step shortcuts are not killed if the user navigates away or taps a widget.

## Components

- **`AutomationExecutionService`**: A foreground service that receives the automation ID, locks execution to prevent overlapping runs, and guarantees the process stays alive.
- **`ActionExecutorService`**: The orchestrator that processes each `Action` in sequence.
- **`AutomationAccessibilityService`**: An `AccessibilityService` that executes UI interactions directly on other apps.
- **`ExecutionResult` & `StepResult`**: Execution now returns a per-step result (Success / Failed / NeedsPermission / Skipped). Multi-step chains report exactly which step failed and why.

## Action Routing Pipeline & Field Binding

`ActionExecutorService` routes each `Action` based on its `ActionType`, reading specific fields:

1. **`SYSTEM_TOGGLE`**: Toggles settings like WiFi or Bluetooth.
   - Reads: `target` (e.g. "wifi", matched case-insensitively), `state` (e.g. "ON", "OFF").
2. **`APP_INTENT`**: Launches Android applications.
   - Reads: `packageName`, `intentAction`.
3. **`HTTP_REQUEST`**: Fires an asynchronous network request.
   - Reads: `url`, `method`. (Carries back the HTTP status code and truncated response body).
4. **`WAIT`**: Pauses the execution sequence.
   - Reads: `delayMillis`. Execution runs off the main thread to prevent ANRs.
5. **`SEND_MESSAGE`**: Dispatches an `ACTION_SENDTO` intent.
   - Reads: `target` (recipient number), `textInput` (message body).
6. **`DIAL_NUMBER`**: Dispatches an `ACTION_DIAL` intent.
   - Reads: `target` (phone number).
7. **`UI_AUTOMATION`**: Hands off execution to `AutomationAccessibilityService` to interact with screen elements.
   - Reads: `uiActionType` (`CLICK`, `LONG_CLICK`, `SCROLL`, `TEXT_INPUT`, `GLOBAL`), `targetText`, `targetContentDescription`, `targetNodeId`, `targetClassName`, `textInput`, `scrollDirection`, `globalAction`, `screenX`, `screenY`.

## Replay Reliability & Fallbacks

Replaying UI actions is inherently flaky because apps take time to load and dynamically change their UI. The engine uses two main fallbacks:

1. **Replay Retry (`awaitTargetNode`)**: A replayed shortcut runs faster than a person taps, so targets frequently haven't appeared yet. The engine now waits up to 5 seconds (`nodeWaitTimeoutMillis`) for the target node to show up on screen before failing.
2. **Coordinate-Tap Fallback**: If every semantic selector (`targetText`, `targetContentDescription`, `targetNodeId`, `targetClassName`) misses, the engine will fall back to executing a raw coordinate tap using the captured `screenX` and `screenY` boundaries.

Failures provide detailed diagnostics ("This screen couldn't be automated") explaining what target was being looked for and why it failed.

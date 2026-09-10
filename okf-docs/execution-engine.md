---
title: Execution Engine Architecture
type: architecture
author: antigravity_claude_opus
tags: [execution, services, architecture, actions, shortcut-runner, preflight, safety]
---

# Execution Engine Architecture

## Overview
The Execution Engine is responsible for running a sequence of `Action` steps. It provides isolation from the UI lifecycle by running in a foreground service, ensuring that multi-step shortcuts are not killed if the user navigates away or taps a widget. Every shortcut execution — whether triggered from the dashboard, a widget, the editor, or a future automated trigger — enters through a single gateway (`ShortcutRunner`) that enforces admission, preflight validation, and safety confirmation before the foreground service starts.

## Components

- **`ShortcutRunner`** (`app/src/main/java/com/shortcuts/app/service/ShortcutRunner.kt`): The single product-level execution gateway. All shortcut runs — from the dashboard (`ShortcutRunSource.DASHBOARD`), a home-screen widget (`WIDGET`), the manual/AI/recorder editor (`EDITOR`), or an automated trigger (`TRIGGER`) — pass through `ShortcutRunner`. It owns shortcut lookup, active-state enforcement, bounded admission (via `ShortcutRunCoordinator`), preflight validation, and foreground-service startup. The foreground service owns the accepted run's lifetime after admission.
- **`ShortcutPreflight`**: A pure, side-effect-free validator that checks every action in a shortcut against the current device environment (`ShortcutPreflightEnvironment`) *before* execution begins. It has no reference to `ActionExecutorService`, OkHttp, or an Android activity launcher — it only asks read-only questions (is the app installed? is accessibility active? does the device have a flashlight?). See "Preflight Validation" below.
- **`ShortcutRunSafety`**: Analyses a shortcut's action list and returns human-readable descriptions of irreversible or outward-facing effects (sending a message, dialling a number, non-GET/HEAD HTTP requests, interacting with another app's screen) that require user confirmation before the run proceeds. One concise confirmation covers all effects; per-step prompts are deliberately avoided.
- **`AutomationExecutionService`**: A foreground service that receives automation IDs, keeps its foreground lifetime alive while queued widget runs drain, and reports each outcome. It verifies the run lease from `ShortcutRunCoordinator` on start and releases it in its `finally` block.
- **`ActionExecutorService`**: The orchestrator that processes each `Action` in sequence. A fair process-wide reentrant lock serializes every chain (and direct single-step API use), so concurrent triggers wait rather than cancelling one another's accessibility gesture or mixing failure state. It receives the run's `ExecutionCancellation` handle and checks `isCancellationRequested` before each step.
- **`AutomationAccessibilityService`**: An `AccessibilityService` that executes UI interactions directly on other apps.
- **`ExecutionResult` & `StepResult`**: Execution returns a per-step result (Success / Failed / NeedsPermission / Skipped). Multi-step chains report exactly which step failed and why.

## ShortcutRunOutcome

Every run request returns a strongly typed `ShortcutRunOutcome` (sealed interface). Callers must never infer an outcome from a boolean or from whether a service happened to be started.

**Saved-shortcut outcomes:**
- `Started(request, shortcutName, runId)` — Admitted and foreground service started.
- `RejectedAlreadyRunning(request)` — Another shortcut currently holds the execution lease.
- `RejectedInactive(request, shortcutName)` — Shortcut is deactivated.
- `RejectedMissingShortcut(request)` — Shortcut ID not found in database.
- `Failed(request, reason, userMessage)` — Lookup or service-launch failure.

**Editor-draft outcomes:**
- `EditorStarted(request, runId)` — Draft admitted and foreground service started.
- `EditorRejectedAlreadyRunning(request)` — Another shortcut is currently running.
- `EditorRejectedInvalidDraft(request, userMessage)` — Empty name, empty action list, or wrong source.
- `EditorConfirmationRequired(request, effects)` — Unconfirmed external side effects present.
- `EditorRejectedPreflight(request, report)` — Blocking preflight validation issues.
- `EditorFailed(request, reason, userMessage)` — Service-launch failure.

`ShortcutRunOutcome.userMessage()` provides a single consistent wording source for every outcome, used by dashboard toasts and widget feedback.

## Preflight Validation

`ShortcutPreflight` checks every action for predictable failures using a `ShortcutPreflightEnvironment` (read-only device queries: `canLaunchPackage`, `hasPermission`, `hasNotificationPolicyAccess`, `canModifySystemSettings`, `isAccessibilityServiceActive`, `hasFlashlight`, `sdkInt`). Checks include:

- **`SYSTEM_TOGGLE`**: valid target/state, camera permission for flashlight, DND access, `WRITE_SETTINGS` for auto-rotate, `BLUETOOTH_CONNECT` on API 31+, and platform-restriction advisories (WiFi/airplane/location open Android settings).
- **`APP_INTENT`**: package exists and has a launch intent.
- **`HTTP_REQUEST`**: valid method, valid URI host, HTTPS policy compliance.
- **`UI_AUTOMATION`**: accessibility service active, valid selectors or coordinate fallback, recognized global actions.
- **`WAIT`**: duration within 1ms–10 minutes.
- **`SEND_MESSAGE` / `DIAL_NUMBER`**: non-blank recipient.

Issues are reported as `ShortcutPreflightIssue(actionIndex, category, userMessage, blocking, resolution?)`. Each issue has a `ShortcutIssueCategory` (e.g. `REQUIRED_FIELD_MISSING`, `PERMISSION_REQUIRED`, `APP_NOT_AVAILABLE`) and optionally a `ShortcutPreflightResolution` enum pointing to the correct system-settings intent. The report's `canRun` property is true only when no blocking issues exist.

Preflight is available through `ShortcutRunner.preflight(actions)` without acquiring an admission lease, so editors can validate a draft at any time without side effects.

## Admission & Cooperative Cancellation

`ShortcutRunCoordinator` (process-wide singleton) enforces single-run admission via an `AtomicReference<ShortcutRunLease?>`. Only one shortcut can hold the execution lease at a time; concurrent requests receive `RejectedAlreadyRunning`. Each lease carries an `ExecutionCancellation` handle; `ShortcutRunCoordinator.cancel(runId)` sets the cancellation flag and interrupts the executor's thread if it is in a cancellable delay.

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
   - Reads: `uiActionType` (`CLICK`, `LONG_CLICK`, `SCROLL`, `TEXT_INPUT`, `GLOBAL`), `targetText`, `targetContentDescription`, `targetNodeId`, `targetClassName`, `textInput`, `scrollDirection`, `globalAction`, `screenX`, `screenY`, and the recorded display width, height, rotation, and density for a coordinate fallback.

## Notification Channels

`AutomationExecutionService` uses two separate notification channels:

- **`shortcut_execution`** (`IMPORTANCE_LOW`): The silent, ongoing foreground-service notification shown while a shortcut is actively running. Importance is low so it stays in the collapsed/silent notification section and does not interrupt the user.
- **`shortcut_attention`** (`IMPORTANCE_DEFAULT`): Failure and needs-attention notifications ("<name> couldn't finish") are posted on this separate channel with `PRIORITY_HIGH` so they are heads-up capable and visible to the user. Previously both notification types shared the low-importance channel, which silently suppressed failure notifications. On Android 13+ (`TIRAMISU`), if `POST_NOTIFICATIONS` is not granted, the service falls back to a Toast.

Implementation: `app/src/main/java/com/shortcuts/app/service/AutomationExecutionService.kt`.

## Replay Reliability & Fallbacks

Replaying UI actions is inherently flaky because apps take time to load and dynamically change their UI. The engine uses two main fallbacks:

1. **Replay Retry (`awaitTargetNode`)**: A replayed shortcut runs faster than a person taps, so targets frequently haven't appeared yet. The engine now waits up to 5 seconds (`nodeWaitTimeoutMillis`) for the target node to show up on screen before failing.
2. **Coordinate-Tap Fallback**: If every semantic selector (`targetText`, `targetContentDescription`, `targetNodeId`, `targetClassName`) misses, the engine can fall back to a raw coordinate tap. The fallback runs only when the recorded display width, height, rotation, and density are compatible with the current display; otherwise it fails with a re-record instruction instead of tapping an uncertain location. The accessibility-service XML requests `canPerformGestures="true"`, which Android requires for the dispatched gesture callback to complete.

Failures provide detailed diagnostics ("This screen couldn't be automated") explaining what target was being looked for and why it failed.

---
title: Replay Pipeline
type: architecture
author: antigravity_gemini_3_1_pro
tags: [replay, automation, accessibility, executor]
---

# Replay Pipeline

## Replay Step Vocabulary
The automation engine supports a specific vocabulary of UI actions for replaying recorded steps:
- **`TAP`**: Clicks a UI element.
- **`TYPE_TEXT`**: Injects text into an input field.
- **`SCROLL`**: Scrolls the view forwards or backwards.
- **`LONG_PRESS`**: Dispatches a long-click accessibility action, or falls back to a long-press gesture at the node's centre or recorded coordinates.
- **`PRESS_ENTER`**: Submits the entry of a focused text field.

Target nodes for these actions are typically resolved by waiting and checking the accessibility tree (e.g., using a bounded wait with `TARGET_LOOKUP_RETRIES`) based on the recorded target criteria (text, content description, or node ID). For `PRESS_ENTER`, if the recorded target cannot be found, it falls back to resolving the currently focused input node.

## PRESS_ENTER Action Details
The `PRESS_ENTER` action is designed to submit text input.
- On **API >= R (Android 11+)**, it dispatches the `AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER` action to the target field.
- On **API < R**, older Android versions do not expose `ACTION_IME_ENTER`. As a fallback, the action dispatches `AccessibilityNodeInfo.ACTION_CLICK` on the focused text field, which is the only available accessibility method to prompt the IME to commit.

*Unit Testing Note:* The `ACTION_IME_ENTER` action ID is injected via a constructor seam (`imeEnterActionId`) because the standard Android unit test stub (`android.jar`) leaves this `static final` field `null`. This injection allows unit tests to supply a fake integer and avoid a `NullPointerException` while preserving the authentic framework behavior on actual devices.

## Failure-Feedback Path
If a replay step fails or the overall automation run cannot complete, the user is notified via the `ReplayFailurePill`. 
- The pill is displayed as a non-touchable `TYPE_ACCESSIBILITY_OVERLAY` that appears on the screen and auto-dismisses after a short duration.
- If the accessibility service is unavailable (e.g., it was disabled or crashed), the system uses a standard system notification as a fallback to deliver the failure message.

## Unrecognised Actions
If an unrecognised `uiActionType` is encountered during execution, the system fails loudly and aborts the run rather than silently skipping the step. This prevents the automation from continuing in an unexpected state and provides clear feedback to the user that the recorded action is invalid or unsupported.

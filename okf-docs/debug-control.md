---
title: Debug Build & Remote Control
type: architecture
author: codex_gpt_5_6_luna
tags: [debug, testing, adb, e2e]
---

# Debug Build & Remote Control

## Side-by-side debug build

The debug build uses the normal Kotlin namespace, `com.shortcuts.app`, but adds the
application ID suffix `.dev` and version suffix `-dev`. Its installed package is therefore
`com.shortcuts.app.dev` and its user-visible app/widget labels are `RepeatKit Dev`. It can be
installed beside the release `com.shortcuts.app` package without replacing its data, widgets,
or accessibility-service registration.

The debug-only `DebugControlActivity` is a no-UI, translucent activity. It is exported only by
the debug manifest so an adb test harness can start it. The translucent theme keeps the activity
in the foreground long enough for a command that starts a foreground service to be admitted; the
activity then finishes while the application-scoped debug coroutine continues the command.

## Commands

The activity component is:

```text
com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity
```

Start a fresh recording, clearing any previous recorder session first:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd start_recording
```

Stop the current recording with the normal user-finished stop reason:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd stop_recording
```

Dump the current recorder actions as pretty JSON:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd dump_recording
```

Save the current actions under an exact name. An existing exact-name automation is updated in
place, preserving its ID; a new name creates a new automation. The recorder session is cleared
after a successful save:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd save --es name "Pixel smoke test"
```

Run a saved automation through the same `ShortcutRunner` widget entry point used by a home-screen
widget tap. The command waits for the foreground execution service's result and writes a receipt:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd run --es name "Pixel smoke test"
```

List all saved automations with their ID, exact name, decoded step count, and active state:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd list
```

Delete the automation with the exact name:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd delete --es name "Pixel smoke test"
```

Clear the current recorder session without saving it:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd clear_recording
```

## Output files

All files are private to the debug app and live under `filesDir/debug/`:

- `last_recording.json` — the current `AutomationRecorder.recordedActions.value`, serialized with
  the same `Action` JSON shape used by `ActionConverter`.
- `automations.json` — an array of `{id, name, stepCount, isActive}` entries.
- `last_run.json` — the selected automation ID/name, one zero-based result entry per step with
  action type, UI action type, success, failure reason, and message, plus accessibility replay
  `matches`, `waits`, and `startUptimeMillis`/`endUptimeMillis`.

The harness can read them without touching the release install:

```sh
adb exec-out run-as com.shortcuts.app.dev cat files/debug/last_recording.json
adb exec-out run-as com.shortcuts.app.dev cat files/debug/automations.json
adb exec-out run-as com.shortcuts.app.dev cat files/debug/last_run.json
```

## Completion contract

When a command finishes, the debug activity logs exactly one single-line completion record with
tag `RepeatKitDebug`:

```text
CMD_DONE {"cmd":"run","ok":true,"file":"debug/last_run.json","error":null}
```

`ok` is `false` on invalid input, lookup failure, persistence failure, service-start refusal, or
run failure. `error` is a short single-line explanation. Commands without an output file report
`"file":null`. The harness should wait for this log line before reading an output file.

None of this control activity, parser, debug scope, debug manifest, debug labels, or debug output
contract ships in release builds. The small release-side `RunResultBus` is an in-process completion
flow with no release logging or persisted behavior; it is inert unless the debug build observes it.

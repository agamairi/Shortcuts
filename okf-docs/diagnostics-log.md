---
title: Diagnostics Log
type: architecture
author: antigravity
tags: [diagnostics, logging, privacy, export]
---

# Diagnostics Log

## Overview & Purpose

RepeatKit includes an on-device, privacy-preserving diagnostics log that records what the app does during recording and shortcut replay. When a user encounters an issue (such as a shortcut failing during execution or recording missing steps), they can share a diagnostic log file directly from Settings or via debug automation.

The diagnostics system is built with strict privacy and performance guarantees:
- **Zero user-content capture:** It never records typed text or on-screen text (node text, content descriptions, hints, window titles, URLs). Only structural metadata (package names, activity classes, resource IDs, gesture kinds, text lengths, timings and error codes) is logged. The one user-authored value it does include is the **shortcut name** in `replay/run_start`, so the owner can say which shortcut failed; the Settings text says so.
- **Strictly on-device until user-shared:** Diagnostics logs remain in private app storage. No background network transmissions or telemetry occur.
- **Asynchronous & lightweight:** All log writes (except crash handler calls) run asynchronously on a dedicated single-threaded daemon executor.
- **Bounded storage:** Rotated log files have a hard cap on size and file count to protect device storage.
- **User control:** The log can be toggled on/off or cleared permanently at any time from Settings.

---

## File Storage & Rotation

Diagnostics files live in internal private storage:
```text
<filesDir>/diagnostics/
  ├── diag-current.jsonl
  ├── diag-1729180000000.jsonl
  └── diag-1729170000000.jsonl
```

### Rotation Rules
- **Max file size:** ~1,000,000 bytes (1 MB). When `diag-current.jsonl` exceeds this limit on a write, it is renamed to `diag-<timestamp>.jsonl` and a new `diag-current.jsonl` is created.
- **Max log files:** 5 files total (1 current + 4 rotated). When the count exceeds 5, the oldest rotated files are pruned.
- **Total storage cap:** ~5 MB maximum.
- **Backup exclusion:** Excluded from Android Auto Backup and cloud transfer in `res/xml/full_backup_content.xml` and `res/xml/data_extraction_rules.xml`.

---

## Line Format & Schema

Every log line is a standalone, newline-delimited JSON object (JSON Lines):
```json
{"ts":"2026-10-17T14:32:01.123-04:00","up":1845210,"cat":"recording","ev":"interaction","source":"touch","stepType":"tap","targetPkg":"com.example.app","targetViewId":"com.example.app:id/submit"}
```

### Common Fields
| Field | Type | Description |
|---|---|---|
| `ts` | String | ISO-8601 timestamp with local time zone offset. |
| `up` | Number | Monotonic system uptime in milliseconds (`SystemClock.uptimeMillis()` or equivalent). |
| `cat` | String | Event category (`app`, `service`, `recording`, `replay`, `widget`). |
| `ev` | String | Event name. |

### Sanitization Limits
- Strings longer than 500 characters are truncated to 500 characters.
- Lists and arrays longer than 50 items are truncated to 50 items.
- Map keys longer than 100 characters are truncated.

---

## Event Catalog

### 1. App Lifecycle & Crashes (`cat: "app"`)
- `app/process_start`: App process started.
  - Fields: `versionName`, `versionCode`, `buildType`, `manufacturer`, `model`, `sdkInt`, `release`.
- `app/crash`: Synchronously captured uncaught exception before process termination.
  - Fields: `thread`, `exceptionClass`, `message` (max 300 chars), `stack` (up to 40 formatted frames), `causes` (up to 3 causes with 10 frames each).

### 2. Accessibility Service (`cat: "service"`)
- `service/connected`: Accessibility service bound and connected.
  - Fields: `serviceClass`, `captureMode` ("v2_touch", "v2_accessibility", or "legacy").
- `service/destroyed`: Accessibility service unbound / destroyed.
  - Fields: `serviceClass`.
- `service/interrupted`: Accessibility service interrupted by the system.
  - Fields: `serviceClass`.

### 3. Recording (`cat: "recording"`)
- `recording/start`: Recording session started.
  - Fields: `captureMode`.
- `recording/stop`: Recording stopped.
  - Fields: `reason` ("user_stopped", "back_pressed", etc.), `legacyActionCount`, `v2Steps` (count).
- `recording/discard`: Recording discarded without saving.
- `recording/save`: Recording saved to database.
  - Fields: `format` ("v2" or "legacy"), `why` ("edited", "v2_unavailable", or "v2").
- `recording/interaction`: Recorded touch or accessibility interaction.
  - Fields: `source` ("touch" or "accessibility"), `stepType` ("tap", "scroll", etc.), `targetPkg`, `targetViewId`.
- `recording/text_commit`: Text committed to an editable field.
  - Fields: `targetPkg`, `targetViewId`, `textLength` (integer count only — never text contents).
- `recording/ime_action`: Explicit IME action (e.g. "search", "go").
  - Fields: `action`.
- `recording/ime_inferred`: IME search/go inferred from typing pause or screen transition.
  - Fields: `action`.
- `recording/app_switch`: Foreground app/activity transition.
  - Fields: `targetPkg`, `targetActivity`.
- `recording/global_action`: System button / global action triggered.
  - Fields: `action` ("back", "home", etc.).
- `recording/back_inferred`: Back action inferred from navigation.
- `recording/degraded`: Capture degraded (e.g. missing snapshot source).
  - Fields: `reason`.
- `recording/compiled`: Workflow compiler finished building V2 workflow.
  - Fields: `stepCount`, `stepTypes` (list of step type strings).
- `recording/compile_failed`: Workflow compilation error.
  - Fields: `exceptionClass`, `message`.

### 4. Replay & Execution (`cat: "replay"`)
- `replay/run_start`: Execution started for a shortcut.
  - Fields: `shortcutId`, `shortcutName`, `engine` ("v2" or "legacy"), `stepCount`, `source` ("widget", "editor", "dashboard", "debug", or "unknown").
- `replay/run_engine`: Engine V2 workflow executed.
  - Fields: `workflowId`, `stepCount`, `stepTypes`.
- `replay/trace`: Compacted replay trace events.
  - Fields: `run` (runId), plus compacted trace fields (`type`, `step`, `count`, `firstUp`, `lastUp`, `outcome`, `candidateCount`, `bestScore`, `breakdown`, `passed`, etc.).
  - Polling bursts of identical window decisions, match evaluations, and postcondition checks are collapsed into single entries with `count`, `firstUp`, and `lastUp`.
- `replay/run_finished`: Shortcut run completed.
  - Fields: `automationId`, `runId`, `success` (Boolean), `steps` (list of step outcome strings like "ok", "failed:WINDOW_NOT_FOUND", "skipped"), `failingStepIndex`, `failureReason`, `userMessage` (truncated to 300 chars).

### 5. Widgets (`cat: "widget"`)
- `widget/tap`: Widget tapped by user.
  - Fields: `shortcutId`.
- `widget/error`: Widget execution or configuration exception.
  - Fields: `exceptionClass`, `where` (class/method name).

---

## Export & Share Flow

1. User opens **Settings → Diagnostics**.
2. Tap **Share log**:
   - Flushes pending in-memory / async writes.
   - Cleans previous export files in `<cacheDir>/diagnostics-export/`.
   - Writes a new export file `repeatkit-diagnostics-<timestamp>.txt`.
   - Writes metadata header lines prefixed with `# `:
     - RepeatKit version, build type, and version code.
     - Device manufacturer, model, Android release, and SDK level.
     - Export timestamp and device time zone.
     - Engine V2 enabled flag.
     - Accessibility service active status.
     - Default keyboard package and default launcher package.
     - Privacy statement confirming structural-only logging.
   - Followed by all log lines concatenated oldest-to-newest.
3. Launches the system share sheet using `androidx.core.content.FileProvider` with authority `<applicationId>.diagnostics`.

---

## Debug Control

In debug builds (`com.shortcuts.app.dev`), the diagnostics log can be exported via adb without UI interaction:

```sh
adb shell am start -n com.shortcuts.app.dev/com.shortcuts.app.debug.DebugControlActivity --es cmd dump_diagnostics
```

The exported file is copied to `<filesDir>/debug/diagnostics_export.txt` and completed with:
```text
CMD_DONE {"cmd":"dump_diagnostics","ok":true,"file":"debug/diagnostics_export.txt","error":null}
```
Pull or view the file:
```sh
adb exec-out run-as com.shortcuts.app.dev cat files/debug/diagnostics_export.txt
```

---
title: "On-device E2E Harness"
type: architecture
author: codex_gpt_5_6_luna
tags: [testing, e2e, adb, reliability]
---

# On-device E2E Harness

`tools/e2e/rk_e2e.py` is a Python 3.9+ standard-library harness that runs on
the Mac and drives the fixture app on one Android device over `adb`. It records
each scenario with the Dev build, replays the saved automation, and compares
the fixture's event stream from both phases.

## Running it

From the repository root:

```text
python3 tools/e2e/rk_e2e.py doctor [--serial SERIAL]
python3 tools/e2e/rk_e2e.py setup-accessibility [--serial SERIAL]
python3 tools/e2e/rk_e2e.py run --all [--repeat 2] [--keep-going]
python3 tools/e2e/rk_e2e.py run --scenario views_basic text_views --serial SERIAL
python3 tools/e2e/rk_e2e.py report tools/e2e/results/20261016-120000
```

`doctor` checks the connected device, Android version, both package installs,
the Dev accessibility service, screen state, unlock state, and gesture
navigation. `setup-accessibility` appends the Dev service to the existing
colon-separated Android setting and enables accessibility; it is idempotent
and never removes another service.

Results are written beneath
`tools/e2e/results/<YYYYmmdd-HHMMSS>/<scenario>/run<k>/`. Each run contains
`recording.json`, `run.json`, `truth.jsonl`, `replay.jsonl`, `verdict.json`,
`logcat.txt`, and record/replay screenshots. The timestamp directory also
contains `summary.json` and `summary.md`.

## Scenario format

Each `tools/e2e/scenarios/<id>.json` has an id, description, ordered human
steps, and `expect: "recorded"`:

```json
{
  "id": "example",
  "description": "A human interaction",
  "steps": [
    {"tap": {"text": "Save"}},
    {"tap": {"desc": "Settings gear"}},
    {"tap": {"id": "com.repeatkit.fixtures:id/icon_unlabeled"}},
    {"tap": {"text": "Open", "row_of": "Item 45"}},
    {"type": "hello world"},
    {"key": "ENTER"},
    {"swipe": {"from": [540, 1800], "to": [540, 700], "ms": 350}},
    {"scroll_until": {"text": "Item 45"}},
    {"wait_ms": 800}
  ],
  "expect": "recorded"
}
```

Element targets are resolved from a fresh uiautomator hierarchy. Dumps retry
five times with a 400 ms backoff, and `row_of` selects the repeated target
whose vertical center is closest to the anchor row. Text input uses adb's
`%s` space encoding. Every step receives a 700 ms settle delay by default;
`run --settle-ms` changes it.

## Verdict rules

The record phase is the ground truth. The replay phase is compared as ordered
`(event, target, value)` triples. Sequence numbers are ignored and `open`
events are ignored. PASS requires the two normalized streams to be identical.
On a mismatch, `verdict.json` reports the first divergence and the first
failing step found in `last_run.json`.

Recording quality is reported separately from replay correctness. The harness
marks `record_quality: "missing_steps"` when the recording has fewer UI steps
than the scenario's human taps/types/gestures or contains an `UNRESOLVED`
step. This distinguishes a recorder omission from a replay mismatch.

## Safety boundary

The Dev package is centralized as `com.shortcuts.app.dev` and asserted to end
in `.dev`. Debug commands and private-file reads use that package only. The
harness never installs, uninstalls, or clears a package; fixture reset uses
`am force-stop` only. Accessibility setup appends to the existing setting
instead of replacing it, which is important on devices owned by other
accessibility services.

## Reliability metric

The primary reliability metric is **scenario pass rate**:

```text
passed scenario runs / total scenario runs
```

The summary also reports per-scenario pass/fail counts, the number of
record-quality issues, and median replay duration. A command exits zero only
when every executed run passes.

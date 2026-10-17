#!/usr/bin/env python3
"""On-device record/replay harness for RepeatKit.

The command-line entry point intentionally keeps all adb interaction behind
``Adb``.  The matching, parsing, scenario validation, and verdict logic are
plain functions so they can be tested without a phone.
"""

import argparse
import json
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path


FIXTURE_PACKAGE = "com.repeatkit.fixtures"
DEV_PACKAGE = "com.shortcuts.app.dev"
DEV_COMPONENT = DEV_PACKAGE + "/com.shortcuts.app.service.AutomationAccessibilityService"
DEBUG_ACTIVITY = DEV_PACKAGE + "/com.shortcuts.app.debug.DebugControlActivity"
SCENARIO_DIR = Path(__file__).resolve().parent / "scenarios"
RESULTS_DIR = Path(__file__).resolve().parent / "results"
DEBUG_COMMAND_TIMEOUT = 30
RUN_TIMEOUT = 120
DEBUG_TAG = "RepeatKitDebug"
FIXTURE_TAG = "FixtureEvent"
LOGCAT_TAGS = [
    "FixtureEvent:I",
    "RepeatKitDebug:I",
    "AutomationAccService:I",
    "AutomationRecorder:I",
    "V2Capture:D",
    "TouchCapture:D",
    "AndroidRuntime:E",
    "*:S",
]


class HarnessError(RuntimeError):
    """An expected harness or device-operation failure."""


def safe_package(package):
    """Reject the production package everywhere a package is addressed."""
    if package == "com.shortcuts.app" or (package.startswith("com.shortcuts.app.") and package != DEV_PACKAGE):
        raise HarnessError("Safety guard refused production RepeatKit package: %s" % package)
    return package


assert DEV_PACKAGE.endswith(".dev")


class Adb:
    """Small adb adapter; production code never invokes subprocess directly."""

    def __init__(self, serial=None, executable="adb"):
        self.serial = serial
        self.executable = executable

    def _command(self, args):
        command = [self.executable]
        if self.serial:
            command.extend(["-s", self.serial])
        command.extend(args)
        return command

    def run(self, args, timeout=30, check=True):
        try:
            return subprocess.run(
                self._command(args),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
                check=check,
            )
        except subprocess.TimeoutExpired as exc:
            raise HarnessError("adb command timed out: %s" % " ".join(self._command(args))) from exc
        except subprocess.CalledProcessError as exc:
            stderr = exc.stderr.decode("utf-8", "replace").strip()
            raise HarnessError("adb command failed (%s): %s" % (exc.returncode, stderr)) from exc

    def text(self, args, timeout=30, check=True):
        result = self.run(args, timeout=timeout, check=check)
        return result.stdout.decode("utf-8", "replace")

    def shell(self, args, timeout=30, check=True):
        args = list(args)
        # Force-stopping RepeatKit (dev or not) makes Android switch its accessibility service
        # off, which only the user can switch back on. Never do it from the harness.
        # Force-stopping any app that owns an accessibility service makes Android unbind that
        # service, and only the user can bring it back. The fixture app is the only safe target.
        if args[:2] == ["am", "force-stop"] and any(a != FIXTURE_PACKAGE for a in args[2:]):
            raise HarnessError("Refusing to force-stop %s: only the fixture app may be force-stopped" % " ".join(args[2:]))
        return self.run(["shell"] + args, timeout=timeout, check=check)

    def shell_text(self, args, timeout=30, check=True):
        return self.text(["shell"] + list(args), timeout=timeout, check=check)

    def exec_out(self, args, timeout=30, check=True):
        return self.run(["exec-out"] + list(args), timeout=timeout, check=check).stdout


def select_serial(requested=None):
    """Select exactly one online device without touching any device state."""
    output = Adb().text(["devices"], timeout=15)
    devices = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    if requested:
        if requested not in devices:
            raise HarnessError("Requested serial is not connected: %s" % requested)
        return requested
    if len(devices) == 1:
        return devices[0]
    if not devices:
        raise HarnessError("No online adb device found. Connect and unlock the phone, or pass --serial.")
    raise HarnessError("More than one device is connected; pass --serial from: %s" % ", ".join(devices))


def merge_enabled_services(current, service=DEV_COMPONENT):
    """Append a service to Android's colon-separated setting, preserving all entries."""
    entries = []
    for entry in (current or "").strip().split(":"):
        if entry and entry.lower() != "null" and entry not in entries:
            entries.append(entry)
    if service not in entries:
        entries.append(service)
    return ":".join(entries)


def parse_bounds(bounds):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not match:
        return None
    left, top, right, bottom = [int(value) for value in match.groups()]
    if right < left or bottom < top:
        return None
    return (left, top, right, bottom)


def parse_ui_nodes(xml_text):
    """Return accessibility nodes with normalized selector fields and bounds."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise HarnessError("Could not parse uiautomator XML: %s" % exc) from exc
    nodes = []
    for element in root.iter("node"):
        nodes.append(
            {
                "text": element.attrib.get("text", ""),
                "desc": element.attrib.get("content-desc", ""),
                "id": element.attrib.get("resource-id", ""),
                "class": element.attrib.get("class", ""),
                "package": element.attrib.get("package", ""),
                "clickable": element.attrib.get("clickable", "") == "true",
                "bounds": parse_bounds(element.attrib.get("bounds", "")),
            }
        )
    return nodes


def _node_matches(node, locator):
    field_map = {
        "text": "text",
        "desc": "desc",
        "content-desc": "desc",
        "content_desc": "desc",
        "id": "id",
        "resource-id": "id",
        "resource_id": "id",
    }
    for locator_key, node_key in field_map.items():
        if locator_key in locator and locator[locator_key] != node[node_key]:
            return False
    return bool(node["bounds"])


def find_bounds(xml_text, locator):
    """Find a node's bounds, optionally choosing a repeated target by row alignment."""
    if not isinstance(locator, dict):
        raise HarnessError("Element locator must be an object")
    row_of = locator.get("row_of")
    if locator.get("unlabeled"):
        return _find_unlabeled_bounds(xml_text, locator)
    candidates = [node for node in parse_ui_nodes(xml_text) if _node_matches(node, locator)]
    if not candidates:
        raise HarnessError("Element not found: %s" % json.dumps(locator, sort_keys=True))
    if row_of is None:
        return candidates[0]["bounds"]

    row_nodes = [node for node in parse_ui_nodes(xml_text) if node["text"] == row_of and node["bounds"]]
    if not row_nodes:
        raise HarnessError("Row anchor not found: %s" % row_of)
    row_center_y = (row_nodes[0]["bounds"][1] + row_nodes[0]["bounds"][3]) / 2.0
    return min(
        (node["bounds"] for node in candidates),
        key=lambda bounds: abs(((bounds[1] + bounds[3]) / 2.0) - row_center_y),
    )


def _find_unlabeled_bounds(xml_text, locator):
    """Find a clickable node with no text, description or id (e.g. a bare Compose Box).

    `below` names a labelled anchor; the nearest unlabeled clickable under it is chosen, which is
    how a person would describe "the blank square under the gear icon".
    """
    nodes = parse_ui_nodes(xml_text)
    unlabeled = [
        node for node in nodes
        if node["clickable"] and node["bounds"] and not node["text"] and not node["desc"] and not node["id"]
        and node["package"] == FIXTURE_PACKAGE
    ]
    anchor_locator = locator.get("below")
    if anchor_locator:
        anchors = [node for node in nodes if _node_matches(node, anchor_locator)]
        if not anchors:
            raise HarnessError("Anchor not found: %s" % json.dumps(anchor_locator, sort_keys=True))
        anchor_bottom = anchors[0]["bounds"][3]
        unlabeled = [node for node in unlabeled if node["bounds"][1] >= anchor_bottom - 4]
        unlabeled.sort(key=lambda node: node["bounds"][1])
    if not unlabeled:
        raise HarnessError("Element not found: %s" % json.dumps(locator, sort_keys=True))
    return unlabeled[0]["bounds"]


def _extract_hierarchy(raw):
    text = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw
    start = text.find("<hierarchy")
    end = text.rfind("</hierarchy>")
    if start < 0 or end < start:
        return None
    return text[start : end + len("</hierarchy>")]


def dump_ui_xml(adb, retries=5, backoff=0.4):
    """Dump the current hierarchy, tolerating transient animation failures."""
    last_error = None
    for attempt in range(retries):
        try:
            xml = _extract_hierarchy(adb.exec_out(["uiautomator", "dump", "/dev/tty"], timeout=15))
            if xml:
                parse_ui_nodes(xml)
                return xml
            last_error = "uiautomator did not return a hierarchy"
        except (HarnessError, OSError) as exc:
            last_error = str(exc)
        try:
            adb.shell(["uiautomator", "dump", "/sdcard/window_dump.xml"], timeout=15)
            xml = _extract_hierarchy(adb.exec_out(["cat", "/sdcard/window_dump.xml"], timeout=15))
            if xml:
                parse_ui_nodes(xml)
                return xml
            last_error = "fallback uiautomator dump was empty"
        except (HarnessError, OSError) as exc:
            last_error = str(exc)
        if attempt + 1 < retries:
            time.sleep(backoff)
    raise HarnessError("Unable to dump accessibility hierarchy after %d attempts: %s" % (retries, last_error))


def wait_for_locator(adb, locator, timeout=30):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            return find_bounds(dump_ui_xml(adb), locator)
        except HarnessError as exc:
            last_error = exc
            time.sleep(0.4)
    raise HarnessError("Timed out waiting for %s: %s" % (json.dumps(locator), last_error))


def screen_size(adb):
    output = adb.shell_text(["wm", "size"], timeout=15)
    matches = re.findall(r"(\d+)x(\d+)", output)
    if matches:
        return int(matches[-1][0]), int(matches[-1][1])
    return 1080, 2400


def scroll_until(adb, locator, max_swipes=15):
    width, height = screen_size(adb)
    for _ in range(max_swipes + 1):
        try:
            return find_bounds(dump_ui_xml(adb), locator)
        except HarnessError:
            if _ == max_swipes:
                break
            x = width // 2
            adb.shell(["input", "swipe", str(x), str(int(height * 0.76)), str(x), str(int(height * 0.30)), "350"])
            time.sleep(0.4)
    raise HarnessError("scroll_until exhausted %d swipes for %s" % (max_swipes, json.dumps(locator)))


def encode_input_text(value):
    """Encode the adb input-text space convention without invoking a shell."""
    return str(value).replace(" ", "%s")


KEYCODES = {
    "ENTER": "KEYCODE_ENTER",
    "BACK": "KEYCODE_BACK",
    "HOME": "KEYCODE_HOME",
    "TAB": "KEYCODE_TAB",
    "ESC": "KEYCODE_ESCAPE",
}


def execute_step(adb, step, settle_ms=700):
    """Perform one human-style scenario step and apply the standard settle wait."""
    if not isinstance(step, dict) or len(step) != 1:
        raise HarnessError("Each scenario step must contain exactly one action: %s" % step)
    action, value = next(iter(step.items()))
    if action == "tap":
        left, top, right, bottom = wait_for_locator(adb, value)
        adb.shell(["input", "tap", str((left + right) // 2), str((top + bottom) // 2)])
    elif action == "long_press":
        left, top, right, bottom = wait_for_locator(adb, value)
        x, y = (left + right) // 2, (top + bottom) // 2
        adb.shell(["input", "swipe", str(x), str(y), str(x), str(y), "800"])
    elif action == "type":
        adb.shell(["input", "text", encode_input_text(value)])
    elif action == "key":
        key = str(value).upper()
        adb.shell(["input", "keyevent", KEYCODES.get(key, key if key.startswith("KEYCODE_") else "KEYCODE_" + key)])
    elif action == "swipe":
        start = value["from"]
        end = value["to"]
        duration = str(value.get("ms", 350))
        adb.shell(["input", "swipe", str(start[0]), str(start[1]), str(end[0]), str(end[1]), duration])
    elif action == "scroll_until":
        scroll_until(adb, value)
    elif action == "wait_ms":
        time.sleep(float(value) / 1000.0)
    else:
        raise HarnessError("Unsupported scenario action: %s" % action)
    time.sleep(float(settle_ms) / 1000.0)


def validate_scenario(data):
    if not isinstance(data, dict):
        raise HarnessError("Scenario must be a JSON object")
    if not isinstance(data.get("id"), str) or not data["id"]:
        raise HarnessError("Scenario id must be a non-empty string")
    if not isinstance(data.get("description"), str):
        raise HarnessError("Scenario %s needs a description" % data.get("id"))
    if not isinstance(data.get("steps"), list):
        raise HarnessError("Scenario %s needs a steps array" % data["id"])
    if data.get("expect") != "recorded":
        raise HarnessError("Scenario %s must use expect=recorded" % data["id"])
    for index, step in enumerate(data["steps"]):
        if not isinstance(step, dict) or len(step) != 1:
            raise HarnessError("Scenario %s step %d is invalid" % (data["id"], index))
    return data


def load_scenario(scenario_id):
    path = SCENARIO_DIR / (scenario_id + ".json")
    if not path.is_file():
        raise HarnessError("Unknown scenario: %s" % scenario_id)
    try:
        return validate_scenario(json.loads(path.read_text(encoding="utf-8")))
    except json.JSONDecodeError as exc:
        raise HarnessError("Invalid scenario JSON %s: %s" % (path, exc)) from exc


def load_all_scenarios():
    return [load_scenario(path.stem) for path in sorted(SCENARIO_DIR.glob("*.json"))]


def normalize_events(events):
    normalized = []
    for event in events or []:
        if event.get("event") == "open":
            continue
        normalized.append([event.get("event"), event.get("target"), event.get("value")])
    return normalized


def diff_event_stream(expected, actual):
    expected = normalize_events(expected)
    actual = normalize_events(actual)
    limit = min(len(expected), len(actual))
    for index in range(limit):
        if expected[index] != actual[index]:
            return {
                "equal": False,
                "index": index,
                "expected": expected[index],
                "got": actual[index],
                "expected_count": len(expected),
                "got_count": len(actual),
            }
    if len(expected) != len(actual):
        return {
            "equal": False,
            "index": limit,
            "expected": expected[limit] if limit < len(expected) else None,
            "got": actual[limit] if limit < len(actual) else None,
            "expected_count": len(expected),
            "got_count": len(actual),
        }
    return {"equal": True, "index": None, "expected": None, "got": None, "expected_count": len(expected), "got_count": len(actual)}


FIXTURE_EVENT_RE = re.compile(r"FixtureEvent(?:\([^)]*\))?\s*:\s*(\{.*\})\s*$")


def parse_fixture_events(log_text):
    events = []
    for line in log_text.splitlines():
        match = FIXTURE_EVENT_RE.search(line)
        if not match:
            continue
        try:
            event = json.loads(match.group(1))
        except json.JSONDecodeError:
            continue
        if isinstance(event, dict):
            events.append(event)
    return events


def scenario_human_step_count(scenario):
    return 1 + sum(1 for step in scenario["steps"] if next(iter(step)) in {"tap", "long_press", "type", "key", "swipe", "scroll_until"})


def _iter_dicts(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _iter_dicts(child)
    elif isinstance(value, list):
        for child in value:
            yield from _iter_dicts(child)


def recorded_ui_step_count(recording):
    count = 0
    for item in recording if isinstance(recording, list) else []:
        if not isinstance(item, dict):
            continue
        action_type = str(item.get("actionType", "")).upper()
        ui_type = str(item.get("uiActionType", "")).upper()
        if action_type == "UI_AUTOMATION" or ui_type in {"TAP", "TYPE_TEXT", "LONG_PRESS", "PRESS_ENTER", "SCROLL"}:
            count += 1
    return count


def has_unresolved(recording):
    for item in _iter_dicts(recording):
        for value in item.values():
            if isinstance(value, str) and "unresolved" in value.lower():
                return True
    return False


def record_quality(scenario, recording):
    issues = []
    if recorded_ui_step_count(recording) < scenario_human_step_count(scenario):
        issues.append(
            "recorded %d UI steps for %d human actions"
            % (recorded_ui_step_count(recording), scenario_human_step_count(scenario))
        )
    if has_unresolved(recording):
        issues.append("recording contains an UNRESOLVED step")
    return ("missing_steps" if issues else "ok"), issues


def extract_failing_step(run_result):
    for item in _iter_dicts(run_result):
        status = str(item.get("status", "")).lower()
        if item.get("ok") is False or status in {"fail", "failed", "error"}:
            return item
    return None


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_jsonl(path, values):
    path.write_text("".join(json.dumps(value, sort_keys=True) + "\n" for value in values), encoding="utf-8")


def parse_cmd_done(log_text, command):
    for line in reversed(log_text.splitlines()):
        match = re.search(r"CMD_DONE\s+(\{.*\})", line)
        if not match:
            continue
        try:
            payload = json.loads(match.group(1))
        except json.JSONDecodeError:
            continue
        if payload.get("cmd") == command:
            return payload
    return None


def wait_for_cmd(adb, command, timeout=DEBUG_COMMAND_TIMEOUT):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        output = adb.text(["logcat", "-d", "-s", DEBUG_TAG + ":I", "*:S"], timeout=15)
        payload = parse_cmd_done(output, command)
        if payload is not None:
            if not payload.get("ok"):
                raise HarnessError("RepeatKit command %s failed: %s" % (command, payload.get("error")))
            return payload
        time.sleep(0.4)
    raise HarnessError("Timed out waiting for RepeatKit command: %s" % command)


def debug_command(adb, command, name=None, timeout=DEBUG_COMMAND_TIMEOUT):
    safe_package(DEV_PACKAGE)
    args = ["shell", "am", "start", "-n", DEBUG_ACTIVITY, "--es", "cmd", command]
    if name is not None:
        args.extend(["--es", "name", name])
    adb.run(args, timeout=15)
    return wait_for_cmd(adb, command, timeout=timeout)


def pull_debug_json(adb, filename):
    safe_package(DEV_PACKAGE)
    raw = adb.exec_out(["run-as", DEV_PACKAGE, "cat", "files/debug/" + filename], timeout=30)
    text = raw.decode("utf-8", "replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise HarnessError("Invalid debug JSON %s: %s" % (filename, exc)) from exc


def pull_optional_debug_text(adb, filename):
    """Engine v2 artifacts exist only on builds that produce them; absence is not an error."""
    safe_package(DEV_PACKAGE)
    result = adb.run(["exec-out", "run-as", DEV_PACKAGE, "cat", "files/debug/" + filename], timeout=30, check=False)
    if result.returncode != 0:
        return None
    text = result.stdout.decode("utf-8", "replace") if isinstance(result.stdout, bytes) else result.stdout
    return text if text.strip() and "No such file" not in text else None


def capture_logcat(adb):
    return adb.text(["logcat", "-d", "-v", "brief", "-s"] + LOGCAT_TAGS, timeout=30)


def screenshot(adb, path):
    path.write_bytes(adb.exec_out(["screencap", "-p"], timeout=30))


def reset_fixture(adb, scenario_id, clear_log=True):
    safe_package(DEV_PACKAGE)
    safe_package(FIXTURE_PACKAGE)
    adb.shell(["am", "force-stop", FIXTURE_PACKAGE])
    adb.shell(["input", "keyevent", "KEYCODE_HOME"])
    debug_command(adb, "clear_recording")
    try:
        debug_command(adb, "delete", name="e2e_" + scenario_id)
    except HarnessError as exc:
        # A first run has nothing to delete; any other failure is still fatal.
        if "not found" not in str(exc):
            raise
    if clear_log:
        adb.run(["logcat", "-c"], timeout=15)


class DeviceNotReady(HarnessError):
    """The phone is asleep or locked; every scenario would fail for the same non-app reason."""


def ensure_device_ready(adb):
    power = adb.shell_text(["dumpsys", "power"])
    window = adb.shell_text(["dumpsys", "window"])
    awake = bool(re.search(r"mWakefulness=Awake", power))
    locked = bool(re.search(r"isKeyguardShowing=true|mShowingLockscreen=true", window))
    if not awake or locked:
        raise DeviceNotReady("Phone is %s — unlock it and keep the screen on, then re-run."
                             % ("asleep" if not awake else "locked"))


def run_one_scenario(adb, scenario, run_dir, settle_ms):
    run_dir.mkdir(parents=True, exist_ok=True)
    record_log = ""
    replay_log = ""
    recording = []
    run_result = {}
    replay_seconds = None
    error = None
    phase = "record"
    ensure_device_ready(adb)
    try:
        reset_fixture(adb, scenario["id"])
        # Like a person: open RepeatKit, start recording there, go Home, then open the app.
        # The recorder only records an app launch after it has seen the launcher come forward.
        adb.shell(["monkey", "-p", DEV_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
        time.sleep(1.5)
        debug_command(adb, "start_recording")
        adb.shell(["input", "keyevent", "KEYCODE_HOME"])
        time.sleep(1.0)
        adb.shell(["monkey", "-p", FIXTURE_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
        launcher_label = scenario.get("launcher_label", scenario["id"])
        # Wait for the fixture's start screen, then scroll to the scenario button like a person
        # would (the last buttons sit below the fold; that scroll is part of the recording).
        wait_for_locator(adb, {"text": "Views basic"}, timeout=30)
        execute_step(adb, {"scroll_until": {"text": launcher_label}}, settle_ms)
        execute_step(adb, {"tap": {"text": launcher_label}}, settle_ms)
        for index, step in enumerate(scenario["steps"]):
            try:
                execute_step(adb, step, settle_ms)
            except HarnessError as exc:
                raise HarnessError("record step %d failed: %s" % (index, exc)) from exc
        debug_command(adb, "stop_recording")
        debug_command(adb, "dump_recording")
        recording = pull_debug_json(adb, "last_recording.json")
        recording_v2 = pull_optional_debug_text(adb, "last_recording_v2.json")
        if recording_v2:
            (run_dir / "recording_v2.json").write_text(recording_v2, encoding="utf-8")
        debug_command(adb, "save", name="e2e_" + scenario["id"])
        screenshot(adb, run_dir / "record.png")
        record_log = capture_logcat(adb)

        adb.shell(["am", "force-stop", FIXTURE_PACKAGE])
        adb.shell(["input", "keyevent", "KEYCODE_HOME"])
        adb.run(["logcat", "-c"], timeout=15)

        phase = "replay"
        replay_started = time.monotonic()
        debug_command(adb, "run", name="e2e_" + scenario["id"], timeout=RUN_TIMEOUT)
        replay_seconds = time.monotonic() - replay_started
        run_result = pull_debug_json(adb, "last_run.json")
        trace = pull_optional_debug_text(adb, "last_trace.jsonl")
        if trace:
            (run_dir / "trace.jsonl").write_text(trace, encoding="utf-8")
        screenshot(adb, run_dir / "replay.png")
        replay_log = capture_logcat(adb)
    except HarnessError as exc:
        error = str(exc)
        try:
            if phase == "replay":
                replay_log = capture_logcat(adb)
                # A failed run still leaves its receipt and Engine v2 trace behind.
                try:
                    run_result = pull_debug_json(adb, "last_run.json")
                except HarnessError:
                    pass
                trace = pull_optional_debug_text(adb, "last_trace.jsonl")
                if trace:
                    (run_dir / "trace.jsonl").write_text(trace, encoding="utf-8")
            elif not record_log:
                record_log = capture_logcat(adb)
        except HarnessError:
            pass

    truth = parse_fixture_events(record_log)
    replay = parse_fixture_events(replay_log)
    quality, quality_issues = record_quality(scenario, recording)
    comparison = diff_event_stream(truth, replay)
    verdict = {
        "scenario": scenario["id"],
        "passed": bool(comparison["equal"] and error is None),
        "record_quality": quality,
        "record_quality_issues": quality_issues,
        "event_diff": comparison,
        "failing_step": extract_failing_step(run_result),
        "error": error,
        "replay_seconds": replay_seconds,
    }
    write_json(run_dir / "recording.json", recording)
    write_json(run_dir / "run.json", run_result)
    write_jsonl(run_dir / "truth.jsonl", truth)
    write_jsonl(run_dir / "replay.jsonl", replay)
    write_json(run_dir / "verdict.json", verdict)
    (run_dir / "logcat.txt").write_text(
        "===== RECORD =====\n" + record_log + "\n===== REPLAY =====\n" + replay_log,
        encoding="utf-8",
    )
    return verdict


def build_summary(results_dir):
    verdicts = []
    for path in sorted(results_dir.glob("*/run*/verdict.json")):
        try:
            verdicts.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError):
            continue
    grouped = {}
    for verdict in verdicts:
        grouped.setdefault(verdict.get("scenario", "unknown"), []).append(verdict)
    scenarios = []
    for scenario_id in sorted(grouped):
        items = grouped[scenario_id]
        durations = [item["replay_seconds"] for item in items if isinstance(item.get("replay_seconds"), (int, float))]
        scenarios.append(
            {
                "scenario": scenario_id,
                "runs": len(items),
                "pass": sum(1 for item in items if item.get("passed")),
                "fail": sum(1 for item in items if not item.get("passed")),
                "record_quality_issues": sum(1 for item in items if item.get("record_quality") != "ok"),
                "median_replay_seconds": statistics.median(durations) if durations else None,
            }
        )
    total = len(verdicts)
    passed = sum(1 for item in verdicts if item.get("passed"))
    return {
        "results_dir": str(results_dir),
        "total_runs": total,
        "passed": passed,
        "failed": total - passed,
        "pass_rate": (passed / total) if total else 0.0,
        "scenarios": scenarios,
    }


def summary_markdown(summary):
    lines = [
        "# RepeatKit E2E summary",
        "",
        "| Scenario | Runs | Pass | Fail | Record quality issues | Median replay seconds |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in summary["scenarios"]:
        median = "-" if row["median_replay_seconds"] is None else "%.2f" % row["median_replay_seconds"]
        lines.append("| %s | %d | %d | %d | %d | %s |" % (row["scenario"], row["runs"], row["pass"], row["fail"], row["record_quality_issues"], median))
    lines.extend(["", "Overall pass rate: %.1f%% (%d/%d runs)." % (summary["pass_rate"] * 100, summary["passed"], summary["total_runs"])])
    return "\n".join(lines) + "\n"


def print_summary(summary):
    print("Scenario                    Runs  Pass  Fail  Record issues  Median replay s")
    print("--------------------------  ----  ----  ----  --------------  ----------------")
    for row in summary["scenarios"]:
        median = "-" if row["median_replay_seconds"] is None else "%.2f" % row["median_replay_seconds"]
        print("%-26s  %4d  %4d  %4d  %14d  %16s" % (row["scenario"], row["runs"], row["pass"], row["fail"], row["record_quality_issues"], median))
    print("Overall pass rate: %.1f%% (%d/%d runs)" % (summary["pass_rate"] * 100, summary["passed"], summary["total_runs"]))


def write_summary(results_dir):
    summary = build_summary(results_dir)
    write_json(results_dir / "summary.json", summary)
    (results_dir / "summary.md").write_text(summary_markdown(summary), encoding="utf-8")
    print_summary(summary)
    return summary


def command_doctor(args):
    serial = select_serial(args.serial)
    adb = Adb(serial)
    enabled = adb.shell_text(["settings", "get", "secure", "enabled_accessibility_services"])
    power = adb.shell_text(["dumpsys", "power"])
    window = adb.shell_text(["dumpsys", "window"])
    navigation = adb.shell_text(["settings", "get", "secure", "navigation_mode"]).strip()
    checks = []
    checks.append(("device connected (%s)" % serial, True, ""))
    checks.append(("Android version: " + adb.shell_text(["getprop", "ro.build.version.release"]).strip(), True, ""))
    for package in (FIXTURE_PACKAGE, DEV_PACKAGE):
        safe_package(package)
        result = adb.run(["shell", "pm", "path", package], timeout=15, check=False)
        checks.append(("package installed: " + package, result.returncode == 0 and bool(result.stdout.strip()), "Install the matching debug build."))
    service_ok = DEV_COMPONENT.lower() in [item.strip() for item in enabled.lower().split(":")]
    checks.append(("Dev accessibility service enabled", service_ok, "Run setup-accessibility, then confirm Android Settings."))
    screen_on = bool(re.search(r"mInteractive=true|Display Power: state=ON|mWakefulness=Awake", power, re.I))
    unlocked = (
        bool(re.search(r"isKeyguardShowing=false|mShowingLockscreen=false|mKeyguardShowing=false|isStatusBarKeyguard=false", window, re.I))
        and not re.search(r"isKeyguardShowing=true|mShowingLockscreen=true|mKeyguardShowing=true", window, re.I)
    )
    checks.append(("screen on", screen_on, "Wake the phone."))
    checks.append(("screen unlocked", unlocked, "Unlock the phone."))
    nav_names = {"0": "3-button", "1": "2-button", "2": "gesture"}
    print("RepeatKit E2E doctor")
    all_ok = True
    for label, ok, fix in checks:
        print("[%s] %s" % ("OK" if ok else "FAIL", label))
        if not ok:
            print("       Fix: " + fix)
            all_ok = False
    # Informational only: scenarios must pass under whichever navigation mode the phone uses.
    print("[INFO] navigation mode: %s (navigation_mode=%s)" % (nav_names.get(navigation, "unknown"), navigation))
    return 0 if all_ok else 1


def command_setup_accessibility(args):
    serial = select_serial(args.serial)
    adb = Adb(serial)
    before = adb.shell_text(["settings", "get", "secure", "enabled_accessibility_services"]).strip()
    after = merge_enabled_services(before)
    safe_package(DEV_PACKAGE)
    adb.shell(["settings", "put", "secure", "enabled_accessibility_services", after])
    adb.shell(["settings", "put", "secure", "accessibility_enabled", "1"])
    verify = adb.shell_text(["settings", "get", "secure", "enabled_accessibility_services"]).strip()
    print("enabled_accessibility_services before: %s" % (before or "<empty>"))
    print("enabled_accessibility_services after:  %s" % verify)
    return 0


def command_run(args):
    if args.all and args.scenario:
        raise HarnessError("Use --all or --scenario, not both")
    if not args.all and not args.scenario:
        raise HarnessError("Choose --all or at least one --scenario")
    scenarios = load_all_scenarios() if args.all else [load_scenario(item) for item in args.scenario]
    serial = select_serial(args.serial)
    adb = Adb(serial)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    results_dir = RESULTS_DIR / timestamp
    results_dir.mkdir(parents=True, exist_ok=True)
    for scenario in scenarios:
        scenario_dir = results_dir / scenario["id"]
        for run_number in range(1, args.repeat + 1):
            print("Running %s run%d" % (scenario["id"], run_number))
            verdict = run_one_scenario(adb, scenario, scenario_dir / ("run%d" % run_number), args.settle_ms)
            print("  %s" % ("PASS" if verdict["passed"] else "FAIL"))
            if not verdict["passed"] and not args.keep_going:
                summary = write_summary(results_dir)
                return 0 if summary["total_runs"] and summary["failed"] == 0 else 1
    summary = write_summary(results_dir)
    return 0 if summary["total_runs"] and summary["failed"] == 0 else 1


def command_report(args):
    results_dir = Path(args.results_dir).resolve()
    if not results_dir.is_dir():
        raise HarnessError("Results directory does not exist: %s" % results_dir)
    summary = write_summary(results_dir)
    return 0 if summary["total_runs"] and summary["failed"] == 0 else 1


def build_parser():
    parser = argparse.ArgumentParser(description="Record and replay RepeatKit fixture scenarios over adb.")
    subparsers = parser.add_subparsers(dest="command")

    doctor = subparsers.add_parser("doctor", help="check the connected device and RepeatKit prerequisites")
    doctor.add_argument("--serial", help="adb device serial")
    doctor.set_defaults(handler=command_doctor)

    setup = subparsers.add_parser("setup-accessibility", help="append and enable the Dev accessibility service")
    setup.add_argument("--serial", help="adb device serial")
    setup.set_defaults(handler=command_setup_accessibility)

    run = subparsers.add_parser("run", help="record, replay, compare, and save fixture results")
    run_group = run.add_mutually_exclusive_group()
    run_group.add_argument("--scenario", nargs="+", metavar="ID", help="scenario id(s) to run")
    run_group.add_argument("--all", action="store_true", help="run every scenario")
    run.add_argument("--repeat", type=int, default=1, help="number of runs per scenario (default: 1)")
    run.add_argument("--serial", help="adb device serial")
    run.add_argument("--keep-going", action="store_true", help="continue after a failed scenario")
    run.add_argument("--settle-ms", type=int, default=700, help="wait after each human action (default: 700)")
    run.set_defaults(handler=command_run)

    report = subparsers.add_parser("report", help="reprint a saved result summary")
    report.add_argument("results_dir", help="saved timestamped results directory")
    report.set_defaults(handler=command_report)
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "handler", None):
        parser.print_help()
        return 2
    try:
        return args.handler(args)
    except HarnessError as exc:
        print("ERROR: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

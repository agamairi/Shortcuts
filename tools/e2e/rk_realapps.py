#!/usr/bin/env python3
"""Record and replay harmless flows in real apps on the connected phone.

Navigation, search and typing only: nothing is sent, played, toggled or purchased, and no app is
force-stopped. Each flow puts the app in a known start state, records it like a person (RepeatKit ->
Home -> launcher icon), saves it, returns the app to its start state, replays it from Home through
the widget run path, and checks the end screen. The saved test shortcut is deleted afterwards.

Flows ending in ``_gboard`` type by tapping the on-screen keyboard's keys and submit with its action
key, which is how people type. The others use ``adb shell input text`` and an injected Enter.

    python3 tools/e2e/rk_realapps.py chrome_search_gboard music_search_gboard
    REPEAT=10 python3 tools/e2e/rk_realapps.py music_search_gboard

The screen is read only through RepeatKit Dev's own service (rk-ui / rk-ime), never uiautomator.
"""
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rk_e2e as h  # noqa: E402

OUT = h.RESULTS_DIR / "realapps"
SETTLE = 900
CHROME_TAB_ID = "com.repeatkit.e2e"  # Chrome reuses one tab for this app id instead of opening new ones


def launcher_component(adb, package):
    out = adb.shell_text(["cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", package])
    return out.strip().splitlines()[-1].strip()


def root_launch(adb, package):
    # NEW_TASK | CLEAR_TASK: the app's launcher screen with its back stack cleared.
    adb.shell(["am", "start", "-W", "-f", "0x10008000", "-n", launcher_component(adb, package)], timeout=30)
    time.sleep(2.0)


def chrome_on(adb, url):
    adb.shell(["am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url,
               "--es", "com.android.browser.application_id", CHROME_TAB_ID, "com.android.chrome"], timeout=30)
    time.sleep(3.0)


def nodes(adb):
    return h.parse_ui_nodes(h.dump_ui_xml(adb))


def screen_has(adb, predicate):
    found = [n for n in nodes(adb) if predicate(n)]
    return bool(found), [(n["id"], n["text"], n["desc"]) for n in found[:3]]


def text_contains(fragment):
    fragment = fragment.lower()
    return lambda n: fragment in (n["text"] or "").lower() or fragment in (n["desc"] or "").lower()


def text_is(value):
    return lambda n: n["text"] == value or n["desc"] == value


def suffix(node):
    return node["id"].split("/")[-1]


def all_of(*checks):
    def check(adb):
        current = nodes(adb)
        missing = [name for name, predicate, want in checks if any(predicate(n) for n in current) != want]
        return (not missing), (["missing/unexpected: " + ", ".join(missing)] if missing else [])
    return check


FLOWS = {}


def flow(fid, package, steps, verify, prepare=None):
    FLOWS[fid] = {"id": fid, "package": package, "steps": steps, "verify": verify,
                  "prepare": prepare or (lambda adb: root_launch(adb, package))}


def clear_calculator(adb):
    for desc in ("clear", "All clear"):
        try:
            h.execute_step(adb, {"tap": {"desc": desc}}, 300)
            return
        except h.HarnessError:
            continue


def define_flows():
    flow("settings_about", "com.android.settings",
         [{"scroll_until": {"text": "About phone"}}, {"tap": {"text": "About phone"}}, {"wait_ms": 1200}],
         lambda adb: screen_has(adb, text_is("Model")))
    flow("settings_battery", "com.android.settings",
         [{"scroll_until": {"text": "Battery"}}, {"tap": {"text": "Battery"}}, {"wait_ms": 1200}],
         lambda adb: screen_has(adb, lambda n: n["text"] in ("Battery usage", "Battery Saver", "Battery saver")))
    flow("calculator", "com.google.android.calculator",
         [{"tap": {"desc": "7"}}, {"tap": {"desc": "plus"}}, {"tap": {"desc": "8"}}, {"tap": {"desc": "equals"}}],
         lambda adb: screen_has(adb, lambda n: suffix(n) in ("formula", "result_final", "result_preview") and n["text"] == "15"),
         prepare=lambda adb: (root_launch(adb, "com.google.android.calculator"), clear_calculator(adb)))
    chrome_results = all_of(
        ("results address", lambda n: suffix(n) == "url_bar" and "repeatkit" in n["text"].lower(), True),
        ("suggestion list closed", lambda n: suffix(n) == "line_1", False))
    for fid, typing in (("chrome_search", [{"type": "repeatkit android"}, {"key": "ENTER"}]),
                        ("chrome_search_gboard", [{"gtype": "repeatkit android"}, {"ime": "action"}])):
        flow(fid, "com.android.chrome",
             [{"tap": {"id": "com.android.chrome:id/url_bar"}}] + typing + [{"wait_ms": 3500}],
             chrome_results, prepare=lambda adb: chrome_on(adb, "https://www.google.com/"))
    music_results = all_of(
        ("query shown", text_contains("lofi beats"), True),
        ("result filters", lambda n: n["text"] in ("Songs", "Albums", "Videos", "Community playlists"), True))
    for fid, typing in (("music_search", [{"type": "lofi beats"}, {"key": "ENTER"}]),
                        ("music_search_gboard", [{"gtype": "lofi beats"}, {"ime": "action"}])):
        flow(fid, "com.google.android.apps.youtube.music",
             [{"tap": {"desc": "Search"}}] + typing + [{"wait_ms": 3000}], music_results)
    flow("music_library", "com.google.android.apps.youtube.music",
         [{"tap": {"text": "Library"}}, {"wait_ms": 1500}],
         lambda adb: screen_has(adb, text_is("Playlists")))


def v2_steps(v2_text):
    """Compact view of the recorded workflow: launch, taps, set_text(value), ime(kind)."""
    steps = []
    for step in json.loads(v2_text).get("steps", []):
        op = step.get("operation") or step.get("target") or {}
        kind = op.get("type")
        if kind == "set_text":
            kind = "set_text(%r)" % op.get("text")
        elif kind == "ime_action":
            kind = "ime(%s)" % op.get("kind")
        steps.append(kind)
    return steps


def run_flow(adb, spec, run_dir):
    run_dir.mkdir(parents=True, exist_ok=True)
    name = "rk_real_" + spec["id"]
    verdict = {"flow": spec["id"], "passed": False, "record_end_ok": None, "replay_end_ok": None,
               "run_ok": None, "error": None, "replay_seconds": None, "steps": None}
    try:
        h.ensure_device_ready(adb)
        h.debug_command(adb, "clear_recording")
        try:
            h.debug_command(adb, "delete", name=name)
        except h.HarnessError as exc:
            if "not found" not in str(exc):
                raise
        adb.shell(["run-as", h.DEV_PACKAGE, "rm", "-f", "files/debug/last_recording_v2.json"], check=False)
        spec["prepare"](adb)
        adb.run(["logcat", "-c"], timeout=15)
        adb.shell(["input", "keyevent", "KEYCODE_HOME"])
        adb.shell(["monkey", "-p", h.DEV_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
        time.sleep(1.5)
        h.debug_command(adb, "start_recording")
        adb.shell(["input", "keyevent", "KEYCODE_HOME"])
        time.sleep(1.0)
        adb.shell(["monkey", "-p", spec["package"], "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
        time.sleep(2.5)
        for index, step in enumerate(spec["steps"]):
            try:
                h.execute_step(adb, step, SETTLE)
            except h.HarnessError as exc:
                raise h.HarnessError("record step %d failed: %s" % (index, exc)) from exc
        ok, detail = spec["verify"](adb)
        verdict["record_end_ok"] = ok
        verdict["record_end_detail"] = detail
        h.screenshot(adb, run_dir / "record.png")
        h.debug_command(adb, "stop_recording")
        h.debug_command(adb, "dump_recording")
        v2 = h.pull_optional_debug_text(adb, "last_recording_v2.json")
        h.debug_command(adb, "save", name=name)
        (run_dir / "record_logcat.txt").write_text(h.capture_logcat(adb), encoding="utf-8")
        if not v2:
            verdict["v2_missing"] = True
        else:
            (run_dir / "recording_v2.json").write_text(v2, encoding="utf-8")
            verdict["steps"] = v2_steps(v2)

        spec["prepare"](adb)
        adb.shell(["input", "keyevent", "KEYCODE_HOME"])
        time.sleep(1.0)
        adb.run(["logcat", "-c"], timeout=15)
        started = time.monotonic()
        try:
            h.debug_command(adb, "run", name=name, timeout=h.RUN_TIMEOUT)
            verdict["run_ok"] = True
        except h.HarnessError as exc:
            verdict["run_ok"] = False
            verdict["error"] = str(exc)
        verdict["replay_seconds"] = round(time.monotonic() - started, 2)
        time.sleep(1.5)
        ok, detail = spec["verify"](adb)
        verdict["replay_end_ok"] = ok
        verdict["replay_end_detail"] = detail
        h.screenshot(adb, run_dir / "replay.png")
        (run_dir / "replay_logcat.txt").write_text(h.capture_logcat(adb), encoding="utf-8")
        trace = h.pull_optional_debug_text(adb, "last_trace.jsonl")
        if trace:
            (run_dir / "trace.jsonl").write_text(trace, encoding="utf-8")
        # A recording that silently fell back to the legacy format is a failure, even if it replays.
        verdict["passed"] = bool(verdict["record_end_ok"] and verdict["run_ok"] and verdict["replay_end_ok"]
                                 and not verdict.get("v2_missing"))
    except h.HarnessError as exc:
        verdict["error"] = str(exc)
        # Keep evidence of what was on screen when a run fails before its own screenshots.
        for save in (lambda: h.screenshot(adb, run_dir / "failure.png"),
                     lambda: (run_dir / "failure_ui.xml").write_text(h.dump_ui_xml(adb, retries=2), encoding="utf-8"),
                     lambda: (run_dir / "failure_logcat.txt").write_text(h.capture_logcat(adb), encoding="utf-8")):
            try:
                save()
            except (h.HarnessError, OSError):
                pass
    finally:
        try:
            h.debug_command(adb, "delete", name=name)
        except h.HarnessError:
            pass
        adb.shell(["input", "keyevent", "KEYCODE_HOME"], check=False)
    (run_dir / "verdict.json").write_text(json.dumps(verdict, indent=2), encoding="utf-8")
    return verdict


def main():
    define_flows()
    wanted = sys.argv[1:] or list(FLOWS)
    unknown = [fid for fid in wanted if fid not in FLOWS]
    if unknown:
        raise SystemExit("Unknown flow(s): %s. Known: %s" % (", ".join(unknown), ", ".join(FLOWS)))
    repeat = int(os.environ.get("REPEAT", "1"))
    adb = h.Adb(serial=os.environ.get("ANDROID_SERIAL"), executable=os.environ.get("ADB", "adb"))
    stamp = time.strftime("%Y%m%d-%H%M%S")
    results = []
    for fid in wanted:
        for i in range(1, repeat + 1):
            print("Running %s run%d" % (fid, i), flush=True)
            v = run_flow(adb, FLOWS[fid], OUT / stamp / fid / ("run%d" % i))
            print("  %s  steps=%s record_end=%s run=%s replay_end=%s %s" % (
                "PASS" if v["passed"] else "FAIL", v.get("steps"), v["record_end_ok"], v["run_ok"],
                v["replay_end_ok"], v.get("error") or ""), flush=True)
            results.append(v)
    print("Real apps: %d/%d passed  (%s)" % (sum(1 for v in results if v["passed"]), len(results), OUT / stamp),
          flush=True)
    for fid in wanted:
        runs = [v for v in results if v["flow"] == fid]
        print("  %-22s %d/%d" % (fid, sum(1 for v in runs if v["passed"]), len(runs)), flush=True)


if __name__ == "__main__":
    main()

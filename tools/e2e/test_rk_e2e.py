import json
import unittest

from rk_e2e import (
    DEV_COMPONENT,
    diff_event_stream,
    encode_input_text,
    find_bounds,
    merge_enabled_services,
    record_quality,
    validate_scenario,
)


class ScenarioParsingTests(unittest.TestCase):
    def test_valid_scenario_is_returned(self):
        scenario = {
            "id": "sample",
            "description": "A sample",
            "steps": [{"tap": {"text": "Save"}}, {"wait_ms": 10}],
            "expect": "recorded",
        }
        self.assertEqual(validate_scenario(scenario), scenario)

    def test_invalid_scenario_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validate_scenario({"id": "sample", "description": "missing steps", "expect": "recorded"})


class UiAutomatorTests(unittest.TestCase):
    XML = """<hierarchy><node class="android.view.View" bounds="[0,0][1080,2200]"><node text="Item 45" bounds="[20,100][600,180]"/><node text="Open" bounds="[800,100][1020,180]"/><node text="Item 46" bounds="[20,300][600,380]"/><node text="Open" bounds="[800,300][1020,380]"/></node></hierarchy>"""

    def test_row_of_selects_vertically_aligned_duplicate(self):
        self.assertEqual(find_bounds(self.XML, {"text": "Open", "row_of": "Item 45"}), (800, 100, 1020, 180))
        self.assertEqual(find_bounds(self.XML, {"text": "Open", "row_of": "Item 46"}), (800, 300, 1020, 380))

    def test_content_description_and_resource_id_bounds(self):
        xml = '<hierarchy><node content-desc="Settings gear" resource-id="pkg:id/gear" bounds="[4,5][44,45]"/></hierarchy>'
        self.assertEqual(find_bounds(xml, {"desc": "Settings gear"}), (4, 5, 44, 45))
        self.assertEqual(find_bounds(xml, {"id": "pkg:id/gear"}), (4, 5, 44, 45))
        self.assertEqual(find_bounds(xml, {"content-desc": "Settings gear"}), (4, 5, 44, 45))
        self.assertEqual(find_bounds(xml, {"resource-id": "pkg:id/gear"}), (4, 5, 44, 45))


class VerdictTests(unittest.TestCase):
    def test_text_locator_falls_back_to_content_description_only_when_no_text_matches(self):
        xml = (
            '<hierarchy><node text="" content-desc="Search" bounds="[0,0][10,10]"/>'
            '<node text="Search" content-desc="" bounds="[20,20][30,30]"/></hierarchy>'
        )
        self.assertEqual(find_bounds(xml, {"text": "Search"}), (20, 20, 30, 30))
        only_desc = '<hierarchy><node text="" content-desc="Search" bounds="[0,0][10,10]"/></hierarchy>'
        self.assertEqual(find_bounds(only_desc, {"text": "Search"}), (0, 0, 10, 10))

    def test_event_diff_ignores_open_and_seq(self):
        expected = [{"event": "open", "target": "screen", "seq": 1}, {"event": "tap", "target": "save", "seq": 2, "value": "x"}]
        actual = [{"event": "open", "target": "other", "seq": 77}, {"event": "tap", "target": "save", "seq": 99, "value": "x"}]
        self.assertTrue(diff_event_stream(expected, actual)["equal"])

    def test_event_diff_reports_first_divergence(self):
        result = diff_event_stream([{"event": "tap", "target": "one"}, {"event": "tap", "target": "two"}], [{"event": "tap", "target": "one"}, {"event": "text", "target": "two", "value": "x"}])
        self.assertFalse(result["equal"])
        self.assertEqual(result["index"], 1)
        self.assertEqual(result["expected"], ["tap", "two", None])

    def test_event_diff_ignores_blur_echo_of_a_submitted_value(self):
        expected = [{"event": "submit", "target": "search", "value": "rk"}, {"event": "text", "target": "search", "value": "rk"}]
        actual = [{"event": "text", "target": "search", "value": "rk"}, {"event": "submit", "target": "search", "value": "rk"}]
        self.assertTrue(diff_event_stream(expected, actual)["equal"])

    def test_event_diff_keeps_typed_value_that_was_never_submitted(self):
        expected = [{"event": "text", "target": "name", "value": "Ada"}]
        actual = [{"event": "text", "target": "name", "value": "Ad"}]
        self.assertFalse(diff_event_stream(expected, actual)["equal"])

    def test_record_quality_counts_the_v2_workflow_when_present(self):
        scenario = {"steps": [{"tap": {"text": "Profile"}}, {"key": "BACK"}]}
        legacy = [{"actionType": "UI_AUTOMATION", "uiActionType": "TAP"}]
        v2 = json.dumps({"steps": [{"type": "launch_app"}, {"type": "ui"}, {"type": "ui"}, {"type": "ui"}]})
        self.assertEqual(record_quality(scenario, legacy)[0], "missing_steps")
        self.assertEqual(record_quality(scenario, legacy, v2)[0], "ok")

    def test_record_quality_flags_unresolved_and_missing_steps(self):
        scenario = {"id": "x", "description": "x", "steps": [{"tap": {"text": "A"}}, {"type": "hi"}], "expect": "recorded"}
        recording = [{"actionType": "UI_AUTOMATION", "uiActionType": "TAP", "targetText": "UNRESOLVED"}]
        quality, issues = record_quality(scenario, recording)
        self.assertEqual(quality, "missing_steps")
        self.assertEqual(len(issues), 2)


class AccessibilitySettingTests(unittest.TestCase):
    def test_merge_appends_without_dropping_existing_services(self):
        existing = "com.other.one/com.Service:com.other.two/com.Service"
        merged = merge_enabled_services(existing, DEV_COMPONENT)
        self.assertEqual(merged.split(":"), ["com.other.one/com.Service", "com.other.two/com.Service", DEV_COMPONENT])

    def test_merge_is_idempotent_and_handles_null(self):
        self.assertEqual(merge_enabled_services("null", DEV_COMPONENT), DEV_COMPONENT)
        self.assertEqual(merge_enabled_services(DEV_COMPONENT, DEV_COMPONENT), DEV_COMPONENT)


class InputEncodingTests(unittest.TestCase):
    def test_adb_spaces_use_percent_s(self):
        self.assertEqual(encode_input_text("hello world"), "hello%sworld")


if __name__ == "__main__":
    unittest.main()

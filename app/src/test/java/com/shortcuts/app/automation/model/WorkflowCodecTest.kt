package com.shortcuts.app.automation.model

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowCodecTest {
    private val codec = WorkflowCodec()

    @Test
    fun `v2 round trip preserves every step operation and selector field`() {
        val selector = fullSelector()
        val fingerprint = fingerprint()
        val window = fullWindow(fingerprint)
        val verification = VerificationSpec(
            postconditions = listOf(
                VerificationPostcondition.ExpectedScreen(fingerprint),
                VerificationPostcondition.ExpectedWindow(window),
                VerificationPostcondition.ExpectedActivity("example.app", "example.app.MainActivity"),
                VerificationPostcondition.TargetPresence(selector, shouldBePresent = false),
                VerificationPostcondition.BooleanStateChanged(NodeBooleanState.CHECKED, true),
                VerificationPostcondition.RangeValueChanged(0.75f, 0.01f),
                VerificationPostcondition.TextEquals("entered text"),
                VerificationPostcondition.FocusMoved(selector),
                VerificationPostcondition.ImeVisibility(false),
                VerificationPostcondition.NextTargetConfidence(selector, 82)
            )
        )
        val gesture = CapturedGesture(
            kind = GestureKind.SWIPE,
            durationMs = 420,
            points = listOf(
                TimedPoint(IntPoint(400, 1_400), 0, NormalizedPoint(0.4f, 0.7f)),
                TimedPoint(IntPoint(400, 400), 420, NormalizedPoint(0.4f, 0.2f))
            ),
            pointerCount = 1
        )
        val operations = listOf(
            UiOperation.TAP,
            UiOperation.LONG_PRESS,
            UiOperation.SWIPE,
            UiOperation.SET_TEXT("hello"),
            UiOperation.IME_ACTION(ImeActionKind.SEARCH),
            UiOperation.GLOBAL_ACTION("GLOBAL_ACTION_BACK")
        )
        val workflow = WorkflowV2(
            workflowId = "workflow-1",
            recordedDevice = RecordedDevice("Google", "Pixel", 36, 1_080, 2_400, 420, 0),
            steps = buildList {
                add(
                    LaunchAppStep(
                        id = "launch-1",
                        title = "Open app",
                        target = LaunchTarget.Launcher("example.app"),
                        policy = LaunchPolicy.RESET_TO_ROOT,
                        expectedWindow = window,
                        expectedAfter = fingerprint,
                        capturedDelayFromPreviousMs = 25
                    )
                )
                add(
                    LaunchAppStep(
                        id = "launch-2",
                        target = LaunchTarget.Component("example.app", "example.app.SettingsActivity"),
                        policy = LaunchPolicy.EXPLICIT_EXPORTED_ACTIVITY,
                        expectedWindow = window
                    )
                )
                add(
                    LaunchAppStep(
                        id = "launch-3",
                        target = LaunchTarget.DeepLink("example.app", "example://settings"),
                        policy = LaunchPolicy.DEEP_LINK,
                        expectedWindow = window
                    )
                )
                operations.forEachIndexed { index, operation ->
                    add(
                        UiStep(
                            id = "ui-$index",
                            title = "Operation $index",
                            operation = operation,
                            expectedWindow = window,
                            element = if (operation is UiOperation.GLOBAL_ACTION) null else selector,
                            gesture = if (operation == UiOperation.SWIPE) gesture else null,
                            reveal = RevealPolicy(RevealDirection.DOWN, 6, 2),
                            verification = verification,
                            safety = StepSafety.REVERSIBLE,
                            expectedBefore = fingerprint,
                            expectedAfter = fingerprint,
                            capturedDelayFromPreviousMs = 100,
                            migrationWarnings = listOf("EXAMPLE_WARNING")
                        )
                    )
                }
                add(WaitStep(id = "wait", title = "Pause", durationMs = 250))
                add(
                    LegacyActionStep(
                        id = "legacy",
                        action = Action(ActionType.HTTP_REQUEST, url = "https://example.test", method = "GET"),
                        migrationWarnings = listOf("LEGACY")
                    )
                )
            }
        )

        val decoded = codec.decode(codec.encode(workflow)) as DecodeResult.Success

        assertFalse(decoded.migratedFromLegacy)
        assertTrue(decoded.warnings.isEmpty())
        assertEquals(workflow, decoded.workflow)
    }

    @Test
    fun `unknown schema version is a failure`() {
        val failure = codec.decode("{\"schemaVersion\":99}") as DecodeResult.Failure

        assertEquals(DecodeFailureCode.UNSUPPORTED_SCHEMA_VERSION, failure.code)
        assertEquals("$.schemaVersion", failure.path)
    }

    @Test
    fun `malformed json is a failure with a path`() {
        val failure = codec.decode("{\"schemaVersion\":2,\"workflowId\":") as DecodeResult.Failure

        assertEquals(DecodeFailureCode.MALFORMED_JSON, failure.code)
        assertTrue(failure.path.startsWith("$"))
    }

    @Test
    fun `unknown required enum fails instead of becoming wait`() {
        val workflow = WorkflowV2(
            workflowId = "enum-test",
            recordedDevice = RecordedDevice("m", "m", 1, 100, 100, 100, 0),
            steps = listOf(
                WaitStep(
                    id = "wait",
                    durationMs = 10,
                    expectedBefore = ScreenFingerprint(
                        packageName = "pkg",
                        activityClass = null,
                        windowType = WindowType.APPLICATION,
                        structuralTokens = emptySet(),
                        landmarkTokens = emptySet(),
                        structureHash = 1
                    )
                )
            )
        )
        val invalid = codec.encode(workflow).replaceFirst(
            "\"windowType\":\"APPLICATION\"",
            "\"windowType\":\"FUTURE_WINDOW\""
        )

        val failure = codec.decode(invalid) as DecodeResult.Failure

        assertEquals(DecodeFailureCode.INVALID_VALUE, failure.code)
        assertTrue(failure.path.contains("windowType"))
    }

    @Test
    fun `array root follows legacy converter and migration path`() {
        val legacyJson = ActionConverter().fromActionList(
            listOf(Action(ActionType.APP_INTENT, packageName = "example.app", delayMillis = 500))
        )

        val decoded = codec.decode(legacyJson) as DecodeResult.Success

        assertTrue(decoded.migratedFromLegacy)
        assertEquals(WorkflowV2.SCHEMA_VERSION, decoded.workflow.schemaVersion)
        assertTrue(decoded.workflow.steps.single() is LaunchAppStep)
    }

    private fun fingerprint() = ScreenFingerprint(
        packageName = "example.app",
        activityClass = "example.app.MainActivity",
        windowType = WindowType.APPLICATION,
        structuralTokens = setOf(10, 20, 30),
        landmarkTokens = setOf(40, 50),
        structureHash = 123_456
    )

    private fun fullWindow(fingerprint: ScreenFingerprint) = WindowSelector(
        packageName = "example.app",
        observedActivityClass = "example.app.MainActivity",
        windowType = WindowType.APPLICATION,
        titleToken = "hmac-title-token",
        boundsOnDisplay = NormalizedRect(0f, 0.05f, 1f, 0.95f),
        displayId = 2,
        fingerprint = fingerprint
    )

    private fun fullSelector(): ElementSelector {
        val scrollContainer = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "example.app:id/list", LocatorStability.STABLE)),
            expectedClass = "androidx.recyclerview.widget.RecyclerView",
            expectedRole = NodeRole.LIST,
            ancestorChain = emptyList(),
            siblingIndex = 0,
            rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0.2f, 1f, 0.9f),
            rawPoint = null,
            requiredActions = setOf(NodeAction.SCROLL_FORWARD)
        )
        return ElementSelector(
            strategies = listOf(
                LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "example.app:id/save", LocatorStability.STABLE),
                LocatorStrategy(1, LocatorKind.TEXT_EXACT, "Save", LocatorStability.VOLATILE),
                LocatorStrategy(2, LocatorKind.CONTENT_DESCRIPTION_EXACT, "Save changes", LocatorStability.LIKELY_STABLE),
                LocatorStrategy(3, LocatorKind.TEST_TAG_EXACT, "save-tag", LocatorStability.STABLE),
                LocatorStrategy(4, LocatorKind.WEB_DOM_ID_EXACT, "save-dom", LocatorStability.STABLE)
            ),
            expectedClass = "android.widget.Button",
            expectedRole = NodeRole.BUTTON,
            ancestorChain = listOf(
                AncestorSelector("example.app:id/row", "android.widget.LinearLayout", NodeRole.LIST_ITEM, 2)
            ),
            siblingIndex = 1,
            rowContext = RowContext(
                containerClass = "android.widget.LinearLayout",
                containerResourceId = "example.app:id/row",
                siblingText = listOf("Account A"),
                siblingContentDescriptions = listOf("Primary account"),
                collectionRow = 4,
                collectionColumn = 1
            ),
            boundsInWindow = NormalizedRect(0.7f, 0.2f, 0.95f, 0.3f),
            rawPoint = CapturedPoint(
                displayPx = IntPoint(900, 600),
                pointInWindow = NormalizedPoint(0.83f, 0.24f),
                pointInNode = NormalizedPoint(0.5f, 0.5f),
                displayWidthPx = 1_080,
                displayHeightPx = 2_400,
                rotation = 0,
                densityDpi = 420
            ),
            requiredActions = setOf(NodeAction.CLICK, NodeAction.FOCUS),
            scrollContainer = scrollContainer
        )
    }
}

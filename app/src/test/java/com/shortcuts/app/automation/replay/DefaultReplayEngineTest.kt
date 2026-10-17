package com.shortcuts.app.automation.replay

import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.diagnostics.TraceEvent
import com.shortcuts.app.automation.diagnostics.TraceSink
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.CapturedPoint
import com.shortcuts.app.automation.model.CoordinateSpace
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchPolicy
import com.shortcuts.app.automation.model.LaunchTarget
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeBooleanState
import com.shortcuts.app.automation.model.NormalizedPoint
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.RevealDirection
import com.shortcuts.app.automation.model.RevealPolicy
import com.shortcuts.app.automation.model.TimedPoint
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationPostcondition
import com.shortcuts.app.automation.model.VerificationSpec
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowV2
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultReplayEngineTest {
    @Test fun `accepted semantic click without state change fails verification`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true)) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), RecordingSink())
        assertEquals(ReplayFailureCode.VERIFICATION_FAILED, (result as ReplayResult.Failed).failure.code)
    }

    @Test fun `semantic click succeeds only after target presence postcondition changes`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true)) {
            snapshot = screen(withTarget = false)
            emit()
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val trace = RecordingSink()
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), trace)
        assertEquals(ReplayResult.Success(1), result)
        assertTrue(trace.events.any { it is com.shortcuts.app.automation.diagnostics.DispatchRecorded && it.method == "SEMANTIC_ACTION" })
    }

    @Test fun `tap without click uses current mapped gesture point`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true, click = false)) { request ->
            assertEquals(DispatchMethod.GESTURE, request.dispatchMethod)
            assertEquals(1, request.gesturePoints.size)
            snapshot = screen(withTarget = false)
            DispatchResult.Accepted(DispatchMethod.GESTURE)
        }
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(tapStep(clickRequired = false)), RecordingSink()))
    }

    @Test fun `gesture is rejected when another interactive window covers mapped point`() = runTest {
        val covered = window("overlay", id = 4, layer = 10) {
            node("Button", clickable = true, bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 2_000))
        }
        val env = FakeEnvironment(windowSet(screen(withTarget = true, click = false).windows.single(), covered)) {
            DispatchResult.Accepted(DispatchMethod.GESTURE)
        }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep(clickRequired = false)), RecordingSink())
        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, (result as ReplayResult.Failed).failure.code)
    }

    @Test fun `accepted but unverified reversible click is never dispatched twice`() = runTest {
        var dispatches = 0
        val env = FakeEnvironment(screen(withTarget = true)) { request ->
            dispatches++
            DispatchResult.Accepted(request.dispatchMethod)
        }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.VERIFICATION_FAILED, result.failure.code)
        assertEquals(1, dispatches)
    }

    @Test fun `irreversible click is never retried`() = runTest {
        var calls = 0
        val env = FakeEnvironment(screen(withTarget = true)) { calls++; DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep(safety = StepSafety.IRREVERSIBLE)), RecordingSink())
        assertEquals(ReplayFailureCode.VERIFICATION_FAILED, (result as ReplayResult.Failed).failure.code)
        assertEquals(1, calls)
    }

    @Test fun `set text focuses sets and verifies without tracing text`() = runTest {
        val env = FakeEnvironment(editableScreen(focused = false, text = "")) { request ->
            when (request.semanticAction) {
                NodeAction.FOCUS -> snapshot = editableScreen(focused = true, text = "")
                NodeAction.SET_TEXT -> snapshot = editableScreen(focused = true, text = request.text.orEmpty())
                else -> Unit
            }
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val trace = RecordingSink()
        val step = tapStep(operation = UiOperation.SET_TEXT("secret value"), clickRequired = false,
            verification = VerificationSpec(listOf(VerificationPostcondition.TextEquals("secret value"))))
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), trace))
        assertEquals(listOf(NodeAction.FOCUS, NodeAction.SET_TEXT), env.requests.map { it.semanticAction })
        assertFalse(trace.events.joinToString().contains("secret value"))
    }

    @Test fun `ime action requires exact focused field`() = runTest {
        val env = FakeEnvironment(editableScreen(focused = false, text = "")) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val step = tapStep(operation = UiOperation.IME_ACTION(ImeActionKind.SEARCH), clickRequired = false)
        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())
        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, (result as ReplayResult.Failed).failure.code)
    }

    @Test fun `cancellation event interrupts expected screen wait`() = runTest {
        val cancel = CancelSignal()
        val env = FakeEnvironment(windowSet()) { DispatchResult.Rejected("not reached") }
        val job = launch { engine(env, { testScheduler.currentTime }, cancel).run(workflow(tapStep()), RecordingSink()) }
        testScheduler.advanceTimeBy(1)
        cancel.cancel()
        job.join()
        assertTrue(job.isCompleted)
    }

    @Test fun `preflight rejects unverifiable and malformed UI steps`() = runTest {
        val engine = engine(FakeEnvironment(screen()) { DispatchResult.Rejected("unused") }, { testScheduler.currentTime })
        val invalid = tapStep(element = null, verification = VerificationSpec(emptyList()))
        val failures = engine.preflight(workflow(invalid))
        assertTrue(failures.any { it.code == ReplayFailureCode.DECODE_FAILED })
        assertTrue(failures.any { it.code == ReplayFailureCode.UNVERIFIABLE_STEP })
    }

    @Test fun `launch waits for its target package and ignores another app first`() = runTest {
        val target = WindowSelector("settings", "SettingsActivity", WindowType.APPLICATION, null, NormalizedRect(0f, 0f, 1f, 1f), 0, null)
        val env = FakeEnvironment(screenFor("other")) { DispatchResult.Rejected("not reached") }
        env.activities[1] = "OtherActivity"
        val step = launchStep(target, timeoutMs = 2_000)
        val job = launch { assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())) }
        testScheduler.advanceTimeBy(1_200)
        env.snapshot = screenFor("settings")
        env.activities[1] = "SettingsActivity"
        env.emit()
        job.join()
    }

    @Test fun `launch timeout identifies requested package and activity`() = runTest {
        val target = WindowSelector("settings", "SettingsActivity", WindowType.APPLICATION, null, NormalizedRect(0f, 0f, 1f, 1f), 0, null)
        val env = FakeEnvironment(screenFor("other")) { DispatchResult.Rejected("not reached") }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(launchStep(target, timeoutMs = 300)), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.EXPECTED_SCREEN_TIMEOUT, result.failure.code)
        assertTrue(result.failure.message.contains("package=settings"))
        assertTrue(result.failure.message.contains("activity=SettingsActivity"))
    }

    @Test fun `expected screen is event driven with no polling faster than safety interval`() = runTest {
        val env = FakeEnvironment(screen(withTarget = false)) { DispatchResult.Rejected("not reached") }
        val snapshots = mutableListOf<Long>()
        env.onSnapshot = { snapshots += testScheduler.currentTime }
        val expected = DefaultScreenFingerprinter().fingerprint(screen(withTarget = true).windows.single(), null)
        var outcome: ReplayResult? = null
        launch { outcome = engine(env, { testScheduler.currentTime }, timeouts = ReplayTimeouts(postActionMs = 200)).run(workflow(tapStep().copy(expectedBefore = expected)), RecordingSink()) }
        testScheduler.advanceTimeBy(1_200)
        env.snapshot = screen(withTarget = true)
        env.emit()
        testScheduler.advanceUntilIdle()
        assertTrue(snapshots.distinct().zipWithNext().all { (a, b) -> b - a >= 250 || b == 1_200L })
        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, (outcome as ReplayResult.Failed).failure.code)
    }

    @Test fun `already matching screen proceeds while change events never settle`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true)) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val expected = DefaultScreenFingerprinter().fingerprint(screen(withTarget = true).windows.single(), null)
        var outcome: ReplayResult? = null
        launch { outcome = engine(env, { testScheduler.currentTime }, timeouts = ReplayTimeouts(postActionMs = 200)).run(workflow(tapStep().copy(expectedBefore = expected)), RecordingSink()) }
        repeat(5) { testScheduler.advanceTimeBy(50); env.emit() }
        testScheduler.advanceUntilIdle()
        assertEquals(ReplayFailureCode.VERIFICATION_FAILED, (outcome as ReplayResult.Failed).failure.code)
        assertTrue(testScheduler.currentTime < 1_000)
    }

    @Test fun `wrong expected window fails before matching or dispatch`() = runTest {
        val wrongWindow = expectedWindow.copy(packageName = "other")
        val env = FakeEnvironment(screen(withTarget = true)) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val step = tapStep().copy(expectedWindow = wrongWindow, timeoutMs = 300)
        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.WINDOW_NOT_FOUND, result.failure.code)
        assertTrue(env.requests.isEmpty())
    }

    @Test fun `boolean state postcondition verifies a toggled switch`() = runTest {
        val env = FakeEnvironment(toggleScreen(false)) { request ->
            if (request.semanticAction == NodeAction.CLICK) snapshot = toggleScreen(true)
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val step = tapStep(element = selector().copy(expectedClass = "Switch"), verification = VerificationSpec(listOf(VerificationPostcondition.BooleanStateChanged(NodeBooleanState.CHECKED))))
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
    }

    @Test fun `fresh dispatch re-matches a target after an inserted row changes node ids`() = runTest {
        val initial = screen(withTarget = true)
        val insertedRow = windowSet(window("app") {
            node("Root") {
                node("TextView", id = 2, resourceId = "app:id/new-row", bounds = com.shortcuts.app.automation.core.tree.r(100, 100, 900, 180))
                node("Button", id = 3, resourceId = "app:id/target", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })
        val env = FakeEnvironment(initial) { request ->
            assertEquals(3, request.nodeId)
            snapshot = screen(withTarget = false)
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        var snapshots = 0
        env.onSnapshot = {
            snapshots++
            // The first accepted locate has already happened before the final pre-dispatch snapshot.
            if (snapshots == 4) env.snapshot = insertedRow
        }

        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), RecordingSink()))
        assertTrue(env.requests.single().snapshotGeneration > 0)
    }

    @Test fun `boolean verification re-matches the target in its post-action snapshot`() = runTest {
        val env = FakeEnvironment(toggleScreen(false)) { request ->
            if (request.semanticAction == NodeAction.CLICK) {
                snapshot = windowSet(window("app") {
                    node("Root") {
                        node("TextView", id = 2, resourceId = "app:id/inserted", bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 100, 100))
                        node("Switch", id = 3, resourceId = "app:id/target", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600),
                            clickable = true, checkable = true, checked = true, actions = setOf(NodeAction.CLICK))
                    }
                })
            }
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val step = tapStep(
            element = selector().copy(expectedClass = "Switch"),
            verification = VerificationSpec(listOf(VerificationPostcondition.BooleanStateChanged(NodeBooleanState.CHECKED)))
        )
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
    }

    @Test fun `expected window verifies a cross app tap`() = runTest {
        val browser = WindowSelector("browser", null, WindowType.APPLICATION, null, NormalizedRect(0f, 0f, 1f, 1f), 0, null)
        val env = FakeEnvironment(screen(withTarget = true)) { request ->
            if (request.semanticAction == NodeAction.CLICK) snapshot = screenFor("browser")
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val step = tapStep(verification = VerificationSpec(listOf(VerificationPostcondition.ExpectedWindow(browser))))
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
    }

    @Test fun `semantic rejection is not retried as a gesture`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true)) { request ->
            DispatchResult.Rejected("no")
        }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, result.failure.code)
        assertEquals(listOf(DispatchMethod.SEMANTIC_ACTION), env.requests.map { it.dispatchMethod })
    }

    @Test fun `long press uses long click semantic action`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true, click = false, longClick = true)) { request ->
            assertEquals(NodeAction.LONG_CLICK, request.semanticAction); snapshot = screen(withTarget = false); DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val step = tapStep(operation = UiOperation.LONG_PRESS, clickRequired = false)
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
    }

    @Test fun `swipe maps all points within current container bounds`() = runTest {
        val env = FakeEnvironment(swipeScreen()) { request ->
            assertEquals(DispatchMethod.GESTURE, request.dispatchMethod)
            assertEquals(listOf(IntPoint(300, 500), IntPoint(700, 1_500)), request.gesturePoints)
            snapshot = swipeScreen(after = true)
            DispatchResult.Accepted(DispatchMethod.GESTURE)
        }
        val gesture = CapturedGesture(GestureKind.SWIPE, 200, listOf(TimedPoint(IntPoint(0, 0), 0, NormalizedPoint(.3f, .25f)), TimedPoint(IntPoint(0, 0), 200, NormalizedPoint(.7f, .75f))))
        val step = tapStep(operation = UiOperation.SWIPE, element = null, verification = VerificationSpec(listOf(VerificationPostcondition.ExpectedScreen(DefaultScreenFingerprinter().fingerprint(swipeScreen(after = true).windows.single(), null))))).copy(gesture = gesture)
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
    }

    @Test fun `global back dispatches a global action`() = runTest {
        val env = FakeEnvironment(screen(withTarget = true)) { request ->
            assertEquals(DispatchMethod.GLOBAL_ACTION, request.dispatchMethod); assertEquals("BACK", request.globalAction)
            snapshot = screen(withTarget = false); DispatchResult.Accepted(DispatchMethod.GLOBAL_ACTION)
        }
        val step = tapStep(operation = UiOperation.GLOBAL_ACTION("BACK"), element = selector(), verification = VerificationSpec(listOf(VerificationPostcondition.TargetPresence(selector(), false))))
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
    }

    @Test fun `inferred Back already on after screen skips dispatch`() = runTest {
        val after = changedTargetScreen()
        val env = FakeEnvironment(after) { error("Detected Back must not dispatch when already satisfied") }
        env.activities[1] = "ProfileActivity"
        val beforeSelector = expectedWindow.copy(observedActivityClass = "EditActivity")
        val afterFingerprint = DefaultScreenFingerprinter().fingerprint(after.windows.single(), "ProfileActivity")
        val step = tapStep(operation = UiOperation.GLOBAL_ACTION("BACK"), element = null,
            verification = VerificationSpec(emptyList(), allowBestEffort = true)).copy(
            expectedWindow = beforeSelector,
            expectedAfter = afterFingerprint,
            migrationWarnings = listOf("INFERRED_BACK")
        )
        val trace = RecordingSink()

        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), trace))
        assertTrue(env.requests.isEmpty())
        assertTrue(trace.events.any { it is com.shortcuts.app.automation.diagnostics.DispatchRecorded && it.reason == "inferred back already satisfied" })
    }

    @Test fun `inferred Back dispatches while still on departing screen`() = runTest {
        val before = screen(withTarget = true)
        val after = changedTargetScreen()
        val env = FakeEnvironment(before) { request ->
            assertEquals("BACK", request.globalAction)
            snapshot = after
            DispatchResult.Accepted(DispatchMethod.GLOBAL_ACTION)
        }
        env.activities[1] = "EditActivity"
        val beforeSelector = expectedWindow.copy(observedActivityClass = "EditActivity")
        val afterFingerprint = DefaultScreenFingerprinter().fingerprint(after.windows.single(), "ProfileActivity")
        val step = tapStep(operation = UiOperation.GLOBAL_ACTION("BACK"), element = null,
            verification = VerificationSpec(emptyList(), allowBestEffort = true)).copy(
            expectedWindow = beforeSelector,
            expectedAfter = afterFingerprint,
            migrationWarnings = listOf("INFERRED_BACK")
        )

        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
        assertEquals(1, env.requests.size)
    }

    @Test fun `retry is skipped when the pre state changes`() = runTest {
        var calls = 0
        val env = FakeEnvironment(screen(withTarget = true)) { calls++; snapshot = changedTargetScreen(); DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.VERIFICATION_FAILED, result.failure.code)
        assertEquals(1, calls)
    }

    @Test fun `scroll to find reaches row 45 and prefers a scroll action`() = runTest {
        var start = 0
        val env = FakeEnvironment(listScreen(0)) { request ->
            if (request.semanticAction == NodeAction.SCROLL_FORWARD) {
                start += 10; snapshot = listScreen(start)
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                assertEquals(DispatchMethod.GESTURE, request.dispatchMethod)
                DispatchResult.Accepted(DispatchMethod.GESTURE)
            }
        }
        val step = listStep(selectorForRow(45, listContainerSelector()), RevealPolicy(RevealDirection.FORWARD, 8, 2))
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()))
        assertEquals(4, env.requests.count { it.semanticAction == NodeAction.SCROLL_FORWARD })
    }

    @Test fun `nested scroll uses only the recorded container`() = runTest {
        val env = FakeEnvironment(nestedListScreen()) { request ->
            if (requests.size == 1) {
                assertEquals(3, request.nodeId); assertEquals(NodeAction.SCROLL_FORWARD, request.semanticAction)
                snapshot = nestedListScreen(showTarget = true)
            } else {
                assertEquals(4, request.nodeId); assertEquals(DispatchMethod.GESTURE, request.dispatchMethod)
            }
            DispatchResult.Accepted(request.dispatchMethod)
        }
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(listStep(selectorForRow(45, nestedContainerSelector()), RevealPolicy(RevealDirection.FORWARD, 2, 2))), RecordingSink()))
    }

    @Test fun `two unchanged scroll fingerprints fail with no progress`() = runTest {
        val env = FakeEnvironment(listScreen(0)) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(listStep(selectorForRow(45, listContainerSelector()), RevealPolicy(RevealDirection.FORWARD, 5, 2))), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.SCROLL_NO_PROGRESS, result.failure.code)
    }

    @Test fun `missing scroll container fails explicitly`() = runTest {
        val env = FakeEnvironment(screen(withTarget = false)) { DispatchResult.Rejected("not reached") }
        val result = engine(env, { testScheduler.currentTime }).run(workflow(listStep(selectorForRow(45, listContainerSelector()), RevealPolicy(RevealDirection.FORWARD))), RecordingSink()) as ReplayResult.Failed
        assertEquals(ReplayFailureCode.SCROLL_CONTAINER_NOT_FOUND, result.failure.code)
    }

    @Test fun `cancellation interrupts launch wait promptly`() = runTest {
        val cancel = CancelSignal(); val env = FakeEnvironment(windowSet()) { DispatchResult.Rejected("not reached") }
        var outcome: ReplayResult? = null
        val job = launch { outcome = engine(env, { testScheduler.currentTime }, cancel).run(workflow(launchStep(expectedWindow)), RecordingSink()) }
        testScheduler.runCurrent(); cancel.cancel(); job.join()
        assertEquals(ReplayFailureCode.CANCELLED, ((outcome as ReplayResult.Failed).failure.code))
    }

    @Test fun `cancellation interrupts scroll wait promptly`() = runTest {
        val cancel = CancelSignal(); val env = FakeEnvironment(listScreen(0)) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        var outcome: ReplayResult? = null
        val job = launch { outcome = engine(env, { testScheduler.currentTime }, cancel).run(workflow(listStep(selectorForRow(45, listContainerSelector()), RevealPolicy(RevealDirection.FORWARD))), RecordingSink()) }
        testScheduler.runCurrent(); cancel.cancel(); job.join()
        assertEquals(ReplayFailureCode.CANCELLED, ((outcome as ReplayResult.Failed).failure.code))
    }

    @Test fun `cancellation interrupts verification promptly`() = runTest {
        val cancel = CancelSignal(); val env = FakeEnvironment(screen(withTarget = true)) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        var outcome: ReplayResult? = null
        val job = launch { outcome = engine(env, { testScheduler.currentTime }, cancel).run(workflow(tapStep()), RecordingSink()) }
        testScheduler.advanceTimeBy(300); cancel.cancel(); job.join()
        assertEquals(ReplayFailureCode.CANCELLED, ((outcome as ReplayResult.Failed).failure.code))
    }

    @Test fun `accessibility disconnect event aborts a wait`() = runTest {
        val env = FakeEnvironment(windowSet()) { DispatchResult.Rejected("not reached") }
        var outcome: ReplayResult? = null
        val job = launch { outcome = engine(env, { testScheduler.currentTime }).run(workflow(tapStep()), RecordingSink()) }
        testScheduler.runCurrent(); env.disconnect(); job.join()
        assertEquals(ReplayFailureCode.ACCESSIBILITY_UNAVAILABLE, ((outcome as ReplayResult.Failed).failure.code))
    }

    @Test fun `legacy display coordinates emit a warning but do not fail preflight`() = runTest {
        val legacySelector = selector().copy(rawPoint = CapturedPoint(IntPoint(500, 400), NormalizedPoint(.5f, .2f), NormalizedPoint(.5f, .5f), 1_000, 2_000, 0, 420, CoordinateSpace.DISPLAY_LEGACY))
        val env = FakeEnvironment(screen(withTarget = true)) { snapshot = screen(withTarget = false); DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }
        val trace = RecordingSink()
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(tapStep(element = legacySelector)), trace))
        assertTrue(trace.events.any { it is com.shortcuts.app.automation.diagnostics.PreflightWarning })
    }

    @Test fun `end to end settings navigation follows dispatched taps`() = runTest {
        var screen = 0
        val env = FakeEnvironment(settingsScreen(screen)) { request ->
            assertEquals(NodeAction.CLICK, request.semanticAction)
            screen++; snapshot = settingsScreen(screen); DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val settings = settingsSelector("settings"); val network = settingsSelector("network"); val internet = settingsSelector("internet")
        val steps = listOf(
            settingsStep("open-settings", settings, network),
            settingsStep("network", network, internet),
            settingsStep("internet", internet, null)
        )
        assertEquals(ReplayResult.Success(3), engine(env, { testScheduler.currentTime }).run(WorkflowV2(workflowId = "settings", recordedDevice = device, steps = steps), RecordingSink()))
    }

    @Test fun `trace serialisation never contains node text or entered text`() = runTest {
        val secretNodeText = "NODE PRIVATE CONTENT"; val secretInput = "ENTERED PRIVATE CONTENT"
        val env = FakeEnvironment(editableScreen(focused = false, text = secretNodeText)) { request ->
            snapshot = when (request.semanticAction) {
                NodeAction.FOCUS -> editableScreen(true, secretNodeText)
                NodeAction.SET_TEXT -> editableScreen(true, request.text.orEmpty())
                else -> snapshot
            }
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }
        val trace = RecordingSink()
        val step = tapStep(operation = UiOperation.SET_TEXT(secretInput), clickRequired = false, verification = VerificationSpec(listOf(VerificationPostcondition.TextEquals(secretInput))))
        assertEquals(ReplayResult.Success(1), engine(env, { testScheduler.currentTime }).run(workflow(step), trace))
        val serialised = trace.events.joinToString("\\n") { it.toString() }
        assertFalse(serialised.contains(secretNodeText)); assertFalse(serialised.contains(secretInput))
    }

    private fun engine(env: FakeEnvironment, clock: () -> Long, cancellation: ReplayCancellationSignal = ReplayCancellationSignal.None,
        timeouts: ReplayTimeouts = ReplayTimeouts()) =
        DefaultReplayEngine(env, FakeLauncher(), WindowMatcher(DefaultScreenFingerprinter()), SelectorMatcher(),
            DefaultScreenFingerprinter(), clock, { delay(it) }, cancellation, timeouts)

    private fun workflow(vararg steps: com.shortcuts.app.automation.model.Step) = WorkflowV2(workflowId = "test", recordedDevice = device, steps = steps.toList())

    private fun launchStep(selector: WindowSelector, timeoutMs: Long = 12_000) = LaunchAppStep("launch", target = LaunchTarget.Launcher(selector.packageName), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = selector, timeoutMs = timeoutMs)

    private fun tapStep(
        operation: UiOperation = UiOperation.TAP,
        clickRequired: Boolean = true,
        element: ElementSelector? = selector(clickRequired),
        safety: StepSafety = StepSafety.REVERSIBLE,
        verification: VerificationSpec = VerificationSpec(listOf(VerificationPostcondition.TargetPresence(selector(clickRequired), false)))
    ) = UiStep(
        id = "tap", operation = operation, expectedWindow = expectedWindow, element = element,
        gesture = null, verification = verification, safety = safety
    )

    private fun selector(clickRequired: Boolean = true) = ElementSelector(
        strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/target", LocatorStability.STABLE)),
        expectedClass = "Button", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
        boundsInWindow = NormalizedRect(.1f, .1f, .9f, .3f), rawPoint = com.shortcuts.app.automation.model.CapturedPoint(
            com.shortcuts.app.automation.model.IntPoint(500, 400), NormalizedPoint(.5f, .2f), NormalizedPoint(.5f, .5f),
            1_000, 2_000, 0, 420
        ), requiredActions = if (clickRequired) setOf(NodeAction.CLICK) else emptySet()
    )

    private fun screen(withTarget: Boolean = false, click: Boolean = true, longClick: Boolean = false): WindowSetSnapshot = windowSet(window("app") {
        node("Root") {
            if (withTarget) node("Button", id = 2, resourceId = "app:id/target", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600),
                clickable = click, longClickable = longClick, actions = buildSet { if (click) add(NodeAction.CLICK); if (longClick) add(NodeAction.LONG_CLICK) })
        }
    })

    private fun screenFor(pkg: String): WindowSetSnapshot = windowSet(window(pkg) { node("Root") })

    private fun toggleScreen(checked: Boolean): WindowSetSnapshot = windowSet(window("app") {
        node("Root") { node("Switch", id = 2, resourceId = "app:id/target", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600), clickable = true, checkable = true, checked = checked, actions = setOf(NodeAction.CLICK)) }
    })

    private fun changedTargetScreen(): WindowSetSnapshot = windowSet(window("app") {
        node("Root", text = "changed") { node("Button", id = 2, resourceId = "app:id/target", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600), clickable = true, actions = setOf(NodeAction.CLICK)) }
    })

    private fun swipeScreen(after: Boolean = false): WindowSetSnapshot = windowSet(window("app") {
        node("Root", bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 2_000)) {
            node(if (after) "After" else "Container", id = 2, resourceId = if (after) "app:id/after" else "app:id/container", bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 2_000))
        }
    })

    private fun listContainerSelector() = ElementSelector(
        strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/list", LocatorStability.STABLE)), expectedClass = "RecyclerView", expectedRole = null,
        ancestorChain = emptyList(), siblingIndex = null, rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null,
        requiredActions = setOf(NodeAction.SCROLL_FORWARD)
    )
    private fun nestedContainerSelector() = listContainerSelector().copy(strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/inner", LocatorStability.STABLE)))
    private fun selectorForRow(row: Int, container: ElementSelector) = ElementSelector(
        strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/row-$row", LocatorStability.STABLE)), expectedClass = "TextView", expectedRole = null,
        ancestorChain = emptyList(), siblingIndex = null, rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null,
        requiredActions = emptySet(), scrollContainer = container
    )
    private fun listStep(target: ElementSelector, reveal: RevealPolicy) = tapStep(element = target, verification = VerificationSpec(listOf(VerificationPostcondition.TargetPresence(target, true)))).copy(reveal = reveal)
    private fun listScreen(start: Int): WindowSetSnapshot = windowSet(window("app") {
        node("Root", text = "page-$start") { node("RecyclerView", id = 2, resourceId = "app:id/list", bounds = com.shortcuts.app.automation.core.tree.r(0, 100, 1_000, 1_900), scrollable = true, actions = setOf(NodeAction.SCROLL_FORWARD)) {
            (start until (start + 10).coerceAtMost(60)).forEach { row -> node("TextView", resourceId = "app:id/row-$row", bounds = com.shortcuts.app.automation.core.tree.r(0, 100 + (row - start) * 100, 1_000, 190 + (row - start) * 100), collectionRow = row) }
        } }
    })
    private fun nestedListScreen(showTarget: Boolean = false): WindowSetSnapshot = windowSet(window("app") {
        node("Root") { node("RecyclerView", id = 2, resourceId = "app:id/outer", bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 2_000), scrollable = true, actions = setOf(NodeAction.SCROLL_FORWARD)) {
            node("RecyclerView", id = 3, resourceId = "app:id/inner", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 1_800), scrollable = true, actions = setOf(NodeAction.SCROLL_FORWARD)) {
                if (showTarget) node("TextView", resourceId = "app:id/row-45", bounds = com.shortcuts.app.automation.core.tree.r(120, 400, 880, 500), collectionRow = 45)
            }
        } }
    })
    private fun settingsSelector(id: String) = ElementSelector(
        strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "settings:id/$id", LocatorStability.STABLE)), expectedClass = "Button", expectedRole = null,
        ancestorChain = emptyList(), siblingIndex = null, rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, .2f), rawPoint = null, requiredActions = setOf(NodeAction.CLICK)
    )
    private fun settingsScreen(index: Int): WindowSetSnapshot = windowSet(window("app") { node("Root") {
        val id = listOf("settings", "network", "internet").getOrElse(index) { "done" }
        node("Button", id = 2, resourceId = "settings:id/$id", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600), clickable = true, actions = setOf(NodeAction.CLICK))
    } })
    private fun settingsStep(id: String, target: ElementSelector, next: ElementSelector?) = UiStep(id, operation = UiOperation.TAP, expectedWindow = expectedWindow, element = target, gesture = null,
        verification = VerificationSpec(listOf(next?.let { VerificationPostcondition.TargetPresence(it, true) } ?: VerificationPostcondition.TargetPresence(target, false))))

    private fun editableScreen(focused: Boolean, text: String): WindowSetSnapshot = windowSet(window("app") {
        node("Root") {
            node("Button", id = 2, resourceId = "app:id/target", bounds = com.shortcuts.app.automation.core.tree.r(100, 200, 900, 600),
                editable = true, focusable = true, focused = focused, text = text,
                actions = setOf(NodeAction.FOCUS, NodeAction.SET_TEXT, NodeAction.IME_ENTER))
        }
    })

    private class FakeEnvironment(initial: WindowSetSnapshot, private val handler: suspend FakeEnvironment.(UiActionRequest) -> DispatchResult) : UiEnvironment {
        var snapshot = initial
        val requests = mutableListOf<UiActionRequest>()
        val activities = mutableMapOf<Int, String?>()
        var onSnapshot: (() -> Unit)? = null
        private var generation = 0L
        private val eventFlow = MutableSharedFlow<UiEnvironmentEvent>(extraBufferCapacity = 8)
        override fun snapshotWindows(): WindowSetSnapshot { generation++; onSnapshot?.invoke(); return snapshot }
        override fun snapshotGeneration(): Long = generation
        override fun events(): Flow<UiEnvironmentEvent> = eventFlow.asSharedFlow()
        override fun currentActivityClass(windowId: Int) = activities[windowId]
        override suspend fun perform(request: UiActionRequest): DispatchResult { requests += request; return handler(request) }
        fun emit() { eventFlow.tryEmit(UiEnvironmentEvent.ContentChanged(0, 1)) }
        fun disconnect() { eventFlow.tryEmit(UiEnvironmentEvent.AccessibilityDisconnected(0)) }
    }

    private class FakeLauncher : AppLauncher { override suspend fun launch(step: com.shortcuts.app.automation.model.LaunchAppStep) = LaunchDispatchResult.Accepted }
    private class RecordingSink : TraceSink { val events = mutableListOf<TraceEvent>(); override fun append(event: TraceEvent) { events += event } }
    private class CancelSignal : ReplayCancellationSignal {
        private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        private var cancelled = false
        fun cancel() { cancelled = true; flow.tryEmit(Unit) }
        override fun isCancelled() = cancelled
        override fun events() = flow.asSharedFlow()
    }

    private companion object {
        val device = RecordedDevice("test", "test", 1, 1_000, 2_000, 420, 0)
        val expectedWindow = WindowSelector("app", null, WindowType.APPLICATION, null, NormalizedRect(0f, 0f, 1f, 1f), 0, null)
    }
}

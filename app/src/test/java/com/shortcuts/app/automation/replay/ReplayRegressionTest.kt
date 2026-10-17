package com.shortcuts.app.automation.replay

import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.diagnostics.ActionRetryRecorded
import com.shortcuts.app.automation.diagnostics.ExpectedScreenRecorded
import com.shortcuts.app.automation.diagnostics.TraceEvent
import com.shortcuts.app.automation.diagnostics.TraceSink
import com.shortcuts.app.automation.model.CapturedPoint
import com.shortcuts.app.automation.model.CoordinateSpace
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NormalizedPoint
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.RevealDirection
import com.shortcuts.app.automation.model.RevealPolicy
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationPostcondition
import com.shortcuts.app.automation.model.VerificationSpec
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowV2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayRegressionTest {

    // --- Fix 1: Target not yet present (delayed content) ---

    @Test
    fun `target delayed by spinner waits event driven and succeeds when content appears`() = runTest {
        val initialScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("ProgressBar", id = 2, resourceId = "app:id/spinner", bounds = r(400, 800, 600, 1_000))
            }
        })
        val readyScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 3, resourceId = "app:id/continue_btn", bounds = r(100, 1_000, 900, 1_200),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })

        val env = FakeEnvironment(initialScreen) {
            snapshot = windowSet(window("app") { node("Root") })
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }

        val step = tapStep(targetSelector("app:id/continue_btn", "Button", r(100, 1_000, 900, 1_200)))
        var outcome: ReplayResult? = null
        val job = launch {
            outcome = engine(env, { testScheduler.currentTime }, timeouts = ReplayTimeouts(preStepScreenMs = 7_000))
                .run(workflow(step), RecordingSink())
        }

        // Simulate 2.5s spinner before content appears
        testScheduler.advanceTimeBy(2_500)
        env.snapshot = readyScreen
        env.emit()
        testScheduler.advanceUntilIdle()
        job.join()

        assertEquals(ReplayResult.Success(1), outcome)
        assertEquals(1, env.requests.size)
        assertEquals(3, env.requests.first().nodeId)
        assertTrue(testScheduler.currentTime >= 2_500)
    }

    @Test
    fun `target that never appears fails with last decision code at deadline`() = runTest {
        val spinnerScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("ProgressBar", id = 2, resourceId = "app:id/spinner", bounds = r(400, 800, 600, 1_000))
            }
        })

        val env = FakeEnvironment(spinnerScreen) { DispatchResult.Rejected("unused") }
        val step = tapStep(targetSelector("app:id/continue_btn", "Button", r(100, 1_000, 900, 1_200)))
            .copy(timeoutMs = 1_000)

        val result = engine(env, { testScheduler.currentTime })
            .run(workflow(step), RecordingSink()) as ReplayResult.Failed

        assertEquals(ReplayFailureCode.TARGET_LOW_CONFIDENCE, result.failure.code)
        assertEquals(1_000L, testScheduler.currentTime)
        assertTrue(env.requests.isEmpty())
    }

    // --- Fix 2: Expected screen fingerprint as a soft hint ---

    @Test
    fun `expected screen fingerprint mismatch proceeds after soft hint deadline and records fingerprintSatisfied false`() = runTest {
        val differentScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("DifferentContent", id = 99, bounds = r(0, 0, 1_000, 500))
            }
        })
        val recordedFingerprint = DefaultScreenFingerprinter().fingerprint(differentScreen.windows.single(), null)

        val currentScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 2, resourceId = "app:id/target", bounds = r(100, 200, 900, 600),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })

        val env = FakeEnvironment(currentScreen) {
            snapshot = windowSet(window("app") { node("Root") })
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }

        val trace = RecordingSink()
        val step = tapStep(targetSelector("app:id/target", "Button", r(100, 200, 900, 600)))
            .copy(expectedBefore = recordedFingerprint, timeoutMs = 4_000)

        val result = engine(env, { testScheduler.currentTime }, timeouts = ReplayTimeouts(expectedScreenFingerprintMs = 1_500))
            .run(workflow(step), trace)

        assertEquals(ReplayResult.Success(1), result)
        val expectedScreenEvent = trace.events.filterIsInstance<ExpectedScreenRecorded>().singleOrNull()
        assertTrue("ExpectedScreenRecorded event must be emitted", expectedScreenEvent != null)
        assertFalse("fingerprintSatisfied must be false", expectedScreenEvent!!.fingerprintSatisfied)
        assertTrue(testScheduler.currentTime >= 1_500)
    }

    @Test
    fun `reveal step skips soft fingerprint wait entirely`() = runTest {
        val differentScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Different", id = 99)
            }
        })
        val recordedFingerprint = DefaultScreenFingerprinter().fingerprint(differentScreen.windows.single(), null)

        val listScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("RecyclerView", id = 2, resourceId = "app:id/list", bounds = r(0, 100, 1_000, 1_900),
                    scrollable = true, actions = setOf(NodeAction.SCROLL_FORWARD)) {
                    node("TextView", id = 3, resourceId = "app:id/target", bounds = r(0, 200, 1_000, 300),
                        clickable = true, actions = setOf(NodeAction.CLICK))
                }
            }
        })

        val env = FakeEnvironment(listScreen) {
            snapshot = windowSet(window("app") { node("Root") })
            DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
        }

        val step = tapStep(targetSelector("app:id/target", "TextView", r(0, 200, 1_000, 300)))
            .copy(
                expectedBefore = recordedFingerprint,
                reveal = RevealPolicy(RevealDirection.FORWARD, 5, 2)
            )

        val trace = RecordingSink()
        val result = engine(env, { testScheduler.currentTime }, timeouts = ReplayTimeouts(expectedScreenFingerprintMs = 1_500))
            .run(workflow(step), trace)

        assertEquals(ReplayResult.Success(1), result)
        // Reveal step should skip fingerprint wait entirely without waiting 1.5s
        assertTrue(testScheduler.currentTime < 1_500)
    }

    @Test
    fun `expected window requirement remains a hard gate when window never appears`() = runTest {
        val otherAppScreen = windowSet(window("other.app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000))
        })

        val env = FakeEnvironment(otherAppScreen) { DispatchResult.Rejected("unused") }
        val step = tapStep(targetSelector("app:id/target", "Button", r(100, 200, 900, 600)))
            .copy(timeoutMs = 500)

        val result = engine(env, { testScheduler.currentTime })
            .run(workflow(step), RecordingSink()) as ReplayResult.Failed

        assertEquals(ReplayFailureCode.WINDOW_NOT_FOUND, result.failure.code)
        assertTrue(env.requests.isEmpty())
    }

    // --- Fix 3: Uniform lists scroll progress judged by container content signature ---

    @Test
    fun `uniform list of identical rows makes progress via container content signature`() = runTest {
        var startRow = 0
        val env = FakeEnvironment(uniformListScreen(0)) { request ->
            if (request.semanticAction == NodeAction.SCROLL_FORWARD) {
                startRow += 10
                snapshot = uniformListScreen(startRow)
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/row-45", LocatorStability.STABLE)),
            expectedClass = "TextView", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet(),
            scrollContainer = listContainerSelector()
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 8, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())

        assertEquals(ReplayResult.Success(1), result)
        assertEquals(4, env.requests.count { it.semanticAction == NodeAction.SCROLL_FORWARD })
    }

    @Test
    fun `two consecutive unchanged container signatures fail with scroll no progress`() = runTest {
        val staticScreen = uniformListScreen(0)
        val env = FakeEnvironment(staticScreen) { DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION) }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/row-99", LocatorStability.STABLE)),
            expectedClass = "TextView", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet(),
            scrollContainer = listContainerSelector()
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 5, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink()) as ReplayResult.Failed

        assertEquals(ReplayFailureCode.SCROLL_NO_PROGRESS, result.failure.code)
        assertEquals(2, env.requests.size)
    }

    // --- Fix 4: Scroll container fallback via recorded point ---

    @Test
    fun `scroll container fallback locates nearest scrollable ancestor of recorded point`() = runTest {
        var startRow = 0
        fun composeScreen(start: Int) = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                // Compose LazyColumn with no resource ID matching the recorded scrollContainer
                node("ComposeLazyColumn", id = 10, bounds = r(0, 200, 1_000, 1_800), scrollable = true,
                    actions = setOf(NodeAction.SCROLL_FORWARD)) {
                    (start until (start + 10).coerceAtMost(60)).forEach { row ->
                        node("ComposeItem", id = 100 + row, bounds = r(0, 200 + (row - start) * 150, 1_000, 350 + (row - start) * 150)) {
                            node("Text", id = 1_000 + row, resourceId = "app:id/row-$row", text = "Row $row",
                                bounds = r(50, 220 + (row - start) * 150, 950, 330 + (row - start) * 150),
                                clickable = true, actions = setOf(NodeAction.CLICK))
                        }
                    }
                }
            }
        })

        val env = FakeEnvironment(composeScreen(0)) { request ->
            if (request.semanticAction == NodeAction.SCROLL_FORWARD) {
                assertEquals(10, request.nodeId) // Successfully targeted LazyColumn (id 10)
                startRow += 10
                snapshot = composeScreen(startRow)
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        // Target with broken container selector, but valid recorded point in window (0.5, 0.25 -> 500, 500)
        // Item 25 is reached after 2 scrolls (0..9 -> 10..19 -> 20..29)
        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/row-25", LocatorStability.STABLE)),
            expectedClass = "Text", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f),
            rawPoint = CapturedPoint(
                displayPx = IntPoint(500, 500),
                pointInWindow = NormalizedPoint(0.5f, 0.25f),
                pointInNode = null,
                displayWidthPx = 1_000, displayHeightPx = 2_000, rotation = 0, densityDpi = 420
            ),
            requiredActions = emptySet(),
            scrollContainer = ElementSelector(
                strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/non_existent_container", LocatorStability.STABLE)),
                expectedClass = "RecyclerView", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null,
                rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null,
                requiredActions = setOf(NodeAction.SCROLL_FORWARD)
            )
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 5, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())

        assertEquals(ReplayResult.Success(1), result)
        assertEquals(2, env.requests.count { it.semanticAction == NodeAction.SCROLL_FORWARD })
    }

    @Test
    fun `scroll container fallback locates largest visible scrollable node containing recorded point`() = runTest {
        val initialScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                // Nested smaller scrollable that does not contain (500, 600)
                node("SmallScrollable", id = 5, bounds = r(100, 100, 300, 300), scrollable = true,
                    actions = setOf(NodeAction.SCROLL_FORWARD))
                // Larger scrollable that contains point (500, 600)
                node("LargeScrollable", id = 6, bounds = r(50, 200, 950, 1_800), scrollable = true,
                    actions = setOf(NodeAction.SCROLL_FORWARD))
            }
        })

        var scrolledContainerId: Int? = null
        val env = FakeEnvironment(initialScreen) { request ->
            if (request.nodeId == 6) {
                scrolledContainerId = request.nodeId
                snapshot = windowSet(window("app") {
                    node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                        node("LargeScrollable", id = 6, bounds = r(50, 200, 950, 1_800), scrollable = true,
                            actions = setOf(NodeAction.SCROLL_FORWARD)) {
                            node("Target", id = 7, resourceId = "app:id/target", bounds = r(100, 500, 900, 700),
                                clickable = true, actions = setOf(NodeAction.CLICK))
                        }
                    }
                })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/target", LocatorStability.STABLE)),
            expectedClass = "Target", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f),
            rawPoint = CapturedPoint(
                displayPx = IntPoint(500, 600),
                pointInWindow = NormalizedPoint(0.5f, 0.3f),
                pointInNode = null,
                displayWidthPx = 1_000, displayHeightPx = 2_000, rotation = 0, densityDpi = 420
            ),
            requiredActions = emptySet(),
            scrollContainer = null
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 2, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())
        assertEquals(ReplayResult.Success(1), result)
        assertEquals(6, scrolledContainerId)
    }

    // --- Fix 5: Reveal direction and swipe semantics ---

    @Test
    fun `reveal policy forward or down prefers scroll down action when scroll forward is absent`() = runTest {
        val screen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("ScrollView", id = 2, resourceId = "app:id/scroll", bounds = r(0, 100, 1_000, 1_900),
                    scrollable = true, actions = setOf(NodeAction.SCROLL_DOWN))
            }
        })

        val env = FakeEnvironment(screen) { request ->
            if (request.nodeId == 2) {
                assertEquals(NodeAction.SCROLL_DOWN, request.semanticAction)
                snapshot = windowSet(window("app") {
                    node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                        node("ScrollView", id = 2, resourceId = "app:id/scroll", bounds = r(0, 100, 1_000, 1_900),
                            scrollable = true, actions = setOf(NodeAction.SCROLL_DOWN)) {
                            node("Button", id = 3, resourceId = "app:id/row-10", bounds = r(0, 200, 1_000, 300),
                                clickable = true, actions = setOf(NodeAction.CLICK))
                        }
                    }
                })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                assertEquals(NodeAction.CLICK, request.semanticAction)
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/row-10", LocatorStability.STABLE)),
            expectedClass = "Button", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet(),
            scrollContainer = ElementSelector(
                strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/scroll", LocatorStability.STABLE)),
                expectedClass = "ScrollView", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null,
                rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet()
            )
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 3, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())
        assertEquals(ReplayResult.Success(1), result)
        assertEquals(NodeAction.SCROLL_DOWN, env.requests.first().semanticAction)
    }

    @Test
    fun `reveal fallback swipe for forward moves finger up from 75 percent to 25 percent`() = runTest {
        val screen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                // Container with NO accessibility scroll actions
                node("GestureContainer", id = 2, resourceId = "app:id/container", bounds = r(0, 200, 1_000, 1_800),
                    scrollable = true, actions = emptySet())
            }
        })

        val env = FakeEnvironment(screen) { request ->
            if (request.nodeId == 2) {
                assertEquals(DispatchMethod.GESTURE, request.dispatchMethod)
                // Container height = 1600. Center x = 500. 75% = 200 + 1200 = 1400. 25% = 200 + 400 = 600.
                assertEquals(listOf(IntPoint(500, 1400), IntPoint(500, 600)), request.gesturePoints)
                assertEquals(300L, request.gesture?.durationMs)
                snapshot = windowSet(window("app") {
                    node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                        node("GestureContainer", id = 2, resourceId = "app:id/container", bounds = r(0, 200, 1_000, 1_800),
                            scrollable = true, actions = emptySet()) {
                            node("Button", id = 3, resourceId = "app:id/target", bounds = r(0, 300, 1_000, 400),
                                clickable = true, actions = setOf(NodeAction.CLICK))
                        }
                    }
                })
                DispatchResult.Accepted(DispatchMethod.GESTURE)
            } else {
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/target", LocatorStability.STABLE)),
            expectedClass = "Button", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet(),
            scrollContainer = ElementSelector(
                strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/container", LocatorStability.STABLE)),
                expectedClass = "GestureContainer", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null,
                rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet()
            )
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 3, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())
        assertEquals(ReplayResult.Success(1), result)
    }

    @Test
    fun `reveal fallback swipe for backward or up moves finger down from 25 percent to 75 percent`() = runTest {
        val screen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("GestureContainer", id = 2, resourceId = "app:id/container", bounds = r(0, 200, 1_000, 1_800),
                    scrollable = true, actions = emptySet())
            }
        })

        val env = FakeEnvironment(screen) { request ->
            if (request.nodeId == 2) {
                assertEquals(DispatchMethod.GESTURE, request.dispatchMethod)
                // Container height = 1600. Center x = 500. 25% = 200 + 400 = 600. 75% = 200 + 1200 = 1400.
                assertEquals(listOf(IntPoint(500, 600), IntPoint(500, 1400)), request.gesturePoints)
                assertEquals(300L, request.gesture?.durationMs)
                snapshot = windowSet(window("app") {
                    node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                        node("GestureContainer", id = 2, resourceId = "app:id/container", bounds = r(0, 200, 1_000, 1_800),
                            scrollable = true, actions = emptySet()) {
                            node("Button", id = 3, resourceId = "app:id/target", bounds = r(0, 300, 1_000, 400),
                                clickable = true, actions = setOf(NodeAction.CLICK))
                        }
                    }
                })
                DispatchResult.Accepted(DispatchMethod.GESTURE)
            } else {
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/target", LocatorStability.STABLE)),
            expectedClass = "Button", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet(),
            scrollContainer = ElementSelector(
                strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/container", LocatorStability.STABLE)),
                expectedClass = "GestureContainer", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null,
                rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = emptySet()
            )
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.UP, 3, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())
        assertEquals(ReplayResult.Success(1), result)
    }

    // --- Fix 6: Settle after scroll & Movement rejection retry ---

    @Test
    fun `list still moving for 400 ms after the scroll engine waits and taps the right row once`() = runTest {
        var scrollStartTime = -1L
        var tapCount = 0
        var tapped = false

        fun movingListScreen(startRow: Int, offsetPx: Int): WindowSetSnapshot = windowSet(window("app") {
            node("Root") {
                node("RecyclerView", id = 2, resourceId = "app:id/list", bounds = r(0, 100, 1_000, 1_900),
                    scrollable = true, actions = setOf(NodeAction.SCROLL_FORWARD)) {
                    (startRow until (startRow + 10).coerceAtMost(60)).forEach { row ->
                        node("TextView", resourceId = "app:id/row-$row",
                            bounds = r(0, 100 + (row - startRow) * 100 + offsetPx, 1_000, 190 + (row - startRow) * 100 + offsetPx),
                            collectionRow = row, clickable = true, actions = setOf(NodeAction.CLICK))
                    }
                }
            }
        })

        lateinit var env: FakeEnvironment
        env = FakeEnvironment(uniformListScreen(0)) { request ->
            if (request.semanticAction == NodeAction.SCROLL_FORWARD) {
                scrollStartTime = testScheduler.currentTime
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else if (request.semanticAction == NodeAction.CLICK) {
                tapCount++
                tapped = true
                snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                DispatchResult.Accepted(request.dispatchMethod)
            }
        }

        env.onSnapshot = {
            if (scrollStartTime >= 0 && !tapped) {
                val elapsed = testScheduler.currentTime - scrollStartTime
                if (elapsed < 400) {
                    env.snapshot = movingListScreen(40, (400 - elapsed).toInt())
                } else {
                    env.snapshot = movingListScreen(40, 0)
                }
            }
        }

        val target = ElementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/row-45", LocatorStability.STABLE)),
            expectedClass = "TextView", expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
            boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null, requiredActions = setOf(NodeAction.CLICK),
            scrollContainer = listContainerSelector()
        )
        val step = tapStep(target).copy(reveal = RevealPolicy(RevealDirection.FORWARD, 8, 2))

        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), RecordingSink())

        assertEquals(ReplayResult.Success(1), result)
        assertEquals(1, tapCount)
        assertEquals(1, env.requests.count { it.semanticAction == NodeAction.SCROLL_FORWARD })
        assertTrue("Tap must happen after list settles", testScheduler.currentTime >= 400)
    }

    @Test
    fun `target moves between locate and act results in one re-locate and successful retry`() = runTest {
        val initialScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 2, resourceId = "app:id/target", bounds = r(100, 200, 900, 400),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })
        val movedScreen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 2, resourceId = "app:id/target", bounds = r(100, 600, 900, 800),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })

        var attempts = 0
        lateinit var env: FakeEnvironment
        env = FakeEnvironment(initialScreen) { request ->
            attempts++
            if (attempts == 1) {
                // First act attempt fails with movement rejection; screen is now settled at moved location
                env.snapshot = movedScreen
                DispatchResult.Rejected("Bounds mismatch for node 2: live bounds do not overlap expected")
            } else {
                // Retry succeeds
                env.snapshot = windowSet(window("app") { node("Root") })
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            }
        }

        val trace = RecordingSink()
        val step = tapStep(targetSelector("app:id/target", "Button", r(100, 200, 900, 400)))
        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), trace)

        assertEquals(ReplayResult.Success(1), result)
        assertEquals(2, attempts)
        val retryEvent = trace.events.filterIsInstance<ActionRetryRecorded>().single()
        assertEquals("Bounds mismatch for node 2: live bounds do not overlap expected", retryEvent.retryReason)
        assertEquals(1, retryEvent.attempt)
    }

    @Test
    fun `three consecutive movement rejections fails after 2 retries with DISPATCH_REJECTED`() = runTest {
        val screen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 2, resourceId = "app:id/target", bounds = r(100, 200, 900, 400),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })

        var attempts = 0
        val env = FakeEnvironment(screen) {
            attempts++
            DispatchResult.Rejected("Bounds mismatch for node 2: live bounds do not overlap expected")
        }

        val trace = RecordingSink()
        val step = tapStep(targetSelector("app:id/target", "Button", r(100, 200, 900, 400)))
        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), trace) as ReplayResult.Failed

        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, result.failure.code)
        assertEquals(3, attempts) // 1 initial + 2 retries
        val retries = trace.events.filterIsInstance<ActionRetryRecorded>()
        assertEquals(2, retries.size)
        assertEquals(1, retries[0].attempt)
        assertEquals(2, retries[1].attempt)
    }

    @Test
    fun `IRREVERSIBLE step with a movement rejection does not retry`() = runTest {
        val screen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 2, resourceId = "app:id/target", bounds = r(100, 200, 900, 400),
                    clickable = true, actions = setOf(NodeAction.CLICK))
            }
        })

        var attempts = 0
        val env = FakeEnvironment(screen) {
            attempts++
            DispatchResult.Rejected("Gesture point no longer hits the recorded target")
        }

        val trace = RecordingSink()
        val step = tapStep(targetSelector("app:id/target", "Button", r(100, 200, 900, 400)), safety = StepSafety.IRREVERSIBLE)
        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), trace) as ReplayResult.Failed

        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, result.failure.code)
        assertEquals(1, attempts)
        val retries = trace.events.filterIsInstance<ActionRetryRecorded>()
        assertTrue("Irreversible steps must never be retried", retries.isEmpty())
    }

    @Test
    fun `non-movement rejection does not retry`() = runTest {
        val screen = windowSet(window("app") {
            node("Root", bounds = r(0, 0, 1_000, 2_000)) {
                node("Button", id = 2, resourceId = "app:id/target", bounds = r(100, 200, 900, 400),
                    clickable = false)
            }
        })

        var attempts = 0
        val env = FakeEnvironment(screen) {
            attempts++
            DispatchResult.Rejected("Permission denied")
        }

        val trace = RecordingSink()
        val target = targetSelector("app:id/target", "Button", r(100, 200, 900, 400)).copy(requiredActions = emptySet())
        val step = tapStep(target)
        val result = engine(env, { testScheduler.currentTime }).run(workflow(step), trace) as ReplayResult.Failed

        assertEquals(ReplayFailureCode.DISPATCH_REJECTED, result.failure.code)
        assertEquals(1, attempts)
        val retries = trace.events.filterIsInstance<ActionRetryRecorded>()
        assertTrue("Non-movement rejections must not be retried", retries.isEmpty())
    }

    // --- Helpers ---

    private fun engine(
        env: FakeEnvironment,
        clock: () -> Long,
        cancellation: ReplayCancellationSignal = ReplayCancellationSignal.None,
        timeouts: ReplayTimeouts = ReplayTimeouts()
    ) = DefaultReplayEngine(
        env, FakeLauncher(), WindowMatcher(DefaultScreenFingerprinter()), SelectorMatcher(),
        DefaultScreenFingerprinter(), clock, { delay(it) }, cancellation, timeouts
    )

    private fun workflow(vararg steps: com.shortcuts.app.automation.model.Step) =
        WorkflowV2(workflowId = "regression-test", recordedDevice = device, steps = steps.toList())

    private fun targetSelector(resourceId: String, expectedClass: String, bounds: IntRect) = ElementSelector(
        strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, resourceId, LocatorStability.STABLE)),
        expectedClass = expectedClass, expectedRole = null, ancestorChain = emptyList(), siblingIndex = null, rowContext = null,
        boundsInWindow = NormalizedRect(bounds.left / 1_000f, bounds.top / 2_000f, bounds.right / 1_000f, bounds.bottom / 2_000f),
        rawPoint = null, requiredActions = setOf(NodeAction.CLICK)
    )

    private fun tapStep(
        element: ElementSelector,
        safety: StepSafety = StepSafety.REVERSIBLE,
        verification: VerificationSpec = VerificationSpec(listOf(VerificationPostcondition.TargetPresence(element, false)))
    ) = UiStep(
        id = "tap", operation = UiOperation.TAP, expectedWindow = expectedWindow, element = element,
        gesture = null, verification = verification, safety = safety
    )

    private fun listContainerSelector() = ElementSelector(
        strategies = listOf(LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "app:id/list", LocatorStability.STABLE)),
        expectedClass = "RecyclerView", expectedRole = null,
        ancestorChain = emptyList(), siblingIndex = null, rowContext = null, boundsInWindow = NormalizedRect(0f, 0f, 1f, 1f), rawPoint = null,
        requiredActions = setOf(NodeAction.SCROLL_FORWARD)
    )

    private fun uniformListScreen(start: Int): WindowSetSnapshot = windowSet(window("app") {
        node("Root") {
            node("RecyclerView", id = 2, resourceId = "app:id/list", bounds = r(0, 100, 1_000, 1_900),
                scrollable = true, actions = setOf(NodeAction.SCROLL_FORWARD)) {
                (start until (start + 10).coerceAtMost(60)).forEach { row ->
                    node("TextView", resourceId = "app:id/row-$row",
                        bounds = r(0, 100 + (row - start) * 100, 1_000, 190 + (row - start) * 100),
                        collectionRow = row, clickable = true, actions = setOf(NodeAction.CLICK))
                }
            }
        }
    })

    private class FakeEnvironment(
        initial: WindowSetSnapshot,
        private val handler: suspend FakeEnvironment.(UiActionRequest) -> DispatchResult
    ) : UiEnvironment {
        var snapshot = initial
        val requests = mutableListOf<UiActionRequest>()
        val activities = mutableMapOf<Int, String?>()
        var onSnapshot: (() -> Unit)? = null
        private val eventFlow = MutableSharedFlow<UiEnvironmentEvent>(extraBufferCapacity = 8)
        override fun snapshotWindows(): WindowSetSnapshot { onSnapshot?.invoke(); return snapshot }
        override fun events(): Flow<UiEnvironmentEvent> = eventFlow.asSharedFlow()
        override fun currentActivityClass(windowId: Int) = activities[windowId]
        override suspend fun perform(request: UiActionRequest): DispatchResult { requests += request; return handler(request) }
        fun emit() { eventFlow.tryEmit(UiEnvironmentEvent.ContentChanged(0, 1)) }
    }

    private class FakeLauncher : AppLauncher {
        override suspend fun launch(step: LaunchAppStep) = LaunchDispatchResult.Accepted
    }

    private class RecordingSink : TraceSink {
        val events = mutableListOf<TraceEvent>()
        override fun append(event: TraceEvent) { events += event }
    }

    private companion object {
        val device = RecordedDevice("test", "test", 1, 1_000, 2_000, 420, 0)
        val expectedWindow = WindowSelector("app", null, WindowType.APPLICATION, null, NormalizedRect(0f, 0f, 1f, 1f), 0, null)
    }
}

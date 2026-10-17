package com.shortcuts.app.automation.replay

import com.shortcuts.app.automation.core.fingerprint.ScreenFingerprinter
import com.shortcuts.app.automation.core.matching.MatchDecision
import com.shortcuts.app.automation.core.matching.MatchOptions
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.WindowDecision
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.diagnostics.ExpectedScreenRecorded
import com.shortcuts.app.automation.diagnostics.DispatchRecorded
import com.shortcuts.app.automation.diagnostics.MatchDecisionRecorded
import com.shortcuts.app.automation.diagnostics.PostconditionRecorded
import com.shortcuts.app.automation.diagnostics.PreflightWarning
import com.shortcuts.app.automation.diagnostics.StateEntered
import com.shortcuts.app.automation.diagnostics.TraceSink
import com.shortcuts.app.automation.diagnostics.TraceEvent
import com.shortcuts.app.automation.diagnostics.WindowDecisionRecorded
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.CoordinateSpace
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LegacyActionStep
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeBooleanState
import com.shortcuts.app.automation.model.RevealDirection
import com.shortcuts.app.automation.model.RevealPolicy
import com.shortcuts.app.automation.model.ScreenFingerprint
import com.shortcuts.app.automation.model.Step
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationPostcondition
import com.shortcuts.app.automation.model.WaitStep
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowV2
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.math.roundToInt

/** Replay deadlines. Delays are injected to make all waiting deterministic under `runTest`. */
data class ReplayTimeouts(
    val launchMs: Long = 12_000,
    val preStepScreenMs: Long = 7_000,
    val postActionMs: Long = 5_000,
    val textMs: Long = 2_000,
    val scrollToFindMs: Long = 10_000,
    val scrollMaxAttempts: Int = 12,
    val settleMs: Long = 300,
    val safetyPollMs: Long = 250,
    val stableSnapshotGapMs: Long = 100,
    val expectedScreenFingerprintMs: Long = 1_500
)

enum class ReplayState {
    PREFLIGHT,
    LAUNCHING,
    WAITING_FOR_EXPECTED_SCREEN,
    LOCATING,
    SCROLLING,
    ACTING,
    VERIFYING,
    FINISHED,
    FAILED,
    CANCELLED
}

interface LegacyStepRunner {
    suspend fun run(step: LegacyActionStep): LegacyStepResult
}

sealed interface LegacyStepResult {
    data object Success : LegacyStepResult
    data class Failure(val message: String) : LegacyStepResult
}

private object UnsupportedLegacyStepRunner : LegacyStepRunner {
    override suspend fun run(step: LegacyActionStep): LegacyStepResult =
        LegacyStepResult.Failure("No LegacyStepRunner is installed")
}

/**
 * Pure Kotlin replay orchestration. It never holds platform objects across waits; all decisions
 * are made from a fresh [WindowSetSnapshot] supplied by the Android adapter.
 */
class DefaultReplayEngine(
    private val environment: UiEnvironment,
    private val appLauncher: AppLauncher,
    private val windowMatcher: WindowMatcher,
    private val selectorMatcher: SelectorMatcher,
    private val fingerprinter: ScreenFingerprinter,
    private val clock: () -> Long,
    private val delayer: suspend (Long) -> Unit,
    private val cancellation: ReplayCancellationSignal = ReplayCancellationSignal.None,
    private val timeouts: ReplayTimeouts = ReplayTimeouts(),
    private val legacyStepRunner: LegacyStepRunner = UnsupportedLegacyStepRunner
) : ReplayEngine {

    override fun preflight(workflow: WorkflowV2): List<ReplayFailure> = buildList {
        if (workflow.schemaVersion != WorkflowV2.SCHEMA_VERSION) {
            add(ReplayFailure(ReplayFailureCode.DECODE_FAILED, null, "Unsupported workflow schema ${workflow.schemaVersion}"))
        }
        workflow.steps.forEach { step ->
            if (step.id.isBlank()) add(ReplayFailure(ReplayFailureCode.DECODE_FAILED, null, "A step has no id"))
            if (step is UiStep) {
                if (step.element == null && step.gesture == null) {
                    add(ReplayFailure(ReplayFailureCode.DECODE_FAILED, step.id, "UI step has neither an element nor a gesture"))
                }
                if (step.verification.postconditions.isEmpty() && !step.verification.allowBestEffort) {
                    add(ReplayFailure(ReplayFailureCode.UNVERIFIABLE_STEP, step.id, "UI step has no observable postcondition"))
                }
            }
        }
    }

    override suspend fun run(workflow: WorkflowV2, sink: TraceSink): ReplayResult {
        sink.append(TraceEvent.RunStarted(clock(), runId = "${workflow.workflowId}-${clock()}", workflowId = workflow.workflowId))
        transition(sink, null, ReplayState.PREFLIGHT)
        workflow.steps.forEach { step ->
            legacyCoordinateWarning(step)?.let { sink.append(PreflightWarning(clock(), step.id, it)) }
        }
        val preflightFailures = preflight(workflow)
        if (preflightFailures.isNotEmpty()) return finishRun(sink, 0, preflightFailures.first())
        if (cancelled()) return finishRun(sink, 0, cancelledFailure(null))

        var completed = 0
        for ((index, step) in workflow.steps.withIndex()) {
            sink.append(TraceEvent.StepStarted(clock(), step.id, index))
            val result = executeStep(step, sink)
            if (result != null) {
                transition(sink, step.id, if (result.code == ReplayFailureCode.CANCELLED) ReplayState.CANCELLED else ReplayState.FAILED)
                sink.append(TraceEvent.StepFinished(clock(), step.id, dispatchAccepted = false, verified = false))
                return finishRun(sink, completed, result)
            }
            transition(sink, step.id, ReplayState.FINISHED)
            sink.append(TraceEvent.StepFinished(clock(), step.id, dispatchAccepted = true, verified = true))
            completed++
        }
        return finishRun(sink, completed, null)
    }

    private suspend fun executeStep(step: Step, sink: TraceSink): ReplayFailure? = when (step) {
        is LaunchAppStep -> executeLaunch(step, sink)
        is UiStep -> executeUi(step, sink)
        is WaitStep -> executeWait(step, sink)
        is LegacyActionStep -> executeLegacy(step, sink)
    }

    private suspend fun executeLaunch(step: LaunchAppStep, sink: TraceSink): ReplayFailure? {
        transition(sink, step.id, ReplayState.LAUNCHING)
        if (cancelled()) return cancelledFailure(step.id)
        when (val launch = appLauncher.launch(step)) {
            LaunchDispatchResult.Accepted -> Unit
            is LaunchDispatchResult.Rejected -> return failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, launch.reason)
        }
        transition(sink, step.id, ReplayState.WAITING_FOR_EXPECTED_SCREEN)
        return waitForExpectedWindow(
            stepId = step.id,
            selector = step.expectedWindow,
            expected = step.expectedAfter ?: step.expectedWindow.fingerprint,
            timeoutMs = minOf(step.timeoutMs, timeouts.launchMs),
            sink = sink,
            timeoutCode = ReplayFailureCode.EXPECTED_SCREEN_TIMEOUT
        )?.failure
    }

    private suspend fun executeWait(step: WaitStep, sink: TraceSink): ReplayFailure? {
        transition(sink, step.id, ReplayState.WAITING_FOR_EXPECTED_SCREEN)
        val end = clock() + step.durationMs
        while (clock() < end) {
            if (cancelled()) return cancelledFailure(step.id)
            if (!environment.isAccessibilityAvailable()) return accessibilityFailure(step.id)
            when (awaitSignal(minOf(timeouts.safetyPollMs, end - clock()))) {
                is AwaitSignal.Cancelled -> return cancelledFailure(step.id)
                is AwaitSignal.Disconnected -> return accessibilityFailure(step.id)
                else -> Unit
            }
        }
        return null
    }

    private suspend fun executeLegacy(step: LegacyActionStep, sink: TraceSink): ReplayFailure? {
        transition(sink, step.id, ReplayState.ACTING)
        if (cancelled()) return cancelledFailure(step.id)
        return when (val result = legacyStepRunner.run(step)) {
            LegacyStepResult.Success -> null
            is LegacyStepResult.Failure -> failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, result.message)
        }
    }

    private suspend fun executeUi(step: UiStep, sink: TraceSink): ReplayFailure? {
        transition(sink, step.id, ReplayState.WAITING_FOR_EXPECTED_SCREEN)
        val stepTimeoutMs = minOf(step.timeoutMs, timeouts.preStepScreenMs)
        val stepDeadline = clock() + stepTimeoutMs

        // (a) Expected WINDOW requirement stays hard
        val selected = waitForExpectedWindow(
            stepId = step.id,
            selector = step.expectedWindow,
            expected = null,
            timeoutMs = (stepDeadline - clock()).coerceAtLeast(0),
            sink = sink,
            timeoutCode = null
        ) ?: return failure(ReplayFailureCode.WINDOW_NOT_FOUND, step.id, "No selectable expected window")
        selected.failure?.let { return it }
        val window = selected.window ?: return failure(ReplayFailureCode.WINDOW_NOT_FOUND, step.id, "No selectable expected window")

        // (b) Soft 1.5 s fingerprint hint (skip for reveal steps)
        val hasReveal = step.reveal != null || step.element?.scrollContainer != null
        if (!hasReveal && step.expectedBefore != null) {
            val softWaitMs = minOf(timeouts.expectedScreenFingerprintMs, (stepDeadline - clock()).coerceAtLeast(0))
            val fingerprintSatisfied = waitForSoftFingerprint(
                stepId = step.id,
                selector = step.expectedWindow,
                expected = step.expectedBefore,
                timeoutMs = softWaitMs,
                sink = sink
            )
            sink.append(ExpectedScreenRecorded(clock(), step.id, fingerprintSatisfied = fingerprintSatisfied))
        }

        if (cancelled()) return cancelledFailure(step.id)
        if (!environment.isAccessibilityAvailable()) return accessibilityFailure(step.id)

        // Target Locating with event-driven retry until step deadline
        val target = locateTarget(step, window, stepDeadline, sink)
        target.failure?.let { return it }
        val node = target.node ?: return failure(ReplayFailureCode.TARGET_LOW_CONFIDENCE, step.id, "No accepted target")
        val beforeWindow = freshSelectedWindow(step.expectedWindow) ?: window
        val beforeFingerprint = fingerprint(beforeWindow)
        val beforeNode = beforeWindow.node(node.nodeId) ?: node

        val first = act(step, beforeWindow, beforeNode, sink, forceGesture = false)
        first.failure?.let { return it }
        transition(sink, step.id, ReplayState.VERIFYING)
        var verification = verify(step, beforeNode, beforeFingerprint, sink)
        if (verification == null) return null

        if (step.operation == UiOperation.TAP && step.safety == StepSafety.REVERSIBLE && first.usedSemantic &&
            retryIsSafe(step, beforeNode, beforeFingerprint, window)
        ) {
            transition(sink, step.id, ReplayState.ACTING)
            val retryWindow = freshSelectedWindow(step.expectedWindow) ?: window
            val retryNode = retryWindow.node(beforeNode.nodeId) ?: return verification
            val retry = act(step, retryWindow, retryNode, sink, forceGesture = true)
            retry.failure?.let { return it }
            transition(sink, step.id, ReplayState.VERIFYING)
            verification = verify(step, beforeNode, beforeFingerprint, sink)
        }
        return verification
    }

    private suspend fun waitForSoftFingerprint(
        stepId: String,
        selector: WindowSelector,
        expected: ScreenFingerprint,
        timeoutMs: Long,
        sink: TraceSink
    ): Boolean {
        val deadline = clock() + timeoutMs
        var currentWindow = freshSelectedWindow(selector)
        if (currentWindow != null && fingerprinter.similarity(expected, fingerprint(currentWindow)) >= 0.80) {
            return true
        }
        while (clock() < deadline) {
            if (cancelled() || !environment.isAccessibilityAvailable()) return false
            val remaining = (deadline - clock()).coerceAtLeast(0)
            when (awaitSignal(minOf(timeouts.safetyPollMs, remaining))) {
                is AwaitSignal.Cancelled -> return false
                is AwaitSignal.Disconnected -> return false
                else -> Unit
            }
            currentWindow = freshSelectedWindow(selector)
            if (currentWindow != null && fingerprinter.similarity(expected, fingerprint(currentWindow)) >= 0.80) {
                return true
            }
        }
        return false
    }

    private suspend fun locateTarget(
        step: UiStep,
        initialWindow: WindowSnapshot,
        stepDeadline: Long,
        sink: TraceSink
    ): LocatedTarget {
        if (step.element == null && step.gesture != null) {
            val win = freshSelectedWindow(step.expectedWindow) ?: initialWindow
            return LocatedTarget(win.node(win.rootId), null)
        }

        val hasReveal = step.reveal != null || step.element?.scrollContainer != null
        if (hasReveal) {
            val win = freshSelectedWindow(step.expectedWindow) ?: initialWindow
            val target = locate(step, win, sink)
            if (target.failure == null && target.node != null) {
                return target
            }
            return scrollToFind(step, win, sink)
        }

        var lastFailure: ReplayFailure? = null
        while (clock() <= stepDeadline) {
            if (cancelled()) return LocatedTarget(null, cancelledFailure(step.id))
            if (!environment.isAccessibilityAvailable()) return LocatedTarget(null, accessibilityFailure(step.id))

            val currentWindow = freshSelectedWindow(step.expectedWindow)
            if (currentWindow != null) {
                val target = locate(step, currentWindow, sink)
                if (target.node != null && target.failure == null) {
                    return target
                }
                lastFailure = target.failure
            } else {
                lastFailure = failure(ReplayFailureCode.WINDOW_NOT_FOUND, step.id, "Expected window was lost during locating")
            }

            if (clock() >= stepDeadline) break

            val remaining = (stepDeadline - clock()).coerceAtLeast(0)
            when (awaitSignal(minOf(timeouts.safetyPollMs, remaining))) {
                is AwaitSignal.Cancelled -> return LocatedTarget(null, cancelledFailure(step.id))
                is AwaitSignal.Disconnected -> return LocatedTarget(null, accessibilityFailure(step.id))
                else -> Unit
            }
        }
        return LocatedTarget(null, lastFailure ?: failure(ReplayFailureCode.TARGET_LOW_CONFIDENCE, step.id, "No accepted target"))
    }

    private fun locate(step: UiStep, window: WindowSnapshot, sink: TraceSink): LocatedTarget {
        transition(sink, step.id, ReplayState.LOCATING)
        val selector = step.element ?: return if (step.gesture != null) LocatedTarget(null, null) else LocatedTarget(null,
            failure(ReplayFailureCode.DECODE_FAILED, step.id, "No element selector"))
        val decision = selectorMatcher.match(window, selector, matchOptions(step))
        traceMatch(sink, step.id, decision)
        return decision.asLocated(step.id)
    }

    private suspend fun scrollToFind(step: UiStep, initialWindow: WindowSnapshot, sink: TraceSink): LocatedTarget {
        val targetSelector = step.element ?: return LocatedTarget(null, failure(ReplayFailureCode.SCROLL_CONTAINER_NOT_FOUND, step.id, "No target selector"))
        val reveal = step.reveal ?: RevealPolicy(RevealDirection.FORWARD, timeouts.scrollMaxAttempts, 2)
        val maxAttempts = minOf(reveal.maxAttempts.coerceAtLeast(1), timeouts.scrollMaxAttempts)
        val deadline = clock() + timeouts.scrollToFindMs
        var noProgress = 0
        var window = initialWindow

        var previousSignature: ContainerSignature? = null

        repeat(maxAttempts) {
            if (cancelled()) return LocatedTarget(null, cancelledFailure(step.id))
            if (clock() >= deadline) return LocatedTarget(null, failure(ReplayFailureCode.TARGET_NOT_FOUND_AFTER_SCROLL, step.id, "Target was not found before scroll deadline"))
            transition(sink, step.id, ReplayState.SCROLLING)

            val container = findScrollContainer(step, window, sink)
                ?: return LocatedTarget(null, failure(ReplayFailureCode.SCROLL_CONTAINER_NOT_FOUND, step.id, "Recorded scroll container was not found"))

            if (previousSignature == null) {
                previousSignature = containerContentSignature(window, container)
            }

            val action = scrollAction(reveal.direction, container)
            val dispatch = if (action != null) {
                dispatch(step, UiActionRequest(step.operation, window.windowId, container.nodeId,
                    semanticAction = action, dispatchMethod = DispatchMethod.SEMANTIC_ACTION), sink)
            } else {
                val points = scrollPoints(container.boundsInScreen, reveal.direction)
                dispatch(step, UiActionRequest(step.operation, window.windowId, container.nodeId,
                    gesture = scrollGesture(points), gesturePoints = points, dispatchMethod = DispatchMethod.GESTURE), sink)
            }
            if (dispatch is DispatchResult.Rejected) return LocatedTarget(null,
                failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, dispatch.reason))

            when (awaitSignal(minOf(timeouts.safetyPollMs, (deadline - clock()).coerceAtLeast(0)))) {
                is AwaitSignal.Cancelled -> return LocatedTarget(null, cancelledFailure(step.id))
                is AwaitSignal.Disconnected -> return LocatedTarget(null, accessibilityFailure(step.id))
                else -> Unit
            }
            window = freshSelectedWindow(step.expectedWindow) ?: window

            val currentContainer = findScrollContainer(step, window, sink) ?: container
            val currentSignature = containerContentSignature(window, currentContainer)

            if (currentSignature == previousSignature) {
                noProgress++
            } else {
                noProgress = 0
            }
            if (noProgress >= reveal.noProgressLimit.coerceAtLeast(1)) {
                return LocatedTarget(null, failure(ReplayFailureCode.SCROLL_NO_PROGRESS, step.id, "Scroll did not change the selected screen"))
            }
            previousSignature = currentSignature

            val located = locate(step, window, sink)
            if (located.failure == null && located.node != null) return located
        }
        return LocatedTarget(null, failure(ReplayFailureCode.TARGET_NOT_FOUND_AFTER_SCROLL, step.id, "Target was not found after $maxAttempts scroll attempts"))
    }

    private fun findScrollContainer(step: UiStep, window: WindowSnapshot, sink: TraceSink): NodeSnapshot? {
        val targetSelector = step.element ?: return null

        // 1. Matched container
        val containerSelector = targetSelector.scrollContainer
        if (containerSelector != null) {
            val containerDecision = selectorMatcher.match(
                window,
                containerSelector,
                MatchOptions(isScrollContainer = true, allowGestureFallback = true)
            )
            traceMatch(sink, step.id, containerDecision)
            val matchedNode = (containerDecision as? MatchDecision.Accepted)?.target?.node
            if (matchedNode != null) {
                return matchedNode
            }
        }

        // 2 & 3. Recorded point fallbacks
        val rawPoint = targetSelector.rawPoint
        val p = rawPoint?.pointInWindow
        val hitPoint = if (p != null) {
            IntPoint(
                (window.boundsInScreen.left + p.x * window.boundsInScreen.width).roundToInt(),
                (window.boundsInScreen.top + p.y * window.boundsInScreen.height).roundToInt()
            )
        } else if (rawPoint?.coordinateSpace == CoordinateSpace.WINDOW_NORMALIZED && rawPoint.displayPx != null) {
            rawPoint.displayPx
        } else {
            null
        }

        if (hitPoint != null && window.boundsInScreen.contains(hitPoint.x, hitPoint.y)) {
            // 2. Nearest scrollable ancestor of the node at the step's recorded point
            val hitNode = window.deepestVisibleNodeAt(hitPoint.x, hitPoint.y)
            if (hitNode != null) {
                val scrollableAncestor = window.ancestors(hitNode).firstOrNull { isScrollable(it) }
                    ?: if (isScrollable(hitNode)) hitNode else null
                if (scrollableAncestor != null) {
                    return scrollableAncestor
                }
            }

            // 3. Largest visible scrollable node in the selected window that contains that point
            val largestContaining = window.nodes.values
                .filter { it.visibleToUser && isScrollable(it) && it.boundsInScreen.contains(hitPoint.x, hitPoint.y) }
                .maxWithOrNull(
                    compareBy<NodeSnapshot> { it.boundsInScreen.area }
                        .thenBy { it.nodeId }
                )
            if (largestContaining != null) {
                return largestContaining
            }
        }

        // 4. Fail
        return null
    }

    private fun isScrollable(node: NodeSnapshot): Boolean =
        node.scrollable ||
            NodeAction.SCROLL_FORWARD in node.actions ||
            NodeAction.SCROLL_BACKWARD in node.actions ||
            NodeAction.SCROLL_UP in node.actions ||
            NodeAction.SCROLL_DOWN in node.actions ||
            NodeAction.SCROLL_LEFT in node.actions ||
            NodeAction.SCROLL_RIGHT in node.actions

    private fun WindowSnapshot.deepestVisibleNodeAt(x: Int, y: Int): NodeSnapshot? {
        val root = node(rootId) ?: return null
        val visited = mutableSetOf<Int>()
        fun findDeepest(node: NodeSnapshot): NodeSnapshot? {
            if (!visited.add(node.nodeId) || !node.visibleToUser) return null
            for (childId in node.childIds.asReversed()) {
                val child = this.node(childId) ?: continue
                val deepest = findDeepest(child)
                if (deepest != null) return deepest
            }
            return node.takeIf { it.boundsInScreen.contains(x, y) }
        }
        return findDeepest(root)
    }

    private data class ContainerSignature(
        val nodes: List<NodeContentSignature>
    )

    private data class NodeContentSignature(
        val nodeId: Int,
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
        val bounds: IntRect,
        val collectionRow: Int? = null,
        val collectionColumn: Int? = null
    )

    private fun containerContentSignature(window: WindowSnapshot, container: NodeSnapshot): ContainerSignature {
        val descendants = window.descendants(container)
        val allNodes = listOf(container) + descendants
        val nodeSignatures = allNodes
            .filter { it.visibleToUser }
            .map {
                NodeContentSignature(
                    nodeId = it.nodeId,
                    text = it.text?.trim(),
                    contentDescription = it.contentDescription?.trim(),
                    resourceId = it.resourceId,
                    bounds = it.boundsInScreen,
                    collectionRow = it.collectionRow,
                    collectionColumn = it.collectionColumn
                )
            }
        return ContainerSignature(nodeSignatures)
    }

    private suspend fun act(
        step: UiStep,
        window: WindowSnapshot,
        node: NodeSnapshot,
        sink: TraceSink,
        forceGesture: Boolean
    ): ActionAttempt {
        transition(sink, step.id, ReplayState.ACTING)
        if (cancelled()) return ActionAttempt(false, cancelledFailure(step.id))
        return when (val operation = step.operation) {
            UiOperation.TAP -> clickAttempt(step, window, node, NodeAction.CLICK, forceGesture, sink)
            UiOperation.LONG_PRESS -> clickAttempt(step, window, node, NodeAction.LONG_CLICK, forceGesture, sink)
            UiOperation.SWIPE -> gestureAttempt(step, window, node, step.gesture, sink)
            is UiOperation.SET_TEXT -> setTextAttempt(step, window, node, operation.text, sink)
            is UiOperation.IME_ACTION -> imeAttempt(step, window, node, operation.kind, sink)
            is UiOperation.GLOBAL_ACTION -> {
                val result = dispatch(step, UiActionRequest(operation, null, null, globalAction = operation.kind,
                    dispatchMethod = DispatchMethod.GLOBAL_ACTION), sink)
                ActionAttempt(false, result.toFailure(step.id))
            }
        }
    }

    private suspend fun clickAttempt(step: UiStep, window: WindowSnapshot, node: NodeSnapshot, action: NodeAction,
        forceGesture: Boolean, sink: TraceSink): ActionAttempt {
        if (!forceGesture && action in node.actions) {
            val result = dispatch(step, UiActionRequest(step.operation, window.windowId, node.nodeId,
                semanticAction = action, dispatchMethod = DispatchMethod.SEMANTIC_ACTION), sink)
            if (result is DispatchResult.Accepted) return ActionAttempt(true, null)
        }
        return gestureAttempt(step, window, node, step.gesture, sink)
    }

    private suspend fun gestureAttempt(step: UiStep, window: WindowSnapshot, node: NodeSnapshot,
        recorded: CapturedGesture?, sink: TraceSink): ActionAttempt {
        val fresh = environment.snapshotWindows()
        val currentWindow = fresh.window(window.windowId) ?: return ActionAttempt(false,
            failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "Selected window disappeared before gesture"))
        val currentNode = currentWindow.node(node.nodeId) ?: return ActionAttempt(false,
            failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "Target disappeared before gesture"))
        val points = gesturePoints(step, currentWindow, currentNode, recorded)
        val point = points.firstOrNull() ?: return ActionAttempt(false, failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "Gesture has no point"))
        val hit = fresh.hitTest(point.x, point.y)
        val descendants = currentWindow.descendants(currentNode).map { it.nodeId }.toSet()
        if (hit == null || hit.window.windowId != currentWindow.windowId ||
            (hit.leaf.nodeId != currentNode.nodeId && hit.leaf.nodeId !in descendants)
        ) return ActionAttempt(false, failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "Gesture point no longer hits the recorded target"))
        val result = dispatch(step, UiActionRequest(step.operation, currentWindow.windowId, currentNode.nodeId,
            gesture = recorded ?: tapGesture(points), gesturePoints = points, dispatchMethod = DispatchMethod.GESTURE), sink)
        return ActionAttempt(false, result.toFailure(step.id))
    }

    private suspend fun setTextAttempt(step: UiStep, window: WindowSnapshot, node: NodeSnapshot, text: String,
        sink: TraceSink): ActionAttempt {
        if (!node.editable) return ActionAttempt(false, failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "Target is not editable"))
        val focus = dispatch(step, UiActionRequest(step.operation, window.windowId, node.nodeId,
            semanticAction = NodeAction.FOCUS, dispatchMethod = DispatchMethod.SEMANTIC_ACTION), sink)
        if (focus is DispatchResult.Rejected) return ActionAttempt(false, focus.toFailure(step.id))
        val focused = environment.snapshotWindows().window(window.windowId)?.node(node.nodeId)?.focused == true
        if (!focused) return ActionAttempt(false, failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "Text target did not gain focus"))
        val set = dispatch(step, UiActionRequest(step.operation, window.windowId, node.nodeId, text = text,
            semanticAction = NodeAction.SET_TEXT, dispatchMethod = DispatchMethod.SEMANTIC_ACTION), sink)
        return ActionAttempt(true, set.toFailure(step.id))
    }

    private suspend fun imeAttempt(step: UiStep, window: WindowSnapshot, node: NodeSnapshot, kind: ImeActionKind,
        sink: TraceSink): ActionAttempt {
        val current = environment.snapshotWindows().window(window.windowId)?.node(node.nodeId)
        if (current == null || !current.editable || !current.focused) return ActionAttempt(false,
            failure(ReplayFailureCode.DISPATCH_REJECTED, step.id, "IME action requires the exact editable field to be focused"))
        val result = dispatch(step, UiActionRequest(step.operation, window.windowId, node.nodeId, imeAction = kind,
            semanticAction = NodeAction.IME_ENTER, dispatchMethod = DispatchMethod.SEMANTIC_ACTION), sink)
        return ActionAttempt(true, result.toFailure(step.id))
    }

    private suspend fun verify(step: UiStep, beforeNode: NodeSnapshot, beforeFingerprint: ScreenFingerprint,
        sink: TraceSink): ReplayFailure? {
        if (step.verification.postconditions.isEmpty() && step.verification.allowBestEffort) return null
        val duration = if (step.operation is UiOperation.SET_TEXT) timeouts.textMs else timeouts.postActionMs
        val deadline = clock() + duration
        var lastFailed = "postcondition"
        do {
            if (cancelled()) return cancelledFailure(step.id)
            val snapshot = environment.snapshotWindows()
            val passed = step.verification.postconditions.all { condition ->
                val ok = evaluate(condition, snapshot, step.expectedWindow, beforeNode, beforeFingerprint)
                sink.append(PostconditionRecorded(clock(), step.id, condition.javaClass.simpleName, ok))
                if (!ok) lastFailed = condition.javaClass.simpleName
                ok
            }
            if (passed) return null
            if (clock() >= deadline) break
            when (awaitSignal(minOf(timeouts.safetyPollMs, deadline - clock()))) {
                is AwaitSignal.Cancelled -> return cancelledFailure(step.id)
                is AwaitSignal.Disconnected -> return accessibilityFailure(step.id)
                else -> Unit
            }
        } while (clock() <= deadline)
        return failure(ReplayFailureCode.VERIFICATION_FAILED, step.id, "Postcondition failed: $lastFailed")
    }

    private fun evaluate(condition: VerificationPostcondition, snapshot: WindowSetSnapshot, expectedWindow: WindowSelector,
        beforeNode: NodeSnapshot, beforeFingerprint: ScreenFingerprint): Boolean = when (condition) {
        is VerificationPostcondition.ExpectedScreen -> snapshot.windows.any { fingerprint(it) similarityAtLeast condition.fingerprint }
        is VerificationPostcondition.ExpectedWindow -> windowMatcher.match(snapshot, condition.selector,
            currentActivity(snapshot, condition.selector)).let { it is WindowDecision.Selected }
        is VerificationPostcondition.ExpectedActivity -> snapshot.windows.any { it.packageName == condition.packageName &&
            environment.currentActivityClass(it.windowId) == condition.activityClass }
        is VerificationPostcondition.TargetPresence -> {
            val window = selectedWindow(snapshot, expectedWindow)
            val accepted = window?.let { selectorMatcher.match(it, condition.selector, MatchOptions(allowGestureFallback = true)) is MatchDecision.Accepted } == true
            accepted == condition.shouldBePresent
        }
        is VerificationPostcondition.BooleanStateChanged -> {
            snapshot.window(beforeNode.windowId)?.node(beforeNode.nodeId)?.let { current ->
                val value = when (condition.state) {
                    NodeBooleanState.CHECKED -> current.checked
                    NodeBooleanState.SELECTED -> current.selected
                    NodeBooleanState.EXPANDED -> current.stateDescription == "expanded"
                }
                condition.expected?.let { value == it } ?: (value != booleanState(beforeNode, condition.state))
            } ?: false
        }
        is VerificationPostcondition.RangeValueChanged -> false // Snapshot deliberately has no range value yet.
        is VerificationPostcondition.TextEquals -> snapshot.window(beforeNode.windowId)?.node(beforeNode.nodeId)?.text == condition.value
        is VerificationPostcondition.FocusMoved -> snapshot.windows.any { window ->
            val decision = selectorMatcher.match(window, condition.selector, MatchOptions(isTextTarget = true, allowGestureFallback = true))
            (decision as? MatchDecision.Accepted)?.target?.node?.focused == true
        }
        is VerificationPostcondition.ImeVisibility -> snapshot.windows.any { it.type == WindowType.INPUT_METHOD } == condition.visible
        is VerificationPostcondition.NextTargetConfidence -> snapshot.windows.any { window ->
            val decision = selectorMatcher.match(window, condition.selector, MatchOptions(allowGestureFallback = true))
            (decision as? MatchDecision.Accepted)?.target?.score?.let { it >= condition.minimum } == true
        }
    }

    private fun retryIsSafe(step: UiStep, beforeNode: NodeSnapshot, beforeFingerprint: ScreenFingerprint,
        expectedWindow: WindowSnapshot): Boolean {
        val current = freshSelectedWindow(step.expectedWindow) ?: expectedWindow
        val currentNode = current.node(beforeNode.nodeId) ?: return false
        return fingerprint(current) == beforeFingerprint && currentNode.checked == beforeNode.checked &&
            currentNode.selected == beforeNode.selected
    }

    private suspend fun waitForExpectedWindow(stepId: String, selector: WindowSelector, expected: ScreenFingerprint?,
        timeoutMs: Long, sink: TraceSink, timeoutCode: ReplayFailureCode?): SelectedWait? {
        val deadline = clock() + timeoutMs
        var stableFingerprint: ScreenFingerprint? = null
        var stableAt = 0L
        var quietSince = clock()
        var lastObserved = "no application window"
        while (clock() <= deadline) {
            if (cancelled()) return SelectedWait(null, cancelledFailure(stepId))
            if (!environment.isAccessibilityAvailable()) return SelectedWait(null, accessibilityFailure(stepId))
            val snapshot = environment.snapshotWindows()
            val decision = windowMatcher.match(snapshot, selector, currentActivity(snapshot, selector))
            traceWindow(sink, stepId, decision)
            if (decision is WindowDecision.Selected) {
                val observed = fingerprint(decision.window)
                lastObserved = "package=${decision.window.packageName}, activity=${environment.currentActivityClass(decision.window.windowId) ?: "unknown"}"
                val expectedMatches = expected == null || fingerprinter.similarity(expected, observed) >= 0.80
                if (expectedMatches) {
                    if (stableFingerprint == observed && clock() - stableAt >= timeouts.stableSnapshotGapMs ||
                        clock() - quietSince >= timeouts.settleMs
                    ) return SelectedWait(decision.window, null)
                    if (stableFingerprint != observed) {
                        stableFingerprint = observed
                        stableAt = clock()
                    }
                } else {
                    stableFingerprint = null
                }
            } else {
                lastObserved = decision.summary()
                stableFingerprint = null
            }
            if (clock() >= deadline) break
            when (awaitSignal(minOf(timeouts.safetyPollMs, deadline - clock()))) {
                is AwaitSignal.Cancelled -> return SelectedWait(null, cancelledFailure(stepId))
                is AwaitSignal.Disconnected -> return SelectedWait(null, accessibilityFailure(stepId))
                is AwaitSignal.Event -> quietSince = clock()
                AwaitSignal.Poll -> Unit
            }
        }
        val code = timeoutCode ?: when (val decision = windowMatcher.match(environment.snapshotWindows(), selector,
            currentActivity(environment.snapshotWindows(), selector))) {
            is WindowDecision.Ambiguous -> ReplayFailureCode.WINDOW_AMBIGUOUS
            else -> ReplayFailureCode.WINDOW_NOT_FOUND
        }
        val expectedActivity = selector.observedActivityClass ?: "unknown"
        return SelectedWait(null, failure(
            code,
            stepId,
            "Expected screen did not become selectable; expected package=${selector.packageName}, activity=$expectedActivity; observed $lastObserved"
        ))
    }

    private suspend fun awaitSignal(timeoutMs: Long): AwaitSignal = coroutineScope {
        if (cancelled()) return@coroutineScope AwaitSignal.Cancelled
        if (!environment.isAccessibilityAvailable()) return@coroutineScope AwaitSignal.Disconnected
        val event = async { environment.events().first() }
        val cancellationEvent = if (cancellation === ReplayCancellationSignal.None) null else async { cancellation.events().first() }
        val poll = async { delayer(timeoutMs.coerceAtLeast(0)); Unit }
        try {
            select {
                event.onAwait { uiEvent -> if (uiEvent is UiEnvironmentEvent.AccessibilityDisconnected) AwaitSignal.Disconnected else AwaitSignal.Event }
                if (cancellationEvent != null) cancellationEvent.onAwait { AwaitSignal.Cancelled }
                poll.onAwait { AwaitSignal.Poll }
            }
        } finally {
            event.cancel(); cancellationEvent?.cancel(); poll.cancel()
        }
    }

    private suspend fun dispatch(step: UiStep, request: UiActionRequest, sink: TraceSink): DispatchResult {
        val result = environment.perform(request)
        when (result) {
            is DispatchResult.Accepted -> sink.append(DispatchRecorded(clock(), step.id, result.method.name, true))
            is DispatchResult.Rejected -> sink.append(DispatchRecorded(clock(), step.id, request.dispatchMethod.name, false, result.reason))
        }
        return result
    }

    private fun matchOptions(step: UiStep) = MatchOptions(
        isTextTarget = step.operation is UiOperation.SET_TEXT || step.operation is UiOperation.IME_ACTION,
        allowGestureFallback = step.gesture != null || step.operation == UiOperation.TAP || step.operation == UiOperation.LONG_PRESS
    )

    private fun gesturePoints(step: UiStep, window: WindowSnapshot, node: NodeSnapshot, gesture: CapturedGesture?): List<IntPoint> {
        if (step.operation == UiOperation.SWIPE && gesture != null) return gesture.points.map { timed ->
            timed.pointInWindow?.let { point -> IntPoint(
                (window.boundsInScreen.left + point.x * window.boundsInScreen.width).roundToInt(),
                (window.boundsInScreen.top + point.y * window.boundsInScreen.height).roundToInt()
            ) } ?: timed.displayPx
        }
        val raw = step.element?.rawPoint
        val point = raw?.pointInNode?.let { p -> IntPoint(
            (node.boundsInScreen.left + p.x * node.boundsInScreen.width).roundToInt(),
            (node.boundsInScreen.top + p.y * node.boundsInScreen.height).roundToInt()
        ) } ?: raw?.takeIf { it.coordinateSpace == CoordinateSpace.WINDOW_NORMALIZED }?.pointInWindow?.let { p -> IntPoint(
            (window.boundsInScreen.left + p.x * window.boundsInScreen.width).roundToInt(),
            (window.boundsInScreen.top + p.y * window.boundsInScreen.height).roundToInt()
        ) } ?: center(node.boundsInScreen)
        return listOf(point)
    }

    private fun scrollPoints(bounds: IntRect, direction: RevealDirection): List<IntPoint> {
        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2
        return when (direction) {
            RevealDirection.FORWARD, RevealDirection.DOWN -> {
                val yStart = (bounds.top + bounds.height * 0.75f).roundToInt()
                val yEnd = (bounds.top + bounds.height * 0.25f).roundToInt()
                listOf(IntPoint(cx, yStart), IntPoint(cx, yEnd))
            }
            RevealDirection.BACKWARD, RevealDirection.UP -> {
                val yStart = (bounds.top + bounds.height * 0.25f).roundToInt()
                val yEnd = (bounds.top + bounds.height * 0.75f).roundToInt()
                listOf(IntPoint(cx, yStart), IntPoint(cx, yEnd))
            }
            RevealDirection.RIGHT -> {
                val xStart = (bounds.left + bounds.width * 0.75f).roundToInt()
                val xEnd = (bounds.left + bounds.width * 0.25f).roundToInt()
                listOf(IntPoint(xStart, cy), IntPoint(xEnd, cy))
            }
            RevealDirection.LEFT -> {
                val xStart = (bounds.left + bounds.width * 0.25f).roundToInt()
                val xEnd = (bounds.left + bounds.width * 0.75f).roundToInt()
                listOf(IntPoint(xStart, cy), IntPoint(xEnd, cy))
            }
        }
    }

    private fun scrollGesture(points: List<IntPoint>) = CapturedGesture(GestureKind.SWIPE, 300,
        points.mapIndexed { index, point -> com.shortcuts.app.automation.model.TimedPoint(point, index * 300L) })
    private fun tapGesture(points: List<IntPoint>) = CapturedGesture(GestureKind.TAP, 1,
        points.map { com.shortcuts.app.automation.model.TimedPoint(it, 0) })
    private fun center(bounds: IntRect) = IntPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)

    private fun scrollAction(direction: RevealDirection, node: NodeSnapshot): NodeAction? = when (direction) {
        RevealDirection.FORWARD, RevealDirection.DOWN -> when {
            NodeAction.SCROLL_FORWARD in node.actions -> NodeAction.SCROLL_FORWARD
            NodeAction.SCROLL_DOWN in node.actions -> NodeAction.SCROLL_DOWN
            else -> null
        }
        RevealDirection.BACKWARD, RevealDirection.UP -> when {
            NodeAction.SCROLL_BACKWARD in node.actions -> NodeAction.SCROLL_BACKWARD
            NodeAction.SCROLL_UP in node.actions -> NodeAction.SCROLL_UP
            else -> null
        }
        RevealDirection.LEFT -> NodeAction.SCROLL_LEFT.takeIf { it in node.actions }
        RevealDirection.RIGHT -> NodeAction.SCROLL_RIGHT.takeIf { it in node.actions }
    }

    private fun selectedWindow(snapshot: WindowSetSnapshot, selector: WindowSelector): WindowSnapshot? =
        (windowMatcher.match(snapshot, selector, currentActivity(snapshot, selector)) as? WindowDecision.Selected)?.window
    private fun freshSelectedWindow(selector: WindowSelector): WindowSnapshot? = selectedWindow(environment.snapshotWindows(), selector)
    private fun currentActivity(snapshot: WindowSetSnapshot, selector: WindowSelector): String? = snapshot.windows
        .firstOrNull { it.packageName == selector.packageName }?.let { environment.currentActivityClass(it.windowId) }
    private fun fingerprint(window: WindowSnapshot): ScreenFingerprint = fingerprinter.fingerprint(window, environment.currentActivityClass(window.windowId))
    private infix fun ScreenFingerprint.similarityAtLeast(other: ScreenFingerprint) = fingerprinter.similarity(this, other) >= .80
    private fun booleanState(node: NodeSnapshot, state: NodeBooleanState) = when (state) {
        NodeBooleanState.CHECKED -> node.checked; NodeBooleanState.SELECTED -> node.selected
        NodeBooleanState.EXPANDED -> node.stateDescription == "expanded"
    }
    private fun cancelled() = cancellation.isCancelled()
    private fun cancelledFailure(stepId: String?) = failure(ReplayFailureCode.CANCELLED, stepId, "Replay was cancelled")
    private fun accessibilityFailure(stepId: String) = failure(ReplayFailureCode.ACCESSIBILITY_UNAVAILABLE, stepId, "Accessibility connection was lost")
    private fun failure(code: ReplayFailureCode, stepId: String?, message: String) = ReplayFailure(code, stepId, message)
    private fun transition(sink: TraceSink, stepId: String?, state: ReplayState) { sink.append(StateEntered(clock(), stepId, state)) }
    private fun finishRun(sink: TraceSink, completed: Int, failure: ReplayFailure?): ReplayResult {
        transition(sink, failure?.stepId, if (failure?.code == ReplayFailureCode.CANCELLED) ReplayState.CANCELLED else if (failure == null) ReplayState.FINISHED else ReplayState.FAILED)
        sink.append(TraceEvent.RunFinished(clock(), completed, failure))
        return failure?.let { ReplayResult.Failed(completed, it) } ?: ReplayResult.Success(completed)
    }
    private fun traceWindow(sink: TraceSink, stepId: String, decision: WindowDecision) = when (decision) {
        is WindowDecision.Selected -> sink.append(WindowDecisionRecorded(clock(), stepId, "selected", decision.confidence, decision.window.windowId, decision.breakdown))
        is WindowDecision.Ambiguous -> sink.append(WindowDecisionRecorded(clock(), stepId, "ambiguous", decision.best.confidence, breakdown = decision.best.breakdown))
        is WindowDecision.NotFound -> sink.append(WindowDecisionRecorded(clock(), stepId, "not_found"))
    }
    private fun traceMatch(sink: TraceSink, stepId: String, decision: MatchDecision) = when (decision) {
        is MatchDecision.Accepted -> sink.append(MatchDecisionRecorded(clock(), stepId, "accepted", decision.candidates.size,
            decision.target.score, decision.candidates.getOrNull(1)?.score, decision.target.node.nodeId, decision.target.breakdown))
        is MatchDecision.Ambiguous -> sink.append(MatchDecisionRecorded(clock(), stepId, "ambiguous", decision.candidates.size,
            decision.best.score, decision.second.score, bestBreakdown = decision.best.breakdown))
        is MatchDecision.LowConfidence -> sink.append(MatchDecisionRecorded(clock(), stepId, "low_confidence", decision.candidates.size,
            decision.best?.score, decision.candidates.getOrNull(1)?.score, bestBreakdown = decision.best?.breakdown))
    }
    private fun WindowDecision.summary() = when (this) {
        is WindowDecision.NotFound -> observedSummary
        is WindowDecision.Ambiguous -> "ambiguous package=${best.window.packageName}"
        is WindowDecision.Selected -> "package=${window.packageName}"
    }
    private fun MatchDecision.asLocated(stepId: String): LocatedTarget = when (this) {
        is MatchDecision.Accepted -> LocatedTarget(target.node, null)
        is MatchDecision.Ambiguous -> LocatedTarget(null, failure(ReplayFailureCode.TARGET_AMBIGUOUS, stepId, "Target match is ambiguous"))
        is MatchDecision.LowConfidence -> LocatedTarget(null, failure(ReplayFailureCode.TARGET_LOW_CONFIDENCE, stepId, "Target match is below confidence threshold"))
    }
    private fun DispatchResult.toFailure(stepId: String): ReplayFailure? = when (this) {
        is DispatchResult.Accepted -> null
        is DispatchResult.Rejected -> failure(ReplayFailureCode.DISPATCH_REJECTED, stepId, reason)
    }
    private fun legacyCoordinateWarning(step: Step): String? = (step as? UiStep)?.element?.rawPoint
        ?.takeIf { it.coordinateSpace == CoordinateSpace.DISPLAY_LEGACY }
        ?.let { "DISPLAY_LEGACY coordinates require full-display safety validation by the Android adapter" }

    private data class SelectedWait(val window: WindowSnapshot?, val failure: ReplayFailure?)
    private data class LocatedTarget(val node: NodeSnapshot?, val failure: ReplayFailure?)
    private data class ActionAttempt(val usedSemantic: Boolean, val failure: ReplayFailure?)
    private sealed interface AwaitSignal { data object Poll : AwaitSignal; data object Event : AwaitSignal; data object Cancelled : AwaitSignal; data object Disconnected : AwaitSignal }
}

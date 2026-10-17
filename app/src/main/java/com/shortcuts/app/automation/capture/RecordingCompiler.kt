package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.fingerprint.ScreenFingerprinter
import com.shortcuts.app.automation.core.text.TextNormalizer
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchPolicy
import com.shortcuts.app.automation.model.LaunchTarget
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.NodeBooleanState
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.RevealDirection
import com.shortcuts.app.automation.model.RevealPolicy
import com.shortcuts.app.automation.model.ScreenFingerprint
import com.shortcuts.app.automation.model.Step
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.TextTokenizer
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationPostcondition
import com.shortcuts.app.automation.model.VerificationSpec
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowV2
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

data class RecordingDeviceMetadata(
    val manufacturer: String = "unknown",
    val model: String = "unknown",
    val sdkInt: Int = 0
)

class RecordingCompiler(
    /** Every installed home app counts: phones often have several, and only one is the default. */
    private val launcherPackageNames: Set<String>,
    private val recorderPackageName: String,
    private val screenFingerprinter: ScreenFingerprinter,
    textTokenizer: TextTokenizer,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val deviceMetadata: RecordingDeviceMetadata = RecordingDeviceMetadata(),
    private val systemPackageNames: Set<String> = setOf("com.android.systemui"),
    private val imePackageNames: Set<String> = emptySet(),
    private val gestureAssembler: GestureAssembler = GestureAssembler(),
    private val selectorExtractor: SelectorExtractor = SelectorExtractor(screenFingerprinter, textTokenizer)
) : CaptureEngine {
    private var context: CaptureScreenContext? = null
    private val events = mutableListOf<AcceptedEvent>()
    private var latestSnapshot: WindowSetSnapshot? = null

    override fun start(context: CaptureScreenContext) {
        check(this.context == null) { "A recording is already active" }
        this.context = context
        latestSnapshot = context.initialSnapshot
        events.clear()
    }

    override fun accept(event: CapturedInteraction) = append(AcceptedEvent.Interaction(event))

    override fun accept(event: CapturedTextCommit) = append(AcceptedEvent.TextCommit(event))

    override fun accept(event: CapturedImeAction) = append(AcceptedEvent.ImeAction(event))

    override fun accept(event: CapturedAppSwitch) = append(AcceptedEvent.AppSwitch(event))

    override fun accept(event: CapturedGlobalAction) = append(AcceptedEvent.GlobalAction(event))

    override fun updateScreen(snapshot: WindowSetSnapshot) {
        requireContext()
        latestSnapshot = snapshot
    }

    override fun finish(): WorkflowV2 {
        val captureContext = requireContext()
        val firstSnapshot = captureContext.initialSnapshot
        val finalSnapshot = latestSnapshot ?: firstSnapshot
        val eventContexts = buildEventContexts(firstSnapshot)
        val revealPlan = buildRevealPlan(eventContexts)
        val syntheticClicks = syntheticClicksToDrop()
        val drafts = mutableListOf<Draft>()
        val activities = mutableMapOf<String, String?>()
        var hasLaunch = false
        var currentRealPackage: String? = null
        var homeDetourPackage: String? = null
        var homeActionAt: Long? = null
        var recentsPending = false
        var lastRealInteraction: InteractionCause? = null
        var lastLauncherInteractionAt: Long? = null

        eventContexts.forEachIndexed { index, eventContext ->
            val event = eventContext.event
            if (event is AcceptedEvent.AppSwitch) {
                val obs = event.value.observedActivityClass
                if (isRealActivityClass(obs)) {
                    activities[event.value.toPackageName] = obs
                }
            }
            when (event) {
                is AcceptedEvent.Interaction -> {
                    val packageName = eventContext.packageName
                    if (packageName in launcherPackageNames) {
                        lastLauncherInteractionAt = event.timestamp
                        return@forEachIndexed
                    }
                    if (isIgnoredPackage(packageName)) return@forEachIndexed
                    val window = eventContext.window ?: return@forEachIndexed
                    if (!hasLaunch) {
                        drafts += launchDraft(eventContext, LaunchPolicy.RESET_TO_ROOT, activities[packageName], packageName, eventContexts.subList(index + 1, eventContexts.size))
                        hasLaunch = true
                    }
                    currentRealPackage = packageName
                    if (index in syntheticClicks || index in revealPlan.droppedSwipes) return@forEachIndexed
                    val uiDraft = interactionDraft(
                        eventContext,
                        window,
                        activities[packageName],
                        revealPlan.revealByTarget[index],
                        revealPlan.scrollContainerByTarget[index],
                        revealPlan.standaloneSwipeOverrides[index]
                    )
                    drafts += uiDraft
                    lastRealInteraction = InteractionCause(packageName.orEmpty(), event.value.upUptimeMs)
                }

                is AcceptedEvent.TextCommit -> {
                    val packageName = event.value.windowSnapshot.packageName
                    if (isIgnoredPackage(packageName)) return@forEachIndexed
                    if (!hasLaunch) {
                        drafts += launchDraft(eventContext, LaunchPolicy.RESET_TO_ROOT, activities[packageName], packageName, eventContexts.subList(index + 1, eventContexts.size))
                        hasLaunch = true
                    }
                    currentRealPackage = packageName
                    if (event.value.fieldNode.password) {
                        drafts.asReversed()
                            .filterIsInstance<UiDraft>()
                            .firstOrNull { it.operation == UiOperation.TAP && sameField(it.targetNode, event.value.fieldNode) }
                            ?.warnings
                            ?.add(SENSITIVE_TEXT_WARNING)
                        return@forEachIndexed
                    }

                    val beforeTyping = findBeforeTypingFocus(index, eventContexts, event.value)
                    val beforeField = beforeTyping?.first ?: event.value.fieldNode
                    val beforeWin = beforeTyping?.second ?: event.value.windowSnapshot
                    val beforeSnap = beforeTyping?.third ?: eventContext.snapshot

                    if (isTextCommitNoOpOrLabel(event.value.finalText, event.value.fieldNode, event.value.windowSnapshot, beforeField, beforeWin)) {
                        return@forEachIndexed
                    }

                    val lastDraft = drafts.lastOrNull()
                    if (lastDraft is UiDraft && lastDraft.operation is UiOperation.SET_TEXT && sameField(lastDraft.targetNode, event.value.fieldNode)) {
                        drafts.removeAt(drafts.lastIndex)
                    }

                    drafts += textDraft(eventContext, event.value, activities[packageName], beforeField, beforeWin, beforeSnap)
                }

                is AcceptedEvent.ImeAction -> {
                    val packageName = event.value.windowSnapshot.packageName
                    if (isIgnoredPackage(packageName)) return@forEachIndexed
                    if (!hasLaunch) {
                        drafts += launchDraft(eventContext, LaunchPolicy.RESET_TO_ROOT, activities[packageName], packageName, eventContexts.subList(index + 1, eventContexts.size))
                        hasLaunch = true
                    }
                    currentRealPackage = packageName
                    drafts += imeDraft(eventContext, event.value, activities[packageName])
                }

                is AcceptedEvent.AppSwitch -> {
                    val appSwitch = event.value
                    val toPackage = appSwitch.toPackageName
                    if (toPackage in launcherPackageNames) {
                        if (!isIgnoredPackage(appSwitch.fromPackageName)) {
                            homeDetourPackage = appSwitch.fromPackageName
                        }
                        currentRealPackage = null
                        return@forEachIndexed
                    }
                    if (isIgnoredPackage(toPackage)) {
                        currentRealPackage = null
                        return@forEachIndexed
                    }

                    val causedByInteraction = lastRealInteraction?.let { cause ->
                        cause.packageName == appSwitch.fromPackageName &&
                            appSwitch.switchedAtUptimeMs - cause.atUptimeMs in 0..APP_SWITCH_CAUSE_WINDOW_MS
                    } == true && !recentsPending
                    if (!hasLaunch) {
                        val policy = if (recentsPending) LaunchPolicy.RESUME_EXISTING_TASK else LaunchPolicy.RESET_TO_ROOT
                        drafts += launchDraft(eventContext, policy, activities[toPackage], toPackage, eventContexts.subList(index + 1, eventContexts.size))
                        hasLaunch = true
                    } else if (!causedByInteraction &&
                        (toPackage != currentRealPackage || homeDetourPackage == toPackage)
                    ) {
                        val launchedFromLauncher = appSwitch.fromPackageName in launcherPackageNames
                        val launcherWasTapped = launchedFromLauncher && lastLauncherInteractionAt != null &&
                            (homeActionAt == null || lastLauncherInteractionAt!! >= homeActionAt!!)
                        val policy = when {
                            recentsPending -> LaunchPolicy.RESUME_EXISTING_TASK
                            launchedFromLauncher && homeDetourPackage == toPackage && !launcherWasTapped ->
                                LaunchPolicy.RESUME_EXISTING_TASK
                            launchedFromLauncher -> LaunchPolicy.RESET_TO_ROOT
                            else -> LaunchPolicy.RESUME_EXISTING_TASK
                        }
                        drafts += launchDraft(eventContext, policy, activities[toPackage], toPackage, eventContexts.subList(index + 1, eventContexts.size))
                    }
                    currentRealPackage = toPackage
                    homeDetourPackage = null
                    homeActionAt = null
                    recentsPending = false
                    lastRealInteraction = null
                }

                is AcceptedEvent.GlobalAction -> {
                    val normalizedKind = event.value.kind.uppercase()
                    if (normalizedKind == GLOBAL_RECENTS) recentsPending = true
                    if (normalizedKind == GLOBAL_HOME) {
                        homeDetourPackage = eventContext.packageName ?: currentRealPackage
                        homeActionAt = event.timestamp
                    }
                    lastRealInteraction = null
                    if (isTrailingNavigation(index, eventContexts, finalSnapshot)) return@forEachIndexed
                    val packageName = eventContext.packageName
                    if (!isIgnoredPackage(packageName) && !hasLaunch) {
                        drafts += launchDraft(eventContext, LaunchPolicy.RESET_TO_ROOT, activities[packageName], packageName, eventContexts.subList(index + 1, eventContexts.size))
                        hasLaunch = true
                    }
                    if (!isIgnoredPackage(packageName)) {
                        globalDraft(eventContext, event.value, activities[packageName])?.let(drafts::add)
                    }
                }
                null -> Unit
            }
        }

        if (!hasLaunch) {
            val fallback = primaryWindow(finalSnapshot, null, null)
                ?.takeUnless { isIgnoredPackage(it.packageName) }
                ?: error("A workflow cannot be compiled without a target application")
            val fallbackContext = EventContext(
                event = null,
                snapshot = finalSnapshot,
                window = fallback,
                packageName = fallback.packageName,
                activityClass = activities[fallback.packageName],
                timestamp = finalSnapshot.capturedAtUptimeMs,
                eventIndex = events.size
            )
            drafts += launchDraft(fallbackContext, LaunchPolicy.RESET_TO_ROOT, activities[fallback.packageName], fallback.packageName)
        }

        check(drafts.firstOrNull() is LaunchDraft) { "Every compiled workflow must begin with a launch step" }
        val steps = materialize(drafts, eventContexts, finalSnapshot, activities)
        context = null
        events.clear()
        latestSnapshot = null
        return WorkflowV2(
            workflowId = captureContext.workflowId,
            recordedDevice = RecordedDevice(
                manufacturer = deviceMetadata.manufacturer,
                model = deviceMetadata.model,
                sdkInt = deviceMetadata.sdkInt,
                displayWidthPx = firstSnapshot.displayWidth,
                displayHeightPx = firstSnapshot.displayHeight,
                densityDpi = (firstSnapshot.density * DEFAULT_DENSITY_DPI).roundToInt(),
                rotation = firstSnapshot.rotation
            ),
            steps = steps
        )
    }

    private fun append(event: AcceptedEvent) {
        requireContext()
        events += event
        latestSnapshot = event.snapshotOrNull() ?: latestSnapshot
    }

    private fun requireContext(): CaptureScreenContext = checkNotNull(context) { "Call start() first" }

    private fun buildEventContexts(firstSnapshot: WindowSetSnapshot): List<EventContext> {
        val activities = mutableMapOf<String, String?>()
        return events.mapIndexed { index, event ->
            if (event is AcceptedEvent.AppSwitch) {
                val obs = event.value.observedActivityClass
                if (isRealActivityClass(obs)) {
                    activities[event.value.toPackageName] = obs
                }
            }
            val snapshot = event.snapshot(firstSnapshot)
            val window = when (event) {
                is AcceptedEvent.Interaction -> primaryWindow(
                    snapshot,
                    event.value.hitWindowId?.let(snapshot::window)?.packageName,
                    event.value.hitWindowId
                )
                is AcceptedEvent.TextCommit -> event.value.windowSnapshot
                is AcceptedEvent.ImeAction -> event.value.windowSnapshot
                is AcceptedEvent.AppSwitch -> primaryWindow(snapshot, event.value.toPackageName, null)
                is AcceptedEvent.GlobalAction -> primaryWindow(snapshot, null, null)
            }
            val precomputedFp = when (event) {
                is AcceptedEvent.Interaction -> event.value.precomputedFingerprint
                is AcceptedEvent.TextCommit -> event.value.precomputedFingerprint
                is AcceptedEvent.ImeAction -> event.value.precomputedFingerprint
                is AcceptedEvent.AppSwitch -> event.value.precomputedFingerprint
                is AcceptedEvent.GlobalAction -> event.value.precomputedFingerprint
            }
            EventContext(
                event = event,
                snapshot = snapshot,
                window = window,
                packageName = window?.packageName ?: (event as? AcceptedEvent.AppSwitch)?.value?.toPackageName,
                activityClass = window?.packageName?.let(activities::get),
                timestamp = event.timestamp,
                eventIndex = index,
                precomputedFingerprint = precomputedFp
            )
        }
    }

    /**
     * The launch step must describe a window of the app it launches. An app that is still drawing when
     * its launch is captured has no window in that snapshot, and the event's window can then be another
     * app's (the launcher). Use the launched app's first window seen afterwards instead; only a recording
     * in which the app never shows a window cannot be compiled.
     */
    private fun launchDraft(
        context: EventContext,
        policy: LaunchPolicy,
        activityClass: String?,
        targetPackage: String?,
        laterContexts: List<EventContext> = emptyList()
    ): LaunchDraft {
        val source = sequenceOf(context).plus(laterContexts)
            .firstOrNull { it.window != null && (targetPackage == null || it.window.packageName == targetPackage) }
            ?: context
        val window = source.window?.takeIf { targetPackage == null || it.packageName == targetPackage }
            ?: syntheticApplicationWindow(
                packageName = requireNotNull(targetPackage) { "Launch target has no application package" },
                snapshot = source.snapshot
            )
        return LaunchDraft(
            id = idGenerator(),
            eventIndex = context.eventIndex,
            timestamp = context.timestamp,
            expectedWindow = selectorExtractor.windowSelector(window, source.snapshot, activityClass, source.precomputedFingerprint),
            policy = policy
        )
    }

    private fun syntheticApplicationWindow(
        packageName: String,
        snapshot: WindowSetSnapshot
    ): WindowSnapshot = WindowSnapshot(
        windowId = Int.MIN_VALUE,
        type = WindowType.APPLICATION,
        layer = 0,
        isActive = true,
        isFocused = true,
        title = null,
        packageName = packageName,
        boundsInScreen = IntRect(0, 0, snapshot.displayWidth, snapshot.displayHeight),
        rootId = Int.MIN_VALUE,
        nodes = emptyMap()
    )

    private fun interactionDraft(
        context: EventContext,
        window: WindowSnapshot,
        activityClass: String?,
        reveal: RevealPolicy?,
        scrollContainerOverride: ElementSelector?,
        standaloneSwipeOverride: Pair<NodeSnapshot, ElementSelector>?
    ): UiDraft {
        val interaction = (context.event as AcceptedEvent.Interaction).value
        val operation = when (interaction.kind) {
            GestureKind.TAP -> UiOperation.TAP
            GestureKind.LONG_PRESS -> UiOperation.LONG_PRESS
            GestureKind.SWIPE -> UiOperation.SWIPE
        }
        val target: NodeSnapshot?
        val elementSelector: ElementSelector?
        if (operation == UiOperation.SWIPE && standaloneSwipeOverride != null) {
            target = standaloneSwipeOverride.first
            elementSelector = standaloneSwipeOverride.second
        } else {
            target = interaction.hitNodeId?.let(window::node)
            val extracted = target?.let {
                selectorExtractor.extract(it, window, interaction.points.firstOrNull(), context.snapshot).selector
            }
            elementSelector = if (extracted != null && extracted.scrollContainer == null && scrollContainerOverride != null) {
                extracted.copy(scrollContainer = scrollContainerOverride)
            } else {
                extracted
            }
        }
        return UiDraft(
            id = idGenerator(),
            eventIndex = context.eventIndex,
            timestamp = context.timestamp,
            operation = operation,
            expectedWindow = selectorExtractor.windowSelector(window, context.snapshot, activityClass, context.precomputedFingerprint),
            element = elementSelector,
            gesture = gestureAssembler.assemble(interaction, window),
            reveal = reveal,
            targetNode = target,
            beforeSnapshot = context.snapshot,
            beforeWindow = window,
            safety = safetyFor(elementSelector),
            warnings = mutableListOf()
        )
    }

    private fun textDraft(
        context: EventContext,
        commit: CapturedTextCommit,
        activityClass: String?,
        beforeFieldNode: NodeSnapshot,
        beforeWindow: WindowSnapshot,
        beforeSnapshot: WindowSetSnapshot
    ): UiDraft {
        val selector = selectorExtractor.elementSelector(
            beforeFieldNode,
            beforeWindow,
            null,
            beforeSnapshot
        )
        return UiDraft(
            id = idGenerator(),
            eventIndex = context.eventIndex,
            timestamp = context.timestamp,
            operation = UiOperation.SET_TEXT(commit.finalText),
            expectedWindow = selectorExtractor.windowSelector(beforeWindow, beforeSnapshot, activityClass, context.precomputedFingerprint),
            element = selector,
            gesture = null,
            reveal = null,
            targetNode = beforeFieldNode,
            beforeSnapshot = beforeSnapshot,
            beforeWindow = beforeWindow,
            safety = StepSafety.REVERSIBLE,
            warnings = mutableListOf()
        )
    }

    private fun imeDraft(
        context: EventContext,
        action: CapturedImeAction,
        activityClass: String?
    ): UiDraft {
        val selector = selectorExtractor.elementSelector(
            action.fieldNode,
            action.windowSnapshot,
            null,
            context.snapshot
        )
        return UiDraft(
            id = idGenerator(),
            eventIndex = context.eventIndex,
            timestamp = context.timestamp,
            operation = UiOperation.IME_ACTION(action.kind),
            expectedWindow = selectorExtractor.windowSelector(action.windowSnapshot, context.snapshot, activityClass, context.precomputedFingerprint),
            element = selector,
            gesture = null,
            reveal = null,
            targetNode = action.fieldNode,
            beforeSnapshot = context.snapshot,
            beforeWindow = action.windowSnapshot,
            safety = safetyForIme(action.kind),
            warnings = if (action.inferred) mutableListOf("INFERRED_IME_ACTION") else mutableListOf()
        )
    }

    private fun globalDraft(
        context: EventContext,
        action: CapturedGlobalAction,
        activityClass: String?
    ): UiDraft? {
        val window = context.window ?: return null
        return UiDraft(
            id = idGenerator(),
            eventIndex = context.eventIndex,
            timestamp = context.timestamp,
            operation = UiOperation.GLOBAL_ACTION(action.kind),
            expectedWindow = selectorExtractor.windowSelector(window, context.snapshot, activityClass, context.precomputedFingerprint),
            element = null,
            gesture = null,
            reveal = null,
            targetNode = null,
            beforeSnapshot = context.snapshot,
            beforeWindow = window,
            safety = StepSafety.REVERSIBLE,
            warnings = if (action.inferred) mutableListOf("INFERRED_BACK") else mutableListOf()
        )
    }

    private fun materialize(
        drafts: List<Draft>,
        eventContexts: List<EventContext>,
        finalSnapshot: WindowSetSnapshot,
        activities: Map<String, String?>
    ): List<Step> = drafts.mapIndexed { draftIndex, draft ->
        val previousTimestamp = eventContexts.getOrNull(draft.eventIndex - 1)?.timestamp
        val delay = previousTimestamp?.let { (draft.timestamp - it).coerceAtLeast(0L) }
        val nextEventContext = eventContexts.getOrNull(draft.eventIndex + 1)
        val afterWindow = if (nextEventContext != null) {
            nextEventContext.window
        } else {
            primaryWindow(finalSnapshot, null, null)
        }
        val afterActivity = nextEventContext?.activityClass
            ?: afterWindow?.packageName?.let(activities::get)
        val afterFingerprint = nextEventContext?.precomputedFingerprint ?: afterWindow?.let { screenFingerprinter.fingerprint(it, afterActivity) }

        when (draft) {
            is LaunchDraft -> LaunchAppStep(
                id = draft.id,
                target = LaunchTarget.Launcher(draft.expectedWindow.packageName),
                policy = draft.policy,
                expectedWindow = draft.expectedWindow,
                expectedAfter = draft.expectedWindow.fingerprint,
                capturedDelayFromPreviousMs = delay
            )
            is UiDraft -> {
                val nextUi = drafts.drop(draftIndex + 1).filterIsInstance<UiDraft>().firstOrNull()
                val verification = verificationFor(
                    draft,
                    afterWindow,
                    afterFingerprint,
                    nextUi,
                    draftIndex == drafts.lastIndex
                )
                UiStep(
                    id = draft.id,
                    operation = draft.operation,
                    expectedWindow = draft.expectedWindow,
                    element = draft.element,
                    gesture = draft.gesture,
                    reveal = draft.reveal,
                    verification = verification,
                    safety = draft.safety,
                    expectedBefore = draft.expectedWindow.fingerprint,
                    expectedAfter = afterFingerprint,
                    capturedDelayFromPreviousMs = delay,
                    migrationWarnings = draft.warnings.distinct()
                )
            }
        }
    }

    private fun verificationFor(
        draft: UiDraft,
        afterWindow: WindowSnapshot?,
        afterFingerprint: ScreenFingerprint?,
        nextUi: UiDraft?,
        usesFinalSnapshot: Boolean
    ): VerificationSpec {
        if (draft.operation is UiOperation.SET_TEXT) {
            return VerificationSpec(listOf(VerificationPostcondition.TextEquals(draft.operation.text)))
        }
        val changedState = changedBooleanState(draft, afterWindow)
        if (changedState != null) return VerificationSpec(listOf(changedState))

        val beforeFingerprint = draft.expectedWindow.fingerprint
        val packageChanged = afterWindow != null && afterWindow.packageName != draft.beforeWindow.packageName
        if (packageChanged) {
            val selector = selectorExtractor.windowSelector(
                afterWindow!!,
                afterSnapshotFor(afterWindow, draft.beforeSnapshot),
                afterFingerprint?.activityClass
            )
            return VerificationSpec(listOf(VerificationPostcondition.ExpectedWindow(selector)))
        }
        // The screen after a tap that leads into typing is only captured once the text is committed,
        // so it shows what the typing did (suggestions, filtered results), not what the tap did. Replay
        // checks right after the tap and would never match it; check that the field to type into is there.
        val nextTextField = nextUi?.takeIf { it.operation is UiOperation.SET_TEXT }?.element
        if (nextTextField != null) {
            return VerificationSpec(listOf(VerificationPostcondition.NextTargetConfidence(nextTextField)))
        }
        if (beforeFingerprint != null && afterFingerprint != null &&
            screenFingerprinter.similarity(beforeFingerprint, afterFingerprint) < SCREEN_CHANGE_THRESHOLD
        ) {
            return VerificationSpec(
                postconditions = listOf(VerificationPostcondition.ExpectedScreen(afterFingerprint)),
                allowBestEffort = usesFinalSnapshot
            )
        }
        val nextSelector = nextUi?.element
        if (nextSelector != null) {
            return VerificationSpec(listOf(VerificationPostcondition.NextTargetConfidence(nextSelector)))
        }
        return VerificationSpec(emptyList(), allowBestEffort = true)
    }

    private fun afterSnapshotFor(window: WindowSnapshot, fallback: WindowSetSnapshot): WindowSetSnapshot =
        fallback.copy(windows = listOf(window))

    private fun changedBooleanState(
        draft: UiDraft,
        afterWindow: WindowSnapshot?
    ): VerificationPostcondition.BooleanStateChanged? {
        val before = draft.targetNode ?: return null
        val after = afterWindow?.let { findSameNode(before, it) } ?: return null
        return when {
            before.checked != after.checked -> VerificationPostcondition.BooleanStateChanged(
                NodeBooleanState.CHECKED,
                after.checked
            )
            before.selected != after.selected -> VerificationPostcondition.BooleanStateChanged(
                NodeBooleanState.SELECTED,
                after.selected
            )
            com.shortcuts.app.automation.model.NodeAction.EXPAND in before.actions &&
                com.shortcuts.app.automation.model.NodeAction.COLLAPSE in after.actions ->
                VerificationPostcondition.BooleanStateChanged(NodeBooleanState.EXPANDED, true)
            com.shortcuts.app.automation.model.NodeAction.COLLAPSE in before.actions &&
                com.shortcuts.app.automation.model.NodeAction.EXPAND in after.actions ->
                VerificationPostcondition.BooleanStateChanged(NodeBooleanState.EXPANDED, false)
            else -> null
        }
    }

    private fun isRealActivityClass(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val str = className.trim()
        val simpleName = str.substringAfterLast('.')
        return simpleName.endsWith("Activity")
    }

    private fun sameWindow(a: WindowSnapshot?, b: WindowSnapshot?): Boolean {
        if (a == null || b == null) return false
        return a.packageName == b.packageName && (a.windowId == b.windowId || a.boundsInScreen == b.boundsInScreen)
    }

    private fun findScrollContainerNode(window: WindowSnapshot, hitNode: NodeSnapshot?): NodeSnapshot? {
        if (hitNode != null) {
            if (hitNode.scrollable) return hitNode
            val ancestor = window.ancestors(hitNode).firstOrNull(NodeSnapshot::scrollable)
            if (ancestor != null) return ancestor
        }
        return window.nodes.values.firstOrNull(NodeSnapshot::scrollable)
    }

    private fun findScrollContainerSelector(
        window: WindowSnapshot,
        hitNode: NodeSnapshot?,
        snapshot: WindowSetSnapshot
    ): Pair<NodeSnapshot, ElementSelector>? {
        val containerNode = findScrollContainerNode(window, hitNode) ?: return null
        val selector = selectorExtractor.elementSelector(containerNode, window, null, snapshot)
        return Pair(containerNode, selector)
    }

    private fun dominantSwipeDirection(swipes: List<CapturedInteraction>): RevealDirection {
        var totalDx = 0
        var totalDy = 0
        for (swipe in swipes) {
            val first = swipe.points.firstOrNull()?.displayPx ?: continue
            val last = swipe.points.lastOrNull()?.displayPx ?: continue
            totalDx += (last.x - first.x)
            totalDy += (last.y - first.y)
        }
        return if (abs(totalDy) >= abs(totalDx)) {
            if (totalDy < 0) RevealDirection.FORWARD else RevealDirection.BACKWARD
        } else {
            if (totalDx < 0) RevealDirection.RIGHT else RevealDirection.LEFT
        }
    }

    private fun buildRevealPlan(contexts: List<EventContext>): RevealPlan {
        val dropped = mutableSetOf<Int>()
        val reveals = mutableMapOf<Int, RevealPolicy>()
        val scrollContainers = mutableMapOf<Int, ElementSelector>()
        val standaloneOverrides = mutableMapOf<Int, Pair<NodeSnapshot, ElementSelector>>()

        var index = 0
        while (index < contexts.size) {
            val firstCtx = contexts[index]
            val firstInteraction = (firstCtx.event as? AcceptedEvent.Interaction)?.value
            if (firstInteraction?.kind != GestureKind.SWIPE) {
                index++
                continue
            }

            val swipeIndices = mutableListOf<Int>()
            val swipeWindow = firstCtx.window
            var cursor = index
            while (cursor < contexts.size) {
                val ctx = contexts[cursor]
                val interaction = (ctx.event as? AcceptedEvent.Interaction)?.value ?: break
                if (interaction.kind != GestureKind.SWIPE) break
                if (!sameWindow(swipeWindow, ctx.window)) break
                swipeIndices += cursor
                cursor++
            }

            val nextCtx = contexts.getOrNull(cursor)
            val nextInteraction = (nextCtx?.event as? AcceptedEvent.Interaction)?.value
            val followedByNonSwipeInSameWindow = nextInteraction != null &&
                nextInteraction.kind != GestureKind.SWIPE &&
                sameWindow(swipeWindow, nextCtx.window)

            var containerPair: Pair<NodeSnapshot, ElementSelector>? = null
            for (idx in swipeIndices) {
                val ctx = contexts[idx]
                val w = ctx.window
                val inter = (ctx.event as AcceptedEvent.Interaction).value
                val hit = inter.hitNodeId?.let { w?.node(it) }
                if (w != null) {
                    containerPair = findScrollContainerSelector(w, hit, ctx.snapshot)
                    if (containerPair != null) break
                }
            }

            val swipeInteractions = swipeIndices.map { (contexts[it].event as AcceptedEvent.Interaction).value }
            val dominantDir = dominantSwipeDirection(swipeInteractions)

            if (followedByNonSwipeInSameWindow) {
                dropped += swipeIndices
                reveals[cursor] = RevealPolicy(dominantDir)
                if (containerPair != null) {
                    scrollContainers[cursor] = containerPair.second
                }
            } else {
                if (swipeIndices.size > 1) {
                    dropped += swipeIndices.drop(1)
                }
                if (containerPair != null) {
                    standaloneOverrides[swipeIndices.first()] = containerPair
                }
            }

            index = cursor.coerceAtLeast(index + 1)
        }
        return RevealPlan(dropped, reveals, scrollContainers, standaloneOverrides)
    }

    private fun revealDirection(interaction: CapturedInteraction): RevealDirection? {
        val first = interaction.points.firstOrNull()?.displayPx ?: return null
        val last = interaction.points.lastOrNull()?.displayPx ?: return null
        val dx = last.x - first.x
        val dy = last.y - first.y
        return if (abs(dy) >= abs(dx)) {
            if (dy < 0) RevealDirection.FORWARD else RevealDirection.BACKWARD
        } else {
            if (dx < 0) RevealDirection.RIGHT else RevealDirection.LEFT
        }
    }

    // TODO: Switch to shared core/tree/NodeLabels.kt when available
    private fun isTextCommitNoOpOrLabel(
        text: String,
        fieldNode: NodeSnapshot,
        window: WindowSnapshot,
        beforeFieldNode: NodeSnapshot?,
        beforeWindow: WindowSnapshot?
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        val candidateLabels = mutableSetOf<String>()
        fun collectLabels(field: NodeSnapshot?, win: WindowSnapshot?) {
            if (field == null) return
            field.hintText?.trim()?.takeIf { it.isNotEmpty() }?.let { candidateLabels += it }
            val w = win ?: return
            for (childId in field.childIds) {
                val child = w.node(childId) ?: continue
                child.text?.trim()?.takeIf { it.isNotEmpty() }?.let { candidateLabels += it }
                child.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { candidateLabels += it }
            }
            for (descendant in w.descendants(field)) {
                descendant.text?.trim()?.takeIf { it.isNotEmpty() }?.let { candidateLabels += it }
                descendant.contentDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { candidateLabels += it }
            }
        }

        collectLabels(fieldNode, window)
        collectLabels(beforeFieldNode, beforeWindow)

        return candidateLabels.any { label ->
            label.equals(trimmed, ignoreCase = false) ||
                TextNormalizer.normalize(label) == TextNormalizer.normalize(trimmed)
        }
    }

    private fun findBeforeTypingFocus(
        commitIndex: Int,
        contexts: List<EventContext>,
        commit: CapturedTextCommit
    ): Triple<NodeSnapshot, WindowSnapshot, WindowSetSnapshot>? {
        for (i in (commitIndex - 1) downTo 0) {
            val ctx = contexts[i]
            val interaction = (ctx.event as? AcceptedEvent.Interaction)?.value ?: continue
            if (interaction.kind != GestureKind.TAP && interaction.kind != GestureKind.LONG_PRESS) continue

            val snap = interaction.beforeSnapshot
            val win = interaction.hitWindowId?.let(snap::window) ?: ctx.window ?: continue
            val hitNode = interaction.hitNodeId?.let(win::node)

            val matchedNode = findMatchingFieldNode(hitNode, win, commit.fieldNode)
            if (matchedNode != null) {
                return Triple(matchedNode, win, snap)
            }
        }
        return null
    }

    private fun findMatchingFieldNode(
        hitNode: NodeSnapshot?,
        window: WindowSnapshot,
        commitFieldNode: NodeSnapshot
    ): NodeSnapshot? {
        if (hitNode != null) {
            if (sameField(hitNode, commitFieldNode)) return hitNode
            if (hitNode.editable && hitNode.boundsInScreen == commitFieldNode.boundsInScreen) return hitNode
            val ancestor = window.ancestors(hitNode).firstOrNull {
                sameField(it, commitFieldNode) || (it.editable && it.boundsInScreen == commitFieldNode.boundsInScreen)
            }
            if (ancestor != null) return ancestor
            val descendant = window.descendants(hitNode).firstOrNull {
                sameField(it, commitFieldNode) || (it.editable && it.boundsInScreen == commitFieldNode.boundsInScreen)
            }
            if (descendant != null) return descendant
        }
        return window.nodes.values.firstOrNull {
            sameField(it, commitFieldNode) || (it.editable && it.boundsInScreen == commitFieldNode.boundsInScreen)
        } ?: window.nodes.values.firstOrNull {
            it.boundsInScreen == commitFieldNode.boundsInScreen
        }
    }

    private fun syntheticClicksToDrop(): Set<Int> = buildSet {
        events.windowed(2).forEachIndexed { index, pair ->
            val longPress = pair[0] as? AcceptedEvent.Interaction ?: return@forEachIndexed
            val click = pair[1] as? AcceptedEvent.Interaction ?: return@forEachIndexed
            if (longPress.value.kind == GestureKind.LONG_PRESS && click.value.kind == GestureKind.TAP &&
                samePhysicalGesture(longPress.value, click.value)
            ) add(index + 1)
        }
    }

    private fun samePhysicalGesture(a: CapturedInteraction, b: CapturedInteraction): Boolean {
        if (a.hitWindowId != b.hitWindowId || a.hitNodeId != b.hitNodeId) return false
        if (a.downUptimeMs == b.downUptimeMs) return true
        val aPoint = a.points.firstOrNull()?.displayPx ?: return false
        val bPoint = b.points.firstOrNull()?.displayPx ?: return false
        return b.downUptimeMs <= a.upUptimeMs && b.upUptimeMs - a.upUptimeMs in 0..SYNTHETIC_CLICK_WINDOW_MS &&
            abs(aPoint.x - bPoint.x) <= SAME_GESTURE_SLOP_PX &&
            abs(aPoint.y - bPoint.y) <= SAME_GESTURE_SLOP_PX
    }

    private fun isTrailingNavigation(
        index: Int,
        contexts: List<EventContext>,
        finalSnapshot: WindowSetSnapshot
    ): Boolean {
        val event = contexts[index].event as? AcceptedEvent.GlobalAction ?: return false
        val kind = event.value.kind.uppercase()
        if (kind != GLOBAL_HOME && kind != GLOBAL_BACK) return false
        val laterRealEvent = contexts.drop(index + 1).any { context ->
            when (val later = context.event) {
                is AcceptedEvent.AppSwitch -> !isIgnoredPackage(later.value.toPackageName)
                is AcceptedEvent.Interaction -> !isIgnoredPackage(context.packageName)
                is AcceptedEvent.TextCommit, is AcceptedEvent.ImeAction -> !isIgnoredPackage(context.packageName)
                is AcceptedEvent.GlobalAction, null -> false
            }
        }
        if (laterRealEvent) return false
        if (kind == GLOBAL_HOME) return true
        val sourcePackage = contexts[index].packageName
        val finalPackage = primaryWindow(finalSnapshot, null, null)?.packageName
        return sourcePackage != finalPackage
    }

    private fun primaryWindow(
        snapshot: WindowSetSnapshot,
        preferredPackage: String?,
        preferredWindowId: Int?
    ): WindowSnapshot? {
        preferredWindowId?.let(snapshot::window)?.let { return it }
        val eligible = snapshot.windows.asSequence()
            .filterNot { it.type == WindowType.ACCESSIBILITY_OVERLAY || it.type == WindowType.INPUT_METHOD }
        val sortedEligible = eligible
            .sortedWith(compareByDescending<WindowSnapshot> { it.isActive || it.isFocused }.thenByDescending { it.layer })
        if (preferredPackage != null) {
            return sortedEligible.firstOrNull { it.packageName == preferredPackage }
        }
        return sortedEligible.firstOrNull()
    }

    private fun isIgnoredPackage(packageName: String?): Boolean = packageName == null ||
        packageName in launcherPackageNames || packageName == recorderPackageName ||
        packageName in systemPackageNames || packageName in imePackageNames

    /**
     * A tap is irreversible when its label or its resource id names an outward-facing or
     * destructive verb. A tap with no label and no id cannot be judged, so it is BEST_EFFORT
     * (never treated as safe to repeat). Replay never re-dispatches an accepted action anyway;
     * this classification drives the run-time confirmation prompt.
     */
    private fun safetyFor(selector: ElementSelector?): StepSafety {
        val strategies = selector?.strategies.orEmpty()
        val labels = strategies.filter {
            it.kind == LocatorKind.TEXT_EXACT || it.kind == LocatorKind.CONTENT_DESCRIPTION_EXACT
        }.map { it.value }
        val idWords = strategies.filter {
            it.kind == LocatorKind.RESOURCE_ID_EXACT || it.kind == LocatorKind.TEST_TAG_EXACT
        }.flatMap { idWordsOf(it.value) }
        return when {
            labels.any { DESTRUCTIVE_LABEL.containsMatchIn(it) } -> StepSafety.IRREVERSIBLE
            idWords.any { it in DESTRUCTIVE_ID_WORDS } -> StepSafety.IRREVERSIBLE
            labels.isEmpty() && idWords.isEmpty() -> StepSafety.BEST_EFFORT
            else -> StepSafety.REVERSIBLE
        }
    }

    /** Submitting a search or moving to the next field is harmless; sending or submitting is not. */
    private fun safetyForIme(kind: ImeActionKind): StepSafety = when (kind) {
        ImeActionKind.SEARCH, ImeActionKind.NEXT -> StepSafety.REVERSIBLE
        ImeActionKind.SEND, ImeActionKind.GO, ImeActionKind.DONE, ImeActionKind.UNSPECIFIED -> StepSafety.IRREVERSIBLE
    }

    /** "com.app:id/btnSendMessage" → [btn, send, message]. */
    private fun idWordsOf(resourceId: String): List<String> =
        resourceId.substringAfterLast('/')
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }

    private fun isNodeVisible(target: NodeSnapshot, snapshot: WindowSetSnapshot): Boolean =
        snapshot.windows.asSequence()
            .filter { it.packageName == target.packageName }
            .flatMap { it.nodes.values.asSequence() }
            .any { candidate -> candidate.visibleToUser && sameNodeIdentity(target, candidate) }

    private fun findSameNode(target: NodeSnapshot, window: WindowSnapshot): NodeSnapshot? =
        window.node(target.nodeId)?.takeIf { sameNodeIdentity(target, it) }
            ?: window.nodes.values.firstOrNull { sameNodeIdentity(target, it) }

    private fun sameNodeIdentity(a: NodeSnapshot, b: NodeSnapshot): Boolean {
        if (a.packageName != b.packageName || a.className != b.className) return false
        val aId = TextNormalizer.normalize(a.resourceId)
        val bId = TextNormalizer.normalize(b.resourceId)
        if (aId != null || bId != null) return aId == bId
        return a.nodeId == b.nodeId
    }

    private fun sameField(a: NodeSnapshot?, b: NodeSnapshot?): Boolean {
        if (a == null || b == null) return false
        if (a.packageName != b.packageName || a.className != b.className) return false
        val aId = TextNormalizer.normalize(a.resourceId)
        val bId = TextNormalizer.normalize(b.resourceId)
        if (aId != null || bId != null) return aId == bId
        if (a.nodeId == b.nodeId) return true
        return a.boundsInScreen == b.boundsInScreen
    }

    private sealed interface AcceptedEvent {
        val timestamp: Long

        data class Interaction(val value: CapturedInteraction) : AcceptedEvent {
            override val timestamp: Long = value.downUptimeMs
        }

        data class TextCommit(val value: CapturedTextCommit) : AcceptedEvent {
            override val timestamp: Long = value.committedAtUptimeMs
        }

        data class ImeAction(val value: CapturedImeAction) : AcceptedEvent {
            override val timestamp: Long = value.actionAtUptimeMs
        }

        data class AppSwitch(val value: CapturedAppSwitch) : AcceptedEvent {
            override val timestamp: Long = value.switchedAtUptimeMs
        }

        data class GlobalAction(val value: CapturedGlobalAction) : AcceptedEvent {
            override val timestamp: Long = value.actionAtUptimeMs
        }

        fun snapshot(first: WindowSetSnapshot): WindowSetSnapshot = when (this) {
            is Interaction -> value.beforeSnapshot
            is TextCommit -> first.copy(
                capturedAtUptimeMs = value.committedAtUptimeMs,
                windows = listOf(value.windowSnapshot)
            )
            is ImeAction -> first.copy(
                capturedAtUptimeMs = value.actionAtUptimeMs,
                windows = listOf(value.windowSnapshot)
            )
            is AppSwitch -> value.snapshot
            is GlobalAction -> value.snapshot
        }

        fun snapshotOrNull(): WindowSetSnapshot? = when (this) {
            is Interaction -> value.beforeSnapshot
            is AppSwitch -> value.snapshot
            is GlobalAction -> value.snapshot
            is TextCommit, is ImeAction -> null
        }
    }

    private data class EventContext(
        val event: AcceptedEvent?,
        val snapshot: WindowSetSnapshot,
        val window: WindowSnapshot?,
        val packageName: String?,
        val activityClass: String?,
        val timestamp: Long,
        val eventIndex: Int,
        val precomputedFingerprint: ScreenFingerprint? = null
    )

    private sealed interface Draft {
        val id: String
        val eventIndex: Int
        val timestamp: Long
    }

    private data class LaunchDraft(
        override val id: String,
        override val eventIndex: Int,
        override val timestamp: Long,
        val expectedWindow: WindowSelector,
        val policy: LaunchPolicy
    ) : Draft

    private data class UiDraft(
        override val id: String,
        override val eventIndex: Int,
        override val timestamp: Long,
        val operation: UiOperation,
        val expectedWindow: WindowSelector,
        val element: ElementSelector?,
        val gesture: CapturedGesture?,
        val reveal: RevealPolicy?,
        val targetNode: NodeSnapshot?,
        val beforeSnapshot: WindowSetSnapshot,
        val beforeWindow: WindowSnapshot,
        val safety: StepSafety,
        val warnings: MutableList<String>
    ) : Draft

    private data class InteractionCause(val packageName: String, val atUptimeMs: Long)
    private data class RevealPlan(
        val droppedSwipes: Set<Int>,
        val revealByTarget: Map<Int, RevealPolicy>,
        val scrollContainerByTarget: Map<Int, ElementSelector>,
        val standaloneSwipeOverrides: Map<Int, Pair<NodeSnapshot, ElementSelector>>
    )

    companion object {
        const val SENSITIVE_TEXT_WARNING = "SENSITIVE_TEXT_NOT_RECORDED"
        private const val APP_SWITCH_CAUSE_WINDOW_MS = 2_000L
        private const val SYNTHETIC_CLICK_WINDOW_MS = 250L
        private const val SAME_GESTURE_SLOP_PX = 8
        private const val SCREEN_CHANGE_THRESHOLD = 0.80
        private const val DEFAULT_DENSITY_DPI = 160f
        private const val GLOBAL_BACK = "BACK"
        private const val GLOBAL_HOME = "HOME"
        private const val GLOBAL_RECENTS = "RECENTS"
        private val DESTRUCTIVE_LABEL = Regex(
            "\\b(?:delete|remove|erase|discard|trash|send|reply|forward|share|pay|payment|purchase|buy|" +
                "order|checkout|check out|transfer|withdraw|donate|charge|subscribe|unsubscribe|book|reserve|" +
                "post|publish|tweet|upload|submit|confirm|approve|accept|sign|call|dial|block|report|unfriend|" +
                "unfollow|cancel subscription|place order|wipe|reset|factory reset|uninstall)\\b",
            RegexOption.IGNORE_CASE
        )
        private val DESTRUCTIVE_ID_WORDS = setOf(
            "delete", "remove", "erase", "discard", "trash", "send", "reply", "forward", "share", "pay",
            "payment", "purchase", "buy", "order", "checkout", "transfer", "withdraw", "donate", "charge",
            "subscribe", "unsubscribe", "book", "reserve", "post", "publish", "upload", "submit", "confirm",
            "approve", "accept", "sign", "call", "dial", "block", "report", "unfollow", "wipe", "uninstall"
        )
    }
}

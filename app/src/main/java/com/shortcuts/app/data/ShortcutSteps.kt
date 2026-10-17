package com.shortcuts.app.data

import com.shortcuts.app.automation.model.DecodeResult
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchTarget
import com.shortcuts.app.automation.model.LegacyActionStep
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.Step
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.WaitStep
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import java.util.UUID

/**
 * Representation of a decoded shortcut document for display and editing.
 */
sealed interface ShortcutDocument {
    val actions: List<Action>

    data class Legacy(
        override val actions: List<Action>,
        val rawJson: String = ""
    ) : ShortcutDocument

    data class V2(
        val workflow: WorkflowV2,
        override val actions: List<Action>,
        val rawJson: String = ""
    ) : ShortcutDocument

    data class Unreadable(
        val reason: String
    ) : ShortcutDocument {
        override val actions: List<Action> get() = emptyList()
    }
}

/**
 * The single gateway for decoding and encoding shortcut actions between persisted JSON
 * (legacy array or Engine v2 object) and the editor/UI's List<Action> projection.
 */
object ShortcutSteps {

    /**
     * Decodes [actionsJson] into a [ShortcutDocument].
     * Supports legacy JSON arrays and Engine v2 workflow objects.
     */
    fun decode(actionsJson: String): ShortcutDocument {
        if (actionsJson.isBlank()) {
            return ShortcutDocument.Unreadable("Shortcut JSON is blank")
        }

        val codec = WorkflowCodec()
        return when (val result = codec.decode(actionsJson)) {
            is DecodeResult.Success -> {
                if (result.migratedFromLegacy) {
                    val actions = ActionConverter().toActionList(actionsJson)
                    ShortcutDocument.Legacy(actions, rawJson = actionsJson)
                } else {
                    val projectedActions = projectWorkflow(result.workflow)
                    ShortcutDocument.V2(result.workflow, projectedActions, rawJson = actionsJson)
                }
            }
            is DecodeResult.Failure -> {
                ShortcutDocument.Unreadable(result.message)
            }
        }
    }

    /**
     * Re-encodes [editedActions] back into JSON matching the schema of [original].
     *
     * - Legacy documents remain legacy JSON arrays serialized via [ActionConverter].
     * - Engine v2 documents rebuild [WorkflowV2.steps] in the edited order:
     *   - Steps with matching [Action.v2StepId] remain byte-identical (or updated for editor changes).
     *   - If an action has [Action.delayMillis] set, a [WaitStep] is inserted after it.
     *   - Removed steps are dropped.
     *   - Added actions (no v2StepId) are wrapped as [LegacyActionStep].
     * - Throws [IllegalArgumentException] if [original] is [ShortcutDocument.Unreadable].
     */
    fun encode(original: ShortcutDocument, editedActions: List<Action>): String {
        return when (original) {
            is ShortcutDocument.Legacy -> {
                ActionConverter().fromActionList(editedActions)
            }
            is ShortcutDocument.V2 -> {
                if (original.rawJson.isNotBlank() && editedActions == original.actions) {
                    return original.rawJson
                }
                encodeV2(original, editedActions)
            }
            is ShortcutDocument.Unreadable -> {
                throw IllegalArgumentException("Cannot encode unreadable shortcut document: ${original.reason}")
            }
        }
    }

    /**
     * Returns the number of steps in [actionsJson], correctly handling both legacy arrays
     * and Engine v2 objects without failing on unreadable strings.
     */
    fun stepCount(actionsJson: String?): Int {
        if (actionsJson.isNullOrBlank()) return 0
        return when (val doc = decode(actionsJson)) {
            is ShortcutDocument.Legacy -> doc.actions.size
            is ShortcutDocument.V2 -> doc.actions.size
            is ShortcutDocument.Unreadable -> 0
        }
    }

    internal fun projectWorkflow(workflow: WorkflowV2): List<Action> {
        return workflow.steps.map { projectStep(it) }
    }

    internal fun projectStep(step: Step): Action {
        return when (step) {
            is LaunchAppStep -> {
                val pkg = when (val target = step.target) {
                    is LaunchTarget.Launcher -> target.packageName
                    is LaunchTarget.Component -> target.packageName
                    is LaunchTarget.DeepLink -> target.packageName
                }.ifBlank { step.expectedWindow.packageName }

                Action(
                    actionType = ActionType.APP_INTENT,
                    packageName = pkg,
                    targetPackageName = step.expectedWindow.packageName.ifBlank { pkg },
                    v2StepId = step.id
                )
            }
            is UiStep -> {
                val element = step.element
                val text = element?.strategies?.firstOrNull { it.kind == LocatorKind.TEXT_EXACT }?.value
                val contentDesc = element?.strategies?.firstOrNull { it.kind == LocatorKind.CONTENT_DESCRIPTION_EXACT }?.value
                val resId = element?.strategies?.firstOrNull { it.kind == LocatorKind.RESOURCE_ID_EXACT }?.value
                val targetName = text ?: contentDesc ?: resId

                val pkg = step.expectedWindow.packageName
                val className = element?.expectedClass
                val screenX = element?.rawPoint?.displayPx?.x
                val screenY = element?.rawPoint?.displayPx?.y

                val isInferredIme = step.migrationWarnings.contains("INFERRED_IME_ACTION")
                if (isInferredIme) {
                    return Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "PRESS_ENTER",
                        target = "Pressed the keyboard's Search/Send key (detected)",
                        targetText = "Pressed the keyboard's Search/Send key (detected)",
                        targetContentDescription = contentDesc,
                        targetNodeId = resId,
                        targetClassName = className,
                        targetPackageName = pkg,
                        v2StepId = step.id
                    )
                }

                when (val op = step.operation) {
                    UiOperation.TAP -> Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "TAP",
                        target = targetName,
                        targetText = text,
                        targetContentDescription = contentDesc,
                        targetNodeId = resId,
                        targetClassName = className,
                        targetPackageName = pkg,
                        screenX = screenX,
                        screenY = screenY,
                        v2StepId = step.id
                    )
                    UiOperation.LONG_PRESS -> Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "LONG_PRESS",
                        target = targetName,
                        targetText = text,
                        targetContentDescription = contentDesc,
                        targetNodeId = resId,
                        targetClassName = className,
                        targetPackageName = pkg,
                        screenX = screenX,
                        screenY = screenY,
                        v2StepId = step.id
                    )
                    is UiOperation.SET_TEXT -> Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "TYPE_TEXT",
                        textInput = op.text,
                        target = targetName,
                        targetText = text,
                        targetContentDescription = contentDesc,
                        targetNodeId = resId,
                        targetClassName = className,
                        targetPackageName = pkg,
                        v2StepId = step.id
                    )
                    is UiOperation.IME_ACTION -> Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "PRESS_ENTER",
                        target = targetName,
                        targetText = text,
                        targetContentDescription = contentDesc,
                        targetNodeId = resId,
                        targetClassName = className,
                        targetPackageName = pkg,
                        v2StepId = step.id
                    )
                    UiOperation.SWIPE -> {
                        val dir = determineSwipeDirection(step)
                        Action(
                            actionType = ActionType.UI_AUTOMATION,
                            uiActionType = "SCROLL",
                            scrollDirection = dir,
                            target = targetName,
                            targetText = text,
                            targetContentDescription = contentDesc,
                            targetNodeId = resId,
                            targetClassName = className,
                            targetPackageName = pkg,
                            v2StepId = step.id
                        )
                    }
                    is UiOperation.GLOBAL_ACTION -> Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "GLOBAL_ACTION",
                        globalAction = op.kind,
                        targetPackageName = pkg,
                        v2StepId = step.id
                    )
                }
            }
            is WaitStep -> Action(
                actionType = ActionType.WAIT,
                delayMillis = step.durationMs,
                v2StepId = step.id
            )
            is LegacyActionStep -> {
                step.action.copy(v2StepId = step.id)
            }
        }
    }

    private fun determineSwipeDirection(step: UiStep): String {
        val gesture = step.gesture
        if (gesture != null && gesture.points.size >= 2) {
            val start = gesture.points.first()
            val end = gesture.points.last()
            val dx = if (start.pointInWindow != null && end.pointInWindow != null) {
                end.pointInWindow.x - start.pointInWindow.x
            } else {
                (end.displayPx.x - start.displayPx.x).toFloat()
            }
            val dy = if (start.pointInWindow != null && end.pointInWindow != null) {
                end.pointInWindow.y - start.pointInWindow.y
            } else {
                (end.displayPx.y - start.displayPx.y).toFloat()
            }
            if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                return if (dx > 0) "RIGHT" else "LEFT"
            } else if (kotlin.math.abs(dy) > 0) {
                return if (dy > 0) "DOWN" else "UP"
            }
        }
        return step.reveal?.direction?.name ?: "DOWN"
    }

    private fun encodeV2(original: ShortcutDocument.V2, editedActions: List<Action>): String {
        val originalStepsById = original.workflow.steps.associateBy { it.id }
        val usedStepIds = mutableSetOf<String>()
        val newSteps = mutableListOf<Step>()

        for (action in editedActions) {
            val stepId = action.v2StepId
            if (stepId != null) {
                if (usedStepIds.contains(stepId)) {
                    throw IllegalArgumentException("Duplicate v2StepId in edited actions: $stepId")
                }
                val originalStep = originalStepsById[stepId]
                    ?: throw IllegalArgumentException("Unknown v2StepId in edited actions: $stepId")
                usedStepIds.add(stepId)

                val updatedStep = updateV2Step(originalStep, action)
                newSteps.add(updatedStep)

                if (originalStep !is WaitStep && action.delayMillis != null && action.delayMillis > 0) {
                    newSteps.add(
                        WaitStep(
                            id = UUID.randomUUID().toString(),
                            durationMs = action.delayMillis,
                            timeoutMs = action.delayMillis
                        )
                    )
                }
            } else {
                newSteps.add(
                    LegacyActionStep(
                        id = UUID.randomUUID().toString(),
                        action = action.copy(v2StepId = null)
                    )
                )
            }
        }

        val newWorkflow = original.workflow.copy(steps = newSteps)
        return WorkflowCodec().encode(newWorkflow)
    }

    private fun updateV2Step(originalStep: Step, action: Action): Step {
        if (action.continueOnError) {
            throw IllegalArgumentException("continueOnError has no Engine v2 equivalent and is not supported for recorded step ${originalStep.id}")
        }

        return when (originalStep) {
            is LaunchAppStep -> {
                if (action.actionType != ActionType.APP_INTENT) {
                    throw IllegalArgumentException("Cannot change actionType of recorded LaunchAppStep ${originalStep.id} to ${action.actionType}")
                }
                if (!action.uiActionType.isNullOrBlank() || !action.targetText.isNullOrBlank() ||
                    !action.targetNodeId.isNullOrBlank() || !action.targetContentDescription.isNullOrBlank() ||
                    !action.targetClassName.isNullOrBlank() || action.screenX != null || action.screenY != null
                ) {
                    throw IllegalArgumentException("Cannot set UI automation properties on recorded LaunchAppStep ${originalStep.id}")
                }
                val newPkg = action.packageName?.trim().orEmpty()
                if (newPkg.isBlank()) {
                    throw IllegalArgumentException("Package name cannot be blank for LaunchAppStep ${originalStep.id}")
                }
                originalStep.copy(
                    target = LaunchTarget.Launcher(newPkg),
                    expectedWindow = originalStep.expectedWindow.copy(packageName = newPkg)
                )
            }
            is WaitStep -> {
                if (action.actionType != ActionType.WAIT) {
                    throw IllegalArgumentException("Cannot change actionType of recorded WaitStep ${originalStep.id} to ${action.actionType}")
                }
                if (!action.packageName.isNullOrBlank() || !action.uiActionType.isNullOrBlank() ||
                    !action.targetText.isNullOrBlank() || !action.targetNodeId.isNullOrBlank()
                ) {
                    throw IllegalArgumentException("Cannot set non-wait properties on recorded WaitStep ${originalStep.id}")
                }
                val duration = action.delayMillis ?: originalStep.durationMs
                if (duration <= 0) {
                    throw IllegalArgumentException("Wait duration must be positive for WaitStep ${originalStep.id}")
                }
                originalStep.copy(
                    durationMs = duration,
                    timeoutMs = duration
                )
            }
            is UiStep -> {
                if (action.actionType != ActionType.UI_AUTOMATION) {
                    throw IllegalArgumentException("Cannot change actionType of recorded UiStep ${originalStep.id} to ${action.actionType}")
                }
                val projected = projectStep(originalStep)

                val editedUiType = action.uiActionType?.trim()?.uppercase()
                val projectedUiType = projected.uiActionType?.trim()?.uppercase()
                if (editedUiType != null && editedUiType != projectedUiType) {
                    throw IllegalArgumentException("Cannot change uiActionType of recorded UiStep ${originalStep.id} from $projectedUiType to $editedUiType")
                }

                if (action.targetText != projected.targetText ||
                    action.targetContentDescription != projected.targetContentDescription ||
                    action.targetNodeId != projected.targetNodeId ||
                    action.targetClassName != projected.targetClassName ||
                    action.targetPackageName != projected.targetPackageName ||
                    action.screenX != projected.screenX ||
                    action.screenY != projected.screenY ||
                    action.preferCoordinateReplay != projected.preferCoordinateReplay ||
                    action.target != projected.target
                ) {
                    throw IllegalArgumentException("Target element and selectors are read-only for recorded UiStep ${originalStep.id}")
                }

                if (originalStep.operation is UiOperation.SET_TEXT) {
                    val newText = action.textInput ?: ""
                    originalStep.copy(operation = UiOperation.SET_TEXT(newText))
                } else {
                    if (action.textInput != null && action.textInput != projected.textInput) {
                        throw IllegalArgumentException("Cannot set textInput on non-SET_TEXT UiStep ${originalStep.id}")
                    }
                    originalStep
                }
            }
            is LegacyActionStep -> {
                originalStep.copy(action = action.copy(v2StepId = null))
            }
        }
    }
}

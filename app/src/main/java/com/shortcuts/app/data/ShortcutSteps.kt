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

    private fun projectWorkflow(workflow: WorkflowV2): List<Action> {
        return workflow.steps.map { projectStep(it) }
    }

    private fun projectStep(step: Step): Action {
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
            val originalStep = if (stepId != null && !usedStepIds.contains(stepId)) {
                originalStepsById[stepId]
            } else {
                null
            }

            if (originalStep != null) {
                usedStepIds.add(originalStep.id)
                // Note: continueOnError has no v2 equivalent — keeping original step structure
                if (originalStep is WaitStep) {
                    val updatedStep = if (action.delayMillis != null && action.delayMillis != originalStep.durationMs) {
                        originalStep.copy(durationMs = action.delayMillis, timeoutMs = action.delayMillis)
                    } else {
                        originalStep
                    }
                    newSteps.add(updatedStep)
                } else {
                    newSteps.add(originalStep)
                    if (action.delayMillis != null && action.delayMillis > 0) {
                        newSteps.add(
                            WaitStep(
                                id = UUID.randomUUID().toString(),
                                durationMs = action.delayMillis,
                                timeoutMs = action.delayMillis
                            )
                        )
                    }
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
}

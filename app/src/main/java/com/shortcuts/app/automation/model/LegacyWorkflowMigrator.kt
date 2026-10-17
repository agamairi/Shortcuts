package com.shortcuts.app.automation.model

import com.google.gson.Gson
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import java.nio.charset.StandardCharsets
import java.util.UUID

class LegacyWorkflowMigrator {
    fun migrate(actions: List<Action>): LegacyMigrationResult {
        val canonical = Gson().toJson(actions)
        val workflowId = stableId("workflow:$canonical")
        var pendingDelay: Long? = null
        val steps = actions.mapIndexed { index, action ->
            val step = migrateAction(action, index, workflowId)
                .withCapturedDelay(pendingDelay)
            pendingDelay = if (action.actionType == ActionType.WAIT) null else action.delayMillis?.takeIf { it > 0 }
            step
        }
        val warnings = steps.flatMap(Step::migrationWarnings).distinct()
        return LegacyMigrationResult(
            workflow = WorkflowV2(
                workflowId = workflowId,
                recordedDevice = RecordedDevice(
                    manufacturer = "legacy",
                    model = "legacy",
                    sdkInt = 0,
                    displayWidthPx = actions.mapNotNull(Action::recordedDisplayWidth).firstOrNull() ?: 0,
                    displayHeightPx = actions.mapNotNull(Action::recordedDisplayHeight).firstOrNull() ?: 0,
                    densityDpi = actions.mapNotNull(Action::recordedDensityDpi).firstOrNull() ?: 0,
                    rotation = actions.mapNotNull(Action::recordedDisplayRotation).firstOrNull() ?: 0
                ),
                steps = steps
            ),
            warnings = warnings
        )
    }

    private fun migrateAction(action: Action, index: Int, workflowId: String): Step {
        val id = stableId("$workflowId:$index:${Gson().toJson(action)}")
        return when (action.actionType) {
            ActionType.APP_INTENT -> migrateLaunch(action, id)
            ActionType.UI_AUTOMATION -> migrateUi(action, id)
            ActionType.WAIT -> WaitStep(
                id = id,
                durationMs = action.delayMillis?.coerceAtLeast(0) ?: 0,
                migrationWarnings = if (action.delayMillis == null) listOf(WARNING_MISSING_WAIT_DURATION) else emptyList()
            )
            ActionType.SYSTEM_TOGGLE,
            ActionType.HTTP_REQUEST,
            ActionType.SEND_MESSAGE,
            ActionType.DIAL_NUMBER -> LegacyActionStep(id = id, action = action)
        }
    }

    private fun migrateLaunch(action: Action, id: String): LaunchAppStep {
        val packageName = action.packageName.orEmpty().ifBlank { action.target.orEmpty() }
        val warnings = buildList {
            add(WARNING_LEGACY_LAUNCH_RESUMES_TASK)
            if (packageName.isBlank()) add(WARNING_MISSING_TARGET_PACKAGE)
        }
        return LaunchAppStep(
            id = id,
            target = LaunchTarget.Launcher(packageName),
            policy = LaunchPolicy.RESUME_EXISTING_TASK,
            expectedWindow = legacyWindow(packageName),
            migrationWarnings = warnings
        )
    }

    private fun migrateUi(action: Action, id: String): Step {
        val uiType = action.uiActionType?.trim()?.uppercase()
        val globalKind = action.globalAction?.takeIf { it.isNotBlank() }
            ?: uiType?.takeIf { it.startsWith("GLOBAL_ACTION_") || it in GLOBAL_ALIASES }
        val operation = when {
            globalKind != null -> UiOperation.GLOBAL_ACTION(globalKind)
            uiType in setOf(null, "TAP", "CLICK", "PRESS") -> UiOperation.TAP
            uiType == "LONG_PRESS" -> UiOperation.LONG_PRESS
            uiType in setOf("TYPE_TEXT", "SET_TEXT", "TEXT_ENTRY", "TYPE", "INPUT_TEXT") ->
                UiOperation.SET_TEXT(action.textInput.orEmpty())
            uiType == "PRESS_ENTER" -> UiOperation.IME_ACTION(ImeActionKind.UNSPECIFIED)
            uiType in setOf("SCROLL", "SCROLL_FORWARD", "SCROLL_BACKWARD") -> UiOperation.SWIPE
            else -> return LegacyActionStep(
                id = id,
                action = action,
                migrationWarnings = listOf(WARNING_UNSUPPORTED_UI_ACTION)
            )
        }

        val warnings = mutableListOf(WARNING_NO_POSTCONDITION)
        val unresolved = action.targetText.equals("UNRESOLVED", ignoreCase = true) ||
            action.target.equals("UNRESOLVED", ignoreCase = true)
        if (unresolved) warnings += WARNING_UNRESOLVED_TARGET
        val rawPoint = legacyPoint(action)
        if (rawPoint != null) {
            warnings += WARNING_LEGACY_DISPLAY_COORDINATES
            if (action.recordedDisplayWidth == null || action.recordedDisplayHeight == null ||
                action.recordedDisplayRotation == null || action.recordedDensityDpi == null
            ) warnings += WARNING_MISSING_DISPLAY_METRICS
        }
        val packageName = action.targetPackageName.orEmpty().ifBlank { action.packageName.orEmpty() }
        if (packageName.isBlank() && globalKind == null) warnings += WARNING_MISSING_TARGET_PACKAGE

        val selector = if (globalKind != null) null else legacySelector(action, rawPoint, unresolved)
        val reveal = if (operation == UiOperation.SWIPE) {
            RevealPolicy(direction = action.scrollDirection.toRevealDirection())
        } else null
        return UiStep(
            id = id,
            operation = operation,
            expectedWindow = legacyWindow(packageName),
            element = selector,
            gesture = null,
            reveal = reveal,
            verification = VerificationSpec(emptyList(), allowBestEffort = true),
            safety = StepSafety.BEST_EFFORT,
            migrationWarnings = warnings.distinct()
        )
    }

    private fun legacySelector(action: Action, rawPoint: CapturedPoint?, unresolved: Boolean): ElementSelector? {
        var rank = 0
        val locators = buildList {
            action.targetNodeId.nonBlank()?.let {
                add(LocatorStrategy(rank++, LocatorKind.RESOURCE_ID_EXACT, it, LocatorStability.STABLE))
            }
            if (!unresolved) action.targetText.nonBlank()?.let {
                add(LocatorStrategy(rank++, LocatorKind.TEXT_EXACT, it, LocatorStability.VOLATILE))
            }
            action.targetContentDescription.nonBlank()?.let {
                add(LocatorStrategy(rank++, LocatorKind.CONTENT_DESCRIPTION_EXACT, it, LocatorStability.LIKELY_STABLE))
            }
        }
        if (locators.isEmpty() && rawPoint == null && action.targetClassName.isNullOrBlank()) return null
        val requiredActions = when (action.uiActionType?.trim()?.uppercase()) {
            "LONG_PRESS" -> setOf(NodeAction.LONG_CLICK)
            "TYPE_TEXT", "SET_TEXT", "TEXT_ENTRY", "TYPE", "INPUT_TEXT" -> setOf(NodeAction.SET_TEXT)
            "SCROLL", "SCROLL_FORWARD" -> setOf(NodeAction.SCROLL_FORWARD)
            "SCROLL_BACKWARD" -> setOf(NodeAction.SCROLL_BACKWARD)
            "PRESS_ENTER" -> setOf(NodeAction.IME_ENTER)
            else -> setOf(NodeAction.CLICK)
        }
        return ElementSelector(
            strategies = locators,
            expectedClass = action.targetClassName,
            expectedRole = null,
            ancestorChain = emptyList(),
            siblingIndex = null,
            rowContext = null,
            boundsInWindow = FULL_BOUNDS,
            rawPoint = rawPoint,
            requiredActions = requiredActions
        )
    }

    private fun legacyPoint(action: Action): CapturedPoint? {
        val x = action.screenX ?: return null
        val y = action.screenY ?: return null
        val width = action.recordedDisplayWidth ?: 0
        val height = action.recordedDisplayHeight ?: 0
        return CapturedPoint(
            displayPx = IntPoint(x, y),
            pointInWindow = NormalizedPoint(
                x = if (width > 0) x.toFloat() / width else 0f,
                y = if (height > 0) y.toFloat() / height else 0f
            ),
            pointInNode = null,
            displayWidthPx = width,
            displayHeightPx = height,
            rotation = action.recordedDisplayRotation ?: 0,
            densityDpi = action.recordedDensityDpi ?: 0,
            coordinateSpace = CoordinateSpace.DISPLAY_LEGACY
        )
    }

    private fun legacyWindow(packageName: String) = WindowSelector(
        packageName = packageName,
        observedActivityClass = null,
        windowType = WindowType.APPLICATION,
        titleToken = null,
        boundsOnDisplay = FULL_BOUNDS,
        displayId = 0,
        fingerprint = null
    )

    private fun Step.withCapturedDelay(delay: Long?): Step = when (this) {
        is LaunchAppStep -> copy(capturedDelayFromPreviousMs = delay)
        is UiStep -> copy(capturedDelayFromPreviousMs = delay)
        is WaitStep -> copy(capturedDelayFromPreviousMs = delay)
        is LegacyActionStep -> copy(capturedDelayFromPreviousMs = delay)
    }

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

    private fun String?.toRevealDirection(): RevealDirection = when (this?.trim()?.uppercase()) {
        "UP" -> RevealDirection.UP
        "LEFT" -> RevealDirection.LEFT
        "RIGHT" -> RevealDirection.RIGHT
        "BACKWARD" -> RevealDirection.BACKWARD
        "FORWARD" -> RevealDirection.FORWARD
        else -> RevealDirection.DOWN
    }

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()

    companion object {
        const val WARNING_LEGACY_LAUNCH_RESUMES_TASK = "LEGACY_LAUNCH_RESUMES_EXISTING_TASK"
        const val WARNING_MISSING_TARGET_PACKAGE = "MISSING_TARGET_PACKAGE"
        const val WARNING_NO_POSTCONDITION = "NO_POSTCONDITION"
        const val WARNING_UNRESOLVED_TARGET = "UNRESOLVED_TARGET"
        const val WARNING_LEGACY_DISPLAY_COORDINATES = "LEGACY_DISPLAY_COORDINATES"
        const val WARNING_MISSING_DISPLAY_METRICS = "MISSING_DISPLAY_METRICS"
        const val WARNING_UNSUPPORTED_UI_ACTION = "UNSUPPORTED_UI_ACTION"
        const val WARNING_MISSING_WAIT_DURATION = "MISSING_WAIT_DURATION"

        private val FULL_BOUNDS = NormalizedRect(0f, 0f, 1f, 1f)
        private val GLOBAL_ALIASES = setOf("GLOBAL_ACTION", "GLOBAL_NAVIGATION", "GLOBAL", "SYSTEM_NAV")
    }
}

data class LegacyMigrationResult(
    val workflow: WorkflowV2,
    val warnings: List<String>
)

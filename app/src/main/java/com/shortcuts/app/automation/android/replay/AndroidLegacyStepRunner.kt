package com.shortcuts.app.automation.android.replay

import com.shortcuts.app.automation.model.LegacyActionStep
import com.shortcuts.app.automation.replay.LegacyStepResult
import com.shortcuts.app.automation.replay.LegacyStepRunner
import com.shortcuts.app.service.ActionExecutorService
import com.shortcuts.app.service.StepResult

class AndroidLegacyStepRunner(
    private val actionExecutor: ActionExecutorService
) : LegacyStepRunner {

    override suspend fun run(step: LegacyActionStep): LegacyStepResult {
        return when (val result = actionExecutor.executeAction(step.action)) {
            is StepResult.Success -> LegacyStepResult.Success
            is StepResult.Failed -> LegacyStepResult.Failure(result.userMessage)
            is StepResult.NeedsPermission -> LegacyStepResult.Failure("Needs ${result.permission} permission")
            is StepResult.Skipped -> LegacyStepResult.Failure("Skipped: ${result.why}")
        }
    }
}

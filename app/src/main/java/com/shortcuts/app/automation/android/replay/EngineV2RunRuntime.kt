package com.shortcuts.app.automation.android.replay

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.shortcuts.app.BuildConfig
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter
import com.shortcuts.app.automation.android.EngineV2
import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.diagnostics.TraceEvent
import com.shortcuts.app.automation.diagnostics.TraceSink
import com.shortcuts.app.automation.model.DecodeResult
import com.shortcuts.app.automation.model.Step
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.diagnostics.Diagnostics
import com.shortcuts.app.diagnostics.ReplayTraceCompactor
import com.shortcuts.app.automation.replay.DefaultReplayEngine
import com.shortcuts.app.automation.replay.DispatchResult
import com.shortcuts.app.automation.replay.ReplayCancellationSignal
import com.shortcuts.app.automation.replay.ReplayFailureCode
import com.shortcuts.app.automation.replay.ReplayResult
import com.shortcuts.app.automation.replay.UiActionRequest
import com.shortcuts.app.automation.replay.UiEnvironment
import com.shortcuts.app.automation.replay.UiEnvironmentEvent
import com.shortcuts.app.service.ActionExecutorService
import com.shortcuts.app.service.AutomationAccessibilityService
import com.shortcuts.app.service.ExecutionCancellation
import com.shortcuts.app.service.FailureReason
import com.shortcuts.app.service.RunResult
import com.shortcuts.app.service.StepResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.security.MessageDigest

fun sha256TitleTokenizer(title: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(title.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun userFacingMessageForFailure(code: ReplayFailureCode, stepTitle: String?, stepIndex: Int): String {
    val label = stepTitle?.takeIf { it.isNotBlank() } ?: "step ${stepIndex + 1}"
    return when (code) {
        ReplayFailureCode.WINDOW_NOT_FOUND -> "Could not find the expected window for $label."
        ReplayFailureCode.WINDOW_AMBIGUOUS -> "Multiple windows matched $label."
        ReplayFailureCode.EXPECTED_SCREEN_TIMEOUT -> "Timed out waiting for the screen expected by $label."
        ReplayFailureCode.TARGET_LOW_CONFIDENCE -> "Could not confidently locate the target for $label."
        ReplayFailureCode.TARGET_AMBIGUOUS -> "Found multiple matching elements on screen for $label."
        ReplayFailureCode.TARGET_NOT_FOUND_AFTER_SCROLL -> "Could not find the target for $label after scrolling."
        ReplayFailureCode.SCROLL_CONTAINER_NOT_FOUND -> "Could not find a scrollable area for $label."
        ReplayFailureCode.SCROLL_NO_PROGRESS -> "Scrolling made no progress for $label."
        ReplayFailureCode.VERIFICATION_FAILED -> "Screen verification failed after running $label."
        ReplayFailureCode.DISPATCH_REJECTED -> "Screen action could not be performed for $label."
        ReplayFailureCode.UNVERIFIABLE_STEP -> "$label cannot be verified."
        ReplayFailureCode.DECODE_FAILED -> "Workflow data is invalid or cannot be read."
        ReplayFailureCode.CANCELLED -> "Shortcut run was stopped."
        ReplayFailureCode.ACCESSIBILITY_UNAVAILABLE -> "RepeatKit Accessibility Service disconnected."
    }
}

fun mapFailureCodeToReason(code: ReplayFailureCode): FailureReason = when (code) {
    ReplayFailureCode.WINDOW_NOT_FOUND,
    ReplayFailureCode.WINDOW_AMBIGUOUS,
    ReplayFailureCode.EXPECTED_SCREEN_TIMEOUT -> FailureReason.INVALID_STATE

    ReplayFailureCode.TARGET_LOW_CONFIDENCE,
    ReplayFailureCode.TARGET_AMBIGUOUS,
    ReplayFailureCode.TARGET_NOT_FOUND_AFTER_SCROLL,
    ReplayFailureCode.SCROLL_CONTAINER_NOT_FOUND,
    ReplayFailureCode.SCROLL_NO_PROGRESS,
    ReplayFailureCode.VERIFICATION_FAILED,
    ReplayFailureCode.DISPATCH_REJECTED -> FailureReason.UI_AUTOMATION_FAILED

    ReplayFailureCode.UNVERIFIABLE_STEP,
    ReplayFailureCode.DECODE_FAILED -> FailureReason.UNSUPPORTED_TARGET

    ReplayFailureCode.CANCELLED -> FailureReason.INVALID_STATE

    ReplayFailureCode.ACCESSIBILITY_UNAVAILABLE -> FailureReason.ACCESSIBILITY_UNAVAILABLE
}

fun mapReplayResultToRunResult(
    workflow: WorkflowV2,
    result: ReplayResult,
    shortcutName: String
): RunResult {
    val totalSteps = workflow.steps.size
    return when (result) {
        is ReplayResult.Success -> {
            val steps = List<StepResult>(totalSteps) { StepResult.Success }
            RunResult(shortcutName = shortcutName, steps = steps)
        }
        is ReplayResult.Failed -> {
            val completed = result.completedStepCount.coerceIn(0, totalSteps)
            val failedIndex = result.failure.stepId?.let { id ->
                workflow.steps.indexOfFirst { it.id == id }.takeIf { it != -1 }
            } ?: completed.coerceAtMost((totalSteps - 1).coerceAtLeast(0))

            val failedStep = workflow.steps.getOrNull(failedIndex)
            val userMsg = userFacingMessageForFailure(result.failure.code, failedStep?.title, failedIndex)
            val reason = mapFailureCodeToReason(result.failure.code)

            val stepResults = mutableListOf<StepResult>()
            for (i in 0 until failedIndex) {
                stepResults.add(StepResult.Success)
            }
            stepResults.add(StepResult.Failed(reason = reason, userMessage = userMsg))
            for (i in (failedIndex + 1) until totalSteps) {
                val skipWhy = if (result.failure.code == ReplayFailureCode.CANCELLED) {
                    "Skipped because the shortcut was stopped"
                } else {
                    "Skipped because step ${failedIndex + 1} did not complete"
                }
                stepResults.add(StepResult.Skipped(skipWhy))
            }
            RunResult(shortcutName = shortcutName, steps = stepResults)
        }
    }
}

class InMemoryTraceSink : TraceSink {
    private val _events = mutableListOf<TraceEvent>()
    val events: List<TraceEvent> get() = synchronized(_events) { _events.toList() }

    override fun append(event: TraceEvent) {
        synchronized(_events) {
            _events.add(event)
        }
    }
}

class BridgedReplayCancellationSignal(
    private val cancellation: ExecutionCancellation
) : ReplayCancellationSignal {
    override fun isCancelled(): Boolean = cancellation.isCancellationRequested

    override fun events(): Flow<Unit> = flow {
        while (!cancellation.isCancellationRequested) {
            delay(50)
        }
        emit(Unit)
    }
}

class EngineV2RunRuntime(
    private val context: Context,
    private val codec: WorkflowCodec = WorkflowCodec()
) {
    suspend fun tryRun(
        actionsJson: String,
        shortcutName: String,
        cancellation: ExecutionCancellation
    ): RunResult? {
        val decodeResult = codec.decode(actionsJson)
        return when (decodeResult) {
            is DecodeResult.Success -> {
                if (decodeResult.migratedFromLegacy) {
                    null // Legacy array: caller executes via legacy ActionExecutorService
                } else {
                    runWorkflow(decodeResult.workflow, shortcutName, cancellation)
                }
            }
            is DecodeResult.Failure -> {
                RunResult(
                    shortcutName = shortcutName,
                    steps = listOf(
                        StepResult.Failed(
                            reason = FailureReason.INVALID_STATE,
                            userMessage = "This shortcut could not be read."
                        )
                    )
                )
            }
        }
    }

    private suspend fun runWorkflow(
        workflow: WorkflowV2,
        shortcutName: String,
        cancellation: ExecutionCancellation
    ): RunResult {
        val service = AutomationAccessibilityService.instance
        val adapter = (EngineV2.snapshotSource as? AccessibilitySnapshotAdapter)
            ?: service?.let { AccessibilitySnapshotAdapter(it) }

        val environment = if (adapter != null && service != null) {
            AndroidUiEnvironment(adapter, service)
        } else {
            val needsAccessibility = workflow.steps.any { it is UiStep }
            if (needsAccessibility) {
                return RunResult(
                    shortcutName = shortcutName,
                    steps = listOf(
                        StepResult.Failed(
                            reason = FailureReason.ACCESSIBILITY_UNAVAILABLE,
                            userMessage = "Turn on RepeatKit Accessibility Service before running this shortcut."
                        )
                    )
                )
            }
            null
        }

        val launcherContext = service ?: context
        val appLauncher = AndroidAppLauncher(launcherContext)
        val actionExecutor = ActionExecutorService(context = context, cancellation = cancellation)
        val legacyStepRunner = AndroidLegacyStepRunner(actionExecutor)
        val cancellationSignal = BridgedReplayCancellationSignal(cancellation)

        val windowMatcher = WindowMatcher(
            fingerprinter = DefaultScreenFingerprinter(),
            // Same salted, normalized tokens the recorder wrote (review finding #10).
            titleTokenizer = com.shortcuts.app.automation.android.InstallTokens.tokenizer(context)::tokenize
        )

        val engine = DefaultReplayEngine(
            environment = environment ?: object : UiEnvironment {
                override fun snapshotWindows(): WindowSetSnapshot = WindowSetSnapshot(
                    capturedAtUptimeMs = SystemClock.uptimeMillis(),
                    displayWidth = 0,
                    displayHeight = 0,
                    rotation = 0,
                    density = 1f,
                    windows = emptyList()
                )
                override fun events(): Flow<UiEnvironmentEvent> = emptyFlow()
                override fun isAccessibilityAvailable(): Boolean = false
                override suspend fun perform(request: UiActionRequest): DispatchResult =
                    DispatchResult.Rejected("Accessibility service unavailable")
            },
            appLauncher = appLauncher,
            windowMatcher = windowMatcher,
            selectorMatcher = SelectorMatcher(),
            fingerprinter = DefaultScreenFingerprinter(),
            clock = { SystemClock.uptimeMillis() },
            delayer = { delay(it) },
            cancellation = cancellationSignal,
            legacyStepRunner = legacyStepRunner
        )

        val sink = InMemoryTraceSink()
        val replayResult = try {
            engine.run(workflow, sink)
        } finally {
            writeDebugTraceIfEnabled(sink)
            logDiagnosticsTrace(workflow, sink)
        }

        return mapReplayResultToRunResult(workflow, replayResult, shortcutName)
    }

    private fun logDiagnosticsTrace(workflow: WorkflowV2, sink: InMemoryTraceSink) {
        val stepTypes = workflow.steps.map(::formatReplayStepType)
        Diagnostics.log(
            "replay",
            "run_engine",
            mapOf(
                "workflowId" to workflow.workflowId,
                "stepCount" to workflow.steps.size,
                "stepTypes" to stepTypes
            )
        )
        val runStarted = sink.events.filterIsInstance<TraceEvent.RunStarted>().firstOrNull()
        val runId = runStarted?.runId
        val compacted = ReplayTraceCompactor.compact(sink.events)
        for (entry in compacted) {
            val traceFields = entry.toMutableMap()
            if (runId != null) {
                traceFields["run"] = runId
            }
            Diagnostics.log("replay", "trace", traceFields)
        }
    }

    private fun formatReplayStepType(step: Step): String = when (step) {
        is com.shortcuts.app.automation.model.LaunchAppStep -> "launch_app"
        is com.shortcuts.app.automation.model.WaitStep -> "wait"
        is com.shortcuts.app.automation.model.LegacyActionStep -> "legacy"
        is com.shortcuts.app.automation.model.UiStep -> when (val op = step.operation) {
            is com.shortcuts.app.automation.model.UiOperation.TAP -> "tap"
            is com.shortcuts.app.automation.model.UiOperation.LONG_PRESS -> "long_press"
            is com.shortcuts.app.automation.model.UiOperation.SWIPE -> "scroll"
            is com.shortcuts.app.automation.model.UiOperation.SET_TEXT -> "set_text"
            is com.shortcuts.app.automation.model.UiOperation.IME_ACTION -> "ime_action"
            is com.shortcuts.app.automation.model.UiOperation.GLOBAL_ACTION -> op.kind.lowercase()
        }
    }

    private fun writeDebugTraceIfEnabled(sink: InMemoryTraceSink) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val debugDir = File(context.filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            val traceFile = File(debugDir, "last_trace.jsonl")
            val gson = Gson()
            val content = sink.events.joinToString("\n") { gson.toJson(it) }
            traceFile.writeText(content)
        }
    }
}

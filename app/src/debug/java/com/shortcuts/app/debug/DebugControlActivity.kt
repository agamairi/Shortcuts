package com.shortcuts.app.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.ShortcutSteps
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.AutomationAccessibilityService
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.service.RunResult
import com.shortcuts.app.service.RunResultBus
import com.shortcuts.app.service.ShortcutRunOutcome
import com.shortcuts.app.service.ShortcutRunRequest
import com.shortcuts.app.service.ShortcutRunSource
import com.shortcuts.app.service.StepResult
import com.shortcuts.app.service.ShortcutRunner
import com.shortcuts.app.service.userMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** No-UI debug command endpoint. It is present only in the debug APK. */
class DebugControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawCommand = intent?.getStringExtra(EXTRA_COMMAND)
        val command = DebugCommand.parse(rawCommand, intent?.getStringExtra(EXTRA_NAME))
        if (command is DebugCommand.Invalid) {
            logDone(rawCommand, ok = false, file = null, error = command.reason)
            finish()
            return
        }

        if (command is DebugCommand.Run) {
            dispatchRun(command)
        } else {
            DebugScope.scope.launchCommand(command)
            finish()
        }
    }

    private fun dispatchRun(command: DebugCommand.Run) {
        // Keep the activity in the foreground until ShortcutRunner has admitted the run and
        // started AutomationExecutionService. The receipt collection itself remains in the
        // application-scoped DebugScope after this activity finishes.
        val startGate = CompletableDeferred<Unit>()
        DebugScope.scope.launchCommand(command, startGate)
        runCatching { runBlocking { startGate.await() } }
        finish()
    }

    private fun CoroutineScope.launchCommand(
        command: DebugCommand,
        startGate: CompletableDeferred<Unit>? = null
    ) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val outcome = if (command is DebugCommand.Run) {
                    runAutomation(command.name, startGate)
                } else {
                    CommandOutcome(
                        file = when (command) {
                            DebugCommand.StartRecording -> {
                                AutomationRecorder.clearRecording()
                                AutomationRecorder.startRecording(applicationContext)
                                null
                            }
                            DebugCommand.StopRecording -> {
                                AutomationRecorder.stopRecording(applicationContext)
                                null
                            }
                            DebugCommand.DumpRecording -> dumpRecording()
                            DebugCommand.DumpDiagnostics -> dumpDiagnostics()
                            is DebugCommand.Save -> saveRecording(command.name)
                            DebugCommand.List -> listAutomations()
                            is DebugCommand.Delete -> deleteAutomation(command.name)
                            DebugCommand.ClearRecording -> {
                                AutomationRecorder.clearRecording()
                                null
                            }
                            is DebugCommand.Invalid -> error(command.reason)
                            is DebugCommand.Run -> error("run dispatch error")
                        }
                    )
                }
                logDone(command.commandName, ok = outcome.ok, file = outcome.file, error = outcome.error)
            } catch (error: Throwable) {
                logDone(command.commandName, ok = false, file = null, error = error.shortError())
            } finally {
                startGate?.complete(Unit)
            }
        }
    }

    private fun dumpRecording(): String {
        val file = debugFile(LAST_RECORDING_FILE)
        writeJson(file, AutomationRecorder.recordedActions.value)
        val v2Json = com.shortcuts.app.automation.android.capture.V2CaptureBridge.lastWorkflowJson
        if (v2Json != null) {
            val v2File = debugFile(LAST_RECORDING_V2_FILE)
            val parsed = com.google.gson.JsonParser.parseString(v2Json)
            writeJson(v2File, parsed)
        }
        return "$DEBUG_DIRECTORY/$LAST_RECORDING_FILE"
    }

    private fun dumpDiagnostics(): String {
        com.shortcuts.app.diagnostics.Diagnostics.flush()
        val exportFile = com.shortcuts.app.diagnostics.Diagnostics.buildExport(applicationContext)
        val target = debugFile(DIAGNOSTICS_EXPORT_FILE)
        exportFile.copyTo(target, overwrite = true)
        return "$DEBUG_DIRECTORY/$DIAGNOSTICS_EXPORT_FILE"
    }

    private suspend fun saveRecording(name: String): String? {
        val actions = AutomationRecorder.recordedActions.value
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = AutomationRepository(database.automationDao())
        val existing = repository.allAutomations.first().firstOrNull { it.name == name }
        val v07Json = ActionConverter().fromActionList(actions)
        val isV2 = com.shortcuts.app.automation.android.EngineV2.enabled &&
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.lastWorkflowJson != null
        val format = if (isV2) "v2" else "legacy"
        val why = if (isV2) "v2" else "v2_unavailable"
        com.shortcuts.app.diagnostics.Diagnostics.log("recording", "save", mapOf("format" to format, "why" to why))
        val actionsJson = if (isV2) {
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.lastWorkflowJson!!
        } else {
            v07Json
        }
        if (existing == null) {
            repository.insert(Automation(name = name, actionsJson = actionsJson))
        } else {
            repository.update(existing.copy(actionsJson = actionsJson))
        }
        AutomationRecorder.clearRecording()
        return null
    }

    private suspend fun listAutomations(): String {
        val database = AppDatabase.getDatabase(applicationContext)
        val automations = AutomationRepository(database.automationDao()).allAutomations.first()
        val entries = automations.map { automation ->
            AutomationListEntry(
                id = automation.id,
                name = automation.name,
                stepCount = ShortcutSteps.stepCount(automation.actionsJson),
                isActive = automation.isActive
            )
        }
        val file = debugFile(AUTOMATIONS_FILE)
        writeJson(file, entries)
        return "$DEBUG_DIRECTORY/$AUTOMATIONS_FILE"
    }

    private suspend fun deleteAutomation(name: String): String? {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = AutomationRepository(database.automationDao())
        val automation = repository.allAutomations.first().firstOrNull { it.name == name }
            ?: error("automation not found: $name")
        repository.delete(automation)
        return null
    }

    private suspend fun runAutomation(
        name: String,
        startGate: CompletableDeferred<Unit>?
    ): CommandOutcome = coroutineScope {
        val database = AppDatabase.getDatabase(applicationContext)
        val automation = AutomationRepository(database.automationDao()).allAutomations.first()
            .firstOrNull { it.name == name }
            ?: error("automation not found: $name")
        val actions = ShortcutSteps.decode(automation.actionsJson).actions
        val startedAtUptimeMillis = SystemClock.uptimeMillis()
        AutomationAccessibilityService.AutomationTrace.clear()

        val resultEvent = async(start = CoroutineStart.UNDISPATCHED) {
            RunResultBus.results.first { event -> event.automationId == automation.id }
        }
        val outcome = ShortcutRunner(applicationContext).run(
            ShortcutRunRequest(
                shortcutId = automation.id,
                source = ShortcutRunSource.WIDGET
            )
        )
        // The activity waits only until the shared execution service has been admitted. The
        // result flow and JSON write continue in DebugScope while the activity finishes.
        startGate?.complete(Unit)
        val startedRunId = (outcome as? ShortcutRunOutcome.Started)?.runId ?: run {
            resultEvent.cancel()
            error(outcome.userMessage())
        }

        val event = resultEvent.await()
        check(event.runId == startedRunId) { "run result ID did not match the started run" }
        val endedAtUptimeMillis = SystemClock.uptimeMillis()
        val receipt = RunReceipt(
            automationId = automation.id,
            automationName = automation.name,
            steps = event.result.toDebugSteps(actions),
            matches = AutomationAccessibilityService.AutomationTrace.matches,
            waits = AutomationAccessibilityService.AutomationTrace.waits,
            startUptimeMillis = startedAtUptimeMillis,
            endUptimeMillis = endedAtUptimeMillis
        )
        val file = debugFile(LAST_RUN_FILE)
        writeJson(file, receipt)
        CommandOutcome(
            file = "$DEBUG_DIRECTORY/$LAST_RUN_FILE",
            ok = event.result.allSucceeded,
            error = event.result.firstIncomplete?.debugError()
        )
    }

    private fun StepResult.debugError(): String = when (this) {
        StepResult.Success -> ""
        is StepResult.Failed -> userMessage
        is StepResult.NeedsPermission -> "Permission $permission is required."
        is StepResult.Skipped -> why
    }

    private fun RunResult.toDebugSteps(actions: List<Action>): List<DebugStepResult> =
        steps.mapIndexed { index, step ->
            val action = actions.getOrNull(index)
            when (step) {
                StepResult.Success -> DebugStepResult(
                    index = index,
                    actionType = action?.actionType?.name ?: "UNKNOWN",
                    uiActionType = action?.uiActionType,
                    success = true,
                    failureReason = null,
                    message = null
                )
                is StepResult.Failed -> DebugStepResult(
                    index = index,
                    actionType = action?.actionType?.name ?: "UNKNOWN",
                    uiActionType = action?.uiActionType,
                    success = false,
                    failureReason = step.reason.name,
                    message = step.userMessage
                )
                is StepResult.NeedsPermission -> DebugStepResult(
                    index = index,
                    actionType = action?.actionType?.name ?: "UNKNOWN",
                    uiActionType = action?.uiActionType,
                    success = false,
                    failureReason = "NEEDS_PERMISSION",
                    message = "Permission ${step.permission} is required."
                )
                is StepResult.Skipped -> DebugStepResult(
                    index = index,
                    actionType = action?.actionType?.name ?: "UNKNOWN",
                    uiActionType = action?.uiActionType,
                    success = false,
                    failureReason = "SKIPPED",
                    message = step.why
                )
            }
        }

    private fun debugFile(fileName: String): File {
        val directory = File(applicationContext.filesDir, DEBUG_DIRECTORY)
        check((directory.exists() || directory.mkdirs()) && directory.isDirectory) {
            "could not create debug output directory"
        }
        return File(directory, fileName)
    }

    private fun writeJson(file: File, value: Any) {
        file.writeText(fileGson.toJson(value), Charsets.UTF_8)
    }

    private fun logDone(command: String?, ok: Boolean, file: String?, error: String?) {
        val done = CommandDone(
            cmd = command?.trim().orEmpty(),
            ok = ok,
            file = file,
            error = error?.replace(Regex("\\s+"), " ")?.trim()?.take(240)
        )
        Log.i(TAG, "CMD_DONE ${logGson.toJson(done)}")
    }

    private fun Throwable.shortError(): String =
        (message ?: javaClass.simpleName).replace(Regex("\\s+"), " ").trim().take(240)

    private data class AutomationListEntry(
        val id: Int,
        val name: String,
        val stepCount: Int,
        val isActive: Boolean
    )

    private data class DebugStepResult(
        val index: Int,
        val actionType: String,
        val uiActionType: String?,
        val success: Boolean,
        val failureReason: String?,
        val message: String?
    )

    private data class RunReceipt(
        val automationId: Int,
        val automationName: String,
        val steps: List<DebugStepResult>,
        val matches: List<AutomationAccessibilityService.AutomationTrace.MatchTrace>,
        val waits: List<AutomationAccessibilityService.AutomationTrace.WaitTrace>,
        val startUptimeMillis: Long,
        val endUptimeMillis: Long
    )

    private data class CommandDone(
        val cmd: String,
        val ok: Boolean,
        val file: String?,
        val error: String?
    )

    private data class CommandOutcome(
        val file: String?,
        val ok: Boolean = true,
        val error: String? = null
    )

    private companion object {
        const val TAG = "RepeatKitDebug"
        const val EXTRA_COMMAND = "cmd"
        const val EXTRA_NAME = "name"
        const val DEBUG_DIRECTORY = "debug"
        const val LAST_RECORDING_FILE = "last_recording.json"
        const val LAST_RECORDING_V2_FILE = "last_recording_v2.json"
        const val LAST_RUN_FILE = "last_run.json"
        const val AUTOMATIONS_FILE = "automations.json"
        const val DIAGNOSTICS_EXPORT_FILE = "diagnostics_export.txt"

        val fileGson: Gson = GsonBuilder()
            .setPrettyPrinting()
            .create()
        val logGson: Gson = GsonBuilder()
            .serializeNulls()
            .create()
    }
}

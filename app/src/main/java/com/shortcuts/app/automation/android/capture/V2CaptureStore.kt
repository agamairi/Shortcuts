package com.shortcuts.app.automation.android.capture

import android.util.Log
import com.google.gson.Gson
import com.shortcuts.app.automation.capture.CapturedAppSwitch
import com.shortcuts.app.automation.capture.CapturedGlobalAction
import com.shortcuts.app.automation.capture.CapturedImeAction
import com.shortcuts.app.automation.capture.CapturedInteraction
import com.shortcuts.app.automation.capture.CapturedTextCommit
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import java.io.File

/**
 * Wrapper holding a polymorphic captured event for persistence.
 */
data class PersistedV2EventWrapper(
    val type: String,
    val interaction: CapturedInteraction? = null,
    val textCommit: CapturedTextCommit? = null,
    val imeAction: CapturedImeAction? = null,
    val appSwitch: CapturedAppSwitch? = null,
    val globalAction: CapturedGlobalAction? = null
)

/**
 * Persistent representation of an active v2 recording session.
 */
data class PersistedV2Session(
    val workflowId: String,
    val initialSnapshot: WindowSetSnapshot,
    val events: List<PersistedV2EventWrapper> = emptyList()
)

/**
 * Storage abstraction for persisting v2 compiler events and finished workflows
 * across process restart.
 */
interface V2CaptureStore {
    fun saveActiveSession(session: PersistedV2Session)
    fun loadActiveSession(): PersistedV2Session?
    fun clearActiveSession()

    fun saveLastWorkflow(workflowJson: String)
    fun loadLastWorkflow(): String?
    fun clearLastWorkflow()

    fun clearAll() {
        clearActiveSession()
        clearLastWorkflow()
    }
}

/**
 * In-memory store for unit tests.
 */
class InMemoryV2CaptureStore : V2CaptureStore {
    private var activeSession: PersistedV2Session? = null
    private var lastWorkflowJson: String? = null

    override fun saveActiveSession(session: PersistedV2Session) {
        activeSession = session
    }

    override fun loadActiveSession(): PersistedV2Session? = activeSession

    override fun clearActiveSession() {
        activeSession = null
    }

    override fun saveLastWorkflow(workflowJson: String) {
        lastWorkflowJson = workflowJson
    }

    override fun loadLastWorkflow(): String? = lastWorkflowJson

    override fun clearLastWorkflow() {
        lastWorkflowJson = null
    }
}

/**
 * File-backed store under the app's files directory.
 */
class FileV2CaptureStore(private val directory: File) : V2CaptureStore {
    private val gson = Gson()
    private val sessionFile get() = File(directory, SESSION_FILE_NAME)
    private val workflowFile get() = File(directory, LAST_WORKFLOW_FILE_NAME)

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    override fun saveActiveSession(session: PersistedV2Session) {
        runCatching {
            sessionFile.writeText(gson.toJson(session), Charsets.UTF_8)
        }.onFailure { Log.w("V2Capture", "Failed to persist active session", it) }
    }

    override fun loadActiveSession(): PersistedV2Session? {
        return runCatching {
            if (!sessionFile.exists()) return null
            gson.fromJson(sessionFile.readText(Charsets.UTF_8), PersistedV2Session::class.java)
        }.getOrNull()
    }

    override fun clearActiveSession() {
        runCatching {
            if (sessionFile.exists()) sessionFile.delete()
        }
    }

    override fun saveLastWorkflow(workflowJson: String) {
        runCatching {
            workflowFile.writeText(workflowJson, Charsets.UTF_8)
        }.onFailure { Log.w("V2Capture", "Failed to persist last workflow", it) }
    }

    override fun loadLastWorkflow(): String? {
        return runCatching {
            if (!workflowFile.exists()) return null
            workflowFile.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    override fun clearLastWorkflow() {
        runCatching {
            if (workflowFile.exists()) workflowFile.delete()
        }
    }

    companion object {
        const val SESSION_FILE_NAME = "v2_capture_session.json"
        const val LAST_WORKFLOW_FILE_NAME = "v2_last_workflow.json"
    }
}

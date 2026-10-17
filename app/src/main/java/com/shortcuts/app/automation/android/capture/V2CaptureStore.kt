package com.shortcuts.app.automation.android.capture

import android.util.Log
import com.google.gson.Gson
import com.shortcuts.app.automation.capture.CapturedAppSwitch
import com.shortcuts.app.automation.capture.CapturedGlobalAction
import com.shortcuts.app.automation.capture.CapturedImeAction
import com.shortcuts.app.automation.capture.CapturedInteraction
import com.shortcuts.app.automation.capture.CapturedTextCommit
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Wrapper holding a polymorphic captured event for persistence.
 */
data class PersistedV2EventWrapper(
    val type: String,
    val interaction: CapturedInteraction? = null,
    val textCommit: CapturedTextCommit? = null,
    val imeAction: CapturedImeAction? = null,
    val appSwitch: CapturedAppSwitch? = null,
    val globalAction: CapturedGlobalAction? = null,
    /** Non-null only when the bridge had to infer an IME submit from tightly scoped evidence. */
    val inferredImeReason: String? = null,
    /** Snapshot missed the post-lift budget; compiler must prefer the DOWN hit provenance. */
    val lateSnapshot: Boolean = false
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
    /** Appends one durable event. False means the bounded recovery cache can no longer continue. */
    fun appendActiveEvent(session: PersistedV2Session, event: PersistedV2EventWrapper): Boolean {
        saveActiveSession(session)
        return true
    }
    fun loadActiveSession(): PersistedV2Session?
    /** A malformed durable capture is never silently treated as an empty recording. */
    fun takeLoadFailure(): String? = null
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

    override fun appendActiveEvent(session: PersistedV2Session, event: PersistedV2EventWrapper): Boolean {
        activeSession = session
        return true
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
    private val eventFile get() = File(directory, EVENT_LOG_FILE_NAME)
    private val workflowFile get() = File(directory, LAST_WORKFLOW_FILE_NAME)
    private var loadFailure: String? = null

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    override fun saveActiveSession(session: PersistedV2Session) {
        runCatching {
            // The manifest is deliberately small; the actual event history is append-only.
            writeAtomically(sessionFile, gson.toJson(session.copy(events = emptyList())))
            if (eventFile.exists()) eventFile.delete()
        }.onFailure { Log.w("V2Capture", "Failed to persist active session", it) }
    }

    override fun appendActiveEvent(session: PersistedV2Session, event: PersistedV2EventWrapper): Boolean = runCatching {
        val line = gson.toJson(compact(event)) + '\n'
        val previousCount = if (eventFile.exists()) eventFile.useLines { it.count() } else 0
        val previousBytes = eventFile.length()
        if (previousCount >= MAX_EVENTS || previousBytes + line.toByteArray(StandardCharsets.UTF_8).size > MAX_EVENT_LOG_BYTES) {
            Log.w("V2Capture", "Active capture recovery cache reached its bounded limit")
            false
        } else {
            // fsync every record: a process death may lose only an incomplete final line, which the
            // loader intentionally discards. The manifest update is atomic for crash consistency.
            FileOutputStream(eventFile, true).use { output ->
                output.write(line.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            writeAtomically(sessionFile, gson.toJson(session.copy(events = emptyList())))
            true
        }
    }.getOrElse {
        Log.w("V2Capture", "Failed to append active capture event", it)
        false
    }

    override fun loadActiveSession(): PersistedV2Session? {
        loadFailure = null
        if (!sessionFile.exists()) return null
        val manifest = runCatching {
            gson.fromJson(sessionFile.readText(Charsets.UTF_8), PersistedV2Session::class.java)
        }.getOrElse {
            loadFailure = "Active capture manifest is corrupt"
            Log.w("V2Capture", loadFailure!!, it)
            return null
        }
        if (!eventFile.exists()) return manifest
        val events = mutableListOf<PersistedV2EventWrapper>()
        val lines = eventFile.readLines(Charsets.UTF_8)
        lines.forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val event = runCatching { gson.fromJson(line, PersistedV2EventWrapper::class.java) }.getOrNull()
            if (event == null) {
                // An unterminated final append is recoverable; corruption anywhere else is not.
                if (index == lines.lastIndex) {
                    Log.w("V2Capture", "Dropped truncated final capture event")
                    return@forEachIndexed
                }
                loadFailure = "Active capture event log is corrupt at record ${index + 1}"
                Log.w("V2Capture", loadFailure!!)
                return null
            }
            events += event
        }
        return manifest.copy(events = events)
    }

    override fun takeLoadFailure(): String? = loadFailure.also { loadFailure = null }

    override fun clearActiveSession() {
        runCatching {
            if (sessionFile.exists()) sessionFile.delete()
            if (eventFile.exists()) eventFile.delete()
        }
    }

    override fun saveLastWorkflow(workflowJson: String) {
        runCatching {
            writeAtomically(workflowFile, workflowJson)
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
        const val EVENT_LOG_FILE_NAME = "v2_capture_events.v1.jsonl"
        const val LAST_WORKFLOW_FILE_NAME = "v2_last_workflow.json"
        const val MAX_EVENTS = 500
        const val MAX_EVENT_LOG_BYTES = 2 * 1024 * 1024
        private const val MAX_COMPACT_NODES = 200
    }

    private fun writeAtomically(target: File, text: String) {
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create ${directory.path}")
        val temp = File(directory, "${target.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(text.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Recovery needs selector context, not a complete copy of every screen. Keep the hit/field,
     * its ancestor chain, direct row siblings and its subtree; strip every unrelated node.
     */
    private fun compact(event: PersistedV2EventWrapper): PersistedV2EventWrapper = event.copy(
        interaction = event.interaction?.let { interaction ->
            interaction.copy(beforeSnapshot = compact(interaction.beforeSnapshot, interaction.hitWindowId, interaction.hitNodeId))
        },
        textCommit = event.textCommit?.let { commit ->
            commit.copy(windowSnapshot = compact(commit.windowSnapshot, commit.fieldNode.nodeId))
        },
        imeAction = event.imeAction?.let { action ->
            action.copy(windowSnapshot = compact(action.windowSnapshot, action.fieldNode.nodeId))
        },
        appSwitch = event.appSwitch?.let { appSwitch ->
            val primary = appSwitch.snapshot.windows.maxByOrNull { it.layer }
            appSwitch.copy(snapshot = compact(appSwitch.snapshot, primary?.windowId, primary?.rootId))
        },
        globalAction = event.globalAction?.let { action ->
            val primary = action.snapshot.windows.maxByOrNull { it.layer }
            action.copy(snapshot = compact(action.snapshot, primary?.windowId, primary?.rootId))
        }
    )

    private fun compact(snapshot: WindowSetSnapshot, windowId: Int?, nodeId: Int?): WindowSetSnapshot = snapshot.copy(
        windows = snapshot.windows.map { window ->
            if (window.windowId == windowId && nodeId != null) compact(window, nodeId) else window.copy(nodes = emptyMap())
        }
    )

    private fun compact(window: WindowSnapshot, nodeId: Int): WindowSnapshot {
        val target = window.node(nodeId) ?: return window.copy(nodes = emptyMap())
        val keep = linkedSetOf<Int>()
        fun includeTree(node: NodeSnapshot) {
            if (!keep.add(node.nodeId) || keep.size >= MAX_COMPACT_NODES) return
            window.children(node).forEach(::includeTree)
        }
        includeTree(target)
        var parent = window.parent(target)
        while (parent != null && keep.size < MAX_COMPACT_NODES) {
            keep += parent.nodeId
            // Row/same-class sibling context is enough for selector extraction.
            window.children(parent).filter { it.className == target.className }.forEach(::includeTree)
            parent = window.parent(parent)
        }
        return window.copy(nodes = window.nodes.filterKeys { it in keep })
    }
}

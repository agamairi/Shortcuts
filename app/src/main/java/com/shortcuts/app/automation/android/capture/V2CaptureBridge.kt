package com.shortcuts.app.automation.android.capture

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import com.shortcuts.app.automation.android.EngineV2
import com.shortcuts.app.automation.capture.CaptureScreenContext
import com.shortcuts.app.automation.capture.RecordingCompiler
import com.shortcuts.app.automation.capture.RecordingDeviceMetadata
import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.DecodeResult
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.service.RecorderEvent
import com.shortcuts.app.service.capture.TouchSample
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bridge between RepeatKit's live recording session and Engine v2's pure [RecordingCompiler].
 *
 * Runs alongside the v0.7 recording session and delivers snapshot-backed, typed capture events.
 * On stop, finishes compilation into a [WorkflowV2] and retains both the workflow and its
 * encoded JSON.
 *
 * Degrades gracefully if [EngineV2.snapshotSource] is unavailable at any point.
 */
object V2CaptureBridge {
    private const val TAG = "V2Capture"
    private const val PREFS_NAME = "v2_capture_bridge"
    private const val PREF_SALT = "tokenizer_salt"

    private val lock = Any()
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "v2-capture-compiler").apply { isDaemon = true }
    }
    private var store: V2CaptureStore? = null
    private var compiler: RecordingCompiler? = null
    private var activeSession: PersistedV2Session? = null
    private var lastForegroundPackage: String? = null
    private val lastActivitiesByPackage = mutableMapOf<String, String>()
    private val imeInference = ImeSubmitInference()
    private var degradedLogged = false

    @Volatile
    var lastWorkflow: WorkflowV2? = null
        private set

    @Volatile
    var lastWorkflowJson: String? = null
        private set

    /**
     * Starts or re-arms the v2 compiler when a recording session begins.
     */
    fun start(context: Context) {
        synchronized(lock) {
            if (!EngineV2.enabled) return@synchronized
            val currentStore = ensureStore(context)
            compiler = null
            activeSession = null
            lastWorkflow = null
            lastWorkflowJson = null
            lastForegroundPackage = null
            lastActivitiesByPackage.clear()
            imeInference.reset()
            degradedLogged = false
            currentStore.clearAll()

            val snapshot = EngineV2.snapshotSource?.snapshot()
            if (snapshot == null) {
                logDegraded("Snapshot source unavailable at start")
                return@synchronized
            }

            val launcherPackages = queryHomePackages(context.packageManager)
            val recorderPackage = context.packageName ?: "com.shortcuts.app"
            val salt = getOrCreateTokenizerSalt(context)
            val tokenizer = Sha256TextTokenizer(salt)
            val deviceMetadata = RecordingDeviceMetadata(
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT
            )
            val imePackages = resolveImePackages(context)
            val workflowId = UUID.randomUUID().toString()

            val newCompiler = RecordingCompiler(
                launcherPackageNames = launcherPackages,
                recorderPackageName = recorderPackage,
                screenFingerprinter = DefaultScreenFingerprinter(),
                textTokenizer = tokenizer,
                deviceMetadata = deviceMetadata,
                systemPackageNames = setOf("com.android.systemui"),
                imePackageNames = imePackages
            )

            newCompiler.start(CaptureScreenContext(workflowId, snapshot))
            compiler = newCompiler
            val session = PersistedV2Session(workflowId, snapshot)
            activeSession = session
            currentStore.saveActiveSession(session)
            Log.d(TAG, "Recording started: workflowId=$workflowId")
        }
    }

    /**
     * Finishes compilation of the active recording into a [WorkflowV2].
     */
    fun stop() {
        synchronized(lock) {
            val activeCompiler = compiler
            if (!EngineV2.enabled || activeCompiler == null) {
                compiler = null
                activeSession = null
                store?.clearActiveSession()
                return@synchronized
            }

            val finalSnapshot = EngineV2.snapshotSource?.snapshot()
            if (finalSnapshot != null) {
                runCatching { activeCompiler.updateScreen(finalSnapshot) }
            }

            val finishResult = runCatching { activeCompiler.finish() }
            finishResult.onSuccess { workflow ->
                val json = WorkflowCodec().encode(workflow)
                lastWorkflow = workflow
                lastWorkflowJson = json
                store?.saveLastWorkflow(json)
                Log.d(TAG, "Recording stopped: steps=${workflow.steps.size}")
            }.onFailure { error ->
                Log.w(TAG, "Failed to compile v2 workflow", error)
                lastWorkflow = null
                lastWorkflowJson = null
                store?.clearLastWorkflow()
            }

            compiler = null
            activeSession = null
            store?.clearActiveSession()
        }
    }

    /**
     * Drops any compiled workflow and deletes persisted session files.
     */
    fun clear() {
        synchronized(lock) {
            compiler = null
            activeSession = null
            lastWorkflow = null
            lastWorkflowJson = null
            lastForegroundPackage = null
            lastActivitiesByPackage.clear()
            imeInference.reset()
            degradedLogged = false
            store?.clearAll()
            Log.d(TAG, "Recording cleared")
        }
    }

    /**
     * Restores active recording session or last workflow across process restart.
     */
    fun restore(context: Context) {
        synchronized(lock) {
            if (!EngineV2.enabled) return@synchronized
            val currentStore = ensureStore(context)
            val savedSession = currentStore.loadActiveSession()
            val recoveryFailure = currentStore.takeLoadFailure()
            if (recoveryFailure != null) {
                // Do not silently substitute an empty compiler: saving this recording must use
                // the already-visible legacy session rather than inventing partial v2 output.
                Log.e(TAG, "V2 recording is unrecoverable: $recoveryFailure")
                currentStore.clearActiveSession()
                compiler = null
                activeSession = null
                return@synchronized
            }
            if (savedSession != null) {
                val launcherPackages = queryHomePackages(context.packageManager)
                val recorderPackage = context.packageName ?: "com.shortcuts.app"
                val salt = getOrCreateTokenizerSalt(context)
                val tokenizer = Sha256TextTokenizer(salt)
                val deviceMetadata = RecordingDeviceMetadata(
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    sdkInt = Build.VERSION.SDK_INT
                )
                val imePackages = resolveImePackages(context)

                val restoredCompiler = RecordingCompiler(
                    launcherPackageNames = launcherPackages,
                    recorderPackageName = recorderPackage,
                    screenFingerprinter = DefaultScreenFingerprinter(),
                    textTokenizer = tokenizer,
                    deviceMetadata = deviceMetadata,
                    systemPackageNames = setOf("com.android.systemui"),
                    imePackageNames = imePackages
                )

                restoredCompiler.start(CaptureScreenContext(savedSession.workflowId, savedSession.initialSnapshot))
                savedSession.events.forEach { wrapper ->
                    wrapper.interaction?.let(restoredCompiler::accept)
                    wrapper.textCommit?.let(restoredCompiler::accept)
                    wrapper.imeAction?.let(restoredCompiler::accept)
                    wrapper.appSwitch?.let(restoredCompiler::accept)
                    wrapper.globalAction?.let(restoredCompiler::accept)
                }
                compiler = restoredCompiler
                activeSession = savedSession
                rebuildBridgeStateLocked(savedSession)
                Log.d(TAG, "Restored active session: events=${savedSession.events.size}")
            } else {
                val workflowJson = currentStore.loadLastWorkflow()
                if (workflowJson != null) {
                    lastWorkflowJson = workflowJson
                    lastWorkflow = (WorkflowCodec().decode(workflowJson) as? DecodeResult.Success)?.workflow
                    Log.d(TAG, "Restored last workflow")
                }
            }
        }
    }

    /**
     * Delivers a classified touch gesture after its relay has been dispatched. Compilation and
     * persistence stay on the dedicated capture worker, never the input dispatcher.
     */
    fun onGestureClassified(
        kind: GestureKind,
        samples: List<TouchSample>,
        beforeSnapshot: WindowSetSnapshot?,
        ownPackage: String,
        lateSnapshot: Boolean = false
    ) {
        captureExecutor.execute {
        synchronized(lock) {
            if (!EngineV2.enabled) return@synchronized
            val activeCompiler = compiler ?: return@synchronized
            if (beforeSnapshot == null) {
                logDegraded("Snapshot source unavailable for gesture; recorded as coordinate-only by legacy capture")
                return@synchronized
            }

            val interaction = createCapturedInteraction(kind, samples, beforeSnapshot, ownPackage)
            imeInference.onOverlayInteraction(samples.firstOrNull()?.uptimeMs)
            activeCompiler.accept(interaction)
            persistEventLocked(PersistedV2EventWrapper(type = "interaction", interaction = interaction, lateSnapshot = lateSnapshot))

            val targetNode = interaction.hitNodeId?.let { beforeSnapshot.node(it) }
            val window = interaction.hitWindowId?.let { beforeSnapshot.window(it) }
            Log.d(
                TAG,
                "Delivered Interaction: kind=$kind pkg=${window?.packageName} nodeClass=${targetNode?.className} windowId=${interaction.hitWindowId} nodeId=${interaction.hitNodeId} viewId=${targetNode?.resourceId} points=${interaction.points.size} down=${interaction.downUptimeMs} up=${interaction.upUptimeMs}"
            )
        }
        }
    }

    /**
     * Delivers a committed field text edit to the compiler.
     */
    fun onTextCommit(event: RecorderEvent) {
        synchronized(lock) {
            if (!EngineV2.enabled) return@synchronized
            val activeCompiler = compiler ?: return@synchronized
            if (event.enteredText.isEmpty()) return@synchronized

            val snapshot = EngineV2.snapshotSource?.snapshot()
            if (snapshot == null) {
                logDegraded("Snapshot source unavailable for text commit")
                return@synchronized
            }

            val window = (event.windowId?.let { snapshot.window(it) })
                ?: snapshot.windows.firstOrNull { it.packageName == event.packageName && it.type != WindowType.ACCESSIBILITY_OVERLAY && it.type != WindowType.INPUT_METHOD }
                ?: snapshot.windows.maxByOrNull { it.layer }
                ?: return@synchronized

            val focusedEditable = window.nodes.values.firstOrNull { it.focused && it.editable }
            val fieldNode = focusedEditable ?: window.nodes.values.firstOrNull { candidate ->
                (event.sourceViewId != null && candidate.resourceId == event.sourceViewId) ||
                    (candidate.className == event.sourceClassName && candidate.editable)
            } ?: return@synchronized

            val commit = createCapturedTextCommit(fieldNode, event.enteredText, window, event.occurredAtMillis)
                ?: return@synchronized
            activeCompiler.accept(commit)
            persistEventLocked(PersistedV2EventWrapper(type = "text_commit", textCommit = commit))
            val currentActivity = lastActivitiesByPackage[window.packageName]
            imeInference.onTextCommit(commit, currentActivity)

            Log.d(
                TAG,
                "Delivered TextCommit: pkg=${window.packageName} nodeClass=${fieldNode.className} windowId=${window.windowId} nodeId=${fieldNode.nodeId} viewId=${fieldNode.resourceId} isEditable=${fieldNode.editable} password=${fieldNode.password} at=${commit.committedAtUptimeMs}"
            )
        }
    }

    /**
     * Delivers an IME submit action (or enter key) to the compiler.
     */
    fun onImeAction(label: String?, uptimeMs: Long) {
        synchronized(lock) {
            if (!EngineV2.enabled) return@synchronized
            val activeCompiler = compiler ?: return@synchronized
            imeInference.reset()
            val kind = mapImeLabelToActionKind(label)

            val snapshot = EngineV2.snapshotSource?.snapshot()
            if (snapshot == null) {
                logDegraded("Snapshot source unavailable for IME action")
                return@synchronized
            }

            val window = snapshot.windows
                .filterNot { it.type == WindowType.ACCESSIBILITY_OVERLAY || it.type == WindowType.INPUT_METHOD }
                .sortedWith(compareByDescending<WindowSnapshot> { it.isActive || it.isFocused }.thenByDescending { it.layer })
                .firstOrNull() ?: return@synchronized

            // An explicit IME action is only meaningful for the editable Android reports as
            // focused; selecting an arbitrary field manufactured submissions on unrelated inputs.
            val fieldNode = window.nodes.values.firstOrNull { it.focused && it.editable }
                ?: return@synchronized

            val action = createCapturedImeAction(fieldNode, kind, window, uptimeMs)
            activeCompiler.accept(action)
            persistEventLocked(PersistedV2EventWrapper(type = "ime_action", imeAction = action))

            Log.d(
                TAG,
                "Delivered ImeAction: kind=$kind pkg=${window.packageName} nodeClass=${fieldNode.className} windowId=${window.windowId} nodeId=${fieldNode.nodeId} viewId=${fieldNode.resourceId} at=$uptimeMs"
            )
        }
    }

    /**
     * Delivers an application switch to the compiler.
     */
    fun onAppSwitch(
        toPackage: String,
        className: CharSequence?,
        uptimeMs: Long
    ) {
        synchronized(lock) {
            if (!EngineV2.enabled) return@synchronized
            val activeCompiler = compiler ?: return@synchronized

            val snapshot = EngineV2.snapshotSource?.snapshot()
            if (snapshot == null) {
                logDegraded("Snapshot source unavailable for app switch")
                return@synchronized
            }

            val activityClass = if (looksLikeActivityClass(className)) {
                val act = className?.toString()
                if (act != null) lastActivitiesByPackage[toPackage] = act
                act
            } else {
                lastActivitiesByPackage[toPackage]
            }

            val inferred = imeInference.onActivityChanged(toPackage, activityClass, uptimeMs)
            if (inferred != null) {
                deliverInferredImeSubmitLocked(inferred)
            }

            val appSwitch = createCapturedAppSwitch(
                fromPackage = lastForegroundPackage,
                toPackage = toPackage,
                activityClass = activityClass,
                snapshot = snapshot,
                switchedAtUptimeMs = uptimeMs
            )
            activeCompiler.accept(appSwitch)
            persistEventLocked(PersistedV2EventWrapper(type = "app_switch", appSwitch = appSwitch))
            lastForegroundPackage = toPackage

            Log.d(
                TAG,
                "Delivered AppSwitch: from=${appSwitch.fromPackageName} to=$toPackage activityClass=$activityClass at=$uptimeMs"
            )
        }
    }

    /**
     * Inspects a text change event for field clearing (the "sent" pattern) or in-progress typing.
     */
    fun onTextChanged(event: RecorderEvent) {
        synchronized(lock) {
            if (!EngineV2.enabled || compiler == null) return@synchronized
            val snapshot = EngineV2.snapshotSource?.snapshot() ?: return@synchronized
            val window = (event.windowId?.let { snapshot.window(it) })
                ?: snapshot.windows.firstOrNull { it.packageName == event.packageName && it.type != WindowType.ACCESSIBILITY_OVERLAY && it.type != WindowType.INPUT_METHOD }
                ?: return@synchronized
            val field = window.nodes.values.firstOrNull { candidate ->
                candidate.editable && candidate.focused && event.sourceViewId != null &&
                    candidate.resourceId == event.sourceViewId
            } ?: return@synchronized
            imeInference.onFieldTextChanged(field, event.enteredText, event.occurredAtMillis)
                ?.let(::deliverInferredImeSubmitLocked)
        }
    }

    private fun deliverInferredImeSubmitLocked(inferred: InferredImeSubmit) {
        val activeCompiler = compiler ?: return
        inferred.pendingCommit?.let { commit ->
            activeCompiler.accept(commit)
            persistEventLocked(PersistedV2EventWrapper(type = "text_commit", textCommit = commit))
            Log.d(
                TAG,
                "Delivered pending TextCommit before inferred IME: pkg=${commit.windowSnapshot.packageName} nodeClass=${commit.fieldNode.className} at=${commit.committedAtUptimeMs}"
            )
        }
        activeCompiler.accept(inferred.action)
        persistEventLocked(PersistedV2EventWrapper(type = "ime_action", imeAction = inferred.action, inferredImeReason = inferred.reason))
        Log.w(TAG, "Inferred ImeAction (reason=${inferred.reason}; INFERRED_IME_ACTION)")
    }

    /**
     * Inspects a key event for global navigation actions (BACK, HOME, RECENTS).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        synchronized(lock) {
            if (!EngineV2.enabled || compiler == null) return false
            if (event.action != KeyEvent.ACTION_UP) return false

            imeInference.onKeyEvent(event.keyCode, event.eventTime)
            val kind = mapKeyCodeToGlobalAction(event.keyCode) ?: return false
            val snapshot = EngineV2.snapshotSource?.snapshot()
            if (snapshot == null) {
                logDegraded("Snapshot source unavailable for global action $kind")
                return false
            }

            val globalAction = createCapturedGlobalAction(kind, snapshot, event.eventTime)
            compiler?.accept(globalAction)
            persistEventLocked(PersistedV2EventWrapper(type = "global_action", globalAction = globalAction))
            Log.d(TAG, "Delivered GlobalAction: kind=$kind at=${event.eventTime}")
            return false
        }
    }

    private fun persistEventLocked(wrapper: PersistedV2EventWrapper) {
        val currentSession = activeSession ?: return
        val updatedSession = currentSession.copy(events = currentSession.events + wrapper)
        if (updatedSession.events.size > FileV2CaptureStore.MAX_EVENTS) {
            Log.w(TAG, "Capture recovery cache limit reached; v2 session is no longer recoverable")
            compiler = null
            activeSession = null
            store?.clearActiveSession()
            return
        }
        activeSession = updatedSession
        if (store?.appendActiveEvent(updatedSession, wrapper) == false) {
            Log.w(TAG, "Could not durably append capture event; v2 saving will fall back safely")
            compiler = null
            activeSession = null
            store?.clearActiveSession()
        }
    }

    /** Rebuild volatile bridge context from the ordered durable event stream after process death. */
    private fun rebuildBridgeStateLocked(session: PersistedV2Session) {
        lastForegroundPackage = null
        lastActivitiesByPackage.clear()
        imeInference.reset()
        session.events.forEach { event ->
            event.appSwitch?.let { appSwitch ->
                lastForegroundPackage = appSwitch.toPackageName
                appSwitch.observedActivityClass?.takeIf(::looksLikeActivityClass)?.let {
                    lastActivitiesByPackage[appSwitch.toPackageName] = it
                }
            }
            event.interaction?.let { imeInference.onOverlayInteraction(it.downUptimeMs) }
            event.textCommit?.let { commit ->
                imeInference.onTextCommit(commit, lastActivitiesByPackage[commit.windowSnapshot.packageName])
            }
            if (event.imeAction != null || event.globalAction != null) imeInference.reset()
        }
    }

    private fun ensureStore(context: Context): V2CaptureStore {
        return store ?: run {
            val appContext = context.applicationContext ?: context
            val filesDir = appContext.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            FileV2CaptureStore(File(filesDir, "v2_capture")).also {
                store = it
            }
        }
    }

    private fun logDegraded(reason: String) {
        if (!degradedLogged) {
            Log.w(TAG, "Degraded mode: $reason")
            degradedLogged = true
        }
    }

    private fun getOrCreateTokenizerSalt(context: Context): String = runCatching {
        val appContext = context.applicationContext ?: context
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var salt = prefs?.getString(PREF_SALT, null)
        if (salt.isNullOrBlank()) {
            salt = UUID.randomUUID().toString()
            prefs?.edit()?.putString(PREF_SALT, salt)?.apply()
        }
        salt
    }.getOrNull() ?: UUID.randomUUID().toString()

    private fun resolveImePackages(context: Context): Set<String> = runCatching {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabledPackages = imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet().orEmpty()
        val defaultImePackage = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )?.let { ComponentName.unflattenFromString(it)?.packageName }
        enabledPackages + setOfNotNull(defaultImePackage)
    }.getOrDefault(emptySet())

    // --- Testing hooks ---

    internal fun setStoreForTest(testStore: V2CaptureStore) {
        synchronized(lock) {
            store = testStore
        }
    }

    internal fun setCompilerForTest(testCompiler: RecordingCompiler?) {
        synchronized(lock) {
            compiler = testCompiler
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            compiler = null
            activeSession = null
            lastWorkflow = null
            lastWorkflowJson = null
            lastForegroundPackage = null
            lastActivitiesByPackage.clear()
            imeInference.reset()
            degradedLogged = false
            store = null
        }
    }

    internal fun getImeInferenceForTest(): ImeSubmitInference = imeInference

    internal fun awaitCaptureIdleForTest() {
        runCatching { captureExecutor.submit {}.get(2, TimeUnit.SECONDS) }
    }
}

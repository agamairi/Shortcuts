package com.shortcuts.app.automation.android.capture

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
    private var appContext: Context? = null
    private var store: V2CaptureStore? = null
    private var compiler: RecordingCompiler? = null
    private var activeSession: PersistedV2Session? = null
    private var lastForegroundPackage: String? = null
    private val lastActivitiesByPackage = mutableMapOf<String, String>()
    /** Most recent capture snapshot for each confirmed screen, retained for inferred Back's before state. */
    private val lastSnapshotsByScreen = mutableMapOf<Pair<String, String>, WindowSetSnapshot>()
    private var launcherPackages = emptySet<String>()
    private var imePackageNames = emptySet<String>()
    private data class PendingAppSwitch(
        val toPackage: String,
        val className: String?,
        val uptimeMs: Long
    )
    private val pendingAppSwitches = ArrayDeque<PendingAppSwitch>()
    private val screenCheckQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var latestScreenChangeUptimeMs = 0L
    private data class PendingTextContext(
        val packageName: String,
        val resourceId: String?,
        val className: String?,
        val enteredText: String,
        val fieldNode: com.shortcuts.app.automation.core.tree.NodeSnapshot,
        val window: WindowSnapshot,
        val snapshot: WindowSetSnapshot
    )
    private var pendingTextContext: PendingTextContext? = null
    private val activityCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Boolean>()
    private val imeInference = ImeSubmitInference()
    private var backInference = BackNavigationInference()
    private var degradedLogged = false

    @Volatile
    var lastWorkflow: WorkflowV2? = null
        private set

    @Volatile
    var lastWorkflowJson: String? = null
        private set

    internal fun isConfirmedActivity(
        packageName: String,
        className: String?
    ): Boolean {
        if (className.isNullOrBlank()) return false
        if (isIgnoredUiClass(className)) return false
        val key = packageName to className
        activityCache[key]?.let { return it }

        val context = appContext
        val pm = context?.packageManager
        val confirmed = if (pm != null) {
            val fullClassName = if (className.startsWith(".")) "$packageName$className" else className
            val component = ComponentName(packageName, fullClassName)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getActivityInfo(component, 0)
                }
                true
            }.getOrDefault(false)
        } else {
            looksLikeActivityClass(className)
        }
        activityCache[key] = confirmed
        return confirmed
    }

    /**
     * Starts or re-arms the v2 compiler when a recording session begins.
     */
    fun start(context: Context) {
        val future = captureExecutor.submit {
            synchronized(lock) {
                if (!EngineV2.enabled) return@synchronized
                appContext = context.applicationContext ?: context
                val currentStore = ensureStore(context)
                compiler = null
                activeSession = null
                lastWorkflow = null
                lastWorkflowJson = null
                lastForegroundPackage = null
                lastActivitiesByPackage.clear()
                lastSnapshotsByScreen.clear()
                activityCache.clear()
                imeInference.reset()
                pendingAppSwitches.clear()
                pendingTextContext = null
                imePackageNames = emptySet()
                degradedLogged = false
                currentStore.clearAll()

                val snapshot = snapshotWithRetry()
                if (snapshot == null) {
                    logDegraded("Snapshot source unavailable at start")
                    return@synchronized
                }

                val launcherPackages = queryHomePackages(context.packageManager)
                val recorderPackage = context.packageName ?: "com.shortcuts.app"
                this@V2CaptureBridge.launcherPackages = launcherPackages
                backInference = BackNavigationInference(ignoredPackages = launcherPackages + recorderPackage)
                val tokenizer = com.shortcuts.app.automation.android.InstallTokens.tokenizer(context)
                val deviceMetadata = RecordingDeviceMetadata(
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    sdkInt = Build.VERSION.SDK_INT
                )
                val imePackages = resolveImePackages(context)
                imePackageNames = imePackages
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
        runCatching { future.get(3, TimeUnit.SECONDS) }
    }

    /**
     * Finishes compilation of the active recording into a [WorkflowV2].
     *
     * @param compileWorkflow If false, capture files and compiler are cleared without producing a workflow.
     */
    fun stop(compileWorkflow: Boolean = true) {
        val future = captureExecutor.submit {
            synchronized(lock) {
                if (!compileWorkflow) {
                    compiler = null
                    activeSession = null
                    lastWorkflow = null
                    lastWorkflowJson = null
                    lastForegroundPackage = null
                    lastActivitiesByPackage.clear()
                    lastSnapshotsByScreen.clear()
                    activityCache.clear()
                    imeInference.reset()
                    pendingAppSwitches.clear()
                    pendingTextContext = null
                    imePackageNames = emptySet()
                    backInference.reset()
                    degradedLogged = false
                    store?.clearAll()
                    Log.d(TAG, "Recording stopped without compiling; capture files deleted")
                    return@synchronized
                }

                val activeCompiler = compiler
                if (!EngineV2.enabled || activeCompiler == null) {
                    compiler = null
                    activeSession = null
                    store?.clearActiveSession()
                    return@synchronized
                }

                val finalSnapshot = snapshotWithRetry()
                if (finalSnapshot != null) {
                    flushPendingAppSwitchesLocked(finalSnapshot)
                    imeInference.onScreenEffect(
                        finalSnapshot,
                        finalSnapshot.capturedAtUptimeMs,
                        lastForegroundPackage?.let(lastActivitiesByPackage::get)
                    )?.let(::deliverInferredImeSubmitLocked)
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
        runCatching { future.get(3, TimeUnit.SECONDS) }
    }

    /**
     * Drops any compiled workflow and deletes persisted session files.
     */
    fun clear() {
        val future = captureExecutor.submit {
            synchronized(lock) {
                compiler = null
                activeSession = null
                lastWorkflow = null
                lastWorkflowJson = null
                lastForegroundPackage = null
                lastActivitiesByPackage.clear()
                lastSnapshotsByScreen.clear()
                activityCache.clear()
                imeInference.reset()
                pendingAppSwitches.clear()
                pendingTextContext = null
                imePackageNames = emptySet()
                backInference.reset()
                degradedLogged = false
                store?.clearAll()
                Log.d(TAG, "Recording cleared")
            }
        }
        runCatching { future.get(2, TimeUnit.SECONDS) }
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
                this@V2CaptureBridge.launcherPackages = launcherPackages
                backInference = BackNavigationInference(ignoredPackages = launcherPackages + recorderPackage)
                val tokenizer = com.shortcuts.app.automation.android.InstallTokens.tokenizer(context)
                val deviceMetadata = RecordingDeviceMetadata(
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                    sdkInt = Build.VERSION.SDK_INT
                )
                val imePackages = resolveImePackages(context)
                imePackageNames = imePackages

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
            flushPendingAppSwitchesLocked(beforeSnapshot)

            val interaction = createCapturedInteraction(kind, samples, beforeSnapshot, ownPackage)
            val firstSample = samples.firstOrNull()
            imeInference.onOverlayInteraction(firstSample?.x?.toInt(), firstSample?.y?.toInt(), firstSample?.uptimeMs)
            backInference.onInteraction(interaction.downUptimeMs)
            activeCompiler.accept(interaction)
            persistEventLocked(PersistedV2EventWrapper(type = "interaction", interaction = interaction, lateSnapshot = lateSnapshot))

            val targetNode = interaction.hitNodeId?.let { beforeSnapshot.node(it) }
            val window = interaction.hitWindowId?.let { beforeSnapshot.window(it) }
            window?.packageName?.let { rememberSnapshotLocked(it, beforeSnapshot) }
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
        captureExecutor.execute {
            synchronized(lock) {
                if (!EngineV2.enabled) return@synchronized
                val activeCompiler = compiler ?: return@synchronized
                if (event.enteredText.isEmpty()) return@synchronized

                val liveSnapshot = EngineV2.snapshotSource?.snapshot()
                val capturedContext = pendingTextContext?.takeIf {
                    it.packageName == event.packageName &&
                        (event.sourceViewId == null || it.resourceId == event.sourceViewId) &&
                        (event.sourceClassName == null || it.className == event.sourceClassName)
                }
                val snapshot = capturedContext?.snapshot ?: liveSnapshot
                if (snapshot == null) {
                    logDegraded("Snapshot source unavailable for text commit")
                    return@synchronized
                }
                liveSnapshot?.let(::flushPendingAppSwitchesLocked)

                val window = capturedContext?.window ?: (event.windowId?.let { snapshot.window(it) })
                    ?: snapshot.windows.firstOrNull { it.packageName == event.packageName && it.type != WindowType.ACCESSIBILITY_OVERLAY && it.type != WindowType.INPUT_METHOD }
                    ?: snapshot.windows.maxByOrNull { it.layer }
                    ?: return@synchronized

                val fieldNode = capturedContext?.fieldNode
                    ?: resolveTextField(window, event.sourceViewId, event.sourceClassName, event.enteredText)
                    ?: return@synchronized

                val committedText = capturedContext?.enteredText ?: event.enteredText
                val commit = createCapturedTextCommit(fieldNode, committedText, window, event.occurredAtMillis)
                    ?: return@synchronized
                backInference.onInteraction(commit.committedAtUptimeMs)
                activeCompiler.accept(commit)
                persistEventLocked(PersistedV2EventWrapper(type = "text_commit", textCommit = commit))
                val currentActivity = lastActivitiesByPackage[window.packageName]
                imeInference.onTextCommit(commit, currentActivity, snapshot)
                pendingTextContext = null
                rememberSnapshotLocked(window.packageName, snapshot)

                Log.d(
                    TAG,
                    "Delivered TextCommit: pkg=${window.packageName} nodeClass=${fieldNode.className} windowId=${window.windowId} nodeId=${fieldNode.nodeId} viewId=${fieldNode.resourceId} isEditable=${fieldNode.editable} password=${fieldNode.password} at=${commit.committedAtUptimeMs}"
                )
            }
        }
    }

    /**
     * Delivers an IME submit action (or enter key) to the compiler.
     */
    fun onImeAction(label: String?, uptimeMs: Long) {
        captureExecutor.execute {
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
                flushPendingAppSwitchesLocked(snapshot)

                val window = snapshot.windows
                    .filterNot { it.type == WindowType.ACCESSIBILITY_OVERLAY || it.type == WindowType.INPUT_METHOD }
                    .sortedWith(compareByDescending<WindowSnapshot> { it.isActive || it.isFocused }.thenByDescending { it.layer })
                    .firstOrNull() ?: return@synchronized

                // An explicit IME action is only meaningful for the editable Android reports as
                // focused; selecting an arbitrary field manufactured submissions on unrelated inputs.
                val fieldNode = window.nodes.values.firstOrNull { it.focused && it.editable }
                    ?: return@synchronized

                val action = createCapturedImeAction(fieldNode, kind, window, uptimeMs)
                backInference.onInteraction(action.actionAtUptimeMs)
                activeCompiler.accept(action)
                persistEventLocked(PersistedV2EventWrapper(type = "ime_action", imeAction = action))
                rememberSnapshotLocked(window.packageName, snapshot)

                Log.d(
                    TAG,
                    "Delivered ImeAction: kind=$kind pkg=${window.packageName} nodeClass=${fieldNode.className} windowId=${window.windowId} nodeId=${fieldNode.nodeId} viewId=${fieldNode.resourceId} at=$uptimeMs"
                )
            }
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
        val classNameStr = className?.toString()
        captureExecutor.execute {
            synchronized(lock) {
                if (!EngineV2.enabled) return@synchronized
                val activeCompiler = compiler ?: return@synchronized

                val snapshot = snapshotWithRetry()
                val isImeWindowState = toPackage in imePackageNames ||
                    snapshot?.windows?.any {
                        it.type == WindowType.INPUT_METHOD && it.packageName == toPackage
                    } == true
                if (isImeWindowState) {
                    if (snapshot != null) {
                        flushPendingAppSwitchesLocked(snapshot)
                        imeInference.onScreenEffect(
                            snapshot,
                            uptimeMs,
                            lastForegroundPackage?.let(lastActivitiesByPackage::get)
                        )?.let(::deliverInferredImeSubmitLocked)
                    }
                    Log.d(TAG, "Ignored IME window state: pkg=$toPackage at=$uptimeMs")
                    return@synchronized
                }
                if (snapshot == null) {
                    pendingAppSwitches.addLast(PendingAppSwitch(toPackage, classNameStr, uptimeMs))
                    logDegraded("Snapshot source unavailable for app switch")
                    return@synchronized
                }
                flushPendingAppSwitchesLocked(snapshot)
                deliverAppSwitchLocked(activeCompiler, toPackage, classNameStr, uptimeMs, snapshot)
            }
        }
    }

    private fun deliverAppSwitchLocked(
        activeCompiler: RecordingCompiler,
        toPackage: String,
        className: String?,
        uptimeMs: Long,
        snapshot: WindowSetSnapshot
    ) {
        val confirmedActivityClass = className?.takeIf { isConfirmedActivity(toPackage, it) }
        val activityClass = confirmedActivityClass ?: lastActivitiesByPackage[toPackage]

        val inferred = imeInference.onActivityChanged(toPackage, activityClass, uptimeMs)
        if (inferred != null) {
            deliverInferredImeSubmitLocked(inferred)
        } else {
            imeInference.onScreenEffect(snapshot, uptimeMs, activityClass)
                ?.let(::deliverInferredImeSubmitLocked)
        }

        val fromPackage = lastForegroundPackage
        if (fromPackage != null && fromPackage in launcherPackages) {
            backInference.onInteraction(uptimeMs)
        }
        val inferredBack = backInference.onActivityChanged(toPackage, confirmedActivityClass, uptimeMs)
        if (confirmedActivityClass != null) lastActivitiesByPackage[toPackage] = confirmedActivityClass
        if (inferredBack != null) {
            val previousSnapshot = lastSnapshotsByScreen[
                inferredBack.packageName to inferredBack.fromActivityClass
            ]
            if (previousSnapshot != null) {
                deliverInferredBackLocked(inferredBack, previousSnapshot, uptimeMs)
            }
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
        confirmedActivityClass?.let { rememberSnapshotLocked(toPackage, snapshot, it) }

        Log.d(
            TAG,
            "Delivered AppSwitch: from=${appSwitch.fromPackageName} to=$toPackage activityClass=$activityClass at=$uptimeMs"
        )
    }

    private fun flushPendingAppSwitchesLocked(snapshot: WindowSetSnapshot) {
        val activeCompiler = compiler ?: return
        while (pendingAppSwitches.isNotEmpty()) {
            val pending = pendingAppSwitches.removeFirst()
            if (pending.toPackage in imePackageNames || snapshot.windows.any {
                    it.type == WindowType.INPUT_METHOD && it.packageName == pending.toPackage
                }
            ) {
                continue
            }
            deliverAppSwitchLocked(
                activeCompiler = activeCompiler,
                toPackage = pending.toPackage,
                className = pending.className,
                uptimeMs = pending.uptimeMs,
                snapshot = snapshot
            )
        }
    }

    /**
     * Inspects a text change event for field clearing (the "sent" pattern) or in-progress typing.
     */
    fun onTextChanged(event: RecorderEvent) {
        captureExecutor.execute {
            synchronized(lock) {
                if (!EngineV2.enabled || compiler == null) return@synchronized
                val snapshot = EngineV2.snapshotSource?.snapshot() ?: return@synchronized
                flushPendingAppSwitchesLocked(snapshot)
                val window = (event.windowId?.let { snapshot.window(it) })
                    ?: snapshot.windows.firstOrNull { it.packageName == event.packageName && it.type != WindowType.ACCESSIBILITY_OVERLAY && it.type != WindowType.INPUT_METHOD }
                    ?: return@synchronized
                val field = resolveTextField(window, event.sourceViewId, event.sourceClassName, event.enteredText)
                    ?: return@synchronized
                val previousTextContext = pendingTextContext?.takeIf {
                    it.packageName == window.packageName &&
                        (field.resourceId == null || it.resourceId == field.resourceId) &&
                        it.className == field.className
                }
                val keyboardWentAway = previousTextContext?.snapshot?.windows?.any {
                    it.type == WindowType.INPUT_METHOD
                } == true && snapshot.windows.none { it.type == WindowType.INPUT_METHOD }
                val isPostSubmitReplacement = previousTextContext != null &&
                    previousTextContext.enteredText != event.enteredText &&
                    (!field.focused || keyboardWentAway)
                if (!isPostSubmitReplacement) {
                    pendingTextContext = PendingTextContext(
                        packageName = window.packageName,
                        resourceId = field.resourceId,
                        className = field.className,
                        enteredText = event.enteredText,
                        fieldNode = field,
                        window = window,
                        snapshot = snapshot
                    )
                }
                imeInference.onFieldTextChanged(field, event.enteredText, event.occurredAtMillis, window)
                    ?.let(::deliverInferredImeSubmitLocked)
                    ?: imeInference.onScreenEffect(
                        snapshot,
                        event.occurredAtMillis,
                        lastActivitiesByPackage[window.packageName]
                    )?.let(::deliverInferredImeSubmitLocked)
            }
        }
    }

    /**
     * Inspects a focus/content/IME-window change without treating it as an application switch.
     *
     * Content changes arrive dozens of times a second in busy apps. Taking a full snapshot for each one
     * backed the capture queue up so far that stopping a recording timed out and the shortcut was saved
     * in the legacy format. So: keep at most one check queued, and only snapshot when something is
     * actually waiting on a screen change (a typed field that may have been submitted, or an app switch
     * that arrived while snapshots were unavailable).
     */
    fun onScreenChanged(uptimeMs: Long) {
        latestScreenChangeUptimeMs = uptimeMs
        if (!screenCheckQueued.compareAndSet(false, true)) return
        captureExecutor.execute {
            screenCheckQueued.set(false)
            synchronized(lock) {
                if (!EngineV2.enabled || compiler == null) return@synchronized
                if (imeInference.candidate == null && pendingAppSwitches.isEmpty()) return@synchronized
                val uptimeMs = latestScreenChangeUptimeMs
                val snapshot = EngineV2.snapshotSource?.snapshot() ?: return@synchronized
                flushPendingAppSwitchesLocked(snapshot)
                imeInference.onScreenEffect(
                    snapshot,
                    uptimeMs,
                    lastForegroundPackage?.let(lastActivitiesByPackage::get)
                )?.let(::deliverInferredImeSubmitLocked)
            }
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
        backInference.onInteraction(inferred.action.actionAtUptimeMs)
        persistEventLocked(PersistedV2EventWrapper(type = "ime_action", imeAction = inferred.action, inferredImeReason = inferred.reason))
        Log.w(TAG, "Inferred ImeAction (reason=${inferred.reason}; INFERRED_IME_ACTION)")
    }

    private fun deliverInferredBackLocked(
        inferred: BackInference,
        previousSnapshot: WindowSetSnapshot,
        uptimeMs: Long
    ) {
        val activeCompiler = compiler ?: return
        val action = createCapturedGlobalAction(
            kind = "BACK",
            snapshot = previousSnapshot,
            actionAtUptimeMs = uptimeMs,
            inferred = true
        )
        activeCompiler.accept(action)
        persistEventLocked(PersistedV2EventWrapper(type = "global_action", globalAction = action))
        Log.d(
            TAG,
            "Inferred GlobalAction BACK (from=${inferred.fromActivityClass} to=${inferred.toActivityClass}; INFERRED_BACK)"
        )
    }

    /**
     * Inspects a key event for global navigation actions (BACK, HOME, RECENTS).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        val keyCode = event.keyCode
        val eventTime = event.eventTime
        captureExecutor.execute {
            synchronized(lock) {
                if (!EngineV2.enabled || compiler == null) return@synchronized
                imeInference.onKeyEvent(keyCode, eventTime)
                val kind = mapKeyCodeToGlobalAction(keyCode) ?: return@synchronized
                backInference.onKeyDeliveredGlobalAction(kind, eventTime)
                val snapshot = EngineV2.snapshotSource?.snapshot()
                if (snapshot == null) {
                    logDegraded("Snapshot source unavailable for global action $kind")
                    return@synchronized
                }

                val globalAction = createCapturedGlobalAction(kind, snapshot, eventTime)
                compiler?.accept(globalAction)
                persistEventLocked(PersistedV2EventWrapper(type = "global_action", globalAction = globalAction))
                primaryApplicationPackage(snapshot)?.let { rememberSnapshotLocked(it, snapshot) }
                Log.d(TAG, "Delivered GlobalAction: kind=$kind at=$eventTime")
            }
        }
        return false
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
        lastSnapshotsByScreen.clear()
        imeInference.reset()
        pendingAppSwitches.clear()
        pendingTextContext = null
        backInference.reset()
        session.events.forEach { event ->
            event.appSwitch?.let { appSwitch ->
                lastForegroundPackage = appSwitch.toPackageName
                val confirmed = appSwitch.observedActivityClass?.takeIf { isConfirmedActivity(appSwitch.toPackageName, it) }
                backInference.onActivityChanged(appSwitch.toPackageName, confirmed, appSwitch.switchedAtUptimeMs)
                confirmed?.let {
                    lastActivitiesByPackage[appSwitch.toPackageName] = it
                    rememberSnapshotLocked(appSwitch.toPackageName, appSwitch.snapshot, it)
                }
            }
            event.interaction?.let {
                val pt = it.points.firstOrNull()?.displayPx
                imeInference.onOverlayInteraction(pt?.x, pt?.y, it.downUptimeMs)
                backInference.onInteraction(it.downUptimeMs)
                it.hitWindowId?.let(it.beforeSnapshot::window)?.packageName?.let { packageName ->
                    rememberSnapshotLocked(packageName, it.beforeSnapshot)
                }
            }
            event.textCommit?.let { commit ->
                imeInference.onTextCommit(commit, lastActivitiesByPackage[commit.windowSnapshot.packageName])
                backInference.onInteraction(commit.committedAtUptimeMs)
            }
            event.imeAction?.let {
                backInference.onInteraction(it.actionAtUptimeMs)
                imeInference.reset()
            }
            event.globalAction?.let {
                if (it.inferred) backInference.onInteraction(it.actionAtUptimeMs)
                else backInference.onKeyDeliveredGlobalAction(it.kind, it.actionAtUptimeMs)
                imeInference.reset()
            }
        }
    }

    private fun rememberSnapshotLocked(
        packageName: String,
        snapshot: WindowSetSnapshot,
        activityClass: String? = lastActivitiesByPackage[packageName]
    ) {
        if (activityClass != null) lastSnapshotsByScreen[packageName to activityClass] = snapshot
    }

    private fun primaryApplicationPackage(snapshot: WindowSetSnapshot): String? = snapshot.windows
        .filterNot { it.type == WindowType.ACCESSIBILITY_OVERLAY || it.type == WindowType.INPUT_METHOD }
        .sortedWith(compareByDescending<WindowSnapshot> { it.isActive || it.isFocused }.thenByDescending { it.layer })
        .firstOrNull()
        ?.packageName

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

    private fun snapshotWithRetry(): WindowSetSnapshot? {
        val source = EngineV2.snapshotSource ?: return null
        repeat(SNAPSHOT_ATTEMPTS) { attempt ->
            source.snapshot()?.let { return it }
            if (attempt + 1 < SNAPSHOT_ATTEMPTS) {
                runCatching { Thread.sleep(SNAPSHOT_RETRY_DELAY_MS) }
            }
        }
        return null
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

    internal fun setImePackageNamesForTest(packages: Set<String>) {
        synchronized(lock) {
            imePackageNames = packages
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
            lastSnapshotsByScreen.clear()
            imeInference.reset()
            backInference = BackNavigationInference()
            launcherPackages = emptySet()
            imePackageNames = emptySet()
            pendingTextContext = null
            degradedLogged = false
            store = null
        }
    }

    internal fun getImeInferenceForTest(): ImeSubmitInference = imeInference

    internal fun awaitCaptureIdleForTest() {
        runCatching { captureExecutor.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private const val SNAPSHOT_ATTEMPTS = 3
    private const val SNAPSHOT_RETRY_DELAY_MS = 25L
}

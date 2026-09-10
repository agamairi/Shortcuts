package com.shortcuts.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.data.Action
import com.shortcuts.app.util.AccessibilityStatusChecker
import kotlinx.coroutines.flow.StateFlow
import kotlin.collections.ArrayDeque

enum class RecorderEventType {
    CLICK,
    LONG_PRESS,
    TEXT_CHANGE,
    SELECTION_CHANGE,
    FOCUS,
    SCROLL,
    APP_CHANGE,
    CONTENT_CHANGE,
    PRESS_ENTER
}

/** The physical display context a coordinate-based tap was captured against. */
data class DisplaySnapshot(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val densityDpi: Int
)

/**
 * Coordinates are only safe on the display geometry they were captured from. Keep this Android
 * boundary here so both passive accessibility capture and the manual overlay record the same data.
 */
@Suppress("DEPRECATION")
internal fun captureDisplaySnapshotForRecording(context: Context): DisplaySnapshot? = runCatching {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        ?: return@runCatching null
    val display = windowManager.defaultDisplay ?: return@runCatching null
    val size = Point().also(display::getRealSize)
    if (size.x <= 0 || size.y <= 0) return@runCatching null
    DisplaySnapshot(
        width = size.x,
        height = size.y,
        rotation = display.rotation,
        densityDpi = context.resources.configuration.densityDpi
    )
}.getOrNull()

data class RecorderEvent(
    val eventType: RecorderEventType,
    val packageName: String,
    val sourceText: String?,
    val sourceContentDescription: String?,
    val sourceViewId: String?,
    val enteredText: String,
    val sourceClassName: String? = null,
    val sourceIsEditable: Boolean = false,
    val screenX: Int? = null,
    val screenY: Int? = null,
    val displaySnapshot: DisplaySnapshot? = null,
    /** AccessibilityEvent.eventTime when available; used to exclude the stop action. */
    val occurredAtMillis: Long = Long.MIN_VALUE,
    /** Scroll metadata copied from AccessibilityEvent so direction can be recorded accurately. */
    val fromIndex: Int? = null,
    val toIndex: Int? = null,
    val scrollX: Int? = null,
    val scrollY: Int? = null,
    val scrollDeltaX: Int? = null,
    val scrollDeltaY: Int? = null,
    /** Accessibility window containing the source, when Android supplies one. */
    val windowId: Int? = null
)

/**
 * App-process façade for the recording session. The [RecorderSessionOwner] remains independent
 * of Compose; [RecorderSessionService] keeps that process owner alive while recording.
 */
object AutomationRecorder {
    private const val TAG = "AutomationRecorder"

    // AccessibilityEvent.eventTime uses the device uptime timebase, so the stop cutoff must use
    // that same clock rather than wall-clock time.
    private val sessionOwner = RecorderSessionOwner(nowMillis = { android.os.SystemClock.uptimeMillis() })
    private var usesPersistentStore = false
    private var disconnectMonitor: AccessibilityServiceDisconnectMonitor? = null
    private var launcherPackage: String? = null

    val isRecording: StateFlow<Boolean> = sessionOwner.isRecording
    val recordedActions = sessionOwner.recordedActions

    private var scrollFinalizerHandler: Handler? = null
    private var pendingScrollFinalizer: Runnable? = null
    private var textFinalizerHandler: Handler? = null
    private var pendingTextFinalizer: Runnable? = null
    private val silentTapSuggestionTracker = SilentTapSuggestionTracker()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun restoreSession(context: Context) {
        configureStore(context)
        sessionOwner.restore()
        if (isRecording.value) {
            silentTapSuggestionTracker.onRecordingStarted(
                startedAtMillis = android.os.SystemClock.uptimeMillis(),
                initialWindowPackage = context.packageName
            )
            RecordingHudOverlay.show(AutomationAccessibilityService.instance, recordedActions.value.size)
        } else {
            silentTapSuggestionTracker.onRecordingStopped()
            RecordingHudOverlay.hide()
        }
    }

    fun setRecordingForTest(isRecording: Boolean) {
        disconnectMonitor?.cancelPendingDisconnect()
        cancelPendingScrollFinalizer()
        cancelPendingTextFinalizer()
        sessionOwner.replaceStore(InMemoryRecorderSessionStore())
        sessionOwner.setRecordingForTest(isRecording)
        resetSilentTapSuggestionTracking(isRecording)
    }

    fun startRecording(context: Context) {
        configureStore(context)
        disconnectMonitor?.cancelPendingDisconnect()
        cancelPendingScrollFinalizer()
        cancelPendingTextFinalizer()
        Log.d(TAG, "Starting recording")
        sessionOwner.start()
        resetSilentTapSuggestionTracking(
            isRecording = true,
            initialWindowPackage = context.packageName
        )
        RecordingHudOverlay.show(AutomationAccessibilityService.instance, recordedActions.value.size)
        RecorderSessionService.start(context)
    }

    fun stopRecording(
        context: Context,
        reason: RecorderSessionStopReason = RecorderSessionStopReason.USER_FINISHED
    ) {
        disconnectMonitor?.cancelPendingDisconnect()
        cancelPendingScrollFinalizer()
        cancelPendingTextFinalizer()
        // Fix #2: Dismiss any active TapMarkOverlay so it doesn't stay up blocking input
        // after recording stops (the overlay would otherwise wait for a tap or its 20s timeout).
        TapMarkOverlay.disarm()
        Log.d(TAG, "Stopping recording")
        sessionOwner.stop()
        RecordingHudOverlay.hide()
        silentTapSuggestionTracker.onRecordingStopped()
        RecorderSessionService.stop(context, reason)
    }

    /** Stops and discards a recording the user backed out of, without presenting it as completed. */
    fun discardRecording(context: Context) {
        stopRecording(context, RecorderSessionStopReason.DISCARDED)
        clearRecording()
    }

    /**
     * Starts a grace period when Android destroys a bound accessibility-service instance.
     * A replacement instance routinely reconnects after framework recycling, so only a service
     * still absent after the grace period and removed from Settings stops the recording.
     */
    fun stopForAccessibilityDisconnect(context: Context) {
        val appContext = context.applicationContext
        accessibilityDisconnectMonitor().onServiceDestroyed(
            isAccessibilityEnabled = {
                AccessibilityStatusChecker.isAccessibilityEnabled(appContext)
            },
            onDisconnectConfirmed = {
                if (isRecording.value) {
                    Log.w(TAG, "Accessibility service remained disabled; stopping recording")
                    TapMarkOverlay.disarm()
                    cancelPendingTextFinalizer()
                    sessionOwner.stop()
                    RecordingHudOverlay.hide()
                    silentTapSuggestionTracker.onRecordingStopped()
                    RecorderSessionNotifier.showAccessibilityDisconnected(appContext)
                    RecorderSessionService.stop(
                        appContext,
                        RecorderSessionStopReason.ACCESSIBILITY_DISCONNECTED
                    )
                }
            }
        )
    }

    /** Cancels a pending destruction confirmation when Android binds a replacement instance. */
    fun onAccessibilityServiceConnected() {
        disconnectMonitor?.onServiceConnected()
        if (isRecording.value) {
            RecordingHudOverlay.show(AutomationAccessibilityService.instance, recordedActions.value.size)
        }
    }

    fun clearRecording() {
        disconnectMonitor?.cancelPendingDisconnect()
        cancelPendingScrollFinalizer()
        cancelPendingTextFinalizer()
        TapMarkOverlay.disarm()
        RecordingHudOverlay.hide()
        sessionOwner.clear()
        silentTapSuggestionTracker.onRecordingStopped()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent, context: Context) {
        // Fix #3: Early-return when not recording. No event source access, text reading, or
        // logging should occur when the recorder is inactive. The lastEventTimeMs bookkeeping
        // used by waitForScreenToSettle during REPLAY lives in AutomationAccessibilityService
        // and runs before this method is called, so replay is unaffected.
        if (!isRecording.value) return

        configureStore(context)
        val eventType = classifyAccessibilityEventType(event.eventType) ?: return

        val packageName = event.packageName?.toString() ?: ""
        val transientPackages = resolveTransientPackages(context)

        // Most apps send TYPE_VIEW_TEXT_CHANGED. Some heavy autocomplete surfaces (notably
        // Chrome's omnibox) do not, but do send focus/scroll/content callbacks while their live
        // input node changes. Only query the active tree when an editable field is already
        // tracked; the common system-wide no-focus path does no extra node-tree work.
        if (eventType != RecorderEventType.TEXT_CHANGE &&
            refreshTrackedFocusedText(context, event.eventTime)
        ) {
            scheduleTextFinalizer()
        }

        if (eventType == RecorderEventType.SELECTION_CHANGE) {
            // Cursor movement and focus initialization are not edits. In particular, Gboard can
            // emit this once per keystroke, which used to duplicate every typed character.
            return
        }

        if (eventType == RecorderEventType.CLICK && packageName in transientPackages) {
            if (isImeSubmitEvent(event)) {
                cancelPendingTextFinalizer()
                sessionOwner.processEvent(
                    emptyRecorderEvent(RecorderEventType.PRESS_ENTER, packageName, event.eventTime),
                    context.packageName,
                    resolveLauncherPackage(context),
                    transientPackages
                )
                updateRecordingHud()
            }
            // Never inspect ordinary keyboard keys or turn them into taps.
            return
        }

        if (eventType == RecorderEventType.APP_CHANGE) {
            if (com.shortcuts.app.BuildConfig.DEBUG) {
                val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
                Log.v(
                    "RecorderRawEvents",
                    "Event: $eventTypeName | Pkg: ${event.packageName} | Cls: ${event.className}" +
                        " | TextLen: ${event.text?.joinToString("")?.length ?: 0}" +
                        " | HasDesc: ${event.contentDescription != null}"
                )
            }
            sessionOwner.processEvent(
                emptyRecorderEvent(eventType, packageName, event.eventTime),
                context.packageName,
                resolveLauncherPackage(context),
                transientPackages
            )
            updateRecordingHud()
            if (isRecording.value && silentTapSuggestionTracker.shouldSuggestForWindowStateChange(
                    eventTimeMillis = event.eventTime,
                    packageName = packageName,
                    recorderPackageName = context.packageName
                )
            ) {
                showSilentTapSuggestion(context)
            }
            return
        }

        if (eventType == RecorderEventType.CONTENT_CHANGE) {
            sessionOwner.processEvent(
                emptyRecorderEvent(eventType, packageName, event.eventTime),
                context.packageName,
                resolveLauncherPackage(context),
                transientPackages
            )
            updateRecordingHud()
            return
        }

        val rawNode = event.source ?: return

        val sensitiveTextNode = isSensitiveTextNode(rawNode)
        if (eventType == RecorderEventType.TEXT_CHANGE) {
            if (sensitiveTextNode) {
                rawNode.recycle()
                return
            }
        }

        // Fix #4: Debug log moved AFTER the password filter. Only log redacted metadata
        // (event type, package, class, text length, viewId) — never the raw text value or
        // content-description — so passwords are never leaked to logcat in debug builds.
        if (com.shortcuts.app.BuildConfig.DEBUG) {
            val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
            val bounds = Rect()
            rawNode.getBoundsInScreen(bounds)
            Log.v(
                "RecorderRawEvents",
                "Event: $eventTypeName | Pkg: ${event.packageName} | Cls: ${event.className}" +
                    " | TextLen: ${event.text?.joinToString("")?.length ?: 0}" +
                    " | HasDesc: ${event.contentDescription != null}" +
                    " | ViewId: ${rawNode.viewIdResourceName} | Bounds: $bounds"
            )
        }

        val node = AndroidRecorderNode(rawNode)
        val sourceText: String?
        val sourceContentDescription: String?
        val sourceViewId: String?

        if (eventType == RecorderEventType.CLICK || eventType == RecorderEventType.LONG_PRESS) {
            val derived = deriveNodeLabel(node)
            val textToUse = derived.text ?: derived.contentDescription
            if (textToUse.isNullOrBlank() && derived.viewIdResourceName.isNullOrBlank()) {
                sourceText = "UNRESOLVED"
                sourceContentDescription = null
                sourceViewId = null
            } else {
                sourceText = derived.text
                sourceContentDescription = derived.contentDescription
                sourceViewId = derived.viewIdResourceName
            }
        } else {
            sourceText = rawNode.text?.toString()
            sourceContentDescription = rawNode.contentDescription?.toString()
            sourceViewId = rawNode.viewIdResourceName
        }
        val bounds = Rect().also { rawNode.getBoundsInScreen(it) }
        val screenX = bounds.takeIf { !it.isEmpty }?.centerX()
        val screenY = bounds.takeIf { !it.isEmpty }?.centerY()
        val displaySnapshot = captureDisplaySnapshotForRecording(context)
        val sourceClassName = rawNode.className?.toString()
        val sourceIsEditable = isEditableNode(rawNode) && !sensitiveTextNode
        val enteredText = when {
            eventType == RecorderEventType.TEXT_CHANGE -> event.text.joinToString("")
            sourceIsEditable -> readableEditableText(rawNode)
            else -> event.text.joinToString("")
        }
        node.free()

        val recorderEvent = RecorderEvent(
            eventType = eventType,
            packageName = packageName,
            sourceText = sourceText,
            sourceContentDescription = sourceContentDescription,
            sourceViewId = sourceViewId,
            enteredText = enteredText,
            sourceClassName = sourceClassName,
            sourceIsEditable = sourceIsEditable,
            screenX = screenX,
            screenY = screenY,
            displaySnapshot = displaySnapshot,
            occurredAtMillis = event.eventTime,
            fromIndex = event.fromIndex.takeIf { it >= 0 },
            toIndex = event.toIndex.takeIf { it >= 0 },
            scrollX = event.scrollX.takeIf { it >= 0 },
            scrollY = event.scrollY.takeIf { it >= 0 },
            scrollDeltaX = event.scrollDeltaXOrNull(),
            scrollDeltaY = event.scrollDeltaYOrNull(),
            windowId = event.windowId.takeIf { it >= 0 }
        )
        sessionOwner.processEvent(
            recorderEvent,
            context.packageName,
            resolveLauncherPackage(context),
            transientPackages
        )
        updateRecordingHud()
        if (eventType == RecorderEventType.CLICK && isRecording.value) {
            silentTapSuggestionTracker.recordCapturedClick(event.eventTime)
        }
        if (eventType == RecorderEventType.SCROLL) {
            scheduleScrollFinalizer()
        }
        if (eventType == RecorderEventType.TEXT_CHANGE) {
            scheduleTextFinalizer()
        } else if (eventType == RecorderEventType.CLICK || eventType == RecorderEventType.LONG_PRESS) {
            cancelPendingTextFinalizer()
        }
    }

    internal fun classifyAccessibilityEventType(eventType: Int): RecorderEventType? = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> RecorderEventType.CLICK
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> RecorderEventType.LONG_PRESS
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RecorderEventType.TEXT_CHANGE
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> RecorderEventType.SELECTION_CHANGE
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> RecorderEventType.FOCUS
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> RecorderEventType.SCROLL
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RecorderEventType.APP_CHANGE
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> RecorderEventType.CONTENT_CHANGE
        else -> null
    }

    /** Records a physical Enter key without consuming it, so the foreground app still receives it. */
    fun onKeyEvent(event: KeyEvent, context: Context, activePackageName: String?): Boolean {
        if (!isRecording.value || activePackageName == context.packageName ||
            event.action != KeyEvent.ACTION_UP || !isEnterKey(event.keyCode)
        ) {
            return false
        }
        cancelPendingTextFinalizer()
        sessionOwner.processEvent(
            emptyRecorderEvent(
                RecorderEventType.PRESS_ENTER,
                activePackageName.orEmpty(),
                event.eventTime
            ),
            context.packageName,
            resolveLauncherPackage(context),
            resolveTransientPackages(context)
        )
        updateRecordingHud()
        return false
    }

    fun processEvent(
        event: RecorderEvent,
        myPackageName: String,
        launcherPackage: String? = null,
        transientPackages: Set<String> = emptySet()
    ) {
        sessionOwner.processEvent(event, myPackageName, launcherPackage, transientPackages)
    }

    /**
     * Appends a manually-marked tap [Action] to the active recording.
     *
     * Called by [TapMarkOverlay] when the user taps on the helper overlay to mark a
     * coordinate. This bypasses the normal accessibility-event path (which never fires
     * for some apps) and writes directly into the same [RecorderSessionOwner] data store,
     * so the step appears alongside auto-captured steps in the review screen.
     */
    fun appendManualTap(action: Action) {
        sessionOwner.appendManualAction(action)
        updateRecordingHud()
        if (isRecording.value) {
            silentTapSuggestionTracker.recordCapturedClick(android.os.SystemClock.uptimeMillis())
        }
    }

    private fun resetSilentTapSuggestionTracking(isRecording: Boolean, initialWindowPackage: String? = null) {
        if (isRecording) {
            silentTapSuggestionTracker.onRecordingStarted(
                startedAtMillis = android.os.SystemClock.uptimeMillis(),
                initialWindowPackage = initialWindowPackage
            )
        } else {
            silentTapSuggestionTracker.onRecordingStopped()
        }
    }

    private fun updateRecordingHud() {
        if (isRecording.value) RecordingHudOverlay.update(recordedActions.value.size)
    }

    /** Posts from the service path so the short, dismissible prompt is always shown on the UI thread. */
    private fun showSilentTapSuggestion(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            if (isRecording.value) {
                Toast.makeText(
                    appContext,
                    "Didn't catch that? Try Mark a tap to record it manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Synchronized
    private fun configureStore(context: Context) {
        if (!usesPersistentStore) {
            sessionOwner.replaceStore(RecorderSessionStorePreferences(context.applicationContext))
            usesPersistentStore = true
        }
    }

    /** Resolves once per app process because the default launcher rarely changes mid-recording. */
    @Synchronized
    private fun resolveLauncherPackage(context: Context): String? {
        if (launcherPackage == null) {
            val resolved = resolveLauncherPackageViaResolveActivity(context)
            launcherPackage = resolved
            if (resolved != null) {
                Log.d(TAG, "Resolved launcher package: $resolved")
            } else {
                Log.w(TAG, "Failed to resolve launcher package")
            }
        }
        return launcherPackage
    }

    /**
     * `resolveActivity(homeIntent, MATCH_DEFAULT_ONLY)` (the old, sole strategy here) can return
     * the platform's generic "android" resolver-stub package instead of the real launcher when
     * Home resolution is ambiguous to it — confirmed on a real device (Pixel 6a), where it
     * silently broke every downstream comparison that depends on the launcher's real package
     * name. `queryIntentActivities` with the same `MATCH_DEFAULT_ONLY` flag only returns
     * activities that actually declare a `CATEGORY_HOME` + `CATEGORY_DEFAULT` intent filter — the
     * synthetic "android" resolver stub is not itself such an activity, so it does not appear
     * here even in the ambiguous case. (`RoleManager.getRoleHolders` would be the most
     * authoritative source, but it is a privileged/system API not present in the public SDK — a
     * normal app cannot call it, even though `adb shell dumpsys role` can.)
     */
    private fun resolveLauncherPackageViaResolveActivity(context: Context): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packageManager = context.applicationContext.packageManager
        val candidates = try {
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            emptyList()
        }
        val fromQuery = candidates
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it != "android" && it != context.packageName }
        if (fromQuery != null) return fromQuery

        // Fall back to resolveActivity only if the query came back empty — still guard against
        // the known-bad synthetic "android" result rather than caching it as if it were real.
        return packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeUnless { it == "android" }
    }

    /** The active IME is resolved dynamically because keyboard package names vary by device. */
    private fun resolveTransientPackages(context: Context): Set<String> {
        return setOfNotNull(resolveCurrentInputMethodPackage(context))
    }

    private fun resolveCurrentInputMethodPackage(context: Context): String? {
        val fromSettings = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
        }.getOrNull()
            ?.let(ComponentName::unflattenFromString)
            ?.packageName
        if (!fromSettings.isNullOrBlank()) return fromSettings

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return runCatching {
                context.getSystemService(InputMethodManager::class.java)
                    ?.currentInputMethodInfo
                    ?.packageName
            }.getOrNull()
        }
        return null
    }

    @Synchronized
    private fun scheduleScrollFinalizer() {
        val handler = scrollFinalizerHandler ?: Handler(Looper.getMainLooper())
            .also { scrollFinalizerHandler = it }
        pendingScrollFinalizer?.let(handler::removeCallbacks)
        val finalizer = Runnable {
            sessionOwner.flushPendingScroll()
            updateRecordingHud()
            synchronized(this) { pendingScrollFinalizer = null }
        }
        pendingScrollFinalizer = finalizer
        handler.postDelayed(finalizer, SCROLL_QUIET_PERIOD_MS)
    }

    @Synchronized
    private fun cancelPendingScrollFinalizer() {
        val handler = scrollFinalizerHandler ?: return
        pendingScrollFinalizer?.let(handler::removeCallbacks)
        pendingScrollFinalizer = null
    }

    @Synchronized
    private fun scheduleTextFinalizer() {
        val handler = textFinalizerHandler ?: Handler(Looper.getMainLooper())
            .also { textFinalizerHandler = it }
        pendingTextFinalizer?.let(handler::removeCallbacks)
        val finalizer = Runnable {
            sessionOwner.flushPendingText()
            updateRecordingHud()
            synchronized(this) { pendingTextFinalizer = null }
        }
        pendingTextFinalizer = finalizer
        handler.postDelayed(finalizer, TEXT_QUIET_PERIOD_MS)
    }

    @Synchronized
    private fun cancelPendingTextFinalizer() {
        val handler = textFinalizerHandler ?: return
        pendingTextFinalizer?.let(handler::removeCallbacks)
        pendingTextFinalizer = null
    }

    @Synchronized
    private fun accessibilityDisconnectMonitor(): AccessibilityServiceDisconnectMonitor {
        return disconnectMonitor ?: AccessibilityServiceDisconnectMonitor(
            scheduler = AndroidMainThreadGracePeriodScheduler(),
            isRecording = { isRecording.value }
        ).also { disconnectMonitor = it }
    }

    private fun AccessibilityEvent.scrollDeltaXOrNull(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) scrollDeltaX else null

    private fun AccessibilityEvent.scrollDeltaYOrNull(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) scrollDeltaY else null

    /**
     * Reads the current input focus using the same root.findFocus(FOCUS_INPUT) pattern used by
     * replay. The lookup is reached only after [trackedFocusedEditableField] returned non-null.
     */
    private fun refreshTrackedFocusedText(context: Context, occurredAtMillis: Long): Boolean {
        val tracked = sessionOwner.trackedFocusedEditableField() ?: return false
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
            ?: return false
        val root = service.getRootNode() ?: return false
        var focusedNode: AccessibilityNodeInfo? = null
        return try {
            focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (!isEditableNode(focusedNode) || isSensitiveTextNode(focusedNode)) return false

            val liveEvent = RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = focusedNode.packageName?.toString() ?: tracked.packageName,
                sourceText = readableEditableText(focusedNode),
                sourceContentDescription = focusedNode.contentDescription?.toString()
                    ?: tracked.sourceContentDescription,
                sourceViewId = focusedNode.viewIdResourceName ?: tracked.sourceViewId,
                enteredText = readableEditableText(focusedNode),
                sourceClassName = focusedNode.className?.toString() ?: tracked.sourceClassName,
                sourceIsEditable = true,
                occurredAtMillis = occurredAtMillis,
                windowId = focusedNode.windowId.takeIf { it >= 0 } ?: tracked.windowId
            )
            sessionOwner.processLiveFocusedTextSnapshot(liveEvent)
        } catch (_: Exception) {
            false
        } finally {
            focusedNode?.recycle()
            if (focusedNode !== root) root.recycle()
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable || className.endsWith("EditText") ||
            className.endsWith("AutoCompleteTextView")
    }

    private fun readableEditableText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) return ""
        val text = node.text?.toString().orEmpty()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return text
        return text.takeUnless { it == node.hintText?.toString() }.orEmpty()
    }

    private fun isSensitiveTextNode(node: AccessibilityNodeInfo): Boolean {
        val inputType = node.inputType
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val isPasswordClass = variation in listOf(
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        ) || ((inputType and android.text.InputType.TYPE_MASK_CLASS) ==
            android.text.InputType.TYPE_CLASS_NUMBER &&
            variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return node.isPassword || isPasswordClass
    }

    private fun emptyRecorderEvent(
        eventType: RecorderEventType,
        packageName: String,
        occurredAtMillis: Long
    ) = RecorderEvent(
        eventType = eventType,
        packageName = packageName,
        sourceText = null,
        sourceContentDescription = null,
        sourceViewId = null,
        enteredText = "",
        occurredAtMillis = occurredAtMillis
    )

    internal fun isEnterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isImeSubmitEvent(event: AccessibilityEvent): Boolean {
        val source = event.source
        val labels = event.text.map { it.toString() } + listOfNotNull(
            event.contentDescription?.toString(),
            source?.text?.toString(),
            source?.contentDescription?.toString()
        )
        source?.recycle()
        return labels.any { label ->
            label.trim().lowercase() in setOf("enter", "go", "search", "send", "done", "next")
        }
    }

    private const val SCROLL_QUIET_PERIOD_MS = 250L
    private const val TEXT_QUIET_PERIOD_MS = 400L
}

/**
 * Detects a likely interaction which changed a screen but had no corresponding accessibility
 * click callback. It intentionally treats this as a hint, not a certainty.
 */
internal class SilentTapSuggestionTracker(
    private val gracePeriodMillis: Long = GRACE_PERIOD_MILLIS,
    private val clickRecencyMillis: Long = CLICK_RECENCY_MILLIS,
    private val cooldownMillis: Long = COOLDOWN_MILLIS
) {
    private var recordingStartedAtMillis = Long.MIN_VALUE
    private var lastCapturedClickAtMillis = Long.MIN_VALUE
    private var lastSuggestionAtMillis = Long.MIN_VALUE
    private var lastWindowPackage: String? = null

    fun onRecordingStarted(startedAtMillis: Long, initialWindowPackage: String? = null) {
        recordingStartedAtMillis = startedAtMillis
        lastCapturedClickAtMillis = Long.MIN_VALUE
        lastSuggestionAtMillis = Long.MIN_VALUE
        lastWindowPackage = initialWindowPackage
    }

    fun onRecordingStopped() {
        recordingStartedAtMillis = Long.MIN_VALUE
        lastCapturedClickAtMillis = Long.MIN_VALUE
        lastSuggestionAtMillis = Long.MIN_VALUE
        lastWindowPackage = null
    }

    fun recordCapturedClick(eventTimeMillis: Long) {
        if (recordingStartedAtMillis != Long.MIN_VALUE) {
            lastCapturedClickAtMillis = eventTimeMillis
        }
    }

    fun shouldSuggestForWindowStateChange(
        eventTimeMillis: Long,
        packageName: String,
        recorderPackageName: String
    ): Boolean {
        val previousWindowPackage = lastWindowPackage
        if (packageName.isNotBlank()) {
            lastWindowPackage = packageName
        }

        if (recordingStartedAtMillis == Long.MIN_VALUE || packageName.isBlank()) return false
        if (eventTimeMillis - recordingStartedAtMillis < gracePeriodMillis) return false
        if (packageName == recorderPackageName || previousWindowPackage == recorderPackageName) return false

        val clickAgeMillis = eventTimeMillis - lastCapturedClickAtMillis
        if (lastCapturedClickAtMillis != Long.MIN_VALUE && clickAgeMillis in 0..clickRecencyMillis) {
            return false
        }

        if (lastSuggestionAtMillis != Long.MIN_VALUE &&
            eventTimeMillis - lastSuggestionAtMillis < cooldownMillis
        ) {
            return false
        }

        lastSuggestionAtMillis = eventTimeMillis
        return true
    }

    private companion object {
        const val GRACE_PERIOD_MILLIS = 3_000L
        const val CLICK_RECENCY_MILLIS = 2_000L
        const val COOLDOWN_MILLIS = 12_000L
    }
}

/** Android scheduling adapter; the disconnect policy itself remains pure Kotlin. */
private class AndroidMainThreadGracePeriodScheduler : GracePeriodScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, task: () -> Unit): GracePeriodCancellation {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMillis)
        return GracePeriodCancellation { handler.removeCallbacks(runnable) }
    }
}

interface RecorderNode {
    val text: String?
    val contentDescription: String?
    val viewIdResourceName: String?
    val childCount: Int
    fun getChildAt(index: Int): RecorderNode?
    fun getParent(): RecorderNode?
    fun free()
}

class AndroidRecorderNode(private val info: AccessibilityNodeInfo) : RecorderNode {
    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val viewIdResourceName: String? get() = info.viewIdResourceName
    override val childCount: Int get() = info.childCount
    override fun getChildAt(index: Int): RecorderNode? {
        return info.getChild(index)?.let { AndroidRecorderNode(it) }
    }
    override fun getParent(): RecorderNode? {
        return info.parent?.let { AndroidRecorderNode(it) }
    }
    override fun free() {
        info.recycle()
    }
}

data class DerivedLabel(
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?
)

fun deriveNodeLabel(node: RecorderNode): DerivedLabel {
    try {
        // 1. Prefer node's own
        if (!node.viewIdResourceName.isNullOrBlank() || !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) {
            return DerivedLabel(node.text, node.contentDescription, node.viewIdResourceName)
        }

        // 3. Search descendants breadth-first
        var visitedCount = 0
        val maxVisited = 20
        val maxDepth = 3

        val queue = ArrayDeque<Pair<RecorderNode, Int>>()
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let { child ->
                queue.addLast(child to 1)
            }
        }

        while (queue.isNotEmpty() && visitedCount < maxVisited) {
            val (curr, depth) = queue.removeFirst()
            visitedCount++

            val hasLabel = !curr.text.isNullOrBlank() || !curr.contentDescription.isNullOrBlank()
            if (hasLabel) {
                val result = DerivedLabel(curr.text, curr.contentDescription, curr.viewIdResourceName)
                curr.free()
                queue.forEach { it.first.free() }
                return result
            }

            if (depth < maxDepth) {
                for (i in 0 until curr.childCount) {
                    curr.getChildAt(i)?.let { child ->
                        queue.addLast(child to depth + 1)
                    }
                }
            }
            curr.free()
        }
        queue.forEach { it.first.free() }

        // 4. Walk up ancestors
        val maxAncestors = 2
        var ancestorCount = 0
        var currentAncestor = node.getParent()

        while (currentAncestor != null && ancestorCount < maxAncestors) {
            if (!currentAncestor.viewIdResourceName.isNullOrBlank() || !currentAncestor.text.isNullOrBlank() || !currentAncestor.contentDescription.isNullOrBlank()) {
                val result = DerivedLabel(currentAncestor.text, currentAncestor.contentDescription, currentAncestor.viewIdResourceName)
                currentAncestor.free()
                return result
            }
            val nextAncestor = currentAncestor.getParent()
            currentAncestor.free()
            currentAncestor = nextAncestor
            ancestorCount++
        }
        currentAncestor?.free()

        // 5. Yield nothing
        return DerivedLabel(null, null, null)
    } finally {
        // Do not free the root node here.
    }
}

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
import com.shortcuts.app.service.capture.CaptureSuppressionGate
import com.shortcuts.app.service.capture.PassiveCaptureEvent
import com.shortcuts.app.service.capture.RecorderCaptureMode
import com.shortcuts.app.service.capture.RecorderCaptureModePreferences
import com.shortcuts.app.service.capture.TouchCaptureOverlay
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
    val windowId: Int? = null,
    /** Whether the source had input focus when the event happened; null when unknown. */
    val sourceIsFocused: Boolean? = null
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
    @Volatile private var recordingCaptureMode = RecorderCaptureMode.PASSIVE
    @Volatile private var captureSuppressionGate = CaptureSuppressionGate(overlayMode = false)

    val isRecording: StateFlow<Boolean> = sessionOwner.isRecording
    val recordedActions = sessionOwner.recordedActions

    private var scrollFinalizerHandler: Handler? = null
    private var pendingScrollFinalizer: Runnable? = null
    private var textFinalizerHandler: Handler? = null
    private var pendingTextFinalizer: Runnable? = null
    private val silentTapSuggestionTracker = SilentTapSuggestionTracker()
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        sessionOwner.onTextCommitListener = { event ->
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onTextCommit(event)
        }
    }

    fun restoreSession(context: Context) {
        configureStore(context)
        cacheCaptureMode(context)
        sessionOwner.restore()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.restore(context)
        if (isRecording.value) {
            silentTapSuggestionTracker.onRecordingStarted(
                startedAtMillis = android.os.SystemClock.uptimeMillis(),
                initialWindowPackage = context.packageName
            )
            RecordingHudOverlay.show(AutomationAccessibilityService.instance, recordedActions.value.size)
            TouchCaptureOverlay.show(AutomationAccessibilityService.instance, isOverlayCaptureMode())
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
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.clear()
        recordingCaptureMode = RecorderCaptureMode.PASSIVE
        captureSuppressionGate = CaptureSuppressionGate(overlayMode = false)
        resetSilentTapSuggestionTracking(isRecording)
    }

    internal fun setDisconnectMonitorForTest(monitor: AccessibilityServiceDisconnectMonitor?) {
        disconnectMonitor = monitor
    }

    internal fun setPendingScrollFinalizerForTest(finalizer: Runnable?) {
        pendingScrollFinalizer = finalizer
    }

    internal fun hasPendingScrollFinalizerForTest(): Boolean = pendingScrollFinalizer != null

    fun startRecording(context: Context) {
        configureStore(context)
        disconnectMonitor?.cancelPendingDisconnect()
        cancelPendingScrollFinalizer()
        cancelPendingTextFinalizer()
        Log.d(TAG, "Starting recording")
        cacheCaptureMode(context)
        sessionOwner.start()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.start(context)
        resetSilentTapSuggestionTracking(
            isRecording = true,
            initialWindowPackage = context.packageName
        )
        RecordingHudOverlay.show(AutomationAccessibilityService.instance, recordedActions.value.size)
        TouchCaptureOverlay.show(AutomationAccessibilityService.instance, isOverlayCaptureMode())
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
        TouchCaptureOverlay.hide()
        Log.d(TAG, "Stopping recording")
        sessionOwner.stop()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.stop(compileWorkflow = reason != RecorderSessionStopReason.DISCARDED)
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
                    TouchCaptureOverlay.hide()
                    cancelPendingScrollFinalizer()
                    cancelPendingTextFinalizer()
                    sessionOwner.stop()
                    com.shortcuts.app.automation.android.capture.V2CaptureBridge.stop(compileWorkflow = false)
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
            TouchCaptureOverlay.show(AutomationAccessibilityService.instance, isOverlayCaptureMode())
        }
    }

    fun clearRecording() {
        disconnectMonitor?.cancelPendingDisconnect()
        cancelPendingScrollFinalizer()
        cancelPendingTextFinalizer()
        TapMarkOverlay.disarm()
        TouchCaptureOverlay.hide()
        RecordingHudOverlay.hide()
        sessionOwner.clear()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.clear()
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
        if (!captureSuppressionGate.shouldProcess(
                event = eventType.toPassiveCaptureEvent(),
                eventUptimeMs = event.eventTime,
                fromActiveIme = eventType == RecorderEventType.CLICK && packageName in transientPackages
            )
        ) return

        // Most apps send TYPE_VIEW_TEXT_CHANGED. Some heavy autocomplete surfaces (notably
        // Chrome's omnibox) do not, but do send focus/scroll/content callbacks while their live
        // input node changes. Only query the active tree when an editable field is already
        // tracked; the common system-wide no-focus path does no extra node-tree work.
        val focusedTextRefresh = if (eventType != RecorderEventType.TEXT_CHANGE) {
            refreshTrackedFocusedText(context, event.eventTime)
        } else {
            FocusedTextSnapshotResult.NOT_TRACKED
        }
        handleFocusedTextRefresh(focusedTextRefresh, event.eventTime, checkScreenEffect = true)

        if (eventType == RecorderEventType.SELECTION_CHANGE) {
            // Cursor movement and focus initialization are not edits. In particular, Gboard can
            // emit this once per keystroke, which used to duplicate every typed character.
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onScreenChanged(event.eventTime)
            return
        }

        if (eventType == RecorderEventType.CLICK && packageName in transientPackages) {
            val imeSubmitLabel = resolveImeSubmitLabel(event)
            if (imeSubmitLabel != null) {
                cancelPendingTextFinalizer()
                sessionOwner.processEvent(
                    emptyRecorderEvent(RecorderEventType.PRESS_ENTER, packageName, event.eventTime),
                    context.packageName,
                    resolveLauncherPackage(context),
                    transientPackages
                )
                com.shortcuts.app.automation.android.capture.V2CaptureBridge.onImeAction(
                    label = imeSubmitLabel,
                    uptimeMs = event.eventTime
                )
                updateRecordingHud()
            }
            // Never inspect ordinary keyboard keys or turn them into taps.
            return
        }

        if (eventType == RecorderEventType.APP_CHANGE &&
            (packageName in transientPackages || isFromInputMethodWindow(event, context))
        ) {
            // Input-method windows are not foreground applications. When the keyboard has closed,
            // flush the app field while its typed value is still buffered, then let v2 inspect the
            // resulting screen effect without changing app attribution to the keyboard package.
            // Gboard also reports window-state changes continuously while the user types
            // (suggestion strip, key previews); flushing on those split one search into a commit
            // per keystroke, which flooded the capture queue.
            if (!isInputMethodWindowShowing(context)) {
                cancelPendingTextFinalizer()
                sessionOwner.flushPendingText()
            }
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onScreenChanged(event.eventTime)
            updateRecordingHud()
            return
        }

        if (eventType == RecorderEventType.APP_CHANGE && !isFromApplicationWindow(event, context)) {
            // Floating windows from other apps (e.g. a "Dynamic Island" overlay, chat heads,
            // system dialogs drawn over apps) emit window-state events too. They are not the app
            // the user switched to, and treating them as one recorded a bogus "open app" step.
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
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onAppSwitch(
                toPackage = packageName,
                className = event.className,
                uptimeMs = event.eventTime
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

        if (isBackgroundWindowEvent(eventType, packageName, event, context)) {
            // Content, scroll and focus callbacks come from every visible window, not only the app
            // in front: the Pixel launcher keeps a system window updating while Chrome is open, and
            // carousels scroll on their own. Treated as a switch to that app, they reset the field
            // being typed into, so Chrome's address-bar text (which only a live re-read can see)
            // was never captured. They still count as a screen change for the field in front.
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onScreenChanged(event.eventTime)
            return
        }

        if (eventType == RecorderEventType.CONTENT_CHANGE) {
            sessionOwner.processEvent(
                emptyRecorderEvent(eventType, packageName, event.eventTime),
                context.packageName,
                resolveLauncherPackage(context),
                transientPackages
            )
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onScreenChanged(event.eventTime)
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
        val sourceIsFocused = rawNode.isFocused
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
            windowId = event.windowId.takeIf { it >= 0 },
            sourceIsFocused = sourceIsFocused.takeIf { eventType == RecorderEventType.TEXT_CHANGE }
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
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onTextChanged(recorderEvent)
        } else if (eventType == RecorderEventType.FOCUS) {
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onScreenChanged(event.eventTime)
        } else if (eventType == RecorderEventType.CLICK || eventType == RecorderEventType.LONG_PRESS) {
            cancelPendingTextFinalizer()
        }
    }

    /**
     * True when the event's window is an ordinary application window, or when the window can't
     * be identified (keep today's behaviour rather than dropping a real app switch).
     */
    private fun isFromApplicationWindow(event: AccessibilityEvent, context: Context): Boolean {
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
            ?: return true
        val windowId = event.windowId
        if (windowId < 0) return true
        val window = runCatching { service.windows.orEmpty().firstOrNull { it.id == windowId } }.getOrNull()
            ?: return true
        return window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
    }

    /**
     * True for a content, scroll or focus callback from another app's window that is not the
     * active application window. Only events from the recorded app, or from the window the user
     * is actually in, may move the recording to another app. The window lookup runs only when the
     * event's package differs from the recorded app.
     */
    private fun isBackgroundWindowEvent(
        eventType: RecorderEventType,
        packageName: String,
        event: AccessibilityEvent,
        context: Context
    ): Boolean = isBackgroundWindowEvent(eventType, packageName, sessionOwner.foregroundPackage()) {
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
            ?: return@isBackgroundWindowEvent null
        val windowId = event.windowId
        if (windowId < 0) return@isBackgroundWindowEvent null
        val window = runCatching { service.windows.orEmpty().firstOrNull { it.id == windowId } }.getOrNull()
            ?: return@isBackgroundWindowEvent null
        isForegroundApplicationWindow(window.type, window.isActive, window.isFocused)
    }

    /**
     * Pure decision behind [isBackgroundWindowEvent]. [isForegroundAppWindow] answers whether the
     * event's window is the active application window, or null when the window can't be identified
     * (then the event is kept, as before).
     */
    internal fun isBackgroundWindowEvent(
        eventType: RecorderEventType,
        packageName: String,
        foregroundPackage: String?,
        isForegroundAppWindow: () -> Boolean?
    ): Boolean {
        if (eventType != RecorderEventType.CONTENT_CHANGE &&
            eventType != RecorderEventType.SCROLL &&
            eventType != RecorderEventType.FOCUS
        ) return false
        if (packageName.isBlank() || packageName == foregroundPackage) return false
        return isForegroundAppWindow() == false
    }

    private fun isInputMethodWindowShowing(context: Context): Boolean {
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
            ?: return false
        return runCatching {
            service.windows.orEmpty().any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
        }.getOrDefault(false)
    }

    private fun isFromInputMethodWindow(event: AccessibilityEvent, context: Context): Boolean {
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
            ?: return false
        val windowId = event.windowId
        if (windowId < 0) return false
        val window = runCatching { service.windows.orEmpty().firstOrNull { it.id == windowId } }.getOrNull()
            ?: return false
        return window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }

    internal fun isForegroundApplicationWindow(windowType: Int, isActive: Boolean, isFocused: Boolean): Boolean =
        windowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && (isActive || isFocused)

    internal fun classifyAccessibilityEventType(eventType: Int): RecorderEventType? = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> RecorderEventType.CLICK
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> RecorderEventType.LONG_PRESS
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> RecorderEventType.TEXT_CHANGE
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> RecorderEventType.SELECTION_CHANGE
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> RecorderEventType.FOCUS
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> RecorderEventType.SCROLL
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> RecorderEventType.APP_CHANGE
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> RecorderEventType.CONTENT_CHANGE
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> RecorderEventType.CONTENT_CHANGE
        else -> null
    }

    private fun RecorderEventType.toPassiveCaptureEvent(): PassiveCaptureEvent = when (this) {
        RecorderEventType.CLICK -> PassiveCaptureEvent.CLICK
        RecorderEventType.LONG_PRESS -> PassiveCaptureEvent.LONG_CLICK
        RecorderEventType.SCROLL -> PassiveCaptureEvent.SCROLL
        RecorderEventType.TEXT_CHANGE -> PassiveCaptureEvent.TEXT
        RecorderEventType.FOCUS -> PassiveCaptureEvent.FOCUS
        RecorderEventType.PRESS_ENTER -> PassiveCaptureEvent.IME_SUBMIT
        RecorderEventType.APP_CHANGE, RecorderEventType.CONTENT_CHANGE, RecorderEventType.SELECTION_CHANGE -> PassiveCaptureEvent.WINDOW
    }

    /** Records a physical Enter key without consuming it, so the foreground app still receives it. */
    fun onKeyEvent(event: KeyEvent, context: Context, activePackageName: String?): Boolean {
        if (!isRecording.value || activePackageName == context.packageName ||
            event.action != KeyEvent.ACTION_UP
        ) {
            return false
        }
        if (!isEnterKey(event.keyCode)) {
            // `adb shell input text` and physical keyboards can update Chrome's focused omnibox
            // through key events without TYPE_VIEW_TEXT_CHANGED. The tracked-field gate keeps
            // ordinary global key traffic from causing any accessibility-tree work.
            val refresh = refreshTrackedFocusedText(context, event.eventTime)
            handleFocusedTextRefresh(refresh, event.eventTime, checkScreenEffect = false)
            com.shortcuts.app.automation.android.capture.V2CaptureBridge.onKeyEvent(event)
            return false
        }
        cancelPendingTextFinalizer()
        sessionOwner.onTrackedEditableFocusLost()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.onKeyEvent(event)
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
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.onImeAction(
            label = null,
            uptimeMs = event.eventTime
        )
        updateRecordingHud()
        return false
    }

    private fun handleFocusedTextRefresh(
        result: FocusedTextSnapshotResult,
        occurredAtMillis: Long,
        checkScreenEffect: Boolean
    ) {
        when (result) {
            FocusedTextSnapshotResult.UPDATED -> scheduleTextFinalizer()
            FocusedTextSnapshotResult.FOCUS_LOST -> {
                // Queue the commit before the screen-effect check. This lets Engine v2 create its
                // IME candidate from the typed value before Chrome replaces it with the result URL.
                cancelPendingTextFinalizer()
                if (checkScreenEffect) {
                    com.shortcuts.app.automation.android.capture.V2CaptureBridge.onScreenChanged(
                        occurredAtMillis
                    )
                }
                updateRecordingHud()
            }
            FocusedTextSnapshotResult.NOT_TRACKED,
            FocusedTextSnapshotResult.UNCHANGED -> Unit
        }
    }

    fun processEvent(
        event: RecorderEvent,
        myPackageName: String,
        launcherPackage: String? = null,
        transientPackages: Set<String> = emptySet()
    ) {
        sessionOwner.processEvent(event, myPackageName, launcherPackage, transientPackages)
    }

    /** The overlay uses this rather than appending an Action so owner-level app/text rules remain intact. */
    fun recordCapturedGesture(event: RecorderEvent) {
        val service = AutomationAccessibilityService.instance ?: return
        sessionOwner.processCapturedGesture(
            event,
            service.packageName,
            resolveLauncherPackage(service),
            resolveTransientPackages(service)
        )
        updateRecordingHud()
        when (event.eventType) {
            RecorderEventType.CLICK, RecorderEventType.LONG_PRESS -> {
                silentTapSuggestionTracker.recordCapturedClick(event.occurredAtMillis)
                cancelPendingTextFinalizer()
            }
            RecorderEventType.SCROLL -> scheduleScrollFinalizer()
            else -> Unit
        }
    }

    /** Relay lifecycle callbacks use uptime to align with [AccessibilityEvent.eventTime]. */
    fun onCaptureRelayStarted(uptimeMs: Long) = captureSuppressionGate.onRelayStarted(uptimeMs)

    fun onCaptureRelayFinished(uptimeMs: Long) = captureSuppressionGate.onRelayFinished(uptimeMs)

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

    private fun cacheCaptureMode(context: Context) {
        recordingCaptureMode = RecorderCaptureModePreferences.mode(context)
        captureSuppressionGate = CaptureSuppressionGate(recordingCaptureMode == RecorderCaptureMode.OVERLAY)
    }

    private fun isOverlayCaptureMode(): Boolean = recordingCaptureMode == RecorderCaptureMode.OVERLAY

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
     * The user's DEFAULT home app is the launcher. `resolveActivity(home, MATCH_DEFAULT_ONLY)`
     * answers exactly that when a default is set, which is the normal case. Only when no default
     * exists does it return the platform's "android" chooser stub; then fall back to the first
     * installed home app. Taking the first `queryIntentActivities` result unconditionally was
     * wrong on phones with several launchers installed (the owner's Pixel has four): it could pick
     * a launcher the user never uses, so opening an app from the real home screen was never
     * recorded as an app-launch step. Requires the HOME `<queries>` entry in the manifest.
     */
    private fun resolveLauncherPackageViaResolveActivity(context: Context): String? = runCatching {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packageManager = context.applicationContext?.packageManager ?: context.packageManager ?: return@runCatching null
        val default = runCatching {
            packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()
        if (default != null && default != "android" && default != context.packageName) return@runCatching default

        val candidates = try {
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            emptyList()
        }
        candidates
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it != "android" && it != context.packageName && it != "com.android.settings" }
    }.getOrNull()

    /** The active IME is resolved dynamically because keyboard package names vary by device. */
    private fun resolveTransientPackages(context: Context): Set<String> {
        val enabledImePackages = runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                .orEmpty()
        }.getOrDefault(emptyList())
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
        val inputWindowPackages = runCatching {
            buildSet {
                service?.windows.orEmpty()
                    .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .forEach { window ->
                        val root = runCatching { window.root }.getOrNull() ?: return@forEach
                        try {
                            root.packageName?.toString()?.let { add(it) }
                        } finally {
                            root.recycle()
                        }
                    }
            }
        }.getOrDefault(emptySet())
        return enabledImePackages.toSet() + inputWindowPackages +
            setOfNotNull(resolveCurrentInputMethodPackage(context))
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
        val handler = scrollFinalizerHandler
        if (handler != null) {
            pendingScrollFinalizer?.let(handler::removeCallbacks)
        }
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
        val handler = textFinalizerHandler
        if (handler != null) {
            pendingTextFinalizer?.let(handler::removeCallbacks)
        }
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
    internal fun refreshTrackedFocusedText(
        context: Context,
        occurredAtMillis: Long
    ): FocusedTextSnapshotResult {
        val tracked = sessionOwner.trackedFocusedEditableField()
            ?: return FocusedTextSnapshotResult.NOT_TRACKED
        val service = context as? AutomationAccessibilityService
            ?: AutomationAccessibilityService.instance
            ?: return FocusedTextSnapshotResult.UNCHANGED
        val windows = runCatching { service.windows.orEmpty() }.getOrDefault(emptyList())
        val keyboardVisible = windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val roots = runCatching {
            buildList {
                windows
                    .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { it.id == tracked.windowId }
                    .forEach { window ->
                        val root = runCatching { window.root }.getOrNull() ?: return@forEach
                        if (runCatching { root.packageName?.toString() == tracked.packageName }.getOrDefault(false)) {
                            add(root)
                        } else {
                            root.recycle()
                        }
                    }
            }
        }.getOrDefault(emptyList()).ifEmpty {
            listOfNotNull(service.getRootNode()).filter { root ->
                val matchesTrackedPackage = runCatching {
                    root.packageName?.toString() == tracked.packageName
                }.getOrDefault(false)
                if (!matchesTrackedPackage) root.recycle()
                matchesTrackedPackage
            }
        }
        var inspectedApplicationRoot = false
        for ((index, root) in roots.withIndex()) {
            var focusedNode: AccessibilityNodeInfo? = null
            try {
                val inputFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                inspectedApplicationRoot = true
                focusedNode = inputFocus ?: continue
                if (!focusedNode.isFocused || !isEditableNode(focusedNode) || isSensitiveTextNode(focusedNode)) {
                    continue
                }

                val liveText = readableEditableText(focusedNode)
                val liveEvent = RecorderEvent(
                    eventType = RecorderEventType.TEXT_CHANGE,
                    packageName = focusedNode.packageName?.toString() ?: tracked.packageName,
                    sourceText = liveText,
                    sourceContentDescription = focusedNode.contentDescription?.toString()
                        ?: tracked.sourceContentDescription,
                    sourceViewId = focusedNode.viewIdResourceName ?: tracked.sourceViewId,
                    enteredText = liveText,
                    sourceClassName = focusedNode.className?.toString() ?: tracked.sourceClassName,
                    sourceIsEditable = true,
                    occurredAtMillis = occurredAtMillis,
                    windowId = focusedNode.windowId.takeIf { it >= 0 } ?: tracked.windowId,
                    sourceIsFocused = true
                )
                val result = sessionOwner.processLiveFocusedTextSnapshot(liveEvent, keyboardVisible)
                if (result == FocusedTextSnapshotResult.UPDATED) {
                    com.shortcuts.app.automation.android.capture.V2CaptureBridge.onTextChanged(liveEvent)
                } else if (result == FocusedTextSnapshotResult.UNCHANGED && keyboardVisible) {
                    // Still in the field with the keyboard up (often reading suggestions): the
                    // submit-inference window restarts from here, not from the last keystroke.
                    com.shortcuts.app.automation.android.capture.V2CaptureBridge.onTypedFieldStillFocused(
                        packageName = liveEvent.packageName,
                        resourceId = liveEvent.sourceViewId,
                        className = liveEvent.sourceClassName,
                        text = liveText,
                        uptimeMs = occurredAtMillis
                    )
                }
                roots.drop(index + 1).forEach { it.recycle() }
                return result
            } catch (_: Exception) {
                // A stale app window must not prevent trying the next application window.
            } finally {
                if (focusedNode !== root) focusedNode?.recycle()
                root.recycle()
            }
        }
        return if (inspectedApplicationRoot) {
            sessionOwner.onTrackedEditableFocusLost()
        } else {
            FocusedTextSnapshotResult.UNCHANGED
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

    private fun resolveImeSubmitLabel(event: AccessibilityEvent): String? {
        val source = event.source
        val labels = event.text.map { it.toString() } + listOfNotNull(
            event.contentDescription?.toString(),
            source?.text?.toString(),
            source?.contentDescription?.toString()
        )
        source?.recycle()
        return labels.firstOrNull { label ->
            label.trim().lowercase() in setOf("enter", "go", "search", "send", "done", "next")
        }
    }

    private fun isImeSubmitEvent(event: AccessibilityEvent): Boolean =
        resolveImeSubmitLabel(event) != null

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

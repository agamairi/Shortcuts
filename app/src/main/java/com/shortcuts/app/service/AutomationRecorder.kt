package com.shortcuts.app.service

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.util.AccessibilityStatusChecker
import kotlinx.coroutines.flow.StateFlow
import kotlin.collections.ArrayDeque

enum class RecorderEventType { CLICK, TEXT_CHANGE, SCROLL }

data class RecorderEvent(
    val eventType: RecorderEventType,
    val packageName: String,
    val sourceText: String?,
    val sourceContentDescription: String?,
    val sourceViewId: String?,
    val enteredText: String,
    val sourceClassName: String? = null,
    val screenX: Int? = null,
    val screenY: Int? = null,
    /** AccessibilityEvent.eventTime when available; used to exclude the stop action. */
    val occurredAtMillis: Long = Long.MIN_VALUE
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

    val isRecording: StateFlow<Boolean> = sessionOwner.isRecording
    val recordedActions = sessionOwner.recordedActions

    fun restoreSession(context: Context) {
        configureStore(context)
        sessionOwner.restore()
    }

    fun setRecordingForTest(isRecording: Boolean) {
        disconnectMonitor?.cancelPendingDisconnect()
        sessionOwner.replaceStore(InMemoryRecorderSessionStore())
        sessionOwner.setRecordingForTest(isRecording)
    }

    fun startRecording(context: Context) {
        configureStore(context)
        disconnectMonitor?.cancelPendingDisconnect()
        Log.d(TAG, "Starting recording")
        sessionOwner.start()
        RecorderSessionService.start(context)
    }

    fun stopRecording(context: Context) {
        disconnectMonitor?.cancelPendingDisconnect()
        Log.d(TAG, "Stopping recording")
        sessionOwner.stop()
        RecorderSessionService.stop(context)
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
                    sessionOwner.stop()
                    RecorderSessionService.stop(appContext)
                    RecorderSessionNotifier.showAccessibilityDisconnected(appContext)
                }
            }
        )
    }

    /** Cancels a pending destruction confirmation when Android binds a replacement instance. */
    fun onAccessibilityServiceConnected() {
        disconnectMonitor?.onServiceConnected()
    }

    fun clearRecording() {
        disconnectMonitor?.cancelPendingDisconnect()
        sessionOwner.clear()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent, context: Context) {
        configureStore(context)
        val rawNode = event.source ?: return
        
        val eventType = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> RecorderEventType.CLICK
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> RecorderEventType.TEXT_CHANGE
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> RecorderEventType.SCROLL
            else -> {
                rawNode.recycle()
                return
            }
        }

        val node = AndroidRecorderNode(rawNode)
        val sourceText: String?
        val sourceContentDescription: String?
        val sourceViewId: String?

        if (eventType == RecorderEventType.CLICK) {
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
        val sourceClassName = rawNode.className?.toString()
        node.free()

        val recorderEvent = RecorderEvent(
            eventType = eventType,
            packageName = event.packageName?.toString() ?: "",
            sourceText = sourceText,
            sourceContentDescription = sourceContentDescription,
            sourceViewId = sourceViewId,
            enteredText = event.text.joinToString(""),
            sourceClassName = sourceClassName,
            screenX = screenX,
            screenY = screenY,
            occurredAtMillis = event.eventTime
        )
        sessionOwner.processEvent(recorderEvent, context.packageName)
    }

    fun processEvent(event: RecorderEvent, myPackageName: String) {
        sessionOwner.processEvent(event, myPackageName)
    }

    @Synchronized
    private fun configureStore(context: Context) {
        if (!usesPersistentStore) {
            sessionOwner.replaceStore(RecorderSessionStorePreferences(context.applicationContext))
            usesPersistentStore = true
        }
    }

    @Synchronized
    private fun accessibilityDisconnectMonitor(): AccessibilityServiceDisconnectMonitor {
        return disconnectMonitor ?: AccessibilityServiceDisconnectMonitor(
            scheduler = AndroidMainThreadGracePeriodScheduler(),
            isRecording = { isRecording.value }
        ).also { disconnectMonitor = it }
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

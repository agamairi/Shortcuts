package com.shortcuts.app.automation.replay

import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.UiOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface UiEnvironment {
    fun snapshotWindows(): WindowSetSnapshot
    fun events(): Flow<UiEnvironmentEvent>
    /** False when the accessibility service has already disconnected before a wait begins. */
    fun isAccessibilityAvailable(): Boolean = true
    /** Activity supplied by the Android accessibility adapter when it is observable. */
    fun currentActivityClass(windowId: Int): String? = null
    suspend fun perform(request: UiActionRequest): DispatchResult
}

sealed interface UiEnvironmentEvent {
    val uptimeMs: Long

    data class WindowsChanged(override val uptimeMs: Long) : UiEnvironmentEvent
    data class ContentChanged(override val uptimeMs: Long, val windowId: Int?) : UiEnvironmentEvent
    data class FocusChanged(override val uptimeMs: Long, val windowId: Int?, val nodeId: Int?) : UiEnvironmentEvent
    data class AccessibilityDisconnected(override val uptimeMs: Long) : UiEnvironmentEvent
}

data class UiActionRequest(
    val operation: UiOperation,
    val windowId: Int?,
    val nodeId: Int?,
    val gesture: CapturedGesture? = null,
    /** Semantic accessibility action, when [dispatchMethod] is [DispatchMethod.SEMANTIC_ACTION]. */
    val semanticAction: NodeAction? = null,
    /** Current-screen points for gesture dispatch; never use recorded display coordinates directly. */
    val gesturePoints: List<IntPoint> = emptyList(),
    val text: String? = null,
    val imeAction: ImeActionKind? = null,
    val globalAction: String? = null,
    val dispatchMethod: DispatchMethod = DispatchMethod.SEMANTIC_ACTION
)

sealed interface DispatchResult {
    data class Accepted(val method: DispatchMethod) : DispatchResult
    data class Rejected(val reason: String) : DispatchResult
}

enum class DispatchMethod { SEMANTIC_ACTION, GESTURE, GLOBAL_ACTION }

/**
 * A cancellation boundary owned by the caller (normally the foreground execution service).
 * Its flow must emit promptly when cancellation is requested so waits do not wait for a poll.
 */
interface ReplayCancellationSignal {
    fun isCancelled(): Boolean
    fun events(): Flow<Unit>

    data object None : ReplayCancellationSignal {
        override fun isCancelled(): Boolean = false
        override fun events(): Flow<Unit> = emptyFlow()
    }
}

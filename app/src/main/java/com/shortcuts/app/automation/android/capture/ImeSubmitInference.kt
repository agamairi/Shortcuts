package com.shortcuts.app.automation.android.capture

import com.shortcuts.app.automation.capture.CapturedImeAction
import com.shortcuts.app.automation.capture.CapturedTextCommit
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.WindowType

/**
 * Result of an inferred IME submission.
 *
 * @property action The inferred [CapturedImeAction] to deliver to the compiler.
 * @property reason Diagnostic reason string for logging ("activity_change", "new_window", "field_cleared", etc.).
 * @property pendingCommit Non-null if text was typed in the field but not yet committed prior to submission.
 */
data class InferredImeSubmit(
    val action: CapturedImeAction,
    val reason: String,
    val pendingCommit: CapturedTextCommit? = null
)

/**
 * Pure heuristic to resolve the [ImeActionKind] from field properties, nearby buttons, and inference context.
 */
fun inferImeActionKind(
    fieldNode: NodeSnapshot,
    windowSnapshot: WindowSnapshot?,
    reason: String = "",
    targetActivityClass: String? = null
): ImeActionKind {
    // 1. Direct field hints, labels, content descriptions, resource IDs
    val fieldTokens = listOfNotNull(
        fieldNode.hintText,
        fieldNode.contentDescription,
        fieldNode.resourceId,
        fieldNode.stateDescription
    ).joinToString(" ").lowercase()

    when {
        fieldTokens.contains("search") || fieldTokens.contains("find") -> return ImeActionKind.SEARCH
        fieldTokens.contains("message") || fieldTokens.contains("send") -> return ImeActionKind.SEND
        fieldTokens.contains("go") -> return ImeActionKind.GO
        fieldTokens.contains("done") -> return ImeActionKind.DONE
        fieldTokens.contains("next") -> return ImeActionKind.NEXT
    }

    // 2. Nearby actionable/clickable buttons in the same window (e.g. Send button, Search button)
    if (windowSnapshot != null) {
        val buttons = windowSnapshot.nodes.values.filter { it.isActionable || it.clickable }
        for (btn in buttons) {
            val btnTokens = listOfNotNull(btn.text, btn.contentDescription, btn.resourceId)
                .joinToString(" ").lowercase()
            when {
                btnTokens.contains("search") || btnTokens.contains("find") -> return ImeActionKind.SEARCH
                btnTokens.contains("send") || btnTokens.contains("submit") -> return ImeActionKind.SEND
                btnTokens.contains("done") -> return ImeActionKind.DONE
                btnTokens.contains("go") -> return ImeActionKind.GO
                btnTokens.contains("next") -> return ImeActionKind.NEXT
            }
        }
    }

    // 3. Reason semantics: field clearing right after typing is the classic "sent" pattern
    if (reason.startsWith("field_cleared")) {
        return ImeActionKind.SEND
    }

    // 4. Target activity class or reason indicator
    val targetTokens = listOfNotNull(targetActivityClass, reason).joinToString(" ").lowercase()
    when {
        targetTokens.contains("search") -> return ImeActionKind.SEARCH
        targetTokens.contains("send") || targetTokens.contains("message") -> return ImeActionKind.SEND
    }

    return ImeActionKind.UNSPECIFIED
}

/**
 * Pure, JVM-testable inference engine for soft-keyboard submissions (Search, Send, Go, Done).
 *
 * Soft keyboard action keys (Gboard Search/Send) are invisible to accessibility services:
 * - They go through InputConnection rather than the input filter (no onKeyEvent).
 * - Gboard emits no TYPE_VIEW_CLICKED for action keys.
 * - Hardware Enter keys or `adb shell input keyevent KEYCODE_ENTER` might also bypass onKeyEvent.
 * - The capture overlay shrinks above the keyboard and does not cover it.
 *
 * Therefore, submission must be inferred from its observable effects:
 * 1. An application window change to a different activity or new window/dialog for the package.
 * 2. The field's text becoming empty (the "sent" pattern).
 *
 * Both must occur within [maxDelayMs] (2 seconds) after a text commit or typing, without any
 * intervening overlay tap or navigation key (BACK, HOME, RECENTS).
 *
 * Keyboard hide alone or same-screen content updates (e.g. autocomplete suggestions) must NEVER
 * synthesize a submission.
 */
class ImeSubmitInference(
    private val maxDelayMs: Long = 2_000L
) {
    data class Candidate(
        val fieldNode: NodeSnapshot,
        val windowSnapshot: WindowSnapshot,
        val initialActivityClass: String?,
        val text: String,
        val timestampMs: Long,
        val hasCommitted: Boolean
    )

    var candidate: Candidate? = null
        private set

    /**
     * Records a committed field edit as an active candidate for IME submission inference.
     */
    fun onTextCommit(commit: CapturedTextCommit, activityClass: String? = null) {
        candidate = Candidate(
            fieldNode = commit.fieldNode,
            windowSnapshot = commit.windowSnapshot,
            initialActivityClass = activityClass,
            text = commit.finalText,
            timestampMs = commit.committedAtUptimeMs,
            hasCommitted = true
        )
    }

    /**
     * Records typed text in a field prior to commit, allowing inference if submitted before commit.
     */
    fun onTextTyped(
        fieldNode: NodeSnapshot,
        text: String,
        windowSnapshot: WindowSnapshot,
        activityClass: String? = null,
        uptimeMs: Long
    ) {
        if (text.isEmpty()) return
        candidate = Candidate(
            fieldNode = fieldNode,
            windowSnapshot = windowSnapshot,
            initialActivityClass = activityClass,
            text = text,
            timestampMs = uptimeMs,
            hasCommitted = false
        )
    }

    /**
     * Inspects an activity transition to determine if it indicates submission from the active candidate.
     */
    fun onActivityChanged(
        toPackage: String,
        activityClass: String?,
        uptimeMs: Long
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }
        if (toPackage != current.windowSnapshot.packageName) {
            // Switched to another app (e.g. launcher or system UI), not a result screen for that package
            return null
        }
        val isDifferentActivity = looksLikeActivityClass(activityClass) &&
            activityClass != current.initialActivityClass
        if (isDifferentActivity) {
            return emitInference(
                current = current,
                reason = "activity_change to $activityClass",
                targetActivityClass = activityClass,
                uptimeMs = uptimeMs
            )
        }
        return null
    }

    /**
     * Inspects a window change (e.g. dialog or new window appearing for the package).
     */
    fun onWindowChanged(
        toPackage: String,
        windowId: Int,
        windowType: WindowType?,
        className: String?,
        uptimeMs: Long
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }
        if (toPackage != current.windowSnapshot.packageName) {
            return null
        }
        if (windowType == WindowType.INPUT_METHOD || windowType == WindowType.ACCESSIBILITY_OVERLAY) {
            return null
        }
        val isNewWindow = windowId != current.windowSnapshot.windowId
        val isDialog = className != null && (className.contains("Dialog") || className.endsWith("Dialog"))
        if (isNewWindow || isDialog) {
            val reason = if (isDialog) "dialog: $className" else "new_window: $windowId"
            return emitInference(
                current = current,
                reason = reason,
                targetActivityClass = className,
                uptimeMs = uptimeMs
            )
        }
        return null
    }

    /**
     * Inspects a field clearing event (the "sent" pattern).
     */
    fun onFieldCleared(
        fieldNodeId: Int? = null,
        resourceId: String? = null,
        uptimeMs: Long
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }
        val matchesField = (fieldNodeId != null && fieldNodeId == current.fieldNode.nodeId) ||
            (resourceId != null && resourceId == current.fieldNode.resourceId) ||
            (fieldNodeId == null && resourceId == null)
        if (matchesField) {
            return emitInference(
                current = current,
                reason = "field_cleared",
                targetActivityClass = null,
                uptimeMs = uptimeMs
            )
        }
        return null
    }

    /**
     * Inspects text updates for field clearing.
     */
    fun onFieldTextChanged(
        fieldNode: NodeSnapshot,
        newText: String,
        uptimeMs: Long
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }
        val matchesField = (fieldNode.nodeId == current.fieldNode.nodeId) ||
            (fieldNode.resourceId != null && fieldNode.resourceId == current.fieldNode.resourceId)
        if (!matchesField) return null

        if (newText.isEmpty() && current.text.isNotEmpty()) {
            return emitInference(
                current = current,
                reason = "field_cleared",
                targetActivityClass = null,
                uptimeMs = uptimeMs
            )
        }
        return null
    }

    /**
     * Intervening user interaction (e.g. tap) cancels any pending inference.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onOverlayInteraction(uptimeMs: Long? = null) {
        candidate = null
    }

    /**
     * Intervening key event cancels any pending inference.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onKeyEvent(keyCode: Int? = null, uptimeMs: Long? = null) {
        candidate = null
    }

    /**
     * Intervening global navigation action (BACK, HOME, RECENTS) cancels pending inference.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onGlobalAction(kind: String? = null, uptimeMs: Long? = null) {
        candidate = null
    }

    /**
     * Keyboard hiding alone must NEVER synthesize submission (Back button also hides the keyboard).
     */
    fun onKeyboardHidden() {
        // Intentionally no-op
    }

    /**
     * Same-screen content updates (e.g. autocomplete suggestions appearing) must NEVER synthesize submission.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onContentChanged(snapshot: WindowSetSnapshot? = null, uptimeMs: Long? = null): InferredImeSubmit? {
        // Intentionally no-op
        return null
    }

    fun reset() {
        candidate = null
    }

    private fun emitInference(
        current: Candidate,
        reason: String,
        targetActivityClass: String?,
        uptimeMs: Long
    ): InferredImeSubmit {
        candidate = null
        val kind = inferImeActionKind(current.fieldNode, current.windowSnapshot, reason, targetActivityClass)
        val pendingCommit = if (!current.hasCommitted) {
            createCapturedTextCommit(
                fieldNode = current.fieldNode,
                finalText = current.text,
                windowSnapshot = current.windowSnapshot,
                committedAtUptimeMs = current.timestampMs
            )
        } else null

        val actionTime = maxOf(current.timestampMs + 1, uptimeMs)
        val action = createCapturedImeAction(
            fieldNode = current.fieldNode,
            kind = kind,
            windowSnapshot = current.windowSnapshot,
            actionAtUptimeMs = actionTime
        )
        return InferredImeSubmit(
            action = action,
            reason = reason,
            pendingCommit = pendingCommit
        )
    }
}

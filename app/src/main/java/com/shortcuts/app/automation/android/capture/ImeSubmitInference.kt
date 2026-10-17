package com.shortcuts.app.automation.android.capture

import com.shortcuts.app.automation.capture.CapturedImeAction
import com.shortcuts.app.automation.capture.CapturedTextCommit
import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.fingerprint.ScreenFingerprinter
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.NodeAction
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
 * 1. An application window change to a different activity for the package.
 * 2. The focused field's committed non-empty value becoming empty in one text event.
 * 3. The field losing input focus (or the IME disappearing) while the same app changes screen
 *    substantially, including an omnibox replacing the typed query with a result URL.
 *
 * Both must occur within [maxDelayMs] (2 seconds) after a text commit or typing, without any
 * intervening overlay tap or navigation key (BACK, HOME, RECENTS).
 *
 * Keyboard hide alone or same-screen content updates (e.g. autocomplete suggestions) must NEVER
 * synthesize a submission.
 */
class ImeSubmitInference(
    private val maxDelayMs: Long = 2_000L,
    private val screenFingerprinter: ScreenFingerprinter = DefaultScreenFingerprinter(),
    private val screenChangeThreshold: Double = 0.80
) {
    data class Candidate(
        val fieldNode: NodeSnapshot,
        val windowSnapshot: WindowSnapshot,
        val initialActivityClass: String?,
        val text: String,
        val timestampMs: Long,
        val hasCommitted: Boolean,
        val initialScreenFingerprint: com.shortcuts.app.automation.model.ScreenFingerprint,
        val keyboardWasVisible: Boolean,
        /** A changed non-empty value means the user is editing/deleting, not submitting. */
        val sawIntermediateTextChange: Boolean = false,
        /** Set when a tap is captured inside the field bounds (e.g. tapping the clear "X" button). */
        val hasTapInsideField: Boolean = false
    )

    var candidate: Candidate? = null
        private set

    /**
     * Records a committed field edit as an active candidate for IME submission inference.
     */
    fun onTextCommit(
        commit: CapturedTextCommit,
        activityClass: String? = null,
        snapshot: WindowSetSnapshot? = null
    ) {
        if (commit.finalText.isEmpty() || commit.fieldNode.nodeId < 0 || !commit.fieldNode.focused) {
            candidate = null
            return
        }
        candidate = Candidate(
            fieldNode = commit.fieldNode,
            windowSnapshot = commit.windowSnapshot,
            initialActivityClass = activityClass,
            text = commit.finalText,
            timestampMs = commit.committedAtUptimeMs,
            hasCommitted = true,
            initialScreenFingerprint = screenFingerprinter.fingerprint(commit.windowSnapshot, activityClass),
            keyboardWasVisible = snapshot?.windows?.any { it.type == WindowType.INPUT_METHOD } == true
        )
    }

    /** Uncommitted typing is deliberately not eligible for effect-only inference. */
    fun onTextTyped(
        fieldNode: NodeSnapshot,
        text: String,
        windowSnapshot: WindowSnapshot,
        activityClass: String? = null,
        uptimeMs: Long
    ) {
        // Kept as a compatibility hook for the Android boundary. No candidate is created.
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
        val isDifferentActivity = current.initialActivityClass != null &&
            looksLikeActivityClass(activityClass) && activityClass != current.initialActivityClass
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

    /** Window/popup changes are not submit evidence. Only a different Activity is eligible. */
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
        return null
    }

    /**
     * Inspects a field clearing event (the "sent" pattern).
     */
    fun onFieldCleared(
        fieldNodeId: Int? = null,
        resourceId: String? = null,
        uptimeMs: Long,
        fieldBounds: com.shortcuts.app.automation.core.tree.IntRect? = null
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }
        val matchesField = (!resourceId.isNullOrBlank() && resourceId == current.fieldNode.resourceId) ||
            (fieldBounds != null && fieldBounds == current.fieldNode.boundsInScreen) ||
            (fieldNodeId != null && fieldNodeId == current.fieldNode.nodeId)
        if (matchesField) {
            if (current.text.length < 2 || current.hasTapInsideField) {
                candidate = null
                return null
            }
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
        uptimeMs: Long,
        windowSnapshot: WindowSnapshot? = null
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }
        val matchesField = matchesField(current, fieldNode, windowSnapshot)
        if (!matchesField) return null

        if (!fieldNode.focused) return null
        if (newText.isNotEmpty() && newText != current.text) {
            candidate = current.copy(sawIntermediateTextChange = true)
            return null
        }
        if (newText.isEmpty() && current.text.isNotEmpty() && !current.sawIntermediateTextChange) {
            if (current.text.length < 2 || current.hasTapInsideField) {
                candidate = null
                return null
            }
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
     * Infers a same-activity keyboard submit from a conservative combination of effects.
     * A focus/IME transition is necessary but insufficient: the application screen must also
     * change substantially, or the submitted field must replace the committed value.
     */
    fun onScreenEffect(
        snapshot: WindowSetSnapshot,
        uptimeMs: Long,
        activityClass: String? = null
    ): InferredImeSubmit? {
        val current = candidate ?: return null
        if (uptimeMs - current.timestampMs > maxDelayMs) {
            candidate = null
            return null
        }

        val appWindow = snapshot.windows
            .asSequence()
            .filter { it.packageName == current.windowSnapshot.packageName }
            .filterNot { it.type == WindowType.INPUT_METHOD || it.type == WindowType.ACCESSIBILITY_OVERLAY }
            .sortedWith(
                compareByDescending<WindowSnapshot> { it.isActive || it.isFocused }
                    .thenByDescending { it.layer }
            )
            .firstOrNull() ?: return null
        val currentField = appWindow.nodes.values.firstOrNull {
            matchesField(current, it, appWindow)
        }
        val focusLost = currentField == null || !currentField.focused
        val keyboardVisible = snapshot.windows.any { it.type == WindowType.INPUT_METHOD }
        val keyboardWentAway = current.keyboardWasVisible && !keyboardVisible
        if (!focusLost && !keyboardWentAway) return null

        // Still typing: the field keeps focus and its text only grew or shrank from what was committed.
        // (Seen with YouTube Music: suggestions changed and the keyboard hid mid-word, and "lofi b" was
        // recorded as a submitted search.) Text that extends what was typed is never a replacement.
        val liveText = currentField?.text
        val stillTyping = currentField != null && currentField.focused && liveText != null &&
            liveText != current.text && (liveText.startsWith(current.text) || current.text.startsWith(liveText))
        if (stillTyping) return null
        val replacementText = liveText
            ?.takeUnless {
                it.isBlank() || it == current.text || it == currentField.hintText || it.startsWith(current.text)
            }
        val textWasReplaced = replacementText != null
        val currentFingerprint = screenFingerprinter.fingerprint(appWindow, activityClass)
        val screenChanged = screenFingerprinter.similarity(
            current.initialScreenFingerprint,
            currentFingerprint
        ) < screenChangeThreshold
        if (!screenChanged && !textWasReplaced) return null

        // Effect-only inference is deliberately limited to fields with IME-action evidence.
        // Android exposes IME_ENTER directly on many fields; semantic search/send/go identifiers
        // are the fallback for WebView/Compose fields that omit that action.
        val hasImeActionEvidence = NodeAction.IME_ENTER in current.fieldNode.actions ||
            inferImeActionKind(current.fieldNode, current.windowSnapshot) != ImeActionKind.UNSPECIFIED
        if (!hasImeActionEvidence) return null

        return emitInference(
            current = current,
            reason = "focus_loss_with_screen_change",
            targetActivityClass = activityClass,
            uptimeMs = uptimeMs
        )
    }

    private fun matchesField(
        current: Candidate,
        fieldNode: NodeSnapshot,
        windowSnapshot: WindowSnapshot?
    ): Boolean {
        if (windowSnapshot != null && windowSnapshot.packageName != current.windowSnapshot.packageName) {
            return false
        }
        if (!fieldNode.editable) return false
        val curRes = current.fieldNode.resourceId
        val newRes = fieldNode.resourceId
        if (!curRes.isNullOrBlank() && !newRes.isNullOrBlank()) {
            return curRes == newRes
        }
        return current.fieldNode.boundsInScreen == fieldNode.boundsInScreen
    }

    /**
     * Intervening user interaction (e.g. tap) cancels pending inference unless it lands
     * inside the field's bounds (e.g. tapping a clear "X" icon).
     */
    fun onOverlayInteraction(x: Int? = null, y: Int? = null, uptimeMs: Long? = null) {
        val current = candidate ?: return
        if (x != null && y != null && current.fieldNode.boundsInScreen.contains(x, y)) {
            candidate = current.copy(hasTapInsideField = true)
        } else {
            candidate = null
        }
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
            actionAtUptimeMs = actionTime,
            inferred = true
        )
        return InferredImeSubmit(
            action = action,
            reason = reason,
            pendingCommit = pendingCommit
        )
    }
}

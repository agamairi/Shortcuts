package com.shortcuts.app.automation.android.capture

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.view.KeyEvent
import com.shortcuts.app.automation.capture.CapturedAppSwitch
import com.shortcuts.app.automation.capture.CapturedGlobalAction
import com.shortcuts.app.automation.capture.CapturedImeAction
import com.shortcuts.app.automation.capture.CapturedInteraction
import com.shortcuts.app.automation.capture.CapturedTextCommit
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.ScreenFingerprint
import com.shortcuts.app.automation.model.TextTokenizer
import com.shortcuts.app.automation.model.TimedPoint
import com.shortcuts.app.data.Action
import com.shortcuts.app.service.capture.TouchAction
import com.shortcuts.app.service.capture.TouchSample
import java.security.MessageDigest

/**
 * Salted SHA-256 [TextTokenizer] storing salt in private SharedPreferences.
 */
class Sha256TextTokenizer(private val salt: String) : TextTokenizer {
    override fun tokenize(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest("$salt:$text".toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Pure mapping from IME label string (from key label, contentDescription, etc.)
 * to Engine v2 [ImeActionKind]. Physical Enter maps to [ImeActionKind.UNSPECIFIED].
 */
fun mapImeLabelToActionKind(label: String?): ImeActionKind {
    return when (label?.trim()?.lowercase()) {
        "search" -> ImeActionKind.SEARCH
        "send" -> ImeActionKind.SEND
        "done" -> ImeActionKind.DONE
        "go" -> ImeActionKind.GO
        "next" -> ImeActionKind.NEXT
        else -> ImeActionKind.UNSPECIFIED
    }
}

/**
 * Pure mapping from Android KeyEvent keycode to Engine v2 global action string.
 */
fun mapKeyCodeToGlobalAction(keyCode: Int): String? {
    return when (keyCode) {
        KeyEvent.KEYCODE_BACK -> "BACK"
        KeyEvent.KEYCODE_HOME -> "HOME"
        KeyEvent.KEYCODE_APP_SWITCH -> "RECENTS"
        else -> null
    }
}

/**
 * Heuristic to detect if a class name looks like an Android Activity.
 * Only accepts class names whose simple name ends with "Activity".
 */
fun looksLikeActivityClass(className: CharSequence?): Boolean {
    if (className.isNullOrBlank()) return false
    val str = className.toString().trim()
    if (!str.contains('.')) return false
    if (str.startsWith("android.view.") || str.startsWith("android.widget.")) return false
    val simpleName = str.substringAfterLast('.')
    return simpleName.endsWith("Activity")
}

/**
 * Checks whether a class name represents a transient UI overlay, dialog, popup, snackbar,
 * or toast that should not overwrite the recorded activity class.
 */
fun isIgnoredUiClass(className: CharSequence?): Boolean {
    if (className.isNullOrBlank()) return true
    val str = className.toString().trim()
    val simpleName = str.substringAfterLast('.')
    if (simpleName.contains("Dialog", ignoreCase = true) ||
        simpleName.contains("Popup", ignoreCase = true) ||
        simpleName.contains("Snackbar", ignoreCase = true) ||
        simpleName.contains("Toast", ignoreCase = true) ||
        simpleName.contains("BottomSheet", ignoreCase = true)
    ) {
        return true
    }
    if (str.startsWith("android.view.") || str.startsWith("android.widget.") ||
        str.startsWith("androidx.compose.") || str.startsWith("com.google.android.material.")
    ) {
        if (!simpleName.endsWith("Activity")) return true
    }
    return false
}

/**
 * Resolves home launcher package names from a list of package strings.
 */
fun resolveHomePackages(packages: List<String?>): Set<String> {
    return packages.asSequence()
        .mapNotNull { it?.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

/**
 * Queries all installed home applications using CATEGORY_HOME.
 */
fun queryHomePackages(packageManager: PackageManager): Set<String> = runCatching {
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_HOME)
    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, 0)
    }
    resolveHomePackages(list.mapNotNull { it.activityInfo?.packageName })
}.getOrDefault(emptySet())

/**
 * Single pure decision function for determining whether to save Engine v2 JSON or legacy v0.7 JSON.
 *
 * Saves v2 JSON when:
 * 1. [engineV2Enabled] is true,
 * 2. [v2WorkflowJson] is non-null,
 * 3. The user did not edit, delete, or reorder steps in the review screen ([originalActions] == [currentActions]).
 *
 * Otherwise returns the v0.7 actions JSON generated by [v07ActionsJson].
 */
fun selectSaveActionsJson(
    engineV2Enabled: Boolean,
    v2WorkflowJson: String?,
    originalActions: List<com.shortcuts.app.data.Action>,
    currentActions: List<com.shortcuts.app.data.Action>,
    v07ActionsJson: () -> String
): String {
    val unedited = originalActions == currentActions
    return if (engineV2Enabled && v2WorkflowJson != null && unedited) {
        v2WorkflowJson
    } else {
        v07ActionsJson()
    }
}

/**
 * Pure mapping from classified touch samples to [CapturedInteraction].
 */
fun createCapturedInteraction(
    kind: GestureKind,
    samples: List<TouchSample>,
    beforeSnapshot: WindowSetSnapshot,
    ownPackage: String,
    precomputedFingerprint: ScreenFingerprint? = null
): CapturedInteraction {
    val downTime = samples.firstOrNull()?.uptimeMs ?: 0L
    val upTime = samples.lastOrNull { it.action == TouchAction.UP }?.uptimeMs
        ?: samples.lastOrNull()?.uptimeMs
        ?: downTime
    val points = samples.map { sample ->
        TimedPoint(
            displayPx = IntPoint(sample.x.toInt(), sample.y.toInt()),
            elapsedMs = (sample.uptimeMs - downTime).coerceAtLeast(0L)
        )
    }
    val first = samples.firstOrNull()
    val hitResult = if (first != null) {
        beforeSnapshot.hitTest(first.x.toInt(), first.y.toInt(), excludePackages = setOf(ownPackage))
    } else null

    return CapturedInteraction(
        kind = kind,
        points = points,
        downUptimeMs = downTime,
        upUptimeMs = upTime,
        beforeSnapshot = beforeSnapshot,
        hitNodeId = hitResult?.target?.nodeId,
        hitWindowId = hitResult?.window?.windowId,
        precomputedFingerprint = precomputedFingerprint
    )
}

/**
 * Pure creation of [CapturedTextCommit]. Returns null if the target field is sensitive (password).
 */
fun createCapturedTextCommit(
    fieldNode: NodeSnapshot,
    finalText: String,
    windowSnapshot: WindowSnapshot,
    committedAtUptimeMs: Long,
    precomputedFingerprint: ScreenFingerprint? = null
): CapturedTextCommit? {
    if (fieldNode.password) return null
    return CapturedTextCommit(
        fieldNode = fieldNode,
        finalText = finalText,
        windowSnapshot = windowSnapshot,
        committedAtUptimeMs = committedAtUptimeMs,
        precomputedFingerprint = precomputedFingerprint
    )
}

/**
 * Pure creation of [CapturedImeAction].
 */
fun createCapturedImeAction(
    fieldNode: NodeSnapshot,
    kind: ImeActionKind,
    windowSnapshot: WindowSnapshot,
    actionAtUptimeMs: Long,
    inferred: Boolean = false,
    precomputedFingerprint: ScreenFingerprint? = null
): CapturedImeAction {
    return CapturedImeAction(
        fieldNode = fieldNode,
        kind = kind,
        windowSnapshot = windowSnapshot,
        actionAtUptimeMs = actionAtUptimeMs,
        inferred = inferred,
        precomputedFingerprint = precomputedFingerprint
    )
}

/**
 * Pure creation of [CapturedAppSwitch].
 */
fun createCapturedAppSwitch(
    fromPackage: String?,
    toPackage: String,
    activityClass: String?,
    snapshot: WindowSetSnapshot,
    switchedAtUptimeMs: Long,
    precomputedFingerprint: ScreenFingerprint? = null
): CapturedAppSwitch {
    return CapturedAppSwitch(
        fromPackageName = fromPackage,
        toPackageName = toPackage,
        observedActivityClass = activityClass,
        snapshot = snapshot,
        switchedAtUptimeMs = switchedAtUptimeMs,
        precomputedFingerprint = precomputedFingerprint
    )
}

/**
 * Pure creation of [CapturedGlobalAction].
 */
fun createCapturedGlobalAction(
    kind: String,
    snapshot: WindowSetSnapshot,
    actionAtUptimeMs: Long,
    inferred: Boolean = false,
    precomputedFingerprint: ScreenFingerprint? = null
): CapturedGlobalAction {
    return CapturedGlobalAction(
        kind = kind,
        snapshot = snapshot,
        actionAtUptimeMs = actionAtUptimeMs,
        inferred = inferred,
        precomputedFingerprint = precomputedFingerprint
    )
}

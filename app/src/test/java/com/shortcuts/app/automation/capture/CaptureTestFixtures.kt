package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.fingerprint.ScreenFingerprinter
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.ScreenFingerprint
import com.shortcuts.app.automation.model.TextTokenizer
import com.shortcuts.app.automation.model.TimedPoint

internal const val APP = "example.app"
internal const val OTHER_APP = "other.app"
internal const val LAUNCHER = "example.launcher"
internal const val RECORDER = "com.shortcuts.app"

internal val fakeFingerprinter = object : ScreenFingerprinter {
    override fun fingerprint(window: WindowSnapshot, activityClass: String?): ScreenFingerprint {
        val tokens = window.nodes.values.asSequence()
            .filter { it.visibleToUser }
            .map { node ->
                listOf(
                    node.className,
                    node.resourceId,
                    node.text,
                    node.contentDescription,
                    node.checked,
                    node.selected
                ).joinToString("|").hashCode().toLong()
            }
            .toSet()
        return ScreenFingerprint(
            packageName = window.packageName,
            activityClass = activityClass,
            windowType = window.type,
            structuralTokens = tokens,
            landmarkTokens = tokens,
            structureHash = tokens.sorted().fold(17L) { hash, token -> hash * 31L + token }
        )
    }

    override fun similarity(a: ScreenFingerprint, b: ScreenFingerprint): Double = when {
        a.packageName != b.packageName -> 0.0
        a.structureHash == b.structureHash -> 1.0
        else -> 0.0
    }
}

internal val fakeTokenizer = TextTokenizer { value -> "token:${value.hashCode()}" }

internal fun screen(
    packageName: String,
    label: String,
    nodeId: Int = 2,
    resourceId: String? = null,
    clickable: Boolean = true,
    checked: Boolean = false,
    selected: Boolean = false,
    bounds: IntRect = r(100, 200, 900, 320)
): WindowSnapshot = window(packageName) {
    node("android.widget.FrameLayout", id = 1) {
        node(
            className = "android.widget.Button",
            id = nodeId,
            resourceId = resourceId,
            text = label,
            bounds = bounds,
            clickable = clickable,
            checkable = checked,
            checked = checked,
            selected = selected,
            actions = setOf(NodeAction.CLICK)
        )
    }
}

internal fun snapshot(window: WindowSnapshot, at: Long): WindowSetSnapshot =
    windowSet(window).copy(capturedAtUptimeMs = at, density = 2.625f)

internal fun interaction(
    before: WindowSetSnapshot,
    nodeId: Int = 2,
    kind: GestureKind = GestureKind.TAP,
    down: Long,
    up: Long = down + 60,
    start: IntPoint = IntPoint(500, 260),
    end: IntPoint = start
): CapturedInteraction = CapturedInteraction(
    kind = kind,
    points = if (start == end) {
        listOf(TimedPoint(start, 0), TimedPoint(end, up - down))
    } else {
        listOf(
            TimedPoint(start, 0),
            TimedPoint(IntPoint((start.x + end.x) / 2, (start.y + end.y) / 2), (up - down) / 2),
            TimedPoint(end, up - down)
        )
    },
    downUptimeMs = down,
    upUptimeMs = up,
    beforeSnapshot = before,
    hitNodeId = nodeId,
    hitWindowId = before.windows.first().windowId
)

internal fun compiler(): RecordingCompiler {
    var id = 0
    return RecordingCompiler(
        launcherPackageNames = setOf(LAUNCHER),
        recorderPackageName = RECORDER,
        screenFingerprinter = fakeFingerprinter,
        textTokenizer = fakeTokenizer,
        idGenerator = { "step-${++id}" },
        deviceMetadata = RecordingDeviceMetadata("Google", "Pixel", 36),
        imePackageNames = setOf("example.ime")
    )
}

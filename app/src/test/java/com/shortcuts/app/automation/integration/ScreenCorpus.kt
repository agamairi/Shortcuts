package com.shortcuts.app.automation.integration

import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.WindowType

private const val SETTINGS_PACKAGE = "com.android.settings"
private const val CHAT_PACKAGE = "com.example.chat"
private const val COMPOSE_PACKAGE = "com.example.compose"
private const val DISPLAY_WIDTH = 1_000
private const val DISPLAY_HEIGHT = 2_000
private val DISPLAY = r(0, 0, DISPLAY_WIDTH, DISPLAY_HEIGHT)

data class CorpusScreen(
    val name: String,
    val snapshot: WindowSetSnapshot,
    val targetPackages: Set<String>
)

fun engineV2ScreenCorpus(): List<CorpusScreen> = listOf(
    CorpusScreen("settings-root", settingsRootSnapshot(), setOf(SETTINGS_PACKAGE)),
    CorpusScreen("sixty-row-list", repeatedOpenList(), setOf("com.example.list")),
    CorpusScreen("near-duplicate-buttons", nearDuplicateButtons(), setOf("com.example.settings")),
    CorpusScreen("compose-no-resource-ids", composeScreen(), setOf(COMPOSE_PACKAGE)),
    CorpusScreen("chat", chatScreen(), setOf(CHAT_PACKAGE)),
    CorpusScreen("dialog-over-activity", dialogScreen(), setOf("com.example.dialog")),
    CorpusScreen("app-under-accessibility-overlay", overlayScreen(), setOf("com.example.overlay"))
)

fun settingsRootSnapshot(extraTopRow: Boolean = false): WindowSetSnapshot {
    val rows = buildList {
        if (extraTopRow) add("About phone" to "Android version and device details")
        addAll(
            listOf(
                "Network & internet" to "Wi-Fi, mobile, hotspot",
                "Connected devices" to "Bluetooth, pairing, connection preferences",
                "Apps" to "Assistant, default apps, unused apps",
                "Notifications" to "Notification history, conversations",
                "Battery" to "Battery usage, battery saver",
                "Storage" to "Storage space, free up space",
                "Sound & vibration" to "Volume, haptics, do not disturb",
                "Display" to "Brightness, lock screen, dark theme"
            )
        )
    }

    val appWindow = window(SETTINGS_PACKAGE, id = 1, bounds = DISPLAY) {
        node("android.widget.FrameLayout", id = 1, bounds = DISPLAY) {
            node(
                className = "android.widget.Toolbar",
                id = 2,
                resourceId = "com.android.settings:id/action_bar",
                bounds = r(0, 0, DISPLAY_WIDTH, 150)
            ) {
                node(
                    className = "android.widget.TextView",
                    id = 3,
                    resourceId = "com.android.settings:id/action_bar_title",
                    text = "Settings",
                    bounds = r(40, 40, 500, 110)
                )
            }
            node(
                className = "androidx.recyclerview.widget.RecyclerView",
                id = 10,
                resourceId = "android:id/list",
                bounds = r(0, 150, DISPLAY_WIDTH, DISPLAY_HEIGHT),
                scrollable = true,
                actions = setOf(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD)
            ) {
                rows.forEachIndexed { index, (title, summary) ->
                    val top = 170 + index * 175
                    node(
                        className = "android.widget.LinearLayout",
                        id = 100 + index,
                        text = title,
                        bounds = r(0, top, DISPLAY_WIDTH, top + 155),
                        clickable = true,
                        actions = setOf(NodeAction.CLICK)
                    ) {
                        node(
                            className = "android.widget.TextView",
                            id = 1_000 + index,
                            resourceId = "android:id/title",
                            text = title,
                            bounds = r(36, top + 25, 760, top + 80)
                        )
                        node(
                            className = "android.widget.TextView",
                            id = 2_000 + index,
                            resourceId = "android:id/summary",
                            text = summary,
                            bounds = r(36, top + 88, 900, top + 135)
                        )
                    }
                }
            }
        }
    }
    return windowSet(appWindow, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun repeatedOpenList(firstVisibleItem: Int = 0): WindowSetSnapshot {
    val appWindow = window("com.example.list", id = 1, bounds = DISPLAY) {
        node("android.widget.FrameLayout", id = 1, bounds = DISPLAY) {
            node(
                className = "androidx.recyclerview.widget.RecyclerView",
                id = 2,
                resourceId = "com.example.list:id/items",
                bounds = r(0, 100, DISPLAY_WIDTH, 1_900),
                scrollable = true,
                actions = setOf(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD)
            ) {
                (firstVisibleItem until firstVisibleItem + 60).forEachIndexed { visibleIndex, itemIndex ->
                    val top = 100 + visibleIndex * 30
                    node(
                        className = "android.widget.LinearLayout",
                        id = 100 + itemIndex,
                        text = "Item $itemIndex",
                        bounds = r(0, top, DISPLAY_WIDTH, top + 30),
                        clickable = true,
                        actions = setOf(NodeAction.CLICK),
                        collectionRow = itemIndex
                    ) {
                        node(
                            className = "android.widget.TextView",
                            id = 1_000 + itemIndex,
                            text = "Item $itemIndex",
                            bounds = r(20, top + 2, 300, top + 13),
                            collectionRow = itemIndex
                        )
                        node(
                            className = "android.widget.TextView",
                            id = 2_000 + itemIndex,
                            text = "Subtitle",
                            bounds = r(20, top + 14, 400, top + 26),
                            collectionRow = itemIndex
                        )
                        node(
                            className = "android.widget.Button",
                            id = 3_000 + itemIndex,
                            text = "Open",
                            bounds = r(820, top + 5, 980, top + 25),
                            clickable = true,
                            actions = setOf(NodeAction.CLICK),
                            collectionRow = itemIndex
                        )
                    }
                }
            }
        }
    }
    return windowSet(appWindow, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun nearDuplicateButtons(includeInternet: Boolean = true): WindowSetSnapshot {
    val appWindow = window("com.example.settings", id = 1, bounds = DISPLAY) {
        node("android.widget.FrameLayout", id = 1, bounds = DISPLAY) {
            node(
                className = "android.widget.Button",
                id = 10,
                text = "Internet & SIM",
                bounds = r(100, 700, 400, 840),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
            if (includeInternet) {
                node(
                    className = "android.widget.Button",
                    id = 11,
                    text = "Internet",
                    bounds = r(420, 700, 700, 840),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
            node(
                className = "android.widget.Button",
                id = 12,
                text = "Internet settings",
                bounds = r(720, 700, 980, 840),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
        }
    }
    return windowSet(appWindow, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun composeScreen(): WindowSetSnapshot {
    val appWindow = window(COMPOSE_PACKAGE, id = 1, bounds = DISPLAY) {
        node("androidx.compose.ui.platform.ComposeView", id = 1, bounds = DISPLAY) {
            node(
                className = "android.widget.Button",
                id = 10,
                text = "Save",
                bounds = r(100, 300, 420, 450),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
            node(
                className = "android.widget.ImageButton",
                id = 11,
                contentDescription = "Settings gear",
                bounds = r(500, 300, 650, 450),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
            node(
                className = "android.view.ViewGroup",
                id = 12,
                bounds = r(100, 600, 420, 750),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
            node(
                className = "android.widget.Switch",
                id = 13,
                text = "Wi-Fi",
                bounds = r(500, 600, 850, 750),
                clickable = true,
                checkable = true,
                actions = setOf(NodeAction.CLICK)
            )
        }
    }
    return windowSet(appWindow, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun chatScreen(): WindowSetSnapshot {
    val appWindow = window(CHAT_PACKAGE, id = 1, bounds = DISPLAY) {
        node("android.widget.FrameLayout", id = 1, bounds = DISPLAY) {
            node(
                className = "androidx.recyclerview.widget.RecyclerView",
                id = 2,
                resourceId = "com.example.chat:id/messages",
                bounds = r(0, 100, DISPLAY_WIDTH, 1_500),
                scrollable = true,
                actions = setOf(NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD)
            ) {
                node("android.widget.TextView", id = 3, text = "Alice: Are you free?", bounds = r(40, 180, 800, 250))
                node("android.widget.TextView", id = 4, text = "Bob: See you soon", bounds = r(40, 300, 800, 370))
            }
            node(
                className = "android.widget.EditText",
                id = 10,
                hintText = "Message",
                bounds = r(40, 1_650, 800, 1_800),
                editable = true,
                focusable = true,
                actions = setOf(NodeAction.SET_TEXT, NodeAction.FOCUS)
            )
            node(
                className = "android.widget.ImageButton",
                id = 11,
                contentDescription = "Send",
                bounds = r(830, 1_650, 960, 1_800),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
        }
    }
    return windowSet(appWindow, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun dialogScreen(): WindowSetSnapshot {
    val activity = window("com.example.dialog", id = 1, layer = 1, bounds = DISPLAY) {
        node("android.widget.FrameLayout", id = 1, bounds = DISPLAY) {
            node("android.widget.TextView", id = 2, text = "Activity behind dialog", bounds = r(100, 200, 900, 300))
        }
    }
    val dialog = window(
        "com.example.dialog",
        id = 2,
        layer = 10,
        bounds = r(150, 600, 850, 1_250)
    ) {
        node("android.widget.FrameLayout", id = 1, bounds = r(150, 600, 850, 1_250)) {
            node("android.widget.TextView", id = 2, text = "Delete this item?", bounds = r(200, 680, 800, 760))
            node(
                className = "android.widget.Button",
                id = 3,
                text = "OK",
                bounds = r(220, 950, 450, 1_080),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
            node(
                className = "android.widget.Button",
                id = 4,
                text = "Cancel",
                bounds = r(550, 950, 780, 1_080),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
        }
    }
    return windowSet(activity, dialog, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun overlayScreen(withOverlay: Boolean = true): WindowSetSnapshot {
    val app = window("com.example.overlay", id = 1, layer = 1, bounds = DISPLAY) {
        node("android.widget.FrameLayout", id = 1, bounds = DISPLAY) {
            node(
                className = "android.widget.Button",
                id = 2,
                text = "Continue",
                bounds = r(300, 650, 700, 800),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            )
        }
    }
    if (!withOverlay) return windowSet(app, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)

    val dynamicIsland = window(
        "com.dynamic.island",
        id = 99,
        type = WindowType.ACCESSIBILITY_OVERLAY,
        layer = 99,
        bounds = r(0, 0, DISPLAY_WIDTH, 300)
    ) {
        node("android.widget.FrameLayout", id = 1, bounds = r(0, 0, DISPLAY_WIDTH, 300)) {
            node("android.widget.TextView", id = 2, text = "Dynamic Island", bounds = r(300, 40, 700, 150))
        }
    }
    return windowSet(dynamicIsland, app, width = DISPLAY_WIDTH, height = DISPLAY_HEIGHT)
}

fun shiftedWindow(window: WindowSnapshot, dy: Int): WindowSnapshot = window.copy(
    nodes = window.nodes.mapValues { (_, node) ->
        node.copy(boundsInScreen = node.boundsInScreen.shifted(0, dy))
    }
)

fun composeScreenShifted(dy: Int = 40): WindowSetSnapshot {
    val original = composeScreen()
    return original.copy(windows = original.windows.map { shiftedWindow(it, dy) })
}

fun dialogDismissedSnapshot(): WindowSetSnapshot = dialogScreen().copy(
    windows = listOf(dialogScreen().windows.first())
)

private fun IntRect.shifted(dx: Int, dy: Int): IntRect = r(left + dx, top + dy, right + dx, bottom + dy)

fun WindowSnapshot.actionableNodesForRoundTrip(): List<NodeSnapshot> = nodes.values
    .filter { it.isActionable && !it.scrollable }
    .sortedBy(NodeSnapshot::nodeId)

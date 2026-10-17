package com.shortcuts.app.automation.android.capture

import android.view.KeyEvent
import com.shortcuts.app.automation.capture.CaptureScreenContext
import com.shortcuts.app.automation.capture.interaction
import com.shortcuts.app.automation.capture.screen
import com.shortcuts.app.automation.capture.snapshot
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.service.capture.TouchAction
import com.shortcuts.app.service.capture.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CapturePureTest {

    @Test
    fun testMapImeLabelToActionKind() {
        assertEquals(ImeActionKind.SEARCH, mapImeLabelToActionKind("Search"))
        assertEquals(ImeActionKind.SEARCH, mapImeLabelToActionKind("search"))
        assertEquals(ImeActionKind.SEARCH, mapImeLabelToActionKind("  SEARCH  "))

        assertEquals(ImeActionKind.SEND, mapImeLabelToActionKind("Send"))
        assertEquals(ImeActionKind.SEND, mapImeLabelToActionKind("send"))
        assertEquals(ImeActionKind.SEND, mapImeLabelToActionKind("  send "))

        assertEquals(ImeActionKind.DONE, mapImeLabelToActionKind("Done"))
        assertEquals(ImeActionKind.DONE, mapImeLabelToActionKind("done"))

        assertEquals(ImeActionKind.GO, mapImeLabelToActionKind("Go"))
        assertEquals(ImeActionKind.GO, mapImeLabelToActionKind("go"))

        assertEquals(ImeActionKind.NEXT, mapImeLabelToActionKind("Next"))
        assertEquals(ImeActionKind.NEXT, mapImeLabelToActionKind("next"))

        assertEquals(ImeActionKind.UNSPECIFIED, mapImeLabelToActionKind("Enter"))
        assertEquals(ImeActionKind.UNSPECIFIED, mapImeLabelToActionKind("enter"))
        assertEquals(ImeActionKind.UNSPECIFIED, mapImeLabelToActionKind("Submit"))
        assertEquals(ImeActionKind.UNSPECIFIED, mapImeLabelToActionKind(null))
        assertEquals(ImeActionKind.UNSPECIFIED, mapImeLabelToActionKind(""))
        assertEquals(ImeActionKind.UNSPECIFIED, mapImeLabelToActionKind("   "))
    }

    @Test
    fun testMapKeyCodeToGlobalAction() {
        assertEquals("BACK", mapKeyCodeToGlobalAction(KeyEvent.KEYCODE_BACK))
        assertEquals("HOME", mapKeyCodeToGlobalAction(KeyEvent.KEYCODE_HOME))
        assertEquals("RECENTS", mapKeyCodeToGlobalAction(KeyEvent.KEYCODE_APP_SWITCH))

        assertNull(mapKeyCodeToGlobalAction(KeyEvent.KEYCODE_ENTER))
        assertNull(mapKeyCodeToGlobalAction(KeyEvent.KEYCODE_A))
        assertNull(mapKeyCodeToGlobalAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(mapKeyCodeToGlobalAction(0))
    }

    @Test
    fun testLooksLikeActivityClass() {
        assertTrue(looksLikeActivityClass("com.example.app.MainActivity"))
        assertTrue(looksLikeActivityClass("com.example.app.ui.SettingsActivity"))
        assertTrue(looksLikeActivityClass("org.chromium.chrome.browser.ChromeTabbedActivity"))

        assertFalse(looksLikeActivityClass("androidx.appcompat.app.AlertDialog"))
        assertFalse(looksLikeActivityClass("android.app.Dialog"))
        assertFalse(looksLikeActivityClass("android.app.AlertDialog"))
        assertFalse(looksLikeActivityClass("com.example.app.CustomDialog"))
        assertFalse(looksLikeActivityClass("android.widget.FrameLayout"))
        assertFalse(looksLikeActivityClass("android.widget.Button"))
        assertFalse(looksLikeActivityClass("android.view.View"))
        assertFalse(looksLikeActivityClass("MainActivity"))
        assertFalse(looksLikeActivityClass(""))
        assertFalse(looksLikeActivityClass(null))
        assertFalse(looksLikeActivityClass("   "))
    }

    @Test
    fun testResolveHomePackages() {
        val injectedList = listOf(
            "com.google.android.apps.nexuslauncher",
            "com.teslacoilsw.launcher",
            "com.google.android.apps.nexuslauncher", // duplicate
            null,
            "",
            "   ",
            "com.android.launcher3"
        )
        val resolved = resolveHomePackages(injectedList)
        assertEquals(
            setOf(
                "com.google.android.apps.nexuslauncher",
                "com.teslacoilsw.launcher",
                "com.android.launcher3"
            ),
            resolved
        )
    }

    @Test
    fun testSha256Tokenizer() {
        val tokenizer1 = Sha256TextTokenizer("salt-123")
        val tokenA1 = tokenizer1.tokenize("Settings")
        val tokenA2 = tokenizer1.tokenize("Settings")
        assertEquals(tokenA1, tokenA2)

        val tokenB = tokenizer1.tokenize("Dashboard")
        assertTrue(tokenA1 != tokenB)

        val tokenizer2 = Sha256TextTokenizer("salt-456")
        val tokenWithOtherSalt = tokenizer2.tokenize("Settings")
        assertTrue(tokenA1 != tokenWithOtherSalt)
    }

    @Test
    fun testSelectSaveActionsJson() {
        val action1 = Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "TAP", targetText = "Button")
        val action2 = Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "TYPE_TEXT", textInput = "hello")
        val v07Json = """[{"actionType":"UI_AUTOMATION","uiActionType":"TAP"}]"""
        val v2Json = """{"schemaVersion":2,"workflowId":"w1","recordedDevice":{},"steps":[]}"""

        // Case 1: Engine v2 enabled, v2 JSON present, and unedited review actions -> select v2
        val chosen1 = selectSaveActionsJson(
            engineV2Enabled = true,
            v2WorkflowJson = v2Json,
            originalActions = listOf(action1, action2),
            currentActions = listOf(action1, action2),
            v07ActionsJson = { v07Json }
        )
        assertEquals(v2Json, chosen1)

        // Case 2: Engine v2 enabled, v2 JSON present, BUT user modified an action -> select v0.7
        val modifiedAction = action2.copy(textInput = "world")
        val chosen2 = selectSaveActionsJson(
            engineV2Enabled = true,
            v2WorkflowJson = v2Json,
            originalActions = listOf(action1, action2),
            currentActions = listOf(action1, modifiedAction),
            v07ActionsJson = { v07Json }
        )
        assertEquals(v07Json, chosen2)

        // Case 3: User deleted an action -> select v0.7
        val chosen3 = selectSaveActionsJson(
            engineV2Enabled = true,
            v2WorkflowJson = v2Json,
            originalActions = listOf(action1, action2),
            currentActions = listOf(action1),
            v07ActionsJson = { v07Json }
        )
        assertEquals(v07Json, chosen3)

        // Case 4: User added an action -> select v0.7
        val extraAction = Action(actionType = ActionType.WAIT, delayMillis = 1000)
        val chosen4 = selectSaveActionsJson(
            engineV2Enabled = true,
            v2WorkflowJson = v2Json,
            originalActions = listOf(action1, action2),
            currentActions = listOf(action1, action2, extraAction),
            v07ActionsJson = { v07Json }
        )
        assertEquals(v07Json, chosen4)

        // Case 5: Engine v2 disabled -> select v0.7 even if unedited
        val chosen5 = selectSaveActionsJson(
            engineV2Enabled = false,
            v2WorkflowJson = v2Json,
            originalActions = listOf(action1, action2),
            currentActions = listOf(action1, action2),
            v07ActionsJson = { v07Json }
        )
        assertEquals(v07Json, chosen5)

        // Case 6: v2 workflow JSON is null -> select v0.7
        val chosen6 = selectSaveActionsJson(
            engineV2Enabled = true,
            v2WorkflowJson = null,
            originalActions = listOf(action1, action2),
            currentActions = listOf(action1, action2),
            v07ActionsJson = { v07Json }
        )
        assertEquals(v07Json, chosen6)
    }

    @Test
    fun testCapturedInteractionMapping() {
        val testWindow = screen("com.example.app", "Submit", nodeId = 10, bounds = r(100, 200, 300, 400))
        val testSnapshot = snapshot(testWindow, 1_000L)

        val samples = listOf(
            TouchSample(TouchAction.DOWN, 150f, 250f, 1_000L),
            TouchSample(TouchAction.MOVE, 152f, 251f, 1_020L),
            TouchSample(TouchAction.UP, 152f, 251f, 1_060L)
        )

        val interaction = createCapturedInteraction(
            kind = GestureKind.TAP,
            samples = samples,
            beforeSnapshot = testSnapshot,
            ownPackage = "com.shortcuts.app"
        )

        assertEquals(GestureKind.TAP, interaction.kind)
        assertEquals(1_000L, interaction.downUptimeMs)
        assertEquals(1_060L, interaction.upUptimeMs)
        assertEquals(3, interaction.points.size)
        assertEquals(IntPoint(150, 250), interaction.points[0].displayPx)
        assertEquals(0L, interaction.points[0].elapsedMs)
        assertEquals(IntPoint(152, 251), interaction.points[1].displayPx)
        assertEquals(20L, interaction.points[1].elapsedMs)
        assertEquals(IntPoint(152, 251), interaction.points[2].displayPx)
        assertEquals(60L, interaction.points[2].elapsedMs)
        assertEquals(10, interaction.hitNodeId)
        assertEquals(testWindow.windowId, interaction.hitWindowId)
    }

    @Test
    fun testCapturedTextCommitMapping() {
        val normalField = NodeSnapshot(
            nodeId = 5,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/search_box",
            text = "query",
            contentDescription = null,
            hintText = "Search...",
            stateDescription = null,
            boundsInScreen = r(50, 50, 500, 150),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = true,
            checkable = false,
            checked = false,
            editable = true,
            focusable = true,
            focused = true,
            scrollable = false,
            selected = false,
            password = false,
            actions = setOf(NodeAction.SET_TEXT),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 1,
            windowId = 1,
            packageName = "com.example.app"
        )

        val testWindow = WindowSnapshot(
            windowId = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = "Search Screen",
            packageName = "com.example.app",
            boundsInScreen = r(0, 0, 1080, 2400),
            rootId = 5,
            nodes = mapOf(5 to normalField)
        )

        val commit = createCapturedTextCommit(
            fieldNode = normalField,
            finalText = "RepeatKit",
            windowSnapshot = testWindow,
            committedAtUptimeMs = 2_000L
        )

        assertNotNull(commit)
        assertEquals("RepeatKit", commit!!.finalText)
        assertEquals(5, commit.fieldNode.nodeId)
        assertEquals(1, commit.windowSnapshot.windowId)
        assertEquals(2_000L, commit.committedAtUptimeMs)

        // Password field must produce null (dropped)
        val passwordField = normalField.copy(password = true)
        val passwordCommit = createCapturedTextCommit(
            fieldNode = passwordField,
            finalText = "secret123",
            windowSnapshot = testWindow,
            committedAtUptimeMs = 2_100L
        )
        assertNull(passwordCommit)
    }

    @Test
    fun testCapturedImeActionMapping() {
        val field = NodeSnapshot(
            nodeId = 8,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/input",
            text = "search text",
            contentDescription = null,
            hintText = null,
            stateDescription = null,
            boundsInScreen = r(10, 10, 300, 80),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = true,
            checkable = false,
            checked = false,
            editable = true,
            focusable = true,
            focused = true,
            scrollable = false,
            selected = false,
            password = false,
            actions = setOf(NodeAction.SET_TEXT),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 1,
            windowId = 2,
            packageName = "com.example.app"
        )

        val testWindow = WindowSnapshot(
            windowId = 2,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = "Input Window",
            packageName = "com.example.app",
            boundsInScreen = r(0, 0, 1080, 2400),
            rootId = 8,
            nodes = mapOf(8 to field)
        )

        val imeAction = createCapturedImeAction(
            fieldNode = field,
            kind = ImeActionKind.SEARCH,
            windowSnapshot = testWindow,
            actionAtUptimeMs = 3_000L
        )

        assertEquals(ImeActionKind.SEARCH, imeAction.kind)
        assertEquals(8, imeAction.fieldNode.nodeId)
        assertEquals(2, imeAction.windowSnapshot.windowId)
        assertEquals(3_000L, imeAction.actionAtUptimeMs)
    }

    @Test
    fun testCapturedAppSwitchMapping() {
        val testWindow = screen("com.target.app", "Target", nodeId = 1)
        val snapshot = snapshot(testWindow, 4_000L)

        val appSwitch = createCapturedAppSwitch(
            fromPackage = "com.source.app",
            toPackage = "com.target.app",
            activityClass = "com.target.app.MainActivity",
            snapshot = snapshot,
            switchedAtUptimeMs = 4_000L
        )

        assertEquals("com.source.app", appSwitch.fromPackageName)
        assertEquals("com.target.app", appSwitch.toPackageName)
        assertEquals("com.target.app.MainActivity", appSwitch.observedActivityClass)
        assertEquals(snapshot, appSwitch.snapshot)
        assertEquals(4_000L, appSwitch.switchedAtUptimeMs)
    }

    @Test
    fun testCapturedGlobalActionMapping() {
        val testWindow = screen("com.example.app", "Screen", nodeId = 1)
        val snapshot = snapshot(testWindow, 5_000L)

        val globalAction = createCapturedGlobalAction(
            kind = "BACK",
            snapshot = snapshot,
            actionAtUptimeMs = 5_000L
        )

        assertEquals("BACK", globalAction.kind)
        assertEquals(snapshot, globalAction.snapshot)
        assertEquals(5_000L, globalAction.actionAtUptimeMs)
    }
}

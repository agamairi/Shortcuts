package com.shortcuts.app.automation.android.capture

import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CaptureStoreTest {
    private fun session() = PersistedV2Session(
        workflowId = "workflow",
        initialSnapshot = WindowSetSnapshot(1L, 100, 100, 0, 1f, emptyList())
    )

    @Test fun appendAndRestoreRoundTrip() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            val event = PersistedV2EventWrapper(type = "interaction", lateSnapshot = true)
            assertTrue(store.appendActiveEvent(session().copy(events = listOf(event)), event))
            val restored = store.loadActiveSession()
            assertNotNull(restored)
            assertEquals(listOf(event), restored!!.events)
        } finally { dir.deleteRecursively() }
    }

    @Test fun truncatedFinalLineIsDiscarded() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            val event = PersistedV2EventWrapper(type = "interaction")
            store.appendActiveEvent(session().copy(events = listOf(event)), event)
            File(dir, FileV2CaptureStore.EVENT_LOG_FILE_NAME).appendText("{truncated")
            assertEquals(1, store.loadActiveSession()!!.events.size)
            assertNull(store.takeLoadFailure())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptionBeforeLastRecordIsReported() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            File(dir, FileV2CaptureStore.EVENT_LOG_FILE_NAME).writeText("{bad}\n{\"type\":\"interaction\"}\n")
            assertNull(store.loadActiveSession())
            assertNotNull(store.takeLoadFailure())
        } finally { dir.deleteRecursively() }
    }

    @Test fun eventCapIsEnforcedAndWorkflowWritesRoundTripAtomically() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            repeat(FileV2CaptureStore.MAX_EVENTS) { index ->
                val event = PersistedV2EventWrapper(type = "event-$index")
                assertTrue(store.appendActiveEvent(session(), event))
            }
            assertTrue(!store.appendActiveEvent(session(), PersistedV2EventWrapper(type = "overflow")))
            store.saveLastWorkflow("{\"schemaVersion\":2}")
            assertEquals("{\"schemaVersion\":2}", store.loadLastWorkflow())
            assertTrue(File(dir, FileV2CaptureStore.LAST_WORKFLOW_FILE_NAME).isFile)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun compactionPicksActiveApplicationWindowOverHigherLayerSystemBars() {
        val appNode = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 1, parentId = null, childIds = emptyList(), className = "android.widget.FrameLayout",
            resourceId = null, text = null, contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 2400), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 1, windowId = 1, packageName = "com.app"
        )
        val appWindow = com.shortcuts.app.automation.core.tree.WindowSnapshot(
            windowId = 1, type = com.shortcuts.app.automation.model.WindowType.APPLICATION, layer = 1, isActive = true, isFocused = true,
            title = "Main", packageName = "com.app", boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 2400), rootId = 1,
            nodes = mapOf(1 to appNode)
        )
        val sysNode = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 2, parentId = null, childIds = emptyList(), className = "android.widget.FrameLayout",
            resourceId = null, text = null, contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 100), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 1, windowId = 2, packageName = "com.android.systemui"
        )
        val sysWindow = com.shortcuts.app.automation.core.tree.WindowSnapshot(
            windowId = 2, type = com.shortcuts.app.automation.model.WindowType.SYSTEM, layer = 50, isActive = false, isFocused = false,
            title = "StatusBar", packageName = "com.android.systemui", boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 100), rootId = 2,
            nodes = mapOf(2 to sysNode)
        )
        val snapshot = WindowSetSnapshot(1_000L, 1080, 2400, 0, 2.5f, listOf(sysWindow, appWindow))
        val appSwitch = com.shortcuts.app.automation.capture.CapturedAppSwitch(
            fromPackageName = null, toPackageName = "com.app", observedActivityClass = "com.app.MainActivity",
            snapshot = snapshot, switchedAtUptimeMs = 1_000L
        )
        val dir = Files.createTempDirectory("v2-capture-compaction-win").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(PersistedV2Session("wf-win", snapshot))
            val event = PersistedV2EventWrapper(type = "app_switch", appSwitch = appSwitch)
            store.appendActiveEvent(session(), event)
            val restored = store.loadActiveSession()
            val restoredSwitch = restored?.events?.firstOrNull()?.appSwitch
            assertNotNull(restoredSwitch)
            val appWin = restoredSwitch!!.snapshot.windows.first { it.nodes.isNotEmpty() }
            assertEquals("com.app", appWin.packageName)
            assertEquals(com.shortcuts.app.automation.model.WindowType.APPLICATION, appWin.type)
            val sysWin = restoredSwitch.snapshot.windows.first { it.windowId == 2 }
            assertTrue(sysWin.nodes.isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun compactionKeepsAllSiblingsInRowContainer() {
        val rootNode = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 1, parentId = null, childIds = listOf(2), className = "android.widget.LinearLayout",
            resourceId = "id/root", text = null, contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 2400), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 1, windowId = 1, packageName = "com.app"
        )
        val rowNode = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 2, parentId = 1, childIds = listOf(3, 4, 5), className = "android.widget.TableRow",
            resourceId = "id/row", text = null, contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 100, 1080, 300), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = 0, collectionColumn = null, drawingOrder = 1, windowId = 1, packageName = "com.app"
        )
        val textSibling = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 3, parentId = 2, childIds = emptyList(), className = "android.widget.TextView",
            resourceId = "id/label", text = "Item 1", contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(10, 110, 200, 290), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 1, windowId = 1, packageName = "com.app"
        )
        val imageSibling = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 4, parentId = 2, childIds = emptyList(), className = "android.widget.ImageView",
            resourceId = "id/icon", text = null, contentDescription = "Icon", hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(210, 110, 300, 290), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 2, windowId = 1, packageName = "com.app"
        )
        val buttonTarget = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 5, parentId = 2, childIds = emptyList(), className = "android.widget.Button",
            resourceId = "id/btn", text = "Buy", contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(800, 110, 1000, 290), visibleToUser = true, enabled = true, clickable = true,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = true,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 3, windowId = 1, packageName = "com.app"
        )
        val window = com.shortcuts.app.automation.core.tree.WindowSnapshot(
            windowId = 1, type = com.shortcuts.app.automation.model.WindowType.APPLICATION, layer = 1, isActive = true, isFocused = true,
            title = "Cart", packageName = "com.app", boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 2400), rootId = 1,
            nodes = mapOf(1 to rootNode, 2 to rowNode, 3 to textSibling, 4 to imageSibling, 5 to buttonTarget)
        )
        val snapshot = WindowSetSnapshot(1_000L, 1080, 2400, 0, 2.5f, listOf(window))
        val interaction = com.shortcuts.app.automation.capture.CapturedInteraction(
            kind = com.shortcuts.app.automation.model.GestureKind.TAP, points = emptyList(), downUptimeMs = 1_000L, upUptimeMs = 1_050L,
            beforeSnapshot = snapshot, hitNodeId = 5, hitWindowId = 1
        )
        val dir = Files.createTempDirectory("v2-capture-siblings").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(PersistedV2Session("wf-sibs", snapshot))
            val event = PersistedV2EventWrapper(type = "interaction", interaction = interaction)
            store.appendActiveEvent(session(), event)
            val restored = store.loadActiveSession()
            val restoredWin = restored?.events?.firstOrNull()?.interaction?.beforeSnapshot?.windows?.firstOrNull()
            assertNotNull(restoredWin)
            assertTrue(restoredWin!!.nodes.containsKey(3))
            assertTrue(restoredWin.nodes.containsKey(4))
            assertTrue(restoredWin.nodes.containsKey(5))
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun precomputedFingerprintComputedBeforeCompactionAndStored() {
        val appNode = com.shortcuts.app.automation.core.tree.NodeSnapshot(
            nodeId = 1, parentId = null, childIds = emptyList(), className = "android.widget.FrameLayout",
            resourceId = null, text = null, contentDescription = null, hintText = null, stateDescription = null,
            boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 2400), visibleToUser = true, enabled = true, clickable = false,
            longClickable = false, checkable = false, checked = false, editable = false, focusable = false,
            focused = false, scrollable = false, selected = false, password = false, actions = emptySet(),
            collectionRow = null, collectionColumn = null, drawingOrder = 1, windowId = 1, packageName = "com.app"
        )
        val appWindow = com.shortcuts.app.automation.core.tree.WindowSnapshot(
            windowId = 1, type = com.shortcuts.app.automation.model.WindowType.APPLICATION, layer = 1, isActive = true, isFocused = true,
            title = "Main", packageName = "com.app", boundsInScreen = com.shortcuts.app.automation.core.tree.r(0, 0, 1080, 2400), rootId = 1,
            nodes = mapOf(1 to appNode)
        )
        val snapshot = WindowSetSnapshot(1_000L, 1080, 2400, 0, 2.5f, listOf(appWindow))
        val expectedFingerprint = com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter().fingerprint(appWindow, "com.app.MainActivity")
        val appSwitch = com.shortcuts.app.automation.capture.CapturedAppSwitch(
            fromPackageName = null, toPackageName = "com.app", observedActivityClass = "com.app.MainActivity",
            snapshot = snapshot, switchedAtUptimeMs = 1_000L
        )
        val dir = Files.createTempDirectory("v2-capture-fp").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(PersistedV2Session("wf-fp", snapshot))
            val event = PersistedV2EventWrapper(type = "app_switch", appSwitch = appSwitch)
            store.appendActiveEvent(session(), event)
            val restored = store.loadActiveSession()
            val wrapper = restored?.events?.firstOrNull()
            assertNotNull(wrapper)
            assertNotNull(wrapper?.precomputedFingerprint)
            assertEquals(expectedFingerprint.structureHash, wrapper?.precomputedFingerprint?.structureHash)
            assertEquals(expectedFingerprint.structuralTokens, wrapper?.precomputedFingerprint?.structuralTokens)
            assertEquals(wrapper?.precomputedFingerprint, wrapper?.appSwitch?.precomputedFingerprint)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun appendActiveEventDoesNotRewriteSessionFile() {
        val dir = Files.createTempDirectory("v2-capture-no-rewrite").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            val initialSession = session()
            store.saveActiveSession(initialSession)
            val sessionFile = File(dir, FileV2CaptureStore.SESSION_FILE_NAME)
            val initialModTime = sessionFile.lastModified()
            Thread.sleep(50)
            val event = PersistedV2EventWrapper(type = "interaction")
            store.appendActiveEvent(initialSession.copy(events = listOf(event)), event)
            assertEquals("session.json must not be touched on event append", initialModTime, sessionFile.lastModified())
        } finally { dir.deleteRecursively() }
    }
}

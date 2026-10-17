package com.shortcuts.app.automation.android.capture

import com.shortcuts.app.automation.capture.screen
import com.shortcuts.app.automation.capture.snapshot
import com.shortcuts.app.automation.capture.fakeFingerprinter
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImeSubmitInferenceTest {

    private lateinit var inference: ImeSubmitInference

    @Before
    fun setUp() {
        inference = ImeSubmitInference(maxDelayMs = 2_000L)
    }

    private fun createFieldNode(
        nodeId: Int = 10,
        resourceId: String? = "com.example.app:id/search",
        text: String? = "test query",
        hintText: String? = "Search",
        contentDescription: String? = null
    ): NodeSnapshot {
        return NodeSnapshot(
            nodeId = nodeId,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription,
            hintText = hintText,
            stateDescription = null,
            boundsInScreen = r(20, 100, 500, 180),
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
    }

    private fun createWindow(
        windowId: Int = 1,
        title: String? = "Main Window",
        nodes: Map<Int, NodeSnapshot> = emptyMap(),
        packageName: String = "com.example.app"
    ): WindowSnapshot {
        return WindowSnapshot(
            windowId = windowId,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = title,
            packageName = packageName,
            boundsInScreen = r(0, 0, 1080, 2400),
            rootId = nodes.keys.firstOrNull() ?: 1,
            nodes = nodes
        )
    }

    @Test
    fun testResultsScreenAfterCommitInfersSearch() {
        val searchField = createFieldNode(
            nodeId = 10,
            resourceId = "com.example.app:id/edit_search",
            text = "android widgets",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(10 to searchField))
        val commit = createCapturedTextCommit(
            fieldNode = searchField,
            finalText = "android widgets",
            windowSnapshot = window,
            committedAtUptimeMs = 1_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        // Within 2 seconds, the activity changes to a results activity
        val inferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.SearchResultsActivity",
            uptimeMs = 1_800L
        )

        assertNotNull(inferred)
        assertEquals(ImeActionKind.SEARCH, inferred!!.action.kind)
        assertEquals(10, inferred.action.fieldNode.nodeId)
        assertTrue(inferred.reason.startsWith("activity_change"))
        assertEquals(1_800L, inferred.action.actionAtUptimeMs)
        assertNull(inferred.pendingCommit)
    }

    @Test
    fun testFieldClearedAfterCommitInfersSend() {
        val messageField = createFieldNode(
            nodeId = 20,
            resourceId = "com.example.app:id/edit_message",
            text = "Hello world",
            hintText = "Message"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(20 to messageField))
        val commit = createCapturedTextCommit(
            fieldNode = messageField,
            finalText = "Hello world",
            windowSnapshot = window,
            committedAtUptimeMs = 2_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.ChatActivity")

        // Field cleared within 2 seconds (the classic "sent" pattern)
        val inferred = inference.onFieldTextChanged(messageField.copy(text = null, focused = true), "", 2_400L)

        assertNotNull(inferred)
        assertEquals(ImeActionKind.SEND, inferred!!.action.kind)
        assertEquals(20, inferred.action.fieldNode.nodeId)
        assertEquals("field_cleared", inferred.reason)
        assertEquals(2_400L, inferred.action.actionAtUptimeMs)
        assertNull(inferred.pendingCommit)
    }

    @Test
    fun testProgressiveDeletionToEmptyDoesNotInferSubmit() {
        val field = createFieldNode(nodeId = 21, text = "Hello", hintText = "Message")
        val window = createWindow(nodes = mapOf(21 to field))
        inference.onTextCommit(createCapturedTextCommit(field, "Hello", window, 2_000L)!!, "com.example.app.ChatActivity")

        assertNull(inference.onFieldTextChanged(field.copy(text = "Hell"), "Hell", 2_100L))
        assertNull(inference.onFieldTextChanged(field.copy(text = null), "", 2_200L))
    }

    @Test
    fun testClearWithoutMatchingFieldDoesNotInferSubmit() {
        val field = createFieldNode(nodeId = 22, text = "Hello", hintText = "Message")
        val window = createWindow(nodes = mapOf(22 to field))
        inference.onTextCommit(createCapturedTextCommit(field, "Hello", window, 2_000L)!!, "com.example.app.ChatActivity")
        assertNull(inference.onFieldCleared(fieldNodeId = null, resourceId = "other.field.id", uptimeMs = 2_100L))
    }

    @Test
    fun testClearMatchingResourceIdWithoutNodeIdInfersSubmit() {
        val field = createFieldNode(nodeId = 22, resourceId = "com.example.app:id/edit_message", text = "Hello", hintText = "Message")
        val window = createWindow(nodes = mapOf(22 to field))
        inference.onTextCommit(createCapturedTextCommit(field, "Hello", window, 2_000L)!!, "com.example.app.ChatActivity")
        val result = inference.onFieldCleared(fieldNodeId = null, resourceId = field.resourceId, uptimeMs = 2_100L)
        assertNotNull(result)
        assertEquals(ImeActionKind.SEND, result?.action?.kind)
        assertTrue(result?.action?.inferred == true)
    }

    @Test
    fun testKeyboardHiddenOnlyDoesNothing() {
        val field = createFieldNode(
            nodeId = 30,
            resourceId = "com.example.app:id/input",
            text = "some text",
            hintText = null
        )
        val window = createWindow(windowId = 1, nodes = mapOf(30 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "some text",
            windowSnapshot = window,
            committedAtUptimeMs = 3_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        // Keyboard hidden alone: must NOT synthesize submission
        inference.onKeyboardHidden()

        // Candidate remains un-emitted
        assertNotNull(inference.candidate)
        assertEquals("some text", inference.candidate?.text)

        // Activity not changed
        val inferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.MainActivity",
            uptimeMs = 3_500L
        )
        assertNull(inferred)
    }

    @Test
    fun testOverlayTapInBetweenCancelsInference() {
        val field = createFieldNode(
            nodeId = 40,
            resourceId = "com.example.app:id/edit_search",
            text = "repeatkit",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(40 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "repeatkit",
            windowSnapshot = window,
            committedAtUptimeMs = 4_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        // User taps an on-screen button before any window transition
        inference.onOverlayInteraction(uptimeMs = 4_200L)
        assertNull(inference.candidate)

        // Even if results screen opens afterward, inference was cancelled by the tap
        val inferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.SearchResultsActivity",
            uptimeMs = 4_500L
        )
        assertNull(inferred)
    }

    @Test
    fun testAutocompleteContentChangeOnSameScreenDoesNothing() {
        val field = createFieldNode(
            nodeId = 50,
            resourceId = "com.example.app:id/search",
            text = "sh",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(50 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "sh",
            windowSnapshot = window,
            committedAtUptimeMs = 5_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.SearchActivity")

        // Suggestions update on the same screen
        val contentInferred = inference.onContentChanged(uptimeMs = 5_200L)
        assertNull(contentInferred)

        // Same activity emitted
        val activityInferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.SearchActivity",
            uptimeMs = 5_300L
        )
        assertNull(activityInferred)
        assertNotNull(inference.candidate)
    }

    @Test
    fun testBackKeyInBetweenCancelsInference() {
        val field = createFieldNode(
            nodeId = 60,
            resourceId = "com.example.app:id/edit_search",
            text = "query",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(60 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "query",
            windowSnapshot = window,
            committedAtUptimeMs = 6_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        // User pressed BACK key
        inference.onKeyEvent(keyCode = 4 /* KEYCODE_BACK */, uptimeMs = 6_200L)
        assertNull(inference.candidate)

        // Subsequent window change does not infer
        val inferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.OtherActivity",
            uptimeMs = 6_400L
        )
        assertNull(inferred)
    }

    @Test
    fun testInferredActionOrderedAfterTextCommitAndBeforeNextInteraction() {
        val field = createFieldNode(
            nodeId = 70,
            resourceId = "com.example.app:id/edit_search",
            text = "fast search",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(70 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "fast search",
            windowSnapshot = window,
            committedAtUptimeMs = 7_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        val inferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.SearchResultsActivity",
            uptimeMs = 7_500L
        )

        assertNotNull(inferred)
        // actionAtUptimeMs is after the text commit timestamp
        assertTrue(inferred!!.action.actionAtUptimeMs > commit.committedAtUptimeMs)
        assertEquals(7_500L, inferred.action.actionAtUptimeMs)
    }

    @Test
    fun testInferenceTimeoutAfterTwoSecondsFails() {
        val field = createFieldNode(
            nodeId = 80,
            resourceId = "com.example.app:id/edit_search",
            text = "timeout search",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(80 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "timeout search",
            windowSnapshot = window,
            committedAtUptimeMs = 8_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        // 2001 ms later (exceeds 2000 ms window)
        val inferred = inference.onActivityChanged(
            toPackage = "com.example.app",
            activityClass = "com.example.app.SearchResultsActivity",
            uptimeMs = 10_001L
        )

        assertNull(inferred)
        assertNull(inference.candidate)
    }

    @Test
    fun testUncommittedTypingDoesNotInventSubmit() {
        val field = createFieldNode(
            nodeId = 90,
            resourceId = "com.example.app:id/message_box",
            text = null,
            hintText = "Message"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(90 to field))

        // User typed but text finalizer has not committed yet
        inference.onTextTyped(
            fieldNode = field,
            text = "quick message",
            windowSnapshot = window,
            activityClass = "com.example.app.ChatActivity",
            uptimeMs = 11_000L
        )

        val inferred = inference.onFieldCleared(
            fieldNodeId = 90,
            resourceId = "com.example.app:id/message_box",
            uptimeMs = 11_300L
        )

        assertNull(inferred)
    }

    @Test
    fun testNearbySendButtonResolvesSendKind() {
        val genericField = createFieldNode(
            nodeId = 100,
            resourceId = "com.example.app:id/generic_input",
            text = "text to send",
            hintText = null
        )
        val sendButton = NodeSnapshot(
            nodeId = 101,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.Button",
            resourceId = "com.example.app:id/btn_send",
            text = "Send",
            contentDescription = null,
            hintText = null,
            stateDescription = null,
            boundsInScreen = r(500, 100, 600, 180),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = false,
            checkable = false,
            checked = false,
            editable = false,
            focusable = true,
            focused = false,
            scrollable = false,
            selected = false,
            password = false,
            actions = setOf(NodeAction.CLICK),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 2,
            windowId = 1,
            packageName = "com.example.app"
        )
        val windowWithButton = createWindow(
            windowId = 1,
            nodes = mapOf(100 to genericField, 101 to sendButton)
        )

        val kind = inferImeActionKind(genericField, windowWithButton)
        assertEquals(ImeActionKind.SEND, kind)
    }

    @Test
    fun testSameActivityDialogDoesNotInferSubmit() {
        val field = createFieldNode(
            nodeId = 110,
            resourceId = "com.example.app:id/search",
            text = "dialog query",
            hintText = "Search"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(110 to field))
        val commit = createCapturedTextCommit(
            fieldNode = field,
            finalText = "dialog query",
            windowSnapshot = window,
            committedAtUptimeMs = 12_000L
        )!!

        inference.onTextCommit(commit, activityClass = "com.example.app.MainActivity")

        // Dialog appears for the same package
        val inferred = inference.onWindowChanged(
            toPackage = "com.example.app",
            windowId = 2,
            windowType = WindowType.APPLICATION,
            className = "androidx.appcompat.app.AlertDialog",
            uptimeMs = 12_500L
        )

        assertNull(inferred)
    }

    @Test
    fun testTrackFieldAcrossSnapshotsByResourceIdAndBoundsNeverByNodeId() {
        val field1 = createFieldNode(
            nodeId = 10,
            resourceId = "com.example.app:id/search_query",
            text = "kotlin",
            hintText = "Search"
        )
        val window1 = createWindow(windowId = 1, nodes = mapOf(10 to field1))
        val commit = createCapturedTextCommit(field1, "kotlin", window1, 1_000L)!!
        inference.onTextCommit(commit, "com.example.app.SearchActivity")

        // Snapshot 2 re-renders: node id changed from 10 to 999, but resourceId and bounds match
        val field2 = field1.copy(nodeId = 999, text = null, focused = true)
        val window2 = createWindow(windowId = 1, nodes = mapOf(999 to field2))

        val result = inference.onFieldTextChanged(field2, "", 1_500L, window2)
        assertNotNull(result)
        assertEquals(ImeActionKind.SEARCH, result!!.action.kind)
        assertTrue(result.action.inferred)
        assertEquals(10, result.action.fieldNode.nodeId)
    }

    @Test
    fun testCommittedTextLengthLessThanTwoDoesNotInferSend() {
        val field = createFieldNode(
            nodeId = 20,
            resourceId = "com.example.app:id/edit_msg",
            text = "a",
            hintText = "Message"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(20 to field))
        val commit = createCapturedTextCommit(field, "a", window, 1_000L)!!
        inference.onTextCommit(commit, "com.example.app.ChatActivity")

        val result = inference.onFieldTextChanged(field.copy(text = null, focused = true), "", 1_200L, window)
        assertNull("Single character clear must not infer submit", result)
    }

    @Test
    fun testTapInsideFieldBoundsDoesNotInferSend() {
        val field = createFieldNode(
            nodeId = 20,
            resourceId = "com.example.app:id/edit_msg",
            text = "hello world",
            hintText = "Message"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(20 to field))
        val commit = createCapturedTextCommit(field, "hello world", window, 1_000L)!!
        inference.onTextCommit(commit, "com.example.app.ChatActivity")

        // User taps inside the field bounds (e.g. clear "X" icon at x=450, y=140)
        inference.onOverlayInteraction(x = 450, y = 140, uptimeMs = 1_200L)

        // Then field is cleared
        val result = inference.onFieldTextChanged(field.copy(text = null, focused = true), "", 1_400L, window)
        assertNull("Tap inside field bounds (clear icon) must cancel submit inference", result)
    }

    @Test
    fun testFocusLossWithSubstantialScreenChangeInfersSameActivitySearch() {
        inference = ImeSubmitInference(screenFingerprinter = fakeFingerprinter)
        val field = createFieldNode(text = "lofi beats")
            .copy(actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER))
        val before = createWindow(nodes = mapOf(field.nodeId to field))
        val keyboard = createWindow(windowId = 9, nodes = emptyMap(), packageName = "example.ime")
            .copy(type = WindowType.INPUT_METHOD)
        val beforeSet = snapshot(before, 1_000L).copy(windows = listOf(before, keyboard))
        val commit = createCapturedTextCommit(field, "lofi beats", before, 1_000L)!!
        inference.onTextCommit(commit, "com.example.app.SearchActivity", beforeSet)

        val resultNode = field.copy(
            nodeId = 11,
            resourceId = "com.example.app:id/result_list",
            className = "androidx.recyclerview.widget.RecyclerView",
            text = null,
            editable = false,
            focused = false,
            actions = emptySet()
        )
        val after = createWindow(nodes = mapOf(resultNode.nodeId to resultNode))
        val inferred = inference.onScreenEffect(
            snapshot(after, 1_600L),
            uptimeMs = 1_600L,
            activityClass = "com.example.app.SearchActivity"
        )

        assertNotNull(inferred)
        assertEquals("focus_loss_with_screen_change", inferred?.reason)
        assertEquals(ImeActionKind.SEARCH, inferred?.action?.kind)
    }

    @Test
    fun testKeyboardDismissalWithoutScreenChangeDoesNotInferEffectSubmit() {
        inference = ImeSubmitInference(screenFingerprinter = fakeFingerprinter)
        val field = createFieldNode(text = "repeatkit")
            .copy(actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER))
        val before = createWindow(nodes = mapOf(field.nodeId to field))
        val keyboard = createWindow(windowId = 9, nodes = emptyMap(), packageName = "example.ime")
            .copy(type = WindowType.INPUT_METHOD)
        val commit = createCapturedTextCommit(field, "repeatkit", before, 1_000L)!!
        inference.onTextCommit(
            commit,
            "com.example.app.SearchActivity",
            snapshot(before, 1_000L).copy(windows = listOf(before, keyboard))
        )

        val sameScreen = before.copy(nodes = mapOf(field.nodeId to field.copy(focused = false)))
        assertNull(inference.onScreenEffect(snapshot(sameScreen, 1_400L), 1_400L))
    }

    @Test
    fun testSuggestionUpdateWhileFieldFocusedDoesNotInferEffectSubmit() {
        inference = ImeSubmitInference(screenFingerprinter = fakeFingerprinter)
        val field = createFieldNode(text = "rep")
            .copy(actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER))
        val before = createWindow(nodes = mapOf(field.nodeId to field))
        val keyboard = createWindow(windowId = 9, nodes = emptyMap(), packageName = "example.ime")
            .copy(type = WindowType.INPUT_METHOD)
        val initial = snapshot(before, 1_000L).copy(windows = listOf(before, keyboard))
        inference.onTextCommit(createCapturedTextCommit(field, "rep", before, 1_000L)!!, null, initial)

        val suggestion = field.copy(
            nodeId = 12,
            resourceId = "com.example.app:id/suggestion",
            className = "android.widget.TextView",
            text = "repeatkit",
            editable = false,
            focused = false,
            actions = emptySet()
        )
        val updated = before.copy(nodes = mapOf(field.nodeId to field, suggestion.nodeId to suggestion))
        val updatedSet = snapshot(updated, 1_300L).copy(windows = listOf(updated, keyboard))

        assertNull(inference.onScreenEffect(updatedSet, 1_300L))
        assertNotNull(inference.candidate)
    }

    @Test
    fun testInterveningTapSuppressesEffectSubmit() {
        inference = ImeSubmitInference(screenFingerprinter = fakeFingerprinter)
        val field = createFieldNode(text = "repeatkit")
            .copy(actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER))
        val before = createWindow(nodes = mapOf(field.nodeId to field))
        val commit = createCapturedTextCommit(field, "repeatkit", before, 1_000L)!!
        inference.onTextCommit(commit, "com.example.app.SearchActivity", snapshot(before, 1_000L))
        inference.onOverlayInteraction(x = 900, y = 900, uptimeMs = 1_100L)

        val after = createWindow(nodes = mapOf(99 to field.copy(nodeId = 99, editable = false, focused = false)))
        assertNull(inference.onScreenEffect(snapshot(after, 1_300L), 1_300L))
    }

    @Test
    fun testInferredFlagOnCapturedImeActionProducesCompilerWarning() {
        val field = createFieldNode(
            nodeId = 20,
            resourceId = "com.example.app:id/edit_msg",
            text = "hello world",
            hintText = "Message"
        )
        val window = createWindow(windowId = 1, nodes = mapOf(20 to field))
        val commit = createCapturedTextCommit(field, "hello world", window, 1_000L)!!
        inference.onTextCommit(commit, "com.example.app.ChatActivity")

        val inferred = inference.onFieldTextChanged(field.copy(text = null, focused = true), "", 1_400L, window)
        assertNotNull(inferred)
        assertTrue(inferred!!.action.inferred)

        val compiler = com.shortcuts.app.automation.capture.RecordingCompiler(
            launcherPackageNames = setOf("com.example.launcher"),
            recorderPackageName = "com.shortcuts.app",
            screenFingerprinter = com.shortcuts.app.automation.capture.fakeFingerprinter,
            textTokenizer = com.shortcuts.app.automation.capture.fakeTokenizer,
            deviceMetadata = com.shortcuts.app.automation.capture.RecordingDeviceMetadata("Google", "Pixel", 36),
            imePackageNames = setOf("com.example.ime")
        )
        compiler.start(com.shortcuts.app.automation.capture.CaptureScreenContext("wf-test", snapshot(window, 1_000L)))
        compiler.accept(commit)
        compiler.accept(inferred.action)
        val workflow = compiler.finish()
        val imeStep = workflow.steps.filterIsInstance<com.shortcuts.app.automation.model.UiStep>()
            .firstOrNull { it.operation is com.shortcuts.app.automation.model.UiOperation.IME_ACTION }
        assertNotNull(imeStep)
        assertTrue("Workflow step must contain INFERRED_IME_ACTION warning", imeStep!!.migrationWarnings.contains("INFERRED_IME_ACTION"))
    }
}

package com.shortcuts.app.automation.integration

import com.shortcuts.app.automation.capture.SelectorExtractor
import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.matching.MatchDecision
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.WindowDecision
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.TextTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class RecordMatchUnderChangeTest {

    private val fingerprinter = DefaultScreenFingerprinter()
    private val extractor = SelectorExtractor(fingerprinter, TextTokenizer { "token:$it" })
    private val windowMatcher = WindowMatcher(fingerprinter, titleTokenizer = { "token:$it" })
    private val selectorMatcher = SelectorMatcher()

    @Test
    fun `a list scrolled by three rows still finds the recorded row action`() {
        val recorded = repeatedOpenList(0)
        val replay = repeatedOpenList(3)
        val recordedWindow = recorded.windows.single()
        val target = openButtonForItem(recordedWindow, 10)
        val extraction = record(recorded, recordedWindow, target)
        val replayWindow = matchWindow(replay, recordedWindow)
        val decision = selectorMatcher.match(replayWindow, extraction.selector)

        assertAcceptedNode(decision, openButtonForItem(replayWindow, 10).nodeId)
    }

    @Test
    fun `a settings row inserted at the top does not change the recorded target`() {
        val recorded = settingsRootSnapshot(false)
        val replay = settingsRootSnapshot(true)
        val recordedWindow = recorded.windows.single()
        val target = settingsRow(recordedWindow, "Battery")
        val extraction = record(recorded, recordedWindow, target)
        val replayWindow = matchWindow(replay, recordedWindow)
        val decision = selectorMatcher.match(replayWindow, extraction.selector)

        assertAcceptedNode(decision, settingsRow(replayWindow, "Battery").nodeId)
    }

    @Test
    fun `a compose layout moved down by forty pixels still finds Save`() {
        val recorded = composeScreen()
        val replay = composeScreenShifted(40)
        val recordedWindow = recorded.windows.single()
        val target = recordedWindow.nodes.values.single { it.text == "Save" }
        val extraction = record(recorded, recordedWindow, target)
        val replayWindow = matchWindow(replay, recordedWindow)
        val decision = selectorMatcher.match(replayWindow, extraction.selector)

        assertAcceptedNode(decision, replayWindow.nodes.values.single { it.text == "Save" }.nodeId)
    }

    @Test
    fun `dismissing a dialog never falls through to the activity behind it`() {
        val recorded = dialogScreen()
        val replay = dialogDismissedSnapshot()
        val dialog = recorded.windows.first { it.windowId == 2 }
        val target = dialog.nodes.values.single { it.text == "OK" }
        val extraction = record(recorded, dialog, target)
        val decision = windowMatcher.match(replay, extractor.windowSelector(dialog, recorded, null))

        assertTrue("Dismissed dialog should not select the activity window: $decision", decision is WindowDecision.NotFound)
        assertTrue("The activity must not contain the dialog target", replay.windows.none { it.nodes.values.any { node -> node.text == "OK" } })
        assertEquals("OK", extraction.selector.strategies.single().value)
    }

    @Test
    fun `removing Internet fails instead of accepting a near duplicate`() {
        val recorded = nearDuplicateButtons(true)
        val replay = nearDuplicateButtons(false)
        val recordedWindow = recorded.windows.single()
        val target = recordedWindow.nodes.values.single { it.text == "Internet" }
        val extraction = record(recorded, recordedWindow, target)
        val replayWindow = matchWindow(replay, recordedWindow)
        val decision = selectorMatcher.match(replayWindow, extraction.selector)

        assertTrue("Removed target must not be accepted as a near duplicate: $decision", decision !is MatchDecision.Accepted)
        assertTrue(
            "Removed target should produce LowConfidence, not ambiguity",
            decision is MatchDecision.LowConfidence
        )
    }

    @Test
    fun `an accessibility overlay at replay does not steal the app window`() {
        val recorded = overlayScreen(false)
        val replay = overlayScreen(true)
        val recordedWindow = recorded.windows.single()
        val target = recordedWindow.nodes.values.single { it.text == "Continue" }
        val extraction = record(recorded, recordedWindow, target)
        val replayWindow = matchWindow(replay, recordedWindow)
        val decision = selectorMatcher.match(replayWindow, extraction.selector)

        assertEquals(recordedWindow.windowId, replayWindow.windowId)
        assertAcceptedNode(decision, replayWindow.nodes.values.single { it.text == "Continue" }.nodeId)
    }

    private fun record(
        snapshot: WindowSetSnapshot,
        window: WindowSnapshot,
        target: NodeSnapshot
    ) = extractor.extract(target, window, centre(target), snapshot)

    private fun matchWindow(replay: WindowSetSnapshot, recordedWindow: WindowSnapshot): WindowSnapshot {
        val recorded = windowSetFor(recordedWindow, replay)
        val selector = extractor.windowSelector(recordedWindow, recorded, null)
        val decision = windowMatcher.match(replay, selector)
        assertTrue("Replay window was not selected: $decision", decision is WindowDecision.Selected)
        return (decision as WindowDecision.Selected).window
    }

    private fun windowSetFor(window: WindowSnapshot, snapshot: WindowSetSnapshot): WindowSetSnapshot =
        snapshot.copy(windows = listOf(window))

    private fun assertAcceptedNode(decision: MatchDecision, expectedNodeId: Int) {
        assertTrue("Expected Accepted, got $decision", decision is MatchDecision.Accepted)
        assertEquals(expectedNodeId, (decision as MatchDecision.Accepted).target.node.nodeId)
    }

    private fun openButtonForItem(window: WindowSnapshot, item: Int): NodeSnapshot {
        val row = window.nodes.values.single { it.text == "Item $item" && it.clickable }
        return window.nodes.values.single { it.text == "Open" && it.parentId == row.nodeId }
    }

    private fun settingsRow(window: WindowSnapshot, title: String): NodeSnapshot =
        window.nodes.values.single { it.text == title && it.clickable }

    private fun centre(node: NodeSnapshot): IntPoint = IntPoint(
        (node.boundsInScreen.left + node.boundsInScreen.right) / 2,
        (node.boundsInScreen.top + node.boundsInScreen.bottom) / 2
    )
}

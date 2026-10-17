package com.shortcuts.app.automation.integration

import com.shortcuts.app.automation.capture.SelectorExtraction
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
import org.junit.Test

class RecordMatchRoundTripTest {

    private val fingerprinter = DefaultScreenFingerprinter()
    private val extractor = SelectorExtractor(fingerprinter, TextTokenizer { "token:$it" })
    private val windowMatcher = WindowMatcher(fingerprinter, titleTokenizer = { "token:$it" })
    private val selectorMatcher = SelectorMatcher()

    @Test
    fun `every actionable corpus target round trips to exactly itself`() {
        val ambiguousCases = mutableListOf<String>()
        var cases = 0

        engineV2ScreenCorpus().forEach { corpus ->
            corpus.snapshot.windows
                .filter { it.packageName in corpus.targetPackages }
                .forEach { window ->
                    window.actionableNodesForRoundTrip().forEach { node ->
                        cases++
                        val key = "${corpus.name}/window-${window.windowId}/node-${node.nodeId}"
                        val extraction = captureAtNodeCentre(corpus.snapshot, window, node)
                        val selectedWindow = selectRecordedWindow(corpus.snapshot, window)
                        val decision = selectorMatcher.match(selectedWindow, extraction.selector)

                        when (decision) {
                            is MatchDecision.Accepted -> assertEquals(
                                "$key accepted the wrong node",
                                extraction.targetNodeId,
                                decision.target.node.nodeId
                            )

                            is MatchDecision.Ambiguous -> {
                                ambiguousCases += key
                                assertTrue(
                                    "$key is ambiguous with node ${decision.second.node.nodeId}; " +
                                        "the explicit ambiguity allow-list must be reviewed",
                                    key in EXPECTED_AMBIGUOUS_CASES
                                )
                            }

                            is MatchDecision.LowConfidence -> error(
                                "$key was not replayable: best=${decision.best?.node?.nodeId}, " +
                                    "score=${decision.best?.score}, candidates=${decision.candidates.map { it.node.nodeId }}"
                            )
                        }
                    }
                }
        }

        assertEquals(
            "Ambiguity allow-list changed; explicitly review every indistinguishable target",
            EXPECTED_AMBIGUOUS_CASES,
            ambiguousCases.toSet()
        )
        assertEquals(
            "The repeated Open buttons must all be distinguished by their row context",
            0,
            ambiguousCases.count { it.startsWith("sixty-row-list/") }
        )
        assertTrue("Corpus should exercise more than 20 actionable targets", cases > 20)
    }

    @Test
    fun `recording uses the hit-tested actionable node at the physical centre`() {
        val corpus = engineV2ScreenCorpus().first { it.name == "sixty-row-list" }
        val window = corpus.snapshot.windows.single()
        val row = window.nodes.getValue(110)
        val point = centre(row)
        val hit = corpus.snapshot.hitTest(point.x, point.y)

        assertTrue("The fixture centre must hit a node", hit != null)
        assertEquals(row.nodeId, hit!!.target.nodeId)
        assertEquals(row.nodeId, captureAtNodeCentre(corpus.snapshot, window, row).targetNodeId)
    }

    private fun captureAtNodeCentre(
        snapshot: WindowSetSnapshot,
        window: WindowSnapshot,
        node: NodeSnapshot
    ): SelectorExtraction {
        val point = centre(node)
        val hit = snapshot.hitTest(point.x, point.y)
        assertTrue("Centre of node ${node.nodeId} must hit an actionable node", hit != null)
        assertEquals("Centre hit the wrong window", window.windowId, hit!!.window.windowId)
        assertEquals("Centre hit the wrong actionable node", node.nodeId, hit.target.nodeId)
        return extractor.extract(hit.target, hit.window, point, snapshot)
    }

    private fun selectRecordedWindow(
        snapshot: WindowSetSnapshot,
        recordedWindow: WindowSnapshot
    ): WindowSnapshot {
        val selector = extractor.windowSelector(recordedWindow, snapshot, null)
        val decision = windowMatcher.match(snapshot, selector)
        assertTrue("Recorded window was not selected: $decision", decision is WindowDecision.Selected)
        return (decision as WindowDecision.Selected).window
    }

    private fun centre(node: NodeSnapshot): IntPoint = IntPoint(
        (node.boundsInScreen.left + node.boundsInScreen.right) / 2,
        (node.boundsInScreen.top + node.boundsInScreen.bottom) / 2
    )

    companion object {
        /** Empty by design: every current corpus target has enough exact or structural evidence. */
        private val EXPECTED_AMBIGUOUS_CASES: Set<String> = emptySet()
    }
}

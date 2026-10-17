package com.shortcuts.app.automation.integration

import com.shortcuts.app.automation.core.matching.AnchorKind
import com.shortcuts.app.automation.core.matching.CandidateScorer
import com.shortcuts.app.automation.core.matching.MatchDecision
import com.shortcuts.app.automation.core.matching.MatchOptions
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.descStrategy
import com.shortcuts.app.automation.core.matching.elementSelector
import com.shortcuts.app.automation.core.matching.textStrategy
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.labelOf
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests verifying Engine v2 matcher fixes on real Compose accessibility trees
 * from Pixel 6a (Android 16):
 * - Bug 1: merged-semantics duplicates collapse (Settings gear & Save buttons).
 * - Bug 2: floating label support for editable fields (straddling/floating within 48 px above).
 * - Bug 3: actionable label-bearing ancestors included in candidate generation directly.
 */
class ComposeMatcherDeviceTreesRegressionTest {

    private val matcher = SelectorMatcher()
    private val scorer = CandidateScorer()

    /**
     * Real accessibility tree from Pixel 6a compose_basic screen:
     * View ''            clk  [36,280][220,387]
     *   TextView 'Save'        [89,310][167,358]
     * View ''            clk  [36,423][143,530]
     *   View desc='Settings gear'  [63,450][116,503]
     */
    private fun composeBasicWindow(): WindowSnapshot = window("com.example.compose", bounds = r(0, 0, 1080, 2400)) {
        node("android.widget.FrameLayout", id = 1, bounds = r(0, 0, 1080, 2400)) {
            // Save button
            node(
                className = "android.view.View",
                id = 10,
                bounds = r(36, 280, 220, 387),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            ) {
                node(
                    className = "android.widget.TextView",
                    id = 11,
                    text = "Save",
                    bounds = r(89, 310, 167, 358),
                    clickable = false
                )
            }
            // Settings gear button
            node(
                className = "android.view.View",
                id = 20,
                bounds = r(36, 423, 143, 530),
                clickable = true,
                actions = setOf(NodeAction.CLICK)
            ) {
                node(
                    className = "android.view.View",
                    id = 21,
                    contentDescription = "Settings gear",
                    bounds = r(63, 450, 116, 503),
                    clickable = false
                )
            }
        }
    }

    /**
     * Real accessibility tree from Pixel 6a text_compose screen:
     * EditText ''        clk  [36,280][1044,423]
     *     TextView 'Search'    [72,333][203,389]
     * EditText ''        clk  [36,476][1044,603]
     *     TextView 'Message'   [72,512][244,568]
     */
    private fun textComposeWindow(searchFocusedFloating: Boolean = false): WindowSnapshot =
        window("com.example.compose", bounds = r(0, 0, 1080, 2400)) {
            node("android.widget.FrameLayout", id = 1, bounds = r(0, 0, 1080, 2400)) {
                // Search field
                val searchLabelBounds = if (searchFocusedFloating) {
                    // Floating label moves up onto outline, straddling top=280
                    r(72, 250, 203, 300)
                } else {
                    r(72, 333, 203, 389)
                }
                node(
                    className = "android.widget.EditText",
                    id = 30,
                    bounds = r(36, 280, 1044, 423),
                    clickable = true,
                    editable = true,
                    focusable = true,
                    focused = searchFocusedFloating,
                    actions = setOf(NodeAction.CLICK, NodeAction.SET_TEXT, NodeAction.FOCUS)
                ) {
                    node(
                        className = "android.widget.TextView",
                        id = 31,
                        text = "Search",
                        bounds = searchLabelBounds,
                        clickable = false
                    )
                }

                // Message field
                node(
                    className = "android.widget.EditText",
                    id = 40,
                    bounds = r(36, 476, 1044, 603),
                    clickable = true,
                    editable = true,
                    focusable = true,
                    actions = setOf(NodeAction.CLICK, NodeAction.SET_TEXT, NodeAction.FOCUS)
                ) {
                    node(
                        className = "android.widget.TextView",
                        id = 41,
                        text = "Message",
                        bounds = r(72, 512, 244, 568),
                        clickable = false
                    )
                }
            }
        }

    // --- BUG 1 TESTS ---

    @Test
    fun `bug 1 - gear button merged-semantics duplicate is collapsed and parent View accepted without ambiguity`() {
        val win = composeBasicWindow()
        val gearParentBounds = NormalizedRect(
            36f / 1080f, 423f / 2400f, 143f / 1080f, 530f / 2400f
        )

        // Recorded selector for gear button
        val selector = elementSelector(
            strategies = listOf(descStrategy("Settings gear")),
            expectedClass = "android.view.View",
            boundsInWindow = gearParentBounds,
            requiredActions = setOf(NodeAction.CLICK)
        )

        // Test with gesture fallback so child View (id 21) also passes hard filters
        val decision = matcher.match(win, selector, MatchOptions(allowGestureFallback = true))

        assertTrue("Expected Accepted for gear parent View, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(20, accepted.target.node.nodeId)
        assertTrue("Target score must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
        assertEquals(AnchorKind.CONTENT_DESCRIPTION, accepted.target.breakdown.anchorKind)

        // Child node 21 must have been dropped from candidates
        assertFalse(
            "Label-source child node 21 must be dropped from candidate set",
            accepted.candidates.any { it.node.nodeId == 21 }
        )
    }

    @Test
    fun `bug 1 - Save button merged-semantics duplicate is collapsed and parent View accepted without ambiguity`() {
        val win = composeBasicWindow()
        val saveParentBounds = NormalizedRect(
            36f / 1080f, 280f / 2400f, 220f / 1080f, 387f / 2400f
        )

        val selector = elementSelector(
            strategies = listOf(textStrategy("Save")),
            expectedClass = "android.view.View",
            boundsInWindow = saveParentBounds,
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector, MatchOptions(allowGestureFallback = true))

        assertTrue("Expected Accepted for Save parent View, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(10, accepted.target.node.nodeId)
        assertTrue("Target score must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
        assertEquals(AnchorKind.TEXT, accepted.target.breakdown.anchorKind)

        // Child TextView 11 must have been dropped
        assertFalse(
            "Label-source child TextView 11 must be dropped from candidate set",
            accepted.candidates.any { it.node.nodeId == 11 }
        )
    }

    // --- BUG 2 TESTS ---

    @Test
    fun `bug 2 - EditText whose label child is straddling top edge still gets label Search`() {
        // Child at [72, 250][203, 300] straddling top=280
        val win = textComposeWindow(searchFocusedFloating = true)
        val editText = win.node(30)!!

        val derived = win.labelOf(editText)
        assertEquals("Search", derived.text)
        assertEquals(31, derived.sourceNodeId)
    }

    @Test
    fun `bug 2 - EditText whose label child is within 48 px above top edge still gets label Search`() {
        // Child completely within 48 px above top=280: e.g. [72, 240][203, 275]
        val win = window("com.example.compose", bounds = r(0, 0, 1080, 2400)) {
            node(
                className = "android.widget.EditText",
                id = 30,
                bounds = r(36, 280, 1044, 423),
                editable = true,
                clickable = true
            ) {
                node(
                    className = "android.widget.TextView",
                    id = 31,
                    text = "Search",
                    bounds = r(72, 240, 203, 275)
                )
            }
        }
        val editText = win.node(30)!!
        val derived = win.labelOf(editText)
        assertEquals("Search", derived.text)
        assertEquals(31, derived.sourceNodeId)
    }

    @Test
    fun `bug 2 - EditText prefers fully-inside label over floating label`() {
        val win = window("com.example.compose", bounds = r(0, 0, 1080, 2400)) {
            node(
                className = "android.widget.EditText",
                id = 30,
                bounds = r(36, 280, 1044, 423),
                editable = true,
                clickable = true
            ) {
                // Floating label child
                node(
                    className = "android.widget.TextView",
                    id = 31,
                    text = "Floating",
                    bounds = r(72, 250, 203, 300)
                )
                // Inside child (e.g. placeholder)
                node(
                    className = "android.widget.TextView",
                    id = 32,
                    text = "Placeholder",
                    bounds = r(72, 330, 203, 380)
                )
            }
        }
        val editText = win.node(30)!!
        val derived = win.labelOf(editText)
        assertEquals("Placeholder", derived.text)
        assertEquals(32, derived.sourceNodeId)
    }

    @Test
    fun `bug 2 - strict rule kept for non-editable nodes`() {
        val win = window("com.example.compose", bounds = r(0, 0, 1080, 2400)) {
            node(
                className = "android.view.View",
                id = 10,
                bounds = r(36, 280, 220, 387),
                editable = false,
                clickable = true
            ) {
                // Label straddling top=280: [72, 250][203, 300]
                node(
                    className = "android.widget.TextView",
                    id = 11,
                    text = "Straddling",
                    bounds = r(72, 250, 203, 300)
                )
            }
        }
        val nonEditableView = win.node(10)!!
        val derived = win.labelOf(nonEditableView)
        // Must NOT receive the straddling label
        assertNull(derived.text)
        assertNull(derived.sourceNodeId)
    }

    // --- BUG 3 TESTS ---

    @Test
    fun `bug 3 - gear parent View accepted when only CONTENT_DESCRIPTION_EXACT anchor fires`() {
        val win = composeBasicWindow()
        val selector = elementSelector(
            strategies = listOf(descStrategy("Settings gear")),
            expectedClass = "android.view.View",
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for gear parent View, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(20, accepted.target.node.nodeId)
        assertTrue("Score must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
    }

    @Test
    fun `bug 3 - Save parent View accepted when only TEXT_EXACT anchor fires`() {
        val win = composeBasicWindow()
        val selector = elementSelector(
            strategies = listOf(textStrategy("Save")),
            expectedClass = "android.view.View",
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for Save parent View, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(10, accepted.target.node.nodeId)
        assertTrue("Score must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
    }

    @Test
    fun `bug 3 - Search EditText with focused floating label accepted for SET_TEXT selector`() {
        val win = textComposeWindow(searchFocusedFloating = true)
        val searchBounds = NormalizedRect(
            36f / 1080f, 280f / 2400f, 1044f / 1080f, 423f / 2400f
        )

        val selector = elementSelector(
            strategies = listOf(textStrategy("Search")),
            expectedClass = "android.widget.EditText",
            boundsInWindow = searchBounds,
            requiredActions = setOf(NodeAction.SET_TEXT)
        )

        val decision = matcher.match(win, selector, MatchOptions(isTextTarget = true))
        assertTrue("Expected Accepted for Search EditText, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(30, accepted.target.node.nodeId)
        assertTrue("Score must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
    }

    @Test
    fun `bug 3 - Message EditText accepted and NOT confused with Search EditText`() {
        val win = textComposeWindow(searchFocusedFloating = true)
        val messageBounds = NormalizedRect(
            36f / 1080f, 476f / 2400f, 1044f / 1080f, 603f / 2400f
        )

        val selector = elementSelector(
            strategies = listOf(textStrategy("Message")),
            expectedClass = "android.widget.EditText",
            boundsInWindow = messageBounds,
            requiredActions = setOf(NodeAction.SET_TEXT)
        )

        val decision = matcher.match(win, selector, MatchOptions(isTextTarget = true))
        assertTrue("Expected Accepted for Message EditText, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(40, accepted.target.node.nodeId)
        assertTrue("Score must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)

        // Ensure Search field is not confused with Message
        assertFalse(
            "Search field (id 30) must not be accepted for Message selector",
            accepted.candidates.any { it.node.nodeId == 30 && it.score >= 75 }
        )
    }

    @Test
    fun `bug 3 - actionable ancestor up to 3 levels inherits label anchor but 4 levels does not`() {
        val win = window("com.example.compose", bounds = r(0, 0, 1080, 2400)) {
            // Level 4 (ancestor of leaf) - actionable
            node("android.view.View", id = 1, clickable = true, actions = setOf(NodeAction.CLICK)) {
                // Level 3 - actionable
                node("android.view.View", id = 2, clickable = true, actions = setOf(NodeAction.CLICK)) {
                    // Level 2 - not actionable container
                    node("android.view.ViewGroup", id = 3) {
                        // Level 1 - not actionable container
                        node("android.view.ViewGroup", id = 4) {
                            // Leaf (Level 0)
                            node("android.widget.TextView", id = 5, text = "Target")
                        }
                    }
                }
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Target")),
            expectedClass = "android.view.View",
            requiredActions = setOf(NodeAction.CLICK)
        )

        // Node 2 is 3 levels above leaf (4 -> 3 -> 2): eligible actionable ancestor!
        val score2 = scorer.scoreCandidate(win.node(2)!!, win, selector)
        assertEquals(AnchorKind.TEXT, score2.breakdown.anchorKind)

        // Node 1 is 4 levels above leaf (4 -> 3 -> 2 -> 1): beyond 3 levels, does not get TEXT anchor
        val score1 = scorer.scoreCandidate(win.node(1)!!, win, selector)
        assertTrue("Node 1 must not get TEXT anchor, was ${score1.breakdown.anchorKind}", score1.breakdown.anchorKind != AnchorKind.TEXT)

        // Matcher should accept Node 2, not Node 1
        val decision = matcher.match(win, selector)
        assertTrue(decision is MatchDecision.Accepted)
        assertEquals(2, (decision as MatchDecision.Accepted).target.node.nodeId)
    }
}

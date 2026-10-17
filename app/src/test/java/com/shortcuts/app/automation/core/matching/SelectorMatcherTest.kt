package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.model.AncestorSelector
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeRole
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RowContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorMatcherTest {

    private val matcher = SelectorMatcher()

    @Test
    fun `exact ID beats substring ID`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                // Substring ID candidate
                node(
                    className = "android.widget.Button",
                    id = 10,
                    resourceId = "com.example.app:id/submit_button",
                    text = "Submit",
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
                // Exact ID candidate
                node(
                    className = "android.widget.Button",
                    id = 20,
                    resourceId = "com.example.app:id/submit",
                    text = "Submit",
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/submit", stability = LocatorStability.STABLE)),
            expectedClass = "android.widget.Button",
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted but got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(20, accepted.target.node.nodeId)
    }

    @Test
    fun `resource id is never searched as text`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                // Node that contains resource id string as its text, but has NO resource id
                node(
                    className = "android.widget.TextView",
                    id = 2,
                    resourceId = null,
                    text = "com.example.app:id/action_button"
                )
            }
        }

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/action_button"))
        )

        val decision = matcher.match(win, selector)
        // Node 2 must not be treated as a resource ID match
        assertTrue("Expected LowConfidence because resource id is never searched as text", decision is MatchDecision.LowConfidence)
    }

    @Test
    fun `Internet does not match Internet and SIM or Internet settings`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.TextView", id = 10, text = "Internet & SIM")
                node("android.widget.TextView", id = 20, text = "Internet settings")
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Internet"))
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected LowConfidence because substring text match is rejected", decision is MatchDecision.LowConfidence)
    }

    @Test
    fun `two equal Delete rows result in Ambiguous`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 10,
                    text = "Delete",
                    bounds = r(100, 100, 300, 200),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
                node(
                    className = "android.widget.Button",
                    id = 20,
                    text = "Delete",
                    bounds = r(100, 300, 300, 400),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }

        // Selector matches both with high confidence (anchor 60 + class 8 + action 5 + bounds proximity = 79)
        val selector = elementSelector(
            strategies = listOf(textStrategy("Delete")),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.05f, 0.3f, 0.2f),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Ambiguous when two equal rows match, got $decision", decision is MatchDecision.Ambiguous)
    }

    @Test
    fun `row sibling text picks the right one of 60 near-identical rows`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 60000)) {
            node("androidx.recyclerview.widget.RecyclerView", id = 1) {
                for (i in 1..60) {
                    val rowId = 1000 + i
                    val textId = 2000 + i
                    val buttonId = 3000 + i
                    node("android.widget.LinearLayout", id = rowId, bounds = r(0, (i - 1) * 100, 1000, i * 100)) {
                        node("android.widget.TextView", id = textId, text = "Item $i", bounds = r(20, (i - 1) * 100 + 10, 400, i * 100 - 10))
                        node(
                            className = "android.widget.Button",
                            id = buttonId,
                            text = "Open",
                            bounds = r(500, (i - 1) * 100 + 10, 800, i * 100 - 10),
                            clickable = true,
                            actions = setOf(NodeAction.CLICK)
                        )
                    }
                }
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Open")),
            expectedClass = "android.widget.Button",
            rowContext = RowContext(
                containerClass = "android.widget.LinearLayout",
                containerResourceId = null,
                siblingText = listOf("Item 45"),
                siblingContentDescriptions = emptyList(),
                collectionRow = null,
                collectionColumn = null
            ),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for Item 45 button, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(3045, accepted.target.node.nodeId)
    }

    @Test
    fun `ancestor path disambiguates equal labels`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                // Section 1: Network
                node("android.widget.LinearLayout", id = 10, resourceId = "com.example.app:id/network_section") {
                    node("android.widget.TextView", id = 11, text = "Details", clickable = true, actions = setOf(NodeAction.CLICK))
                }
                // Section 2: Display
                node("android.widget.LinearLayout", id = 20, resourceId = "com.example.app:id/display_section") {
                    node("android.widget.TextView", id = 21, text = "Details", clickable = true, actions = setOf(NodeAction.CLICK))
                }
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Details")),
            ancestorChain = listOf(
                AncestorSelector(
                    resourceId = "com.example.app:id/network_section",
                    className = "android.widget.LinearLayout",
                    role = null,
                    siblingIndex = null
                )
            ),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for network section Details, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(11, accepted.target.node.nodeId)
    }

    @Test
    fun `sibling index disambiguates repeated icons`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.LinearLayout", id = 1, bounds = r(0, 0, 1000, 300)) {
                node("android.widget.ImageView", id = 10, bounds = r(100, 100, 200, 200), contentDescription = "More", clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.ImageView", id = 20, bounds = r(300, 100, 400, 200), contentDescription = "More", clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.ImageView", id = 30, bounds = r(500, 100, 600, 200), contentDescription = "More", clickable = true, actions = setOf(NodeAction.CLICK))
            }
        }

        // Expected siblingIndex = 1 (second icon) with bounds around icon 20
        val selector = elementSelector(
            strategies = listOf(descStrategy("More")),
            expectedClass = "android.widget.ImageView",
            siblingIndex = 1,
            boundsInWindow = NormalizedRect(0.3f, 0.05f, 0.4f, 0.1f),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for icon at index 1, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(20, accepted.target.node.nodeId)
    }

    @Test
    fun `generic class cannot outweigh an identity contradiction`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 2,
                    resourceId = "com.example.app:id/cancel",
                    bounds = r(100, 100, 300, 200),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/submit", stability = LocatorStability.STABLE)),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.05f, 0.3f, 0.1f),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Identity contradiction must reject or produce LowConfidence", decision is MatchDecision.LowConfidence)
    }

    @Test
    fun `best score below 75 fails as LowConfidence`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.TextView",
                    id = 2,
                    text = "Partial Match"
                )
            }
        }

        // Only matches text anchor (60) with no corroborations -> 60 < 75
        val selector = elementSelector(
            strategies = listOf(textStrategy("Partial Match"))
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected LowConfidence when score < 75, got $decision", decision is MatchDecision.LowConfidence)
        val lowConf = decision as MatchDecision.LowConfidence
        val best = lowConf.best
        assertTrue(best != null && best.score < 75)
    }

    @Test
    fun `margin below 12 fails as Ambiguous`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                // Candidate 1: exact ID (70) + class (8) + action (5) + bounds (4) = 87
                node(
                    className = "android.widget.Button",
                    id = 10,
                    resourceId = "com.example.app:id/btn1",
                    bounds = r(100, 100, 300, 200),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
                // Candidate 2: exact contentDesc (65) + class (8) + action (5) + bounds (5) = 83
                node(
                    className = "android.widget.Button",
                    id = 20,
                    contentDescription = "Action Button",
                    bounds = r(100, 300, 300, 400),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }

        val selector = elementSelector(
            strategies = listOf(
                idStrategy("com.example.app:id/btn1", stability = LocatorStability.LIKELY_STABLE, rank = 0),
                descStrategy("Action Button", stability = LocatorStability.LIKELY_STABLE, rank = 1)
            ),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.05f, 0.3f, 0.25f),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        // Score 1 is 87, Score 2 is 83. Margin is 4 < 12. Both >= 75.
        assertTrue("Expected Ambiguous when margin < 12, got $decision", decision is MatchDecision.Ambiguous)
    }

    @Test
    fun `hidden disabled empty-bounds wrong-package wrong-window nodes are rejected`() {
        val win = window("com.example.app", id = 1) {
            node("android.widget.FrameLayout", id = 1) {
                // Hidden node
                node("android.widget.Button", id = 10, text = "Target", visible = false, clickable = true)
                // Disabled node
                node("android.widget.Button", id = 20, text = "Target", enabled = false, clickable = true)
                // Empty bounds node
                node("android.widget.Button", id = 30, text = "Target", bounds = r(0, 0, 0, 0), clickable = true)
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Target")),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected LowConfidence because all candidates fail hard filters, got $decision", decision is MatchDecision.LowConfidence)
        assertEquals(0, (decision as MatchDecision.LowConfidence).candidates.size)
    }

    @Test
    fun `shuffled candidate order gives the same decision`() {
        val win1 = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.Button", id = 10, resourceId = "com.example.app:id/b1", text = "One", clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.Button", id = 20, resourceId = "com.example.app:id/b2", text = "Two", clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.Button", id = 30, resourceId = "com.example.app:id/b3", text = "Three", clickable = true, actions = setOf(NodeAction.CLICK))
            }
        }

        val win2 = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.Button", id = 30, resourceId = "com.example.app:id/b3", text = "Three", clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.Button", id = 10, resourceId = "com.example.app:id/b1", text = "One", clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.Button", id = 20, resourceId = "com.example.app:id/b2", text = "Two", clickable = true, actions = setOf(NodeAction.CLICK))
            }
        }

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/b2")),
            expectedClass = "android.widget.Button",
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision1 = matcher.match(win1, selector)
        val decision2 = matcher.match(win2, selector)

        assertTrue(decision1 is MatchDecision.Accepted)
        assertTrue(decision2 is MatchDecision.Accepted)
        val target1 = (decision1 as MatchDecision.Accepted).target
        val target2 = (decision2 as MatchDecision.Accepted).target
        assertEquals(target1.node.nodeId, target2.node.nodeId)
        assertEquals(20, target1.node.nodeId)
        assertEquals(decision1.candidates.map { it.node.nodeId }, decision2.candidates.map { it.node.nodeId })
    }

    @Test
    fun `scrolled list picks the correct row by row context`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true) {
                    for (i in 1..10) {
                        val rowId = 100 + i
                        val top = (i - 1) * 100
                        node("android.widget.LinearLayout", id = rowId, bounds = r(0, top, 1000, top + 90)) {
                            node("android.widget.TextView", id = 1000 + i, text = "Item $i", bounds = r(20, top + 10, 300, top + 80))
                            node(
                                className = "android.widget.Button",
                                id = 2000 + i,
                                text = "Open",
                                bounds = r(800, top + 10, 950, top + 80),
                                clickable = true,
                                actions = setOf(NodeAction.CLICK)
                            )
                        }
                    }
                }
            }
        }

        // Selector recorded for Item 5, but placed at Item 2's bounds (e.g. after a scroll)
        val selector = elementSelector(
            strategies = listOf(textStrategy("Open")),
            expectedClass = "android.widget.Button",
            rowContext = RowContext(
                containerClass = "android.widget.LinearLayout",
                containerResourceId = null,
                siblingText = listOf("Item 5"),
                siblingContentDescriptions = emptyList(),
                collectionRow = null,
                collectionColumn = null
            ),
            boundsInWindow = NormalizedRect(0.8f, 0.05f, 0.95f, 0.09f), // bounds near Item 2 (y around 100..190)
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for Item 5 button despite scrolled bounds, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(2005, accepted.target.node.nodeId)
        assertTrue("inCollection must be true", accepted.target.breakdown.inCollection)
        assertEquals(2, accepted.target.breakdown.boundsProximityCorroboration)
        assertEquals(12, accepted.target.breakdown.rowContextCorroboration)
        assertFalse(accepted.target.breakdown.centerOutsideExpandedBounds)
    }

    @Test
    fun `inserted row keeps exact text match above 75`() {
        // Replay window where an extra row was inserted above "Battery"
        val win = window("com.android.settings", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 10, scrollable = true) {
                    node("android.widget.LinearLayout", id = 100, text = "About phone", bounds = r(0, 100, 1000, 250), clickable = true, actions = setOf(NodeAction.CLICK))
                    node("android.widget.LinearLayout", id = 101, text = "Network", bounds = r(0, 260, 1000, 410), clickable = true, actions = setOf(NodeAction.CLICK))
                    node("android.widget.LinearLayout", id = 102, text = "Battery", bounds = r(0, 420, 1000, 570), clickable = true, actions = setOf(NodeAction.CLICK)) {
                        node("android.widget.TextView", id = 1002, text = "Battery usage, battery saver", bounds = r(30, 480, 800, 550))
                    }
                }
            }
        }

        // Selector recorded when "Battery" was at index 1 and top was 260
        val selector = elementSelector(
            strategies = listOf(textStrategy("Battery")),
            expectedClass = "android.widget.LinearLayout",
            ancestorChain = listOf(
                AncestorSelector(resourceId = null, className = "androidx.recyclerview.widget.RecyclerView", role = NodeRole.UNKNOWN, siblingIndex = 0),
                AncestorSelector(resourceId = null, className = "android.widget.FrameLayout", role = NodeRole.UNKNOWN, siblingIndex = null)
            ),
            siblingIndex = 1, // now it is at siblingIndex 2
            rowContext = RowContext(
                containerClass = "androidx.recyclerview.widget.RecyclerView",
                containerResourceId = null,
                siblingText = listOf("Battery usage, battery saver"),
                siblingContentDescriptions = emptyList(),
                collectionRow = null,
                collectionColumn = null
            ),
            boundsInWindow = NormalizedRect(0f, 0.13f, 1f, 0.205f), // old bounds 260..410
            requiredActions = setOf(NodeAction.CLICK),
            scrollContainer = elementSelector(
                strategies = emptyList(),
                expectedClass = "androidx.recyclerview.widget.RecyclerView"
            )
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected Accepted for Battery row after insertion, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(102, accepted.target.node.nodeId)
        assertTrue("Confidence must be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
        assertTrue("inCollection must be true", accepted.target.breakdown.inCollection)
        assertEquals(0, accepted.target.breakdown.siblingIndexCorroboration)
        assertEquals(0, accepted.target.breakdown.centerOutsideBoundsPenalty)
        assertEquals(12, accepted.target.breakdown.rowContextCorroboration)
    }

    @Test
    fun `a list item whose row context does NOT match is rejected even if it sits exactly at the recorded position`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true) {
                    // Row at exactly the recorded bounds, but row context has "Wrong Item"
                    node("android.widget.LinearLayout", id = 100, bounds = r(0, 100, 1000, 200)) {
                        node("android.widget.TextView", id = 101, text = "Wrong Item", bounds = r(20, 110, 300, 180))
                        node(
                            className = "android.widget.Button",
                            id = 102,
                            text = "Open",
                            bounds = r(800, 110, 950, 180),
                            clickable = true,
                            actions = setOf(NodeAction.CLICK)
                        )
                    }
                }
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Open")),
            expectedClass = "android.widget.Button",
            rowContext = RowContext(
                containerClass = "android.widget.LinearLayout",
                containerResourceId = null,
                siblingText = listOf("Correct Item"),
                siblingContentDescriptions = emptyList(),
                collectionRow = null,
                collectionColumn = null
            ),
            boundsInWindow = NormalizedRect(0.8f, 0.055f, 0.95f, 0.09f), // exactly matches node 102 bounds (800..950, 110..180)
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected LowConfidence because row context contradicted with -20 penalty, got $decision", decision is MatchDecision.LowConfidence)
        val lowConf = decision as MatchDecision.LowConfidence
        val candidate = lowConf.best
        assertTrue("Candidate must exist", candidate != null)
        assertTrue("inCollection must be true", candidate!!.breakdown.inCollection)
        assertEquals("Bounds proximity bonus is retained in collections", 6, candidate.breakdown.boundsProximityCorroboration)
        assertEquals("Row context mismatch penalty in collection must be 20", 20, candidate.breakdown.rowContextSimilarityPenalty)
        assertTrue("Candidate score must be below 75 confidence floor, was ${candidate.score}", candidate.score < 75)
    }

    @Test
    fun `non-list near-duplicate behaviour unchanged - Internet never accepts Internet and SIM`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.Button", id = 10, text = "Internet & SIM", bounds = r(100, 700, 400, 840), clickable = true, actions = setOf(NodeAction.CLICK))
                node("android.widget.Button", id = 12, text = "Internet settings", bounds = r(720, 700, 980, 840), clickable = true, actions = setOf(NodeAction.CLICK))
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Internet")),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.42f, 0.35f, 0.70f, 0.42f),
            requiredActions = setOf(NodeAction.CLICK)
        )

        val decision = matcher.match(win, selector)
        assertTrue("Expected LowConfidence because substring text match is rejected, got $decision", decision is MatchDecision.LowConfidence)
        val lowConf = decision as MatchDecision.LowConfidence
        lowConf.candidates.forEach { candidate ->
            assertFalse("Non-list candidate must not be inCollection", candidate.breakdown.inCollection)
        }
    }
}

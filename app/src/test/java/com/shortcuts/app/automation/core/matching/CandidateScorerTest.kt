package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.model.AncestorSelector
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RowContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateScorerTest {

    private val scorer = CandidateScorer()

    @Test
    fun `exact resource ID scores 70 anchor points plus corroborations`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 2,
                    resourceId = "com.example.app:id/submit",
                    text = "Submit",
                    bounds = r(100, 200, 300, 300),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }
        val node = win.node(2)!!

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/submit")),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.1f, 0.3f, 0.15f), // center=(0.2, 0.125), node=(0.2, 0.125) -> dist=0
            requiredActions = setOf(NodeAction.CLICK)
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        val bd = candidate.breakdown

        assertEquals(AnchorKind.RESOURCE_ID, bd.anchorKind)
        assertEquals(70, bd.anchorPoints)
        assertTrue(bd.exactClassRoleMatched)
        assertEquals(8, bd.classRoleCorroboration)
        assertEquals(6, bd.boundsProximityCorroboration) // dist = 0 -> round(6 * 1.0) = 6
        assertTrue(bd.requiredActionsSupported)
        assertEquals(5, bd.requiredActionsCorroboration)
        // 70 + 8 + 6 + 5 = 89
        assertEquals(89, candidate.score)
    }

    @Test
    fun `exact content description anchor scores 65 points`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.ImageButton",
                    id = 2,
                    contentDescription = "Navigate up",
                    bounds = r(10, 10, 90, 90),
                    clickable = true
                )
            }
        }
        val node = win.node(2)!!

        val selector = elementSelector(
            strategies = listOf(descStrategy("Navigate up")),
            expectedClass = "android.widget.ImageButton"
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        assertEquals(AnchorKind.CONTENT_DESCRIPTION, candidate.breakdown.anchorKind)
        assertEquals(65, candidate.breakdown.anchorPoints)
    }

    @Test
    fun `exact normalized text anchor scores 60 points`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.TextView",
                    id = 2,
                    text = "Welcome Back",
                    bounds = r(10, 10, 90, 90)
                )
            }
        }
        val node = win.node(2)!!

        val selector = elementSelector(
            strategies = listOf(textStrategy("Welcome Back")),
            expectedClass = "android.widget.TextView"
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        assertEquals(AnchorKind.TEXT, candidate.breakdown.anchorKind)
        assertEquals(60, candidate.breakdown.anchorPoints)
    }

    @Test
    fun `secondary exact text locator adds 4 points corroboration`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 2,
                    resourceId = "com.example.app:id/action_save",
                    text = "Save",
                    clickable = true
                )
            }
        }
        val node = win.node(2)!!

        val selector = elementSelector(
            strategies = listOf(
                idStrategy("com.example.app:id/action_save", rank = 0),
                textStrategy("Save", rank = 1)
            ),
            expectedClass = "android.widget.Button"
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        val bd = candidate.breakdown

        assertEquals(AnchorKind.RESOURCE_ID, bd.anchorKind)
        assertEquals(70, bd.anchorPoints)
        assertEquals(1, bd.secondaryLocatorsCount)
        assertEquals(4, bd.secondaryLocatorsCorroboration)
    }

    @Test
    fun `generic class cannot outweigh an identity contradiction`() {
        // Node has expected class android.widget.Button (+8) and is near recorded bounds (+6)
        // BUT has a different non-null resource ID (-35) and mismatched STABLE ID strategy (-15)
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 2,
                    resourceId = "com.example.app:id/cancel_btn",
                    text = "Cancel",
                    bounds = r(100, 100, 300, 200),
                    clickable = true
                )
            }
        }
        val node = win.node(2)!!

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/submit_btn", stability = LocatorStability.STABLE)),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.05f, 0.3f, 0.1f)
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        val bd = candidate.breakdown

        assertTrue(bd.differentResourceId)
        assertEquals(35, bd.differentResourceIdPenalty)
        assertEquals(1, bd.mismatchedStableLocatorsCount)
        assertEquals(15, bd.mismatchedStableLocatorsPenalty)

        // Class was matched (+8), coordinate was anchor (35), but penalties (-35 - 15 = -50) crush it:
        assertTrue("Final score must be well below 75, was ${candidate.score}", candidate.score < 50)
    }

    @Test
    fun `sibling index corroboration adds 5 points`() {
        val win = window("com.example.app") {
            node("android.widget.LinearLayout", id = 1) {
                node("android.widget.ImageView", id = 10, resourceId = "com.example.app:id/icon")
                node("android.widget.ImageView", id = 20, resourceId = "com.example.app:id/icon")
                node("android.widget.ImageView", id = 30, resourceId = "com.example.app:id/icon")
            }
        }
        val targetNode = win.node(20)!! // index 1 in children of node 1

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/icon")),
            siblingIndex = 1
        )

        val candidate = scorer.scoreCandidate(targetNode, win, selector)
        assertTrue(candidate.breakdown.siblingIndexMatched)
        assertEquals(5, candidate.breakdown.siblingIndexCorroboration)
    }

    @Test
    fun `ancestor similarity below 0_5 incurs 15 penalty`() {
        val win = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.ScrollView", id = 2) {
                    node("android.widget.Button", id = 3, resourceId = "com.example.app:id/btn")
                }
            }
        }
        val node = win.node(3)!!

        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/btn")),
            ancestorChain = listOf(
                AncestorSelector(resourceId = "com.example.app:id/wrong_container", className = "android.widget.RelativeLayout", role = null, siblingIndex = null),
                AncestorSelector(resourceId = "com.example.app:id/wrong_root", className = "android.widget.LinearLayout", role = null, siblingIndex = null)
            )
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        assertTrue(candidate.breakdown.ancestorSimilarityLow)
        assertEquals(15, candidate.breakdown.ancestorSimilarityPenalty)
    }

    @Test
    fun `score before contradictions is capped at 100`() {
        val win = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 2,
                    resourceId = "com.example.app:id/btn",
                    text = "Submit",
                    contentDescription = "Submit form",
                    bounds = r(100, 100, 300, 200),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }
        val node = win.node(2)!!

        // High matching evidence: ID 70 + class 8 + action 5 + bounds 6 + desc 4 + text 4 + sibling 5 = 102
        val selector = elementSelector(
            strategies = listOf(
                idStrategy("com.example.app:id/btn"),
                descStrategy("Submit form"),
                textStrategy("Submit")
            ),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.05f, 0.3f, 0.1f),
            requiredActions = setOf(NodeAction.CLICK),
            siblingIndex = 0
        )

        val candidate = scorer.scoreCandidate(node, win, selector)
        assertEquals(100, candidate.breakdown.scoreAfterCap)
        assertEquals(100, candidate.score)
    }
}

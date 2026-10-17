package com.shortcuts.app.automation.integration

import com.shortcuts.app.automation.capture.SelectorExtractor
import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.matching.AnchorKind
import com.shortcuts.app.automation.core.matching.CandidateScorer
import com.shortcuts.app.automation.core.matching.MatchDecision
import com.shortcuts.app.automation.core.matching.SelectorMatcher
import com.shortcuts.app.automation.core.matching.WindowDecision
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.matching.elementSelector
import com.shortcuts.app.automation.core.matching.idStrategy
import com.shortcuts.app.automation.core.matching.textStrategy
import com.shortcuts.app.automation.core.matching.windowSelector
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.TextTokenizer
import com.shortcuts.app.automation.model.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test suite verifying each fix required by the Engine v2 selector contract:
 * 1. Compose label mismatch: Compose button label lives on child TextView; clickable parent View matches and child does not outscore parent.
 * 2. Editable text field never emits typed value; matcher matches hintText or labelOf.
 * 3. Uniqueness is evidence (+10 unique exact anchor bonus).
 * 4. Collection regime keeps bounds proximity bonus (+6) and drops center outside penalty and sibling index.
 * 5. Window matching: (a) sole candidate window selects waiving confidence floor, (b) activity weight only applies to real activities, (c) dialog window over activity wins.
 */
class SelectorContractRegressionTest {

    private val fingerprinter = DefaultScreenFingerprinter()
    private val tokenizer = TextTokenizer { "token:$it" }
    private val extractor = SelectorExtractor(fingerprinter, tokenizer)
    private val windowMatcher = WindowMatcher(fingerprinter, titleTokenizer = { "token:$it" })
    private val selectorMatcher = SelectorMatcher()

    @Test
    fun `fix 1 compose button label lives on child TextView - matches clickable parent View and child does not outscore parent`() {
        val targetWindow = window("com.example.composeapp") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.view.View",
                    id = 10,
                    bounds = r(100, 300, 420, 450),
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                ) {
                    node(
                        className = "android.widget.TextView",
                        id = 11,
                        text = "Save",
                        bounds = r(120, 320, 400, 430),
                        clickable = false
                    )
                }
            }
        }

        // Capture side: SelectorExtractor extracts TEXT_EXACT = "Save" from child labelOf
        val hitNode = targetWindow.node(10)!!
        val extraction = extractor.extract(
            hitNode,
            targetWindow,
            IntPoint(260, 375),
            windowSet(targetWindow)
        )

        assertEquals(10, extraction.targetNodeId)
        assertEquals(11, extraction.labelSourceNodeId)
        assertTrue(extraction.selector.strategies.any { it.kind == LocatorKind.TEXT_EXACT && it.value == "Save" })
        assertEquals("android.view.View", extraction.selector.expectedClass)

        // Matching side: SelectorMatcher matches against window
        val decision = selectorMatcher.match(targetWindow, extraction.selector)
        assertTrue("Expected Accepted but got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(10, accepted.target.node.nodeId)
        assertTrue("Score should be >= 75, was ${accepted.target.score}", accepted.target.score >= 75)
        assertEquals(AnchorKind.TEXT, accepted.target.breakdown.anchorKind)

        // Also verify the child TextView does not out-score its actionable parent
        val childNode = targetWindow.node(11)!!
        val scorer = CandidateScorer()
        val parentCandidate = scorer.scoreCandidate(hitNode, targetWindow, extraction.selector)
        val childCandidate = scorer.scoreCandidate(childNode, targetWindow, extraction.selector)
        assertTrue(
            "Child candidate score (${childCandidate.score}) must not exceed parent score (${parentCandidate.score})",
            parentCandidate.score >= childCandidate.score
        )
    }

    @Test
    fun `fix 2 editable text field never emits typed value and matcher matches hintText or labelOf`() {
        // Record side: user just typed "Agam" into a field with hint "Search..."
        val recordedWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.EditText",
                    id = 2,
                    text = "Agam",
                    hintText = "Search...",
                    editable = true,
                    focusable = true,
                    resourceId = "com.example.app:id/search_box",
                    actions = setOf(NodeAction.SET_TEXT)
                )
            }
        }
        val recordedNode = recordedWindow.node(2)!!
        val extraction = extractor.extract(
            recordedNode,
            recordedWindow,
            null as IntPoint?,
            windowSet(recordedWindow)
        )

        // Never emit typed text "Agam"
        assertFalse(extraction.selector.strategies.any { it.value == "Agam" })
        // Emits hint "Search..."
        assertTrue(extraction.selector.strategies.any { it.kind == LocatorKind.TEXT_EXACT && it.value == "Search..." })

        // Replay side: field is empty, node.text is ""
        val replayWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.EditText",
                    id = 2,
                    text = "",
                    hintText = "Search...",
                    editable = true,
                    focusable = true,
                    resourceId = "com.example.app:id/search_box",
                    actions = setOf(NodeAction.SET_TEXT)
                )
            }
        }

        val decision = selectorMatcher.match(replayWindow, extraction.selector)
        assertTrue("Expected Accepted when matching empty field by hintText, got $decision", decision is MatchDecision.Accepted)
        val accepted = decision as MatchDecision.Accepted
        assertEquals(2, accepted.target.node.nodeId)
        assertTrue("Anchor should be positive", accepted.target.breakdown.anchorPoints > 0)

        // Also test Compose TextField where label lives on child TextView
        val composeTextFieldWindow = window("com.example.compose") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.view.View",
                    id = 20,
                    text = "typed value",
                    editable = true,
                    focusable = true,
                    actions = setOf(NodeAction.SET_TEXT)
                ) {
                    node(
                        className = "android.widget.TextView",
                        id = 21,
                        text = "Username",
                        editable = false
                    )
                }
            }
        }
        val composeExtraction = extractor.extract(
            composeTextFieldWindow.node(20)!!,
            composeTextFieldWindow,
            null as IntPoint?,
            windowSet(composeTextFieldWindow)
        )
        assertFalse(composeExtraction.selector.strategies.any { it.value == "typed value" })
        assertTrue(composeExtraction.selector.strategies.any { it.kind == LocatorKind.TEXT_EXACT && it.value == "Username" })

        // Replay empty Compose field
        val replayComposeWindow = window("com.example.compose") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.view.View",
                    id = 20,
                    text = "",
                    editable = true,
                    focusable = true,
                    actions = setOf(NodeAction.SET_TEXT)
                ) {
                    node(
                        className = "android.widget.TextView",
                        id = 21,
                        text = "Username",
                        editable = false
                    )
                }
            }
        }
        val composeDecision = selectorMatcher.match(replayComposeWindow, composeExtraction.selector)
        assertTrue("Expected Accepted for empty compose textfield via derived labelOf, got $composeDecision", composeDecision is MatchDecision.Accepted)
        assertEquals(20, (composeDecision as MatchDecision.Accepted).target.node.nodeId)
    }

    @Test
    fun `fix 3 uniqueness is evidence - unique exact anchor awards plus 10 bonus`() {
        val singleWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 10,
                    resourceId = "com.example.app:id/unique_action",
                    text = "Confirm",
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }
        val selector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/unique_action")),
            expectedClass = "android.widget.Button",
            requiredActions = setOf(NodeAction.CLICK)
        )

        val singleDecision = selectorMatcher.match(singleWindow, selector)
        assertTrue(singleDecision is MatchDecision.Accepted)
        val singleAccepted = singleDecision as MatchDecision.Accepted
        assertTrue("uniqueExactAnchor must be true when only 1 candidate has exact anchor", singleAccepted.target.breakdown.uniqueExactAnchor)
        assertEquals(10, singleAccepted.target.breakdown.uniqueExactAnchorBonus)

        // Multiple candidates with exact anchor -> neither gets unique anchor bonus
        val multiWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.Button",
                    id = 10,
                    resourceId = "com.example.app:id/action",
                    text = "Confirm",
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
                node(
                    className = "android.widget.Button",
                    id = 20,
                    resourceId = "com.example.app:id/action",
                    text = "Confirm",
                    clickable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }
        val multiSelector = elementSelector(
            strategies = listOf(idStrategy("com.example.app:id/action")),
            expectedClass = "android.widget.Button",
            requiredActions = setOf(NodeAction.CLICK)
        )
        val multiDecision = selectorMatcher.match(multiWindow, multiSelector)
        assertTrue(multiDecision is MatchDecision.Ambiguous)
        val ambiguous = multiDecision as MatchDecision.Ambiguous
        assertFalse("uniqueExactAnchor must be false when multiple exact anchors exist", ambiguous.best.breakdown.uniqueExactAnchor)
        assertEquals(0, ambiguous.best.breakdown.uniqueExactAnchorBonus)
        assertFalse(ambiguous.second.breakdown.uniqueExactAnchor)
        assertEquals(0, ambiguous.second.breakdown.uniqueExactAnchorBonus)
    }

    @Test
    fun `fix 4 collection regime keeps bounds proximity bonus and drops center outside penalty and sibling index`() {
        val scrollWindow = window("com.example.app", bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.ScrollView", id = 2, scrollable = true, bounds = r(0, 0, 1000, 2000)) {
                    node(
                        className = "android.widget.Button",
                        id = 10,
                        text = "Settings",
                        bounds = r(100, 200, 900, 350),
                        clickable = true,
                        actions = setOf(NodeAction.CLICK)
                    )
                }
            }
        }

        val selector = elementSelector(
            strategies = listOf(textStrategy("Settings")),
            expectedClass = "android.widget.Button",
            boundsInWindow = NormalizedRect(0.1f, 0.1f, 0.9f, 0.175f),
            requiredActions = setOf(NodeAction.CLICK),
            siblingIndex = 99
        )

        val scorer = CandidateScorer()
        val candidate = scorer.scoreCandidate(scrollWindow.node(10)!!, scrollWindow, selector)

        assertTrue("Candidate must be inCollection", candidate.breakdown.inCollection)
        assertEquals("Bounds proximity bonus (+6) must be retained in collection regime", 6, candidate.breakdown.boundsProximityCorroboration)
        assertEquals("Center outside bounds penalty must be 0 in collection regime", 0, candidate.breakdown.centerOutsideBoundsPenalty)
        assertEquals("Sibling index corroboration must be 0 in collection regime", 0, candidate.breakdown.siblingIndexCorroboration)

        val decision = selectorMatcher.match(scrollWindow, selector)
        assertTrue("Decision must be Accepted, was $decision", decision is MatchDecision.Accepted)
    }

    @Test
    fun `fix 5a a lone window on the wrong activity is rejected, a lone matching window is selected`() {
        val soleWin = window("com.example.app", id = 1, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 1)
        }
        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.TargetActivity"
        )
        // Review finding #2: being the only window of the package must never waive identity.
        val wrong = windowMatcher.match(listOf(soleWin), selector, currentActivityClass = "com.example.app.OtherActivity")
        assertTrue("A lone window on the wrong activity must not be selected, got $wrong", wrong !is WindowDecision.Selected)

        val right = windowMatcher.match(listOf(soleWin), selector, currentActivityClass = "com.example.app.TargetActivity")
        assertTrue("A lone window on the recorded activity must be selected, got $right", right is WindowDecision.Selected)
    }

    @Test
    fun `fix 5b activity weight applies only to real activity classes and ignores dialogs`() {
        val win = window("com.example.app", id = 1, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 1)
        }

        // Case 1: AlertDialog - not a real activity
        val dialogSelector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "androidx.appcompat.app.AlertDialog"
        )
        val scoredDialog = windowMatcher.scoreWindow(win, dialogSelector, currentActivityClass = "androidx.appcompat.app.AlertDialog")
        assertFalse("AlertDialog must not match as an activity", scoredDialog.breakdown.activityMatched)
        assertEquals(0.0, scoredDialog.breakdown.activityWeight, 0.001)

        // Case 2: Real Activity ending with Activity
        val realSelector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.MainActivity"
        )
        val scoredReal = windowMatcher.scoreWindow(win, realSelector, currentActivityClass = "com.example.app.MainActivity")
        assertTrue("MainActivity must match as an activity", scoredReal.breakdown.activityMatched)
        assertEquals(40.0, scoredReal.breakdown.activityWeight, 0.001)
    }

    @Test
    fun `fix 5c a recording made in a dialog selects the dialog over its activity`() {
        val baseActivityWindow = window(
            "com.example.app",
            id = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            bounds = r(0, 0, 1000, 2000)
        ) {
            node("android.widget.FrameLayout", id = 10)
        }

        val dialogWindow = window(
            "com.example.app",
            id = 2,
            type = WindowType.APPLICATION,
            layer = 5,
            bounds = r(100, 400, 900, 1600)
        ) {
            node("android.widget.FrameLayout", id = 20)
        }

        val selector = windowSelector(
            packageName = "com.example.app",
            windowType = WindowType.APPLICATION,
            // The recording was made inside the dialog, so it carries the dialog's bounds.
            boundsOnDisplay = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f)
        )

        val windowSet = windowSet(baseActivityWindow, dialogWindow, width = 1000, height = 2000)
        val decision = windowMatcher.match(windowSet, selector)

        assertTrue("Expected Selected decision for dialog window, got $decision", decision is WindowDecision.Selected)
        val selected = decision as WindowDecision.Selected
        assertEquals(2, selected.window.windowId)
    }
}

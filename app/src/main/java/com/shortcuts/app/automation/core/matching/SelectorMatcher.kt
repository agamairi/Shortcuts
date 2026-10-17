package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.labelOf
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.NodeAction
import kotlin.math.max
import kotlin.math.min

data class MatchOptions(
    val isTextTarget: Boolean = false,
    val isScrollContainer: Boolean = false,
    val allowGestureFallback: Boolean = false
)

/**
 * Strict element matcher implementing Engine v2 spec §2.C/§2.D.
 *
 * Traverses immutable snapshot, applies hard candidate filters, generates candidates
 * exclusively through exact locators / anchors, scores them via [CandidateScorer],
 * sorts them deterministically by score and nodeId, and applies decision thresholds:
 * - best < 75 -> [MatchDecision.LowConfidence]
 * - second >= 75 and best - second < 12 -> [MatchDecision.Ambiguous]
 * - else -> [MatchDecision.Accepted]
 */
class SelectorMatcher(
    private val candidateScorer: CandidateScorer = CandidateScorer()
) {

    fun match(
        window: WindowSnapshot,
        selector: ElementSelector,
        options: MatchOptions = MatchOptions()
    ): MatchDecision {
        val eligibleCandidates = mutableListOf<ScoredCandidate>()

        for (node in window.nodes.values) {
            if (!passesHardFilters(node, window, selector, options)) {
                continue
            }

            val scored = candidateScorer.scoreCandidate(node, window, selector)
            // Candidate generation by anchors only: node must have a recognized matching anchor
            if (scored.breakdown.anchorKind != AnchorKind.NONE) {
                eligibleCandidates.add(scored)
            }
        }

        // Bug 1: Collapse merged-semantics duplicates.
        // When a candidate is the label source (labelOf(...).sourceNodeId) of another candidate
        // that is one of its ancestors and that ancestor is actionable (clickable/long-clickable/editable or has CLICK),
        // drop the label-source child from the candidate set — the actionable parent is the element.
        val nodesToDrop = mutableSetOf<Int>()
        for (parentCandidate in eligibleCandidates) {
            if (!candidateScorer.isActionable(parentCandidate.node)) continue
            val derivedLabel = window.labelOf(parentCandidate.node)
            val sourceId = derivedLabel.sourceNodeId ?: continue
            if (sourceId != parentCandidate.node.nodeId) {
                for (childCandidate in eligibleCandidates) {
                    if (childCandidate.node.nodeId == sourceId) {
                        val ancestors = window.ancestors(childCandidate.node)
                        if (ancestors.any { it.nodeId == parentCandidate.node.nodeId }) {
                            nodesToDrop.add(sourceId)
                        }
                    }
                }
            }
        }

        val candidatesAfterCollapse = if (nodesToDrop.isNotEmpty()) {
            eligibleCandidates.filterNot { it.node.nodeId in nodesToDrop }
        } else {
            eligibleCandidates
        }

        // Uniqueness is evidence (Fix 3):
        // When exactly ONE candidate in the selected window passes the hard filters with an
        // exact id/text/desc/label anchor, add +10 ("unique exact anchor").
        val exactAnchorCandidates = candidatesAfterCollapse.filter {
            it.breakdown.anchorKind == AnchorKind.RESOURCE_ID ||
                it.breakdown.anchorKind == AnchorKind.CONTENT_DESCRIPTION ||
                it.breakdown.anchorKind == AnchorKind.TEXT
        }

        val candidatesToUse = if (exactAnchorCandidates.size == 1) {
            val unique = exactAnchorCandidates.single()
            val reScored = candidateScorer.scoreCandidate(
                node = unique.node,
                window = window,
                selector = selector,
                uniqueExactAnchor = true
            )
            candidatesAfterCollapse.map { if (it.node.nodeId == unique.node.nodeId) reScored else it }
        } else {
            candidatesAfterCollapse
        }

        val sortedCandidates = candidatesToUse.sortedWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { it.node.nodeId }
        )

        if (sortedCandidates.isEmpty()) {
            return MatchDecision.LowConfidence(best = null, candidates = emptyList())
        }

        val best = sortedCandidates[0]
        if (best.score < 75) {
            return MatchDecision.LowConfidence(best = best, candidates = sortedCandidates)
        }

        val second = sortedCandidates.getOrNull(1)
        if (second != null && second.score >= 75 && (best.score - second.score) < 12) {
            return MatchDecision.Ambiguous(best = best, second = second, candidates = sortedCandidates)
        }

        return MatchDecision.Accepted(target = best, candidates = sortedCandidates)
    }

    private fun passesHardFilters(
        node: NodeSnapshot,
        window: WindowSnapshot,
        selector: ElementSelector,
        options: MatchOptions
    ): Boolean {
        // Hard filter 1: selected window & package
        if (node.windowId != window.windowId || node.packageName != window.packageName) {
            return false
        }

        // Hard filter 2: visible to user
        if (!node.visibleToUser) {
            return false
        }

        // Hard filter 3: enabled
        if (!node.enabled) {
            return false
        }

        // Hard filter 4: non-empty bounds intersecting visible window
        val nb = node.boundsInScreen
        if (nb.width <= 0 || nb.height <= 0) {
            return false
        }
        if (!boundsIntersect(nb, window.boundsInScreen)) {
            return false
        }

        // Hard filter 5: editable for text (SET_TEXT / IME_ACTION)
        val requiresEditable = options.isTextTarget ||
            NodeAction.SET_TEXT in selector.requiredActions ||
            NodeAction.IME_ENTER in selector.requiredActions
        if (requiresEditable && !node.editable) {
            return false
        }

        // Hard filter 6: scrollable when matching a scroll container
        val requiresScrollable = options.isScrollContainer ||
            selector.requiredActions.any { it in SCROLL_ACTIONS }
        if (requiresScrollable && !node.scrollable) {
            return false
        }

        // Hard filter 7: required action present unless gesture fallback allowed
        if (!options.allowGestureFallback && selector.requiredActions.isNotEmpty()) {
            val allActionsSupported = selector.requiredActions.all { action ->
                candidateScorer.isActionSupported(node, action)
            }
            if (!allActionsSupported) {
                return false
            }
        }

        return true
    }

    private fun boundsIntersect(a: IntRect, b: IntRect): Boolean {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        return interRight > interLeft && interBottom > interTop
    }

    companion object {
        private val SCROLL_ACTIONS = setOf(
            NodeAction.SCROLL_FORWARD,
            NodeAction.SCROLL_BACKWARD,
            NodeAction.SCROLL_UP,
            NodeAction.SCROLL_DOWN,
            NodeAction.SCROLL_LEFT,
            NodeAction.SCROLL_RIGHT
        )
    }
}

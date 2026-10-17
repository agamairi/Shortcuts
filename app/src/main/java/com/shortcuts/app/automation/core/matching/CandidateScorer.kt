package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.tree.NodeRoles
import com.shortcuts.app.automation.core.tree.sameClassSiblingIndex

import com.shortcuts.app.automation.core.geometry.NormalizedGeometry
import com.shortcuts.app.automation.core.text.TextNormalizer
import com.shortcuts.app.automation.core.tree.DerivedLabel
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.labelOf
import com.shortcuts.app.automation.model.AncestorSelector
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeRole
import com.shortcuts.app.automation.model.NormalizedPoint
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RowContext
import kotlin.math.round

/**
 * Pure Kotlin candidate scorer implementing Engine v2 spec §2.C/§2.D.
 *
 * Starts with the strongest matching anchor, adds corroborations (capped at 100),
 * and subtracts contradiction penalties.
 * Produces a complete [ScoreBreakdown] with named fields for every component.
 */
class CandidateScorer {

    fun scoreCandidate(
        node: NodeSnapshot,
        window: WindowSnapshot,
        selector: ElementSelector,
        uniqueExactAnchor: Boolean = false
    ): ScoredCandidate {
        val breakdown = computeScoreBreakdown(node, window, selector, uniqueExactAnchor)
        return ScoredCandidate(
            node = node,
            score = breakdown.totalScore,
            breakdown = breakdown
        )
    }

    fun computeScoreBreakdown(
        node: NodeSnapshot,
        window: WindowSnapshot,
        selector: ElementSelector,
        uniqueExactAnchor: Boolean = false
    ): ScoreBreakdown {
        // Ancestor & Collection detection
        val actualAncestors = window.ancestors(node)
        val inCollection = isInCollection(node, actualAncestors, selector)

        // Geometry calculations
        val candidateRect = NormalizedGeometry.normalize(node.boundsInScreen, window.boundsInScreen)
        val candidateCenter = NormalizedGeometry.center(candidateRect)
        val recordedCenter = NormalizedGeometry.center(selector.boundsInWindow)
        val normalizedBoundsDistance = NormalizedGeometry.distance(candidateCenter, recordedCenter)

        // Ancestor & Row context similarities
        val ancestorSimilarity = computeAncestorSimilarity(selector.ancestorChain, actualAncestors, window, inCollection)

        val rowContext = selector.rowContext
        val hasRowEvidence = rowContext != null && hasRowContextEvidence(rowContext)
        val rowContextSimilarity = if (rowContext != null) {
            computeRowContextSimilarity(rowContext, node, window)
        } else {
            1.0
        }
        val rowContextExactMatch = rowContext != null && checkRowContextExactMatch(rowContext, node, window)

        // Anchor evaluation
        val derivedLabel = window.labelOf(node)
        val labelSourceNode = derivedLabel.sourceNodeId?.let { window.node(it) }
        val idMatched = selector.strategies.any { strategy ->
            isResourceIdStrategy(strategy.kind) && (
                (node.resourceId != null && node.resourceId == strategy.value) ||
                (labelSourceNode?.resourceId != null && labelSourceNode.resourceId == strategy.value)
            )
        }
        val eligibleAncestor = isEligibleActionableAncestor(node, derivedLabel, window)
        val descMatched = selector.strategies.any { strategy ->
            strategy.kind == LocatorKind.CONTENT_DESCRIPTION_EXACT && (
                TextNormalizer.sameText(node.contentDescription, strategy.value) ||
                (eligibleAncestor && TextNormalizer.sameText(derivedLabel.contentDescription, strategy.value))
            )
        }
        val textMatched = selector.strategies.any { strategy ->
            strategy.kind == LocatorKind.TEXT_EXACT && (
                TextNormalizer.sameText(node.text, strategy.value) ||
                (eligibleAncestor && TextNormalizer.sameText(derivedLabel.text, strategy.value)) ||
                (node.editable && TextNormalizer.sameText(node.hintText, strategy.value))
            )
        }
        val ancestorAnchorMatched = selector.ancestorChain.isNotEmpty() &&
            ancestorSimilarity >= 0.80 &&
            selector.expectedClass != null &&
            node.className == selector.expectedClass
        val rowContextAnchorMatched = rowContext != null &&
            rowContextExactMatch &&
            selector.expectedClass != null &&
            node.className == selector.expectedClass
        val coordAnchorMatched = normalizedBoundsDistance <= 0.25f

        // Pick strongest anchor
        val anchorKind = when {
            idMatched -> AnchorKind.RESOURCE_ID
            descMatched -> AnchorKind.CONTENT_DESCRIPTION
            textMatched -> AnchorKind.TEXT
            ancestorAnchorMatched -> AnchorKind.ANCESTOR_CHAIN
            rowContextAnchorMatched -> AnchorKind.ROW_CONTEXT
            coordAnchorMatched -> AnchorKind.COORDINATE_PROXIMITY
            else -> AnchorKind.NONE
        }
        val anchorPoints = anchorKind.basePoints

        // Corroborations
        // 1. Exact class/role (+8)
        val classMatched = selector.expectedClass != null && node.className == selector.expectedClass
        val roleMatched = selector.expectedRole != null && inferRole(node) == selector.expectedRole
        val exactClassRoleMatched = classMatched || roleMatched
        val classRoleCorroboration = if (exactClassRoleMatched) 8 else 0

        // 2. Ancestor similarity: round(8 * similarity)
        val ancestorCorroboration = if (selector.ancestorChain.isNotEmpty()) {
            round(8.0 * ancestorSimilarity).toInt()
        } else {
            0
        }

        // 3. Row context similarity: round(8 * similarity), or +12 if inCollection and similarity is 1.0
        val rowContextCorroboration = if (hasRowEvidence) {
            if (inCollection && rowContextSimilarity >= 1.0) {
                12
            } else {
                round(8.0 * rowContextSimilarity).toInt()
            }
        } else {
            0
        }

        // 4. Sibling index (+5) - ignored in collection
        val actualSiblingIndex = window.sameClassSiblingIndex(node)
        val siblingIndexMatched = !inCollection && selector.siblingIndex != null && actualSiblingIndex == selector.siblingIndex
        val siblingIndexCorroboration = if (siblingIndexMatched) 5 else 0

        // 5. Bounds proximity: round(6 * (1 - min(1, dist / 0.25))) - retained in collections (Fix 4)
        val boundsFactor = (1.0f - minOf(1.0f, normalizedBoundsDistance / 0.25f)).coerceAtLeast(0f)
        val boundsProximityCorroboration = round(6.0 * boundsFactor).toInt()

        // 6. Required action supported (+5)
        val requiredActionsSupported = selector.requiredActions.isNotEmpty() &&
            selector.requiredActions.all { isActionSupported(node, it) }
        val requiredActionsCorroboration = if (requiredActionsSupported) 5 else 0

        // 7. Extra exact text/desc locators (+4 each)
        val secondaryLocatorsCount = countSecondaryLocators(node, derivedLabel, selector, anchorKind, window)
        val secondaryLocatorsCorroboration = secondaryLocatorsCount * 4

        // 8. Unique exact anchor (+10) (Fix 3)
        val uniqueExactAnchorCorroboration = if (uniqueExactAnchor) 10 else 0

        val rawScoreBeforeCap = anchorPoints + classRoleCorroboration + ancestorCorroboration +
            rowContextCorroboration + siblingIndexCorroboration + boundsProximityCorroboration +
            requiredActionsCorroboration + secondaryLocatorsCorroboration + uniqueExactAnchorCorroboration
        val scoreAfterCap = minOf(100, rawScoreBeforeCap)

        // Contradictions
        // 1. Different non-null resource ID (-35)
        val recordedResId = selector.strategies.firstOrNull { isResourceIdStrategy(it.kind) }?.value
        val differentResourceId = recordedResId != null && node.resourceId != null && node.resourceId != recordedResId &&
            (labelSourceNode?.resourceId == null || labelSourceNode.resourceId != recordedResId)
        val differentResourceIdPenalty = if (differentResourceId) 35 else 0

        // 2. Mismatch of a locator marked STABLE (-15 each)
        val mismatchedStableCount = selector.strategies.count { strategy ->
            strategy.stability == LocatorStability.STABLE && !strategyMatches(strategy, node, window, derivedLabel)
        }
        val mismatchedStableLocatorsPenalty = mismatchedStableCount * 15

        // 3. Class/role mismatch (-15)
        val classMismatch = selector.expectedClass != null && node.className != selector.expectedClass
        val roleMismatch = selector.expectedRole != null &&
            inferRole(node) != NodeRole.UNKNOWN &&
            inferRole(node) != selector.expectedRole
        val classRoleMismatch = classMismatch || roleMismatch
        val classRoleMismatchPenalty = if (classRoleMismatch) 15 else 0

        // 4. Ancestor similarity below 0.5 (-15)
        val ancestorSimilarityLow = selector.ancestorChain.isNotEmpty() && ancestorSimilarity < 0.5
        val ancestorSimilarityPenalty = if (ancestorSimilarityLow) 15 else 0

        // 5. Row-context similarity below 0.5 (-10 outside collections, -20 in collections)
        val rowContextSimilarityLow = hasRowEvidence && rowContextSimilarity < 0.5
        val rowContextSimilarityPenalty = when {
            !rowContextSimilarityLow -> 0
            inCollection -> 20
            else -> 10
        }

        // 6. Candidate centre outside recorded bounds expanded by 15% (-10) - not applied in collection
        val centerOutsideExpandedBounds = !inCollection && isCenterOutsideExpanded(candidateCenter, selector.boundsInWindow)
        val centerOutsideBoundsPenalty = if (centerOutsideExpandedBounds) 10 else 0

        val totalPenalties = differentResourceIdPenalty + mismatchedStableLocatorsPenalty +
            classRoleMismatchPenalty + ancestorSimilarityPenalty +
            rowContextSimilarityPenalty + centerOutsideBoundsPenalty

        val totalScore = scoreAfterCap - totalPenalties

        return ScoreBreakdown(
            anchorKind = anchorKind,
            anchorPoints = anchorPoints,
            exactClassRoleMatched = exactClassRoleMatched,
            classRoleCorroboration = classRoleCorroboration,
            ancestorSimilarity = ancestorSimilarity,
            ancestorCorroboration = ancestorCorroboration,
            rowContextSimilarity = rowContextSimilarity,
            rowContextCorroboration = rowContextCorroboration,
            siblingIndexMatched = siblingIndexMatched,
            siblingIndexCorroboration = siblingIndexCorroboration,
            normalizedBoundsDistance = normalizedBoundsDistance,
            boundsProximityCorroboration = boundsProximityCorroboration,
            requiredActionsSupported = requiredActionsSupported,
            requiredActionsCorroboration = requiredActionsCorroboration,
            secondaryLocatorsCount = secondaryLocatorsCount,
            secondaryLocatorsCorroboration = secondaryLocatorsCorroboration,
            uniqueExactAnchor = uniqueExactAnchor,
            uniqueExactAnchorCorroboration = uniqueExactAnchorCorroboration,
            rawScoreBeforeCap = rawScoreBeforeCap,
            scoreAfterCap = scoreAfterCap,
            differentResourceId = differentResourceId,
            differentResourceIdPenalty = differentResourceIdPenalty,
            mismatchedStableLocatorsCount = mismatchedStableCount,
            mismatchedStableLocatorsPenalty = mismatchedStableLocatorsPenalty,
            classRoleMismatch = classRoleMismatch,
            classRoleMismatchPenalty = classRoleMismatchPenalty,
            ancestorSimilarityLow = ancestorSimilarityLow,
            ancestorSimilarityPenalty = ancestorSimilarityPenalty,
            rowContextSimilarityLow = rowContextSimilarityLow,
            rowContextSimilarityPenalty = rowContextSimilarityPenalty,
            centerOutsideExpandedBounds = centerOutsideExpandedBounds,
            centerOutsideBoundsPenalty = centerOutsideBoundsPenalty,
            totalPenalties = totalPenalties,
            totalScore = totalScore,
            inCollection = inCollection
        )
    }

    private fun isResourceIdStrategy(kind: LocatorKind): Boolean = when (kind) {
        LocatorKind.RESOURCE_ID_EXACT,
        LocatorKind.TEST_TAG_EXACT,
        LocatorKind.WEB_DOM_ID_EXACT -> true
        else -> false
    }

    private fun strategyMatches(
        strategy: LocatorStrategy,
        node: NodeSnapshot,
        window: WindowSnapshot,
        derivedLabel: DerivedLabel
    ): Boolean {
        val eligibleAncestor = isEligibleActionableAncestor(node, derivedLabel, window)
        return when (strategy.kind) {
            LocatorKind.RESOURCE_ID_EXACT,
            LocatorKind.TEST_TAG_EXACT,
            LocatorKind.WEB_DOM_ID_EXACT -> (node.resourceId != null && node.resourceId == strategy.value) ||
                (derivedLabel.sourceNodeId != null && window.node(derivedLabel.sourceNodeId)?.resourceId == strategy.value)
            LocatorKind.TEXT_EXACT -> TextNormalizer.sameText(node.text, strategy.value) ||
                (eligibleAncestor && TextNormalizer.sameText(derivedLabel.text, strategy.value)) ||
                (node.editable && TextNormalizer.sameText(node.hintText, strategy.value))
            LocatorKind.CONTENT_DESCRIPTION_EXACT -> TextNormalizer.sameText(node.contentDescription, strategy.value) ||
                (eligibleAncestor && TextNormalizer.sameText(derivedLabel.contentDescription, strategy.value))
        }
    }

    private fun countSecondaryLocators(
        node: NodeSnapshot,
        derivedLabel: DerivedLabel,
        selector: ElementSelector,
        anchorKind: AnchorKind,
        window: WindowSnapshot
    ): Int {
        var count = 0
        var anchorTextUsed = anchorKind == AnchorKind.TEXT
        var anchorDescUsed = anchorKind == AnchorKind.CONTENT_DESCRIPTION
        val eligibleAncestor = isEligibleActionableAncestor(node, derivedLabel, window)

        for (strategy in selector.strategies) {
            when (strategy.kind) {
                LocatorKind.TEXT_EXACT -> {
                    val matches = TextNormalizer.sameText(node.text, strategy.value) ||
                        (eligibleAncestor && TextNormalizer.sameText(derivedLabel.text, strategy.value)) ||
                        (node.editable && TextNormalizer.sameText(node.hintText, strategy.value))
                    if (matches) {
                        if (anchorTextUsed) {
                            anchorTextUsed = false // consumed primary
                        } else {
                            count++
                        }
                    }
                }
                LocatorKind.CONTENT_DESCRIPTION_EXACT -> {
                    val matches = TextNormalizer.sameText(node.contentDescription, strategy.value) ||
                        (eligibleAncestor && TextNormalizer.sameText(derivedLabel.contentDescription, strategy.value))
                    if (matches) {
                        if (anchorDescUsed) {
                            anchorDescUsed = false // consumed primary
                        } else {
                            count++
                        }
                    }
                }
                else -> {}
            }
        }
        return count
    }

    private fun isInCollection(
        node: NodeSnapshot,
        actualAncestors: List<NodeSnapshot>,
        selector: ElementSelector
    ): Boolean {
        if (selector.scrollContainer != null || selector.rowContext != null) {
            return true
        }
        if (node.collectionRow != null || node.collectionColumn != null) {
            return true
        }
        if (actualAncestors.any { it.scrollable || it.collectionRow != null || it.collectionColumn != null }) {
            return true
        }
        return false
    }

    private fun computeAncestorSimilarity(
        expectedChain: List<AncestorSelector>,
        actualAncestors: List<NodeSnapshot>,
        window: WindowSnapshot,
        inCollection: Boolean
    ): Double {
        if (expectedChain.isEmpty()) return 1.0
        if (actualAncestors.isEmpty()) return 0.0

        val scrollContainerIndex = actualAncestors.indexOfFirst { it.scrollable }
        val collectionRowIndex = actualAncestors.indexOfFirst { it.collectionRow != null || it.collectionColumn != null }

        var matched = 0
        var actualIndex = 0

        for (expected in expectedChain) {
            var found = false
            while (actualIndex < actualAncestors.size) {
                val candidateAncestor = actualAncestors[actualIndex]
                val isAtOrBelowListItem = inCollection && (
                    (scrollContainerIndex > 0 && actualIndex < scrollContainerIndex) ||
                    (scrollContainerIndex == -1 && collectionRowIndex >= 0 && actualIndex <= collectionRowIndex)
                )
                actualIndex++
                if (ancestorMatches(expected, candidateAncestor, window, ignoreSiblingIndex = isAtOrBelowListItem)) {
                    matched++
                    found = true
                    break
                }
            }
            if (!found) break
        }
        return matched.toDouble() / expectedChain.size.toDouble()
    }

    private fun ancestorMatches(
        expected: AncestorSelector,
        actual: NodeSnapshot,
        window: WindowSnapshot,
        ignoreSiblingIndex: Boolean = false
    ): Boolean {
        if (actual.className != expected.className && (expected.role == null || inferRole(actual) != expected.role)) {
            return false
        }
        if (expected.resourceId != null && actual.resourceId != expected.resourceId) {
            return false
        }
        if (!ignoreSiblingIndex && expected.siblingIndex != null) {
            if (window.sameClassSiblingIndex(actual) != expected.siblingIndex) return false
        }
        return true
    }

    private fun findRowNode(node: NodeSnapshot, window: WindowSnapshot): NodeSnapshot {
        val ancestors = window.ancestors(node)
        val scrollContainer = ancestors.firstOrNull { it.scrollable }
        if (scrollContainer != null) {
            val listRow = directChildBelow(node, scrollContainer, window)
            if (listRow != null) return listRow
        }
        val metadataRow = (listOf(node) + ancestors).firstOrNull { it.collectionRow != null || it.collectionColumn != null }
        if (metadataRow != null) return metadataRow
        return window.parent(node) ?: node
    }

    private fun directChildBelow(
        target: NodeSnapshot,
        ancestor: NodeSnapshot,
        window: WindowSnapshot
    ): NodeSnapshot? {
        var current = target
        var parent = window.parent(current)
        while (parent != null && parent.nodeId != ancestor.nodeId) {
            current = parent
            parent = window.parent(current)
        }
        return current.takeIf { parent?.nodeId == ancestor.nodeId }
    }

    private fun hasRowContextEvidence(rowContext: RowContext): Boolean =
        rowContext.siblingText.isNotEmpty() ||
            rowContext.siblingContentDescriptions.isNotEmpty() ||
            rowContext.collectionRow != null

    private fun checkRowContextExactMatch(
        rowContext: RowContext,
        node: NodeSnapshot,
        window: WindowSnapshot
    ): Boolean {
        val rowNode = findRowNode(node, window)
        val contextNodes = (listOf(rowNode) + window.descendants(rowNode)).filter { it.nodeId != node.nodeId }
        val excludedLabels = setOfNotNull(
            TextNormalizer.normalize(node.text),
            TextNormalizer.normalize(node.contentDescription)
        )
        val observedTokens = buildSet {
            for (ctx in contextNodes) {
                TextNormalizer.normalize(ctx.text)?.let { if (it !in excludedLabels) add(it) }
                TextNormalizer.normalize(ctx.contentDescription)?.let { if (it !in excludedLabels) add(it) }
            }
        }
        val expectedTokens = buildSet {
            rowContext.siblingText.forEach { TextNormalizer.normalize(it)?.let(::add) }
            rowContext.siblingContentDescriptions.forEach { TextNormalizer.normalize(it)?.let(::add) }
        }
        return expectedTokens.isNotEmpty() && expectedTokens.any { it in observedTokens }
    }

    private fun computeRowContextSimilarity(
        rowContext: RowContext,
        node: NodeSnapshot,
        window: WindowSnapshot
    ): Double {
        val rowNode = findRowNode(node, window)
        val candidateRow = node.collectionRow ?: window.ancestors(node).firstNotNullOfOrNull { it.collectionRow }

        if (rowContext.collectionRow != null && candidateRow != null && rowContext.collectionRow != candidateRow) {
            return 0.0
        }

        if (rowContext.collectionRow != null && candidateRow != null && rowContext.collectionRow == candidateRow) {
            if (rowContext.siblingText.isEmpty() && rowContext.siblingContentDescriptions.isEmpty()) {
                return 1.0
            }
        }

        val expectedTokens = buildSet {
            rowContext.siblingText.forEach { TextNormalizer.normalize(it)?.let(::add) }
            rowContext.siblingContentDescriptions.forEach { TextNormalizer.normalize(it)?.let(::add) }
        }

        if (expectedTokens.isEmpty()) {
            if (rowContext.collectionRow != null) {
                return if (candidateRow != null && candidateRow == rowContext.collectionRow) 1.0 else 0.0
            }
            if (rowContext.containerResourceId != null && rowNode.resourceId == rowContext.containerResourceId) {
                return 1.0
            }
            if (rowContext.containerClass != null && rowNode.className == rowContext.containerClass) {
                return 1.0
            }
            return 1.0
        }

        val contextNodes = (listOf(rowNode) + window.descendants(rowNode)).filter { it.nodeId != node.nodeId }
        val excludedLabels = setOfNotNull(
            TextNormalizer.normalize(node.text),
            TextNormalizer.normalize(node.contentDescription)
        )
        val observedTokens = buildSet {
            for (ctx in contextNodes) {
                TextNormalizer.normalize(ctx.text)?.let { if (it !in excludedLabels) add(it) }
                TextNormalizer.normalize(ctx.contentDescription)?.let { if (it !in excludedLabels) add(it) }
            }
        }

        val intersection = expectedTokens.intersect(observedTokens).size
        val union = expectedTokens.union(observedTokens).size
        return if (union == 0) 1.0 else intersection.toDouble() / union.toDouble()
    }

    private fun isCenterOutsideExpanded(center: NormalizedPoint, bounds: NormalizedRect): Boolean {
        val expandX = maxOf(bounds.width * 0.15f, 0.02f)
        val expandY = maxOf(bounds.height * 0.15f, 0.02f)
        val left = bounds.left - expandX
        val top = bounds.top - expandY
        val right = bounds.right + expandX
        val bottom = bounds.bottom + expandY
        return center.x < left || center.x > right || center.y < top || center.y > bottom
    }

    fun isActionSupported(node: NodeSnapshot, action: NodeAction): Boolean = when (action) {
        NodeAction.CLICK -> node.clickable || NodeAction.CLICK in node.actions
        NodeAction.LONG_CLICK -> node.longClickable || NodeAction.LONG_CLICK in node.actions
        NodeAction.SET_TEXT -> node.editable || NodeAction.SET_TEXT in node.actions
        NodeAction.FOCUS -> node.focusable || NodeAction.FOCUS in node.actions
        NodeAction.SCROLL_FORWARD, NodeAction.SCROLL_BACKWARD,
        NodeAction.SCROLL_UP, NodeAction.SCROLL_DOWN,
        NodeAction.SCROLL_LEFT, NodeAction.SCROLL_RIGHT -> node.scrollable || action in node.actions
        else -> action in node.actions
    }

    /** Delegates to the shared mapping so capture and matching always agree on roles. */
    fun inferRole(node: NodeSnapshot): NodeRole = NodeRoles.roleOf(node)

    fun isActionable(node: NodeSnapshot): Boolean =
        node.clickable || node.longClickable || node.editable ||
            NodeAction.CLICK in node.actions || node.isActionable

    fun ancestorDistance(ancestor: NodeSnapshot, descendantId: Int, window: WindowSnapshot): Int? {
        var current = window.node(descendantId) ?: return null
        var distance = 0
        while (distance < 3) {
            val parent = window.parent(current) ?: return null
            distance++
            if (parent.nodeId == ancestor.nodeId) return distance
            current = parent
        }
        return null
    }

    fun isEligibleActionableAncestor(
        node: NodeSnapshot,
        derivedLabel: DerivedLabel,
        window: WindowSnapshot
    ): Boolean {
        val sourceId = derivedLabel.sourceNodeId ?: return false
        if (sourceId == node.nodeId) return false
        if (!isActionable(node)) return false
        val dist = ancestorDistance(node, sourceId, window) ?: return false
        return dist in 1..3
    }
}

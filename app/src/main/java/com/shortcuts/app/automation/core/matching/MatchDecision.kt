package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.tree.NodeSnapshot

/**
 * Result of strict element matching according to Engine v2 spec §2.C/§2.D.
 */
sealed interface MatchDecision {
    val candidates: List<ScoredCandidate>

    data class Accepted(
        val target: ScoredCandidate,
        override val candidates: List<ScoredCandidate>
    ) : MatchDecision

    data class Ambiguous(
        val best: ScoredCandidate,
        val second: ScoredCandidate,
        override val candidates: List<ScoredCandidate>
    ) : MatchDecision

    data class LowConfidence(
        val best: ScoredCandidate?,
        override val candidates: List<ScoredCandidate>
    ) : MatchDecision
}

data class ScoredCandidate(
    val node: NodeSnapshot,
    val score: Int,
    val breakdown: ScoreBreakdown
)

enum class AnchorKind(val basePoints: Int) {
    RESOURCE_ID(70),
    CONTENT_DESCRIPTION(65),
    TEXT(60),
    ANCESTOR_CHAIN(55),
    ROW_CONTEXT(55),
    COORDINATE_PROXIMITY(35),
    NONE(0)
}

data class ScoreBreakdown(
    val anchorKind: AnchorKind,
    val anchorPoints: Int,
    val exactClassRoleMatched: Boolean,
    val classRoleCorroboration: Int,
    val ancestorSimilarity: Double,
    val ancestorCorroboration: Int,
    val rowContextSimilarity: Double,
    val rowContextCorroboration: Int,
    val siblingIndexMatched: Boolean,
    val siblingIndexCorroboration: Int,
    val normalizedBoundsDistance: Float,
    val boundsProximityCorroboration: Int,
    val requiredActionsSupported: Boolean,
    val requiredActionsCorroboration: Int,
    val secondaryLocatorsCount: Int,
    val secondaryLocatorsCorroboration: Int,
    val uniqueExactAnchor: Boolean = false,
    val uniqueExactAnchorCorroboration: Int = 0,
    val rawScoreBeforeCap: Int,
    val scoreAfterCap: Int,
    val differentResourceId: Boolean,
    val differentResourceIdPenalty: Int,
    val mismatchedStableLocatorsCount: Int,
    val mismatchedStableLocatorsPenalty: Int,
    val classRoleMismatch: Boolean,
    val classRoleMismatchPenalty: Int,
    val ancestorSimilarityLow: Boolean,
    val ancestorSimilarityPenalty: Int,
    val rowContextSimilarityLow: Boolean,
    val rowContextSimilarityPenalty: Int,
    val centerOutsideExpandedBounds: Boolean,
    val centerOutsideBoundsPenalty: Int,
    val totalPenalties: Int,
    val totalScore: Int,
    val inCollection: Boolean = false
) {
    val uniqueExactAnchorBonus: Int get() = uniqueExactAnchorCorroboration
}

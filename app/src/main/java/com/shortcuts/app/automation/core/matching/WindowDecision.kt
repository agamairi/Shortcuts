package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.tree.WindowSnapshot

/**
 * Result of window selection according to Engine v2 spec §2.A.
 */
sealed interface WindowDecision {
    data class Selected(
        val window: WindowSnapshot,
        val confidence: Double,
        val breakdown: WindowScoreBreakdown
    ) : WindowDecision

    data class NotFound(
        val observedSummary: String
    ) : WindowDecision

    data class Ambiguous(
        val best: ScoredWindow,
        val second: ScoredWindow
    ) : WindowDecision
}

data class ScoredWindow(
    val window: WindowSnapshot,
    val confidence: Double,
    val breakdown: WindowScoreBreakdown
)

data class WindowScoreBreakdown(
    val activityMatched: Boolean,
    val activityWeight: Double,
    val windowTypeMatched: Boolean,
    val windowTypeWeight: Double,
    val titleTokenMatched: Boolean,
    val titleTokenWeight: Double,
    val boundsMatched: Boolean,
    val boundsWeight: Double,
    val fingerprintSimilarity: Double?,
    val fingerprintWeight: Double,
    val totalMatchedWeight: Double,
    val applicableWeight: Double,
    val confidence: Double,
    val soleCandidate: Boolean = false
)

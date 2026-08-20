package com.shortcuts.app.planner

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import java.net.URI
import java.util.Locale

/**
 * Safely associates calls from a whole-prompt (Tier-1) response with segmented clauses.
 *
 * Calls do not carry clause IDs, so this deliberately uses only identifying evidence already
 * present in the action: an app label/package, a toggle target, a request host, or a recipient.
 * A weak match and a tie are both treated as unassigned; Tier 2 can then generate that clause
 * independently instead of showing the user a confidently mislabeled review card.
 */
class ClauseAligner(
    private val appLabelForPackage: (String) -> String? = { null }
) {
    /**
     * Returns one nullable action per input clause, in the original clause order.
     *
     * Candidate pairs are greedily accepted from highest score to lowest. This is a simple
     * maximum-weight matching approximation: it preserves the one-action/one-clause rule while
     * favouring the strongest available evidence. Before that pass, actions with tied best
     * clauses are removed entirely, because arbitrary tie-breaking could mislabel a step.
     */
    fun align(clauses: List<String>, actions: List<Action>): List<Action?> {
        if (clauses.isEmpty() || actions.isEmpty()) return List(clauses.size) { null }

        val candidatesByAction = actions.mapIndexed { actionIndex, action ->
            clauses.mapIndexedNotNull { clauseIndex, clause ->
                score(clause, action)
                    .takeIf { it >= MINIMUM_SCORE }
                    ?.let { Candidate(actionIndex, clauseIndex, it) }
            }
        }

        val unambiguousCandidates: List<Candidate> = candidatesByAction.flatMap { candidates ->
            val bestScore = candidates.maxOfOrNull { it.score }
            // A model call that fits two clauses equally well is unsafe to place in either one.
            if (bestScore == null || candidates.count { it.score == bestScore } != 1) {
                emptyList<Candidate>()
            } else {
                candidates
            }
        }

        val assignments = arrayOfNulls<Action>(clauses.size)
        val usedActions = BooleanArray(actions.size)
        unambiguousCandidates
            .sortedWith(compareByDescending<Candidate> { it.score }
                .thenBy { it.actionIndex }
                .thenBy { it.clauseIndex })
            .forEach { candidate ->
                if (!usedActions[candidate.actionIndex] && assignments[candidate.clauseIndex] == null) {
                    assignments[candidate.clauseIndex] = actions[candidate.actionIndex]
                    usedActions[candidate.actionIndex] = true
                }
            }
        return assignments.toList()
    }

    private fun score(clause: String, action: Action): Int = when (action.actionType) {
        ActionType.APP_INTENT -> scoreApp(clause, action.packageName)
        ActionType.SYSTEM_TOGGLE -> scoreToggle(clause, action.target)
        ActionType.HTTP_REQUEST -> scoreHttpHost(clause, action.url)
        ActionType.SEND_MESSAGE, ActionType.DIAL_NUMBER -> scoreExactValue(clause, action.target)
        ActionType.UI_AUTOMATION -> scoreExactValue(
            clause,
            action.targetText ?: action.targetNodeId ?: action.target
        )
    }

    private fun scoreApp(clause: String, packageName: String?): Int {
        val packageValue = packageName?.trim().orEmpty()
        if (packageValue.isBlank()) return 0
        val normalizedClause = normalize(clause)
        if (normalizedClause.contains(normalize(packageValue))) return EXACT_SCORE

        appLabelForPackage(packageValue)
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                if (containsNormalized(clause, label)) return EXACT_SCORE
            }

        // The Action stores a package, not its display label. Its distinctive package token
        // (for example "chrome" in com.android.chrome) remains useful, deterministic evidence.
        val clauseTokens = tokens(clause)
        return if (packageValue.split('.')
                .map(::normalize)
                .any { it.length >= 3 && it !in GENERIC_PACKAGE_TOKENS && it in clauseTokens }
        ) PACKAGE_TOKEN_SCORE else 0
    }

    private fun scoreToggle(clause: String, target: String?): Int {
        val normalizedTarget = normalize(target.orEmpty())
        if (normalizedTarget.isEmpty()) return 0
        val aliases = TOGGLE_ALIASES[normalizedTarget] ?: setOf(normalizedTarget)
        return aliases.maxOfOrNull { alias ->
            when {
                containsNormalized(clause, alias) -> EXACT_SCORE
                alias in tokens(clause) -> EXACT_SCORE
                else -> 0
            }
        } ?: 0
    }

    private fun scoreHttpHost(clause: String, url: String?): Int {
        val urlValue = url?.takeIf { it.isNotBlank() } ?: return 0
        val host = try {
            URI(urlValue).host
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: return 0
        return if (containsNormalized(clause, host)) EXACT_SCORE else 0
    }

    private fun scoreExactValue(clause: String, value: String?): Int =
        value?.takeIf { it.isNotBlank() }
            ?.takeIf { containsNormalized(clause, it) }
            ?.let { EXACT_SCORE }
            ?: 0

    private fun containsNormalized(text: String, value: String): Boolean {
        val normalizedValue = normalize(value)
        return normalizedValue.isNotEmpty() && normalize(text).contains(normalizedValue)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun tokens(value: String): Set<String> = value
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .toSet()

    private data class Candidate(val actionIndex: Int, val clauseIndex: Int, val score: Int)

    private companion object {
        const val MINIMUM_SCORE = 8
        const val EXACT_SCORE = 12
        const val PACKAGE_TOKEN_SCORE = 10

        val GENERIC_PACKAGE_TOKENS = setOf("com", "android", "google", "app", "apps", "mobile")
        val TOGGLE_ALIASES = mapOf(
            "wifi" to setOf("wifi", "wi-fi"),
            "bluetooth" to setOf("bluetooth"),
            "airplanemode" to setOf("airplane mode", "airplane-mode"),
            "location" to setOf("location"),
            "donotdisturb" to setOf("do not disturb", "dnd"),
            "flashlight" to setOf("flashlight", "torch"),
            "torch" to setOf("flashlight", "torch"),
            "volume" to setOf("volume", "mute", "unmute"),
            "ringmode" to setOf("ring mode", "ringer", "silent", "mute"),
            "ringer" to setOf("ring mode", "ringer", "silent", "mute"),
            "autorotate" to setOf("auto rotate", "auto-rotate", "rotation"),
            "rotation" to setOf("auto rotate", "auto-rotate", "rotation")
        )
    }
}

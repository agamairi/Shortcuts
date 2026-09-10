package com.shortcuts.app.planner

import com.shortcuts.app.data.Action

/**
 * Represents the result of attempting to plan a multi-step shortcut from a user prompt.
 *
 * INVARIANT: [steps].size == number of segments the prompt was split into.
 * Every input segment produces exactly one [DraftStep] — segments the model cannot
 * handle become [DraftStep.Unresolved] with a human-readable reason, never dropped.
 */
data class DraftShortcut(
    val steps: List<DraftStep>,
    val originalPrompt: String
)

/** One segment's planning outcome. */
sealed interface DraftStep {
    /** The original text segment this step was produced from. */
    val sourceText: String

    /**
     * The model successfully produced a recognised action for this segment.
     * @param action     The resolved action to execute.
     * @param confidence Estimated confidence in the resolution (0.0–1.0).
     */
    data class Resolved(
        override val sourceText: String,
        val action: Action,
        val confidence: Float
    ) : DraftStep

    /**
     * The model could not produce a valid action for this segment.
     * The step is preserved in the draft so the user can see what was skipped
     * and why — it is NEVER silently dropped.
     * @param reason Human-readable explanation of why resolution failed.
     */
    data class Unresolved(
        override val sourceText: String,
        val reason: String
    ) : DraftStep
}

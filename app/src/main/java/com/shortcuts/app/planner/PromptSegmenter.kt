package com.shortcuts.app.planner

/**
 * Splits a multi-command natural-language prompt into atomic imperative clauses,
 * each suitable for a single model generation.
 *
 * Splitting rules (in priority order):
 * 1. Never split inside single- or double-quoted spans.
 * 2. Never split on a conjunction that follows a speech/communication verb
 *    (tell, say, text, message, ask, remind, send, write, email) — the words
 *    after such a verb are message CONTENT, not a new command.
 * 3. Only treat "and" / "then" / "," / ";" as a clause boundary when the
 *    token that follows is a recognized imperative verb.
 * 4. "and" joining two nouns (no following imperative verb) is NOT a boundary.
 *
 * Pure Kotlin — zero Android dependencies, no side effects, fully unit-testable.
 */
object PromptSegmenter {

    /** Verbs whose objects are content — anything after them is NOT a new command. */
    private val SPEECH_VERBS = setOf(
        "tell", "say", "text", "message", "ask", "remind", "send", "write", "email", "notify"
    )

    /**
     * Imperative verbs that legitimately start a new command clause.
     * "send" is here too, but it is also a speech verb; the speech-verb rule
     * takes precedence because it is checked first.
     */
    private val IMPERATIVE_VERBS = setOf(
        "turn", "open", "launch", "set", "play", "send", "call",
        "text", "toggle", "enable", "disable", "start", "stop",
        "mute", "unmute", "dim", "brighten", "close", "lock", "unlock",
        "navigate", "search", "download", "upload", "connect", "disconnect",
        "show", "hide", "read", "record", "take", "share"
    )

    /**
     * Splits [prompt] into atomic command clauses.
     * A blank or single-clause prompt is returned as a one-element list.
     */
    fun split(prompt: String): List<String> {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return emptyList()

        val tokens = tokenize(trimmed)
        val segments = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        segments.add(current)

        // Track whether we are inside a quoted span
        var inSingleQuote = false
        var inDoubleQuote = false

        // Track whether we just passed a speech verb (suppress next split)
        var suppressNextSplit = false

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]

            // ── Quote tracking ──────────────────────────────────────────────
            if (tok == "'" && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                current.add(tok)
                i++
                continue
            }
            if (tok == "\"" && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                current.add(tok)
                i++
                continue
            }

            // Inside a quote → never split
            if (inSingleQuote || inDoubleQuote) {
                current.add(tok)
                i++
                continue
            }

            // ── Conjunction / punctuation detection ─────────────────────────
            val lower = tok.lowercase()
            val isConjunction = lower == "and" || lower == "then" || lower == "," || lower == ";"
            val isThenAnd = lower == "and" && i + 1 < tokens.size && tokens[i + 1].lowercase() == "then"

            if (isConjunction || isThenAnd) {
                // Check for "and then" as a combined token pair
                val skipCount = if (isThenAnd) 2 else 1

                if (!suppressNextSplit) {
                    // Look ahead for an imperative verb (skipping filler words like "also", "just")
                    val nextVerb = nextImperativeVerb(tokens, i + skipCount)
                    if (nextVerb != null) {
                        // Real boundary — start a new segment
                        val connector = tokens.subList(i, i + skipCount).joinToString(" ")
                        i += skipCount
                        // Skip whitespace-only tokens between connector and next segment
                        while (i < tokens.size && tokens[i].isBlank()) i++
                        current = mutableListOf()
                        segments.add(current)
                        // Reset speech-verb suppression for the new clause
                        suppressNextSplit = false
                        continue
                    }
                    // No imperative verb follows → not a boundary, keep as-is
                }
                // Suppressed or no imperative verb → emit the conjunction as content
                current.add(tok)
                if (isThenAnd) {
                    i++
                    if (i < tokens.size) current.add(tokens[i])
                }
                i++
                continue
            }

            // ── Track speech verbs (to suppress the NEXT conjunction) ───────
            if (lower in SPEECH_VERBS) {
                suppressNextSplit = true
            } else if (lower in IMPERATIVE_VERBS && current.size > 0) {
                // A NEW imperative that isn't right at start of segment can reset the flag
                // only if we're starting fresh after a split already happened.
                // (Don't reset mid-content within the same segment.)
            }

            current.add(tok)
            i++
        }

        // Collapse, trim, and filter empty segments
        return segments
            .map { it.joinToString("").trim() }
            .filter { it.isNotBlank() }
            .let { if (it.isEmpty()) listOf(trimmed) else it }
    }

    /**
     * Looks forward from [startIndex] in [tokens], skipping fillers, and returns
     * the first token that is an imperative verb, or null if none found before the
     * next conjunction or end of tokens.
     */
    private fun nextImperativeVerb(tokens: List<String>, startIndex: Int): String? {
        val fillers = setOf("also", "just", "please", "quickly", "then", "next", "now")
        var idx = startIndex
        while (idx < tokens.size) {
            val tok = tokens[idx].lowercase().trimEnd(',', ';')
            if (tok.isBlank()) {
                idx++
                continue
            }
            // Stop if we hit another conjunction before finding a verb
            if (tok == "and" || tok == "," || tok == ";") return null
            if (tok in IMPERATIVE_VERBS) return tok
            if (tok in fillers) {
                idx++
                continue
            }
            // Non-filler, non-imperative word found → not a boundary
            return null
        }
        return null
    }

    /**
     * Tokenizes [text] preserving whitespace runs as tokens so that
     * re-joining produces the original spacing (minus leading/trailing).
     * Quote characters are split out as individual tokens.
     */
    private fun tokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()

        for (ch in text) {
            when {
                ch == '\'' || ch == '"' -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                    result.add(ch.toString())
                }
                ch == ',' || ch == ';' -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                    result.add(ch.toString())
                }
                ch.isWhitespace() -> {
                    if (sb.isNotEmpty()) {
                        result.add(sb.toString())
                        sb.clear()
                    }
                    result.add(ch.toString())
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }
}

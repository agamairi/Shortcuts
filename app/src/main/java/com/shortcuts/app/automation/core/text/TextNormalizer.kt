package com.shortcuts.app.automation.core.text

import java.text.Normalizer

/**
 * The single definition of "same text" for locators. Exact matching compares normalized values:
 * Unicode NFC, zero-width characters removed, whitespace runs collapsed, ends trimmed. Case is
 * preserved, so "Delete" and "delete" remain different labels.
 */
object TextNormalizer {
    private val zeroWidth = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")
    private val whitespace = Regex("\\s+")

    fun normalize(value: String?): String? {
        if (value == null) return null
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(zeroWidth, "")
            .replace(' ', ' ')
            .replace(whitespace, " ")
            .trim()
        return normalized.ifEmpty { null }
    }

    fun sameText(a: String?, b: String?): Boolean {
        val left = normalize(a) ?: return false
        return left == normalize(b)
    }
}

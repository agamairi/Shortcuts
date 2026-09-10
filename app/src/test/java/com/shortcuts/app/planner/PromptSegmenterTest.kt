package com.shortcuts.app.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the prompt segmenter.
 *
 * The previous implementation split on any occurrence of "and" / "then" / comma, which
 * destroyed real prompts: "Text mom and tell her I'm running late" became two broken
 * fragments, as did "Play Simon and Garfunkel".
 */
class PromptSegmenterTest {

    // ── The four defects named in the PRD ────────────────────────────────────

    @Test
    fun `speech verb content is not split into a second command`() {
        val segments = PromptSegmenter.split("Text mom and tell her I'm running late")
        assertEquals(1, segments.size)
        assertTrue(segments[0].contains("running late"))
    }

    @Test
    fun `speech verb content without an apostrophe is still not split`() {
        // Guards against the apostrophe in "I'm" masking a broken speech-verb rule by
        // opening an unterminated quote span that suppresses splitting for the wrong reason.
        val segments = PromptSegmenter.split("Text mom and tell her the meeting moved")
        assertEquals(1, segments.size)
    }

    @Test
    fun `and joining two nouns is not a clause boundary`() {
        assertEquals(1, PromptSegmenter.split("Play Simon and Garfunkel").size)
    }

    @Test
    fun `and joining two imperative clauses is a boundary`() {
        val segments = PromptSegmenter.split("Turn on wifi and open Spotify")
        assertEquals(2, segments.size)
        assertTrue(segments[0].contains("wifi"))
        assertTrue(segments[1].contains("Spotify"))
    }

    @Test
    fun `three clause prompt yields three segments`() {
        val segments = PromptSegmenter.split("Turn on wifi, open Spotify, then text mom I'm late")
        assertEquals(3, segments.size)
    }

    // ── Quoting ─────────────────────────────────────────────────────────────

    @Test
    fun `double quoted text is never split`() {
        assertEquals(1, PromptSegmenter.split("""Send a message saying "turn on the lights and lock up"""").size)
    }

    @Test
    fun `single quoted text is never split`() {
        assertEquals(1, PromptSegmenter.split("Say 'open the door and start the car'").size)
    }

    @Test
    fun `quoted content with a comma is not split`() {
        assertEquals(1, PromptSegmenter.split("""Text dad "running late, start without me"""").size)
    }

    // ── Single-segment pass-through ─────────────────────────────────────────

    @Test
    fun `single command passes through unchanged`() {
        val segments = PromptSegmenter.split("Turn on the flashlight")
        assertEquals(1, segments.size)
        assertEquals("Turn on the flashlight", segments[0])
    }

    @Test
    fun `blank prompt yields no segments`() {
        assertTrue(PromptSegmenter.split("").isEmpty())
        assertTrue(PromptSegmenter.split("   ").isEmpty())
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Open Spotify", PromptSegmenter.split("   Open Spotify   ")[0])
    }

    // ── Boundary detection ──────────────────────────────────────────────────

    @Test
    fun `then is a boundary when followed by an imperative`() {
        assertEquals(2, PromptSegmenter.split("Open Spotify then turn on bluetooth").size)
    }

    @Test
    fun `and then is a boundary when followed by an imperative`() {
        assertEquals(2, PromptSegmenter.split("Turn on wifi and then open Chrome").size)
    }

    @Test
    fun `comma separated imperatives split`() {
        assertEquals(2, PromptSegmenter.split("Mute the phone, open Gmail").size)
    }

    @Test
    fun `semicolon separated imperatives split`() {
        assertEquals(2, PromptSegmenter.split("Lock the screen; open Maps").size)
    }

    @Test
    fun `filler words between conjunction and verb still split`() {
        assertEquals(2, PromptSegmenter.split("Turn on wifi and also open Spotify").size)
    }

    @Test
    fun `and followed by a non-verb noun phrase does not split`() {
        assertEquals(1, PromptSegmenter.split("Open Chrome and Firefox").size)
    }

    @Test
    fun `trailing conjunction does not create an empty segment`() {
        assertTrue(PromptSegmenter.split("Turn on wifi and").none { it.isBlank() })
    }

    @Test
    fun `no segment is ever blank`() {
        val prompts = listOf(
            "Turn on wifi, open Spotify, then text mom I'm late",
            "Mute the phone; open Gmail, and launch Maps",
            "Play Simon and Garfunkel"
        )
        prompts.forEach { p ->
            assertTrue("blank segment from '$p'", PromptSegmenter.split(p).none { it.isBlank() })
        }
    }

    @Test
    fun `segments preserve their original wording`() {
        val segments = PromptSegmenter.split("Turn on wifi and open Spotify")
        segments.forEach { assertTrue(it.trim() == it) }
        assertTrue(segments.any { it.contains("Turn on wifi") })
    }

    @Test
    fun `four clause prompt yields four segments`() {
        val segments = PromptSegmenter.split("Turn on wifi, open Spotify, mute the phone, then lock the screen")
        assertEquals(4, segments.size)
    }

    @Test
    fun `apostrophe inside a word does not corrupt segmentation`() {
        // "I'm" contains a single quote; it must not open an unterminated quoted span
        // that swallows a genuine later boundary.
        val segments = PromptSegmenter.split("Turn on wifi and open Spotify")
        assertEquals(2, segments.size)
    }
}

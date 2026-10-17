package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowMatcherTest {

    private val fingerprinter = DefaultScreenFingerprinter()
    private val matcher = WindowMatcher(
        fingerprinter = fingerprinter,
        titleTokenizer = { "token_$it" }
    )

    @Test
    fun `changed list and tab fingerprint does not weaken otherwise identical window`() {
        val recorded = window("com.example.app", id = 1) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.TextView", id = 2, text = "Home")
                node("android.widget.TextView", id = 3, text = "Row 1")
            }
        }.copy(title = "Example")
        val afterScrollAndTabChange = window("com.example.app", id = 9) {
            node("android.widget.FrameLayout", id = 10) {
                node("android.widget.TextView", id = 11, text = "Settings")
                node("android.widget.TextView", id = 12, text = "Row 35")
            }
        }.copy(title = "Example")
        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.MainActivity",
            windowType = WindowType.APPLICATION,
            titleToken = "token_Example",
            fingerprint = fingerprinter.fingerprint(recorded, "com.example.app.MainActivity")
        )

        val decision = matcher.match(
            windowSet(afterScrollAndTabChange),
            selector,
            currentActivityClass = "com.example.app.MainActivity"
        )

        assertTrue("Changed screen content must not change window identity: $decision", decision is WindowDecision.Selected)
        val selected = decision as WindowDecision.Selected
        assertEquals(9, selected.window.windowId)
        assertEquals(1.0, selected.confidence, 0.001)
        assertNull(selected.breakdown.fingerprintSimilarity)
        assertEquals(0.0, selected.breakdown.fingerprintWeight, 0.001)
    }

    @Test
    fun `wrong activity in same package is not found even when it is the sole candidate`() {
        val wrongActivityWindow = window("com.example.app", id = 1) {
            node("android.widget.FrameLayout", id = 1)
        }.copy(title = "Expected title")
        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.TargetActivity",
            windowType = WindowType.APPLICATION,
            titleToken = "token_Expected title"
        )

        val decision = matcher.match(
            windowSet(wrongActivityWindow),
            selector,
            currentActivityClass = "com.example.app.OtherActivity"
        )

        assertTrue("A sole candidate must not waive identity confidence: $decision", decision is WindowDecision.NotFound)
    }

    @Test
    fun `unexpected same-package confirmation dialog blocks expected activity`() {
        val activity = window(
            "com.example.app",
            id = 1,
            layer = 1,
            bounds = r(0, 0, 1_000, 2_000)
        ) {
            node("android.widget.FrameLayout", id = 1)
        }.copy(title = "Items")
        val unexpectedDialog = window(
            "com.example.app",
            id = 2,
            layer = 5,
            bounds = r(100, 400, 900, 1_600)
        ) {
            node("android.widget.FrameLayout", id = 2) {
                node("android.widget.TextView", id = 3, text = "Confirm delete")
            }
        }.copy(title = "Confirmation")
        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.ItemsActivity",
            windowType = WindowType.APPLICATION,
            titleToken = "token_Items"
        )

        val decision = matcher.match(
            windowSet(activity, unexpectedDialog),
            selector,
            currentActivityClass = "com.example.app.ItemsActivity"
        )

        assertTrue("An unrecorded modal must block the activity beneath it: $decision", decision is WindowDecision.NotFound)
        val summary = (decision as WindowDecision.NotFound).observedSummary
        assertTrue(summary.contains("Unexpected same-package dialog or popup"))
        assertTrue(summary.contains("id=2"))
    }

    @Test
    fun `recorded dialog wins over its activity by title and bounds evidence`() {
        val activity = window(
            "com.example.app",
            id = 1,
            layer = 1,
            bounds = r(0, 0, 1_000, 2_000)
        ) {
            node("android.widget.FrameLayout", id = 1)
        }.copy(title = "Items")
        val dialog = window(
            "com.example.app",
            id = 2,
            layer = 5,
            bounds = r(100, 400, 900, 1_600)
        ) {
            node("android.widget.FrameLayout", id = 2)
        }.copy(title = "Confirm delete")
        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.ItemsActivity",
            windowType = WindowType.APPLICATION,
            titleToken = "token_Confirm delete",
            boundsOnDisplay = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f)
        )

        val decision = matcher.match(
            windowSet(activity, dialog),
            selector,
            currentActivityClass = "com.example.app.ItemsActivity"
        )

        assertTrue("The explicitly recorded dialog should be selected: $decision", decision is WindowDecision.Selected)
        assertEquals(2, (decision as WindowDecision.Selected).window.windowId)
        assertEquals(1.0, decision.confidence, 0.001)
    }

    @Test
    fun `two same-activity windows are distinguished by title token`() {
        val expected = window("com.example.app", id = 1, layer = 1) {
            node("android.widget.FrameLayout", id = 1)
        }.copy(title = "Primary")
        val other = window("com.example.app", id = 2, layer = 8) {
            node("android.widget.FrameLayout", id = 2)
        }.copy(title = "Secondary")
        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.MainActivity",
            windowType = WindowType.APPLICATION,
            titleToken = "token_Primary"
        )

        val decision = matcher.match(
            windowSet(other, expected),
            selector,
            currentActivityClass = "com.example.app.MainActivity"
        )

        assertTrue("Title identity should beat z-order: $decision", decision is WindowDecision.Selected)
        assertEquals(1, (decision as WindowDecision.Selected).window.windowId)
    }

    @Test
    fun `indistinguishable same-package windows remain ambiguous`() {
        val higher = window("com.example.app", id = 1, layer = 2) {
            node("android.widget.FrameLayout", id = 1)
        }
        val lower = window("com.example.app", id = 2, layer = 1) {
            node("android.widget.FrameLayout", id = 2)
        }
        val selector = windowSelector(packageName = "com.example.app")

        val decision = matcher.match(windowSet(lower, higher), selector)

        assertTrue("The 0.15 ambiguity margin must always apply: $decision", decision is WindowDecision.Ambiguous)
        val ambiguous = decision as WindowDecision.Ambiguous
        assertEquals(1, ambiguous.best.window.windowId)
        assertEquals(2, ambiguous.second.window.windowId)
    }

    @Test
    fun `non-activity class does not contribute activity weight`() {
        val candidate = window("com.example.app", id = 1) {
            node("android.widget.FrameLayout", id = 1)
        }
        val selector = windowSelector(
            observedActivityClass = "androidx.appcompat.app.AlertDialog"
        )

        val scored = matcher.scoreWindow(
            candidate,
            selector,
            currentActivityClass = "androidx.appcompat.app.AlertDialog"
        )

        assertFalse(scored.breakdown.activityMatched)
        assertEquals(0.0, scored.breakdown.activityWeight, 0.001)
        assertEquals(20.0, scored.breakdown.applicableWeight, 0.001)
    }

    @Test
    fun `target app wins over higher accessibility overlay`() {
        val appWindow = window("com.target.app", id = 10, layer = 1) {
            node("android.widget.FrameLayout", id = 1)
        }
        val overlayWindow = window(
            "com.thirdparty.island",
            id = 20,
            type = WindowType.ACCESSIBILITY_OVERLAY,
            layer = 99
        ) {
            node("android.widget.FrameLayout", id = 21)
        }

        val decision = matcher.match(
            windowSet(overlayWindow, appWindow),
            windowSelector(packageName = "com.target.app")
        )

        assertTrue(decision is WindowDecision.Selected)
        assertEquals(10, (decision as WindowDecision.Selected).window.windowId)
    }

    @Test
    fun `IME is ignored for normal target and wrong package is rejected`() {
        val ime = window("keyboard", id = 2, type = WindowType.INPUT_METHOD, layer = 10) {
            node("android.widget.FrameLayout", id = 2)
        }
        val wrongPackage = window("other", id = 3, layer = 2) {
            node("android.widget.FrameLayout", id = 3)
        }

        val decision = matcher.match(
            windowSet(ime, wrongPackage),
            windowSelector(packageName = "com.example.app")
        )

        assertTrue(decision is WindowDecision.NotFound)
    }
}

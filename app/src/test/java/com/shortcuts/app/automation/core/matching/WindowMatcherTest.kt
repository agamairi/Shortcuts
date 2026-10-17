package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowMatcherTest {

    private val fingerprinter = DefaultScreenFingerprinter()
    private val matcher = WindowMatcher(
        fingerprinter = fingerprinter,
        titleTokenizer = { "token_$it" }
    )

    @Test
    fun `target app window wins over a higher accessibility overlay from another package`() {
        // App window (lower layer = 1)
        val appWindow = window("com.target.app", id = 10, type = WindowType.APPLICATION, layer = 1) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.Button", id = 2, text = "Target Action")
            }
        }
        // Dynamic Island accessibility overlay from a 3rd party tool (higher layer = 99)
        val overlayWindow = window("com.thirdparty.island", id = 20, type = WindowType.ACCESSIBILITY_OVERLAY, layer = 99) {
            node("android.widget.FrameLayout", id = 21) {
                node("android.widget.TextView", id = 22, text = "Island Status")
            }
        }

        val selector = windowSelector(
            packageName = "com.target.app",
            windowType = WindowType.APPLICATION
        )

        val windowSet = windowSet(overlayWindow, appWindow)
        val decision = matcher.match(windowSet, selector)

        assertTrue("Expected Selected decision but got $decision", decision is WindowDecision.Selected)
        val selected = decision as WindowDecision.Selected
        assertEquals(10, selected.window.windowId)
        assertEquals("com.target.app", selected.window.packageName)
    }

    @Test
    fun `dialog wins when the step expects a dialog`() {
        val baseActivityWindow = window(
            "com.example.app",
            id = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            bounds = r(0, 0, 1000, 2000)
        ) {
            node("android.widget.FrameLayout", id = 100) {
                node("android.widget.TextView", id = 101, text = "Base Activity")
            }
        }

        val dialogWindow = window(
            "com.example.app",
            id = 2,
            type = WindowType.APPLICATION,
            layer = 5,
            bounds = r(100, 400, 900, 1600)
        ) {
            node("android.widget.FrameLayout", id = 200) {
                node("android.widget.TextView", id = 201, resourceId = "com.example.app:id/dialog_title", text = "Confirm Delete")
                node("android.widget.Button", id = 202, text = "OK")
            }
        }

        val dialogFingerprint = fingerprinter.fingerprint(dialogWindow, "com.example.app.DeleteDialog")

        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.DeleteDialog",
            windowType = WindowType.APPLICATION,
            boundsOnDisplay = NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f),
            fingerprint = dialogFingerprint
        )

        val windowSet = windowSet(baseActivityWindow, dialogWindow, width = 1000, height = 2000)
        val decision = matcher.match(windowSet, selector, currentActivityClass = "com.example.app.DeleteDialog")

        assertTrue("Expected Selected decision for dialog, but got $decision", decision is WindowDecision.Selected)
        val selected = decision as WindowDecision.Selected
        assertEquals(2, selected.window.windowId)
        assertTrue(selected.confidence >= 0.80)
    }

    @Test
    fun `IME is ignored for normal targets`() {
        val appWindow = window("com.example.app", id = 1, type = WindowType.APPLICATION, layer = 1) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.EditText", id = 2, text = "Search...")
            }
        }
        val imeWindow = window("com.google.android.inputmethod.latin", id = 2, type = WindowType.INPUT_METHOD, layer = 10) {
            node("android.widget.FrameLayout", id = 3) {
                node("android.widget.Button", id = 4, text = "Enter")
            }
        }

        val selector = windowSelector(
            packageName = "com.example.app",
            windowType = WindowType.APPLICATION
        )

        val windowSet = windowSet(imeWindow, appWindow)
        val decision = matcher.match(windowSet, selector)

        assertTrue("Expected Selected but got $decision", decision is WindowDecision.Selected)
        assertEquals(1, (decision as WindowDecision.Selected).window.windowId)
    }

    @Test
    fun `two same-package windows with insufficient evidence fail as Ambiguous`() {
        // Two windows from same package, same type, same size, no distinct activity or fingerprint recorded
        val win1 = window("com.example.app", id = 1, type = WindowType.APPLICATION, layer = 2) {
            node("android.widget.FrameLayout", id = 10)
        }
        val win2 = window("com.example.app", id = 2, type = WindowType.APPLICATION, layer = 1) {
            node("android.widget.FrameLayout", id = 20)
        }

        val selector = windowSelector(
            packageName = "com.example.app",
            windowType = WindowType.APPLICATION
        )

        val windowSet = windowSet(win1, win2)
        val decision = matcher.match(windowSet, selector)

        assertTrue("Expected Ambiguous decision but got $decision", decision is WindowDecision.Ambiguous)
        val ambiguous = decision as WindowDecision.Ambiguous
        assertEquals(1, ambiguous.best.window.windowId)
        assertEquals(2, ambiguous.second.window.windowId)
    }

    @Test
    fun `window confidence below 0_80 results in NotFound`() {
        val win1 = window("com.example.app", id = 1, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 1)
        }.copy(title = "ExpectedTitle")
        val win2 = window("com.example.app", id = 2, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 2)
        }

        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.TargetActivity",
            titleToken = "token_ExpectedTitle"
        )

        // Neither activityClass nor titleToken match on either window
        val decision = matcher.match(listOf(win1, win2), selector, currentActivityClass = "com.example.app.OtherActivity")
        assertTrue("Expected NotFound when confidence < 0.80 and multiple candidates exist, got $decision", decision is WindowDecision.NotFound)
    }

    @Test
    fun `sole candidate window waives confidence floor and records soleCandidate in breakdown`() {
        val win = window("com.example.app", id = 1, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 1)
        }

        val selector = windowSelector(
            packageName = "com.example.app",
            observedActivityClass = "com.example.app.TargetActivity",
            titleToken = "token_ExpectedTitle"
        )

        val decision = matcher.match(listOf(win), selector, currentActivityClass = "com.example.app.OtherActivity")
        assertTrue("Expected Selected when sole candidate window exists, got $decision", decision is WindowDecision.Selected)
        val selected = decision as WindowDecision.Selected
        assertTrue(selected.breakdown.soleCandidate)
    }

    @Test
    fun `title token is matched via injected tokenizer`() {
        val win = window("com.example.app", id = 1, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 1)
        }.copy(title = "MyScreen")

        val selector = windowSelector(
            packageName = "com.example.app",
            windowType = WindowType.APPLICATION,
            titleToken = "token_MyScreen"
        )

        val decision = matcher.match(listOf(win), selector)
        assertTrue("Expected Selected, got $decision", decision is WindowDecision.Selected)
        val selected = decision as WindowDecision.Selected
        assertTrue(selected.breakdown.titleTokenMatched)
    }

    @Test
    fun `wrong package windows are hard-rejected`() {
        val win = window("com.unrelated.pkg", id = 1, type = WindowType.APPLICATION) {
            node("android.widget.FrameLayout", id = 1)
        }

        val selector = windowSelector(
            packageName = "com.example.app",
            windowType = WindowType.APPLICATION
        )

        val decision = matcher.match(listOf(win), selector)
        assertTrue("Expected NotFound, got $decision", decision is WindowDecision.NotFound)
    }
}

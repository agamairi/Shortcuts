package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorExtractorTest {
    private val extractor = SelectorExtractor(fakeFingerprinter, fakeTokenizer)

    @Test
    fun `id-only actionable parent keeps target identity and records descendant label provenance`() {
        val targetWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.LinearLayout",
                    id = 2,
                    resourceId = "example.app:id/network_row",
                    bounds = r(50, 100, 950, 400),
                    clickable = true
                ) {
                    node("android.widget.TextView", id = 3, text = "Network & internet", bounds = r(100, 150, 700, 250))
                }
            }
        }

        val result = extractor.extract(
            targetWindow.node(2)!!,
            targetWindow,
            IntPoint(300, 200),
            windowSet(targetWindow)
        )

        assertEquals(2, result.targetNodeId)
        assertEquals(3, result.labelSourceNodeId)
        assertEquals("android.widget.LinearLayout", result.selector.expectedClass)
        assertEquals(r(50, 100, 950, 400).left / 1_000f, result.selector.boundsInWindow.left)
        assertTrue(result.selector.strategies.any {
            it.kind == LocatorKind.RESOURCE_ID_EXACT && it.value == "example.app:id/network_row"
        })
        assertTrue(result.selector.strategies.any {
            it.kind == LocatorKind.TEXT_EXACT && it.value == "Network & internet"
        })
    }

    @Test
    fun `touch-containing labelled descendant wins over first labelled descendant`() {
        val targetWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.LinearLayout", id = 2, bounds = r(0, 100, 1_000, 500), clickable = true) {
                    node("android.widget.TextView", id = 3, text = "Title", bounds = r(50, 150, 400, 250))
                    node("android.widget.TextView", id = 4, contentDescription = "Trailing switch", bounds = r(800, 150, 950, 300))
                }
            }
        }

        val result = extractor.extract(
            targetWindow.node(2)!!,
            targetWindow,
            IntPoint(850, 200),
            windowSet(targetWindow)
        )

        assertEquals(4, result.labelSourceNodeId)
        assertEquals(
            "Trailing switch",
            result.selector.strategies.single { it.kind == LocatorKind.CONTENT_DESCRIPTION_EXACT }.value
        )
        assertFalse(result.selector.strategies.any { it.value == "Title" })
    }

    @Test
    fun `selector ranks stability structure row context point and scroll container`() {
        val targetWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, resourceId = "example.app:id/list", scrollable = true) {
                    node("android.widget.LinearLayout", id = 3, collectionRow = 4, bounds = r(0, 100, 1_000, 400)) {
                        node("android.widget.TextView", id = 4, text = "Account A", bounds = r(30, 120, 350, 220))
                        node(
                            "android.widget.Button",
                            id = 5,
                            resourceId = "example.app:id/a123456",
                            text = "Pay $12.99",
                            contentDescription = "Confirm",
                            bounds = r(700, 150, 950, 300),
                            clickable = true,
                            actions = setOf(NodeAction.CLICK)
                        )
                        node("android.widget.Button", id = 6, text = "Other", bounds = r(700, 310, 950, 390), clickable = true)
                    }
                }
            }
        }
        val snapshot = windowSet(targetWindow).copy(density = 2.625f)

        val selector = extractor.extract(
            targetWindow.node(5)!!,
            targetWindow,
            IntPoint(800, 220),
            snapshot
        ).selector

        assertEquals(listOf(0, 1, 2), selector.strategies.map { it.rank })
        assertEquals(
            LocatorStability.LIKELY_STABLE,
            selector.strategies.single { it.kind == LocatorKind.RESOURCE_ID_EXACT }.stability
        )
        assertEquals(
            LocatorStability.VOLATILE,
            selector.strategies.single { it.kind == LocatorKind.TEXT_EXACT }.stability
        )
        assertEquals(NodeRole.BUTTON, selector.expectedRole)
        assertEquals(0, selector.siblingIndex)
        assertEquals(listOf("Account A", "Other"), selector.rowContext!!.siblingText)
        assertEquals(4, selector.rowContext!!.collectionRow)
        assertTrue(selector.ancestorChain.size <= 6)
        assertEquals("android.widget.LinearLayout", selector.ancestorChain.first().className)
        assertNotNull(selector.scrollContainer)
        assertNull(selector.scrollContainer!!.scrollContainer)
        assertEquals(420, selector.rawPoint!!.densityDpi)
        assertEquals(0.8f, selector.rawPoint!!.pointInWindow.x)
        assertTrue(NodeAction.CLICK in selector.requiredActions)
    }

    @Test
    fun `password selector omits field text and hint`() {
        val targetWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    text = "secret value",
                    hintText = "Bank password",
                    contentDescription = "Password",
                    password = true,
                    editable = true,
                    resourceId = "example.app:id/password"
                )
            }
        }

        val selector = extractor.elementSelector(
            targetWindow.node(2)!!,
            targetWindow,
            null,
            windowSet(targetWindow)
        )

        assertFalse(selector.strategies.any { it.kind == LocatorKind.TEXT_EXACT })
        assertFalse(selector.strategies.any { it.value.contains("secret") || it.value.contains("Bank") })
        assertTrue(selector.strategies.any { it.kind == LocatorKind.CONTENT_DESCRIPTION_EXACT })
    }

    @Test
    fun `editable node never emits typed value as text locator`() {
        val targetWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    text = "Agam typed this",
                    hintText = "Enter name",
                    editable = true,
                    resourceId = "example.app:id/name_input"
                )
            }
        }

        val selector = extractor.elementSelector(
            targetWindow.node(2)!!,
            targetWindow,
            null,
            windowSet(targetWindow)
        )

        assertFalse(selector.strategies.any { it.value == "Agam typed this" })
        assertTrue(selector.strategies.any { it.kind == LocatorKind.TEXT_EXACT && it.value == "Enter name" })
    }

    @Test
    fun `window selector hashes title and fingerprints exact window`() {
        val base = screen(APP, "Settings").copy(title = "  Settings  ")
        val selector = extractor.windowSelector(base, windowSet(base), "SettingsActivity")

        assertEquals(APP, selector.packageName)
        assertEquals("SettingsActivity", selector.observedActivityClass)
        assertEquals(fakeTokenizer.tokenize("Settings"), selector.titleToken)
        assertNotNull(selector.fingerprint)
        assertEquals(1f, selector.boundsOnDisplay.right)
    }
}

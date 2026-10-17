package com.shortcuts.app.automation.android.capture

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveTextFieldTest {

    private val compose = "android.widget.EditText"

    @Test
    fun `typing into the second of two id-less fields is credited to that field`() {
        val window = window("com.example.app") {
            node("android.view.View", id = 1) {
                node(compose, id = 2, text = "", hintText = "Search", editable = true, bounds = r(0, 100, 1000, 200))
                node(compose, id = 3, text = "hello", hintText = "Message", editable = true, focused = true, bounds = r(0, 300, 1000, 400))
            }
        }

        assertEquals(3, resolveTextField(window, sourceViewId = null, sourceClassName = compose, enteredText = "hello")?.nodeId)
    }

    @Test
    fun `without focus the field holding the typed text wins`() {
        val window = window("com.example.app") {
            node("android.view.View", id = 1) {
                node(compose, id = 2, text = "", editable = true, bounds = r(0, 100, 1000, 200))
                node(compose, id = 3, text = "hello", editable = true, bounds = r(0, 300, 1000, 400))
            }
        }

        assertEquals(3, resolveTextField(window, null, compose, "hello")?.nodeId)
    }

    @Test
    fun `ambiguous id-less fields return null instead of the first one`() {
        val window = window("com.example.app") {
            node("android.view.View", id = 1) {
                node(compose, id = 2, text = "", editable = true, bounds = r(0, 100, 1000, 200))
                node(compose, id = 3, text = "", editable = true, bounds = r(0, 300, 1000, 400))
            }
        }

        assertNull(resolveTextField(window, null, compose, "hello"))
    }

    @Test
    fun `a view id picks its field even when another field is focused`() {
        val window = window("com.example.app") {
            node("android.view.View", id = 1) {
                node(compose, id = 2, resourceId = "app:id/search", editable = true, focused = true, bounds = r(0, 100, 1000, 200))
                node(compose, id = 3, resourceId = "app:id/message", editable = true, bounds = r(0, 300, 1000, 400))
            }
        }

        assertEquals(3, resolveTextField(window, "app:id/message", compose, "hi")?.nodeId)
    }
}

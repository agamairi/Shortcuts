package com.shortcuts.app.widget

import com.shortcuts.app.data.Automation
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAppearanceTest {

    @Test
    fun `automation widget appearance uses the automation color and icon keys`() {
        val automation = Automation(
            id = 7,
            name = "Evening",
            actionsJson = "[]",
            colorKey = "GREEN",
            iconKey = "BELL"
        )

        val appearance = WidgetAppearance.fromAutomation(automation)

        assertEquals(WidgetColorKey.GREEN, appearance.color)
        assertEquals(WidgetIconKey.BELL, appearance.icon)
    }

    @Test
    fun `automation widget appearance safely falls back for old or invalid values`() {
        val automation = Automation(id = 8, name = "Old", actionsJson = "[]", colorKey = "unknown", iconKey = null)

        val appearance = WidgetAppearance.fromAutomation(automation)

        assertEquals(WidgetColorKey.BLUE, appearance.color)
        assertEquals(WidgetIconKey.BOLT, appearance.icon)
    }

    @Test
    fun `every widget color key resolves to its own compose color`() {
        WidgetColorKey.entries.forEach { key ->
            assertEquals(key.composeColor, resolveWidgetColor(key.name))
        }
    }

    @Test
    fun `color resolver handles unknown and null keys with the deterministic default`() {
        assertEquals(WidgetColorKey.BLUE.composeColor, resolveWidgetColor("unknown"))
        assertEquals(WidgetColorKey.BLUE.composeColor, resolveWidgetColor(null))
    }

    @Test
    fun `every widget icon key resolves to its own drawable`() {
        WidgetIconKey.entries.forEach { key ->
            assertEquals(key, resolveWidgetIconKey(key.name))
            assertEquals(key.drawableRes, resolveWidgetIconDrawable(key.name))
        }
    }

    @Test
    fun `icon resolver handles unknown and null keys with the deterministic default`() {
        assertEquals(WidgetIconKey.BOLT, resolveWidgetIconKey("unknown"))
        assertEquals(WidgetIconKey.BOLT, resolveWidgetIconKey(null))
        assertEquals(WidgetIconKey.BOLT.drawableRes, resolveWidgetIconDrawable("unknown"))
        assertEquals(WidgetIconKey.BOLT.drawableRes, resolveWidgetIconDrawable(null))
    }
}

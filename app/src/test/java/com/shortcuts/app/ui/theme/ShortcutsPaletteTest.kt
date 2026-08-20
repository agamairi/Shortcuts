package com.shortcuts.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.shortcuts.app.widget.WidgetColorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ShortcutsPaletteTest {

    private fun linearize(channel: Float): Float {
        return if (channel <= 0.04045f) {
            channel / 12.92f
        } else {
            ((channel + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    private fun luminance(color: Color): Float {
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun contrastRatio(c1: Color, c2: Color): Float {
        val l1 = luminance(c1)
        val l2 = luminance(c2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun testAllColorsClearContrastFloor() {
        val white = Color.White
        val colors = listOf(
            TileColors.Blue to "Blue",
            TileColors.Purple to "Purple",
            TileColors.Green to "Green",
            TileColors.Orange to "Orange",
            TileColors.Red to "Red",
            TileColors.Teal to "Teal",
            TileColors.Pink to "Pink",
            TileColors.Indigo to "Indigo",
            TileColors.DeepPurple to "DeepPurple",
            TileColors.Cyan to "Cyan",
            TileColors.Brown to "Brown",
            TileColors.BlueGrey to "BlueGrey",
            TileColors.Olive to "Olive",
            TileColors.Navy to "Navy"
        )
        
        colors.forEach { (color, name) ->
            val ratio = contrastRatio(color, white)
            assertTrue("Color $name failed contrast ratio: $ratio < 3.0", ratio >= 3.0f)
        }
    }

    @Test
    fun testTileColorsAndWidgetColorKeyParity() {
        // Enforce that TileColors defines exactly the set from WidgetColorKey
        assertEquals("Blue mismatch", WidgetColorKey.BLUE.composeColor, TileColors.Blue)
        assertEquals("Purple mismatch", WidgetColorKey.PURPLE.composeColor, TileColors.Purple)
        assertEquals("Green mismatch", WidgetColorKey.GREEN.composeColor, TileColors.Green)
        assertEquals("Orange mismatch", WidgetColorKey.ORANGE.composeColor, TileColors.Orange)
        assertEquals("Red mismatch", WidgetColorKey.RED.composeColor, TileColors.Red)
        assertEquals("Teal mismatch", WidgetColorKey.TEAL.composeColor, TileColors.Teal)
        assertEquals("Pink mismatch", WidgetColorKey.PINK.composeColor, TileColors.Pink)
        assertEquals("Indigo mismatch", WidgetColorKey.INDIGO.composeColor, TileColors.Indigo)
        assertEquals("DeepPurple mismatch", WidgetColorKey.DEEP_PURPLE.composeColor, TileColors.DeepPurple)
        assertEquals("Cyan mismatch", WidgetColorKey.CYAN.composeColor, TileColors.Cyan)
        assertEquals("Brown mismatch", WidgetColorKey.BROWN.composeColor, TileColors.Brown)
        assertEquals("BlueGrey mismatch", WidgetColorKey.BLUE_GREY.composeColor, TileColors.BlueGrey)
        assertEquals("Olive mismatch", WidgetColorKey.OLIVE.composeColor, TileColors.Olive)
        assertEquals("Navy mismatch", WidgetColorKey.NAVY.composeColor, TileColors.Navy)
    }

    @Test
    fun testWidgetColorKeyRoundTrip() {
        WidgetColorKey.entries.forEach { key ->
            val fromName = WidgetColorKey.valueOf(key.name)
            assertEquals("Failed round trip for ${key.name}", key, fromName)
        }

        val result = runCatching { WidgetColorKey.valueOf("UNKNOWN_KEY") }
        assertTrue("Unknown key should throw or fallback safely", result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}

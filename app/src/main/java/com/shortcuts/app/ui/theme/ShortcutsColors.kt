package com.shortcuts.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colour palette.
 *
 * The light values are lifted verbatim from the approved design: a WARM neutral ground
 * (#FAF8F5 / #16130F) rather than the cold grey the app used before — the warmth is what
 * lets the saturated shortcut tiles sit on the page without fighting it.
 *
 * The dark values keep that warmth (#14120F, not a blue-black) so the two modes read as the
 * same product. Tile hues are unchanged across modes: they are the shortcut's identity, and
 * they are already saturated enough to hold up on a dark ground.
 */
@Immutable
data class ShortcutsPalette(
    val ground: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val outline: Color,
    val outlineDashed: Color,
    val warn: Color,
    val warnGround: Color,
    val warnInk: Color,
    val danger: Color,
    /** Foreground for the saturated shortcut tiles, which stay the same in both themes. */
    val tileContent: Color,
    val isDark: Boolean
)

val LightPalette = ShortcutsPalette(
    ground = Color(0xFFFAF8F5),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF2EDE6),
    ink = Color(0xFF16130F),
    inkMuted = Color(0xFF4A443D),
    inkFaint = Color(0xFF8A8279),
    outline = Color(0xFFE8E2DA),
    outlineDashed = Color(0xFFD6CEC3),
    warn = Color(0xFFE8A33D),
    warnGround = Color(0xFFFDF6EA),
    warnInk = Color(0xFF7A5A1E),
    danger = Color(0xFFB03B3B),
    tileContent = Color.White,
    isDark = false
)

val DarkPalette = ShortcutsPalette(
    ground = Color(0xFF14120F),
    surface = Color(0xFF1E1B17),
    surfaceMuted = Color(0xFF2A2622),
    ink = Color(0xFFF5F1EB),
    inkMuted = Color(0xFFC9C1B6),
    inkFaint = Color(0xFF9A9187),
    outline = Color(0xFF332E28),
    outlineDashed = Color(0xFF423B33),
    warn = Color(0xFFE8A33D),
    warnGround = Color(0xFF3A2E18),
    warnInk = Color(0xFFF0D9A8),
    danger = Color(0xFFE07A7A),
    tileContent = Color.White,
    isDark = true
)

/** Shortcut tile colours. Identical in both modes — a tile's colour is its identity. */
object TileColors {
    val Blue = com.shortcuts.app.widget.WidgetColorKey.BLUE.composeColor
    val Purple = com.shortcuts.app.widget.WidgetColorKey.PURPLE.composeColor
    val Green = com.shortcuts.app.widget.WidgetColorKey.GREEN.composeColor
    val Orange = com.shortcuts.app.widget.WidgetColorKey.ORANGE.composeColor
    val Red = com.shortcuts.app.widget.WidgetColorKey.RED.composeColor
    val Teal = com.shortcuts.app.widget.WidgetColorKey.TEAL.composeColor
    val Pink = com.shortcuts.app.widget.WidgetColorKey.PINK.composeColor
    val Indigo = com.shortcuts.app.widget.WidgetColorKey.INDIGO.composeColor
    val DeepPurple = com.shortcuts.app.widget.WidgetColorKey.DEEP_PURPLE.composeColor
    val Cyan = com.shortcuts.app.widget.WidgetColorKey.CYAN.composeColor
    val Brown = com.shortcuts.app.widget.WidgetColorKey.BROWN.composeColor
    val BlueGrey = com.shortcuts.app.widget.WidgetColorKey.BLUE_GREY.composeColor
    val Olive = com.shortcuts.app.widget.WidgetColorKey.OLIVE.composeColor
    val Navy = com.shortcuts.app.widget.WidgetColorKey.NAVY.composeColor
}

val LocalShortcutsPalette = staticCompositionLocalOf { LightPalette }

internal fun materialSchemeFor(palette: ShortcutsPalette): ColorScheme =
    if (palette.isDark) {
        darkColorScheme(
            primary = palette.ink,
            onPrimary = palette.ground,
            background = palette.ground,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceMuted,
            onSurfaceVariant = palette.inkMuted,
            outline = palette.outline,
            error = palette.danger
        )
    } else {
        lightColorScheme(
            primary = palette.ink,
            onPrimary = palette.ground,
            background = palette.ground,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceMuted,
            onSurfaceVariant = palette.inkMuted,
            outline = palette.outline,
            error = palette.danger
        )
    }

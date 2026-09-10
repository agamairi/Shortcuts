package com.shortcuts.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The app theme.
 *
 * [mode] decides the palette: [ThemeMode.SYSTEM] defers to the device, the other two force it.
 * Colours beyond Material's own slots (the warm grounds, the amber warning set, tile colours)
 * are reached through [LocalShortcutsPalette] rather than MaterialTheme.colorScheme, because
 * they have no sensible Material equivalent and squeezing them into one loses their meaning.
 *
 * Usage inside a composable:
 *   val palette = LocalShortcutsPalette.current
 *   Box(Modifier.background(palette.ground))
 */
@Composable
fun ShortcutsTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (useDark) DarkPalette else LightPalette

    CompositionLocalProvider(LocalShortcutsPalette provides palette) {
        MaterialTheme(
            colorScheme = materialSchemeFor(palette),
            typography = ShortcutsTypography,
            content = content
        )
    }
}

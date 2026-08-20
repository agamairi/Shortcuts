package com.shortcuts.app.ui.theme

/**
 * How the app decides between the light and dark palettes.
 *
 * [SYSTEM] is the default: it follows the device setting, which is what most users expect
 * and what Android's own apps do. [LIGHT] and [DARK] are explicit overrides.
 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

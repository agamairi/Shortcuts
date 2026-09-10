package com.shortcuts.app.widget

import androidx.compose.ui.graphics.Color

enum class WidgetColorKey(val hex: Long) {
    BLUE(0xFF1A73E8),
    PURPLE(0xFF9C27B0),
    GREEN(0xFF34A853),
    ORANGE(0xFFEF6C00),
    RED(0xFFD32F2F),
    TEAL(0xFF00897B),
    PINK(0xFFE91E63),
    INDIGO(0xFF3F51B5),
    DEEP_PURPLE(0xFF673AB7),
    CYAN(0xFF0097A7),
    BROWN(0xFF795548),
    BLUE_GREY(0xFF607D8B),
    OLIVE(0xFF827717),
    NAVY(0xFF1A237E);

    val composeColor: Color get() = Color(hex)
}

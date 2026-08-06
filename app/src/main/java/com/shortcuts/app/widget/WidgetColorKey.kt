package com.shortcuts.app.widget

import androidx.compose.ui.graphics.Color

enum class WidgetColorKey(val hex: Long) {
    BLUE(0xFF1A73E8),
    PURPLE(0xFF9C27B0),
    GREEN(0xFF34A853),
    ORANGE(0xFFF57C00),
    RED(0xFFD32F2F),
    TEAL(0xFF00897B);

    val composeColor: Color get() = Color(hex)
}

package com.shortcuts.app.widget

object GreetingTextHelper {
    fun greetingFor(hour: Int, name: String): String {
        val displayName = if (name.isBlank()) "there" else name
        val greeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Hi"
        }
        return "$greeting, $displayName"
    }
}

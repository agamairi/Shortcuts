package com.shortcuts.app.util

import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey

object AutomationVisuals {
    fun colorForAutomation(id: Int): WidgetColorKey {
        val entries = WidgetColorKey.entries
        val index = (id.hashCode() and 0x7FFFFFFF) % entries.size
        return entries[index]
    }

    fun iconForAutomation(id: Int): WidgetIconKey {
        val entries = WidgetIconKey.entries
        val index = (id.hashCode() and 0x7FFFFFFF) % entries.size
        return entries[index]
    }
}

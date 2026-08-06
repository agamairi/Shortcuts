package com.shortcuts.app.util

import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.Automation

enum class SortMode {
    NONE,
    NAME,
    ACTION_COUNT
}

object AutomationSorter {
    private val actionConverter = ActionConverter()

    fun sortAutomations(
        automations: List<Automation>,
        sortMode: SortMode,
        ascending: Boolean
    ): List<Automation> {
        return when (sortMode) {
            SortMode.NONE -> {
                if (ascending) automations else automations.reversed()
            }
            SortMode.NAME -> {
                val comparator = compareBy<Automation, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                if (ascending) automations.sortedWith(comparator) else automations.sortedWith(comparator.reversed())
            }
            SortMode.ACTION_COUNT -> {
                val comparator = compareBy<Automation> { automation ->
                    runCatching { actionConverter.toActionList(automation.actionsJson).size }.getOrDefault(0)
                }
                if (ascending) automations.sortedWith(comparator) else automations.sortedWith(comparator.reversed())
            }
        }
    }
}

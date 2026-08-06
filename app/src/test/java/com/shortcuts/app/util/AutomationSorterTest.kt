package com.shortcuts.app.util

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationSorterTest {

    private val converter = ActionConverter()

    private val singleAction = converter.fromActionList(
        listOf(Action(actionType = ActionType.SYSTEM_TOGGLE))
    )
    private val tripleAction = converter.fromActionList(
        listOf(
            Action(actionType = ActionType.SYSTEM_TOGGLE),
            Action(actionType = ActionType.APP_INTENT),
            Action(actionType = ActionType.HTTP_REQUEST)
        )
    )
    private val emptyActions = "[]"

    private val autoA = Automation(id = 1, name = "Zebra Routine", actionsJson = singleAction) // 1 action
    private val autoB = Automation(id = 2, name = "Alpha Task", actionsJson = tripleAction) // 3 actions
    private val autoC = Automation(id = 3, name = "Morning Mode", actionsJson = emptyActions) // 0 actions

    private val testList = listOf(autoA, autoB, autoC)

    @Test
    fun `sortAutomations NONE mode ascending retains original order`() {
        val result = AutomationSorter.sortAutomations(testList, SortMode.NONE, ascending = true)
        assertEquals(listOf(autoA, autoB, autoC), result)
    }

    @Test
    fun `sortAutomations NONE mode descending reverses original order`() {
        val result = AutomationSorter.sortAutomations(testList, SortMode.NONE, ascending = false)
        assertEquals(listOf(autoC, autoB, autoA), result)
    }

    @Test
    fun `sortAutomations NAME mode ascending sorts A-Z`() {
        val result = AutomationSorter.sortAutomations(testList, SortMode.NAME, ascending = true)
        assertEquals(listOf(autoB, autoC, autoA), result)
    }

    @Test
    fun `sortAutomations NAME mode descending sorts Z-A`() {
        val result = AutomationSorter.sortAutomations(testList, SortMode.NAME, ascending = false)
        assertEquals(listOf(autoA, autoC, autoB), result)
    }

    @Test
    fun `sortAutomations ACTION_COUNT mode ascending sorts by action count low to high`() {
        val result = AutomationSorter.sortAutomations(testList, SortMode.ACTION_COUNT, ascending = true)
        assertEquals(listOf(autoC, autoA, autoB), result) // 0, 1, 3
    }

    @Test
    fun `sortAutomations ACTION_COUNT mode descending sorts by action count high to low`() {
        val result = AutomationSorter.sortAutomations(testList, SortMode.ACTION_COUNT, ascending = false)
        assertEquals(listOf(autoB, autoA, autoC), result) // 3, 1, 0
    }
}

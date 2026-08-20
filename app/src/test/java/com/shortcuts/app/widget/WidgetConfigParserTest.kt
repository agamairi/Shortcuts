package com.shortcuts.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigParserTest {
    @Test
    fun `preserves all ids in a valid multi-shortcut binding`() {
        assertEquals(listOf(7, 8, 9), WidgetConfigParser.automationIds("[7,8,9]"))
    }

    @Test
    fun `invalid legacy JSON becomes an empty setup state`() {
        assertEquals(emptyList<Int>(), WidgetConfigParser.automationIds("not-json"))
    }
}

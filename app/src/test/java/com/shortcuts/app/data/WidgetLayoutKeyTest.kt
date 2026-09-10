package com.shortcuts.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutKeyTest {

    @Test
    fun `null and blank layout keys resolve to auto`() {
        assertEquals(WidgetLayoutKey.AUTO, WidgetLayoutKey.fromKeyOrAuto(null))
        assertEquals(WidgetLayoutKey.AUTO, WidgetLayoutKey.fromKeyOrAuto(""))
        assertEquals(WidgetLayoutKey.AUTO, WidgetLayoutKey.fromKeyOrAuto("  "))
    }

    @Test
    fun `layout keys match enum names regardless of case or surrounding whitespace`() {
        assertEquals(WidgetLayoutKey.GRID, WidgetLayoutKey.fromKeyOrAuto("GRID"))
        assertEquals(WidgetLayoutKey.GRID, WidgetLayoutKey.fromKeyOrAuto("grid"))
        assertEquals(WidgetLayoutKey.GRID, WidgetLayoutKey.fromKeyOrAuto("Grid"))
        assertEquals(WidgetLayoutKey.LIST, WidgetLayoutKey.fromKeyOrAuto(" list "))
    }

    @Test
    fun `unknown future layout key degrades to auto`() {
        assertEquals(WidgetLayoutKey.AUTO, WidgetLayoutKey.fromKeyOrAuto("CAROUSEL"))
    }
}

package com.shortcuts.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordButtonNavigationTest {

    @Test
    fun `record button opens recorder regardless of accessibility service state`() {
        assertEquals(RECORDER_ROUTE, recordButtonDestination(serviceActive = false))
        assertEquals(RECORDER_ROUTE, recordButtonDestination(serviceActive = true))
    }
}

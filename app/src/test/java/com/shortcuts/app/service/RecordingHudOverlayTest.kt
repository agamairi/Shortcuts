package com.shortcuts.app.service

import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingHudOverlayTest {
    @Test
    fun `recording HUD cannot receive touch or keyboard focus`() {
        val flags = RecordingHudOverlay.windowFlags()

        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }
}

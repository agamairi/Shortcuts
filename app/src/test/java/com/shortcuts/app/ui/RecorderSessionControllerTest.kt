package com.shortcuts.app.ui

import android.content.Context
import com.shortcuts.app.ui.screens.RecorderSessionController
import com.shortcuts.app.util.AccessibilityStatusChecker
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test

class RecorderSessionControllerTest {

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkObject(AccessibilityStatusChecker)
    }

    @Test
    fun `recorder refuses to enter recording state when accessibility service is disabled`() {
        val context = mockk<Context>(relaxed = true)
        var didEnterRecordingState = false
        mockkObject(AccessibilityStatusChecker)
        every { AccessibilityStatusChecker.isAccessibilityServiceActive(context) } returns false

        val didStart = RecorderSessionController().startIfServiceActive(context) {
            didEnterRecordingState = true
        }

        assertFalse(didStart)
        assertFalse(didEnterRecordingState)
        verify { AccessibilityStatusChecker.isAccessibilityServiceActive(context) }
    }
}

package com.shortcuts.app.ui

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.ui.screens.RecorderListOperations
import com.shortcuts.app.ui.screens.RecorderSessionUiState
import com.shortcuts.app.ui.screens.RecorderUiState
import com.shortcuts.app.ui.screens.determineRecorderSessionUiState
import com.shortcuts.app.ui.screens.determineRecorderUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderScreenLogicTest {

    @Test
    fun `recorder session state distinguishes start recording and review`() {
        // live session with N captured steps renders as live-with-N, not idle
        assertEquals(
            RecorderSessionUiState.RECORDING,
            determineRecorderSessionUiState(isRecording = true, recordedActionsCount = 5, editableActionsCount = 0)
        )
        
        // idle state
        assertEquals(
            RecorderSessionUiState.START_RECORDING,
            determineRecorderSessionUiState(isRecording = false, recordedActionsCount = 0, editableActionsCount = 0)
        )
        
        // review state
        assertEquals(
            RecorderSessionUiState.REVIEW,
            determineRecorderSessionUiState(isRecording = false, recordedActionsCount = 2, editableActionsCount = 0)
        )
        
        assertEquals(
            RecorderSessionUiState.REVIEW,
            determineRecorderSessionUiState(isRecording = false, recordedActionsCount = 0, editableActionsCount = 2)
        )
    }

    @Test
    fun `recorder prerequisite state covers consent and accessibility combinations`() {
        assertEquals(RecorderUiState.CONSENT_REQUIRED, determineRecorderUiState(false, false))
        assertEquals(RecorderUiState.CONSENT_REQUIRED, determineRecorderUiState(false, true))
        assertEquals(RecorderUiState.SERVICE_NOT_ENABLED, determineRecorderUiState(true, false))
        assertEquals(RecorderUiState.READY_TO_RECORD, determineRecorderUiState(true, true))
    }

    @Test
    fun testRecorderListOperations() {
        val action1 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "1")
        val action2 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "2")
        val action3 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "3")
        
        val list = mutableListOf(action1, action2, action3)
        
        // Move up boundary
        RecorderListOperations.moveUp(list, 0)
        assertEquals(listOf(action1, action2, action3), list)
        
        // Move up normal
        RecorderListOperations.moveUp(list, 1)
        assertEquals(listOf(action2, action1, action3), list)
        
        // Move down normal
        RecorderListOperations.moveDown(list, 1)
        assertEquals(listOf(action2, action3, action1), list)
        
        // Move down boundary
        RecorderListOperations.moveDown(list, 2)
        assertEquals(listOf(action2, action3, action1), list)
        
        // Remove normal
        RecorderListOperations.remove(list, 1)
        assertEquals(listOf(action2, action1), list)
        
        // Remove boundary out of bounds
        RecorderListOperations.remove(list, 5)
        assertEquals(listOf(action2, action1), list)
    }
}

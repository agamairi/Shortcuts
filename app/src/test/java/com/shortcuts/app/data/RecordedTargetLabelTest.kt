package com.shortcuts.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordedTargetLabelTest {

    private fun tap(text: String? = null, desc: String? = null, id: String? = null) = Action(
        actionType = ActionType.UI_AUTOMATION,
        uiActionType = "TAP",
        targetText = text,
        targetContentDescription = desc,
        targetNodeId = id
    )

    @Test
    fun `text wins, then content description, then a readable view id`() {
        assertEquals("Save", tap(text = "Save", desc = "Save button", id = "app:id/save").recordedTargetLabel())
        assertEquals("7", tap(desc = "7", id = "com.google.android.calculator:id/digit_7").recordedTargetLabel())
        assertEquals("digit 7", tap(id = "com.google.android.calculator:id/digit_7").recordedTargetLabel())
    }

    @Test
    fun `an unlabeled element has no label`() {
        assertNull(tap(text = " ").recordedTargetLabel())
    }
}

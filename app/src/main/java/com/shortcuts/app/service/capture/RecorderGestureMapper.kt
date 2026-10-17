package com.shortcuts.app.service.capture

/** Direction convention: a finger moving up reveals later content, therefore it is FORWARD. */
object RecorderGestureMapper {
    fun scrollDelta(start: Float, end: Float): Int = (start - end).toInt()
}

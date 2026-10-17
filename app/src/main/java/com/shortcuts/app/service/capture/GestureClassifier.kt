package com.shortcuts.app.service.capture

import kotlin.math.hypot

/** Android-free description of the parts of a MotionEvent the recorder needs. */
data class TouchSample(
    val action: TouchAction,
    val x: Float,
    val y: Float,
    val uptimeMs: Long
)

enum class TouchAction { DOWN, MOVE, UP, CANCEL, POINTER_DOWN }
enum class CapturedGestureKind { TAP, LONG_PRESS, SWIPE, CANCELLED }

data class ClassifiedGesture(
    val kind: CapturedGestureKind,
    val samples: List<TouchSample>
) {
    val down: TouchSample? get() = samples.firstOrNull()
    val up: TouchSample? get() = samples.lastOrNull { it.action == TouchAction.UP }
    val heldMillis: Long get() = (up?.uptimeMs ?: down?.uptimeMs ?: 0L) - (down?.uptimeMs ?: 0L)
}

/** Stateful, platform-independent gesture classifier. One instance is used for one finger stream. */
class GestureClassifier(
    private val touchSlopPx: Float,
    private val longPressTimeoutMs: Long
) {
    private val samples = mutableListOf<TouchSample>()
    private var cancelled = false

    fun reset() {
        samples.clear()
        cancelled = false
    }

    fun add(sample: TouchSample): ClassifiedGesture? {
        if (sample.action == TouchAction.DOWN) reset()
        if (samples.isEmpty() && sample.action != TouchAction.DOWN) return null
        samples += sample
        if (sample.action == TouchAction.POINTER_DOWN || sample.action == TouchAction.CANCEL) cancelled = true
        return when (sample.action) {
            TouchAction.UP, TouchAction.CANCEL, TouchAction.POINTER_DOWN -> classify()
            else -> null
        }
    }

    fun classify(): ClassifiedGesture {
        val snapshot = samples.toList()
        val down = snapshot.firstOrNull()
        val up = snapshot.lastOrNull { it.action == TouchAction.UP }
        val kind = when {
            cancelled || down == null || up == null -> CapturedGestureKind.CANCELLED
            snapshot.any { hypot((it.x - down.x).toDouble(), (it.y - down.y).toDouble()) > touchSlopPx } -> CapturedGestureKind.SWIPE
            up.uptimeMs - down.uptimeMs >= longPressTimeoutMs -> CapturedGestureKind.LONG_PRESS
            else -> CapturedGestureKind.TAP
        }
        return ClassifiedGesture(kind, snapshot)
    }
}

package com.shortcuts.app.automation.core.geometry

import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.model.NormalizedPoint
import com.shortcuts.app.automation.model.NormalizedRect
import kotlin.math.hypot

/** Window-relative geometry, so coordinates survive inset, density and window-size changes. */
object NormalizedGeometry {
    fun normalize(rect: IntRect, within: IntRect): NormalizedRect {
        val w = within.width.coerceAtLeast(1).toFloat()
        val h = within.height.coerceAtLeast(1).toFloat()
        return NormalizedRect(
            left = (rect.left - within.left) / w,
            top = (rect.top - within.top) / h,
            right = (rect.right - within.left) / w,
            bottom = (rect.bottom - within.top) / h
        )
    }

    fun normalize(x: Int, y: Int, within: IntRect): NormalizedPoint {
        val w = within.width.coerceAtLeast(1).toFloat()
        val h = within.height.coerceAtLeast(1).toFloat()
        return NormalizedPoint((x - within.left) / w, (y - within.top) / h)
    }

    fun denormalize(point: NormalizedPoint, within: IntRect): Pair<Float, Float> =
        Pair(within.left + point.x * within.width, within.top + point.y * within.height)

    fun center(rect: NormalizedRect): NormalizedPoint =
        NormalizedPoint((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)

    /** Euclidean distance in normalized units (the window diagonal is ~1.41). */
    fun distance(a: NormalizedPoint, b: NormalizedPoint): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
}

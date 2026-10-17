package com.shortcuts.app.service.capture

data class RelayPoint(val x: Float, val y: Float, val relativeTimeMs: Long)
data class RelayStrokePlan(val points: List<RelayPoint>, val durationMs: Long)

/** Converts the user's already-finished gesture into a safe, bounded injected stroke. */
object RelayPlanner {
    fun plan(gesture: ClassifiedGesture): RelayStrokePlan? {
        val down = gesture.down ?: return null
        val up = gesture.up ?: return null
        return when (gesture.kind) {
            CapturedGestureKind.TAP -> RelayStrokePlan(listOf(RelayPoint(down.x, down.y, 0)), 50L)
            CapturedGestureKind.LONG_PRESS -> RelayStrokePlan(
                listOf(RelayPoint(down.x, down.y, 0)),
                gesture.heldMillis.coerceAtLeast(600L).coerceAtMost(1_500L)
            )
            CapturedGestureKind.SWIPE -> RelayStrokePlan(
                downsample(gesture.samples.filter { it.action != TouchAction.CANCEL }, 12, down.uptimeMs),
                gesture.heldMillis.coerceIn(80L, 700L)
            )
            CapturedGestureKind.CANCELLED -> null
        }
    }

    private fun downsample(samples: List<TouchSample>, maxPoints: Int, startMs: Long): List<RelayPoint> {
        if (samples.size <= maxPoints) return samples.map { RelayPoint(it.x, it.y, (it.uptimeMs - startMs).coerceAtLeast(0L)) }
        val picked = ArrayList<TouchSample>(maxPoints)
        for (index in 0 until maxPoints) {
            val sourceIndex = (index.toLong() * (samples.size - 1) / (maxPoints - 1)).toInt()
            picked += samples[sourceIndex]
        }
        return picked.map { RelayPoint(it.x, it.y, (it.uptimeMs - startMs).coerceAtLeast(0L)) }
    }
}

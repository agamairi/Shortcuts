package com.shortcuts.app.service.capture

/** Pure classification of the strict post-lift snapshot budget. */
internal enum class SnapshotAvailability { READY, LATE, MISSING }

internal fun classifySnapshotAvailability(
    hasSnapshot: Boolean,
    completedAtUptimeMs: Long?,
    actionUpUptimeMs: Long,
    budgetMs: Long = 120L
): SnapshotAvailability = when {
    !hasSnapshot -> SnapshotAvailability.MISSING
    completedAtUptimeMs != null && completedAtUptimeMs <= actionUpUptimeMs + budgetMs -> SnapshotAvailability.READY
    else -> SnapshotAvailability.LATE
}

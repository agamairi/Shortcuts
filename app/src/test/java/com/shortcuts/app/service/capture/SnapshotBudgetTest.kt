package com.shortcuts.app.service.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotBudgetTest {
    @Test fun readySnapshotIsUsed() = assertEquals(SnapshotAvailability.READY, classifySnapshotAvailability(true, 1_120L, 1_000L))
    @Test fun lateSnapshotDoesNotBlockRelay() = assertEquals(SnapshotAvailability.LATE, classifySnapshotAvailability(true, 1_121L, 1_000L))
    @Test fun missingSnapshotFallsBackToCoordinates() = assertEquals(SnapshotAvailability.MISSING, classifySnapshotAvailability(false, null, 1_000L))
}

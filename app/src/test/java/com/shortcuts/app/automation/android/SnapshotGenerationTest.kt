package com.shortcuts.app.automation.android

import com.shortcuts.app.automation.android.replay.staleSnapshotGenerationRejection
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotGenerationTest {
    @Test
    fun `request from an old snapshot generation is rejected`() {
        assertNotNull(staleSnapshotGenerationRejection(requestGeneration = 7, latestGeneration = 8))
        assertNull(staleSnapshotGenerationRejection(requestGeneration = 8, latestGeneration = 8))
    }
}

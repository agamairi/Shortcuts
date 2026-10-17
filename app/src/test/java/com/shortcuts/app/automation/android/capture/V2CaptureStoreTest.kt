package com.shortcuts.app.automation.android.capture

import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CaptureStoreTest {
    private fun session() = PersistedV2Session(
        workflowId = "workflow",
        initialSnapshot = WindowSetSnapshot(1L, 100, 100, 0, 1f, emptyList())
    )

    @Test fun appendAndRestoreRoundTrip() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            val event = PersistedV2EventWrapper(type = "interaction", lateSnapshot = true)
            assertTrue(store.appendActiveEvent(session().copy(events = listOf(event)), event))
            val restored = store.loadActiveSession()
            assertNotNull(restored)
            assertEquals(listOf(event), restored!!.events)
        } finally { dir.deleteRecursively() }
    }

    @Test fun truncatedFinalLineIsDiscarded() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            val event = PersistedV2EventWrapper(type = "interaction")
            store.appendActiveEvent(session().copy(events = listOf(event)), event)
            File(dir, FileV2CaptureStore.EVENT_LOG_FILE_NAME).appendText("{truncated")
            assertEquals(1, store.loadActiveSession()!!.events.size)
            assertNull(store.takeLoadFailure())
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptionBeforeLastRecordIsReported() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            File(dir, FileV2CaptureStore.EVENT_LOG_FILE_NAME).writeText("{bad}\n{\"type\":\"interaction\"}\n")
            assertNull(store.loadActiveSession())
            assertNotNull(store.takeLoadFailure())
        } finally { dir.deleteRecursively() }
    }

    @Test fun eventCapIsEnforcedAndWorkflowWritesRoundTripAtomically() {
        val dir = Files.createTempDirectory("v2-capture-store").toFile()
        try {
            val store = FileV2CaptureStore(dir)
            store.saveActiveSession(session())
            repeat(FileV2CaptureStore.MAX_EVENTS) { index ->
                val event = PersistedV2EventWrapper(type = "event-$index")
                assertTrue(store.appendActiveEvent(session(), event))
            }
            assertTrue(!store.appendActiveEvent(session(), PersistedV2EventWrapper(type = "overflow")))
            store.saveLastWorkflow("{\"schemaVersion\":2}")
            assertEquals("{\"schemaVersion\":2}", store.loadLastWorkflow())
            assertTrue(File(dir, FileV2CaptureStore.LAST_WORKFLOW_FILE_NAME).isFile)
        } finally { dir.deleteRecursively() }
    }
}

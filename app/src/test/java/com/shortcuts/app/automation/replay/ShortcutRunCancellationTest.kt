package com.shortcuts.app.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRunCancellationTest {
    @Test
    fun `cancelling a run cancels its job without interrupting this thread`() {
        val cancellation = ActiveRunCancellation()
        val job = Job()
        cancellation.bindRunJob(job)

        cancellation.cancel()

        assertTrue(job.isCancelled)
        assertFalse("Run cancellation must not interrupt a pooled caller thread", Thread.currentThread().isInterrupted)
    }
}

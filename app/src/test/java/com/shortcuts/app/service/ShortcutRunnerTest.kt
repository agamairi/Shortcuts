package com.shortcuts.app.service

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutRunnerTest {

    @Test
    fun `active shortcut is admitted and starts the foreground service`() = runBlocking {
        val starter = RecordingStarter()
        val runner = ShortcutRunner(
            store = FakeStore(RunnableShortcut(id = 7, name = "Morning", isActive = true)),
            acceptedRunStarter = starter,
            admission = FakeAdmission()
        )

        val outcome = runner.run(ShortcutRunRequest(7, ShortcutRunSource.DASHBOARD))

        assertTrue(outcome is ShortcutRunOutcome.Started)
        assertEquals("Morning", (outcome as ShortcutRunOutcome.Started).shortcutName)
        assertEquals(listOf(7), starter.startedShortcutIds)
    }

    @Test
    fun `inactive shortcut is rejected before it is admitted or started`() = runBlocking {
        val starter = RecordingStarter()
        val admission = FakeAdmission()
        val runner = ShortcutRunner(
            store = FakeStore(RunnableShortcut(id = 7, name = "Morning", isActive = false)),
            acceptedRunStarter = starter,
            admission = admission
        )

        val outcome = runner.run(ShortcutRunRequest(7, ShortcutRunSource.WIDGET))

        assertEquals(
            ShortcutRunOutcome.RejectedInactive(
                ShortcutRunRequest(7, ShortcutRunSource.WIDGET),
                "Morning"
            ),
            outcome
        )
        assertFalse(admission.wasAcquireAttempted)
        assertTrue(starter.startedShortcutIds.isEmpty())
    }

    @Test
    fun `missing shortcut is rejected without starting a service`() = runBlocking {
        val starter = RecordingStarter()
        val runner = ShortcutRunner(
            store = FakeStore(null),
            acceptedRunStarter = starter,
            admission = FakeAdmission()
        )

        val outcome = runner.run(ShortcutRunRequest(404, ShortcutRunSource.DASHBOARD))

        assertEquals(
            ShortcutRunOutcome.RejectedMissingShortcut(
                ShortcutRunRequest(404, ShortcutRunSource.DASHBOARD)
            ),
            outcome
        )
        assertTrue(starter.startedShortcutIds.isEmpty())
    }

    @Test
    fun `second active shortcut is explicitly rejected while a run owns admission`() = runBlocking {
        val starter = RecordingStarter()
        val runner = ShortcutRunner(
            store = FakeStore(RunnableShortcut(id = 8, name = "Evening", isActive = true)),
            acceptedRunStarter = starter,
            admission = FakeAdmission(allowAcquire = false)
        )

        val outcome = runner.run(ShortcutRunRequest(8, ShortcutRunSource.WIDGET))

        assertEquals(
            ShortcutRunOutcome.RejectedAlreadyRunning(
                ShortcutRunRequest(8, ShortcutRunSource.WIDGET)
            ),
            outcome
        )
        assertTrue(starter.startedShortcutIds.isEmpty())
        assertEquals("Another shortcut is already running.", outcome.userMessage())
    }

    @Test
    fun `failed service start releases the admission lease for the next request`() = runBlocking {
        val admission = FakeAdmission()
        val runner = ShortcutRunner(
            store = FakeStore(RunnableShortcut(id = 9, name = "Errand", isActive = true)),
            acceptedRunStarter = AcceptedShortcutRunStarter { _, _ -> error("service unavailable") },
            admission = admission
        )

        val outcome = runner.run(ShortcutRunRequest(9, ShortcutRunSource.DASHBOARD))

        assertEquals(ShortcutRunFailureReason.FOREGROUND_SERVICE_START_FAILED, (outcome as ShortcutRunOutcome.Failed).reason)
        assertEquals(listOf("run-1"), admission.releasedRunIds)
    }

    @Test
    fun `editor draft uses the same admission path and EDITOR source`() {
        val editorStarter = RecordingEditorStarter()
        val runner = ShortcutRunner(
            store = FakeStore(null),
            acceptedRunStarter = RecordingStarter(),
            admission = FakeAdmission(),
            acceptedEditorRunStarter = editorStarter
        )

        val request = EditorShortcutRunRequest(
            shortcutName = "Draft",
            actions = listOf(Action(ActionType.WAIT, delayMillis = 1))
        )

        val outcome = runner.runEditor(request)

        assertEquals(ShortcutRunSource.EDITOR, outcome.source)
        assertTrue(outcome is ShortcutRunOutcome.EditorStarted)
        assertEquals(listOf("Draft"), editorStarter.startedNames)
    }

    @Test
    fun `editor gateway refuses an external effect until the combined confirmation was accepted`() {
        val editorStarter = RecordingEditorStarter()
        val admission = FakeAdmission()
        val runner = ShortcutRunner(
            store = FakeStore(null),
            acceptedRunStarter = RecordingStarter(),
            admission = admission,
            acceptedEditorRunStarter = editorStarter
        )

        val outcome = runner.runEditor(
            EditorShortcutRunRequest(
                shortcutName = "Send update",
                actions = listOf(Action(ActionType.SEND_MESSAGE, target = "+14165551212"))
            )
        )

        assertTrue(outcome is ShortcutRunOutcome.EditorConfirmationRequired)
        assertFalse(admission.wasAcquireAttempted)
        assertTrue(editorStarter.startedNames.isEmpty())
    }

    private class FakeStore(private val shortcut: RunnableShortcut?) : ShortcutRunStore {
        override suspend fun findShortcut(id: Int): RunnableShortcut? = shortcut
    }

    private class RecordingStarter : AcceptedShortcutRunStarter {
        val startedShortcutIds = mutableListOf<Int>()

        override fun start(shortcutId: Int, runId: String) {
            startedShortcutIds += shortcutId
        }
    }

    private class RecordingEditorStarter : AcceptedEditorRunStarter {
        val startedNames = mutableListOf<String>()

        override fun start(shortcutName: String, actions: List<Action>, runId: String) {
            startedNames += shortcutName
        }
    }

    private class FakeAdmission(
        private val allowAcquire: Boolean = true
    ) : ShortcutRunAdmission {
        var wasAcquireAttempted = false
        val releasedRunIds = mutableListOf<String>()

        override fun tryAcquire(): ShortcutRunLease? {
            wasAcquireAttempted = true
            return if (allowAcquire) ShortcutRunLease("run-1", NoExecutionCancellation) else null
        }

        override fun release(runId: String) {
            releasedRunIds += runId
        }
    }
}

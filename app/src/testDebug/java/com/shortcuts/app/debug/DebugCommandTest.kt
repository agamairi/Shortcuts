package com.shortcuts.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCommandTest {
    @Test
    fun `parses every command`() {
        assertTrue(DebugCommand.parse("start_recording", null) is DebugCommand.StartRecording)
        assertTrue(DebugCommand.parse("stop_recording", null) is DebugCommand.StopRecording)
        assertTrue(DebugCommand.parse("dump_recording", null) is DebugCommand.DumpRecording)
        assertEquals(DebugCommand.Save("demo"), DebugCommand.parse("save", "demo"))
        assertEquals(DebugCommand.Run("demo"), DebugCommand.parse("run", "demo"))
        assertTrue(DebugCommand.parse("list", null) is DebugCommand.List)
        assertEquals(DebugCommand.Delete("demo"), DebugCommand.parse("delete", "demo"))
        assertTrue(DebugCommand.parse("clear_recording", null) is DebugCommand.ClearRecording)
    }

    @Test
    fun `requires a name for save run and delete`() {
        listOf("save", "run", "delete").forEach { command ->
            assertTrue(DebugCommand.parse(command, null) is DebugCommand.Invalid)
            assertTrue(DebugCommand.parse(command, "") is DebugCommand.Invalid)
            assertTrue(DebugCommand.parse(command, "   ") is DebugCommand.Invalid)
        }
    }

    @Test
    fun `rejects unknown and null commands`() {
        assertTrue(DebugCommand.parse("not_a_command", null) is DebugCommand.Invalid)
        assertTrue(DebugCommand.parse(null, null) is DebugCommand.Invalid)
    }
}

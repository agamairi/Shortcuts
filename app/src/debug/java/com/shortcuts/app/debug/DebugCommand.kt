package com.shortcuts.app.debug

sealed class DebugCommand(val commandName: String) {
    data object StartRecording : DebugCommand("start_recording")
    data object StopRecording : DebugCommand("stop_recording")
    data object DumpRecording : DebugCommand("dump_recording")
    data class Save(val name: String) : DebugCommand("save")
    data class Run(val name: String) : DebugCommand("run")
    data object List : DebugCommand("list")
    data class Delete(val name: String) : DebugCommand("delete")
    data object ClearRecording : DebugCommand("clear_recording")
    data class Invalid(val reason: String) : DebugCommand("invalid")

    companion object {
        fun parse(cmd: String?, name: String?): DebugCommand {
            val normalizedCommand = cmd?.trim().orEmpty()
            val normalizedName = name?.trim().orEmpty()
            return when (normalizedCommand) {
                "start_recording" -> StartRecording
                "stop_recording" -> StopRecording
                "dump_recording" -> DumpRecording
                "save" -> if (normalizedName.isEmpty()) {
                    Invalid("save requires a name")
                } else {
                    Save(normalizedName)
                }
                "run" -> if (normalizedName.isEmpty()) {
                    Invalid("run requires a name")
                } else {
                    Run(normalizedName)
                }
                "list" -> List
                "delete" -> if (normalizedName.isEmpty()) {
                    Invalid("delete requires a name")
                } else {
                    Delete(normalizedName)
                }
                "clear_recording" -> ClearRecording
                "" -> Invalid("cmd is missing")
                else -> Invalid("unknown command: $normalizedCommand")
            }
        }
    }
}

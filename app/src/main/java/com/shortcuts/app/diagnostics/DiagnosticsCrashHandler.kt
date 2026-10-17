package com.shortcuts.app.diagnostics

/**
 * UncaughtExceptionHandler that synchronously logs unhandled crashes before chaining
 * to the previously registered handler.
 */
class DiagnosticsCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val log: DiagnosticsLog
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val stack = throwable.stackTrace.take(40).map { formatFrame(it) }
            val causes = mutableListOf<String>()
            var currentCause = throwable.cause
            var causeCount = 0
            while (currentCause != null && causeCount < 3) {
                causes.add("${currentCause.javaClass.name}: ${currentCause.message?.take(300) ?: ""}")
                val frames = currentCause.stackTrace.take(10)
                for (frame in frames) {
                    causes.add(formatFrame(frame))
                }
                currentCause = currentCause.cause
                causeCount++
            }

            val fields = LinkedHashMap<String, Any?>()
            fields["thread"] = thread.name
            fields["exceptionClass"] = throwable.javaClass.name
            fields["message"] = throwable.message?.take(300)
            fields["stack"] = stack
            fields["causes"] = causes

            log.appendSync("app", "crash", fields)
            log.flush()
        } catch (_: Throwable) {
            // Never let logging prevent the previous handler from being called
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun formatFrame(frame: StackTraceElement): String {
        val fileName = frame.fileName ?: "Unknown"
        return "${frame.className}.${frame.methodName}($fileName:${frame.lineNumber})"
    }
}

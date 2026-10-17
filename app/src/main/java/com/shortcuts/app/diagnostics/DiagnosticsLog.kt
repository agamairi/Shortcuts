package com.shortcuts.app.diagnostics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure JVM file-based diagnostics log with size-capped rotation and JSON Lines output.
 * No android.* dependencies so it executes and unit-tests cleanly on the JVM.
 */
class DiagnosticsLog(
    private val dir: File,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val uptimeClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxFileBytes: Long = 1_000_000L,
    private val maxFiles: Int = 5,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-log").apply { isDaemon = true }
    }
) {
    @Volatile
    var enabled: Boolean = true

    private val writeLock = Any()
    private val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create()

    companion object {
        const val CURRENT_FILE_NAME = "diag-current.jsonl"
        private const val MAX_STRING_LENGTH = 500
        private const val MAX_LIST_ITEMS = 50
    }

    /**
     * Appends an event asynchronously on the dedicated single-thread executor.
     * Silent no-op when disabled. Swallows all IO failures.
     */
    fun append(category: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val line = buildJsonLine(category, event, fields)
        try {
            executor.execute {
                writeLine(line)
            }
        } catch (_: Throwable) {
            // Log executor failure swallowed
        }
    }

    /**
     * Appends an event synchronously on the caller's thread.
     * Used by [DiagnosticsCrashHandler]. Swallows all IO failures.
     */
    fun appendSync(category: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val line = buildJsonLine(category, event, fields)
        writeLine(line)
    }

    private fun buildJsonLine(category: String, event: String, fields: Map<String, Any?>): String {
        val now = wallClock()
        val ts = try {
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (_: Throwable) {
            Instant.ofEpochMilli(now).toString()
        }

        val lineMap = LinkedHashMap<String, Any?>()
        lineMap["ts"] = ts
        lineMap["up"] = uptimeClock()
        lineMap["cat"] = category
        lineMap["ev"] = event

        for ((key, value) in fields) {
            lineMap[key] = sanitizeValue(value)
        }

        return gson.toJson(lineMap) + "\n"
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null -> null
        is Number, is Boolean -> value
        is String -> if (value.length > MAX_STRING_LENGTH) value.take(MAX_STRING_LENGTH) else value
        is List<*> -> value.take(MAX_LIST_ITEMS).map { sanitizeValue(it) }
        is Array<*> -> value.take(MAX_LIST_ITEMS).map { sanitizeValue(it) }
        is Map<*, *> -> value.entries.take(MAX_LIST_ITEMS).associate { (k, v) ->
            k.toString().take(100) to sanitizeValue(v)
        }
        else -> {
            val str = value.toString()
            if (str.length > MAX_STRING_LENGTH) str.take(MAX_STRING_LENGTH) else str
        }
    }

    private fun writeLine(line: String) {
        try {
            synchronized(writeLock) {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val currentFile = File(dir, CURRENT_FILE_NAME)
                val lineBytes = line.toByteArray(Charsets.UTF_8)

                if (currentFile.exists() && currentFile.length() + lineBytes.size > maxFileBytes) {
                    val rotatedName = "diag-${wallClock()}.jsonl"
                    var rotatedFile = File(dir, rotatedName)
                    if (rotatedFile.exists()) {
                        rotatedFile = File(dir, "diag-${wallClock()}_${System.nanoTime()}.jsonl")
                    }
                    currentFile.renameTo(rotatedFile)
                }

                FileOutputStream(currentFile, true).use { fos ->
                    fos.write(lineBytes)
                    fos.flush()
                }
                pruneOldFilesLocked()
            }
        } catch (_: Throwable) {
            // Swallow all IO errors; log must never crash the app
        }
    }

    /** Runs right after rotation, before the new current file exists: leave room for it. */
    private fun pruneOldFilesLocked() {
        try {
            val rotated = listLogFilesLocked().filter { it.name != CURRENT_FILE_NAME }
            val excess = rotated.size - (maxFiles - 1).coerceAtLeast(0)
            for (i in 0 until excess.coerceAtMost(rotated.size)) {
                rotated[i].delete()
            }
        } catch (_: Throwable) {
            // Swallowed
        }
    }

    private fun listLogFilesLocked(): List<File> {
        val files = dir.listFiles { _, name ->
            name.startsWith("diag-") && name.endsWith(".jsonl")
        } ?: emptyArray()

        val rotated = files.filter { it.name != CURRENT_FILE_NAME }.sortedBy { it.name }
        val current = files.filter { it.name == CURRENT_FILE_NAME }
        return rotated + current
    }

    /** Returns log files ordered oldest first, with current file last. */
    fun files(): List<File> {
        return try {
            synchronized(writeLock) {
                listLogFilesLocked()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Total bytes across all active diagnostics log files. */
    fun totalBytes(): Long {
        return try {
            files().sumOf { it.length() }
        } catch (_: Throwable) {
            0L
        }
    }

    /** Oldest timestamp in milliseconds from the first entry of the oldest file, or file mtime. */
    fun oldestTimestampMillis(): Long? {
        val all = files()
        if (all.isEmpty()) return null
        val oldest = all.first()

        try {
            if (oldest.exists()) {
                val firstLine = oldest.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                if (!firstLine.isNullOrBlank()) {
                    val parsed = gson.fromJson(firstLine, Map::class.java)
                    val ts = parsed["ts"] as? String
                    if (!ts.isNullOrBlank()) {
                        return OffsetDateTime.parse(ts).toInstant().toEpochMilli()
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall back to file modified time
        }

        return try {
            oldest.lastModified().takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    /** Deletes all diagnostics files. */
    fun clear() {
        try {
            synchronized(writeLock) {
                files().forEach { it.delete() }
            }
        } catch (_: Throwable) {
            // Swallowed
        }
    }

    /** Waits for pending asynchronous writes to finish. */
    fun flush(timeoutMs: Long = 2_000) {
        try {
            val latch = CountDownLatch(1)
            executor.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            // Swallowed
        }
    }

    /**
     * Flushes and writes an export file combining header lines prefixed by '# '
     * followed by all log lines oldest to newest.
     */
    fun export(target: File, header: List<String>): File {
        flush()
        try {
            synchronized(writeLock) {
                target.parentFile?.mkdirs()
                target.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (h in header) {
                        writer.write("# $h\n")
                    }
                    for (file in listLogFilesLocked()) {
                        if (file.exists()) {
                            file.forEachLine(Charsets.UTF_8) { line ->
                                if (line.isNotBlank()) {
                                    writer.write(line)
                                    writer.write("\n")
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Swallowed
        }
        return target
    }

    /**
     * Flushes and exports the log content directly as a String.
     */
    fun exportToString(header: List<String> = emptyList()): String {
        flush()
        val sb = StringBuilder()
        try {
            synchronized(writeLock) {
                for (h in header) {
                    sb.append("# ").append(h).append("\n")
                }
                for (file in listLogFilesLocked()) {
                    if (file.exists()) {
                        file.forEachLine(Charsets.UTF_8) { line ->
                            if (line.isNotBlank()) {
                                sb.append(line).append("\n")
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Swallowed
        }
        return sb.toString()
    }

    fun export(header: List<String> = emptyList()): String = exportToString(header)
}

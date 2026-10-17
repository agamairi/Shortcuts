package com.shortcuts.app.diagnostics

import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

class DiagnosticsLogTest {

    private lateinit var tempDir: File
    private val gson = Gson()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("diagnostics-log-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `line format contains ts, up, cat, ev and fields as valid JSON per line`() {
        val log = DiagnosticsLog(tempDir)
        log.appendSync(
            category = "test_cat",
            event = "test_event",
            fields = mapOf(
                "action" to "click",
                "count" to 5,
                "flag" to true
            )
        )

        val currentFile = File(tempDir, DiagnosticsLog.CURRENT_FILE_NAME)
        assertTrue(currentFile.exists())
        val lines = currentFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(1, lines.size)

        @Suppress("UNCHECKED_CAST")
        val parsed = gson.fromJson(lines[0], Map::class.java) as Map<String, Any?>
        assertNotNull(parsed["ts"])
        assertTrue((parsed["ts"] as String).isNotBlank())
        assertNotNull(parsed["up"])
        assertEquals("test_cat", parsed["cat"])
        assertEquals("test_event", parsed["ev"])
        assertEquals("click", parsed["action"])
        assertEquals(5.0, (parsed["count"] as Number).toDouble(), 0.001)
        assertEquals(true, parsed["flag"])
    }

    @Test
    fun `disabled logger writes no file`() {
        val log = DiagnosticsLog(tempDir).apply { enabled = false }
        log.appendSync("cat", "event", mapOf("k" to "v"))
        log.append("cat", "event", mapOf("k" to "v"))
        log.flush()

        val files = tempDir.listFiles() ?: emptyArray()
        assertEquals(0, files.size)
        assertEquals(0L, log.totalBytes())
        assertTrue(log.files().isEmpty())
    }

    @Test
    fun `rotation keeps at most maxFiles files and drops the oldest`() {
        val clock = AtomicLong(1_000_000L)
        val maxFiles = 3
        // Low maxFileBytes so each line forces a rotation on the next write
        val log = DiagnosticsLog(
            dir = tempDir,
            wallClock = { clock.addAndGet(10_000L) },
            uptimeClock = { 100L },
            maxFileBytes = 120L,
            maxFiles = maxFiles
        )

        // Write 6 events synchronously, each exceeding maxFileBytes
        for (i in 1..6) {
            log.appendSync("cat", "ev_$i", mapOf("payload" to "x".repeat(100)))
        }

        val files = log.files()
        assertTrue("File count must be <= $maxFiles but was ${files.size}", files.size <= maxFiles)
        assertEquals(maxFiles, files.size)

        // Check that the current file is the last one
        assertEquals(DiagnosticsLog.CURRENT_FILE_NAME, files.last().name)

        // Oldest events (ev_1, ev_2, ev_3) should have been pruned; only the latest remain
        val allContent = files.joinToString("\n") { it.readText() }
        assertFalse("ev_1 should have been pruned", allContent.contains("ev_1"))
        assertFalse("ev_2 should have been pruned", allContent.contains("ev_2"))
        assertTrue("ev_5 should exist", allContent.contains("ev_5"))
        assertTrue("ev_6 should exist", allContent.contains("ev_6"))
    }

    @Test
    fun `export writes header lines then log lines oldest to newest`() {
        val clock = AtomicLong(1_000_000L)
        val log = DiagnosticsLog(
            dir = tempDir,
            wallClock = { clock.addAndGet(10_000L) },
            uptimeClock = { 200L },
            maxFileBytes = 100L,
            maxFiles = 5
        )

        log.appendSync("cat", "first_event", mapOf("seq" to 1))
        // Force rotation by writing an event that exceeds maxFileBytes
        log.appendSync("cat", "second_event", mapOf("seq" to 2, "padding" to "p".repeat(120)))
        log.appendSync("cat", "third_event", mapOf("seq" to 3))

        val exportTarget = File(tempDir, "export.txt")
        val exportedFile = log.export(exportTarget, listOf("Header line 1", "Header line 2"))

        assertTrue(exportedFile.exists())
        val lines = exportedFile.readLines().filter { it.isNotBlank() }

        assertEquals("# Header line 1", lines[0])
        assertEquals("# Header line 2", lines[1])

        val contentLines = lines.subList(2, lines.size)
        assertEquals(3, contentLines.size)
        assertTrue(contentLines[0].contains("first_event"))
        assertTrue(contentLines[1].contains("second_event"))
        assertTrue(contentLines[2].contains("third_event"))
    }

    @Test
    fun `clear removes everything`() {
        val log = DiagnosticsLog(tempDir)
        log.appendSync("cat", "ev1", mapOf("a" to 1))
        log.appendSync("cat", "ev2", mapOf("b" to 2))

        assertTrue(log.files().isNotEmpty())
        assertTrue(log.totalBytes() > 0L)

        log.clear()

        assertTrue(log.files().isEmpty())
        assertEquals(0L, log.totalBytes())
        assertEquals(null, log.oldestTimestampMillis())
    }

    @Test
    fun `long strings and long lists are truncated`() {
        val log = DiagnosticsLog(tempDir)
        val longString = "A".repeat(800)
        val longList = (1..80).toList()

        log.appendSync(
            "cat",
            "ev",
            mapOf(
                "str" to longString,
                "list" to longList
            )
        )

        val currentFile = File(tempDir, DiagnosticsLog.CURRENT_FILE_NAME)
        val line = currentFile.readLines().first()

        @Suppress("UNCHECKED_CAST")
        val parsed = gson.fromJson(line, Map::class.java) as Map<String, Any?>
        val truncatedStr = parsed["str"] as String
        val truncatedList = parsed["list"] as List<*>

        assertEquals(500, truncatedStr.length)
        assertEquals(50, truncatedList.size)
    }

    @Test
    fun `unwritable dir never throws`() {
        val unwritableFileAsDir = File(tempDir, "not_a_dir.txt")
        unwritableFileAsDir.writeText("blocking file")

        // DiagnosticsLog pointing to a file instead of a directory
        val log = DiagnosticsLog(unwritableFileAsDir)

        // Must not throw for any operation
        log.append("cat", "ev", mapOf("k" to "v"))
        log.appendSync("cat", "ev_sync", mapOf("k" to "v"))
        log.flush()
        log.files()
        log.totalBytes()
        log.oldestTimestampMillis()
        log.clear()
        val exportFile = File(tempDir, "export_fail.txt")
        log.export(exportFile, listOf("header"))
    }
}

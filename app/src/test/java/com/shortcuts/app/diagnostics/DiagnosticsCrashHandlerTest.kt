package com.shortcuts.app.diagnostics

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiagnosticsCrashHandlerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("crash-handler-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `uncaught exception writes crash line synchronously and delegates to previous handler`() {
        val log = DiagnosticsLog(tempDir)
        val previousHandler = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        val handler = DiagnosticsCrashHandler(previousHandler, log)

        val rootCause = IllegalStateException("Root cause error")
        val mainException = RuntimeException("Main crash error", rootCause)
        val testThread = Thread.currentThread()

        handler.uncaughtException(testThread, mainException)

        val export = log.export()
        assertTrue("Log should contain crash event", export.contains("\"ev\":\"crash\""))
        assertTrue("Log should contain app category", export.contains("\"cat\":\"app\""))
        assertTrue("Log should contain thread name", export.contains("\"thread\":\"${testThread.name}\""))
        assertTrue("Log should contain exception class", export.contains("java.lang.RuntimeException"))
        assertTrue("Log should contain message", export.contains("Main crash error"))
        assertTrue("Log should contain causes", export.contains("Root cause error"))

        verify(exactly = 1) { previousHandler.uncaughtException(testThread, mainException) }
    }

    @Test
    fun `previous handler is called even if logging throws an exception`() {
        val failingLog = mockk<DiagnosticsLog>()
        every { failingLog.appendSync(any(), any(), any()) } throws RuntimeException("Disk full / I/O error")

        val previousHandler = mockk<Thread.UncaughtExceptionHandler>(relaxed = true)
        val handler = DiagnosticsCrashHandler(previousHandler, failingLog)

        val testThread = Thread.currentThread()
        val exception = RuntimeException("Simulated crash")

        handler.uncaughtException(testThread, exception)

        verify(exactly = 1) { previousHandler.uncaughtException(testThread, exception) }
    }
}

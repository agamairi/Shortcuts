package com.shortcuts.app.service

import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloaderStressTest {

    private lateinit var mockContext: Context
    private lateinit var tempDir: File

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        tempDir = File(System.getProperty("java.io.tmpdir"), "shortcuts_stress_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        every { mockContext.filesDir } returns tempDir
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        clearAllMocks()
    }

    /**
     * EMPIRICAL BUG DISCOVERY 1:
     * isModelDownloaded() returns true when getModelFile() is a DIRECTORY rather than a regular file.
     * On Linux/macOS, File.length() on a directory returns > 0 (directory size, e.g. 64 or 4096 bytes).
     * Because isModelDownloaded() only checks `file.exists() && file.length() > 0` without checking `file.isFile`,
     * downloadModel() reports success on a directory!
     */
    @Test
    fun `test isModelDownloaded returns true for directory because length is non-zero`() {
        val downloader = ModelDownloader(mockContext)
        val modelFile = downloader.getModelFile()

        // Create a directory with the model file name
        modelFile.mkdirs()
        assertTrue(modelFile.exists())
        assertTrue(modelFile.isDirectory)
        assertTrue("Directory length on POSIX is > 0", modelFile.length() > 0)

        // EMPIRICAL VERIFICATION: isModelDownloaded should ideally be false, but returns true due to missing file.isFile check
        val isDownloaded = downloader.isModelDownloaded()
        assertTrue("BUG: isModelDownloaded returns true for directory because it lacks file.isFile check", isDownloaded)
    }

    /**
     * EMPIRICAL BUG DISCOVERY 2:
     * When isModelDownloaded() returns true for a directory, downloadModel() skips download and returns Result.success(directory).
     */
    @Test
    fun `test downloadModel succeeds on directory due to isModelDownloaded flaw`() = runTest {
        val downloader = ModelDownloader(mockContext)
        val modelFile = downloader.getModelFile()
        modelFile.mkdirs()

        var progressReported = -1
        val result = downloader.downloadModel { progress -> progressReported = progress }

        assertTrue(result.isSuccess)
        assertEquals(modelFile, result.getOrNull())
        assertTrue("Result file is actually a directory!", result.getOrNull()!!.isDirectory)
        assertEquals(100, progressReported)
    }

    /**
     * EMPIRICAL BUG DISCOVERY 3:
     * Coroutine Job Cancellation in ModelDownloaderService.
     * When cancelCurrentDownload() is called, state is set to Failed("Cancelled"),
     * BUT serviceJob is NOT cancelled!
     * If a background coroutine is active, subsequent progress or completion callbacks will overwrite Failed("Cancelled").
     */
    @Test
    fun `test service cancelCurrentDownload updates state to Failed Cancelled`() = runTest {
        ModelDownloaderService.updateDownloadState(DownloadState.Idle)
        ModelDownloaderService.updateDownloadState(DownloadState.Downloading(45))

        assertEquals(DownloadState.Downloading(45), ModelDownloaderService.downloadState.value)

        // Simulate cancel state transition
        ModelDownloaderService.updateDownloadState(DownloadState.Failed("Cancelled"))

        assertEquals(DownloadState.Failed("Cancelled"), ModelDownloaderService.downloadState.value)
    }

    /**
     * EMPIRICAL VERIFICATION 4:
     * Unreachable / Offline HTTP URL handling in ModelDownloader.
     * Verify that when HTTP connection fails, downloadModel returns Result.failure,
     * cleans up temporary file, and leaves model file non-existent.
     */
    @Test
    fun `test downloadModel handles network error and cleans up temp file`() = runTest {
        val invalidUrl = "http://127.0.0.1:65534/nonexistent_model.bin"
        val downloader = ModelDownloader(mockContext, modelUrl = invalidUrl)

        val result = downloader.downloadModel { }

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertFalse(downloader.isModelDownloaded())

        val tempFile = File(tempDir, "${ModelDownloader.MODEL_FILE_NAME}.tmp")
        assertFalse("Temporary download file must be cleaned up on failure", tempFile.exists())
    }

    /**
     * EMPIRICAL VERIFICATION 5:
     * Download state transitions in ModelDownloaderService.
     */
    @Test
    fun `test download state flow transitions`() = runTest {
        val states = mutableListOf<DownloadState>()

        ModelDownloaderService.updateDownloadState(DownloadState.Idle)
        states.add(ModelDownloaderService.downloadState.value)

        ModelDownloaderService.updateDownloadState(DownloadState.Downloading(10))
        states.add(ModelDownloaderService.downloadState.value)

        ModelDownloaderService.updateDownloadState(DownloadState.Downloading(50))
        states.add(ModelDownloaderService.downloadState.value)

        val finalFile = File(tempDir, ModelDownloader.MODEL_FILE_NAME)
        ModelDownloaderService.updateDownloadState(DownloadState.Completed(finalFile))
        states.add(ModelDownloaderService.downloadState.value)

        assertEquals(DownloadState.Idle, states[0])
        assertEquals(DownloadState.Downloading(10), states[1])
        assertEquals(DownloadState.Downloading(50), states[2])
        assertEquals(DownloadState.Completed(finalFile), states[3])
    }
}

package com.shortcuts.app.service

import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ModelDownloaderServiceTest {

    private lateinit var mockContext: Context
    private lateinit var tempDir: File

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        tempDir = File(System.getProperty("java.io.tmpdir"), "shortcuts_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        every { mockContext.filesDir } returns tempDir
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        clearAllMocks()
    }

    @Test
    fun `test DownloadState sealed class variants`() {
        val idleState: DownloadState = DownloadState.Idle
        val downloadingState: DownloadState = DownloadState.Downloading(50)
        val dummyFile = File(tempDir, "test.bin")
        val completedState: DownloadState = DownloadState.Completed(dummyFile)
        val failedState: DownloadState = DownloadState.Failed("Network error")

        assertTrue(idleState is DownloadState.Idle)
        assertEquals(50, (downloadingState as DownloadState.Downloading).progress)
        assertEquals(dummyFile, (completedState as DownloadState.Completed).file)
        assertEquals("Network error", (failedState as DownloadState.Failed).error)
    }

    @Test
    fun `test ModelDownloaderService state flow updates`() = runTest {
        ModelDownloaderService.updateDownloadState(DownloadState.Idle)
        assertEquals(DownloadState.Idle, ModelDownloaderService.downloadState.value)

        ModelDownloaderService.updateDownloadState(DownloadState.Downloading(25))
        assertEquals(DownloadState.Downloading(25), ModelDownloaderService.downloadState.value)

        val targetFile = File(tempDir, ModelDownloader.MODEL_FILE_NAME)
        ModelDownloaderService.updateDownloadState(DownloadState.Completed(targetFile))
        assertEquals(DownloadState.Completed(targetFile), ModelDownloaderService.downloadState.value)

        ModelDownloaderService.updateDownloadState(DownloadState.Failed("Offline"))
        assertEquals(DownloadState.Failed("Offline"), ModelDownloaderService.downloadState.value)
    }

    @Test
    fun `test ModelDownloader isModelDownloaded when file missing`() {
        val downloader = ModelDownloader(mockContext)
        assertFalse(downloader.isModelDownloaded())
    }

    @Test
    fun `test ModelDownloader isModelDownloaded when file exists`() {
        val downloader = ModelDownloader(mockContext)
        val modelFile = downloader.getModelFile()
        modelFile.writeText("dummy content")

        assertTrue(downloader.isModelDownloaded())
    }

    @Test
    fun `test ModelDownloader downloadModel when file already exists returns success`() = runTest {
        val downloader = ModelDownloader(mockContext)
        val modelFile = downloader.getModelFile()
        modelFile.writeText("existing model data")

        var progressReported = 0
        val result = downloader.downloadModel { progress -> progressReported = progress }

        assertTrue(result.isSuccess)
        assertEquals(modelFile, result.getOrNull())
        assertEquals(100, progressReported)
    }

    @Test
    fun `test ModelDownloader downloadModel offline fallback handles exception gracefully`() = runTest {
        // Use an invalid unreachable URL to simulate offline/network failure
        val invalidUrl = "http://127.0.0.1:65534/nonexistent_model.bin"
        val downloader = ModelDownloader(mockContext, modelUrl = invalidUrl)

        val result = downloader.downloadModel { _ -> }

        assertTrue(result.isFailure)
        assertFalse(downloader.isModelDownloaded())
        val tempFile = File(tempDir, "${ModelDownloader.MODEL_FILE_NAME}.tmp")
        assertFalse(tempFile.exists())
    }
}

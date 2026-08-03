package com.shortcuts.app.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(
    private val context: Context,
    private val modelUrl: String = MODEL_URL
) {
    companion object {
        const val MODEL_URL = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm"
        const val MODEL_FILE_NAME = "functiongemma.litertlm"
    }

    fun getModelFile(): File = File(context.filesDir, MODEL_FILE_NAME)

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 0
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val modelFile = getModelFile()
        if (isModelDownloaded()) {
            onProgress(100)
            return@withContext Result.success(modelFile)
        }

        val tempFile = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
        try {
            var currentUrl = modelUrl
            var connection: HttpURLConnection? = null
            var redirected = true
            var redirectCount = 0

            while (redirected && redirectCount < 5) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl.isNullOrEmpty()) {
                        throw IllegalStateException("HTTP redirect missing Location header")
                    }
                    currentUrl = newUrl
                    redirectCount++
                } else {
                    redirected = false
                }
            }

            val conn = connection ?: throw IllegalStateException("Failed to open connection")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("HTTP error code: ${conn.responseCode}")
            }

            val fileLength = conn.contentLengthLong
            val input: InputStream = conn.inputStream
            val output = FileOutputStream(tempFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int
            var lastProgress = -1

            while (input.read(data).also { count = it } != -1) {
                total += count
                output.write(data, 0, count)
                if (fileLength > 0) {
                    val progress = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
            }

            output.flush()
            output.close()
            input.close()
            conn.disconnect()

            if (tempFile.exists() && tempFile.length() > 0) {
                if (tempFile.renameTo(modelFile)) {
                    onProgress(100)
                    Result.success(modelFile)
                } else {
                    tempFile.delete()
                    Result.failure(IllegalStateException("Failed to rename temporary file"))
                }
            } else {
                tempFile.delete()
                Result.failure(IllegalStateException("Downloaded file is empty"))
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            Result.failure(e)
        }
    }
}

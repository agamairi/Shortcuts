package com.shortcuts.app.service

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

class ModelDownloader(
    private val context: Context,
    private val modelUrl: String = MODEL_URL
) {
    companion object {
        const val MODEL_URL = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm"
        const val MODEL_FILE_NAME = "functiongemma.litertlm"
        // Published Git LFS SHA-256 hash for functiongemma-mobile-actions_q8_ekv1024.litertlm / mobile-actions_q8_ekv1024.litertlm
        const val MODEL_SHA256 = "92109695f911d1872fa8ae07c1e3ff0ed70f2c3d1690d410ec6db8587c2ab409"
        const val REQUIRED_SPACE_BYTES = 285_000_000L
    }

    @Volatile
    private var isCancelled = false
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun cancel() {
        isCancelled = true
        activeConnection?.disconnect()
    }

    fun getModelFile(): File = File(context.filesDir, MODEL_FILE_NAME)

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        if (file.exists() && file.isFile && file.length() > 0) {
            try {
                val hash = computeSha256(file)
                return hash.equals(MODEL_SHA256, ignoreCase = true)
            } catch (e: Exception) {
                return false
            }
        }
        return false
    }

    private fun checkHasEnoughSpace(): Boolean {
        val statFs = StatFs(context.filesDir.absolutePath)
        val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        return availableBytes >= REQUIRED_SPACE_BYTES
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val modelFile = getModelFile()
        isCancelled = false

        if (modelFile.exists()) {
            if (modelFile.isFile && modelFile.length() > 0) {
                try {
                    val hash = computeSha256(modelFile)
                    if (hash.equals(MODEL_SHA256, ignoreCase = true)) {
                        onProgress(100)
                        return@withContext Result.success(modelFile)
                    } else {
                        modelFile.delete()
                    }
                } catch (e: Exception) {
                    modelFile.delete()
                }
            } else {
                modelFile.deleteRecursively()
            }
        }

        if (!checkHasEnoughSpace()) {
            return@withContext Result.failure(IllegalStateException("Not enough free space. Need at least 285 MB."))
        }

        val tempFile = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
        try {
            var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

            var currentUrl = modelUrl
            var connection: HttpURLConnection? = null
            var redirected = true
            var redirectCount = 0

            while (redirected && redirectCount < 5) {
                if (isCancelled) throw CancellationException("Download cancelled")
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 15000
                    if (downloadedBytes > 0) {
                        setRequestProperty("Range", "bytes=$downloadedBytes-")
                    }
                }
                activeConnection = connection
                
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
            activeConnection = conn

            val status = conn.responseCode
            val append = if (status == HttpURLConnection.HTTP_PARTIAL) {
                true
            } else if (status == HttpURLConnection.HTTP_OK) {
                downloadedBytes = 0
                false
            } else {
                conn.disconnect()
                throw IllegalStateException("HTTP error code: ${conn.responseCode}")
            }

            val fileLength = if (status == HttpURLConnection.HTTP_PARTIAL) {
                downloadedBytes + conn.contentLengthLong
            } else {
                conn.contentLengthLong
            }

            conn.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val data = ByteArray(8192)
                    var total: Long = downloadedBytes
                    var count: Int
                    var lastProgress = -1

                    while (true) {
                        if (isCancelled) throw CancellationException("Download cancelled")
                        count = try {
                            input.read(data)
                        } catch (e: Exception) {
                            if (isCancelled) throw CancellationException("Download cancelled")
                            throw e
                        }
                        if (count == -1) break
                        
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
                }
            }
            conn.disconnect()
            activeConnection = null

            if (tempFile.exists() && tempFile.length() > 0) {
                val computedHash = computeSha256(tempFile)
                if (!computedHash.equals(MODEL_SHA256, ignoreCase = true)) {
                    tempFile.delete()
                    Result.failure(
                        IllegalStateException("Model integrity check failed: SHA-256 hash mismatch. Expected: $MODEL_SHA256, got: $computedHash")
                    )
                } else if (tempFile.renameTo(modelFile)) {
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
            activeConnection?.disconnect()
            activeConnection = null
            if (e !is CancellationException && tempFile.exists() && tempFile.length() == 0L) {
                tempFile.delete()
            }
            Result.failure(e)
        }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

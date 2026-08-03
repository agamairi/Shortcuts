package com.shortcuts.app.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloader(private val context: Context) {

    // Using the same functiongemma quant model from the expense tracker project.
    private val modelUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm"

    suspend fun downloadModel(onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val modelFile = File(context.filesDir, "functiongemma.litertlm")
        if (modelFile.exists() && modelFile.length() > 0) {
            return@withContext modelFile // Already downloaded
        }

        try {
            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()

            val fileLength = connection.contentLength
            val input = connection.inputStream
            val output = FileOutputStream(modelFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    onProgress((total * 100 / fileLength).toInt())
                }
                output.write(data, 0, count)
            }
            output.flush()
            output.close()
            input.close()
            
            return@withContext modelFile
        } catch (e: Exception) {
            e.printStackTrace()
            if (modelFile.exists()) {
                modelFile.delete()
            }
            return@withContext null
        }
    }
}

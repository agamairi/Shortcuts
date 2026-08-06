package com.shortcuts.app.service

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OnDeviceInferenceService(private val context: Context) {
    private var llmInference: LlmInference? = null
    var isInitialized = false
        private set

    suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        
        val modelFile = File(context.filesDir, "functiongemma.litertlm")
        if (!modelFile.exists()) {
            Log.e("InferenceService", "Model file not found. Please download it first.")
            return@withContext false
        }

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Log.d("InferenceService", "Model initialized successfully.")
            return@withContext true
        } catch (e: Exception) {
            Log.e("InferenceService", "Error initializing model", e)
            return@withContext false
        }
    }

    suspend fun generateAutomationJson(prompt: String): String? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            val success = initializeModel()
            if (!success) return@withContext null
        }

        try {
            // FunctionGemma expects a specific prompt format or system prompt depending on the variant.
            // For simplicity, we just pass the user prompt.
            val response = llmInference?.generateResponse(prompt)
            return@withContext response
        } catch (e: Exception) {
            Log.e("InferenceService", "Error during inference", e)
            return@withContext null
        }
    }

    suspend fun generateWidgetSpecJson(prompt: String): String? = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            val success = initializeModel()
            if (!success) return@withContext null
        }

        try {
            val fullPrompt = "Describe a shortcut widget design. Output ONLY strict JSON with format {\"label\": \"...\", \"color\": \"blue|purple|green|orange|red|teal\", \"icon\": \"wifi|bluetooth|home|bolt|star|bell\", \"automation_name\": \"...\"} for prompt: $prompt"
            val response = llmInference?.generateResponse(fullPrompt)
            return@withContext response
        } catch (e: Exception) {
            Log.e("InferenceService", "Error during widget spec inference", e)
            return@withContext null
        }
    }
    
    fun close() {
        llmInference?.close()
        llmInference = null
        isInitialized = false
    }
}

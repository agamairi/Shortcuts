package com.shortcuts.app.service

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.shortcuts.app.planner.AppMatch
import com.shortcuts.app.planner.FunctionCallingPromptBuilder
import com.shortcuts.app.planner.GroundingContext
import com.shortcuts.app.planner.PackageManagerInstalledAppSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps MediaPipe's on-device LLM for function-call generation.
 *
 * ## Session-per-generation design
 *
 * MediaPipe's LlmInference engine is heavyweight (~1 GB model load) and is created ONCE
 * via [initializeModel].  It is intentionally NOT recreated before each generation.
 *
 * For each generation we open a fresh [LlmInferenceSession] (createFromOptions → addQueryChunk
 * → generateResponse → close).  This is necessary because the underlying JNI layer
 * (LlmTaskRunner.nativeRemoveCallback) performs a DeleteGlobalRef on a callback reference
 * that is already freed after the first generateResponse() returns, causing a SIGABRT
 * ("jobject is an invalid global reference") if the same session is reused for a second call.
 * Confirmed on Pixel 6a with com.google.mediapipe:tasks-genai:0.10.33.
 *
 * A new session is cheap (metadata-only, no model re-read) and solves the crash without
 * the previous workaround of closing and reloading the entire engine (~1 GB) on every call.
 *
 * Thread-safety: [engineMutex] serialises both engine init and all generation calls so
 * concurrent coroutine callers cannot race or double-init.
 */
class OnDeviceInferenceService(
    private val context: Context,
    private val groundingContext: GroundingContext = GroundingContext(
        PackageManagerInstalledAppSource(context.packageManager)
    )
) {
    private var llmEngine: LlmInference? = null
    private val engineMutex = Mutex()

    var isInitialized = false
        private set

    /**
     * Initialises the LlmInference engine exactly once.  Subsequent calls are no-ops.
     * Safe to call from multiple coroutines concurrently — the Mutex ensures only one
     * init runs.
     *
     * @return `true` if the engine is ready, `false` on error (model file missing, etc.).
     */
    suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            if (isInitialized) return@withLock true

            val modelFile = File(context.filesDir, "functiongemma.litertlm")
            if (!modelFile.exists()) {
                Log.e("InferenceService", "Model file not found. Please download it first.")
                return@withLock false
            }

            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                llmEngine = LlmInference.createFromOptions(context, options)
                isInitialized = true
                Log.d("InferenceService", "Model engine initialised successfully.")
                true
            } catch (e: Exception) {
                Log.e("InferenceService", "Error initialising model engine", e)
                false
            }
        }
    }

    /**
     * Runs one generation using a fresh [LlmInferenceSession] opened against the
     * long-lived engine.  The session is closed immediately after the response is
     * obtained (via `use {}` — LlmInferenceSession implements AutoCloseable).
     *
     * The Mutex is held for the duration so only one session is alive at a time,
     * preventing concurrent JNI calls that could corrupt shared native state.
     */
    private suspend fun generate(promptForSession: (LlmInferenceSession) -> String): String? = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val engine = llmEngine ?: return@withLock null
            try {
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTopP(0.95f)
                    .setTemperature(0.8f)
                    .build()
                LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
                    session.addQueryChunk(promptForSession(session))
                    session.generateResponse()
                }
            } catch (e: Exception) {
                Log.e("InferenceService", "Error during inference session", e)
                null
            }
        }
    }

    suspend fun generateAutomationJson(prompt: String): String? {
        if (!isInitialized && !initializeModel()) return null
        return generate { session -> buildFunctionCallingPrompt(prompt, session) }
    }

    /** Resolves model output against the launchable apps visible on this device. */
    fun resolveApp(query: String): AppMatch? = groundingContext.resolveApp(query)

    /** Gives Tier-1 clause alignment the user-visible label for a resolved app package. */
    fun appLabelForPackage(packageName: String): String? = groundingContext.appLabelForPackage(packageName)

    /**
     * FunctionGemma only activates its function-calling behavior when given a "developer"-role
     * turn declaring the callable functions before the user request; passing the bare user prompt
     * (the previous behavior here) makes it fall back to generic chat and ramble. The model's
     * on-device generateResponse() takes a flat string with no separate "tools" parameter, so the
     * function declarations are inlined as plain text instructions here. Function names/params map
     * 1:1 onto this app's own ActionType schema (see Automation.kt) via
     * AiBuilderViewModel.parseFunctionCallResponse().
     */
    private fun buildFunctionCallingPrompt(
        userPrompt: String,
        session: LlmInferenceSession
    ): String = FunctionCallingPromptBuilder.build(
        userPrompt = userPrompt,
        availableApps = groundingContext.appsForPrompt(),
        sizeInTokens = session::sizeInTokens
    )

    suspend fun generateWidgetSpecJson(prompt: String): String? {
        if (!isInitialized && !initializeModel()) return null
        val fullPrompt = "Describe a shortcut widget design. Output ONLY strict JSON with format {\"label\": \"...\", \"color\": \"blue|purple|green|orange|red|teal\", \"icon\": \"wifi|bluetooth|home|bolt|star|bell\", \"automation_name\": \"...\"} for prompt: $prompt"
        return generate { fullPrompt }
    }

    /**
     * Releases the LlmInference engine.  Call this when the owning component (e.g. ViewModel)
     * is cleared, not before each generation.
     */
    fun close() {
        llmEngine?.close()
        llmEngine = null
        isInitialized = false
    }

}

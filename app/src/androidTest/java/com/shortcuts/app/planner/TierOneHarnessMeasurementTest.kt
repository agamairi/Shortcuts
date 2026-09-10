package com.shortcuts.app.planner

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shortcuts.app.service.OnDeviceInferenceService
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * MEASUREMENT, not a pass/fail assertion.
 *
 * The two-tier harness assumes FunctionGemma *may* emit several function calls in one
 * generation. That assumption was never validated — Tier 1 is safe either way, but the
 * speedup it promises is hypothetical until measured against the real model.
 *
 * This runs multi-command prompts through the actual on-device model and reports how many
 * function calls each response contained. Skipped automatically when the model file is
 * absent, so it never breaks a normal CI run.
 *
 * Read the result in logcat under the tag "TierOneMeasurement".
 */
@RunWith(AndroidJUnit4::class)
class TierOneHarnessMeasurementTest {

    private val prompts = listOf(
        "turn on wifi and open Chrome",
        "open Chrome then turn on the flashlight",
        "turn on the flashlight and turn on bluetooth",
        "turn on wifi, open Chrome, then turn on the flashlight",
        "mute the phone and open Chrome"
    )

    @Test
    fun measureFunctionCallsPerGeneration() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.filesDir, "functiongemma.litertlm")
        assumeTrue("Model not present; skipping measurement", modelFile.exists())

        val service = OnDeviceInferenceService(context)
        val parser = FunctionCallParser { query -> service.resolveApp(query) }

        runBlocking {
            check(service.initializeModel()) { "Model engine failed to initialise" }
            prompts.forEach { prompt ->
                val expectedClauses = PromptSegmenter.split(prompt).size
                val response = service.generateAutomationJson(prompt)
                val actions = response?.let { parser.parseActions(it) } ?: emptyList()
                val clauses = PromptSegmenter.split(prompt)
                // What the OLD all-or-nothing rule would have salvaged from this response.
                val oldRuleCovered = if (actions.size == clauses.size) clauses.size else 0
                // What deterministic alignment salvages from the SAME response.
                val aligner = ClauseAligner { pkg -> service.appLabelForPackage(pkg) }
                val aligned = aligner.align(clauses, actions).count { it != null }
                android.util.Log.w(
                    "TierOneMeasurement",
                    "  raw actions: " + actions.joinToString { a ->
                        "${a.actionType}(target=${a.target},pkg=${a.packageName})"
                    } + " | clauses=" + clauses.joinToString(" // ")
                )
                android.util.Log.w(
                    "TierOneMeasurement",
                    "clauses=$expectedClauses calls=${actions.size} " +
                        "oldRuleCovered=$oldRuleCovered alignedCovered=$aligned " +
                        "generationsSaved=$aligned | \"$prompt\""
                )
            }
            service.close()
        }
    }
}

package com.shortcuts.app.automation.model

import com.shortcuts.app.data.Action

data class WorkflowV2(
    val schemaVersion: Int = SCHEMA_VERSION,
    val workflowId: String,
    val recordedDevice: RecordedDevice,
    val steps: List<Step>
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

data class RecordedDevice(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val rotation: Int
)

sealed interface Step {
    val id: String
    val title: String?
    val timeoutMs: Long
    val expectedBefore: ScreenFingerprint?
    val expectedAfter: ScreenFingerprint?
    val capturedDelayFromPreviousMs: Long?
    val migrationWarnings: List<String>
}

data class LaunchAppStep(
    override val id: String,
    override val title: String? = null,
    val target: LaunchTarget,
    val policy: LaunchPolicy,
    val expectedWindow: WindowSelector,
    override val timeoutMs: Long = 12_000,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint? = null,
    override val capturedDelayFromPreviousMs: Long? = null,
    override val migrationWarnings: List<String> = emptyList()
) : Step

data class UiStep(
    override val id: String,
    override val title: String? = null,
    val operation: UiOperation,
    val expectedWindow: WindowSelector,
    val element: ElementSelector?,
    val gesture: CapturedGesture?,
    val reveal: RevealPolicy? = null,
    val verification: VerificationSpec,
    val safety: StepSafety = StepSafety.REVERSIBLE,
    override val timeoutMs: Long = 7_000,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint? = null,
    override val capturedDelayFromPreviousMs: Long? = null,
    override val migrationWarnings: List<String> = emptyList()
) : Step

data class WaitStep(
    override val id: String,
    override val title: String? = null,
    val durationMs: Long,
    override val timeoutMs: Long = durationMs,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint? = null,
    override val capturedDelayFromPreviousMs: Long? = null,
    override val migrationWarnings: List<String> = emptyList()
) : Step

data class LegacyActionStep(
    override val id: String,
    override val title: String? = null,
    val action: Action,
    override val migrationWarnings: List<String> = emptyList(),
    override val timeoutMs: Long = 7_000,
    override val expectedBefore: ScreenFingerprint? = null,
    override val expectedAfter: ScreenFingerprint? = null,
    override val capturedDelayFromPreviousMs: Long? = null
) : Step

sealed interface UiOperation {
    data object TAP : UiOperation
    data object LONG_PRESS : UiOperation
    data object SWIPE : UiOperation
    data class SET_TEXT(val text: String) : UiOperation
    data class IME_ACTION(val kind: ImeActionKind) : UiOperation
    data class GLOBAL_ACTION(val kind: String) : UiOperation
}

enum class ImeActionKind { SEARCH, SEND, DONE, NEXT, GO, UNSPECIFIED }

enum class StepSafety { REVERSIBLE, IRREVERSIBLE, BEST_EFFORT }

enum class LaunchPolicy {
    RESET_TO_ROOT,
    RESUME_EXISTING_TASK,
    EXPLICIT_EXPORTED_ACTIVITY,
    DEEP_LINK
}

sealed interface LaunchTarget {
    data class Launcher(val packageName: String) : LaunchTarget
    data class Component(val packageName: String, val className: String) : LaunchTarget
    data class DeepLink(val packageName: String, val uri: String) : LaunchTarget
}

fun interface TextTokenizer {
    fun tokenize(text: String): String
}

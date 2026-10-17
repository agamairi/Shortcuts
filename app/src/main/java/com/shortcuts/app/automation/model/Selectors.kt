package com.shortcuts.app.automation.model

data class ElementSelector(
    val strategies: List<LocatorStrategy>,
    val expectedClass: String?,
    val expectedRole: NodeRole?,
    val ancestorChain: List<AncestorSelector>,
    val siblingIndex: Int?,
    val rowContext: RowContext?,
    val boundsInWindow: NormalizedRect,
    val rawPoint: CapturedPoint?,
    val requiredActions: Set<NodeAction>,
    val scrollContainer: ElementSelector? = null
)

data class LocatorStrategy(
    val rank: Int,
    val kind: LocatorKind,
    val value: String,
    val stability: LocatorStability
)

enum class LocatorKind {
    RESOURCE_ID_EXACT,
    TEXT_EXACT,
    CONTENT_DESCRIPTION_EXACT,
    TEST_TAG_EXACT,
    WEB_DOM_ID_EXACT
}

enum class LocatorStability { STABLE, LIKELY_STABLE, VOLATILE }

data class AncestorSelector(
    val resourceId: String?,
    val className: String,
    val role: NodeRole?,
    val siblingIndex: Int?
)

data class RowContext(
    val containerClass: String?,
    val containerResourceId: String?,
    val siblingText: List<String>,
    val siblingContentDescriptions: List<String>,
    val collectionRow: Int?,
    val collectionColumn: Int?
)

data class WindowSelector(
    val packageName: String,
    val observedActivityClass: String?,
    val windowType: WindowType,
    val titleToken: String?,
    val boundsOnDisplay: NormalizedRect,
    val displayId: Int,
    val fingerprint: ScreenFingerprint?
)

enum class WindowType {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY_OVERLAY,
    SPLIT_SCREEN_DIVIDER,
    MAGNIFICATION_OVERLAY,
    UNKNOWN
}

enum class NodeRole {
    BUTTON,
    CHECKBOX,
    SWITCH,
    RADIO_BUTTON,
    TEXT_FIELD,
    TEXT,
    IMAGE,
    LIST,
    LIST_ITEM,
    TAB,
    LINK,
    SLIDER,
    MENU,
    DIALOG,
    UNKNOWN
}

enum class NodeAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    FOCUS,
    CLEAR_FOCUS,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    SCROLL_UP,
    SCROLL_DOWN,
    SCROLL_LEFT,
    SCROLL_RIGHT,
    IME_ENTER,
    EXPAND,
    COLLAPSE,
    SELECT,
    SET_PROGRESS
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

data class NormalizedPoint(val x: Float, val y: Float)

data class IntPoint(val x: Int, val y: Int)

enum class CoordinateSpace { WINDOW_NORMALIZED, DISPLAY_LEGACY }

data class CapturedPoint(
    val displayPx: IntPoint,
    val pointInWindow: NormalizedPoint,
    val pointInNode: NormalizedPoint?,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val rotation: Int,
    val densityDpi: Int,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.WINDOW_NORMALIZED
)

data class TimedPoint(
    val displayPx: IntPoint,
    val elapsedMs: Long,
    val pointInWindow: NormalizedPoint? = null
)

data class CapturedGesture(
    val kind: GestureKind,
    val durationMs: Long,
    val points: List<TimedPoint>,
    val pointerCount: Int = 1
)

enum class GestureKind { TAP, LONG_PRESS, SWIPE }

data class ScreenFingerprint(
    val version: Int = 1,
    val packageName: String,
    val activityClass: String?,
    val windowType: WindowType,
    val structuralTokens: Set<Long>,
    val landmarkTokens: Set<Long>,
    val structureHash: Long
)

data class RevealPolicy(
    val direction: RevealDirection,
    val maxAttempts: Int = 8,
    val noProgressLimit: Int = 2
)

enum class RevealDirection { FORWARD, BACKWARD, UP, DOWN, LEFT, RIGHT }

data class VerificationSpec(
    val postconditions: List<VerificationPostcondition>,
    val allowBestEffort: Boolean = false
)

sealed interface VerificationPostcondition {
    data class ExpectedScreen(val fingerprint: ScreenFingerprint) : VerificationPostcondition
    data class ExpectedWindow(val selector: WindowSelector) : VerificationPostcondition
    data class ExpectedActivity(val packageName: String, val activityClass: String) : VerificationPostcondition
    data class TargetPresence(val selector: ElementSelector, val shouldBePresent: Boolean) : VerificationPostcondition
    data class BooleanStateChanged(val state: NodeBooleanState, val expected: Boolean? = null) : VerificationPostcondition
    data class RangeValueChanged(val expectedValue: Float? = null, val tolerance: Float = 0f) : VerificationPostcondition
    data class TextEquals(val value: String) : VerificationPostcondition
    data class FocusMoved(val selector: ElementSelector) : VerificationPostcondition
    data class ImeVisibility(val visible: Boolean) : VerificationPostcondition
    data class NextTargetConfidence(val selector: ElementSelector, val minimum: Int = 75) : VerificationPostcondition
}

enum class NodeBooleanState { CHECKED, SELECTED, EXPANDED }

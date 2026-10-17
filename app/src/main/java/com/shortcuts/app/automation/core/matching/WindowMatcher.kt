package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.fingerprint.ScreenFingerprinter
import com.shortcuts.app.automation.core.geometry.NormalizedGeometry
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin window matcher implementing Engine v2 spec §2.A.
 *
 * Package is a hard filter. Identity confidence is normalized over the recorded activity (40),
 * window type (20), salted title token (20), and normalized bounds (20). Screen fingerprints are
 * deliberately excluded: replay evaluates them separately as a soft expected-screen hint.
 * Selection always requires confidence >= 0.80 and a >= 0.15 lead over the runner-up.
 */
class WindowMatcher(
    // Kept for source compatibility while callers migrate. Fingerprints must not affect identity.
    @Suppress("UNUSED_PARAMETER") fingerprinter: ScreenFingerprinter = DefaultScreenFingerprinter(),
    private val titleTokenizer: (String) -> String = { it }
) {

    fun match(
        windows: List<WindowSnapshot>,
        selector: WindowSelector,
        currentActivityClass: String? = null,
        displayBounds: IntRect? = null
    ): WindowDecision {
        val eligibleWindows = windows.filter { window ->
            val overlayAllowed = selector.windowType == WindowType.ACCESSIBILITY_OVERLAY
            if (!overlayAllowed && window.type == WindowType.ACCESSIBILITY_OVERLAY) {
                return@filter false
            }

            val imeAllowed = selector.windowType == WindowType.INPUT_METHOD
            if (!imeAllowed && window.type == WindowType.INPUT_METHOD) {
                return@filter false
            }

            window.packageName == selector.packageName
        }

        if (eligibleWindows.isEmpty()) {
            val observedSummary = windows.joinToString(", ") {
                "${it.packageName}(${it.type}, id=${it.windowId})"
            }
            return WindowDecision.NotFound(
                "No eligible windows for package '${selector.packageName}'. Observed: [$observedSummary]"
            )
        }

        val scored = eligibleWindows.map { window ->
            scoreWindow(window, selector, currentActivityClass, displayBounds)
        }.sortedWith(
            compareByDescending<ScoredWindow> { it.confidence }
                .thenByDescending { it.window.layer }
                .thenBy { it.window.windowId }
        )

        val unexpectedModal = findUnexpectedModal(scored, selector, displayBounds)
        if (unexpectedModal != null) {
            return WindowDecision.NotFound(
                "Unexpected same-package dialog or popup (id=${unexpectedModal.window.windowId}, " +
                    "layer=${unexpectedModal.window.layer}) covers the expected activity window; " +
                    "the recorded selector does not identify that dialog."
            )
        }

        val best = scored.first()
        if (best.confidence < MIN_CONFIDENCE) {
            return WindowDecision.NotFound(
                "Best window confidence ${best.confidence} is below threshold $MIN_CONFIDENCE. " +
                    "Breakdown: ${best.breakdown}"
            )
        }

        val second = scored.getOrNull(1)
        if (second != null && best.confidence - second.confidence < MIN_MARGIN) {
            return WindowDecision.Ambiguous(best, second)
        }

        return WindowDecision.Selected(best.window, best.confidence, best.breakdown)
    }

    fun match(
        windowSet: WindowSetSnapshot,
        selector: WindowSelector,
        currentActivityClass: String? = null
    ): WindowDecision = match(
        windows = windowSet.windows,
        selector = selector,
        currentActivityClass = currentActivityClass,
        displayBounds = IntRect(0, 0, windowSet.displayWidth, windowSet.displayHeight)
    )

    internal fun scoreWindow(
        window: WindowSnapshot,
        selector: WindowSelector,
        currentActivityClass: String?,
        displayBounds: IntRect? = null
    ): ScoredWindow {
        var applicableWeight = 0.0
        var totalMatchedWeight = 0.0

        val activityApplicable = selector.observedActivityClass?.let(::looksLikeActivity) == true
        val activityMatched = activityApplicable &&
            currentActivityClass != null &&
            currentActivityClass == selector.observedActivityClass
        val activityWeight = if (activityMatched) ACTIVITY_WEIGHT else 0.0
        if (activityApplicable) applicableWeight += ACTIVITY_WEIGHT
        totalMatchedWeight += activityWeight

        val windowTypeApplicable = selector.windowType != WindowType.UNKNOWN
        val windowTypeMatched = windowTypeApplicable && window.type == selector.windowType
        val windowTypeWeight = if (windowTypeMatched) WINDOW_TYPE_WEIGHT else 0.0
        if (windowTypeApplicable) applicableWeight += WINDOW_TYPE_WEIGHT
        totalMatchedWeight += windowTypeWeight

        val titleApplicable = selector.titleToken != null
        val observedTitleToken = window.title?.let(titleTokenizer)
        val titleTokenMatched = titleApplicable && observedTitleToken == selector.titleToken
        val titleTokenWeight = if (titleTokenMatched) TITLE_WEIGHT else 0.0
        if (titleApplicable) applicableWeight += TITLE_WEIGHT
        totalMatchedWeight += titleTokenWeight

        val boundsApplicable = displayBounds != null && displayBounds.width > 0 && displayBounds.height > 0
        val boundsMatched = boundsApplicable && boundsMatch(
            NormalizedGeometry.normalize(window.boundsInScreen, requireNotNull(displayBounds)),
            selector.boundsOnDisplay
        )
        val boundsWeight = if (boundsMatched) BOUNDS_WEIGHT else 0.0
        if (boundsApplicable) applicableWeight += BOUNDS_WEIGHT
        totalMatchedWeight += boundsWeight

        val confidence = if (applicableWeight > 0.0) {
            totalMatchedWeight / applicableWeight
        } else {
            0.0
        }

        val breakdown = WindowScoreBreakdown(
            activityMatched = activityMatched,
            activityWeight = activityWeight,
            windowTypeMatched = windowTypeMatched,
            windowTypeWeight = windowTypeWeight,
            titleTokenMatched = titleTokenMatched,
            titleTokenWeight = titleTokenWeight,
            boundsMatched = boundsMatched,
            boundsWeight = boundsWeight,
            totalMatchedWeight = totalMatchedWeight,
            applicableWeight = applicableWeight,
            confidence = confidence
        )

        return ScoredWindow(window, confidence, breakdown)
    }

    private fun findUnexpectedModal(
        scored: List<ScoredWindow>,
        selector: WindowSelector,
        displayBounds: IntRect?
    ): ScoredWindow? {
        if (displayBounds == null || displayBounds.width <= 0 || displayBounds.height <= 0) return null

        // A smaller recorded window is explicit dialog identity. Its bounds and optional title token
        // must win by ordinary scoring; it receives no special confidence or ambiguity waiver.
        if (isDialog(selector.boundsOnDisplay)) return null

        val activityWindow = scored
            .filterNot { candidate ->
                isDialog(NormalizedGeometry.normalize(candidate.window.boundsInScreen, displayBounds))
            }
            .maxWithOrNull(
                compareBy<ScoredWindow> { it.confidence }
                    .thenBy { it.window.layer }
                    .thenByDescending { it.window.windowId }
            ) ?: return null

        return scored
            .asSequence()
            .filter { it.window.windowId != activityWindow.window.windowId }
            .filter { it.window.layer > activityWindow.window.layer }
            .filter { candidate ->
                val normalized = NormalizedGeometry.normalize(candidate.window.boundsInScreen, displayBounds)
                isDialog(normalized) && rectanglesOverlap(normalized, selector.boundsOnDisplay)
            }
            .maxByOrNull { it.window.layer }
    }

    private fun boundsMatch(a: NormalizedRect, b: NormalizedRect): Boolean {
        val leftDiff = abs(a.left - b.left)
        val topDiff = abs(a.top - b.top)
        val rightDiff = abs(a.right - b.right)
        val bottomDiff = abs(a.bottom - b.bottom)
        if (leftDiff <= BOUNDS_TOLERANCE && topDiff <= BOUNDS_TOLERANCE &&
            rightDiff <= BOUNDS_TOLERANCE && bottomDiff <= BOUNDS_TOLERANCE
        ) {
            return true
        }

        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        val unionArea = (a.width * a.height) + (b.width * b.height) - interArea
        return unionArea > 0f && interArea / unionArea >= MIN_BOUNDS_IOU
    }

    private fun rectanglesOverlap(a: NormalizedRect, b: NormalizedRect): Boolean =
        min(a.right, b.right) > max(a.left, b.left) &&
            min(a.bottom, b.bottom) > max(a.top, b.top)

    private fun isDialog(rect: NormalizedRect): Boolean =
        rect.width < 0.92f || rect.height < 0.92f ||
            rect.left > 0.04f || rect.top > 0.04f || rect.right < 0.96f || rect.bottom < 0.96f

    companion object {
        const val ACTIVITY_WEIGHT = 40.0
        const val WINDOW_TYPE_WEIGHT = 20.0
        const val TITLE_WEIGHT = 20.0
        const val BOUNDS_WEIGHT = 20.0
        const val MIN_CONFIDENCE = 0.80
        const val MIN_MARGIN = 0.15
        const val BOUNDS_TOLERANCE = 0.05f
        const val MIN_BOUNDS_IOU = 0.80f

        val NON_ACTIVITY_TOKENS = listOf(
            "Dialog",
            "PopupWindow",
            "PopupDecorView",
            "Snackbar",
            "Toast",
            "BottomSheet",
            "Menu"
        )

        fun looksLikeActivity(className: String): Boolean {
            if (className.isBlank()) return false
            val simpleName = className.substringAfterLast('.')
            return NON_ACTIVITY_TOKENS.none { simpleName.contains(it) }
        }
    }
}

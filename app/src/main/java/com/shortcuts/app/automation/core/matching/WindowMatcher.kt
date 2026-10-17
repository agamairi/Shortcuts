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
 * Excludes ACCESSIBILITY_OVERLAY and INPUT_METHOD windows unless explicitly expected;
 * hard-rejects non-matching packages; scores activity (30), window type (15), title token (10),
 * normalized bounds (10), and fingerprint similarity (35) over applicable weights only.
 * Requires confidence >= 0.80 and margin >= 0.15 over the runner-up.
 * Uses Z-order (layer) strictly as a tie-breaker.
 */
class WindowMatcher(
    private val fingerprinter: ScreenFingerprinter = DefaultScreenFingerprinter(),
    private val titleTokenizer: (String) -> String = { it }
) {

    fun match(
        windows: List<WindowSnapshot>,
        selector: WindowSelector,
        currentActivityClass: String? = null,
        displayBounds: IntRect? = null
    ): WindowDecision {
        val eligibleWindows = windows.filter { window ->
            // Hard filter 1: Exclude ACCESSIBILITY_OVERLAY unless selector expects it
            val overlayAllowed = selector.windowType == WindowType.ACCESSIBILITY_OVERLAY
            if (!overlayAllowed && window.type == WindowType.ACCESSIBILITY_OVERLAY) {
                return@filter false
            }

            // Hard filter 2: Exclude INPUT_METHOD unless selector expects it
            val imeAllowed = selector.windowType == WindowType.INPUT_METHOD
            if (!imeAllowed && window.type == WindowType.INPUT_METHOD) {
                return@filter false
            }

            // Hard filter 3: Package name equality
            window.packageName == selector.packageName
        }

        if (eligibleWindows.isEmpty()) {
            val observedSummary = windows.joinToString(", ") { "${it.packageName}(${it.type}, id=${it.windowId})" }
            return WindowDecision.NotFound("No eligible windows for package '${selector.packageName}'. Observed: [$observedSummary]")
        }

        // Fix 5(a): If exactly one eligible window exists for the expected package with the expected window type,
        // select it (confidence floor waived; record soleCandidate=true in the breakdown) — the element matcher then decides.
        val matchingTypeWindows = if (selector.windowType != WindowType.UNKNOWN) {
            eligibleWindows.filter { it.type == selector.windowType }
        } else {
            eligibleWindows
        }

        if (matchingTypeWindows.size == 1) {
            val sole = matchingTypeWindows.single()
            val isDismissedDialog = if (displayBounds != null) {
                val candidateNormalized = NormalizedGeometry.normalize(sole.boundsInScreen, displayBounds)
                isDialog(selector.boundsOnDisplay) && !isDialog(candidateNormalized)
            } else {
                false
            }
            if (!isDismissedDialog) {
                val scoredSole = scoreWindow(sole, selector, currentActivityClass, displayBounds)
                val finalBreakdown = scoredSole.breakdown.copy(soleCandidate = true)
                return WindowDecision.Selected(sole, scoredSole.confidence, finalBreakdown)
            }
        }

        val scored = eligibleWindows.map { window ->
            scoreWindow(window, selector, currentActivityClass, displayBounds)
        }

        // Fix 5(c): A dialog window over its activity: the dialog (topmost window of that package
        // containing the recorded point) must win when the step's target lies in it.
        val dialogWinner = findDialogWinner(scored, selector, displayBounds)

        val sortedScored = if (dialogWinner != null) {
            listOf(dialogWinner) + (scored - dialogWinner).sortedWith(
                compareByDescending<ScoredWindow> { it.confidence }
                    .thenByDescending { it.window.layer }
                    .thenBy { it.window.windowId }
            )
        } else {
            scored.sortedWith(
                compareByDescending<ScoredWindow> { it.confidence }
                    .thenByDescending { it.window.layer }
                    .thenBy { it.window.windowId }
            )
        }

        val best = sortedScored.first()
        val second = sortedScored.getOrNull(1)

        if (second != null) {
            val isCoveredByDialog = dialogWinner != null &&
                best.window.windowId == dialogWinner.window.windowId &&
                second.window.layer < best.window.layer &&
                windowContainsPoint(second.window, displayBounds, NormalizedGeometry.center(selector.boundsOnDisplay))

            if (!isCoveredByDialog && (best.confidence - second.confidence) < 0.15) {
                return WindowDecision.Ambiguous(best, second)
            }
        }

        if (best.confidence < 0.80) {
            if (dialogWinner != null && best.window.windowId == dialogWinner.window.windowId) {
                return WindowDecision.Selected(best.window, best.confidence, best.breakdown)
            }
            return WindowDecision.NotFound(
                "Best window confidence ${best.confidence} is below threshold 0.80. Breakdown: ${best.breakdown}"
            )
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

        // 1. Activity class (weight 30) - Fix 5(b): applies only when observedActivityClass looks like a real Activity
        val activityApplicable = selector.observedActivityClass != null && looksLikeActivity(selector.observedActivityClass)
        var activityMatched = false
        var activityWeight = 0.0
        if (activityApplicable) {
            applicableWeight += 30.0
            if (currentActivityClass != null && currentActivityClass == selector.observedActivityClass) {
                activityMatched = true
                activityWeight = 30.0
                totalMatchedWeight += 30.0
            }
        }

        // 2. Window type (weight 15)
        val windowTypeApplicable = selector.windowType != WindowType.UNKNOWN
        var windowTypeMatched = false
        var windowTypeWeight = 0.0
        if (windowTypeApplicable) {
            applicableWeight += 15.0
            if (window.type == selector.windowType) {
                windowTypeMatched = true
                windowTypeWeight = 15.0
                totalMatchedWeight += 15.0
            }
        }

        // 3. Salted title token (weight 10)
        val titleApplicable = selector.titleToken != null
        var titleTokenMatched = false
        var titleTokenWeight = 0.0
        if (titleApplicable) {
            applicableWeight += 10.0
            val observedToken = window.title?.let(titleTokenizer)
            if (observedToken != null && observedToken == selector.titleToken) {
                titleTokenMatched = true
                titleTokenWeight = 10.0
                totalMatchedWeight += 10.0
            }
        }

        // 4. Normalized bounds (weight 10)
        var boundsMatched = false
        var boundsWeight = 0.0
        if (displayBounds != null) {
            applicableWeight += 10.0
            val observedNormalized = NormalizedGeometry.normalize(window.boundsInScreen, displayBounds)
            if (boundsMatch(observedNormalized, selector.boundsOnDisplay)) {
                boundsMatched = true
                boundsWeight = 10.0
                totalMatchedWeight += 10.0
            }
        }

        // 5. Screen fingerprint similarity (weight 35)
        val fpApplicable = selector.fingerprint != null
        var fpSimilarity: Double? = null
        var fpWeight = 0.0
        if (fpApplicable) {
            applicableWeight += 35.0
            val observedFp = fingerprinter.fingerprint(window, currentActivityClass)
            val sim = fingerprinter.similarity(selector.fingerprint!!, observedFp)
            fpSimilarity = sim
            fpWeight = 35.0 * sim
            totalMatchedWeight += fpWeight
        }

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
            fingerprintSimilarity = fpSimilarity,
            fingerprintWeight = fpWeight,
            totalMatchedWeight = totalMatchedWeight,
            applicableWeight = applicableWeight,
            confidence = confidence
        )

        return ScoredWindow(
            window = window,
            confidence = confidence,
            breakdown = breakdown
        )
    }

    private fun boundsMatch(a: NormalizedRect, b: NormalizedRect): Boolean {
        // Coordinate tolerance of 5% (0.05f)
        val leftDiff = abs(a.left - b.left)
        val topDiff = abs(a.top - b.top)
        val rightDiff = abs(a.right - b.right)
        val bottomDiff = abs(a.bottom - b.bottom)
        if (leftDiff <= 0.05f && topDiff <= 0.05f && rightDiff <= 0.05f && bottomDiff <= 0.05f) {
            return true
        }

        // Or IoU >= 0.80
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = (interRight - interLeft).coerceAtLeast(0f)
        val interH = (interBottom - interTop).coerceAtLeast(0f)
        val interArea = interW * interH
        val unionArea = (a.width * a.height) + (b.width * b.height) - interArea
        return if (unionArea > 0f) (interArea / unionArea) >= 0.80f else true
    }

    private fun looksLikeActivity(className: String): Boolean {
        val simpleName = className.substringAfterLast('.').substringAfterLast('$')
        return simpleName.endsWith("Activity")
    }

    private fun findDialogWinner(
        scored: List<ScoredWindow>,
        selector: WindowSelector,
        displayBounds: IntRect?
    ): ScoredWindow? {
        if (displayBounds == null || scored.size < 2) return null
        val targetPoint = NormalizedGeometry.center(selector.boundsOnDisplay)
        val targetX = (targetPoint.x * displayBounds.width + displayBounds.left).toInt()
        val targetY = (targetPoint.y * displayBounds.height + displayBounds.top).toInt()

        val containing = scored.filter { it.window.boundsInScreen.contains(targetX, targetY) }
        if (containing.size < 2) return null

        val topmost = containing.maxByOrNull { it.window.layer } ?: return null
        val topmostNormalized = NormalizedGeometry.normalize(topmost.window.boundsInScreen, displayBounds)

        if (isDialog(topmostNormalized)) {
            return topmost
        }
        return null
    }

    private fun windowContainsPoint(
        window: WindowSnapshot,
        displayBounds: IntRect?,
        point: com.shortcuts.app.automation.model.NormalizedPoint
    ): Boolean {
        if (displayBounds == null) return false
        val pxX = (point.x * displayBounds.width + displayBounds.left).toInt()
        val pxY = (point.y * displayBounds.height + displayBounds.top).toInt()
        return window.boundsInScreen.contains(pxX, pxY)
    }

    private fun isDialog(rect: NormalizedRect): Boolean =
        rect.width < 0.92f || rect.height < 0.92f ||
            rect.left > 0.04f || rect.top > 0.04f || rect.right < 0.96f || rect.bottom < 0.96f
}

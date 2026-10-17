package com.shortcuts.app.automation.android.capture

/** A same-app activity transition that has the shape of a Back stack pop. */
data class BackInference(
    val packageName: String,
    val fromActivityClass: String,
    val toActivityClass: String
)

/**
 * Pure, JVM-testable inference for Back navigation that Android does not expose as a key event.
 *
 * Activity changes alone are not sufficient evidence: an app can finish a screen after Save or
 * navigate programmatically.  We therefore only infer a Back for a quiet, same-package pop to
 * the immediately preceding confirmed activity, and never around a delivered physical Back key.
 */
class BackNavigationInference(
    private val quietMs: Long = QUIET_MS,
    private val ignoredPackages: Set<String> = emptySet()
) {
    private val historyByPackage = mutableMapOf<String, MutableList<String>>()
    private var currentPackage: String? = null
    private var lastInteractionUptimeMs: Long? = null
    private var lastDeliveredBackUptimeMs: Long? = null

    /** Records an input that makes an activity transition ambiguous. */
    fun onInteraction(uptimeMs: Long) {
        lastInteractionUptimeMs = uptimeMs
    }

    /** Records a global action delivered by a physical key. */
    fun onKeyDeliveredGlobalAction(kind: String, uptimeMs: Long) {
        onInteraction(uptimeMs)
        if (kind.equals("BACK", ignoreCase = true)) {
            lastDeliveredBackUptimeMs = uptimeMs
        }
    }

    /**
     * Updates the confirmed activity history and returns a Back only for a quiet direct pop.
     * Unknown classes deliberately leave the stack untouched: dialogs and keyboard changes are
     * not navigation evidence.
     */
    fun onActivityChanged(
        packageName: String,
        activityClass: String?,
        uptimeMs: Long
    ): BackInference? {
        val previousPackage = currentPackage
        currentPackage = packageName
        if (packageName in ignoredPackages || activityClass == null) return null

        val stack = historyByPackage.getOrPut(packageName) { mutableListOf() }
        val top = stack.lastOrNull()
        val belowTop = stack.getOrNull(stack.lastIndex - 1)
        val isDirectPop = previousPackage == packageName &&
            top != null && belowTop != null && activityClass == belowTop

        if (isDirectPop) {
            val fromActivityClass = requireNotNull(top)
            stack.removeAt(stack.lastIndex)
            val interactionWasQuiet = lastInteractionUptimeMs?.let { uptimeMs - it > quietMs } ?: true
            val noDeliveredBack = lastDeliveredBackUptimeMs?.let { uptimeMs - it !in 0..quietMs } ?: true
            return if (interactionWasQuiet && noDeliveredBack) {
                BackInference(packageName, fromActivityClass, activityClass)
            } else {
                null
            }
        }

        if (top != activityClass) stack += activityClass
        return null
    }

    fun reset() {
        historyByPackage.clear()
        currentPackage = null
        lastInteractionUptimeMs = null
        lastDeliveredBackUptimeMs = null
    }

    internal fun historyForTest(packageName: String): List<String> = historyByPackage[packageName].orEmpty()

    companion object {
        const val QUIET_MS: Long = 600L
    }
}

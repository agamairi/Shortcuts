package com.shortcuts.app.planner

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

/** A launchable app that can safely be offered to the on-device model. */
data class InstalledApp(
    val userVisibleLabel: String,
    val packageName: String,
    val isUserInstalled: Boolean = false
)

/** Supplies the real device's launchable apps. Kept small so JVM tests can use a fake. */
fun interface InstalledAppSource {
    fun launchableApps(): List<InstalledApp>
}

/** Test-friendly [InstalledAppSource] with no dependency on Android framework state. */
class FakeInstalledAppSource(
    private val apps: List<InstalledApp>
) : InstalledAppSource {
    override fun launchableApps(): List<InstalledApp> = apps
}

/**
 * Enumerates only packages with a launcher activity. The manifest's scoped <queries> declaration
 * grants exactly this visibility; broad package visibility is deliberately not requested.
 */
class PackageManagerInstalledAppSource(
    private val packageManager: PackageManager
) : InstalledAppSource {
    override fun launchableApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                val appInfo = activityInfo.applicationInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: packageName
                InstalledApp(
                    userVisibleLabel = label,
                    packageName = packageName,
                    isUserInstalled = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                )
            }
            .distinctBy { it.packageName }
    }
}

data class AppMatch(
    val app: InstalledApp,
    val confidence: Float
)

/**
 * Resolves model output against packages that are actually launchable on this device.
 * Ambiguous or weak input returns null: launching the wrong app is never an acceptable repair.
 */
class GroundingContext(private val installedAppSource: InstalledAppSource) {
    companion object {
        /** Maximum entries exposed to the model before token budgeting is applied. */
        const val MAX_PROMPT_APPS = 24

        private const val EXACT_CONFIDENCE = 1.0f
        private const val PREFIX_CONFIDENCE = 0.90f
        private const val TOKEN_SUBSET_CONFIDENCE = 0.80f
        private const val EDIT_DISTANCE_CONFIDENCE = 0.65f
    }

    /** User-installed apps are usually the most useful targets, so they are listed first. */
    fun appsForPrompt(): List<InstalledApp> = installedAppSource.launchableApps()
        .distinctBy { it.packageName }
        .sortedWith(
            compareByDescending<InstalledApp> { it.isUserInstalled }
                .thenBy { it.userVisibleLabel.lowercase(Locale.ROOT) }
        )
        .take(MAX_PROMPT_APPS)

    fun resolveApp(query: String): AppMatch? {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return null

        val candidates = installedAppSource.launchableApps()
            .distinctBy { it.packageName }
            .mapNotNull { app -> match(app, query, normalizedQuery) }

        if (candidates.isEmpty()) return null
        val bestConfidence = candidates.maxOf { it.confidence }
        val best = candidates.filter { it.confidence == bestConfidence }
        return best.singleOrNull()
    }

    /** Returns a grounded label for an already-resolved package, without fuzzy matching. */
    fun appLabelForPackage(packageName: String): String? = installedAppSource.launchableApps()
        .firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }
        ?.userVisibleLabel

    private fun match(app: InstalledApp, rawQuery: String, normalizedQuery: String): AppMatch? {
        val label = normalize(app.userVisibleLabel)
        val packageName = normalize(app.packageName)
        val fields = listOf(label, packageName)
        val queryTokens = queryTokens(rawQuery)
        val appTokenFields = listOf(app.userVisibleLabel, app.packageName).map(::queryTokens)

        return when {
            fields.any { it == normalizedQuery } -> AppMatch(app, EXACT_CONFIDENCE)
            fields.any { it.startsWith(normalizedQuery) } -> AppMatch(app, PREFIX_CONFIDENCE)
            queryTokens.isNotEmpty() && appTokenFields.any { it.containsAll(queryTokens) } ->
                AppMatch(app, TOKEN_SUBSET_CONFIDENCE)
            else -> closestEditDistance(fields, normalizedQuery)?.let {
                AppMatch(app, EDIT_DISTANCE_CONFIDENCE)
            }
        }
    }

    private fun closestEditDistance(fields: List<String>, query: String): Int? {
        val allowedDistance = minOf(3, maxOf(1, query.length / 4))
        return fields.map { levenshtein(query, it) }
            .minOrNull()
            ?.takeIf { it <= allowedDistance }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun queryTokens(value: String): Set<String> = value
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
        .toSet()

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        for (leftIndex in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost
                )
            }
            previous = current
        }
        return previous[right.length]
    }
}

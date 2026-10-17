package com.shortcuts.app.automation.core.fingerprint

import com.shortcuts.app.automation.core.text.TextNormalizer
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.ScreenFingerprint

/**
 * Default pure-Kotlin implementation of [ScreenFingerprinter].
 *
 * Structural tokens: 64-bit deterministic FNV-1a hashes of (className, resourceId, depth bucket) for visible nodes.
 * Landmark tokens: 64-bit deterministic FNV-1a hashes of normalized text/contentDescription of visible nodes
 * that look like stable landmarks (top 25% of window with short text <= 40 chars, or title/toolbar/tab resource ids).
 * Raw text is never stored in [ScreenFingerprint].
 * Structure hash: stable FNV-1a 64 hash over the sorted structural token set.
 *
 * Similarity: 0.0 if packageName differs; otherwise weighted Jaccard 0.6 * structural + 0.4 * landmark
 * (+ 0.1 bonus capped at 1.0 when activityClass matches and both are non-null).
 */
class DefaultScreenFingerprinter : ScreenFingerprinter {

    override fun fingerprint(window: WindowSnapshot, activityClass: String?): ScreenFingerprint {
        val structuralTokens = mutableSetOf<Long>()
        val landmarkTokens = mutableSetOf<Long>()

        val windowBounds = window.boundsInScreen
        val top25Cutoff = windowBounds.top + (windowBounds.height * 0.25f)

        for (node in window.nodes.values) {
            if (!node.visibleToUser) continue

            val depth = window.ancestors(node).size
            val bucket = depthBucket(depth)
            val resId = node.resourceId ?: ""
            val structuralKey = "${node.className}|$resId|$bucket"
            structuralTokens.add(fnv1a64(structuralKey))

            val isTop25 = node.boundsInScreen.top <= top25Cutoff
            val resIdLower = resId.lowercase()
            val isLandmarkResId = resIdLower.contains("title") ||
                resIdLower.contains("toolbar") ||
                resIdLower.contains("tab")

            val normText = TextNormalizer.normalize(node.text)
            if (normText != null) {
                val isShort = normText.length <= 40
                if ((isTop25 && isShort) || isLandmarkResId) {
                    landmarkTokens.add(fnv1a64("text:$normText"))
                }
            }

            val normDesc = TextNormalizer.normalize(node.contentDescription)
            if (normDesc != null) {
                val isShort = normDesc.length <= 40
                if ((isTop25 && isShort) || isLandmarkResId) {
                    landmarkTokens.add(fnv1a64("desc:$normDesc"))
                }
            }
        }

        val sortedTokens = structuralTokens.sorted()
        val structureHash = hashTokenSet(sortedTokens)

        return ScreenFingerprint(
            version = 1,
            packageName = window.packageName,
            activityClass = activityClass,
            windowType = window.type,
            structuralTokens = structuralTokens,
            landmarkTokens = landmarkTokens,
            structureHash = structureHash
        )
    }

    override fun similarity(a: ScreenFingerprint, b: ScreenFingerprint): Double {
        if (a.packageName != b.packageName) return 0.0

        val structuralJaccard = jaccard(a.structuralTokens, b.structuralTokens)
        val landmarkJaccard = jaccard(a.landmarkTokens, b.landmarkTokens)

        val baseScore = (0.6 * structuralJaccard) + (0.4 * landmarkJaccard)
        val bonus = if (a.activityClass != null && b.activityClass != null && a.activityClass == b.activityClass) {
            0.1
        } else {
            0.0
        }

        return (baseScore + bonus).coerceAtMost(1.0)
    }

    private fun jaccard(setA: Set<Long>, setB: Set<Long>): Double {
        if (setA.isEmpty() && setB.isEmpty()) return 1.0
        val intersectionSize = setA.intersect(setB).size
        val unionSize = setA.union(setB).size
        return if (unionSize == 0) 1.0 else intersectionSize.toDouble() / unionSize.toDouble()
    }

    private fun depthBucket(depth: Int): Int = when {
        depth <= 2 -> depth
        depth <= 5 -> 3
        depth <= 9 -> 6
        else -> 10
    }

    companion object {
        private const val FNV1A_64_OFFSET = -3750763034362895579L // 0xcbf29ce484222325L
        private const val FNV1A_64_PRIME = 1099511628211L

        fun fnv1a64(str: String): Long {
            val bytes = str.toByteArray(Charsets.UTF_8)
            var hash = FNV1A_64_OFFSET
            for (b in bytes) {
                hash = hash xor (b.toLong() and 0xFFL)
                hash *= FNV1A_64_PRIME
            }
            return hash
        }

        fun hashTokenSet(tokens: Iterable<Long>): Long {
            var hash = FNV1A_64_OFFSET
            for (token in tokens) {
                for (i in 0 until 8) {
                    val byte = (token ushr (i * 8)) and 0xFFL
                    hash = hash xor byte
                    hash *= FNV1A_64_PRIME
                }
            }
            return hash
        }
    }
}

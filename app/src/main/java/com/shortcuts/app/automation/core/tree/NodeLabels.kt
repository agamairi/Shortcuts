package com.shortcuts.app.automation.core.tree

import com.shortcuts.app.automation.core.text.TextNormalizer
import com.shortcuts.app.automation.model.IntPoint

/**
 * Semantic label derived for a node within its window snapshot.
 *
 * Used by both capture ([SelectorExtractor]) and matching ([CandidateScorer], [SelectorMatcher])
 * so that Compose buttons and text fields whose user-visible label lives on a child node
 * match deterministically on both sides of the pipeline.
 */
data class DerivedLabel(
    val text: String?,
    val contentDescription: String?,
    val sourceNodeId: Int?
)

/**
 * Derives the semantic label for [node] within this [WindowSnapshot].
 *
 * For non-editable nodes that carry their own text or content description (or test tag),
 * returns their own label with [sourceNodeId] = [node.nodeId].
 *
 * For unlabelled nodes (such as Compose buttons where the clickable node is a bare View)
 * or editable nodes (where [node.text] represents the user's typed value rather than identity),
 * searches descendants breadth-first for visible labelled children contained within [node]'s bounds.
 * If [touchPoint] is provided, prioritizes descendants whose bounds contain the touch point.
 */
fun WindowSnapshot.labelOf(
    node: NodeSnapshot,
    touchPoint: IntPoint? = null
): DerivedLabel {
    // Non-editable nodes with their own label keep it directly
    if (!node.editable && hasOwnLabel(node)) {
        return DerivedLabel(
            text = TextNormalizer.normalize(node.text),
            contentDescription = TextNormalizer.normalize(node.contentDescription),
            sourceNodeId = node.nodeId
        )
    }

    // Search descendants breadth-first for a labelled child
    val descendants = breadthFirstDescendants(node)
        .filter { it.visibleToUser && isInside(it.boundsInScreen, node.boundsInScreen) && hasDescendantLabel(it) }

    val candidate = descendants.firstOrNull { descendant ->
        touchPoint != null && descendant.boundsInScreen.contains(touchPoint.x, touchPoint.y)
    } ?: descendants.firstOrNull()

    if (candidate != null) {
        return DerivedLabel(
            text = TextNormalizer.normalize(candidate.text),
            contentDescription = TextNormalizer.normalize(candidate.contentDescription),
            sourceNodeId = candidate.nodeId
        )
    }

    // Fallback for editable nodes: content description if present on the node itself
    if (node.editable) {
        val desc = TextNormalizer.normalize(node.contentDescription)
        if (desc != null) {
            return DerivedLabel(
                text = null,
                contentDescription = desc,
                sourceNodeId = node.nodeId
            )
        }
    }

    return DerivedLabel(
        text = null,
        contentDescription = null,
        sourceNodeId = null
    )
}

private fun WindowSnapshot.breadthFirstDescendants(target: NodeSnapshot): List<NodeSnapshot> {
    val result = mutableListOf<NodeSnapshot>()
    val queue = ArrayDeque<NodeSnapshot>()
    children(target).forEach(queue::addLast)
    val visited = mutableSetOf(target.nodeId)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (!visited.add(node.nodeId)) continue
        result += node
        children(node).forEach(queue::addLast)
    }
    return result
}

private fun hasOwnLabel(node: NodeSnapshot): Boolean {
    val hasText = !node.editable && TextNormalizer.normalize(node.text) != null
    val hasDesc = TextNormalizer.normalize(node.contentDescription) != null
    val hasTag = testTag(node.resourceId) != null
    return hasText || hasDesc || hasTag
}

private fun hasDescendantLabel(node: NodeSnapshot): Boolean {
    val hasText = TextNormalizer.normalize(node.text) != null
    val hasDesc = TextNormalizer.normalize(node.contentDescription) != null
    val hasTag = testTag(node.resourceId) != null
    return hasText || hasDesc || hasTag
}

private val STANDARD_RESOURCE_ID = Regex("^(?:[^:]+:)?id/.+$")

private fun testTag(resourceId: String?): String? {
    val value = TextNormalizer.normalize(resourceId) ?: return null
    return value.takeUnless(STANDARD_RESOURCE_ID::matches)
}

private fun isInside(inner: IntRect, outer: IntRect): Boolean =
    inner.left >= outer.left && inner.top >= outer.top &&
        inner.right <= outer.right && inner.bottom <= outer.bottom

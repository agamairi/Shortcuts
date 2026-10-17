package com.shortcuts.app.automation.core.tree

/**
 * The single definition of a selector's sibling index: the node's position among its parent's
 * children of the same class, in drawing order. Same-class (not raw child) position stays stable
 * when unrelated decoration views are added between, say, repeated icon buttons. Capture records
 * it and matching compares it, so both must call this.
 */
fun WindowSnapshot.sameClassSiblingIndex(node: NodeSnapshot): Int? {
    val parent = parent(node) ?: return null
    return children(parent)
        .filter { it.className == node.className }
        .indexOfFirst { it.nodeId == node.nodeId }
        .takeIf { it >= 0 }
}

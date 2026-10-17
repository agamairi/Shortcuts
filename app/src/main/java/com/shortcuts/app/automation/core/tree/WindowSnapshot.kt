package com.shortcuts.app.automation.core.tree

import com.shortcuts.app.automation.model.WindowType

data class WindowSnapshot(
    val windowId: Int,
    val type: WindowType,
    val layer: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    /** Raw adapter-side value. Never copy this field into a persisted workflow or trace. */
    val title: String?,
    val packageName: String,
    val boundsInScreen: IntRect,
    val rootId: Int,
    val nodes: Map<Int, NodeSnapshot>
) {
    fun node(id: Int): NodeSnapshot? = nodes[id]

    fun children(node: NodeSnapshot): List<NodeSnapshot> =
        node.childIds.mapNotNull(nodes::get)

    fun parent(node: NodeSnapshot): NodeSnapshot? = node.parentId?.let(nodes::get)

    fun ancestors(node: NodeSnapshot): List<NodeSnapshot> = buildList {
        var current = parent(node)
        val visited = mutableSetOf<Int>()
        while (current != null && visited.add(current.nodeId)) {
            add(current)
            current = parent(current)
        }
    }

    fun descendants(node: NodeSnapshot): List<NodeSnapshot> = buildList {
        val visited = mutableSetOf(node.nodeId)
        fun visit(parent: NodeSnapshot) {
            children(parent).forEach { child ->
                if (visited.add(child.nodeId)) {
                    add(child)
                    visit(child)
                }
            }
        }
        visit(node)
    }
}

data class WindowSetSnapshot(
    val capturedAtUptimeMs: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val rotation: Int,
    val density: Float,
    val windows: List<WindowSnapshot>
) {
    fun window(id: Int): WindowSnapshot? = windows.firstOrNull { it.windowId == id }

    fun node(id: Int): NodeSnapshot? = windows.firstNotNullOfOrNull { it.node(id) }

    fun hitTest(
        x: Int,
        y: Int,
        excludePackages: Set<String> = emptySet()
    ): HitTestResult? {
        val selectedWindow = windows
            .asSequence()
            .sortedByDescending(WindowSnapshot::layer)
            .filterNot { it.type == WindowType.ACCESSIBILITY_OVERLAY }
            .filterNot { it.packageName in excludePackages }
            .firstOrNull { window ->
                window.boundsInScreen.contains(x, y) && window.node(window.rootId) != null
            } ?: return null

        val root = selectedWindow.node(selectedWindow.rootId) ?: return null
        val leaf = selectedWindow.deepestVisibleNodeAt(root, x, y, mutableSetOf()) ?: return null
        var candidate: NodeSnapshot? = leaf
        var rejectedOversizedActionable = false
        while (candidate != null) {
            if (candidate.isActionable) {
                val windowArea = selectedWindow.boundsInScreen.area
                val safelySized = windowArea > 0 && candidate.boundsInScreen.area * 100 < windowArea * 80
                if (safelySized) return HitTestResult(selectedWindow, candidate, leaf)
                rejectedOversizedActionable = true
            }
            candidate = selectedWindow.parent(candidate)
        }
        return if (rejectedOversizedActionable) null else HitTestResult(selectedWindow, leaf, leaf)
    }

    private fun WindowSnapshot.deepestVisibleNodeAt(
        node: NodeSnapshot,
        x: Int,
        y: Int,
        visited: MutableSet<Int>
    ): NodeSnapshot? {
        if (!visited.add(node.nodeId) || !node.visibleToUser) return null
        for (childId in node.childIds.asReversed()) {
            val child = this.node(childId) ?: continue
            val deepest = deepestVisibleNodeAt(child, x, y, visited)
            if (deepest != null) return deepest
        }
        return node.takeIf { it.boundsInScreen.contains(x, y) }
    }
}

data class HitTestResult(
    val window: WindowSnapshot,
    val target: NodeSnapshot,
    val leaf: NodeSnapshot
)

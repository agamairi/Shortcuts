package com.shortcuts.app.automation.core.tree

import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.WindowType

fun r(left: Int, top: Int, right: Int, bottom: Int) = IntRect(left, top, right, bottom)

fun window(
    pkg: String,
    id: Int = 1,
    type: WindowType = WindowType.APPLICATION,
    layer: Int = 0,
    bounds: IntRect = r(0, 0, 1_000, 2_000),
    block: WindowFixtureBuilder.() -> Unit
): WindowSnapshot = WindowFixtureBuilder(pkg, id, type, layer, bounds).apply(block).build()

fun windowSet(
    vararg windows: WindowSnapshot,
    width: Int = 1_000,
    height: Int = 2_000
) = WindowSetSnapshot(
    capturedAtUptimeMs = 1_000,
    displayWidth = width,
    displayHeight = height,
    rotation = 0,
    density = 1f,
    windows = windows.toList()
)

class WindowFixtureBuilder internal constructor(
    private val packageName: String,
    private val windowId: Int,
    private val type: WindowType,
    private val layer: Int,
    private val bounds: IntRect
) {
    private var nextId = 1
    private val nodes = linkedMapOf<Int, NodeSnapshot>()
    private var rootId: Int? = null

    fun node(
        className: String,
        id: Int = nextId++,
        resourceId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        stateDescription: String? = null,
        bounds: IntRect = this.bounds,
        visible: Boolean = true,
        enabled: Boolean = true,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        editable: Boolean = false,
        focusable: Boolean = false,
        focused: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        password: Boolean = false,
        actions: Set<NodeAction> = emptySet(),
        collectionRow: Int? = null,
        collectionColumn: Int? = null,
        drawingOrder: Int = 0,
        block: NodeFixtureBuilder.() -> Unit = {}
    ) {
        check(rootId == null) { "A window fixture has exactly one root; nest additional nodes inside it" }
        nextId = maxOf(nextId, id + 1)
        rootId = id
        addNode(
            parentId = null,
            className = className,
            id = id,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription,
            hintText = hintText,
            stateDescription = stateDescription,
            bounds = bounds,
            visible = visible,
            enabled = enabled,
            clickable = clickable,
            longClickable = longClickable,
            checkable = checkable,
            checked = checked,
            editable = editable,
            focusable = focusable,
            focused = focused,
            scrollable = scrollable,
            selected = selected,
            password = password,
            actions = actions,
            collectionRow = collectionRow,
            collectionColumn = collectionColumn,
            drawingOrder = drawingOrder,
            block = block
        )
    }

    private fun addNode(
        parentId: Int?,
        className: String,
        id: Int,
        resourceId: String?,
        text: String?,
        contentDescription: String?,
        hintText: String?,
        stateDescription: String?,
        bounds: IntRect,
        visible: Boolean,
        enabled: Boolean,
        clickable: Boolean,
        longClickable: Boolean,
        checkable: Boolean,
        checked: Boolean,
        editable: Boolean,
        focusable: Boolean,
        focused: Boolean,
        scrollable: Boolean,
        selected: Boolean,
        password: Boolean,
        actions: Set<NodeAction>,
        collectionRow: Int?,
        collectionColumn: Int?,
        drawingOrder: Int,
        block: NodeFixtureBuilder.() -> Unit
    ) {
        val childIds = mutableListOf<Int>()
        val builder = NodeFixtureBuilder(childIds) { child ->
            addNode(parentId = id, child = child)
        }
        builder.block()
        nodes[id] = NodeSnapshot(
            nodeId = id,
            parentId = parentId,
            childIds = childIds,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDescription,
            hintText = hintText,
            stateDescription = stateDescription,
            boundsInScreen = bounds,
            visibleToUser = visible,
            enabled = enabled,
            clickable = clickable,
            longClickable = longClickable,
            checkable = checkable,
            checked = checked,
            editable = editable,
            focusable = focusable,
            focused = focused,
            scrollable = scrollable,
            selected = selected,
            password = password,
            actions = actions,
            collectionRow = collectionRow,
            collectionColumn = collectionColumn,
            drawingOrder = drawingOrder,
            windowId = windowId,
            packageName = packageName
        )
    }

    fun build(): WindowSnapshot {
        val root = checkNotNull(rootId) { "Call node(...) once to define the window root" }
        return WindowSnapshot(
            windowId = windowId,
            type = type,
            layer = layer,
            isActive = true,
            isFocused = true,
            title = null,
            packageName = packageName,
            boundsInScreen = bounds,
            rootId = root,
            nodes = nodes.toMap()
        )
    }

    inner class NodeFixtureBuilder internal constructor(
        private val childIds: MutableList<Int>,
        private val add: (NodeArguments) -> Unit
    ) {
        fun node(
            className: String,
            id: Int = nextId++,
            resourceId: String? = null,
            text: String? = null,
            contentDescription: String? = null,
            hintText: String? = null,
            stateDescription: String? = null,
            bounds: IntRect = this@WindowFixtureBuilder.bounds,
            visible: Boolean = true,
            enabled: Boolean = true,
            clickable: Boolean = false,
            longClickable: Boolean = false,
            checkable: Boolean = false,
            checked: Boolean = false,
            editable: Boolean = false,
            focusable: Boolean = false,
            focused: Boolean = false,
            scrollable: Boolean = false,
            selected: Boolean = false,
            password: Boolean = false,
            actions: Set<NodeAction> = emptySet(),
            collectionRow: Int? = null,
            collectionColumn: Int? = null,
            drawingOrder: Int = childIds.size,
            block: NodeFixtureBuilder.() -> Unit = {}
        ) {
            nextId = maxOf(nextId, id + 1)
            childIds += id
            add(
                NodeArguments(
                    className, id, resourceId, text, contentDescription, hintText, stateDescription,
                    bounds, visible, enabled, clickable, longClickable, checkable, checked, editable,
                    focusable, focused, scrollable, selected, password, actions, collectionRow,
                    collectionColumn, drawingOrder, block
                )
            )
        }
    }

    internal data class NodeArguments(
        val className: String,
        val id: Int,
        val resourceId: String?,
        val text: String?,
        val contentDescription: String?,
        val hintText: String?,
        val stateDescription: String?,
        val bounds: IntRect,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val longClickable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val editable: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val selected: Boolean,
        val password: Boolean,
        val actions: Set<NodeAction>,
        val collectionRow: Int?,
        val collectionColumn: Int?,
        val drawingOrder: Int,
        val block: NodeFixtureBuilder.() -> Unit
    )

    private fun addNode(parentId: Int?, child: NodeArguments) = addNode(
        parentId, child.className, child.id, child.resourceId, child.text,
        child.contentDescription, child.hintText, child.stateDescription, child.bounds,
        child.visible, child.enabled, child.clickable, child.longClickable, child.checkable,
        child.checked, child.editable, child.focusable, child.focused, child.scrollable,
        child.selected, child.password, child.actions, child.collectionRow,
        child.collectionColumn, child.drawingOrder, child.block
    )
}

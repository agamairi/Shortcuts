package com.shortcuts.app.automation.core.tree

import com.shortcuts.app.automation.model.NodeRole

/**
 * The single className → role mapping. Capture writes a selector's expectedRole with it and
 * matching compares candidates with it, so both sides must agree. Matching is by simple-name
 * suffix so platform, AndroidX, Material and Compose-reported classes resolve alike.
 */
object NodeRoles {
    fun roleOf(node: NodeSnapshot): NodeRole = roleOf(node.className, node.editable, node.checkable, node.scrollable)

    fun roleOf(className: String?, editable: Boolean = false, checkable: Boolean = false, scrollable: Boolean = false): NodeRole {
        val simple = className.orEmpty().substringAfterLast('.')
        return when {
            editable || simple.endsWith("EditText") || simple == "TextField" -> NodeRole.TEXT_FIELD
            simple.endsWith("Switch") || simple == "SwitchCompat" || simple == "SwitchMaterial" -> NodeRole.SWITCH
            simple.endsWith("CheckBox") -> NodeRole.CHECKBOX
            simple.endsWith("RadioButton") -> NodeRole.RADIO_BUTTON
            simple.endsWith("SeekBar") || simple == "Slider" -> NodeRole.SLIDER
            simple == "TabWidget" || simple.endsWith("TabView") || simple == "Tab" -> NodeRole.TAB
            simple.endsWith("Button") || simple.endsWith("ImageButton") -> NodeRole.BUTTON
            simple.endsWith("RecyclerView") || simple.endsWith("ListView") || simple.endsWith("GridView") ||
                simple == "LazyColumn" || simple == "LazyRow" || (scrollable && simple.endsWith("View")) -> NodeRole.LIST
            simple.endsWith("ImageView") || simple == "Image" -> NodeRole.IMAGE
            simple.endsWith("TextView") || simple == "Text" -> NodeRole.TEXT
            checkable -> NodeRole.CHECKBOX
            else -> NodeRole.UNKNOWN
        }
    }
}

package com.shortcuts.app.widget

import androidx.compose.ui.graphics.Color
import com.shortcuts.app.data.Automation

/** Resolves a persisted colour key, handling casing, whitespace, and old invalid values. */
fun resolveWidgetColorKey(
    colorKey: String?,
    default: WidgetColorKey = WidgetColorKey.BLUE
): WidgetColorKey = WidgetColorKey.entries.firstOrNull { it.name.equals(colorKey?.trim(), ignoreCase = true) }
    ?: default

/** Shared Compose colour resolver for every in-app shortcut surface. */
fun resolveWidgetColor(
    colorKey: String?,
    default: WidgetColorKey = WidgetColorKey.BLUE
): Color = resolveWidgetColorKey(colorKey, default).composeColor

/** Resolves a persisted icon key, handling casing, whitespace, and old invalid values. */
fun resolveWidgetIconKey(
    iconKey: String?,
    default: WidgetIconKey = WidgetIconKey.BOLT
): WidgetIconKey = WidgetIconKey.entries.firstOrNull { it.name.equals(iconKey?.trim(), ignoreCase = true) }
    ?: default

/** Shared drawable resolver for surfaces that render Android/Glance widget icons. */
fun resolveWidgetIconDrawable(
    iconKey: String?,
    default: WidgetIconKey = WidgetIconKey.BOLT
): Int = resolveWidgetIconKey(iconKey, default).drawableRes

/** Parses persisted widget choices defensively so old or invalid Room values never break a widget. */
data class WidgetAppearance(
    val color: WidgetColorKey,
    val icon: WidgetIconKey
) {
    companion object {
        fun fromAutomation(automation: Automation): WidgetAppearance = WidgetAppearance(
            color = resolveWidgetColorKey(automation.colorKey),
            icon = resolveWidgetIconKey(automation.iconKey)
        )

        fun fromKeys(colorKey: String?, iconKey: String?): WidgetAppearance = WidgetAppearance(
            color = resolveWidgetColorKey(colorKey),
            icon = resolveWidgetIconKey(iconKey)
        )
    }
}

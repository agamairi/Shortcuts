package com.shortcuts.app.widget

import com.shortcuts.app.data.WidgetConfigSource

/** The single, adaptive widget shown to new users in the homescreen picker. */
class ShortcutWidget : UnifiedShortcutWidget(
    WidgetConfigSource.UNIFIED,
    ShortcutWidgetConfigActivity::class.java
)

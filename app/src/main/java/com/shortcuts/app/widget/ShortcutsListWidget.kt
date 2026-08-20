package com.shortcuts.app.widget

import com.shortcuts.app.data.WidgetConfigSource

/** Legacy provider shim; its component name remains installed for placed widgets. */
class ShortcutsListWidget : UnifiedShortcutWidget(
    WidgetConfigSource.LIST,
    ShortcutsListWidgetConfigActivity::class.java
)

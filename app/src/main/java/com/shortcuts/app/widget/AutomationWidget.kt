package com.shortcuts.app.widget

import com.shortcuts.app.data.WidgetConfigSource

/** Legacy provider shim; its component name remains installed for placed widgets. */
class AutomationWidget : UnifiedShortcutWidget(
    WidgetConfigSource.AUTOMATION,
    AutomationWidgetConfigActivity::class.java
)

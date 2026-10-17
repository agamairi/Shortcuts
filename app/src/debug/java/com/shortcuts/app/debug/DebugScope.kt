package com.shortcuts.app.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-scoped lifetime for debug commands after the no-UI activity finishes. */
object DebugScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

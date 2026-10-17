package com.shortcuts.app.automation.android

import com.shortcuts.app.automation.core.tree.WindowSetSnapshot

/** Produces an immutable snapshot of every interactive window, or null when unavailable. */
fun interface WindowSnapshotSource {
    fun snapshot(): WindowSetSnapshot?
}

/**
 * Process-wide switchboard for Engine v2 while it runs beside the v0.7 pipeline.
 * The accessibility service publishes its snapshot source here when it connects; the recorder
 * bridge and the replay runtime read it. New recordings are saved as v2 only when [enabled].
 */
object EngineV2 {
    @Volatile
    var snapshotSource: WindowSnapshotSource? = null

    /** Engine v2 is the product: new recordings are saved as v2 in every build type. */
    @Volatile
    var enabled: Boolean = true
}

package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.geometry.NormalizedGeometry
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.CapturedGesture

class GestureAssembler {
    fun assemble(interaction: CapturedInteraction, window: WindowSnapshot): CapturedGesture =
        CapturedGesture(
            kind = interaction.kind,
            durationMs = (interaction.upUptimeMs - interaction.downUptimeMs).coerceAtLeast(0L),
            points = interaction.points.map { point ->
                point.copy(
                    pointInWindow = NormalizedGeometry.normalize(
                        point.displayPx.x,
                        point.displayPx.y,
                        window.boundsInScreen
                    )
                )
            },
            pointerCount = 1
        )
}

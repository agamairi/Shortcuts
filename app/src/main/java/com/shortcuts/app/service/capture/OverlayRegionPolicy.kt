package com.shortcuts.app.service.capture

data class CaptureRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}
data class CaptureInsets(val statusBar: Int, val navigationBar: Int)
data class CaptureApplicationWindow(val layer: Int, val packageName: String?)
sealed class OverlayRegion { data object NotTouchable : OverlayRegion(); data class Touchable(val rect: CaptureRect) : OverlayRegion() }

/** Rules which deliberately keep system and sensitive windows out of the capture surface. */
object OverlayRegionPolicy {
    private val blockedSystemPackages = setOf(
        "android", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    /** Select from application windows only; overlays, IMEs, and SystemUI bars are excluded upstream. */
    fun topAppPackage(applicationWindows: Iterable<CaptureApplicationWindow>): String? =
        applicationWindows
            .filter { it.packageName != "com.android.systemui" }
            .maxByOrNull { it.layer }
            ?.packageName

    fun region(
        displayWidth: Int,
        displayHeight: Int,
        insets: CaptureInsets,
        imeBounds: CaptureRect?,
        shadeOpen: Boolean,
        topAppPackage: String?,
        recorderPackageName: String
    ): OverlayRegion {
        if (displayWidth <= 0 || displayHeight <= 0 || shadeOpen ||
            topAppPackage == recorderPackageName || topAppPackage in blockedSystemPackages
        ) return OverlayRegion.NotTouchable
        val bottom = minOf(displayHeight - insets.navigationBar.coerceAtLeast(0), imeBounds?.top ?: displayHeight)
        val top = insets.statusBar.coerceAtLeast(0)
        return if (bottom <= top) OverlayRegion.NotTouchable else OverlayRegion.Touchable(CaptureRect(0, top, displayWidth, bottom))
    }
}

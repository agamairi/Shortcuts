package com.shortcuts.app.widget

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.shortcuts.app.R

enum class WidgetIconKey(
    @DrawableRes val drawableRes: Int,
    val displayLabel: String,
    val composeIcon: ImageVector
) {
    WIFI(R.drawable.ic_widget_wifi, "WiFi", Icons.Filled.Wifi),
    BLUETOOTH(R.drawable.ic_widget_bluetooth, "Bluetooth", Icons.Filled.Bluetooth),
    HOME(R.drawable.ic_widget_home, "Home", Icons.Filled.Home),
    BOLT(R.drawable.ic_widget_bolt, "Bolt", Icons.Filled.Bolt),
    STAR(R.drawable.ic_widget_star, "Star", Icons.Filled.Star),
    BELL(R.drawable.ic_widget_bell, "Bell", Icons.Filled.Notifications)
}

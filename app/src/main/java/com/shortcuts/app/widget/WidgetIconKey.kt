package com.shortcuts.app.widget

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Phone
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
    BELL(R.drawable.ic_widget_bell, "Bell", Icons.Filled.Notifications),
    MOON(R.drawable.ic_widget_moon, "Moon", Icons.Filled.DarkMode),
    SUN(R.drawable.ic_widget_sun, "Sun", Icons.Filled.LightMode),
    CAMERA(R.drawable.ic_widget_camera, "Camera", Icons.Filled.CameraAlt),
    MUSIC(R.drawable.ic_widget_music, "Music", Icons.Filled.MusicNote),
    PHONE(R.drawable.ic_widget_phone, "Phone", Icons.Filled.Phone),
    MESSAGE(R.drawable.ic_widget_message, "Message", Icons.Filled.Message),
    CAR(R.drawable.ic_widget_car, "Car", Icons.Filled.DirectionsCar),
    COFFEE(R.drawable.ic_widget_coffee, "Coffee", Icons.Filled.LocalCafe),
    LOCK(R.drawable.ic_widget_lock, "Lock", Icons.Filled.Lock),
    HEART(R.drawable.ic_widget_heart, "Heart", Icons.Filled.Favorite),
    MAP(R.drawable.ic_widget_map, "Map", Icons.Filled.Map)
}

package com.doormonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00121F),
    secondary = Color(0xFF81D4FA),
    background = Color(0xFF0F1721),
    surface = Color(0xFF161E29),
    onBackground = Color(0xFFE3E8EE),
    onSurface = Color(0xFFE3E8EE)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF0288D1)
)

/** App-wide Material3 theme. Defaults to dark, which suits a wall-mounted tablet at night. */
@Composable
fun DoorMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}

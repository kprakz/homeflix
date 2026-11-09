package com.homeflix.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HomeflixRed = Color(0xFFE50914)
val Ink = Color(0xFF08080A)
val Panel = Color(0xFF141418)
val PanelHigh = Color(0xFF1E1E24)
val Muted = Color(0xFF9AA0A6)
val Gold = Color(0xFFF5C451)

private val scheme = darkColorScheme(
    primary = HomeflixRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF7A0007),
    onPrimaryContainer = Color.White,
    secondary = Gold,
    onSecondary = Color.Black,
    background = Ink,
    onBackground = Color(0xFFF4F4F5),
    surface = Panel,
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = PanelHigh,
    onSurfaceVariant = Muted,
    outline = Color(0xFF3A3A42),
    error = Color(0xFFFF6B6B),
)

@Composable
fun HomeflixTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

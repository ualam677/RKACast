package com.rkacast.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF9D7BFF),
    onPrimary = Color(0xFF1B0B45),
    primaryContainer = Color(0xFF3A2A73),
    onPrimaryContainer = Color(0xFFE6DDFF),
    secondary = Color(0xFF5ED3F3),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE4E5EA),
    surface = Color(0xFF16181E),
    onSurface = Color(0xFFE4E5EA),
    surfaceVariant = Color(0xFF22252D),
    onSurfaceVariant = Color(0xFFB7BAC4),
    surfaceContainer = Color(0xFF1B1E25),
    surfaceContainerHigh = Color(0xFF22252D),
    error = Color(0xFFFF6B6B),
)

@Composable
fun RKACastTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

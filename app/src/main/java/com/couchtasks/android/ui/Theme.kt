package com.couchtasks.android.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LinenBackground = Color(0xFFFAF7F0)
val LinenSurface = Color(0xFFFFFDF8)
val CobaltBlue = Color(0xFF155EEF)
val HealthyGreen = Color(0xFF188038)
val Ink = Color(0xFF172033)

private val CouchTasksColorScheme: ColorScheme = lightColorScheme(
    primary = CobaltBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF445E91),
    background = LinenBackground,
    onBackground = Ink,
    surface = LinenSurface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0E7D8),
    onSurfaceVariant = Color(0xFF5B6475),
    outline = Color(0xFFD8CCBA),
)

@Composable
fun CouchTasksTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CouchTasksColorScheme,
        content = content,
    )
}

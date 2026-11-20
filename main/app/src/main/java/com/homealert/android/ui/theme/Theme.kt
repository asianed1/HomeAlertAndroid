package com.homealert.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianCanvas = Color(0xFF0B0E14)
val ElevatedSurface = Color(0xFF131822)
val CardContainer = Color(0xFF1B2230)
val SkyCyanPrimary = Color(0xFF38BDF8)
val ArmedEmerald = Color(0xFF10B981)
val FailoverAmber = Color(0xFFF59E0B)
val AlertCrimson = Color(0xFFEF4444)
val MutedSlateText = Color(0xFF94A3B8)

private val HomeAlertDarkScheme = darkColorScheme(
    primary = SkyCyanPrimary,
    onPrimary = Color(0xFF041E2B),
    primaryContainer = Color(0xFF0E3A52),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = ArmedEmerald,
    onSecondary = Color(0xFF032318),
    tertiary = FailoverAmber,
    background = ObsidianCanvas,
    onBackground = Color(0xFFF1F5F9),
    surface = ElevatedSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = CardContainer,
    onSurfaceVariant = MutedSlateText,
    outline = Color(0xFF2B3548),
    error = AlertCrimson,
    onError = Color.White
)

@Composable
fun HomeAlertTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HomeAlertDarkScheme,
        content = content
    )
}

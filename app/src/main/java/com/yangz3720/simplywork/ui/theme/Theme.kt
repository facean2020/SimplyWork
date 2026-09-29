package com.yangz3720.simplywork.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = PaperLight,
    primaryContainer = AccentContainerLight,
    onPrimaryContainer = OnAccentContainerLight,
    secondary = MistLight,
    onSecondary = PaperLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = PaperLight,
    onSurfaceVariant = MistLight,
    error = ErrorLight,
    onError = PaperLight,
    outline = OutlineLight
)

private val DarkColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = PaperDark,
    primaryContainer = AccentContainerDark,
    onPrimaryContainer = OnAccentContainerDark,
    secondary = MistDark,
    onSecondary = PaperDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = MistDark,
    error = ErrorDark,
    onError = PaperDark,
    outline = OutlineDark
)

/**
 * Brand tone wins: dynamic color (Android 12+) is off by default, keeping the fixed "paper + ink teal" identity.
 */
@Composable
fun SimplyWorkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
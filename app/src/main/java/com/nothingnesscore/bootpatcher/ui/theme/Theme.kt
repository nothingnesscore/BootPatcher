package com.nothingnesscore.bootpatcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Palette and shape configuration.
 *
 * Monet (dynamic colour) is used when the platform exposes it, which keeps the
 * app visually in step with the user's wallpaper-derived system palette. The
 * hand-written schemes are the fallback for devices without Monet, and they
 * carry the same hues as the launcher icon so the app still looks deliberate.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF00BFA5),
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFFB9FFF1),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF7B5800),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF7CDBCD),
    onSecondary = Color(0xFF003730),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFFB9FFF1),
    tertiary = Color(0xFFF2BF48),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun BootPatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** When true, derive colours from the system Monet palette. */
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val supportsMonet = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    val colors = when {
        useDynamicColor && supportsMonet && darkTheme -> dynamicDarkColorScheme(context)
        useDynamicColor && supportsMonet -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
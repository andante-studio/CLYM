package com.starrydream.nanoclick.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = NanoBluePrimary,
    onPrimary = Color.White,
    primaryContainer = NanoBluePrimarySoft,
    onPrimaryContainer = NanoBlueText,
    secondary = NanoBlueSecondary,
    onSecondary = Color.White,
    secondaryContainer = NanoNeutralSurface,
    onSecondaryContainer = NanoBlueText,
    tertiary = NanoBluePrimarySoft,
    onTertiary = NanoBlueText,
    background = NanoBlueBackground,
    onBackground = NanoBlueText,
    surface = NanoBlueSurface,
    onSurface = NanoBlueText,
    surfaceVariant = NanoNeutralSurfaceVariant,
    onSurfaceVariant = NanoBlueText,
    outline = NanoBlueOutline,
    error = NanoBlueError,
    onError = Color.White
)

@Composable
fun NanoclickTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}

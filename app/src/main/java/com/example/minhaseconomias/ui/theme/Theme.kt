package com.example.minhaseconomias.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GreenPrimaryDark,
    onPrimary = GreenOnPrimaryDark,
    primaryContainer = GreenPrimaryContainerDark,
    onPrimaryContainer = GreenOnPrimaryContainerDark,
    secondary = GreenSecondaryDark,
    onSecondary = GreenOnSecondaryDark,
    secondaryContainer = GreenSecondaryContainerDark,
    onSecondaryContainer = GreenOnSecondaryContainerDark,
    tertiary = GreenTertiaryDark,
    onTertiary = GreenOnTertiaryDark,
    tertiaryContainer = GreenTertiaryContainerDark,
    onTertiaryContainer = GreenOnTertiaryContainerDark,
    error = GreenErrorDark,
    onError = GreenOnErrorDark,
    background = GreenBackgroundDark,
    onBackground = GreenOnBackgroundDark,
    surface = GreenSurfaceDark,
    onSurface = GreenOnSurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = GreenPrimaryLight,
    onPrimary = GreenOnPrimaryLight,
    primaryContainer = GreenPrimaryContainerLight,
    onPrimaryContainer = GreenOnPrimaryContainerLight,
    secondary = GreenSecondaryLight,
    onSecondary = GreenOnSecondaryLight,
    secondaryContainer = GreenSecondaryContainerLight,
    onSecondaryContainer = GreenOnSecondaryContainerLight,
    tertiary = GreenTertiaryLight,
    onTertiary = GreenOnTertiaryLight,
    tertiaryContainer = GreenTertiaryContainerLight,
    onTertiaryContainer = GreenOnTertiaryContainerLight,
    error = GreenErrorLight,
    onError = GreenOnErrorLight,
    background = GreenBackgroundLight,
    onBackground = GreenOnBackgroundLight,
    surface = GreenSurfaceLight,
    onSurface = GreenOnSurfaceLight
)

@Composable
fun MinhaseconomiasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Desativado para forçar nosso tema verde
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
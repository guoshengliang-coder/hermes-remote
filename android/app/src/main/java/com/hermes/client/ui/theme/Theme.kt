package com.hermes.client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** App-wide flag for tool-call verbosity (true = Technical/show payloads, false = Product/hide). */
val LocalToolCallTechnical = compositionLocalOf { false }

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You is opt-in and acts only as a neutral base. Off by default so the curated
    // palette is the stable, screenshot-ready look.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> HermesDarkColors
        else -> HermesLightColors
    }
    // Transitional: LocalProfileAccent now always carries the BRAND accent (chrome no longer
    // follows the profile — identity lives in the avatar alone). Only the screens deleted in
    // the nav-rework phase still read it; this provider goes away with them.
    CompositionLocalProvider(LocalProfileAccent provides brandAccentColors(darkTheme)) {
        MaterialTheme(
            colorScheme = colors,
            typography = HermesTypography,
            shapes = HermesShapes,
            content = content,
        )
    }
}

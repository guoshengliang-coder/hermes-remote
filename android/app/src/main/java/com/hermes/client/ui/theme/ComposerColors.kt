package com.hermes.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// HG-254: independent of FAB colours; the approved dark composer is a little lighter.
internal val ComposerActionLight = Color(0xFF181C24)
internal val ComposerActionDark = Color(0xFF1E232B)
internal val ComposerActionInk = Color.White
internal val ComposerActionOutlineDark = Color(0xFF3A4049)

@Composable
internal fun composerActionColor(): Color = if (isDarkSurface()) ComposerActionDark else ComposerActionLight

@Composable
internal fun composerActionOutline(): Color = if (isDarkSurface()) ComposerActionOutlineDark else Color.Transparent

package com.hermes.client.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * App bar tinted by the active profile's accent (chrome-only per the design decision). The
 * soft `container` color fills the bar and the accent's on-color carries the title, so the
 * whole top of the screen reflects which tenant you're acting as — a glanceable isolation
 * signal the single-account apps can't offer. Falls back to the brand accent for no profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesTopBar(
    title: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    subtitle: String? = null,
    // Centered = the session-list root (M3 center-aligned bar); pushed screens stay start-aligned.
    centered: Boolean = false,
    // Content-row height of the centered bar. A parameter only so the session list can drive it
    // from its temporary tuning panel; ignored when [centered] is false.
    centeredHeight: androidx.compose.ui.unit.Dp = 48.dp,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val barBg = MaterialTheme.colorScheme.surface
    val barOn = MaterialTheme.colorScheme.onSurface
    val colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = barBg,
        titleContentColor = barOn,
        navigationIconContentColor = barOn,
        actionIconContentColor = barOn,
    )
    if (centered) {
        androidx.compose.material3.CenterAlignedTopAppBar(
            // 48dp, the mock's `h-12` (5th pull; `h-14` before it), against Material's 64dp
            // default. Only the centred bar — that is the one the session-list mock specifies
            // (docs/DESIGN.md §5.2). It still clears a 48dp touch target exactly, because this is
            // the content row alone.
            //
            // `expandedHeight`, NOT `Modifier.height`. The bar draws its own status-bar inset
            // inside its container, so constraining the whole composable spends most of the
            // budget on the inset and squashes the content row: on a HONOR CLK-AN00 the title was
            // vertically clipped and sat flush against the first group header. The mock has the
            // same split — `h-12` is the content row, `pt-safe` is separate.
            expandedHeight = centeredHeight,
            modifier = modifier,
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = barBg,
                titleContentColor = barOn,
                navigationIconContentColor = barOn,
                actionIconContentColor = barOn,
            ),
            title = {
                Text(title, style = com.hermes.client.ui.theme.SessionsTopBarTitle, color = barOn)
            },
            navigationIcon = navigationIcon,
            actions = actions,
        )
        return
    }
    TopAppBar(
        modifier = modifier,
        colors = colors,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = barOn)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

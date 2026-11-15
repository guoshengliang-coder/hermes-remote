package com.hermes.client.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The real composer surface, also used by nested-theme rendering fixtures. */
@Composable
internal fun ComposerSurface(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(if (expanded) 28.dp else 30.dp),
        tonalElevation = 1.dp,
        shadowElevation = 7.dp,
        modifier = modifier,
        content = content,
    )
}

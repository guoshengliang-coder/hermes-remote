package com.hermes.client.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.SendUpIcon
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.ComposerActionInk
import com.hermes.client.ui.theme.composerActionColor
import com.hermes.client.ui.theme.composerActionOutline

/** One 48dp slot in expanded, compact and voice composers; callers retain action ownership. */
@Composable
internal fun ComposerActionButton(
    isStop: Boolean,
    enabled: Boolean,
    language: AppLanguage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = if (enabled) composerActionColor() else Color.Transparent,
        border = if (enabled) BorderStroke(1.dp, composerActionOutline()) else null,
        modifier = modifier.size(48.dp),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = if (isStop) Icons.Rounded.Stop else SendUpIcon,
                contentDescription = if (isStop) localized(language, "停止", "Stop") else localized(language, "发送", "Send"),
                modifier = Modifier.size(24.dp),
                tint = if (enabled) ComposerActionInk else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

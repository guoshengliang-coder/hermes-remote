package com.hermes.client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ShortText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.hermesSheetState
import com.hermes.client.ui.localization.l10n

/**
 * Format picker for "share transcript": pasting a wall of text is only one of three sensible
 * outcomes, so the action asks first and then hands off to the system share sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareTranscriptSheet(
    onText: () -> Unit,
    onMarkdown: () -> Unit,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState()) {
        Text(
            l10n("分享对话", "Share transcript"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
        )
        ShareFormatRow(
            icon = Icons.Rounded.ShortText,
            title = l10n("文字", "Plain text"),
            subtitle = l10n("直接粘贴到聊天，适合短对话", "Paste straight into a chat — best for short conversations"),
            onClick = onText,
        )
        ShareFormatRow(
            icon = Icons.Rounded.Description,
            title = l10n("Markdown 文件", "Markdown file"),
            subtitle = l10n("保留格式，适合归档或再编辑", "Keeps formatting — best for archiving or editing"),
            onClick = onMarkdown,
        )
        ShareFormatRow(
            icon = Icons.Rounded.Image,
            title = l10n("长图", "Image"),
            subtitle = l10n("一张图发出去，适合直接给人看", "One picture to send — best for reading right away"),
            onClick = onImage,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
    }
}

@Composable
private fun ShareFormatRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

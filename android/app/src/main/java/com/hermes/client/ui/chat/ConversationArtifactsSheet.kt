package com.hermes.client.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.client.domain.ChatFile
import com.hermes.client.domain.ChatImage
import com.hermes.client.ui.components.LoadingDots
import com.hermes.client.ui.components.SheetCloseHandle
import com.hermes.client.ui.components.hermesSheetState
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.localization.localizedMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationArtifactsSheet(
    artifacts: List<ConversationArtifact>, history: OlderHistoryUiState, listState: LazyListState,
    onRetry: () -> Unit, onOpenImage: (String, ChatImage) -> Unit,
    onOpenFile: (ChatFile) -> Unit, onShareFile: (ChatFile) -> Unit,
    onContext: (ConversationArtifact) -> Unit, onDismiss: () -> Unit,
    initialHistoryError: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = hermesSheetState(),
        sheetGesturesEnabled = false, dragHandle = { SheetCloseHandle(onDismiss) },
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = MaterialTheme.colorScheme.surface) {
        ConversationArtifactsContent(artifacts, history, listState, onRetry, onOpenImage, onOpenFile, onShareFile, onContext, onDismiss, initialHistoryError)
    }
}

@Composable
internal fun ConversationArtifactsContent(
    artifacts: List<ConversationArtifact>, history: OlderHistoryUiState, listState: LazyListState,
    onRetry: () -> Unit, onOpenImage: (String, ChatImage) -> Unit,
    onOpenFile: (ChatFile) -> Unit, onShareFile: (ChatFile) -> Unit,
    onContext: (ConversationArtifact) -> Unit, onDismiss: () -> Unit,
    initialHistoryError: String? = null,
) {
    val language = LocalAppLanguage.current
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(localized(language, "产物", "Artifacts"), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, localized(language, "关闭", "Close")) }
        }
        Text(localized(language, "显示文件当前内容，同一路径再次交付可能更新内容。", "Files show their current content; delivering the same path again may update it."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (history.loading) item(key = "loading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingDots(size = 20.dp)
                    Text(localized(language, "正在整理整个会话的产物…", "Collecting artifacts from the entire conversation…"),
                        modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            history.error?.let { error -> item(key = "error") {
                Column {
                    Text(error.localizedMessage(language), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (error.retryable) TextButton(onClick = onRetry) { Text(localized(language, "重试", "Retry")) }
                }
            } }
            if (initialHistoryError != null) item(key = "initial-error") {
                Column {
                    Text(initialHistoryError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text(localized(language, "重试", "Retry")) }
                }
            }
            if (!history.loading && history.error == null && initialHistoryError == null && artifacts.isEmpty()) item(key = "empty") {
                Text(localized(language, "暂无产物", "No artifacts yet"), modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(artifacts, key = { it.reference }) { artifact ->
                artifact.file?.let { file ->
                    ChatFileList(
                        files = listOf(file.copy(name = artifact.displayName)),
                        onOpen = onOpenFile,
                        onShare = onShareFile,
                        showPlaybackHint = false,
                        footer = { ArtifactFooter(artifact, language, onContext) },
                    )
                } ?: artifact.image?.let { image ->
                    // Images have no attachment card of their own; give them the same shell so a
                    // delivery reads as one unit whichever kind of file it carries (HG-233).
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.heightIn(max = 180.dp)) { ChatImageGrid(listOf(image)) { onOpenImage(artifact.messageId, it) } }
                            Text(artifact.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ArtifactFooter(artifact, language, onContext)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The foot of one delivery card: when it arrived and the way back to the message that produced
 * it. It sits inside the card so the time belongs to this delivery instead of floating between
 * two cards (user decision 2026-10-30).
 */
@Composable
private fun ArtifactFooter(
    artifact: ConversationArtifact,
    language: AppLanguage,
    onContext: (ConversationArtifact) -> Unit,
) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                artifact.timestamp?.let { formatTimeSeparator(it, language) }
                    ?: localized(language, "时间未知", "Time unknown"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                localized(language, "查看上下文", "View context"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { onContext(artifact) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

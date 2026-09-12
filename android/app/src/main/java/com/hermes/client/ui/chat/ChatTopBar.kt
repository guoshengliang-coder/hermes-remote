package com.hermes.client.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.ui.components.HermesMark
import com.hermes.client.ui.components.PromptListIcon
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * The chat screen's top bar — `[返回] 标题 [＋新建对话] [⋮更多]` (docs/DESIGN.md §5.4, HG-5).
 * Bare 48dp icons on the content background: floating containers read as separate controls
 * fighting the transcript.
 *
 * Lifted out of `ChatScreen`'s `Scaffold(topBar = …)` by HG-37, for the same reason
 * `ChatsTopBar` sits apart from the session list: a bar that carries a visibility *rule* needs
 * a screenshot golden, and a bar welded into a screen that only builds under Hilt cannot have
 * one. Everything here is plain data and callbacks; the overflow's expanded state is the one
 * piece of state it owns, because nobody outside reads it.
 *
 * [actionsVisible] is that rule (HG-37): an empty new session shows neither ＋ nor ⋮. ＋ there
 * would point at the session the user is already sitting in, and all five menu items — search,
 * my prompts, refresh, share, archive — act on a transcript that does not exist yet. Decide it
 * with [chatTopBarActionsVisible] so this bar and the greeting overlay never disagree.
 */
@Composable
internal fun ChatTopBar(
    title: String,
    actionsVisible: Boolean,
    creatingNewChat: Boolean,
    refreshingConversation: Boolean,
    promptsLabel: String,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    onSearch: () -> Unit,
    onPrompts: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    var transcriptMenu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bare 48dp icon (M3 top-bar convention): the floating white containers read as
        // separate controls fighting the content; naked icons blend into the bar.
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = localized(language, "返回", "Back"),
                modifier = Modifier.offset(x = (-4).dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = adaptiveSessionTitleSize(title).sp,
                    lineHeight = (adaptiveSessionTitleSize(title) + 4).sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle()
        }
        // Fades on the same 150ms beat as the new-session greeting it disappears with
        // (docs/DESIGN.md §5.10), so the screen settles once rather than twice.
        AnimatedVisibility(
            visible = actionsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The top bar carries the one highest-frequency action; search moved into the
                // menu below (docs/DESIGN.md §5.4, HG-5). Reading an answer and wanting to start
                // the next thing is the common case, and it used to cost a trip back to the list.
                IconButton(onClick = { if (!creatingNewChat) onNewChat() }, enabled = !creatingNewChat) {
                    if (creatingNewChat) {
                        HermesMark(size = 20.dp)
                    } else {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = localized(language, "新建对话", "New conversation"),
                            modifier = Modifier.offset(x = 4.dp),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { transcriptMenu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = localized(language, "更多", "More"),
                            modifier = Modifier.offset(x = (-4).dp),
                        )
                    }
                    DropdownMenu(
                        expanded = transcriptMenu,
                        onDismissRequest = { transcriptMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        // Navigation before actions (docs/DESIGN.md §5.4). Search leads: it
                        // lost its top-bar slot to 新建对话, so it must be the first thing
                        // found here.
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, Modifier.size(20.dp)) },
                            text = { Text(localized(language, "搜索对话", "Search this chat")) },
                            onClick = {
                                transcriptMenu = false
                                onSearch()
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(PromptListIcon, contentDescription = null, Modifier.size(20.dp)) },
                            text = { Text(promptsLabel) },
                            onClick = {
                                transcriptMenu = false
                                onPrompts()
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                if (refreshingConversation) {
                                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                        HermesMark(size = 20.dp)
                                    }
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(20.dp))
                                }
                            },
                            text = { Text(localized(language, "刷新对话", "Refresh conversation")) },
                            enabled = !refreshingConversation,
                            onClick = {
                                transcriptMenu = false
                                onRefresh()
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, Modifier.size(20.dp)) },
                            text = { Text(localized(language, "分享对话", "Share transcript")) },
                            onClick = {
                                transcriptMenu = false
                                onShare()
                            },
                        )
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Rounded.Archive, contentDescription = null, Modifier.size(20.dp)) },
                            text = { Text(localized(language, "归档对话", "Archive conversation")) },
                            onClick = {
                                transcriptMenu = false
                                onArchive()
                            },
                        )
                    }
                }
            }
        }
    }
}

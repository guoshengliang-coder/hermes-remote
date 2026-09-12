package com.hermes.client.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.domain.Session
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.ListLoadingState
import com.hermes.client.ui.components.SearchField
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Pick conversations to reference (docs/SESSION_EXCHANGE_REQUIREMENTS.md §3).
 *
 * A full-screen `Dialog`, not a bottom sheet: multi-select needs to scroll, to search, and to show
 * a running count, and §5.8 of DESIGN.md already concedes that a scrollable sheet has to disable
 * its own gestures to work at all. It is a Dialog rather than a nav destination because that is
 * how this screen already hosts full-screen surfaces that return a value (`ImageEditorDialog`),
 * and because the composer's draft and staged attachments stay alive underneath it.
 *
 * Scope, exclusions and the selection cap are [sessionPickerCandidates] / [pickerRowEnabled] —
 * pure, and tested without a gateway.
 */
@Composable
internal fun SessionPickerDialog(
    /** How many more attachments the composer can take; the selection cannot exceed it (§3.5). */
    remainingSlots: Int,
    /** The conversation being composed in — never offered as a choice. */
    excludeSessionId: String?,
    onCancel: () -> Unit,
    onPicked: (List<Session>) -> Unit,
    vm: SessionPickerViewModel = hiltViewModel(),
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        SessionPickerContent(remainingSlots, excludeSessionId, onCancel, onPicked, vm)
    }
}

@Composable
private fun SessionPickerContent(
    remainingSlots: Int,
    excludeSessionId: String?,
    onCancel: () -> Unit,
    onPicked: (List<Session>) -> Unit,
    vm: SessionPickerViewModel,
) {
    val language = LocalAppLanguage.current
    val state by vm.state.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }

    // Archived conversations join the list only once there is something to search for (§3.2).
    LaunchedEffect(query) { if (query.isNotBlank()) vm.ensureArchivedLoaded() }

    val archivedIds = remember(state.archived) { state.archived.mapTo(mutableSetOf()) { it.id } }
    val rows = remember(state.sessions, state.archived, activeProfile, excludeSessionId, query) {
        val pool = if (query.isBlank()) state.sessions else state.sessions + state.archived
        sessionPickerCandidates(pool, activeProfile, excludeSessionId)
            .filter { matchesPickerQuery(it, query) }
            .distinctBy { it.id }
    }

    BackHandler(onBack = onCancel)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = localized(language, "返回", "Back"),
                    )
                }
                Text(
                    localized(language, "选择会话", "Pick conversations"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp).weight(1f),
                )
            }
            SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = localized(language, "搜索会话标题", "Search conversation titles"),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f)) {
                when {
                    state.loading && state.sessions.isEmpty() -> ListLoadingState()
                    state.error != null && state.sessions.isEmpty() ->
                        ErrorState(error = state.error!!, onRetry = { vm.load() })
                    rows.isEmpty() && query.isNotBlank() ->
                        EmptyState(title = localized(language, "没有匹配的会话", "No conversation matches"))
                    rows.isEmpty() ->
                        EmptyState(
                            title = localized(language, "这个身份下还没有可引用的会话", "No conversation here to reference yet"),
                            subtitle = localized(
                                language,
                                "发过消息的会话才能被引用。",
                                "A conversation can be referenced once it has messages.",
                            ),
                        )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.id }) { session ->
                            val enabled = pickerRowEnabled(session.id, selected, remainingSlots)
                            SessionPickerRow(
                                session = session,
                                checked = session.id in selected,
                                enabled = enabled,
                                archived = session.id in archivedIds,
                                onToggle = {
                                    selected = if (session.id in selected) selected - session.id
                                    else selected + session.id
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    // Says the ceiling before it is hit, never after (§3.5).
                    localized(
                        language,
                        "最多再选 ${pickerSelectableCount(remainingSlots, selected.size)} 个",
                        "${pickerSelectableCount(remainingSlots, selected.size)} more can be added",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onPicked(rows.filter { it.id in selected }) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text(localized(language, "添加 ${selected.size} 个会话", "Add ${selected.size}"))
                }
            }
        }
    }
}

/** One selectable conversation. `internal` so a screenshot test renders the real row, not a copy. */
@Composable
internal fun SessionPickerRow(
    session: Session,
    checked: Boolean,
    enabled: Boolean,
    archived: Boolean,
    onToggle: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole row dims, not just the checkbox: a title at full strength beside a pale box
        // reads as "tap here", and this row is precisely the one that will not respond.
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (archived) {
                    Text(
                        localized(language, "已归档", "Archived"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            // The same subline component as the Chats list, search results, the project drill-down
            // and the archive — five consumers now, one truth about what a conversation row says.
            SessionSubline(session)
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
    }
}

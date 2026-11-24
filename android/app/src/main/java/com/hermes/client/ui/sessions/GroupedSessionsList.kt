package com.hermes.client.ui.sessions

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.repository.SessionReadStore
import com.hermes.client.domain.Session
import com.hermes.client.ui.components.SectionHeader
import com.hermes.client.ui.components.SectionTone
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/** The production Chats list, independent of the screen's network, dialogs and reveal effects. */
@Composable
internal fun GroupedSessionsList(
    sessions: List<Session>,
    isPinned: (Session) -> Boolean,
    runtimeFor: (Session) -> SessionRuntime?,
    unreadTokens: Set<String>,
    draftTokens: Set<String>,
    unsentTokens: Set<String>,
    defaultProjectPath: String?,
    selectedSession: String? = null,
    selectedDevice: String? = null,
    selectedProfile: String? = null,
    listState: LazyListState = rememberLazyListState(),
    nowMs: Long = System.currentTimeMillis(),
    onOpen: (Session) -> Unit,
    onMoveToProject: (Session) -> Unit,
    onTogglePin: (Session) -> Unit,
    onRename: suspend (Session, String) -> com.hermes.client.data.error.AppError?,
    onArchive: suspend (Session) -> com.hermes.client.data.error.AppError?,
    onDelete: suspend (Session) -> com.hermes.client.data.error.AppError?,
    onRefreshMutation: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    val (needsYou, others) = splitNeedsYou(sessions) { runtimeFor(it)?.phase }
    val pinned = others.filter(isPinned)
    val groups = groupByRecency(others.filterNot(isPinned), nowMs)
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val sections = listOf(
        SessionListSection("needs", localized(language, "需要你处理", "Needs you"), SectionTone.NEEDS_YOU, needsYou),
        SessionListSection("pinned", localized(language, "已置顶", "Pinned"), SectionTone.PINNED, pinned,
            localized(language, "仅此设备", "Device only")),
        SessionListSection("today", localized(language, "今天", "Today"), SectionTone.TODAY, groups.today),
        SessionListSection("yesterday", localized(language, "昨天", "Yesterday"), SectionTone.YESTERDAY, groups.yesterday),
        SessionListSection("week", localized(language, "前 7 天", "Previous 7 days"), SectionTone.RECENT, groups.week),
        SessionListSection("earlier", localized(language, "更早", "Earlier"), SectionTone.OLDER, groups.earlier),
    )
    LazyColumn(state = listState) {
        sections.filter { it.sessions.isNotEmpty() }.forEach { section ->
            item(key = "h-${section.id}") {
                SectionHeader(
                    section.label, section.sessions.size, section.tone,
                    note = section.note,
                    collapsed = section.id in collapsed,
                    onToggle = {
                        collapsed = if (section.id in collapsed) collapsed - section.id else collapsed + section.id
                    },
                )
            }
            if (section.id !in collapsed) {
                // HG-252: identity survives a move between sections. Do not animate these
                // transparent rows: disappearing content and moving neighbours can overlap.
                items(section.sessions, key = ::sessionListItemKey) { session ->
                    val token = SessionReadStore.token(session.profile, session.id, session.deviceId)
                    SessionRow(
                        session = session,
                        selected = session.id == selectedSession && session.deviceId == selectedDevice && session.profile == selectedProfile,
                        isPinned = isPinned(session),
                        defaultProjectPath = defaultProjectPath,
                        runtime = runtimeFor(session),
                        unread = token in unreadTokens,
                        hasDraft = token in draftTokens,
                        hasUnsent = token in unsentTokens,
                        onOpen = { onOpen(session) },
                        onMoveToProject = { onMoveToProject(session) },
                        onTogglePin = { onTogglePin(session) },
                        onRename = { onRename(session, it) },
                        onArchive = { onArchive(session) },
                        onDelete = { onDelete(session) },
                        onRefreshMutation = onRefreshMutation,
                    )
                }
            }
        }
    }
}

private data class SessionListSection(
    val id: String,
    val label: String,
    val tone: SectionTone,
    val sessions: List<Session>,
    val note: String? = null,
)

/** Bundle-saveable, unambiguous UI identity; no group/state/title/timestamp or disk-key migration. */
internal fun sessionListItemKey(session: Session): String = listOf(
    session.deviceId.orEmpty(),
    session.profile?.ifBlank { "default" } ?: "default",
    session.id,
).joinToString(separator = "", prefix = "session:") { "${it.length}:$it" }

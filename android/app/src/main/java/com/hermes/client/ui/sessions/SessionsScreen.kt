package com.hermes.client.ui.sessions

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha
import com.hermes.client.ui.localization.localizedMessage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.domain.Session
import com.hermes.client.data.progress.SessionRunPhase
import com.hermes.client.data.progress.SessionRuntime
import com.hermes.client.data.progress.isActive
import com.hermes.client.data.repository.SessionReadStore
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.theme.StatusTone
import com.hermes.client.ui.theme.statusColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: SessionsViewModel = hiltViewModel(),
    onOpen: (ChatLaunch) -> Unit,
    onOpenCard: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenArchived: () -> Unit = {},
    onOpenCron: () -> Unit = {},
    onOpenMessaging: () -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pinnedTokens by vm.pinnedTokens.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val showBots = remember(state.configuredChannels, state.botSessions) {
        showBotsTab(state.configuredChannels, state.botSessions.size)
    }
    // The channel count decides whether the segment exists; the sessions fill it.
    LaunchedEffect(Unit) { vm.refreshChannelCount() }
    LaunchedEffect(viewMode) { if (viewMode == ViewMode.BOTS) vm.loadBots() }
    // A segment that disappears (last channel removed, history archived) must not strand the user
    // on an empty view.
    LaunchedEffect(showBots, viewMode) {
        if (!showBots && viewMode == ViewMode.BOTS) vm.setViewMode(ViewMode.SESSIONS)
    }
    val runtimes by vm.runtimes.collectAsStateWithLifecycle()
    val unreadTokens by vm.unreadTokens.collectAsStateWithLifecycle()
    val defaultProjectPath by vm.defaultProjectPath.collectAsStateWithLifecycle()
    // Session whose「移动到项目…」picker is open (from the long-press menu).
    var moveTarget by remember { mutableStateOf<Session?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    // The Chats list always creates in the gateway's launch directory — the default project.
    // Creating INTO a project is the Projects page's job (docs/DESIGN.md §5.3).
    val creator = rememberSessionCreator(vm, activeProfile, onOpen)
    val openExisting = rememberSessionOpener(vm, onOpen)

    // I1: route to Setup when a 401 is received
    LaunchedEffect(state.unauthorized) {
        if (state.unauthorized) onUnauthorized()
    }

    // A profile switch is a gateway write; on failure the UI stays on the old profile — say so.
    val switchFailed by vm.switchFailed.collectAsStateWithLifecycle()
    LaunchedEffect(switchFailed) {
        switchFailed?.let {
            Toast.makeText(context, localized(language, "切换身份失败，仍在当前身份", "Couldn't switch profile — staying on the current one"), Toast.LENGTH_SHORT).show()
            vm.clearSwitchFailed()
        }
    }


    // Re-fetch on every resume — notably when returning from a chat. The "sessions" nav entry
    // (and its ViewModel) stays alive across navigation, so init() runs only once; without this
    // a session created or updated while in a chat never appears until a profile switch or app
    // restart. Mirrors the same ON_RESUME refresh used by CronScreen.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        vm.onVisible()
    }

    Scaffold(
        topBar = {
            Column {
                ChatsTopBar(
                    activeProfile = activeProfile,
                    onOpenCard = onOpenCard,
                    onOpenSearch = onOpenSearch,
                    onOpenProjects = onOpenProjects,
                    onOpenArchived = onOpenArchived,
                )
                // Zero or two segments: without a messaging channel there is nothing to switch
                // between, so the row is absent entirely (docs/DESIGN.md §5.16).
                val modes = remember(showBots) { chatsSegmentModes(showBots) }
                if (modes.isNotEmpty()) {
                    ChatsSegmentedRow(
                        tabs = modes.map {
                            it to when (it) {
                                ViewMode.SESSIONS -> localized(language, "会话", "Chats")
                                ViewMode.BOTS -> localized(language, "机器人", "Bots")
                            }
                        },
                        selected = viewMode,
                        onSelect = { vm.setViewMode(it) },
                    )
                }
            }
        },
        floatingActionButton = {
            // Bot conversations are started by the other side; nothing to create here.
            if (viewMode == ViewMode.BOTS) return@Scaffold
            NewSessionFab(creator, cwd = null)
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewMode == ViewMode.BOTS) {
                // ── Bots: what Hermes has been saying on other apps. Rows open the ordinary chat
                // screen — there is no second transcript renderer any more (docs/DESIGN.md §5.16).
                val sections = remember(state.botSessions) { botSections(state.botSessions) }
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.botsLoading && state.botSessions.isEmpty() ->
                            com.hermes.client.ui.components.ListLoadingState()
                        state.botError != null ->
                            com.hermes.client.ui.components.ErrorState(
                                error = state.botError!!,
                                onRetry = { vm.loadBots() },
                            )
                        sections.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "还没有机器人对话", "No bot conversations yet"),
                                subtitle = localized(
                                    language,
                                    "别人在钉钉、Slack 这些应用里找 Hermes 聊过之后，记录会出现在这里。",
                                    "Once someone talks to Hermes on DingTalk, Slack or another app, the record shows up here.",
                                ),
                            )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            sections.forEach { section ->
                                item(key = "bot-hdr-${section.source}") {
                                    Text(
                                        botSourceLabel(section.source),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(
                                            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp,
                                        ),
                                    )
                                }
                                items(section.sessions, key = { "bot-${it.id}" }) { s ->
                                    ListItem(
                                        headlineContent = { Text(s.title) },
                                        supportingContent = {
                                            Text(
                                                localized(language, "${s.messageCount} 条", "${s.messageCount} messages"),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        },
                                        modifier = Modifier.clickable { onOpen(ChatLaunch.existing(s)) },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Sessions mode ───────────────────────────────────────────────────────────
                // Cron alert strip: HealthStrip's pattern — only rendered when something needs
                // attention, tap goes to the cron screen.
                if (health.total > 0) {
                    val label = when {
                        // One outage, named, with its fallout — not a count of symptoms.
                        health.channels.size == 1 && health.standaloneCronJobs == 0 -> {
                            val channel = health.channels.single()
                            if (channel.affectedJobs > 0) {
                                localized(
                                    language,
                                    "${channel.name} 未连接 · ${channel.affectedJobs} 个定时任务受影响",
                                    "${channel.name} is not connected · ${channel.affectedJobs} scheduled job(s) affected",
                                )
                            } else {
                                localized(
                                    language,
                                    "${channel.name} 未连接",
                                    "${channel.name} is not connected",
                                )
                            }
                        }
                        health.channels.isEmpty() -> localized(
                            language,
                            "${health.standaloneCronJobs} 个定时任务需要处理",
                            "${health.standaloneCronJobs} scheduled job(s) need attention",
                        )
                        else -> localized(
                            language,
                            "${health.total} 项需要处理",
                            "${health.total} things need attention",
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            // Root cause first: when a channel is down that is where the fix is.
                            .clickable { if (health.hasChannelCause) onOpenMessaging() else onOpenCron() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (health.hasChannelCause) Icons.Rounded.Forum else Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                // Reveal newly promoted 需要你处理 sessions: LazyColumn's scroll anchoring
                // otherwise leaves them hidden above the viewport (see NeedsYouReveal.kt).
                val sessionsListState = rememberLazyListState()
                val revealScope = rememberCoroutineScope()
                var needsYouPill by remember { mutableStateOf(0) }
                val needsYouIds = remember(state.sessions, runtimes) {
                    splitNeedsYou(state.sessions) { s -> vm.runtimeFor(s, runtimes)?.phase }
                        .first.map { "${it.profile.orEmpty()}:${it.id}" }.toSet()
                }
                var revealedNeedsYou by remember { mutableStateOf<Set<String>?>(null) }
                LaunchedEffect(needsYouIds) {
                    val previous = revealedNeedsYou
                    revealedNeedsYou = needsYouIds
                    if (needsYouIds.isEmpty()) needsYouPill = 0
                    if (previous == null) return@LaunchedEffect  // first composition: nothing is "new"
                    when (
                        needsYouRevealAction(
                            previous, needsYouIds,
                            sessionsListState.firstVisibleItemIndex,
                            sessionsListState.isScrollInProgress,
                        )
                    ) {
                        NeedsYouReveal.SCROLL_TO_TOP -> sessionsListState.animateScrollToItem(0)
                        NeedsYouReveal.SHOW_PILL -> needsYouPill = needsYouIds.size
                        NeedsYouReveal.NONE -> Unit
                    }
                }
                // The pill dissolves once the reader reaches the top on their own.
                LaunchedEffect(sessionsListState) {
                    snapshotFlow { sessionsListState.firstVisibleItemIndex }
                        .collect { if (it == 0) needsYouPill = 0 }
                }
                // Same anchoring problem as 需要你处理 above, with a deliberate action behind it:
                // a just-pinned row moves into the 已置顶 section at the top, which LazyColumn
                // inserts ABOVE the viewport. Unlike an unsolicited promotion this one was asked
                // for, so follow it up unconditionally instead of offering a pill (HG-11).
                val pinReveals by vm.pinRevealRequests.collectAsStateWithLifecycle()
                // Seeded with the count as it stands, so only a pin made while this list is on
                // screen scrolls it. The counter outlives the screen; without the seed, coming
                // back from a chat would replay the last pin and yank the reader to the top.
                var handledPinReveals by remember { mutableStateOf(pinReveals) }
                LaunchedEffect(pinReveals) {
                    if (pinReveals != handledPinReveals) {
                        handledPinReveals = pinReveals
                        sessionsListState.animateScrollToItem(0)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // Delegated properties do not smart-cast; the local also makes the
                    // "pins are known from here down" boundary explicit.
                    val pins = pinnedTokens
                    when {
                        // Pins unread: rendering now would draw a list with no 已置顶 section and
                        // then insert one above the viewport a beat later (HG-11).
                        pins == null || (state.loading && state.sessions.isEmpty()) ->
                            com.hermes.client.ui.components.ListLoadingState()
                        state.error != null && state.sessions.isEmpty() -> com.hermes.client.ui.components.ErrorState(
                            error = state.error!!,
                            onRetry = { vm.refresh() },
                        )
                        state.sessions.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "暂无会话", "No sessions yet"),
                                subtitle = localized(language, "点击右下角的加号开始对话。", "Tap the plus button to start a conversation."),
                                actionLabel = localized(language, "新建会话", "New session"),
                                onAction = { creator.create(null) },
                            )
                        else -> {
                            val isPinned = { s: Session ->
                                com.hermes.client.data.repository.PinStore.token(s.profile, s.id, s.deviceId) in pins
                            }
                            // Sessions blocked on the user jump the whole order — then pins,
                            // then plain recency.
                            val (needsYou, others) = splitNeedsYou(state.sessions) { s ->
                                vm.runtimeFor(s, runtimes)?.phase
                            }
                            val pinned = others.filter(isPinned)
                            val groups = groupByRecency(
                                others.filterNot(isPinned),
                                nowMs = System.currentTimeMillis(),
                            )

                            // Collapsible groups (Mission Control's pattern): tap the header to
                            // fold, the count stays visible so nothing silently disappears.
                            var collapsed by androidx.compose.runtime.saveable.rememberSaveable {
                                androidx.compose.runtime.mutableStateOf(emptyList<String>())
                            }
                            val toggle: (String) -> Unit = { k ->
                                collapsed = if (k in collapsed) collapsed - k else collapsed + k
                            }
                            LazyColumn(state = sessionsListState) {
                                if (needsYou.isNotEmpty()) {
                                    item(key = "h-needs") {
                                        SectionHeader(
                                            localized(language, "需要你处理", "Needs you"), needsYou.size,
                                            collapsed = "needs" in collapsed, onToggle = { toggle("needs") },
                                        )
                                    }
                                    if ("needs" !in collapsed) {
                                        items(needsYou, key = { "n-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = isPinned(s), defaultProjectPath = defaultProjectPath, onMoveToProject = { moveTarget = s },
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id, s.deviceId) in unreadTokens,
                                                onOpen = { openExisting(s) },
                                                onTogglePin = { vm.togglePin(s) },
                                                onRename = { vm.rename(s, it) },
                                                onArchive = { vm.archive(s) },
                                                onDelete = { vm.delete(s) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                                if (pinned.isNotEmpty()) {
                                    item(key = "h-pinned") {
                                        SectionHeader(
                                            localized(language, "已置顶", "Pinned"), pinned.size,
                                            note = localized(language, "仅此设备", "Device only"),
                                            collapsed = "pinned" in collapsed, onToggle = { toggle("pinned") },
                                        )
                                    }
                                    if ("pinned" !in collapsed) {
                                        items(pinned, key = { "p-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = true, defaultProjectPath = defaultProjectPath, onMoveToProject = { moveTarget = s },
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id, s.deviceId) in unreadTokens,
                                                onOpen = { openExisting(s) },
                                                onTogglePin = { vm.togglePin(s) },
                                                onRename = { vm.rename(s, it) },
                                                onArchive = { vm.archive(s) },
                                                onDelete = { vm.delete(s) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                                if (groups.today.isNotEmpty()) {
                                    item(key = "h-today") {
                                        SectionHeader(
                                            localized(language, "今天", "Today"), groups.today.size,
                                            collapsed = "today" in collapsed, onToggle = { toggle("today") },
                                        )
                                    }
                                    if ("today" !in collapsed) {
                                        items(groups.today, key = { "today-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = false, defaultProjectPath = defaultProjectPath, onMoveToProject = { moveTarget = s },
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id, s.deviceId) in unreadTokens,
                                                onOpen = { openExisting(s) },
                                                onTogglePin = { vm.togglePin(s) },
                                                onRename = { vm.rename(s, it) },
                                                onArchive = { vm.archive(s) },
                                                onDelete = { vm.delete(s) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                                if (groups.week.isNotEmpty()) {
                                    item(key = "h-week") {
                                        SectionHeader(
                                            localized(language, "前 7 天", "Previous 7 days"), groups.week.size,
                                            collapsed = "week" in collapsed, onToggle = { toggle("week") },
                                        )
                                    }
                                    if ("week" !in collapsed) {
                                        items(groups.week, key = { "week-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = false, defaultProjectPath = defaultProjectPath, onMoveToProject = { moveTarget = s },
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id, s.deviceId) in unreadTokens,
                                                onOpen = { openExisting(s) },
                                                onTogglePin = { vm.togglePin(s) },
                                                onRename = { vm.rename(s, it) },
                                                onArchive = { vm.archive(s) },
                                                onDelete = { vm.delete(s) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                                if (groups.earlier.isNotEmpty()) {
                                    item(key = "h-earlier") {
                                        SectionHeader(
                                            localized(language, "更早", "Earlier"), groups.earlier.size,
                                            collapsed = "earlier" in collapsed, onToggle = { toggle("earlier") },
                                        )
                                    }
                                    if ("earlier" !in collapsed) {
                                        items(groups.earlier, key = { "earlier-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = false, defaultProjectPath = defaultProjectPath, onMoveToProject = { moveTarget = s },
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id, s.deviceId) in unreadTokens,
                                                onOpen = { openExisting(s) },
                                                onTogglePin = { vm.togglePin(s) },
                                                onRename = { vm.rename(s, it) },
                                                onArchive = { vm.archive(s) },
                                                onDelete = { vm.delete(s) },
                                                modifier = Modifier.animateItem(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (needsYouPill > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(18.dp),
                            shadowElevation = 4.dp,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                        ) {
                            Row(
                                Modifier
                                    .clickable {
                                        needsYouPill = 0
                                        revealScope.launch { sessionsListState.animateScrollToItem(0) }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    localized(language, "$needsYouPill 个会话需要处理", "$needsYouPill session(s) need you"),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                    if (state.loading && state.sessions.isNotEmpty()) {
                        // Content already on screen: a 2dp line at the top, never a cover.
                        com.hermes.client.ui.components.TopProgressLine(
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }

    moveTarget?.let { target ->
        val projects = remember(state.sessions, defaultProjectPath) {
            deriveProjectsFromSessions(state.sessions, defaultProjectPath)
        }
        ProjectPickerSheet(
            projects = projects,
            currentProjectId = projectOf(target, projects)?.id,
            onDismiss = { moveTarget = null },
            onPick = { project ->
                moveTarget = null
                val label = if (project.id == DEFAULT_PROJECT_ID) localized(language, "默认项目", "Default project") else project.label
                scope.launch {
                    val error = vm.moveToProject(target, project)
                    val text = error?.localizedMessage(language)
                        ?: localized(language, "已移动到 $label", "Moved to $label")
                    Toast.makeText(context, text, if (error != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    count: Int,
    note: String? = null,
    collapsed: Boolean = false,
    onToggle: (() -> Unit)? = null,
) {
    val language = LocalAppLanguage.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        note?.let {
            Text(
                "  ·  $it",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text(
            count.toString(),
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onToggle != null) {
            Icon(
                if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = if (collapsed) localized(language, "展开 $label", "Expand $label")
                else localized(language, "收起 $label", "Collapse $label"),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(
    session: Session,
    isPinned: Boolean,
    defaultProjectPath: String?,
    onMoveToProject: () -> Unit,
    runtime: SessionRuntime? = null,
    unread: Boolean = false,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingArchive by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val language = LocalAppLanguage.current
    val trailing: (@Composable () -> Unit)? = when (sessionRowTrailing(runtime, unread)) {
        SessionRowTrailing.RUNTIME -> ({ RuntimeIndicator(runtime!!) })
        SessionRowTrailing.UNREAD -> ({ UnreadIndicator() })
        SessionRowTrailing.NONE -> null
    }
    // Moving a running session is refused by the gateway (4009); grey the item out instead of
    // letting the tap fail.
    val moveEnabled = runtime?.hasActiveWork != true && runtime?.phase?.isActive != true

    ListItem(
            headlineContent = { Text(session.title) },
            // No leading slot: the pinned marker rides in the subline so every title shares one
            // left edge (docs/DESIGN.md §5.2). Project · model, then the live status line. No
            // profile text: the list is scoped to one profile and identity lives only in the
            // avatar (docs/DESIGN.md §1).
            supportingContent = {
                Column {
                    SessionSubline(session, defaultProjectPath = defaultProjectPath, pinned = isPinned)
                    runtime?.takeIf { it.phase != SessionRunPhase.IDLE || it.hasRunningProcesses }?.let { value ->
                        Text(
                            runtimeLabel(value, language),
                            style = MaterialTheme.typography.labelMedium,
                            color = runtimeColor(value.phase),
                        )
                    }
                }
            },
            trailingContent = trailing,
            // Tap opens the session; long-press opens the management menu.
            modifier = Modifier.combinedClickable(
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
    )

    if (menuOpen) {
        ModalBottomSheet(onDismissRequest = { menuOpen = false }, sheetState = com.hermes.client.ui.components.hermesSheetState()) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(if (isPinned) localized(language, "取消置顶", "Unpin") else localized(language, "置顶", "Pin")) },
                leadingContent = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                modifier = Modifier.clickable { menuOpen = false; onTogglePin() },
            )
            ListItem(
                headlineContent = { Text(localized(language, "重命名", "Rename")) },
                leadingContent = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                modifier = Modifier.clickable { menuOpen = false; renaming = true },
            )
            val currentLabel = projectLabelOf(session, defaultProjectPath)
                ?: localized(language, "默认项目", "Default project")
            ListItem(
                headlineContent = { Text(localized(language, "移动到项目…", "Move to project…")) },
                supportingContent = { Text(localized(language, "当前：$currentLabel", "Current: $currentLabel")) },
                leadingContent = {
                    Icon(com.hermes.client.ui.components.FolderStrokeIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                },
                modifier = Modifier
                    .alpha(if (moveEnabled) 1f else 0.38f)
                    .clickable(enabled = moveEnabled) { menuOpen = false; onMoveToProject() },
            )
            ListItem(
                headlineContent = { Text(localized(language, "归档", "Archive")) },
                leadingContent = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                modifier = Modifier.clickable { menuOpen = false; onArchive() },
            )
            ListItem(
                headlineContent = { Text(localized(language, "删除", "Delete"), color = MaterialTheme.colorScheme.error) },
                leadingContent = {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier.clickable { menuOpen = false; confirmingDelete = true },
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(20.dp))
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(localized(language, "重命名会话", "Rename session")) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { renaming = false; if (title.isNotBlank()) onRename(title.trim()) },
                ) { Text(localized(language, "保存", "Save")) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(localized(language, "取消", "Cancel")) } },
        )
    }

    if (confirmingArchive) {
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            title = { Text(localized(language, "归档这个对话？", "Archive this conversation?")) },
            text = {
                Text(
                    localized(
                        language,
                        "归档后它会从会话列表移到「已归档」，随时可以恢复。",
                        "It moves out of your conversation list into 已归档, and you can restore it any time.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingArchive = false; onArchive() }) {
                    Text(localized(language, "归档", "Archive"))
                }
            },
            dismissButton = { TextButton(onClick = { confirmingArchive = false }) { Text(localized(language, "取消", "Cancel")) } },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(localized(language, "删除会话？", "Delete session?")) },
            text = { Text(localized(language, "“${session.title}”将被永久删除。", "\"${session.title}\" will be permanently deleted.")) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text(localized(language, "删除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(localized(language, "取消", "Cancel")) } },
        )
    }
}

private fun runtimeLabel(runtime: SessionRuntime, language: com.hermes.client.ui.localization.AppLanguage): String {
    if (!runtime.phase.isActive && runtime.hasRunningProcesses) {
        val count = runtime.chat.backgroundProcesses.count { it.running }
        return localized(language, "后台任务运行中 · $count", "$count background task(s) running")
    }
    return when (runtime.phase) {
    SessionRunPhase.SUBMITTING -> localized(language, "正在发送…", "Sending…")
    SessionRunPhase.THINKING -> localized(language, "思考中…", "Thinking…")
    SessionRunPhase.STREAMING -> localized(language, "正在输出…", "Responding…")
    SessionRunPhase.USING_TOOL -> runtime.toolName?.let {
        localized(language, "正在使用 ${toolDisplayName(it, language)}…", "Using ${toolDisplayName(it, language)}…")
    } ?: localized(language, "正在使用工具…", "Using a tool…")
    SessionRunPhase.WAITING_APPROVAL -> localized(language, "等待你的确认", "Waiting for approval")
    SessionRunPhase.WAITING_CLARIFICATION -> localized(language, "等待你的回答", "Waiting for your answer")
    SessionRunPhase.WAITING_ATTENTION -> localized(language, "等待你处理", "Needs your attention")
    SessionRunPhase.RECONNECTING -> localized(language, "正在恢复连接…", "Reconnecting…")
    SessionRunPhase.COMPLETED_UNREAD -> localized(language, "已完成", "Completed")
    SessionRunPhase.FAILED -> localized(language, "运行失败", "Run failed")
    SessionRunPhase.INTERRUPTED -> localized(language, "已中断", "Interrupted")
    SessionRunPhase.IDLE -> ""
    }
}

private fun toolDisplayName(raw: String, language: com.hermes.client.ui.localization.AppLanguage): String = when {
    raw.contains("search", ignoreCase = true) -> localized(language, "搜索", "Search")
    raw.contains("browser", ignoreCase = true) -> localized(language, "浏览器", "Browser")
    raw.contains("terminal", ignoreCase = true) || raw.contains("shell", ignoreCase = true) -> localized(language, "终端", "Terminal")
    else -> raw.substringAfterLast('.').replace('_', ' ').take(18)
}

/** What the row's trailing slot shows. Pure so the terminal-state rule is unit-testable. */
internal enum class SessionRowTrailing { RUNTIME, UNREAD, NONE }

/**
 * Active work always shows its indicator; otherwise unread wins; a finished run shows the green
 * completed dot only. 已中断 / 运行失败 keep their status line but no dot: the neutral terminal
 * dot sat 1dp from the unread dot and read as unread (docs/DESIGN.md §5.2, decision 2026-09-02).
 */
internal fun sessionRowTrailing(runtime: SessionRuntime?, unread: Boolean): SessionRowTrailing = when {
    runtime?.hasActiveWork == true -> SessionRowTrailing.RUNTIME
    unread -> SessionRowTrailing.UNREAD
    runtime?.phase == SessionRunPhase.COMPLETED_UNREAD -> SessionRowTrailing.RUNTIME
    else -> SessionRowTrailing.NONE
}

/**
 * Which colour SOURCE a runtime phase draws from. Split out of [runtimeColor] so the mapping is
 * testable without a Compose runtime — in particular that COMPLETED_UNREAD no longer resolves to
 * the brand colour.
 */
internal enum class SessionStatusPaint { WAITING, FAILED, COMPLETED, NEUTRAL }

internal fun sessionStatusPaint(phase: SessionRunPhase): SessionStatusPaint = when (phase) {
    SessionRunPhase.WAITING_APPROVAL, SessionRunPhase.WAITING_CLARIFICATION,
    SessionRunPhase.WAITING_ATTENTION -> SessionStatusPaint.WAITING
    SessionRunPhase.FAILED -> SessionStatusPaint.FAILED
    SessionRunPhase.COMPLETED_UNREAD -> SessionStatusPaint.COMPLETED
    else -> SessionStatusPaint.NEUTRAL
}

@Composable
private fun runtimeColor(phase: SessionRunPhase) = when (sessionStatusPaint(phase)) {
    SessionStatusPaint.WAITING -> MaterialTheme.colorScheme.tertiary
    SessionStatusPaint.FAILED -> MaterialTheme.colorScheme.error
    // Deliberately NOT primary: with a blue brand, a blue "done" is indistinguishable from the
    // chrome around it (section headers, FAB). Green carries the status; see StatusColors.kt.
    SessionStatusPaint.COMPLETED -> statusColor(StatusTone.GOOD)
    SessionStatusPaint.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun RuntimeIndicator(runtime: SessionRuntime) {
    val phase = runtime.phase
    val color = runtimeColor(phase)
    if ((phase.isActive || runtime.hasRunningProcesses) &&
        phase !in setOf(
            SessionRunPhase.WAITING_APPROVAL,
            SessionRunPhase.WAITING_CLARIFICATION,
            SessionRunPhase.WAITING_ATTENTION,
        )
    ) {
        // Not the brand mark: this indicator's colour carries run status (§2.1 keeps status
        // colours independent of the brand), and a tinted H would blur the two systems.
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = color,
            strokeWidth = 2.dp,
        )
    } else {
        Box(
            Modifier
                .size(10.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape),
        )
    }
}

@Composable
private fun UnreadIndicator() {
    Box(
        Modifier
            .size(9.dp)
            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
    )
}


/**
 * Which segments the Chats row shows. The row carries CONTENT — which batch of chats you are
 * looking at — so it holds Bots and nothing else; Projects and Archive organise chats and live in
 * the overflow menu instead (docs/DESIGN.md §5.16, 2026-09-09). With no messaging channel there
 * is only one batch, so the row is empty and the caller renders nothing at all.
 */
internal fun chatsSegmentModes(showBots: Boolean): List<ViewMode> =
    if (showBots) listOf(ViewMode.SESSIONS, ViewMode.BOTS) else emptyList()

/**
 * `[avatar 36] 会话 [search][more]`. Search keeps a top-bar slot on purpose: it is how you find a
 * chat you cannot name, and burying it would cost a tap without buying any room. The overflow
 * holds the two pages that organise chats rather than show them — so the centred title now has
 * 52dp on the left against two actions on the right, which is why it has its own screenshot.
 */
@Composable
internal fun ChatsTopBar(
    activeProfile: String?,
    onOpenCard: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenArchived: () -> Unit,
) {
    val language = LocalAppLanguage.current
    var menuOpen by remember { mutableStateOf(false) }
    com.hermes.client.ui.components.HermesTopBar(
        title = localized(language, "会话", "Chats"),
        navigationIcon = {
            // The active profile's avatar IS the identity signal — and the door to
            // the card page, the app's only profile-switch point.
            IconButton(onClick = onOpenCard) {
                com.hermes.client.ui.components.ProfileAvatar(activeProfile, size = 36.dp)
            }
        },
        centered = true,
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Rounded.Search, contentDescription = localized(language, "搜索", "Search"))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = localized(language, "更多", "More"))
                }
                // Same menu shape as the chat screen's (docs/DESIGN.md §5.4): navigation first,
                // 20dp leading glyphs, 16dp corners on `surface`.
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(com.hermes.client.ui.components.FolderStrokeIcon, contentDescription = null, Modifier.size(20.dp))
                        },
                        text = { Text(localized(language, "项目", "Projects")) },
                        onClick = { menuOpen = false; onOpenProjects() },
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(com.hermes.client.ui.components.ArchiveBoxIcon, contentDescription = null, Modifier.size(20.dp))
                        },
                        text = { Text(localized(language, "已归档", "Archived")) },
                        onClick = { menuOpen = false; onOpenArchived() },
                    )
                }
            }
        },
    )
}

/**
 * The Chats segment row. Extracted so its width can be pinned by a screenshot test — at most two
 * labels now that Projects and Archive have moved out, but the tightest case (English at
 * fontScale 1.3) is still one that would only ever be noticed on a device.
 */
@Composable
internal fun ChatsSegmentedRow(
    tabs: List<Pair<ViewMode, String>>,
    selected: ViewMode,
    onSelect: (ViewMode) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        tabs.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = accent,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                // No check glyph: its appear/disappear used to shove the labels sideways on every
                // switch. Selection reads from the fill alone.
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

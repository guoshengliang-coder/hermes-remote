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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    vm: SessionsViewModel = hiltViewModel(),
    onOpen: (ChatLaunch) -> Unit,
    onOpenCard: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCron: () -> Unit = {},
    onUnauthorized: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pinnedTokens by vm.pinnedTokens.collectAsStateWithLifecycle()
    val archivedState by vm.archivedState.collectAsStateWithLifecycle()
    val cronAlerts by vm.cronAlerts.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val projectsState by vm.projectsState.collectAsStateWithLifecycle()
    val runtimes by vm.runtimes.collectAsStateWithLifecycle()
    val unreadTokens by vm.unreadTokens.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var creatingSession by rememberSaveable { mutableStateOf(false) }

    fun createSession() {
        if (creatingSession) return
        creatingSession = true
        scope.launch {
            try {
                vm.createSession()?.let { onOpen(ChatLaunch.new(it, activeProfile)) }
            } finally {
                creatingSession = false
            }
        }
    }

    fun openExisting(session: Session) {
        scope.launch {
            if (vm.prepareOpen(session)) {
                onOpen(ChatLaunch.existing(session))
            } else {
                Toast.makeText(context, localized(language, "无法切换到该会话所属身份，请稍后重试", "Could not switch to this session's profile. Try again."), Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        vm.refresh()
    }

    Scaffold(
        topBar = {
            Column {
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
                    },
                )
                val accent = MaterialTheme.colorScheme.primary
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val tabs = listOf(
                        ViewMode.SESSIONS to localized(language, "会话", "Sessions"),
                        ViewMode.PROJECTS to localized(language, "项目", "Projects"),
                        ViewMode.ARCHIVED to localized(language, "已归档", "Archived"),
                    )
                    tabs.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = viewMode == mode,
                            onClick = { vm.setViewMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = accent,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text(label) }
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = ::createSession,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                if (creatingSession) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = localized(language, "新建会话", "New session"),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (viewMode == ViewMode.PROJECTS) {
                Box(Modifier.fillMaxSize()) {
                    when {
                        projectsState.loading && projectsState.tree.isEmpty() ->
                            com.hermes.client.ui.components.LoadingState()
                        projectsState.error != null ->
                            com.hermes.client.ui.components.ErrorState(
                                message = projectsState.error!!,
                                onRetry = { vm.loadProjectTree() },
                            )
                        projectsState.scope != null ->
                            ProjectScopeView(
                                project = projectsState.scope!!,
                                onBack = { vm.exitProject() },
                                // Projects span profiles, so switch to the session's own profile
                                // (awaited) before opening, or the chat resumes against the wrong DB.
                                onOpenSession = ::openExisting,
                            )
                        projectsState.tree.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "暂无项目", "No projects"),
                                subtitle = localized(language, "在项目文件夹中运行的会话会显示在这里。", "Chats run in a project folder show up here."),
                                actionLabel = localized(language, "重新加载", "Reload"),
                                onAction = { vm.loadProjectTree() },
                            )
                        else -> ProjectOverview(projectsState.tree, onOpenProject = { vm.enterProject(it) })
                    }
                }
            } else if (viewMode == ViewMode.ARCHIVED) {
                // ── Archived mode (was its own pushed screen; now the third segment) ────────
                Box(Modifier.fillMaxSize()) {
                    when {
                        archivedState.loading && archivedState.sessions.isEmpty() ->
                            com.hermes.client.ui.components.LoadingState()
                        archivedState.error != null ->
                            com.hermes.client.ui.components.ErrorState(
                                message = archivedState.error!!,
                                onRetry = { vm.loadArchived() },
                            )
                        archivedState.sessions.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "暂无归档会话", "Nothing archived"),
                                subtitle = localized(language, "长按会话可将它归档。", "Long-press a session to archive it."),
                            )
                        else -> LazyColumn {
                            items(archivedState.sessions, key = { "a-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                ListItem(
                                    headlineContent = { Text(s.title) },
                                    supportingContent = { Text(s.model ?: "") },
                                    trailingContent = {
                                        IconButton(onClick = { vm.unarchive(s) }) {
                                            Icon(
                                                Icons.Rounded.Unarchive,
                                                contentDescription = localized(language, "取消归档", "Unarchive"),
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable { openExisting(s) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else {
                // ── Sessions mode ───────────────────────────────────────────────────────────
                // Cron alert strip: HealthStrip's pattern — only rendered when something needs
                // attention, tap goes to the cron screen.
                if (cronAlerts > 0) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable { onOpenCron() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Schedule, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            localized(language, "$cronAlerts 个定时任务需要处理", "$cronAlerts scheduled job(s) need attention"),
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
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.loading && state.sessions.isEmpty() -> com.hermes.client.ui.components.LoadingState()
                        state.error != null && state.sessions.isEmpty() -> com.hermes.client.ui.components.ErrorState(
                            message = state.error!!,
                            onRetry = { vm.refresh() },
                        )
                        state.sessions.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "暂无会话", "No sessions yet"),
                                subtitle = localized(language, "点击右下角的加号开始对话。", "Tap the plus button to start a conversation."),
                                actionLabel = localized(language, "新建会话", "New session"),
                                onAction = ::createSession,
                            )
                        else -> {
                            val isPinned = { s: Session ->
                                com.hermes.client.data.repository.PinStore.token(s.profile, s.id) in pinnedTokens
                            }
                            // Sessions blocked on the user jump the whole order — then pins,
                            // then plain recency.
                            val (needsYou, others) = splitNeedsYou(state.sessions) { s ->
                                vm.runtimeFor(s, runtimes)?.phase
                            }
                            val pinned = others.filter(isPinned)
                            val recent = sessionsByRecency(others.filterNot(isPinned))

                            // Collapsible groups (Mission Control's pattern): tap the header to
                            // fold, the count stays visible so nothing silently disappears.
                            var collapsed by androidx.compose.runtime.saveable.rememberSaveable {
                                androidx.compose.runtime.mutableStateOf(emptyList<String>())
                            }
                            val toggle: (String) -> Unit = { k ->
                                collapsed = if (k in collapsed) collapsed - k else collapsed + k
                            }
                            LazyColumn {
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
                                                session = s, isPinned = isPinned(s), profileCount = profiles.size,
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id) in unreadTokens,
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
                                        items(pinned, key = { "p-${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = true, profileCount = profiles.size,
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id) in unreadTokens,
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
                                if (recent.isNotEmpty()) {
                                    item(key = "h-recent") {
                                        SectionHeader(
                                            localized(language, "最近", "Recent"), recent.size,
                                            collapsed = "recent" in collapsed, onToggle = { toggle("recent") },
                                        )
                                    }
                                    if ("recent" !in collapsed) {
                                        items(recent, key = { "${it.profile.orEmpty()}:${it.id}" }) { s ->
                                            SessionRow(
                                                session = s, isPinned = false, profileCount = profiles.size,
                                                runtime = vm.runtimeFor(s, runtimes),
                                                unread = SessionReadStore.token(s.profile, s.id) in unreadTokens,
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
                    if (state.loading && state.sessions.isNotEmpty()) {
                        LinearProgressIndicator(
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                        )
                    }
                }
            }
        }
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
    profileCount: Int,
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
    val haptics = LocalHapticFeedback.current
    val language = LocalAppLanguage.current
    val trailing: (@Composable () -> Unit)? = when {
        runtime?.hasActiveWork == true -> ({ RuntimeIndicator(runtime) })
        unread -> ({ UnreadIndicator() })
        runtime != null && runtime.phase != SessionRunPhase.IDLE -> ({ RuntimeIndicator(runtime) })
        else -> null
    }
    val profileLabel = profileDisplayLabel(session.profile, profileCount, language)

    ListItem(
            headlineContent = { Text(session.title) },
            leadingContent = if (isPinned) {
                {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = localized(language, "已置顶", "Pinned"),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else null,
            // Pinned rows pool across profiles, so the tenant prefix stays to disambiguate;
            // grouped rows already sit under a profile header, so it would be redundant there.
            supportingContent = {
                Column {
                    listOfNotNull(profileLabel, session.model?.ifBlank { null })
                        .joinToString(" · ")
                        .takeIf { it.isNotBlank() }
                        ?.let { Text(it) }
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
        ModalBottomSheet(onDismissRequest = { menuOpen = false }) {
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

internal fun profileDisplayLabel(
    profile: String?,
    profileCount: Int,
    language: com.hermes.client.ui.localization.AppLanguage,
): String? {
    if (profileCount <= 1) return null
    val normalized = profile?.trim().orEmpty().ifBlank { "default" }
    return if (normalized.equals("default", ignoreCase = true)) {
        localized(language, "默认身份", "Default profile")
    } else {
        localized(language, "身份：$normalized", "Profile: $normalized")
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

@Composable
private fun runtimeColor(phase: SessionRunPhase) = when (phase) {
    SessionRunPhase.WAITING_APPROVAL, SessionRunPhase.WAITING_CLARIFICATION,
    SessionRunPhase.WAITING_ATTENTION ->
        MaterialTheme.colorScheme.tertiary
    SessionRunPhase.FAILED -> MaterialTheme.colorScheme.error
    SessionRunPhase.COMPLETED_UNREAD -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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

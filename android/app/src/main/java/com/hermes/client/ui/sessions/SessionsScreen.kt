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
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
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
import androidx.compose.material3.Surface
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
    val defaultProjectPath by vm.defaultProjectPath.collectAsStateWithLifecycle()
    val introSeen by vm.introSeen.collectAsStateWithLifecycle()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    // Session whose「移动到项目…」picker is open (from the long-press menu).
    var moveTarget by remember { mutableStateOf<Session?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var creatingSession by rememberSaveable { mutableStateOf(false) }
    var openRequestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var openRequestSerial by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

    fun createSession() {
        if (creatingSession) return
        creatingSession = true
        // One rule (docs/DESIGN.md §5.3): drilled into a real project, the new chat is created IN
        // that folder; everywhere else — Sessions segment, overview, the default project — it
        // lands in the gateway's launch directory, the default project. No inference, no picker.
        val targetCwd = projectsState.scope
            ?.takeIf { viewMode == ViewMode.PROJECTS && it.id != DEFAULT_PROJECT_ID }
            ?.path
        scope.launch {
            try {
                vm.createSession(targetCwd)?.let { created ->
                    if (created.fellBackToDefault) {
                        val error = AppError(AppErrorCode.PROJECT_FELL_BACK_TO_DEFAULT, retryable = false, stage = "session_create")
                        Toast.makeText(context, error.localizedMessage(language), Toast.LENGTH_LONG).show()
                    }
                    onOpen(ChatLaunch.new(created.id, activeProfile))
                }
            } finally {
                creatingSession = false
            }
        }
    }

    fun openExisting(session: Session) {
        val request = ++openRequestSerial
        openRequestJob?.cancel()
        openRequestJob = scope.launch {
            if (vm.prepareOpen(session) && request == openRequestSerial) {
                onOpen(ChatLaunch.existing(session))
            } else if (request == openRequestSerial) {
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

    // First entry into a real project: a one-time notice that the FAB now creates there. Keyed on
    // the project and on whether the seen-set has loaded (not on its contents) so marking the
    // project seen does not restart the effect and cut the snackbar short.
    val scopeProject = projectsState.scope
    LaunchedEffect(scopeProject?.id, introSeen == null) {
        val seen = introSeen ?: return@LaunchedEffect
        val project = scopeProject ?: return@LaunchedEffect
        if (project.id == DEFAULT_PROJECT_ID || project.id in seen) return@LaunchedEffect
        vm.markIntroSeen(project.id)
        snackbarHostState.showSnackbar(
            message = localized(language, "在这里新建的会话会归入 ${project.label}", "Chats created here join ${project.label}"),
            actionLabel = localized(language, "知道了", "Got it"),
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
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
                            // No check glyph: its appear/disappear used to shove the labels
                            // sideways on every switch. Selection reads from the fill alone.
                            icon = {},
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
                                error = projectsState.error!!,
                                onRetry = { vm.loadProjectTree() },
                            )
                        projectsState.scope != null ->
                            ProjectScopeView(
                                project = projectsState.scope!!,
                                defaultProjectPath = defaultProjectPath,
                                onBack = { vm.exitProject() },
                                // Projects span profiles, so switch to the session's own profile
                                // (awaited) before opening, or the chat resumes against the wrong DB.
                                onOpenSession = ::openExisting,
                            )
                        // The default project is always derived; "no projects" means nothing else
                        // exists yet AND the default project is empty too.
                        projectsState.tree.all { it.id == DEFAULT_PROJECT_ID && it.sessionCount == 0 } ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "暂无项目", "No projects"),
                                subtitle = localized(
                                    language,
                                    "会话页新建的会话属于默认项目；在项目文件夹中运行的会话会显示在这里。",
                                    "Chats created from Sessions belong to the default project; chats run in a project folder show up here.",
                                ),
                                actionLabel = localized(language, "重新加载", "Reload"),
                                onAction = { vm.loadProjectTree() },
                            )
                        else -> ProjectOverview(
                            projectsState.tree,
                            nowMs = System.currentTimeMillis(),
                            onOpenProject = { vm.enterProject(it) },
                        )
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
                                error = archivedState.error!!,
                                onRetry = { vm.loadArchived() },
                            )
                        archivedState.sessions.isEmpty() ->
                            com.hermes.client.ui.components.EmptyState(
                                title = localized(language, "暂无归档会话", "Nothing archived"),
                                subtitle = localized(language, "长按会话可将它归档。", "Long-press a session to archive it."),
                            )
                        else -> LazyColumn {
                            items(archivedState.sessions, key = { "a-${it.profile.orEmpty()}:${it.id}" }) { s ->
                                ArchivedRow(
                                    session = s,
                                    defaultProjectPath = defaultProjectPath,
                                    onOpen = { openExisting(s) },
                                    onUnarchive = { vm.unarchive(s) },
                                    onDelete = { vm.delete(s) },
                                )
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
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.loading && state.sessions.isEmpty() -> com.hermes.client.ui.components.LoadingState()
                        state.error != null && state.sessions.isEmpty() -> com.hermes.client.ui.components.ErrorState(
                            error = state.error!!,
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
                                                session = s, isPinned = true, defaultProjectPath = defaultProjectPath, onMoveToProject = { moveTarget = s },
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
                        LinearProgressIndicator(
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
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

/**
 * One archived session: stroke archive-box + title/model (matching the Projects row layout),
 * no divider. Tap opens; LONG-PRESS offers unarchive and delete — the same sheet pattern as
 * live session rows (the old trailing unarchive button broke the layout symmetry).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedRow(
    session: Session,
    defaultProjectPath: String?,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    ListItem(
        leadingContent = {
            Icon(
                com.hermes.client.ui.components.ArchiveBoxIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = { Text(session.title) },
        supportingContent = { SessionSubline(session, defaultProjectPath = defaultProjectPath) },
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
                headlineContent = { Text(localized(language, "取消归档", "Unarchive")) },
                leadingContent = { Icon(Icons.Rounded.Unarchive, contentDescription = null) },
                modifier = Modifier.clickable { menuOpen = false; onUnarchive() },
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

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(localized(language, "删除会话？", "Delete session?")) },
            text = { Text(localized(language, "“${session.title}”将被永久删除。", "\"${session.title}\" will be permanently deleted.")) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(localized(language, "删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(localized(language, "取消", "Cancel")) }
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
            // Project · model, then the live status line. No profile text: the list is scoped to
            // one profile and identity lives only in the avatar (docs/DESIGN.md §1).
            supportingContent = {
                Column {
                    SessionSubline(session, defaultProjectPath = defaultProjectPath)
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

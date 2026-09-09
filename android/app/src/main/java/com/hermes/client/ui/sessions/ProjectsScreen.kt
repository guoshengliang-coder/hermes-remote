package com.hermes.client.ui.sessions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized

/**
 * Projects, as a full-screen page reached from the Chats overflow menu (docs/DESIGN.md §5.16,
 * 2026-09-09). Two states in one route: the overview, and one project drilled into. Back unwinds
 * the drill-in first, then leaves the page — so the drilled-in project never needs to survive in
 * a route argument, and a rebuild of the tree cannot strand the user on a project that vanished.
 *
 * [vm] has no default ON PURPOSE: it must be the Chats screen's ViewModel, handed in by
 * [com.hermes.client.ui.nav.HermesNav]. A `hiltViewModel()` here would silently build a SECOND
 * SessionsViewModel — a second socket restore, a second event collector, every list fetched
 * twice — and the page would look perfectly fine while doing it.
 */
@Composable
fun ProjectsScreen(
    vm: SessionsViewModel,
    onBack: () -> Unit,
    onOpen: (ChatLaunch) -> Unit,
) {
    val language = LocalAppLanguage.current
    val projectsState by vm.projectsState.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val defaultProjectPath by vm.defaultProjectPath.collectAsStateWithLifecycle()
    val introSeen by vm.introSeen.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val creator = rememberSessionCreator(vm, activeProfile, onOpen)
    val openSession = rememberSessionOpener(vm, onOpen)
    val scope = projectsState.scope
    // Resolved in composition: projectDisplayLabel is @Composable (it localizes the default
    // project's name), so neither the snackbar effect nor the title can call it inline.
    val scopeLabel = scope?.let { projectDisplayLabel(it) }

    // Entering the page always refreshes. The list only shows a spinner when it is empty, so a
    // re-entry updates warm content silently.
    LaunchedEffect(Unit) { vm.loadProjectTree() }

    BackHandler(enabled = scope != null) { vm.exitProject() }

    // First entry into a real project: a one-time notice that the FAB now creates there. Keyed on
    // the project and on whether the seen-set has loaded (not on its contents) so marking the
    // project seen does not restart the effect and cut the snackbar short.
    LaunchedEffect(scope?.id, introSeen == null) {
        val seen = introSeen ?: return@LaunchedEffect
        val project = scope ?: return@LaunchedEffect
        if (project.id == DEFAULT_PROJECT_ID || project.id in seen) return@LaunchedEffect
        vm.markIntroSeen(project.id)
        snackbarHostState.showSnackbar(
            message = localized(
                language,
                "在这里新建的会话会归入 $scopeLabel",
                "Chats created here join $scopeLabel",
            ),
            actionLabel = localized(language, "知道了", "Got it"),
            duration = SnackbarDuration.Short,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = scopeLabel ?: localized(language, "项目", "Projects"),
                navigationIcon = {
                    IconButton(onClick = { if (scope != null) vm.exitProject() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localized(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
        floatingActionButton = { NewSessionFab(creator, cwd = projectCreationCwd(scope)) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                projectsState.loading && projectsState.tree.isEmpty() ->
                    com.hermes.client.ui.components.ListLoadingState()
                projectsState.error != null ->
                    com.hermes.client.ui.components.ErrorState(
                        error = projectsState.error!!,
                        onRetry = { vm.loadProjectTree() },
                    )
                scope != null ->
                    ProjectScopeView(
                        project = scope,
                        defaultProjectPath = defaultProjectPath,
                        // Projects span profiles, so switch to the session's own profile
                        // (awaited) before opening, or the chat resumes against the wrong DB.
                        onOpenSession = openSession,
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
    }
}

package com.hermes.client.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.hermes.client.data.network.isUnhealthy
import com.hermes.client.ui.chat.ChatScreen
import com.hermes.client.ui.chat.ChatLaunch
import com.hermes.client.ui.chat.ChatViewModel
import com.hermes.client.ui.components.HealthSheet
import com.hermes.client.ui.components.HealthStrip
import com.hermes.client.ui.cron.CronDetailScreen
import com.hermes.client.ui.cron.CronEditScreen
import com.hermes.client.ui.cron.CronScreen
import com.hermes.client.ui.messaging.MessagingScreen
import com.hermes.client.ui.messaging.MessagingSetupScreen
import com.hermes.client.ui.models.ModelsScreen
import com.hermes.client.ui.models.ModelsViewModel
import com.hermes.client.ui.sessions.SessionsScreen
import com.hermes.client.ui.sessions.SessionsViewModel
import com.hermes.client.ui.sessions.SearchViewModel
import com.hermes.client.ui.settings.AboutScreen
import com.hermes.client.ui.settings.AppearanceScreen
import com.hermes.client.ui.settings.EnvScreen
import com.hermes.client.ui.settings.McpSettingsScreen
import com.hermes.client.ui.settings.MemorySettingsScreen
import com.hermes.client.ui.settings.SettingsScreen
import com.hermes.client.ui.settings.AppUpdateScreen
import com.hermes.client.ui.settings.LanguageScreen
import com.hermes.client.ui.setup.SetupScreen
import com.hermes.client.ui.tools.AgentsToolsScreen
import com.hermes.client.ui.usage.UsageScreen
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.startup.StartupFailure
import com.hermes.client.ui.startup.StartupDestination
import com.hermes.client.ui.startup.ForegroundRecoveryCoordinator
import com.hermes.client.data.diagnostics.CrashReporter

/** Route for a chat target. [encode] is injectable so the shape is unit-testable off-device. */
internal fun chatRoute(target: ChatLaunch, encode: (String) -> String = { Uri.encode(it) }): String = buildString {
    append("chat/")
    append(encode(target.sessionId))
    val query = buildList {
        target.profile?.takeIf { it.isNotBlank() }?.let { add("profile=${encode(it)}") }
        target.title?.takeIf { it.isNotBlank() }?.let { add("title=${encode(it)}") }
        if (target.isNew) add("new=true")
        target.initialQuery?.takeIf { it.isNotBlank() }?.let { add("q=${encode(it)}") }
    }
    if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
}

private fun diagnosticRoute(route: String): String =
    if (route.startsWith("chat/")) "chat#${route.substringAfter("chat/").substringBefore('?').hashCode()}"
    else route.substringBefore('?')

/** Only the configuration-repair destination may consume the repair completion signal. */
internal fun shouldPopCompletedRepair(
    route: String?,
    expectedCompletion: Long,
    actualCompletion: Long,
): Boolean = route?.startsWith("settings_connection") == true &&
    expectedCompletion >= 0L &&
    actualCompletion >= expectedCompletion

/**
 * Root navigation host. The session list is the ONLY main screen; everything else is either a
 * pushed screen (chat, cron, settings, archived — back arrow) or lives on the card page, a
 * modal drawer opened from the list's avatar. The drawer is the app's single profile-switch
 * point. First-launch gating: when [hasConfig] is false the start destination is "setup".
 *
 * onUnauthorized clears the back stack and routes to "setup" so an expired token forces re-entry.
 *
 * [deepLinkRoute], when non-null, is navigated to once (keyed by value) — used to jump straight
 * to a session when the activity is launched or resumed from a tapped notification.
 * [onDeepLinkConsumed] is invoked right after that navigation so the caller can clear its
 * pending-route state; otherwise a config change (rotation, dark-mode/font-scale) would recreate
 * the activity, re-read the same intent extra, and re-navigate to the same chat.
 */
@Composable
fun HermesNav(
    hasConfig: Boolean,
    deepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    configurationRepair: StartupFailure? = null,
    repairCompletion: Long = 0L,
    onConnectionConfigurationSaved: () -> Unit = {},
    onInitialConfigurationSaved: () -> Unit = {},
    onDestinationChanged: (StartupDestination) -> Unit = {},
    foregroundRecovery: ForegroundRecoveryCoordinator? = null,
) {
    val language = LocalAppLanguage.current
    val nav = rememberNavController()
    // Chat is the primary Hermes Remote workflow. The richer activity dashboard makes several
    // optional API calls and must never block the user's first successful connection.
    val start = if (hasConfig) "sessions" else "setup"
    // Set on a fresh successful pairing; the sheet itself no-ops if it was already shown once.
    var showNotificationOnboarding by rememberSaveable { mutableStateOf(false) }
    var pendingSetupCompletion by rememberSaveable { mutableStateOf<Long?>(null) }

    fun openCanonicalChat(route: String) {
        CrashReporter.breadcrumb("nav", "open ${diagnosticRoute(route)}")
        nav.navigate(route) {
            // A chat is a leaf of the single Sessions root, never another layer on top of a
            // previous chat/search/detail destination. This also normalizes stacks restored after
            // process death and makes system back deterministic: one press always returns home.
            popUpTo("sessions") { inclusive = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    // Guard the navigate: a hermes:// deep link is untrusted, and even the notification path could
    // carry a stale/unknown route — an unresolved route must be ignored, never crash.
    LaunchedEffect(deepLinkRoute) {
        deepLinkRoute?.let {
            runCatching {
                if (it.startsWith("chat/")) openCanonicalChat(it)
                else nav.navigate(it) { launchSingleTop = true }
            }
            onDeepLinkConsumed()
        }
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    LaunchedEffect(route, backStackEntry?.arguments) {
        CrashReporter.breadcrumb("nav", "destination ${route ?: "unknown"}")
        val destination = when {
            route == "sessions" -> StartupDestination.Sessions
            route == "search" -> StartupDestination.Search
            route == "models" -> StartupDestination.Models
            route?.startsWith("chat/") == true -> {
                val id = backStackEntry?.arguments?.getString("id").orEmpty()
                if (id.isBlank()) StartupDestination.Static else StartupDestination.Chat(
                    sessionId = id,
                    profile = backStackEntry?.arguments?.getString("profile"),
                )
            }
            else -> StartupDestination.Static
        }
        onDestinationChanged(destination)
    }

    LaunchedEffect(configurationRepair) {
        configurationRepair?.let { failure ->
            nav.navigate(
                "settings_connection?repair=${failure.name}&completion=${repairCompletion + 1}",
            ) { launchSingleTop = true }
        }
    }
    val expectedRepairCompletion = backStackEntry?.arguments?.getLong("completion", -1L) ?: -1L
    LaunchedEffect(route, repairCompletion, expectedRepairCompletion) {
        if (shouldPopCompletedRepair(route, expectedRepairCompletion, repairCompletion)) {
            nav.popBackStack()
        }
    }
    LaunchedEffect(route, repairCompletion, pendingSetupCompletion) {
        val expected = pendingSetupCompletion
        if (route == "setup" && expected != null && repairCompletion >= expected) {
            pendingSetupCompletion = null
            showNotificationOnboarding = true
            nav.navigate("sessions") { popUpTo("setup") { inclusive = true } }
        }
    }

    val shellVm: ShellViewModel = hiltViewModel()
    val health by shellVm.health.collectAsStateWithLifecycle()
    var showHealthSheet by rememberSaveable { mutableStateOf(false) }

    // Probe only while the app is foregrounded (in-app-only v1). ProcessLifecycleOwner replays its
    // current state on addObserver, so ON_START fires immediately if already foregrounded.
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> shellVm.onAppForeground()
                Lifecycle.Event.ON_STOP -> shellVm.onAppBackground()
                else -> {}
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) }
    }

    val onUnauthorized: () -> Unit = {
        nav.navigate("setup") { popUpTo(0) { inclusive = true } }
    }
    // Pushed screens navigate "up"; their top-bar nav icon (formerly the drawer hamburger) is a
    // back arrow wired to this.
    val back: () -> Unit = { nav.popBackStack() }
    val backToSessions: () -> Unit = {
        CrashReporter.breadcrumb("nav", "chat back -> sessions")
        if (!nav.popBackStack("sessions", inclusive = false)) {
            nav.navigate("sessions") {
                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    // Drill into a screen from a tab hub (Agent Activity / You).
    val push: (String) -> Unit = { dest -> nav.navigate(dest) { launchSingleTop = true } }
    val openChat: (ChatLaunch) -> Unit = { target -> openCanonicalChat(chatRoute(target)) }

    // Card page: modal drawer off the session list. Gestures only on the list root so a swipe
    // inside a chat can't accidentally drag it out; the avatar button opens it anywhere it shows.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = androidx.compose.runtime.rememberCoroutineScope()
    val openCard: () -> Unit = { drawerScope.launch { drawerState.open() } }
    // Navigating from the card closes it for the pushed screen, but ARMS a reopen: pressing back
    // from that screen returns to the card (not the bare list) — the card is where you came from.
    var reopenCardOnSessions by androidx.compose.runtime.remember { mutableStateOf(false) }
    val closeCardAnd: (String) -> Unit = { dest ->
        drawerScope.launch { drawerState.close() }
        reopenCardOnSessions = true
        push(dest)
    }
    LaunchedEffect(route) {
        if (reopenCardOnSessions && route == "sessions") {
            reopenCardOnSessions = false
            drawerState.open()
        }
    }

    // Belt-and-braces: back must NEVER finish the activity while the card is open. The sheet's
    // own predictive-back handler (composed later) takes precedence when available.
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || route == "sessions",
        drawerContent = { CardPage(onNavigate = closeCardAnd, drawerState = drawerState) },
    ) {
    Scaffold(
        // Let each destination's own Scaffold own the top/side insets; this outer one
        // contributes nothing itself (otherwise the status-bar inset would apply twice).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // Renders nothing when healthy. When shown it owns the status-bar inset, so the content
            // below consumes that inset to avoid a second top gap under the strip.
            if (hasConfig && health.isUnhealthy()) {
                HealthStrip(health = health, onClick = { showHealthSheet = true })
            }
            val contentModifier =
                if (hasConfig && health.isUnhealthy()) Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)
                else Modifier.weight(1f)
            if (showNotificationOnboarding) {
                com.hermes.client.ui.settings.NotificationOnboardingSheet(
                    onDone = { showNotificationOnboarding = false },
                )
            }
            NavHost(
                navController = nav,
                startDestination = start,
                modifier = contentModifier,
            ) {
            composable("setup") {
                SetupScreen(
                    onSaved = {
                        pendingSetupCompletion = repairCompletion + 1L
                        onInitialConfigurationSaved()
                    },
                )
            }
            // ---- Tab roots ----
            composable("sessions") {
                val vm: SessionsViewModel = hiltViewModel()
                DisposableEffect(foregroundRecovery, vm) {
                    foregroundRecovery?.register("sessions") { vm.recoverForForeground() }
                    onDispose { foregroundRecovery?.unregister("sessions") }
                }
                SessionsScreen(
                    vm = vm,
                    onOpen = openChat,
                    onOpenCard = openCard,
                    onOpenSearch = { nav.navigate("search") { launchSingleTop = true } },
                    onOpenCron = { push("cron") },
                    onUnauthorized = onUnauthorized,
                )
            }
            composable(
                route = "search?q={q}",
                arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                val vm: SearchViewModel = hiltViewModel()
                DisposableEffect(foregroundRecovery, vm) {
                    foregroundRecovery?.register("search") { vm.recoverForForeground() }
                    onDispose { foregroundRecovery?.unregister("search") }
                }
                com.hermes.client.ui.sessions.SearchScreen(onOpen = openChat, onBack = back, vm = vm)
            }

            // ---- Pushed screens (back arrow) ----
            composable(
                route = "chat/{id}?profile={profile}&title={title}&new={new}&q={q}",
                arguments = listOf(
                    navArgument("profile") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("new") { type = NavType.BoolType; defaultValue = false },
                    navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
                enterTransition = {
                    fadeIn(tween(170)) + slideInHorizontally(tween(190)) { it / 12 }
                },
                exitTransition = {
                    fadeOut(tween(120)) + slideOutHorizontally(tween(150)) { -it / 18 }
                },
                popEnterTransition = {
                    fadeIn(tween(160)) + slideInHorizontally(tween(180)) { -it / 14 }
                },
                popExitTransition = {
                    fadeOut(tween(120)) + slideOutHorizontally(tween(170)) { it / 12 }
                },
            ) { entry ->
                val vm: ChatViewModel = hiltViewModel()
                val recoveryKey = "chat:${entry.arguments?.getString("id").orEmpty()}"
                DisposableEffect(foregroundRecovery, vm, recoveryKey) {
                    foregroundRecovery?.register(recoveryKey) { vm.recoverForForeground() }
                    onDispose { foregroundRecovery?.unregister(recoveryKey) }
                }
                ChatScreen(
                    sessionId = entry.arguments?.getString("id") ?: "",
                    sessionProfile = entry.arguments?.getString("profile"),
                    initialTitle = entry.arguments?.getString("title"),
                    isNewSession = entry.arguments?.getBoolean("new") ?: false,
                    initialQuery = entry.arguments?.getString("q"),
                    vm = vm,
                    onMenu = backToSessions,
                    onSearchAll = { q -> nav.navigate("search?q=${Uri.encode(q)}") { launchSingleTop = true } },
                    onNewChat = { id ->
                        openCanonicalChat(chatRoute(ChatLaunch.new(id)))
                    },
                    onUnauthorized = onUnauthorized,
                )
            }
            composable("models") {
                val vm: ModelsViewModel = hiltViewModel()
                DisposableEffect(foregroundRecovery, vm) {
                    foregroundRecovery?.register("models") { vm.recoverForForeground() }
                    onDispose { foregroundRecovery?.unregister("models") }
                }
                ModelsScreen(onMenu = back, vm = vm)
            }
            composable("profiles") {
                com.hermes.client.ui.profiles.ProfilePickerScreen(
                    onBack = back,
                    onEdit = { name ->
                        nav.navigate("profile_edit/${android.net.Uri.encode(name)}") { launchSingleTop = true }
                    },
                )
            }
            composable(
                "profile_edit/{profile}",
                arguments = listOf(navArgument("profile") { type = NavType.StringType }),
            ) {
                com.hermes.client.ui.profiles.ProfileEditScreen(onBack = back)
            }
            composable("cron") {
                CronScreen(
                    onMenu = back,
                    onOpen = { id -> nav.navigate("cron_detail/$id") },
                    onNew = { seed -> nav.navigate("cron_edit/$seed") },
                )
            }
            composable("cron_detail/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                CronDetailScreen(
                    jobId = id,
                    onBack = back,
                    onEdit = { nav.navigate("cron_edit/$id") },
                )
            }
            composable("cron_edit/{id}") { entry ->
                CronEditScreen(
                    jobId = entry.arguments?.getString("id") ?: "new",
                    onDone = { nav.popBackStack() },
                )
            }
            composable("messaging") {
                MessagingScreen(
                    onMenu = back,
                    onSetup = { id -> nav.navigate("messaging_setup/$id") },
                )
            }
            composable("messaging_setup/{id}") { entry ->
                MessagingSetupScreen(
                    platformId = entry.arguments?.getString("id") ?: "",
                    onDone = { nav.popBackStack() },
                )
            }
            composable("usage") { UsageScreen(onMenu = back) }
            composable("settings") {
                SettingsScreen(
                    onMenu = back,
                    onNavigate = { dest -> nav.navigate(dest) { launchSingleTop = true } },
                )
            }
            composable("app_update") { AppUpdateScreen(onBack = { nav.popBackStack() }) }
            composable("settings_appearance") { AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable("settings_language") { LanguageScreen(onBack = { nav.popBackStack() }) }
            composable("settings_notifications") {
                com.hermes.client.ui.settings.NotificationsScreen(onBack = { nav.popBackStack() })
            }
            composable("settings_memory") { MemorySettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings_prompts") {
                com.hermes.client.ui.settings.PromptLibraryScreen(onBack = { nav.popBackStack() })
            }
            composable("settings_mcp") { McpSettingsScreen(onBack = { nav.popBackStack() }) }
            composable("settings_env") { EnvScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "settings_connection?repair={repair}&completion={completion}",
                arguments = listOf(
                    navArgument("repair") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("completion") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                ),
            ) { entry ->
                val repairFailure = entry.arguments?.getString("repair")
                    ?.let { raw -> runCatching { StartupFailure.valueOf(raw) }.getOrNull() }
                com.hermes.client.ui.settings.ConnectionSettingsScreen(
                    onBack = if (repairFailure == null) ({ nav.popBackStack() }) else null,
                    repairFailure = repairFailure,
                    onSaved = if (repairFailure == null) ({}) else onConnectionConfigurationSaved,
                )
            }
            composable("settings_diagnostics") {
                com.hermes.client.ui.settings.DiagnosticsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenGallery = { nav.navigate("component_gallery") { launchSingleTop = true } },
                )
            }
            composable("component_gallery") {
                com.hermes.client.ui.gallery.ComponentGalleryScreen(onBack = { nav.popBackStack() })
            }
            composable("settings_about") { AboutScreen(onBack = { nav.popBackStack() }) }
            composable("agents_tools") { AgentsToolsScreen(onMenu = back) }
            }
        }
    }
    }

    if (showHealthSheet) {
        HealthSheet(
            health = health,
            onRecheck = { shellVm.recheckHealth() },
            onDismiss = { showHealthSheet = false },
        )
    }
}

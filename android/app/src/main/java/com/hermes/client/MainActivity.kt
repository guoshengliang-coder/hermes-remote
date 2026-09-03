package com.hermes.client

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.diagnostics.CrashReporter
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import com.hermes.client.ui.diagnostics.CrashReportScreen
import com.hermes.client.ui.nav.HermesNav
import com.hermes.client.ui.nav.deepLinkRouteFor
import com.hermes.client.ui.nav.isNewChatLink
import com.hermes.client.ui.theme.HermesTheme
import com.hermes.client.ui.theme.LocalToolCallTechnical
import com.hermes.client.ui.theme.Motion
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.AppLanguageProvider
import com.hermes.client.ui.localization.localized
import com.hermes.client.ui.startup.StartupScreen
import com.hermes.client.ui.startup.StartupReason
import com.hermes.client.ui.startup.StartupUiState
import com.hermes.client.ui.startup.StartupViewModel
import com.hermes.client.ui.startup.ForegroundRecoveryCoordinator
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var credentialStore: CredentialStore
    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var profileManager: ProfileManager
    @Inject lateinit var profileIdentityStore: com.hermes.client.data.repository.ProfileIdentityStore
    @Inject lateinit var chat: com.hermes.client.data.repository.ChatRepository
    @Inject lateinit var pendingShare: com.hermes.client.share.PendingShareStore
    @Inject lateinit var languages: AppLanguageProvider
    @Inject lateinit var foregroundRecovery: ForegroundRecoveryCoordinator
    private val startupViewModel: StartupViewModel by viewModels()
    private val processColdStart = PROCESS_UI_LAUNCH_CLAIMED.compareAndSet(false, true)

    /**
     * Route requested by a tapped notification (see `HermesNotifier.openIntent`'s
     * `extra_route`). Read on create and on every `onNewIntent` so a tap while the app is
     * already running still navigates; consumed by `HermesNav`'s `deepLinkRoute` param.
     */
    private var pendingRoute = mutableStateOf<String?>(null)
    private val newChatInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ already enforces edge-to-edge for targetSdk 35+; declaring it
        // makes pre-15 devices behave identically (screens already handle insets).
        enableEdgeToEdge()
        startupViewModel.onActivityCreated(processColdStart)
        val dlData = intent?.data
        if (dlData != null && isNewChatLink(dlData.toString())) {
            openNewChat()
            intent?.data = null
        } else {
            pendingRoute.value = intent?.getStringExtra("extra_route")
                ?: dlData?.let { deepLinkRouteFor(it.toString()) }
            intent?.removeExtra("extra_route")
            intent?.data = null
        }
        handleShare(intent)
        val initiallyHasConfig = runCatching { credentialStore.load() }.getOrNull() != null
        val crashReport = CrashReporter.read(this)
        setContent {
            val mode by settingsStore.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val technical by settingsStore.toolCallTechnical.collectAsState(initial = true)
            val language by settingsStore.appLanguage.collectAsState(initial = AppLanguage.ZH)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // System-bar icons must follow the APP's theme, not the OS DayNight default:
            // with the OS in dark mode but the app in light mode, the default leaves
            // white icons over our white edge-to-edge background — an invisible clock
            // and signal cluster (reported 2026-09-01).
            val view = androidx.compose.ui.platform.LocalView.current
            if (!view.isInEditMode) {
                androidx.compose.runtime.LaunchedEffect(dark) {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
            }
            val identities by profileIdentityStore.identities.collectAsState(initial = emptyMap())
            val startupState by startupViewModel.state.collectAsState()
            val repairCompletion by startupViewModel.repairCompletion.collectAsState()
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                com.hermes.client.ui.components.LocalProfileIdentities provides identities,
                com.hermes.client.ui.components.LocalAvatarDir provides profileIdentityStore.avatarDir,
            ) {
                HermesTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalToolCallTechnical provides technical) {
                        Surface {
                            // If the previous run crashed, show the saved trace first so it can be
                            // shared, then continue into the app once dismissed.
                            var report by remember { mutableStateOf(crashReport) }
                            val current = report
                            if (current != null) {
                                CrashReportScreen(
                                    report = current,
                                    onShare = { shareCrash(current) },
                                    onDismiss = { CrashReporter.clear(this@MainActivity); report = null },
                                )
                            } else {
                                androidx.compose.foundation.layout.Box {
                                    var hasConfig by remember { mutableStateOf(initiallyHasConfig) }
                                    val deepLinkRoute by pendingRoute
                                    val coldGateVisible = when (val currentStartup = startupState) {
                                        is StartupUiState.Loading -> currentStartup.reason == StartupReason.COLD_START
                                        is StartupUiState.Failed -> currentStartup.reason == StartupReason.COLD_START
                                        is StartupUiState.RepairRequired -> false
                                        StartupUiState.Hidden -> false
                                    }
                                    // Do not construct the cold-start destination behind the gate.
                                    // The startup coordinator preloads its first snapshot; creating
                                    // SessionsViewModel afterward lets it render the cache immediately.
                                    // Warm recovery keeps the existing navigation tree alive in place.
                                    // The gate fades out over the first screen (StartupScreen's
                                    // exit), and the first screen rises 16dp in underneath it. A
                                    // launch that never showed the gate starts settled: no motion.
                                    val navVisibility = remember { MutableTransitionState(!coldGateVisible) }
                                    navVisibility.targetState = !coldGateVisible
                                    val riseIn = with(LocalDensity.current) { 16.dp.roundToPx() }
                                    AnimatedVisibility(
                                        visibleState = navVisibility,
                                        enter = fadeIn(tween(Motion.DurationMedium)) +
                                            slideInVertically(tween(Motion.DurationMedium, easing = Motion.Standard)) { riseIn },
                                        exit = ExitTransition.None,
                                    ) {
                                        HermesNav(
                                            hasConfig = hasConfig,
                                            deepLinkRoute = deepLinkRoute,
                                            onDeepLinkConsumed = { pendingRoute.value = null },
                                            configurationRepair = (startupState as? StartupUiState.RepairRequired)?.failure,
                                            repairCompletion = repairCompletion,
                                            onConnectionConfigurationSaved = startupViewModel::onConfigurationSaved,
                                            onInitialConfigurationSaved = {
                                                hasConfig = true
                                                startupViewModel.onInitialConfigurationSaved()
                                            },
                                            onDestinationChanged = startupViewModel::onActiveDestinationChanged,
                                            foregroundRecovery = foregroundRecovery,
                                        )
                                    }
                                    StartupScreen(
                                        state = startupState,
                                        onRetry = startupViewModel::retry,
                                        onOpenConnectionSettings = startupViewModel::requestConfigurationRepair,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        startupViewModel.onForeground()
    }

    override fun onStop() {
        startupViewModel.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val dlData = intent.data
        if (dlData != null && isNewChatLink(dlData.toString())) {
            openNewChat()
            intent.data = null
        } else {
            pendingRoute.value = intent.getStringExtra("extra_route")
                ?: dlData?.let { deepLinkRouteFor(it.toString()) }
            intent.removeExtra("extra_route")
            intent.data = null
        }
        handleShare(intent)
    }

    /** Share the saved crash trace via the system share sheet. */
    private fun shareCrash(report: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_SUBJECT,
                localized(languages.current, "Hermes GO 崩溃报告", "Hermes GO crash report"),
            )
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, localized(languages.current, "分享崩溃报告", "Share crash report")))
    }

    /** Create a fresh chat and navigate to it (widget "New chat" / hermes://new). No-op if unconfigured. */
    private fun openNewChat() {
        if (credentialStore.load() == null) return
        if (!newChatInFlight.compareAndSet(false, true)) return // a create is already running — ignore repeat taps
        lifecycleScope.launch {
            try {
                runCatching {
                    chat.connect() // idempotent; a cold start has no socket yet
                    profileManager.refresh() // load active profile so the session isn't orphaned to default
                    chat.createSession(profileManager.active.value).id
                }.onSuccess { id -> pendingRoute.value = "chat/$id?new=true" }
                    .onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            localized(languages.current, "无法新建会话（HR-RPC-001）", "Couldn't start a chat (HR-RPC-001)"),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
            } finally {
                newChatInFlight.set(false)
            }
        }
    }

    /**
     * Handle an incoming ACTION_SEND share (text or a single attachment): open a new chat with
     * the text pre-filled and/or the attachment staged. Reuses the notification deep-link rail.
     */
    private fun handleShare(intent: Intent?) {
        val text = com.hermes.client.share.sharedText(
            intent?.action, intent?.type,
            intent?.getStringExtra(Intent.EXTRA_SUBJECT), intent?.getStringExtra(Intent.EXTRA_TEXT),
        )
        val isImage = com.hermes.client.share.isImageShare(intent?.action, intent?.type)
        val hasStream = intent?.action == Intent.ACTION_SEND && intent.hasExtra(Intent.EXTRA_STREAM)
        val attachmentUri: android.net.Uri? = if (hasStream) {
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else null

        if (text == null && attachmentUri == null) return

        // For an image share the caption (if any) is EXTRA_TEXT; a text share's caption is `text`.
        val caption = if (isImage) {
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        } else text

        // Consume the extras so a config-change recreation doesn't re-fire the share.
        intent?.removeExtra(Intent.EXTRA_TEXT)
        intent?.removeExtra(Intent.EXTRA_SUBJECT)
        intent?.removeExtra(Intent.EXTRA_STREAM)

        if (credentialStore.load() == null) return

        lifecycleScope.launch {
            var b64: String? = null
            var mime: String? = null
            var attachmentName: String? = null
            if (attachmentUri != null) {
                val read = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        com.hermes.client.ui.chat.prepareAttachment(
                            this@MainActivity,
                            attachmentUri,
                            if (isImage) "shared-image.jpg" else "shared-file",
                        )
                    }.getOrNull()
                }
                if (read == null) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        localized(languages.current, "无法读取附件（HR-FILE-001）", "Couldn't read the attachment (HR-FILE-001)"),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    if (caption == null) return@launch  // nothing left to share
                } else {
                    b64 = android.util.Base64.encodeToString(read.bytes, android.util.Base64.NO_WRAP)
                    mime = read.mimeType
                    attachmentName = read.name
                }
            }
            // connect() first — a cold-start share has no open socket yet, and createSession()
            // would otherwise fail after the ready-gate timeout. connect() is idempotent.
            chat.connect()
            runCatching {
                // Load the active profile before creating: on a cold-start share nothing has called
                // refresh() yet (that normally happens when SessionsViewModel inits), so active would
                // be null and the new session would orphan to the gateway's default profile. refresh()
                // hits the gateway, so keep it inside runCatching — an offline cold-start share must
                // surface the error toast, not crash.
                profileManager.refresh()
                chat.createSession(profileManager.active.value).id
            }
                .onSuccess { id ->
                    pendingShare.put(
                        id,
                        com.hermes.client.share.PendingShare(
                            text = caption,
                            imageBase64 = b64,
                            imageMime = mime,
                            attachmentName = attachmentName,
                        ),
                    )
                    pendingRoute.value = "chat/$id"
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        localized(languages.current, "无法新建会话（HR-RPC-001）", "Couldn't start a chat (HR-RPC-001)"),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    private companion object {
        val PROCESS_UI_LAUNCH_CLAIMED = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

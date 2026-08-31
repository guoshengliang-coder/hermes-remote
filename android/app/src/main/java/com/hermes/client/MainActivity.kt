package com.hermes.client

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.LocalAppLanguage
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
    @Inject lateinit var avatarColorStore: com.hermes.client.data.repository.AvatarColorStore
    @Inject lateinit var chat: com.hermes.client.data.repository.ChatRepository
    @Inject lateinit var pendingShare: com.hermes.client.share.PendingShareStore

    /**
     * Route requested by a tapped notification (see `HermesNotifier.openIntent`'s
     * `extra_route`). Read on create and on every `onNewIntent` so a tap while the app is
     * already running still navigates; consumed by `HermesNav`'s `deepLinkRoute` param.
     */
    private var pendingRoute = mutableStateOf<String?>(null)
    private val newChatInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val hasConfig = credentialStore.load() != null
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
            val avatarColors by avatarColorStore.overrides.collectAsState(initial = emptyMap())
            CompositionLocalProvider(
                LocalAppLanguage provides language,
                com.hermes.client.ui.theme.LocalAvatarColors provides avatarColors,
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
                                val deepLinkRoute by pendingRoute
                                HermesNav(
                                    hasConfig = hasConfig,
                                    deepLinkRoute = deepLinkRoute,
                                    onDeepLinkConsumed = { pendingRoute.value = null },
                                )
                            }
                        }
                    }
                }
            }
        }
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
            putExtra(Intent.EXTRA_SUBJECT, "Hermes Beta crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Share crash report"))
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
                    chat.createSession(profileManager.active.value)
                }.onSuccess { id -> pendingRoute.value = "chat/$id" }
                    .onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.widget.Toast.makeText(
                            this@MainActivity, "Couldn't start a chat", android.widget.Toast.LENGTH_SHORT,
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
                        this@MainActivity, "Couldn't read the attachment", android.widget.Toast.LENGTH_SHORT,
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
                chat.createSession(profileManager.active.value)
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
                        this@MainActivity, "Couldn't start a chat", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
}

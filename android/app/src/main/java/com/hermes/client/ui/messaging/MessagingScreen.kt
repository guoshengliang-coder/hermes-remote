package com.hermes.client.ui.messaging
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.data.network.HermesApiException
import com.hermes.client.ui.localization.AppLanguage
import com.hermes.client.ui.localization.localizedMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.hermes.client.ui.localization.l10n
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.ui.localization.LocalAppLanguage
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

data class MessagingUiState(
    val platforms: List<MessagingPlatformDto> = emptyList(),
    val loading: Boolean = true,
    val error: AppError? = null,
    val message: LocalizedText? = null,
    val restarting: Boolean = false,
    val testing: String? = null,
)

@HiltViewModel
class MessagingViewModel @Inject constructor(
    private val tools: ToolsRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(MessagingUiState())
    val state: StateFlow<MessagingUiState> = _state.asStateFlow()

    init {
        // Messaging platform state is per-profile — reload on profile switch.
        viewModelScope.launch { profileManager.active.collect { load() } }
    }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { tools.messagingPlatforms(profileManager.active.value) }
            .onSuccess { _state.value = _state.value.copy(platforms = it, loading = false, error = null) }
            .onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = AppError(
                        AppErrorCode.MESSAGING_LIST_FAILED,
                        retryable = true,
                        technicalCause = it.message,
                        stage = "messaging_load",
                    ),
                )
            }
    }

    fun toggle(id: String, enabled: Boolean) = viewModelScope.launch {
        // Optimistic; reload to reflect true state (enabling may fail if not configured).
        _state.value = _state.value.copy(
            platforms = _state.value.platforms.map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
        runCatching { tools.setMessagingEnabled(id, enabled, profileManager.active.value) }
            .onSuccess {
                _state.value = _state.value.copy(
                    message = if (enabled) localizedText("已启用 $id", "$id enabled") else localizedText("已停用 $id", "$id disabled"),
                )
                load()
            }
            .onFailure { failure ->
                // 409 is the multiplex port-binding refusal, not a generic RPC error: another
                // profile already owns this platform's listener. Saying "retry" there is a lie.
                val conflict = (failure as? HermesApiException)?.code == 409
                _state.value = _state.value.copy(
                    message = errorText(
                        if (conflict) AppErrorCode.MESSAGING_PROFILE_CONFLICT else AppErrorCode.MESSAGING_SAVE_FAILED,
                        failure.message,
                        stage = "messaging_toggle",
                    ),
                )
                load()
            }
    }

    /**
     * Restarts the gateway so channels sitting in `pending_restart` actually connect. Saving a
     * platform only writes config; without this the app would keep telling the user to restart
     * without offering any way to do it.
     */
    fun restartGateway() = viewModelScope.launch {
        _state.value = _state.value.copy(restarting = true)
        runCatching { tools.restartGateway(profileManager.active.value) }
            .onSuccess {
                _state.value = _state.value.copy(
                    restarting = false,
                    message = localizedText("网关正在重启，稍候刷新。", "The gateway is restarting. Refresh shortly."),
                )
                load()
            }
            .onFailure { failure ->
                _state.value = _state.value.copy(
                    restarting = false,
                    message = errorText(AppErrorCode.MESSAGING_RESTART_FAILED, failure.message, stage = "gateway_restart"),
                )
            }
    }

    /** Hermes' own check. Its reply names the missing field or says a restart is still pending. */
    fun test(id: String) = viewModelScope.launch {
        _state.value = _state.value.copy(testing = id)
        runCatching { tools.testMessagingPlatform(id, profileManager.active.value) }
            .onSuccess { result ->
                val detail = result.message?.takeIf { it.isNotBlank() }
                _state.value = _state.value.copy(
                    testing = null,
                    message = if (result.ok) {
                        localizedText(detail ?: "连接正常。", detail ?: "The connection is healthy.")
                    } else {
                        errorText(AppErrorCode.MESSAGING_PLATFORM_FAILED, detail, stage = "messaging_test")
                    },
                )
                load()
            }
            .onFailure { failure ->
                _state.value = _state.value.copy(
                    testing = null,
                    message = errorText(AppErrorCode.MESSAGING_PLATFORM_FAILED, failure.message, stage = "messaging_test"),
                )
            }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    /** Localized summary + code for a snackbar; the technical cause stays in diagnostics only. */
    private fun errorText(code: AppErrorCode, cause: String?, stage: String): LocalizedText {
        val error = AppError(code, retryable = code != AppErrorCode.MESSAGING_PROFILE_CONFLICT,
            technicalCause = cause, stage = stage)
        return LocalizedText(
            error.localizedMessage(AppLanguage.ZH),
            error.localizedMessage(AppLanguage.EN),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingScreen(
    onMenu: () -> Unit,
    onSetup: (String) -> Unit = {},
    vm: MessagingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalAppLanguage.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val stateMessage = state.message?.resolve(language)
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(stateMessage) {
        stateMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    var confirmingRestart by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    if (confirmingRestart) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingRestart = false },
            title = { Text(l10n("重启网关？", "Restart the gateway?")) },
            text = {
                Text(
                    l10n(
                        "重启会中断所有渠道当前正在进行的对话，通常几十秒后恢复。重启后，已保存的渠道才会真正连接。",
                        "Restarting interrupts every channel's live conversation for up to a minute. Saved channels only connect afterwards.",
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirmingRestart = false; vm.restartGateway() }) {
                    Text(l10n("重启", "Restart"))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmingRestart = false }) {
                    Text(l10n("取消", "Cancel"))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = l10n("消息渠道", "Messaging"),
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = l10n("返回", "Back")) } },
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    error = state.error!!,
                    onRetry = vm::load,
                )
                else -> {
                    val sections = androidx.compose.runtime.remember(state.platforms) {
                        messagingSections(state.platforms)
                    }
                    val pending = androidx.compose.runtime.remember(state.platforms) {
                        pendingRestartPlatforms(state.platforms)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (pending.isNotEmpty()) {
                            item(key = "pending-restart") {
                                // Saving a channel only writes config. Without this card the app
                                // would keep the user waiting on a connection that never comes.
                                PendingRestartCard(
                                    count = pending.size,
                                    names = pending.joinToString("、") { it.name ?: it.id },
                                    busy = state.restarting,
                                    onRestart = { confirmingRestart = true },
                                )
                            }
                        }
                        sections.forEach { section ->
                            item(key = "hdr-${section.group.name}") {
                                Text(
                                    section.title.resolve(language),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(section.platforms, key = { it.id }) { p ->
                                val rowStatus = messagingRowStatus(p)
                                MessagingRow(
                                    platform = p,
                                    status = rowStatus,
                                    language = language,
                                    dark = dark,
                                    testing = state.testing == p.id,
                                    onOpen = { onSetup(p.id) },
                                    onToggle = { enabled ->
                                        if (p.configured) vm.toggle(p.id, enabled) else onSetup(p.id)
                                    },
                                    onTest = { vm.test(p.id) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The saved-but-not-live banner. Deliberately a neutral tile, not the error-coloured HealthStrip:
 * nothing is broken here, a step is simply unfinished — and it carries the action that finishes it.
 */
@Composable
private fun PendingRestartCard(count: Int, names: String, busy: Boolean, onRestart: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.large,
        color = com.hermes.client.ui.theme.tileColor(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            com.hermes.client.ui.theme.statusColor(com.hermes.client.ui.theme.StatusTone.WARN, dark),
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        l10n("$count 个渠道待重启生效", "$count channels need a gateway restart"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        names,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            androidx.compose.material3.OutlinedButton(
                onClick = onRestart,
                enabled = !busy,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (busy) l10n("重启中…", "Restarting…") else l10n("重启网关", "Restart the gateway"))
            }
        }
    }
}

@Composable
private fun MessagingRow(
    platform: MessagingPlatformDto,
    status: MessagingRowStatus,
    language: com.hermes.client.ui.localization.AppLanguage,
    dark: Boolean,
    testing: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(platform.name ?: platform.id) },
        supportingContent = {
            Column {
                Text(
                    messagingStatusText(status).resolve(language),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (status) {
                        MessagingRowStatus.CONNECTED ->
                            com.hermes.client.ui.theme.statusColor(com.hermes.client.ui.theme.StatusTone.GOOD, dark)
                        MessagingRowStatus.PENDING_RESTART ->
                            com.hermes.client.ui.theme.statusColor(com.hermes.client.ui.theme.StatusTone.WARN, dark)
                        MessagingRowStatus.FAILED, MessagingRowStatus.GATEWAY_STOPPED ->
                            com.hermes.client.ui.theme.statusColor(com.hermes.client.ui.theme.StatusTone.BAD, dark)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // Hermes' own words about what is wrong. Kept as detail under our localized
                // summary, never as the primary message (ERROR_HANDLING.md).
                platform.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (platform.configured) {
                    androidx.compose.material3.TextButton(onClick = onTest, enabled = !testing) {
                        Text(if (testing) l10n("检测中…", "Testing…") else l10n("测试", "Test"))
                    }
                }
                androidx.compose.material3.Switch(
                    checked = platform.enabled,
                    onCheckedChange = { onToggle(it) },
                )
            }
        },
        modifier = Modifier.clickable { onOpen() },
    )
}

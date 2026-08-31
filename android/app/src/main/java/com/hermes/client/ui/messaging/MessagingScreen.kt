package com.hermes.client.ui.messaging
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.MessagingPlatformDto
import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.ToolsRepository
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
                    error = AppError(AppErrorCode.RPC_FAILED, retryable = true, technicalCause = it.message, stage = "messaging_load"),
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
            .onFailure {
                _state.value = _state.value.copy(message = localizedText("操作失败（HR-RPC-001）", "Operation failed (HR-RPC-001)"))
                load()
            }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
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
    val stateMessage = state.message?.resolve(language)
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(stateMessage) {
        stateMessage?.let { snackbar.showSnackbar(it); vm.clearMessage() }
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
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.platforms, key = { it.id }) { p ->
                        val status = when {
                            p.enabled && p.gatewayRunning -> l10n("已连接", "Connected")
                            p.enabled -> l10n("已启用", "Enabled")
                            p.configured -> l10n("已配置", "Configured")
                            else -> l10n("未配置", "Not configured")
                        }
                        ListItem(
                            headlineContent = { Text(p.name ?: p.id) },
                            supportingContent = {
                                Column {
                                    val setup = l10n("点击进行设置", "Tap to set up")
                                    Text(listOfNotNull(p.description?.takeIf { it.isNotBlank() }, setup).joinToString("  ·  "))
                                    Text(status, style = MaterialTheme.typography.labelSmall,
                                        color = if (p.enabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            trailingContent = {
                                androidx.compose.material3.Switch(
                                    checked = p.enabled,
                                    // Only allow the quick toggle once configured; otherwise open setup.
                                    onCheckedChange = { if (p.configured) vm.toggle(p.id, it) else onSetup(p.id) },
                                )
                            },
                            modifier = Modifier.clickable { onSetup(p.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

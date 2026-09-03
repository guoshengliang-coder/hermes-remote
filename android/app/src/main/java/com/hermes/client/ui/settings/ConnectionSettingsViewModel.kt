package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.normalizeGatewayBaseUrl
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.GatewayProbeResult
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.hermes.client.ui.localization.LocalizedText
import com.hermes.client.ui.localization.localizedText

data class ConnectionUiState(
    val url: String = "",
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: LocalizedText? = null,
    val testing: Boolean = false,
    val saved: Boolean = false,
)

/**
 * View/update the gateway URL + token after first-run setup. Mirrors SetupViewModel but is
 * reachable from Settings and reconnects the live socket on save so a changed server/token
 * takes effect without restarting the app.
 */
@HiltViewModel
class ConnectionSettingsViewModel @Inject constructor(
    private val store: CredentialStore,
    private val rest: HermesRestApi,
    private val chat: ChatRepository,
    private val gatedAuth: GatedAuth,
) : ViewModel() {
    private val _state = MutableStateFlow(
        runCatching { store.load() }.getOrNull()?.let {
            ConnectionUiState(url = it.baseUrl, token = it.token, username = it.username, password = it.password)
        } ?: ConnectionUiState(),
    )
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()
    private var testRevision = 0L

    fun onUrlChange(v: String) { invalidateTest { copy(url = v.trim(), saved = false, testResult = null) } }
    fun onTokenChange(v: String) { invalidateTest { copy(token = v.trim(), saved = false, testResult = null) } }
    fun onUsernameChange(v: String) { invalidateTest { copy(username = v.trim(), saved = false, testResult = null) } }
    fun onPasswordChange(v: String) { invalidateTest { copy(password = v, saved = false, testResult = null) } }

    private fun invalidateTest(transform: ConnectionUiState.() -> ConnectionUiState) {
        testRevision += 1L
        _state.value = _state.value.transform().copy(testing = false)
    }

    /** Test with the entered values WITHOUT persisting: a login probe when a username is set,
     *  otherwise a plain status check. */
    fun test() = viewModelScope.launch {
        val s = _state.value
        val revision = ++testRevision
        _state.value = s.copy(testing = true, testResult = null)
        val url = runCatching { normalizeGatewayBaseUrl(s.url) }.getOrElse {
            if (revision == testRevision) {
                _state.value = s.copy(
                    testing = false,
                    testResult = localizedText("Relay 地址无效（HR-CONFIG-003）", "Invalid Relay URL (HR-CONFIG-003)"),
                )
            }
            return@launch
        }
        val result = if (s.username.isNotBlank()) {
            if (withContext(Dispatchers.IO) { gatedAuth.probeLogin(url, s.username, s.password) }) {
                localizedText("连接成功 ✓", "Connected ✓")
            } else {
                localizedText("连接失败，请检查配置（HR-CONN-002）", "Connection failed — check the configuration (HR-CONN-002)")
            }
        } else {
            when (val probe = rest.probeStatusFor(url, s.token)) {
                GatewayProbeResult.Reachable -> localizedText(
                    "Relay 与 Mac 基础连接正常 ✓",
                    "Relay and Mac are reachable ✓",
                )
                is GatewayProbeResult.Unauthorized -> localizedText(
                    "App Token 无效或已失效（HR-AUTH-001）",
                    "The App Token is invalid or expired (HR-AUTH-001)",
                )
                is GatewayProbeResult.InvalidEndpoint -> localizedText(
                    "Relay 地址不是兼容的服务（HR-CONFIG-003）",
                    "The Relay URL isn't a compatible service (HR-CONFIG-003)",
                )
                is GatewayProbeResult.ServerFailure -> if (probe.errorCode == "device_offline") {
                    localizedText(
                        "Mac 端当前离线，请启动 Hermes Go Desktop（HR-CONN-005）",
                        "The Mac is offline. Start Hermes Go Desktop (HR-CONN-005)",
                    )
                } else {
                    localizedText("Relay 暂时不可用（HR-CONN-002）", "The Relay is temporarily unavailable (HR-CONN-002)")
                }
                is GatewayProbeResult.Unreachable -> localizedText(
                    "连接失败，请检查网络和地址（HR-CONN-002）",
                    "Connection failed — check the network and URL (HR-CONN-002)",
                )
            }
        }
        if (revision == testRevision) {
            _state.value = _state.value.copy(testing = false, testResult = result)
        }
    }

    /** Persist the new server/credentials, drop any stale session, then reconnect. */
    fun save(reconnect: Boolean = true) {
        val s = _state.value
        val url = runCatching { normalizeGatewayBaseUrl(s.url) }.getOrElse {
            _state.value = s.copy(testResult = localizedText("Relay 地址无效（HR-CONFIG-003）", "Invalid Relay URL (HR-CONFIG-003)"), saved = false)
            return
        }
        store.save(GatewayConfig(url, s.token.trim(), s.username.trim(), s.password))
        gatedAuth.cookieJar.clear() // force a fresh login with the new credentials
        if (reconnect) runCatching { chat.reconnect() }
        _state.value = _state.value.copy(saved = true, testResult = localizedText("已保存，正在重新连接", "Saved — reconnecting"))
    }
}

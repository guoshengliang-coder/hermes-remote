package com.hermes.client.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.DEFAULT_REMOTE_GATEWAY_URL
import com.hermes.client.data.auth.GatewayConfig
import com.hermes.client.data.auth.normalizeGatewayBaseUrl
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.HermesRestApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SetupResult { CONNECTED, UNREACHABLE, INVALID_URL }

data class SetupUiState(
    val url: String = DEFAULT_REMOTE_GATEWAY_URL,
    val token: String = "",
    val username: String = "",
    val password: String = "",
    val testResult: SetupResult? = null,
    val connecting: Boolean = false,
    val saved: Boolean = false,
    val scanError: Boolean = false,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val store: CredentialStore,
    private val rest: HermesRestApi,
    private val gatedAuth: GatedAuth,
) : ViewModel() {
    private val _state = MutableStateFlow(
        store.load()?.let {
            SetupUiState(url = it.baseUrl, token = it.token, username = it.username, password = it.password)
        } ?: SetupUiState(),
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun onUrlChange(v: String) { _state.value = _state.value.copy(url = v.trim()) }
    fun onTokenChange(v: String) { _state.value = _state.value.copy(token = v.trim()) }
    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v.trim()) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v) }

    // T10b: test() must NOT persist unverified credentials — probe with transient values.
    fun test() = viewModelScope.launch {
        val s = _state.value
        val url = runCatching { normalizeGatewayBaseUrl(s.url) }.getOrElse {
            _state.value = s.copy(testResult = SetupResult.INVALID_URL)
            return@launch
        }
        val ok = if (s.username.isNotBlank()) {
            withContext(Dispatchers.IO) { gatedAuth.probeLogin(url, s.username, s.password) }
        } else {
            rest.statusFor(url, s.token)
        }
        _state.value = _state.value.copy(testResult = if (ok) SetupResult.CONNECTED else SetupResult.UNREACHABLE)
    }

    /**
     * Single-action connect: probe with the entered values and persist ONLY on success, so a user
     * can no longer save an unverified config and land in a dead chat screen wondering whether
     * the URL or the token was wrong. The failure message renders in place.
     */
    fun connect() = viewModelScope.launch {
        val s = _state.value
        if (s.connecting) return@launch
        _state.value = s.copy(connecting = true, testResult = null)
        val url = runCatching { normalizeGatewayBaseUrl(s.url) }.getOrElse {
            _state.value = _state.value.copy(connecting = false, testResult = SetupResult.INVALID_URL)
            return@launch
        }
        val ok = if (s.username.isNotBlank()) {
            withContext(Dispatchers.IO) { gatedAuth.probeLogin(url, s.username, s.password) }
        } else {
            rest.statusFor(url, s.token)
        }
        if (!ok) {
            _state.value = _state.value.copy(connecting = false, testResult = SetupResult.UNREACHABLE)
            return@launch
        }
        _state.value = _state.value.copy(connecting = false, testResult = SetupResult.CONNECTED)
        save()
    }

    fun save() {
        val s = _state.value
        val url = runCatching { normalizeGatewayBaseUrl(s.url) }.getOrElse {
            _state.value = s.copy(testResult = SetupResult.INVALID_URL, saved = false)
            return
        }
        store.save(GatewayConfig(url, s.token, s.username.trim(), s.password))
        gatedAuth.cookieJar.clear()
        _state.value = _state.value.copy(saved = true)
    }

    /** Apply a scanned pairing QR: prefill the fields and auto-run the existing probe. */
    fun applyPairing(raw: String) {
        val p = parsePairingPayload(raw)
        if (p == null) {
            _state.value = _state.value.copy(scanError = true)
            return
        }
        _state.value = _state.value.copy(
            url = p.url, token = p.token, username = p.username, password = p.password,
            scanError = false, testResult = null,
        )
        test()
    }

    fun clearScanError() {
        if (_state.value.scanError) _state.value = _state.value.copy(scanError = false)
    }
}

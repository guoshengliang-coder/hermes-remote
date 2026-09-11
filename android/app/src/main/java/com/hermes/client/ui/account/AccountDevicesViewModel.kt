package com.hermes.client.ui.account

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.BuildConfig
import com.hermes.client.data.auth.AccountSession
import com.hermes.client.data.auth.AccountDeviceRouteMode
import com.hermes.client.data.auth.AccountSessionManager
import com.hermes.client.data.auth.AccountTransportMode
import com.hermes.client.data.auth.AccountSessionStore
import com.hermes.client.data.auth.AccountClock
import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.DEFAULT_REMOTE_GATEWAY_URL
import com.hermes.client.data.auth.isLoopbackGatewayBaseUrl
import com.hermes.client.data.auth.PendingEmailChallenge
import com.hermes.client.data.auth.PendingAccountDeletion
import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountApiException
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountDevicesResponseDto
import com.hermes.client.data.network.asOwnedDevice
import com.hermes.client.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AccountStage {
    DISCOVERING,
    UNAVAILABLE,
    SIGNED_OUT,
    CODE_SENT,
    SIGNED_IN,
    ACCOUNT_DELETION_COMMITTED,
}

enum class AccountDeletionStage { CONFIRMING, CODE_SENT, RETRY_COMMIT }

enum class AccountRetryAction { REFRESH, SEND_CODE, RESEND_CODE, VERIFY_CODE, SELECT_DEVICE }

data class AccountUiError(
    val code: String,
    val retryable: Boolean,
    val retryAction: AccountRetryAction = AccountRetryAction.REFRESH,
)

data class AccountDeletionUiState(
    val stage: AccountDeletionStage = AccountDeletionStage.CONFIRMING,
    val typedConfirmation: String = "",
    val acknowledged: Boolean = false,
    val code: String = "",
    val codeExpiresInSeconds: Int = 0,
    val resendInSeconds: Int = 0,
    val busy: Boolean = false,
    val error: AccountUiError? = null,
)

data class AccountDevicesUiState(
    val stage: AccountStage = AccountStage.DISCOVERING,
    val email: String = "",
    val code: String = "",
    val session: AccountSession? = null,
    val devices: List<AccountDeviceDto> = emptyList(),
    val maxOwnedDevices: Int = 3,
    val deviceRouteMode: AccountDeviceRouteMode = AccountDeviceRouteMode.EXPLICIT_DEVICE,
    val supportsDeviceSharing: Boolean = false,
    val busy: Boolean = false,
    val selectingDeviceId: String? = null,
    val codeExpiresInSeconds: Int = 0,
    val resendInSeconds: Int = 0,
    val accountDeletionEnabled: Boolean = false,
    val accountDeletion: AccountDeletionUiState? = null,
    val error: AccountUiError? = null,
)

internal data class EmailChallengeTiming(
    val expiresInSeconds: Int,
    val resendInSeconds: Int,
    val expired: Boolean,
)

internal fun emailChallengeTiming(pending: PendingEmailChallenge, now: Instant): EmailChallengeTiming {
    val expiry = runCatching { Instant.parse(pending.expiresAt) }.getOrNull()
        ?: return EmailChallengeTiming(0, 0, expired = true)
    val resend = runCatching { Instant.parse(pending.resendAfter) }.getOrNull()
        ?: return EmailChallengeTiming(0, 0, expired = true)
    fun secondsUntil(deadline: Instant): Int {
        val millis = Duration.between(now, deadline).toMillis().coerceAtLeast(0)
        return ((millis + 999L) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return EmailChallengeTiming(
        expiresInSeconds = secondsUntil(expiry),
        resendInSeconds = secondsUntil(resend),
        expired = !expiry.isAfter(now),
    )
}

internal fun accountDeletionChallengeTiming(
    pending: PendingAccountDeletion,
    now: Instant,
): EmailChallengeTiming = emailChallengeTiming(
    PendingEmailChallenge(
        baseUrl = pending.baseUrl,
        email = pending.email,
        challengeId = pending.challengeId,
        expiresAt = pending.expiresAt,
        resendAfter = pending.resendAfter,
        exchangeIdempotencyKey = pending.reauthenticationIdempotencyKey,
    ),
    now,
)

@HiltViewModel
class AccountDevicesViewModel @Inject constructor(
    private val api: AccountApi,
    private val store: AccountSessionStore,
    private val sessions: AccountSessionManager,
    private val legacyCredentials: CredentialStore,
    private val chat: ChatRepository,
    private val clock: AccountClock,
) : ViewModel() {
    constructor(
        api: AccountApi,
        store: AccountSessionStore,
        sessions: AccountSessionManager,
        legacyCredentials: CredentialStore,
        chat: ChatRepository,
    ) : this(api, store, sessions, legacyCredentials, chat, AccountClock.SYSTEM)

    private var devicePageVisible = false
    private var emptyDevicePollJob: Job? = null
    private var challengeTimerJob: Job? = null
    private var accountDeletionTimerJob: Job? = null
    private var retryDevice: AccountDeviceDto? = null
    private val pendingAtLaunch = store.loadPendingEmailChallenge()
    private val timingAtLaunch = pendingAtLaunch?.let { emailChallengeTiming(it, clock.now()) }
    private val pendingDeletionAtLaunch = store.loadPendingAccountDeletion()
    private val deletionTimingAtLaunch = pendingDeletionAtLaunch
        ?.takeUnless { it.isReadyToCommit }
        ?.let { accountDeletionChallengeTiming(it, clock.now()) }
    private val _state = MutableStateFlow(
        AccountDevicesUiState(
            stage = when {
                store.accountDeletionCommitted() -> AccountStage.ACCOUNT_DELETION_COMMITTED
                sessions.session.value != null -> AccountStage.SIGNED_IN
                pendingAtLaunch != null && timingAtLaunch?.expired == false -> AccountStage.CODE_SENT
                else -> AccountStage.DISCOVERING
            },
            email = pendingAtLaunch?.email.orEmpty(),
            session = sessions.session.value,
            codeExpiresInSeconds = timingAtLaunch?.expiresInSeconds ?: 0,
            resendInSeconds = timingAtLaunch?.resendInSeconds ?: 0,
            accountDeletion = pendingDeletionAtLaunch?.let { pending ->
                AccountDeletionUiState(
                    stage = if (pending.isReadyToCommit) {
                        AccountDeletionStage.RETRY_COMMIT
                    } else {
                        AccountDeletionStage.CODE_SENT
                    },
                    codeExpiresInSeconds = deletionTimingAtLaunch?.expiresInSeconds ?: 0,
                    resendInSeconds = deletionTimingAtLaunch?.resendInSeconds ?: 0,
                )
            },
        ),
    )
    val state: StateFlow<AccountDevicesUiState> = _state.asStateFlow()

    init { refresh() }

    /** Poll only while the device page is visible and the signed-in account has no Mac yet. */
    fun setDevicePageVisible(visible: Boolean) {
        devicePageVisible = visible
        if (!visible) {
            emptyDevicePollJob?.cancel()
            emptyDevicePollJob = null
            challengeTimerJob?.cancel()
            challengeTimerJob = null
            accountDeletionTimerJob?.cancel()
            accountDeletionTimerJob = null
            return
        }
        scheduleEmptyDevicePoll()
        startChallengeTimer()
        startAccountDeletionTimer()
    }

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value.trim(), error = null)
    }

    fun onCodeChange(value: String) {
        _state.value = _state.value.copy(code = value.filter(Char::isDigit).take(6), error = null)
    }

    fun refresh() = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null, session = sessions.session.value)
        if (store.accountDeletionCommitted()) {
            showAccountDeletionCommitted()
            return@launch
        }
        val current = sessions.session.value
        if (current == null) {
            discoverSignedOut()
        } else {
            val pendingDeletion = store.loadPendingAccountDeletion()
            if (pendingDeletion?.accountId == current.accountId && pendingDeletion.isReadyToCommit) {
                commitAccountDeletion(current, pendingDeletion)
                return@launch
            }
            if (pendingDeletion != null && pendingDeletion.accountId != current.accountId) {
                store.clearPendingAccountDeletion()
                _state.value = _state.value.copy(accountDeletion = null)
            }
            loadDevices(current)
        }
    }

    fun sendCode() = viewModelScope.launch {
        val email = _state.value.email.trim()
        if (!looksLikeEmail(email) || _state.value.busy || _state.value.stage == AccountStage.CODE_SENT) {
            return@launch
        }
        requestCode(email, AccountRetryAction.SEND_CODE)
    }

    fun resendCode() = viewModelScope.launch {
        val pending = store.loadPendingEmailChallenge() ?: return@launch
        if (!applyChallengeTiming(pending) || _state.value.busy || _state.value.resendInSeconds > 0) {
            return@launch
        }
        requestCode(pending.email, AccountRetryAction.RESEND_CODE)
    }

    private suspend fun requestCode(email: String, retryAction: AccountRetryAction) {
        _state.value = _state.value.copy(busy = true, error = null)
        val baseUrl = accountBaseUrl()
        try {
            val capabilities = api.capabilities(baseUrl)
            if (!capabilities.accountAuth.enabled || !capabilities.accountAuth.android ||
                "email_otp" !in capabilities.accountAuth.providers
            ) {
                _state.value = _state.value.copy(
                    stage = AccountStage.UNAVAILABLE,
                    busy = false,
                    error = AccountUiError("HR-AUTH-011", retryable = false),
                )
                return
            }
            val challenge = api.requestEmailChallenge(baseUrl, email, store.clientInstallationId())
            val pending = PendingEmailChallenge(
                baseUrl = baseUrl,
                email = email,
                challengeId = challenge.challengeId,
                expiresAt = challenge.expiresAt,
                resendAfter = challenge.resendAfter,
                exchangeIdempotencyKey = UUID.randomUUID().toString(),
            )
            store.savePendingEmailChallenge(pending)
            _state.value = _state.value.copy(
                stage = AccountStage.CODE_SENT,
                busy = false,
                email = email,
                code = "",
                error = null,
            )
            applyChallengeTiming(pending)
            startChallengeTimer()
        } catch (error: AccountApiException) {
            showError(error, retryAction)
        } catch (_: Exception) {
            showError("HR-ACCOUNT-002", retryable = true, retryAction = retryAction)
        }
    }

    fun verifyCode() = viewModelScope.launch {
        val pending = store.loadPendingEmailChallenge() ?: run {
            _state.value = _state.value.copy(
                stage = AccountStage.SIGNED_OUT,
                error = AccountUiError("HR-AUTH-009", retryable = false),
            )
            return@launch
        }
        if (!applyChallengeTiming(pending)) return@launch
        val code = _state.value.code
        if (code.length != 6 || _state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val response = api.exchangeEmailCode(
                baseUrl = pending.baseUrl,
                challengeId = pending.challengeId,
                email = pending.email,
                code = code,
                clientInstallationId = store.clientInstallationId(),
                displayName = phoneDisplayName(),
                appVersion = BuildConfig.VERSION_NAME,
                idempotencyKey = pending.exchangeIdempotencyKey,
            )
            val keepLegacyTransportUntilProbe = !sessions.requiresAccountReauthentication() &&
                runCatching { legacyCredentials.load() }.getOrNull() != null
            sessions.activate(
                pending.baseUrl,
                response,
                keepLegacyTransportUntilProbe = keepLegacyTransportUntilProbe,
            )
            challengeTimerJob?.cancel()
            challengeTimerJob = null
            _state.value = _state.value.copy(
                stage = AccountStage.SIGNED_IN,
                session = sessions.session.value,
                code = "",
                busy = false,
            )
            loadDevices(sessions.session.value ?: return@launch)
        } catch (error: AccountApiException) {
            if (error.errorCode == "HR-AUTH-009") {
                store.clearPendingEmailChallenge()
                challengeTimerJob?.cancel()
                challengeTimerJob = null
            }
            showError(error, AccountRetryAction.VERIFY_CODE)
        } catch (_: Exception) {
            showError(
                "HR-ACCOUNT-002",
                retryable = true,
                retryAction = AccountRetryAction.VERIFY_CODE,
            )
        }
    }

    fun useDevice(device: AccountDeviceDto) = viewModelScope.launch {
        if (_state.value.selectingDeviceId != null) return@launch
        activateDevice(device, _state.value.deviceRouteMode)
    }

    private suspend fun activateDevice(device: AccountDeviceDto, routeMode: AccountDeviceRouteMode) {
        retryDevice = device
        _state.value = _state.value.copy(selectingDeviceId = device.deviceId, error = null)
        try {
            val current = sessions.session.value ?: return
            val bearer = sessions.accessToken() ?: return
            val selected = when (routeMode) {
                AccountDeviceRouteMode.SINGLE_BINDING -> {
                    api.probeSingleBinding(current.baseUrl, bearer)
                    device.copy(isDefault = true)
                }
                AccountDeviceRouteMode.EXPLICIT_DEVICE -> {
                    api.selectDefaultDevice(current.baseUrl, bearer, device.deviceId).also {
                        api.probeDevice(current.baseUrl, bearer, it.deviceId)
                    }
                }
            }
            sessions.selectDevice(selected, routeMode)
            _state.value = _state.value.copy(
                session = sessions.session.value,
                devices = _state.value.devices.map {
                    if (it.deviceId == selected.deviceId) selected else it.copy(isDefault = false)
                },
                selectingDeviceId = null,
            )
            emptyDevicePollJob?.cancel()
            emptyDevicePollJob = null
            retryDevice = null
            chat.reconnect()
        } catch (error: AccountApiException) {
            handleAuthenticatedAccountError(error, device.deviceId)
            showError(error, AccountRetryAction.SELECT_DEVICE)
        } catch (_: Exception) {
            showError(
                "HR-ACCOUNT-002",
                retryable = true,
                retryAction = AccountRetryAction.SELECT_DEVICE,
            )
        } finally {
            _state.value = _state.value.copy(selectingDeviceId = null, session = sessions.session.value)
        }
    }

    fun signOut(onFinished: () -> Unit = {}) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, error = null)
        runCatching { sessions.signOut() }
        emptyDevicePollJob?.cancel()
        emptyDevicePollJob = null
        challengeTimerJob?.cancel()
        challengeTimerJob = null
        chat.reconnect()
        _state.value = AccountDevicesUiState(stage = AccountStage.DISCOVERING)
        discoverSignedOut()
        onFinished()
    }

    fun beginAccountDeletion() {
        val current = sessions.session.value ?: return
        if (!_state.value.accountDeletionEnabled || current.accountEmail.isNullOrBlank()) return
        _state.value = _state.value.copy(
            accountDeletion = AccountDeletionUiState(),
            error = null,
        )
    }

    fun onAccountDeletionConfirmationChange(value: String) {
        val deletion = _state.value.accountDeletion ?: return
        if (deletion.busy || deletion.stage != AccountDeletionStage.CONFIRMING) return
        _state.value = _state.value.copy(
            accountDeletion = deletion.copy(typedConfirmation = value.take(32), error = null),
        )
    }

    fun onAccountDeletionAcknowledgedChange(value: Boolean) {
        val deletion = _state.value.accountDeletion ?: return
        if (deletion.busy || deletion.stage != AccountDeletionStage.CONFIRMING) return
        _state.value = _state.value.copy(
            accountDeletion = deletion.copy(acknowledged = value, error = null),
        )
    }

    fun onAccountDeletionCodeChange(value: String) {
        val deletion = _state.value.accountDeletion ?: return
        if (deletion.busy || deletion.stage != AccountDeletionStage.CODE_SENT) return
        _state.value = _state.value.copy(
            accountDeletion = deletion.copy(
                code = value.filter(Char::isDigit).take(6),
                error = null,
            ),
        )
    }

    fun dismissAccountDeletion() {
        val deletion = _state.value.accountDeletion ?: return
        // Once DELETE may have reached the server, discarding its replay key could strand an
        // ambiguous operation. Keep the recovery dialog until the exact request resolves.
        if (deletion.busy || deletion.stage == AccountDeletionStage.RETRY_COMMIT) return
        store.clearPendingAccountDeletion()
        accountDeletionTimerJob?.cancel()
        accountDeletionTimerJob = null
        _state.value = _state.value.copy(accountDeletion = null)
    }

    fun requestAccountDeletionCode() = viewModelScope.launch {
        val deletion = _state.value.accountDeletion ?: return@launch
        val current = sessions.session.value ?: return@launch
        val email = current.accountEmail?.trim().orEmpty()
        if (deletion.busy || deletion.typedConfirmation != ACCOUNT_DELETION_CONFIRMATION ||
            !deletion.acknowledged || email.isBlank()
        ) return@launch
        requestAccountDeletionChallenge(current, email, deletion)
    }

    fun resendAccountDeletionCode() = viewModelScope.launch {
        val deletion = _state.value.accountDeletion ?: return@launch
        val current = sessions.session.value ?: return@launch
        val pending = store.loadPendingAccountDeletion() ?: return@launch
        if (deletion.busy || deletion.stage != AccountDeletionStage.CODE_SENT ||
            !applyAccountDeletionTiming(pending) || _state.value.accountDeletion?.resendInSeconds != 0
        ) return@launch
        requestAccountDeletionChallenge(current, pending.email, deletion.copy(code = ""))
    }

    private suspend fun requestAccountDeletionChallenge(
        current: AccountSession,
        email: String,
        prior: AccountDeletionUiState,
    ) {
        setAccountDeletionState(prior.copy(busy = true, error = null))
        try {
            val bearer = sessions.accessToken() ?: run {
                showAccountDeletionError("HR-AUTH-003", false, AccountDeletionStage.CONFIRMING)
                return
            }
            val challenge = api.requestEmailReauthenticationChallenge(current.baseUrl, email, bearer)
            val pending = PendingAccountDeletion(
                baseUrl = current.baseUrl,
                accountId = current.accountId,
                email = email,
                challengeId = challenge.challengeId,
                expiresAt = challenge.expiresAt,
                resendAfter = challenge.resendAfter,
                reauthenticationIdempotencyKey = UUID.randomUUID().toString(),
            )
            store.savePendingAccountDeletion(pending)
            setAccountDeletionState(
                prior.copy(
                    stage = AccountDeletionStage.CODE_SENT,
                    code = "",
                    busy = false,
                    error = null,
                ),
            )
            applyAccountDeletionTiming(pending)
            startAccountDeletionTimer()
        } catch (error: AccountApiException) {
            handleAuthenticatedAccountError(error, rejectedDeviceId = null)
            showAccountDeletionError(error.errorCode, error.retryable, AccountDeletionStage.CONFIRMING)
        } catch (_: Exception) {
            showAccountDeletionError("HR-ACCOUNT-002", true, AccountDeletionStage.CONFIRMING)
        }
    }

    fun verifyAndDeleteAccount() = viewModelScope.launch {
        val deletion = _state.value.accountDeletion ?: return@launch
        val current = sessions.session.value ?: return@launch
        val pending = store.loadPendingAccountDeletion() ?: return@launch
        if (deletion.busy || deletion.stage != AccountDeletionStage.CODE_SENT || deletion.code.length != 6 ||
            !applyAccountDeletionTiming(pending)
        ) return@launch
        setAccountDeletionState(deletion.copy(busy = true, error = null))
        try {
            val bearer = sessions.accessToken() ?: run {
                showAccountDeletionError("HR-AUTH-003", false, AccountDeletionStage.CONFIRMING)
                return@launch
            }
            val proof = api.reauthenticateEmail(
                baseUrl = pending.baseUrl,
                challengeId = pending.challengeId,
                email = pending.email,
                code = deletion.code,
                scope = ACCOUNT_DELETION_SCOPE,
                bearer = bearer,
                idempotencyKey = pending.reauthenticationIdempotencyKey,
            )
            if (proof.scope != ACCOUNT_DELETION_SCOPE || proof.grant.isBlank()) {
                throw IllegalStateException("invalid account deletion proof")
            }
            val ready = pending.copy(
                grant = proof.grant,
                deletionIdempotencyKey = UUID.randomUUID().toString(),
            )
            // Commit recovery material before the destructive request leaves the process.
            store.savePendingAccountDeletion(ready)
            setAccountDeletionState(
                deletion.copy(
                    stage = AccountDeletionStage.RETRY_COMMIT,
                    code = "",
                    busy = true,
                    error = null,
                ),
            )
            commitAccountDeletion(sessions.session.value ?: current, ready)
        } catch (error: AccountApiException) {
            if (error.errorCode == "HR-AUTH-006" || error.errorCode == "HR-AUTH-009") {
                invalidateAccountDeletionProof(error.errorCode)
            } else {
                handleAuthenticatedAccountError(error, rejectedDeviceId = null)
                showAccountDeletionError(error.errorCode, error.retryable, AccountDeletionStage.CODE_SENT)
            }
        } catch (_: Exception) {
            showAccountDeletionError("HR-ACCOUNT-002", true, AccountDeletionStage.CODE_SENT)
        }
    }

    fun retryAccountDeletionCommit() = viewModelScope.launch {
        val current = sessions.session.value ?: return@launch
        val pending = store.loadPendingAccountDeletion()?.takeIf { it.isReadyToCommit } ?: return@launch
        commitAccountDeletion(current, pending)
    }

    private suspend fun commitAccountDeletion(current: AccountSession, pending: PendingAccountDeletion) {
        val grant = pending.grant ?: return
        val mutationKey = pending.deletionIdempotencyKey ?: return
        setAccountDeletionState(
            (_state.value.accountDeletion ?: AccountDeletionUiState(AccountDeletionStage.RETRY_COMMIT)).copy(
                stage = AccountDeletionStage.RETRY_COMMIT,
                code = "",
                busy = true,
                error = null,
            ),
        )
        try {
            api.deleteAccount(pending.baseUrl, current.accessToken, grant, mutationKey)
            finishAccountDeletion()
        } catch (error: AccountApiException) {
            when (error.errorCode) {
                "HR-ACCOUNT-012" -> finishAccountDeletion()
                "HR-AUTH-006", "HR-AUTH-009" -> invalidateAccountDeletionProof(error.errorCode)
                else -> {
                    handleAuthenticatedAccountError(error, rejectedDeviceId = null)
                    if (sessions.session.value != null) {
                        showAccountDeletionError(
                            error.errorCode,
                            error.retryable,
                            AccountDeletionStage.RETRY_COMMIT,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            showAccountDeletionError("HR-ACCOUNT-002", true, AccountDeletionStage.RETRY_COMMIT)
        }
    }

    private fun invalidateAccountDeletionProof(code: String) {
        store.clearPendingAccountDeletion()
        accountDeletionTimerJob?.cancel()
        accountDeletionTimerJob = null
        setAccountDeletionState(
            AccountDeletionUiState(
                stage = AccountDeletionStage.CONFIRMING,
                error = AccountUiError(code, retryable = false),
            ),
        )
    }

    private fun finishAccountDeletion() {
        accountDeletionTimerJob?.cancel()
        accountDeletionTimerJob = null
        emptyDevicePollJob?.cancel()
        emptyDevicePollJob = null
        sessions.completeAccountDeletion()
        chat.disconnect()
        showAccountDeletionCommitted()
    }

    private fun showAccountDeletionCommitted() {
        _state.value = AccountDevicesUiState(stage = AccountStage.ACCOUNT_DELETION_COMMITTED)
    }

    fun startOver() {
        store.clearPendingEmailChallenge()
        challengeTimerJob?.cancel()
        challengeTimerJob = null
        _state.value = _state.value.copy(
            stage = AccountStage.SIGNED_OUT,
            code = "",
            codeExpiresInSeconds = 0,
            resendInSeconds = 0,
            error = null,
        )
    }

    fun allowExplicitLegacyFallback() {
        sessions.allowExplicitLegacyFallback()
    }

    fun startNewAccountSignIn() {
        store.setAccountDeletionCommitted(false)
        // Stay fail-closed while the replacement account signs in; retained App Token data must
        // not become active merely because the terminal deletion screen was dismissed.
        store.setAccountReauthenticationRequired(true)
        _state.value = AccountDevicesUiState(stage = AccountStage.DISCOVERING)
        refresh()
    }

    fun retryError() {
        val error = _state.value.error ?: return
        if (!error.retryable || _state.value.busy) return
        when (error.retryAction) {
            AccountRetryAction.REFRESH -> refresh()
            AccountRetryAction.SEND_CODE -> sendCode()
            AccountRetryAction.RESEND_CODE -> resendCode()
            AccountRetryAction.VERIFY_CODE -> verifyCode()
            AccountRetryAction.SELECT_DEVICE -> retryDevice?.let(::useDevice) ?: refresh()
        }
    }

    private suspend fun discoverSignedOut() {
        val baseUrl = accountBaseUrl()
        try {
            val capabilities = api.capabilities(baseUrl)
            val available = capabilities.accountAuth.enabled && capabilities.accountAuth.android &&
                "email_otp" in capabilities.accountAuth.providers
            val pending = store.loadPendingEmailChallenge()
            val timing = pending?.let { emailChallengeTiming(it, clock.now()) }
            if (pending != null && timing?.expired != false) store.clearPendingEmailChallenge()
            _state.value = _state.value.copy(
                stage = if (available) {
                    if (pending != null && timing?.expired == false) AccountStage.CODE_SENT else AccountStage.SIGNED_OUT
                } else AccountStage.UNAVAILABLE,
                busy = false,
                codeExpiresInSeconds = timing?.takeUnless { it.expired }?.expiresInSeconds ?: 0,
                resendInSeconds = timing?.takeUnless { it.expired }?.resendInSeconds ?: 0,
                maxOwnedDevices = capabilities.binding.maxActiveConnectorsPerAccount,
                deviceRouteMode = if (capabilities.binding.supportsDeviceSelection) {
                    AccountDeviceRouteMode.EXPLICIT_DEVICE
                } else {
                    AccountDeviceRouteMode.SINGLE_BINDING
                },
                supportsDeviceSharing = capabilities.binding.supportsDeviceSharing,
                error = when {
                    !available -> AccountUiError("HR-AUTH-011", retryable = false)
                    pending != null && timing?.expired != false -> AccountUiError("HR-AUTH-009", retryable = false)
                    else -> null
                },
            )
            if (pending != null && timing?.expired == false && devicePageVisible) startChallengeTimer()
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                stage = AccountStage.UNAVAILABLE,
                busy = false,
                error = AccountUiError("HR-ACCOUNT-002", retryable = true),
            )
        }
    }

    private suspend fun loadDevices(current: AccountSession) {
        try {
            val capabilities = try {
                api.capabilities(current.baseUrl)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val accountDeletionEnabled = capabilities?.accountAuth?.accountDeletion == true &&
                !current.accountEmail.isNullOrBlank()
            // Account deletion is an account-level capability. Keep it usable even if the
            // independent remote-device listing is temporarily unavailable.
            _state.value = _state.value.copy(accountDeletionEnabled = accountDeletionEnabled)
            val bearer = sessions.accessToken() ?: return
            val routeMode = when {
                capabilities == null -> current.deviceRouteMode
                capabilities.binding.supportsDeviceSelection -> AccountDeviceRouteMode.EXPLICIT_DEVICE
                else -> AccountDeviceRouteMode.SINGLE_BINDING
            }
            val response = when {
                capabilities?.binding?.enabled == false -> AccountDevicesResponseDto(
                    maxOwnedDevices = capabilities.binding.maxActiveConnectorsPerAccount,
                )
                routeMode == AccountDeviceRouteMode.EXPLICIT_DEVICE ->
                    api.devices(current.baseUrl, bearer)
                else -> {
                    val snapshot = api.binding(current.baseUrl, bearer)
                    AccountDevicesResponseDto(
                        items = snapshot.binding?.takeIf { snapshot.state == "bound" }
                            ?.let { listOf(it.asOwnedDevice()) }
                            .orEmpty(),
                        maxOwnedDevices = capabilities?.binding?.maxActiveConnectorsPerAccount ?: 1,
                    )
                }
            }
            val selectedId = sessions.session.value?.selectedDeviceId
            val selectedDeviceWasRemoved = selectedId != null && response.items.none { it.deviceId == selectedId }
            val selectedDevice = response.items.firstOrNull { it.deviceId == selectedId }
            val routeModeChanged = selectedDevice != null && current.deviceRouteMode != routeMode
            if (selectedDeviceWasRemoved) {
                sessions.clearDeviceSelection()
            }
            _state.value = _state.value.copy(
                stage = AccountStage.SIGNED_IN,
                session = sessions.session.value,
                devices = response.items,
                maxOwnedDevices = response.maxOwnedDevices,
                deviceRouteMode = routeMode,
                supportsDeviceSharing = capabilities?.binding?.supportsDeviceSharing == true,
                accountDeletionEnabled = accountDeletionEnabled,
                busy = false,
            )
            // One available Mac is unambiguous and should connect without an extra tap. With
            // multiple owned/shared Macs, the user must choose explicitly. Re-read the local
            // selection after revocation cleanup: using the stale pre-refresh id here left a sole
            // replacement Mac unselected until the user tapped Refresh/Use.
            if (sessions.session.value?.selectedDeviceId == null && response.items.size == 1) {
                activateDevice(response.items.single(), routeMode)
                // If the replacement itself failed, retire the now-invalid old socket. A successful
                // activation already reconnects exactly once to the replacement.
                if (selectedDeviceWasRemoved && sessions.session.value?.selectedDeviceId == null) {
                    chat.reconnect()
                }
            } else if (routeModeChanged) {
                activateDevice(checkNotNull(selectedDevice), routeMode)
            } else if (response.items.isEmpty()) {
                if (selectedDeviceWasRemoved) chat.reconnect()
                scheduleEmptyDevicePoll()
            } else {
                if (selectedDeviceWasRemoved) chat.reconnect()
                emptyDevicePollJob?.cancel()
                emptyDevicePollJob = null
            }
        } catch (error: AccountApiException) {
            handleAuthenticatedAccountError(error, rejectedDeviceId = null)
            showError(error, AccountRetryAction.REFRESH)
        } catch (_: Exception) {
            showError(
                "HR-ACCOUNT-002",
                retryable = true,
                retryAction = AccountRetryAction.REFRESH,
            )
        }
    }

    private fun accountBaseUrl(): String = sessions.session.value?.baseUrl
        ?: store.loadPendingEmailChallenge()?.baseUrl
        ?: store.lastAccountBaseUrl()
        ?: runCatching { legacyCredentials.load()?.baseUrl }
            .getOrNull()
            ?.takeUnless(::isLoopbackGatewayBaseUrl)
        ?: DEFAULT_REMOTE_GATEWAY_URL

    /** Account-control errors use the same session/device recovery as Hermes REST responses. */
    private fun handleAuthenticatedAccountError(error: AccountApiException, rejectedDeviceId: String?) {
        val beforeMode = sessions.transportMode()
        val beforeRoute = sessions.transportRoutingContext()?.deviceId
        sessions.handleRestRejection(error.statusCode, error.errorCode, rejectedDeviceId)
        val afterMode = sessions.transportMode()
        val afterRoute = sessions.transportRoutingContext()?.deviceId
        when {
            afterMode == AccountTransportMode.REAUTHENTICATION_REQUIRED ||
                afterMode == AccountTransportMode.DEVICE_SELECTION_REQUIRED ->
                chat.disconnect()
            beforeMode == AccountTransportMode.ACCOUNT && beforeRoute != afterRoute ->
                chat.reconnect()
        }
    }

    private fun showError(error: AccountApiException, retryAction: AccountRetryAction) =
        showError(error.errorCode, error.retryable, retryAction)

    private fun showError(code: String, retryable: Boolean, retryAction: AccountRetryAction) {
        _state.value = _state.value.copy(
            busy = false,
            selectingDeviceId = null,
            session = sessions.session.value,
            stage = if (store.accountDeletionCommitted()) AccountStage.ACCOUNT_DELETION_COMMITTED
                else if (sessions.session.value != null) AccountStage.SIGNED_IN
                else if (store.loadPendingEmailChallenge() != null) AccountStage.CODE_SENT
                else AccountStage.SIGNED_OUT,
            error = AccountUiError(code, retryable, retryAction),
        )
        if (retryable) scheduleEmptyDevicePoll()
    }

    private fun scheduleEmptyDevicePoll() {
        if (!devicePageVisible || sessions.session.value == null || _state.value.devices.isNotEmpty() ||
            emptyDevicePollJob?.isActive == true
        ) return
        emptyDevicePollJob = viewModelScope.launch {
            delay(EMPTY_DEVICE_POLL_MS)
            emptyDevicePollJob = null
            if (devicePageVisible && sessions.session.value != null && _state.value.devices.isEmpty()) {
                refresh()
            }
        }
    }

    private fun startChallengeTimer() {
        challengeTimerJob?.cancel()
        challengeTimerJob = null
        if (!devicePageVisible || _state.value.stage != AccountStage.CODE_SENT) return
        val pending = store.loadPendingEmailChallenge() ?: return
        if (!applyChallengeTiming(pending)) return
        challengeTimerJob = viewModelScope.launch {
            while (_state.value.stage == AccountStage.CODE_SENT) {
                delay(CHALLENGE_TICK_MS)
                val active = store.loadPendingEmailChallenge() ?: break
                if (!applyChallengeTiming(active)) break
            }
        }
    }

    private fun applyChallengeTiming(pending: PendingEmailChallenge): Boolean {
        val timing = emailChallengeTiming(pending, clock.now())
        if (timing.expired) {
            store.clearPendingEmailChallenge()
            challengeTimerJob?.cancel()
            challengeTimerJob = null
            _state.value = _state.value.copy(
                stage = AccountStage.SIGNED_OUT,
                code = "",
                codeExpiresInSeconds = 0,
                resendInSeconds = 0,
                error = AccountUiError("HR-AUTH-009", retryable = false),
            )
            return false
        }
        _state.value = _state.value.copy(
            codeExpiresInSeconds = timing.expiresInSeconds,
            resendInSeconds = timing.resendInSeconds,
        )
        return true
    }

    private fun setAccountDeletionState(deletion: AccountDeletionUiState) {
        _state.value = _state.value.copy(
            busy = false,
            session = sessions.session.value,
            accountDeletion = deletion,
        )
    }

    private fun showAccountDeletionError(
        code: String,
        retryable: Boolean,
        stage: AccountDeletionStage,
    ) {
        if (store.accountDeletionCommitted()) {
            showAccountDeletionCommitted()
            return
        }
        if (sessions.session.value == null) {
            _state.value = _state.value.copy(
                stage = AccountStage.SIGNED_OUT,
                session = null,
                busy = false,
                accountDeletion = null,
                error = AccountUiError(code, retryable),
            )
            return
        }
        val current = _state.value.accountDeletion ?: AccountDeletionUiState(stage)
        _state.value = _state.value.copy(
            stage = AccountStage.SIGNED_IN,
            session = sessions.session.value,
            busy = false,
            accountDeletion = current.copy(
                stage = stage,
                busy = false,
                error = AccountUiError(code, retryable),
            ),
        )
    }

    private fun startAccountDeletionTimer() {
        accountDeletionTimerJob?.cancel()
        accountDeletionTimerJob = null
        if (!devicePageVisible ||
            _state.value.accountDeletion?.stage != AccountDeletionStage.CODE_SENT
        ) return
        val pending = store.loadPendingAccountDeletion() ?: return
        if (!applyAccountDeletionTiming(pending)) return
        accountDeletionTimerJob = viewModelScope.launch {
            while (_state.value.accountDeletion?.stage == AccountDeletionStage.CODE_SENT) {
                delay(CHALLENGE_TICK_MS)
                val active = store.loadPendingAccountDeletion() ?: break
                if (!applyAccountDeletionTiming(active)) break
            }
        }
    }

    private fun applyAccountDeletionTiming(pending: PendingAccountDeletion): Boolean {
        if (pending.isReadyToCommit) return true
        val timing = accountDeletionChallengeTiming(pending, clock.now())
        if (timing.expired) {
            store.clearPendingAccountDeletion()
            accountDeletionTimerJob?.cancel()
            accountDeletionTimerJob = null
            setAccountDeletionState(
                AccountDeletionUiState(
                    stage = AccountDeletionStage.CONFIRMING,
                    error = AccountUiError("HR-AUTH-009", retryable = false),
                ),
            )
            return false
        }
        val current = _state.value.accountDeletion ?: return false
        setAccountDeletionState(
            current.copy(
                codeExpiresInSeconds = timing.expiresInSeconds,
                resendInSeconds = timing.resendInSeconds,
            ),
        )
        return true
    }

    private fun phoneDisplayName(): String = runCatching {
        listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }.getOrNull().orEmpty().ifBlank { "Android phone" }.take(128)

    companion object {
        const val EMPTY_DEVICE_POLL_MS = 5_000L
        const val CHALLENGE_TICK_MS = 1_000L
        const val ACCOUNT_DELETION_CONFIRMATION = "DELETE"
        const val ACCOUNT_DELETION_SCOPE = "account.delete"

        fun looksLikeEmail(value: String): Boolean {
            val at = value.indexOf('@')
            return at in 1 until value.lastIndex && value.substring(at + 1).contains('.') && value.length <= 254
        }
    }
}

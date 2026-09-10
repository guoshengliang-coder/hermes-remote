package com.hermes.client.data.auth

import com.hermes.client.data.network.AccountApi
import com.hermes.client.data.network.AccountApiException
import com.hermes.client.data.network.AccountDeviceDto
import com.hermes.client.data.network.AccountExchangeResponseDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

data class AccountConnection(
    val baseUrl: String,
    val bearer: String,
    val deviceId: String,
    val deviceRouteMode: AccountDeviceRouteMode,
)

data class AccountControlConnection(
    val baseUrl: String,
    val bearer: String,
)

data class AccountRoutingContext(
    val accountId: String,
    val deviceId: String,
)

enum class AccountTransportMode {
    ACCOUNT,
    /** A verified account exists beside the still-authoritative, working Legacy transport. */
    ACCOUNT_PENDING,
    DEVICE_SELECTION_REQUIRED,
    REAUTHENTICATION_REQUIRED,
    ACCOUNT_DELETION_COMMITTED,
    LEGACY,
}

/** Owns refresh rotation and the single active account-mode routing decision. */
class AccountSessionManager(
    private val store: AccountSessionStore,
    private val api: AccountApi,
    private val now: () -> Instant = { Instant.now() },
) {
    private val refreshMutex = Mutex()
    // Hilt creates networking singletons while HermesApp starts, including in pure Robolectric UI
    // tests where AndroidKeyStore is intentionally unavailable. Defer opening encrypted storage
    // until account state is actually observed or used.
    private val sessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MutableStateFlow(store.loadAccountSession())
    }
    private val _session: MutableStateFlow<AccountSession?> get() = sessionDelegate.value
    val session: StateFlow<AccountSession?> get() = _session.asStateFlow()
    @Volatile private var transportDeviceId: String? = null

    /** App-start background services must not force AndroidKeyStore open before an Activity exists. */
    fun hasLoadedConnection(): Boolean = sessionDelegate.isInitialized() &&
        transportMode() == AccountTransportMode.ACCOUNT

    /** Single source of truth for whether transport may use account or retained legacy credentials. */
    fun transportMode(): AccountTransportMode {
        if (store.accountDeletionCommitted()) return AccountTransportMode.ACCOUNT_DELETION_COMMITTED
        if (store.accountReauthenticationRequired()) return AccountTransportMode.REAUTHENTICATION_REQUIRED
        if (store.explicitLegacyConnectionSelected()) return AccountTransportMode.LEGACY
        val current = _session.value ?: return AccountTransportMode.LEGACY
        if (current.activationPending) return AccountTransportMode.ACCOUNT_PENDING
        return if (current.selectedDeviceId == null) {
            AccountTransportMode.DEVICE_SELECTION_REQUIRED
        } else {
            AccountTransportMode.ACCOUNT
        }
    }

    fun activate(
        baseUrl: String,
        response: AccountExchangeResponseDto,
        keepLegacyTransportUntilProbe: Boolean = false,
    ) {
        val value = AccountSession(
            baseUrl = normalizeGatewayBaseUrl(baseUrl),
            accountId = response.account.id,
            accountDisplayName = response.account.displayName,
            accountEmail = response.account.email,
            installationId = response.installation.id,
            installationDisplayName = response.installation.displayName,
            accessToken = response.session.accessToken,
            accessExpiresAt = response.session.accessExpiresAt,
            refreshToken = response.session.refreshToken,
            refreshExpiresAt = response.session.refreshExpiresAt,
            activationPending = keepLegacyTransportUntilProbe,
        )
        store.saveAccountSession(value)
        // Fail closed across a process crash: commit the new account first, then activate it.
        // If the second write is interrupted the app remains in explicit Legacy mode.
        store.setExplicitLegacyConnectionSelected(false)
        store.setAccountDeletionCommitted(false)
        store.clearPendingEmailChallenge()
        store.clearPendingAccountDeletion()
        _session.value = value
    }

    fun selectDevice(
        device: AccountDeviceDto,
        routeMode: AccountDeviceRouteMode = AccountDeviceRouteMode.EXPLICIT_DEVICE,
    ) {
        val current = _session.value ?: return
        val updated = current.copy(
            selectedDeviceId = device.deviceId,
            selectedDeviceName = device.desktopDisplayName,
            activationPending = false,
            deviceRouteMode = routeMode,
        )
        store.saveAccountSession(updated)
        store.setExplicitLegacyConnectionSelected(false)
        _session.value = updated
        transportDeviceId = device.deviceId
    }

    fun clearDeviceSelection() {
        val current = _session.value ?: return
        update(current.copy(selectedDeviceId = null, selectedDeviceName = null))
        transportDeviceId = null
    }

    /** Route foreground transport to an existing conversation without changing the user's default Mac. */
    fun routeToDevice(deviceId: String): Boolean {
        if (_session.value == null || deviceId.isBlank()) return false
        if (transportMode() != AccountTransportMode.ACCOUNT) return false
        val changed = transportDeviceId != deviceId
        transportDeviceId = deviceId
        return changed
    }

    /** Restore list/new-conversation traffic to the selected/default Mac. */
    fun restoreSelectedDeviceRoute(): Boolean {
        // ViewModels are created by Compose previews/Robolectric before account mode is observed.
        // A passive restore must not be the operation that opens AndroidKeyStore; startup/account
        // discovery loads it explicitly when account state is actually needed.
        val selected = loadedSession()?.selectedDeviceId ?: return false
        if (transportMode() != AccountTransportMode.ACCOUNT) return false
        val changed = transportDeviceId != null && transportDeviceId != selected
        transportDeviceId = selected
        return changed
    }

    /** Stable account/default route used by lists, search, settings, and new conversations. */
    fun routingContext(): AccountRoutingContext? {
        val current = loadedSession() ?: return null
        if (transportMode() != AccountTransportMode.ACCOUNT) return null
        val device = current.selectedDeviceId ?: return null
        return AccountRoutingContext(current.accountId, device)
    }

    /** Foreground WebSocket route, which may temporarily follow an older conversation's Mac. */
    fun transportRoutingContext(): AccountRoutingContext? {
        val current = loadedSession() ?: return null
        if (transportMode() != AccountTransportMode.ACCOUNT) return null
        val device = transportDeviceId ?: current.selectedDeviceId ?: return null
        return AccountRoutingContext(current.accountId, device)
    }

    private fun loadedSession(): AccountSession? =
        if (sessionDelegate.isInitialized()) sessionDelegate.value.value else null

    suspend fun accessToken(): String? {
        val current = _session.value ?: return null
        if (!needsRefresh(current)) return current.accessToken
        return refreshMutex.withLock {
            val latest = _session.value ?: return@withLock null
            if (!needsRefresh(latest)) return@withLock latest.accessToken
            val key = latest.pendingRefreshIdempotencyKey ?: UUID.randomUUID().toString()
            if (latest.pendingRefreshIdempotencyKey == null) update(latest.copy(pendingRefreshIdempotencyKey = key))
            try {
                val tokens = api.refresh(
                    latest.baseUrl,
                    latest.refreshToken,
                    store.clientInstallationId(),
                    key,
                )
                val refreshed = latest.copy(
                    accessToken = tokens.accessToken,
                    accessExpiresAt = tokens.accessExpiresAt,
                    refreshToken = tokens.refreshToken,
                    refreshExpiresAt = tokens.refreshExpiresAt,
                    pendingRefreshIdempotencyKey = null,
                )
                update(refreshed)
                refreshed.accessToken
            } catch (error: AccountApiException) {
                if (error.statusCode == 401 || error.errorCode in INVALID_SESSION_CODES) {
                    invalidateAccountSession()
                }
                throw error
            }
        }
    }

    suspend fun connection(deviceIdOverride: String? = null): AccountConnection? {
        if (transportMode() != AccountTransportMode.ACCOUNT) return null
        val selected = deviceIdOverride?.takeIf { it.isNotBlank() }
            ?: _session.value?.selectedDeviceId
            ?: return null
        val bearer = accessToken() ?: return null
        val current = _session.value ?: return null
        return AccountConnection(current.baseUrl, bearer, selected, current.deviceRouteMode)
    }

    /** Account-owned endpoints such as the phone lifecycle inbox do not belong to one Mac. */
    suspend fun accountControlConnection(): AccountControlConnection? {
        val mode = transportMode()
        if (mode != AccountTransportMode.ACCOUNT && mode != AccountTransportMode.DEVICE_SELECTION_REQUIRED) {
            return null
        }
        val current = _session.value ?: return null
        val bearer = accessToken() ?: return null
        return AccountControlConnection(current.baseUrl, bearer)
    }

    /** Stable, non-secret scope used to isolate the local inbox cursor between phone sessions. */
    fun lifecycleCursorScope(): String? {
        val mode = transportMode()
        if (mode != AccountTransportMode.ACCOUNT && mode != AccountTransportMode.DEVICE_SELECTION_REQUIRED) {
            return null
        }
        val current = _session.value ?: return null
        return listOf(
            normalizeGatewayBaseUrl(current.baseUrl),
            current.accountId,
            current.installationId,
        ).joinToString("\u0000")
    }

    fun requiresAccountReauthentication(): Boolean = store.accountReauthenticationRequired()

    /** Called only from the explicit Legacy connection action. */
    fun allowExplicitLegacyFallback() {
        store.setAccountReauthenticationRequired(false)
        store.setAccountDeletionCommitted(false)
        store.clearPendingAccountDeletion()
        store.setExplicitLegacyConnectionSelected(true)
        transportDeviceId = null
    }

    /** Called only after the server committed permanent account deletion (or confirmed it did). */
    fun completeAccountDeletion() {
        store.completeAccountDeletion()
        store.clearPendingEmailChallenge()
        _session.value = null
        transportDeviceId = null
    }

    /** Terminal account WebSocket handshake failures map to the narrowest durable recovery. */
    fun handleTransportHandshakeRejection(statusCode: Int, rejectedDeviceId: String?) {
        when (statusCode) {
            401 -> clearLocal()
            404 -> {
                val current = _session.value ?: return
                if (rejectedDeviceId == null || rejectedDeviceId == current.selectedDeviceId) {
                    clearDeviceSelection()
                } else if (transportDeviceId == rejectedDeviceId) {
                    transportDeviceId = current.selectedDeviceId
                }
            }
        }
    }

    /** Applies the same recovery to account REST responses without treating ordinary 404s as revocation. */
    fun handleRestRejection(statusCode: Int, errorCode: String?, rejectedDeviceId: String?) {
        when {
            statusCode == 401 || errorCode in INVALID_SESSION_CODES -> invalidateAccountSession()
            errorCode == "HR-BIND-011" && !rejectedDeviceId.isNullOrBlank() ->
                handleTransportHandshakeRejection(404, rejectedDeviceId)
        }
    }

    /** WebSocket follows the foreground conversation route; ordinary REST defaults to selected. */
    suspend fun transportConnection(): AccountConnection? {
        val selected = transportDeviceId ?: _session.value?.selectedDeviceId ?: return null
        return connection(selected)
    }

    suspend fun signOut() {
        val current = _session.value ?: return
        try {
            val bearer = accessToken() ?: current.accessToken
            api.signOut(current.baseUrl, bearer)
        } finally {
            clearLocal(requireReauthentication = false)
        }
    }

    fun clearLocal(requireReauthentication: Boolean = true) {
        store.clearAccountSession(requireReauthentication)
        store.setExplicitLegacyConnectionSelected(false)
        store.clearPendingEmailChallenge()
        store.clearPendingAccountDeletion()
        store.setAccountDeletionCommitted(false)
        _session.value = null
        transportDeviceId = null
    }

    /** A failed opt-in probe restores Legacy; an already-active account still fails closed. */
    private fun invalidateAccountSession() {
        clearLocal(requireReauthentication = _session.value?.activationPending != true)
    }

    private fun update(value: AccountSession) {
        store.saveAccountSession(value)
        _session.value = value
    }

    private fun needsRefresh(value: AccountSession): Boolean {
        val expiry = runCatching { Instant.parse(value.accessExpiresAt) }.getOrNull() ?: return true
        return !expiry.isAfter(now().plusSeconds(REFRESH_SKEW_SECONDS))
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60L
        val INVALID_SESSION_CODES = setOf("HR-AUTH-003", "HR-AUTH-004", "HR-AUTH-005")
    }
}

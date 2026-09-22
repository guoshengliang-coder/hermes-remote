package com.hermes.client.notifications.push

import android.content.Context
import com.hermes.client.data.auth.AccountControlConnection
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.error.AppError
import com.hermes.client.data.error.AppErrorCode
import com.hermes.client.data.error.redactSecrets
import com.hermes.client.data.network.AccountApiException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the notification settings row shows for real-time push (docs/DESIGN.md §5.10). */
sealed interface PushStatus {
    /** The build carries no Firebase values. */
    data object NotConfigured : PushStatus

    /** The Gateway advertises no push provider (capability absent, or the route is missing). */
    data object ServerUnsupported : PushStatus

    /** No Google Play services on this phone: the 15-minute sync is the background path. */
    data object NoGooglePlayServices : PushStatus

    /** Notifications are off, or the phone is not in account mode: nothing to register. */
    data object Inactive : PushStatus

    data object Registering : PushStatus

    data object Enabled : PushStatus

    /** HR-NOTIF-002. Periodic sync keeps working; the row offers Retry. */
    data class Failed(val error: AppError) : PushStatus
}

/** The account this phone installation is registered under; changes mean re-registration. */
data class PushIdentity(
    val baseUrl: String,
    val accountId: String,
    val installationId: String,
)

/** Account state the registration needs, kept narrow so tests do not need AndroidKeyStore. */
interface PushAccountSource {
    /** Null when there is no active account-mode session (signed out, Legacy, pending, re-auth). */
    val identity: Flow<PushIdentity?>

    suspend fun controlConnection(): AccountControlConnection?
}

/** The three Relay calls; implemented over AccountApi. */
interface PushRegistrationApi {
    suspend fun serverSupportsFcm(baseUrl: String): Boolean
    suspend fun register(connection: AccountControlConnection, token: String)
    suspend fun unregister(connection: AccountControlConnection)
}

/**
 * Remembers what was last registered, as a hash: the FCM token is an address for this phone and
 * has no business sitting in plain preferences or reaching a log.
 */
interface PushRegistrationRecord {
    fun read(): String?
    fun write(fingerprint: String?)
}

class SharedPreferencesPushRegistrationRecord(context: Context) : PushRegistrationRecord {
    private val prefs = context.getSharedPreferences("push_registration", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(fingerprint: String?) {
        prefs.edit().apply { if (fingerprint == null) remove(KEY) else putString(KEY, fingerprint) }.apply()
    }

    private companion object {
        const val KEY = "fingerprint"
    }
}

internal fun pushRegistrationFingerprint(identity: PushIdentity, token: String): String {
    val material = listOf(PROVIDER_FCM, identity.baseUrl, identity.accountId, identity.installationId, token)
        .joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/**
 * Diagnostic text for HR-NOTIF-002. Built from the failure's kind and server code only, and the
 * token is scrubbed anyway in case an exception message ever quoted it.
 */
internal fun pushFailureCause(stage: String, error: Throwable, token: String?): String {
    val base = when (error) {
        is AccountApiException -> "stage=$stage http=${error.statusCode} code=${error.errorCode}"
        else -> "stage=$stage ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }
    val scrubbed = if (token.isNullOrEmpty()) base else base.replace(token, "<redacted>")
    return redactSecrets(scrubbed)
}

internal const val PROVIDER_FCM = "fcm"

/**
 * Keeps this phone's FCM token registered with the Relay exactly while it is useful (HG-94):
 * FCM configured and available, account mode signed in, and notifications enabled. It never
 * changes the monitoring policy — the 15-minute job and foreground socket keep running whether
 * or not push works, which is also why no failure here is ever more than a status row.
 */
class PushRegistrationManager(
    private val platform: PushPlatform,
    private val accounts: PushAccountSource,
    private val api: PushRegistrationApi,
    private val record: PushRegistrationRecord,
    private val notificationsEnabled: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<PushStatus>(PushStatus.Inactive)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /**
     * Latest (identity, notifications enabled) pair; reconcile always acts on the newest one. Null
     * until first observed, so an early onNewToken cannot mistake "not read yet" for "signed out".
     */
    @Volatile internal var desired: Pair<PushIdentity?, Boolean>? = null
    @Volatile private var availability: PushAvailability? = null

    /** Called once from Application.onCreate. Touches account storage only when FCM is usable. */
    fun start() {
        val available = platform.initialize()
        availability = available
        when (available) {
            PushAvailability.NOT_CONFIGURED -> { _status.value = PushStatus.NotConfigured; return }
            PushAvailability.NO_GOOGLE_PLAY_SERVICES -> { _status.value = PushStatus.NoGooglePlayServices; return }
            PushAvailability.AVAILABLE -> Unit
        }
        scope.launch {
            combine(accounts.identity, notificationsEnabled) { identity, enabled -> identity to enabled }
                .distinctUntilChanged()
                .collectLatest { next ->
                    desired = next
                    reconcile()
                }
        }
    }

    /** FCM rotated the token. Upload it if registration is currently wanted. */
    fun onNewToken(token: String) {
        if (availability != PushAvailability.AVAILABLE) return
        scope.launch { reconcile(tokenOverride = token) }
    }

    /** The settings row's Retry. */
    fun retry() {
        if (availability != PushAvailability.AVAILABLE) return
        scope.launch { reconcile(force = true) }
    }

    /**
     * Sign-out hook: runs with the bearer that is about to be revoked. Removes the server record
     * (best effort) and invalidates the FCM token so a signed-out phone is no longer addressable.
     */
    suspend fun unregisterForSignOut(connection: AccountControlConnection) = mutex.withLock {
        if (availability != PushAvailability.AVAILABLE) return@withLock
        if (record.read() != null) {
            runCatchingNonCancel { api.unregister(connection) }
        }
        record.write(null)
        runCatchingNonCancel { platform.deleteToken() }
        _status.value = PushStatus.Inactive
    }

    /**
     * Brings the server record in line with [desired]. The record is a fingerprint of identity and
     * token together, so a new account, installation or token each re-uploads, and nothing else does.
     */
    internal suspend fun reconcile(
        tokenOverride: String? = null,
        force: Boolean = false,
    ) = mutex.withLock {
        val (identity, enabled) = desired ?: return@withLock
        if (identity == null) {
            // Signed out, re-auth required, account deleted or back in Legacy. The server side
            // went with the installation; drop the local token so FCM stops addressing us.
            if (record.read() != null) {
                record.write(null)
                runCatchingNonCancel { platform.deleteToken() }
            }
            _status.value = PushStatus.Inactive
            return@withLock
        }
        if (!enabled) {
            if (record.read() != null) {
                runCatchingNonCancel {
                    accounts.controlConnection()?.let { connection -> api.unregister(connection) }
                }
                record.write(null)
            }
            _status.value = PushStatus.Inactive
            return@withLock
        }
        _status.value = PushStatus.Registering
        var token: String? = tokenOverride
        try {
            val connection = accounts.controlConnection()
            if (connection == null) {
                _status.value = PushStatus.Inactive
                return@withLock
            }
            if (!api.serverSupportsFcm(connection.baseUrl)) {
                record.write(null)
                _status.value = PushStatus.ServerUnsupported
                return@withLock
            }
            val current = token ?: platform.fetchToken()
            token = current
            val fingerprint = pushRegistrationFingerprint(identity, current)
            if (!force && record.read() == fingerprint) {
                _status.value = PushStatus.Enabled
                return@withLock
            }
            api.register(connection, current)
            record.write(fingerprint)
            _status.value = PushStatus.Enabled
            DebugLog.log("push", "registered for real-time push")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AccountApiException) {
            if (error.statusCode == 404 || error.statusCode == 405 || error.statusCode == 501) {
                // An older Gateway without the route: the same as no capability, not an error.
                record.write(null)
                _status.value = PushStatus.ServerUnsupported
            } else {
                fail(error, token)
            }
        } catch (error: Exception) {
            fail(error, token)
        }
    }

    private fun fail(error: Throwable, token: String?) {
        val cause = pushFailureCause("register", error, token)
        DebugLog.log("push", "registration failed: $cause")
        _status.value = PushStatus.Failed(
            AppError(
                AppErrorCode.PUSH_REGISTRATION_FAILED,
                retryable = true,
                technicalCause = cause,
                stage = "push_registration",
            ),
        )
    }

    private inline fun runCatchingNonCancel(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DebugLog.log("push", "best-effort push cleanup failed: ${error.javaClass.simpleName}")
        }
    }
}

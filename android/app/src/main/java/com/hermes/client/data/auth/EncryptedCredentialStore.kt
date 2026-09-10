package com.hermes.client.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.hermes.client.data.diagnostics.DebugLog

/**
 * Concrete secure storage backed by EncryptedSharedPreferences (security-crypto 1.0.0).
 * If migrating to a newer backend later, only this file changes — callers depend on
 * the CredentialStore interface.
 */
class EncryptedCredentialStore(private val context: Context) :
    CredentialStore,
    AccountSessionStore,
    ConversationDeviceStore {
    // EncryptedSharedPreferences keeps its Tink keyset inside this same prefs file. If that keyset
    // is corrupted (e.g. an interrupted write or an app upgrade), create() throws
    // InvalidProtocolBufferException and the app crashes on EVERY launch — before any UI. Recover
    // by wiping the backing file once and regenerating a fresh keyset: the saved credentials are
    // lost (the user re-enters them on the Setup screen), which is far better than a crash loop.
    private val prefs by lazy { openOrReset() }

    private fun create(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun openOrReset(): SharedPreferences =
        try {
            create()
        } catch (e: Exception) {
            // Corrupt/unreadable keyset. EncryptedSharedPreferences stores its keyset inside the
            // PREFS_NAME file, so deleting that and recreating recovers it (verified on a physical
            // device). If a second attempt still fails the corruption is deeper — the master key in
            // the Android keystore — so drop that too and regenerate everything, guaranteeing we
            // never crash-loop on launch.
            DebugLog.log("auth", "credential store unreadable, resetting: ${e.javaClass.simpleName}")
            runCatching { context.deleteSharedPreferences(PREFS_NAME) }
            try {
                create()
            } catch (e2: Exception) {
                DebugLog.log("auth", "reset insufficient, clearing master key: ${e2.javaClass.simpleName}")
                runCatching {
                    java.security.KeyStore.getInstance("AndroidKeyStore")
                        .apply { load(null) }
                        .deleteEntry(MASTER_KEY_ALIAS)
                }
                runCatching { context.deleteSharedPreferences(PREFS_NAME) }
                create()
            }
        }

    override fun load(): GatewayConfig? {
        val storedUrl = prefs.getString("base_url", null) ?: return null
        val url = runCatching { normalizeGatewayBaseUrl(storedUrl) }.getOrDefault(storedUrl)
        if (url != storedUrl) prefs.edit().putString("base_url", url).apply()
        return GatewayConfig(
            baseUrl = url,
            token = prefs.getString("token", null) ?: "",
            username = prefs.getString("username", null) ?: "",
            password = prefs.getString("password", null) ?: "",
        )
    }

    override fun save(config: GatewayConfig) {
        prefs.edit()
            .putString("base_url", config.baseUrl)
            .putString("token", config.token)
            .putString("username", config.username)
            .putString("password", config.password)
            .apply()
    }

    override fun clear() {
        prefs.edit()
            .remove("base_url")
            .remove("token")
            .remove("username")
            .remove("password")
            .apply()
    }

    @Synchronized
    override fun clientInstallationId(): String {
        prefs.getString(ACCOUNT_CLIENT_INSTALLATION_ID, null)?.let { return it }
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(ACCOUNT_CLIENT_INSTALLATION_ID, created).commit()
        return prefs.getString(ACCOUNT_CLIENT_INSTALLATION_ID, null) ?: created
    }

    override fun loadAccountSession(): AccountSession? {
        val baseUrl = prefs.getString("account_base_url", null) ?: return null
        val accountId = prefs.getString("account_id", null) ?: return null
        val installationId = prefs.getString("account_installation_id", null) ?: return null
        val accessToken = prefs.getString("account_access_token", null) ?: return null
        val refreshToken = prefs.getString("account_refresh_token", null) ?: return null
        return AccountSession(
            baseUrl = baseUrl,
            accountId = accountId,
            accountDisplayName = prefs.getString("account_display_name", null),
            accountEmail = prefs.getString("account_email", null),
            installationId = installationId,
            installationDisplayName = prefs.getString("account_installation_name", null).orEmpty(),
            accessToken = accessToken,
            accessExpiresAt = prefs.getString("account_access_expires_at", null).orEmpty(),
            refreshToken = refreshToken,
            refreshExpiresAt = prefs.getString("account_refresh_expires_at", null).orEmpty(),
            selectedDeviceId = prefs.getString("account_selected_device_id", null),
            selectedDeviceName = prefs.getString("account_selected_device_name", null),
            activationPending = prefs.getBoolean(ACCOUNT_ACTIVATION_PENDING, false),
            deviceRouteMode = prefs.getString(ACCOUNT_DEVICE_ROUTE_MODE, null)
                ?.let { runCatching { AccountDeviceRouteMode.valueOf(it) }.getOrNull() }
                ?: AccountDeviceRouteMode.EXPLICIT_DEVICE,
            pendingRefreshIdempotencyKey = prefs.getString("account_pending_refresh_key", null),
        )
    }

    override fun saveAccountSession(session: AccountSession) {
        prefs.edit()
            .putString("account_base_url", session.baseUrl)
            .putString(ACCOUNT_LAST_BASE_URL, session.baseUrl)
            .putString("account_id", session.accountId)
            .putString("account_display_name", session.accountDisplayName)
            .putString("account_email", session.accountEmail)
            .putString("account_installation_id", session.installationId)
            .putString("account_installation_name", session.installationDisplayName)
            .putString("account_access_token", session.accessToken)
            .putString("account_access_expires_at", session.accessExpiresAt)
            .putString("account_refresh_token", session.refreshToken)
            .putString("account_refresh_expires_at", session.refreshExpiresAt)
            .putString("account_selected_device_id", session.selectedDeviceId)
            .putString("account_selected_device_name", session.selectedDeviceName)
            .putBoolean(ACCOUNT_ACTIVATION_PENDING, session.activationPending)
            .putString(ACCOUNT_DEVICE_ROUTE_MODE, session.deviceRouteMode.name)
            .putString("account_pending_refresh_key", session.pendingRefreshIdempotencyKey)
            .putBoolean(ACCOUNT_REAUTHENTICATION_REQUIRED, false)
            .putBoolean(ACCOUNT_DELETION_COMMITTED, false)
            // Refresh idempotency must be durable before network I/O begins. Account mutations
            // are infrequent and security-sensitive, so synchronous commit is intentional here.
            .commit()
    }

    override fun clearAccountSession() = clearAccountSession(requireReauthentication = false)

    override fun clearAccountSession(requireReauthentication: Boolean) {
        prefs.edit()
            .remove("account_base_url")
            .remove("account_id")
            .remove("account_display_name")
            .remove("account_email")
            .remove("account_installation_id")
            .remove("account_installation_name")
            .remove("account_access_token")
            .remove("account_access_expires_at")
            .remove("account_refresh_token")
            .remove("account_refresh_expires_at")
            .remove("account_selected_device_id")
            .remove("account_selected_device_name")
            .remove(ACCOUNT_ACTIVATION_PENDING)
            .remove(ACCOUNT_DEVICE_ROUTE_MODE)
            .remove("account_pending_refresh_key")
            .remove("account_email_base_url")
            .remove("account_email_mailbox")
            .remove("account_email_challenge_id")
            .remove("account_email_expires_at")
            .remove("account_email_resend_after")
            .remove("account_email_exchange_key")
            .remove(ACCOUNT_DELETION_BASE_URL)
            .remove(ACCOUNT_DELETION_ACCOUNT_ID)
            .remove(ACCOUNT_DELETION_EMAIL)
            .remove(ACCOUNT_DELETION_CHALLENGE_ID)
            .remove(ACCOUNT_DELETION_EXPIRES_AT)
            .remove(ACCOUNT_DELETION_RESEND_AFTER)
            .remove(ACCOUNT_DELETION_REAUTH_KEY)
            .remove(ACCOUNT_DELETION_GRANT)
            .remove(ACCOUNT_DELETION_MUTATION_KEY)
            .putBoolean(ACCOUNT_REAUTHENTICATION_REQUIRED, requireReauthentication)
            .putBoolean(ACCOUNT_EXPLICIT_LEGACY_CONNECTION, false)
            .putBoolean(ACCOUNT_DELETION_COMMITTED, false)
            .commit()
    }

    override fun accountReauthenticationRequired(): Boolean =
        prefs.getBoolean(ACCOUNT_REAUTHENTICATION_REQUIRED, false)

    override fun lastAccountBaseUrl(): String? = prefs.getString(ACCOUNT_LAST_BASE_URL, null)

    override fun setAccountReauthenticationRequired(required: Boolean) {
        prefs.edit().putBoolean(ACCOUNT_REAUTHENTICATION_REQUIRED, required).commit()
    }

    override fun explicitLegacyConnectionSelected(): Boolean =
        prefs.getBoolean(ACCOUNT_EXPLICIT_LEGACY_CONNECTION, false)

    override fun setExplicitLegacyConnectionSelected(selected: Boolean) {
        prefs.edit().putBoolean(ACCOUNT_EXPLICIT_LEGACY_CONNECTION, selected).commit()
    }

    override fun loadPendingEmailChallenge(): PendingEmailChallenge? {
        val challengeId = prefs.getString("account_email_challenge_id", null) ?: return null
        return PendingEmailChallenge(
            baseUrl = prefs.getString("account_email_base_url", null) ?: return null,
            email = prefs.getString("account_email_mailbox", null) ?: return null,
            challengeId = challengeId,
            expiresAt = prefs.getString("account_email_expires_at", null).orEmpty(),
            resendAfter = prefs.getString("account_email_resend_after", null).orEmpty(),
            exchangeIdempotencyKey = prefs.getString("account_email_exchange_key", null) ?: return null,
        )
    }

    override fun savePendingEmailChallenge(challenge: PendingEmailChallenge) {
        prefs.edit()
            .putString("account_email_base_url", challenge.baseUrl)
            .putString("account_email_mailbox", challenge.email)
            .putString("account_email_challenge_id", challenge.challengeId)
            .putString("account_email_expires_at", challenge.expiresAt)
            .putString("account_email_resend_after", challenge.resendAfter)
            .putString("account_email_exchange_key", challenge.exchangeIdempotencyKey)
            // The exchange replay key must survive process death before code submission.
            .commit()
    }

    override fun clearPendingEmailChallenge() {
        prefs.edit()
            .remove("account_email_base_url")
            .remove("account_email_mailbox")
            .remove("account_email_challenge_id")
            .remove("account_email_expires_at")
            .remove("account_email_resend_after")
            .remove("account_email_exchange_key")
            .commit()
    }

    override fun loadPendingAccountDeletion(): PendingAccountDeletion? {
        val challengeId = prefs.getString(ACCOUNT_DELETION_CHALLENGE_ID, null) ?: return null
        return PendingAccountDeletion(
            baseUrl = prefs.getString(ACCOUNT_DELETION_BASE_URL, null) ?: return null,
            accountId = prefs.getString(ACCOUNT_DELETION_ACCOUNT_ID, null) ?: return null,
            email = prefs.getString(ACCOUNT_DELETION_EMAIL, null) ?: return null,
            challengeId = challengeId,
            expiresAt = prefs.getString(ACCOUNT_DELETION_EXPIRES_AT, null).orEmpty(),
            resendAfter = prefs.getString(ACCOUNT_DELETION_RESEND_AFTER, null).orEmpty(),
            reauthenticationIdempotencyKey =
                prefs.getString(ACCOUNT_DELETION_REAUTH_KEY, null) ?: return null,
            grant = prefs.getString(ACCOUNT_DELETION_GRANT, null),
            deletionIdempotencyKey = prefs.getString(ACCOUNT_DELETION_MUTATION_KEY, null),
        )
    }

    override fun savePendingAccountDeletion(pending: PendingAccountDeletion) {
        prefs.edit()
            .putString(ACCOUNT_DELETION_BASE_URL, pending.baseUrl)
            .putString(ACCOUNT_DELETION_ACCOUNT_ID, pending.accountId)
            .putString(ACCOUNT_DELETION_EMAIL, pending.email)
            .putString(ACCOUNT_DELETION_CHALLENGE_ID, pending.challengeId)
            .putString(ACCOUNT_DELETION_EXPIRES_AT, pending.expiresAt)
            .putString(ACCOUNT_DELETION_RESEND_AFTER, pending.resendAfter)
            .putString(ACCOUNT_DELETION_REAUTH_KEY, pending.reauthenticationIdempotencyKey)
            .putString(ACCOUNT_DELETION_GRANT, pending.grant)
            .putString(ACCOUNT_DELETION_MUTATION_KEY, pending.deletionIdempotencyKey)
            // Both grant and final mutation key must be durable before DELETE leaves the phone.
            .commit()
    }

    override fun clearPendingAccountDeletion() {
        prefs.edit()
            .remove(ACCOUNT_DELETION_BASE_URL)
            .remove(ACCOUNT_DELETION_ACCOUNT_ID)
            .remove(ACCOUNT_DELETION_EMAIL)
            .remove(ACCOUNT_DELETION_CHALLENGE_ID)
            .remove(ACCOUNT_DELETION_EXPIRES_AT)
            .remove(ACCOUNT_DELETION_RESEND_AFTER)
            .remove(ACCOUNT_DELETION_REAUTH_KEY)
            .remove(ACCOUNT_DELETION_GRANT)
            .remove(ACCOUNT_DELETION_MUTATION_KEY)
            .commit()
    }

    override fun accountDeletionCommitted(): Boolean =
        prefs.getBoolean(ACCOUNT_DELETION_COMMITTED, false)

    override fun setAccountDeletionCommitted(committed: Boolean) {
        prefs.edit().putBoolean(ACCOUNT_DELETION_COMMITTED, committed).commit()
    }

    override fun completeAccountDeletion() {
        prefs.edit()
            .remove("account_base_url")
            .remove("account_id")
            .remove("account_display_name")
            .remove("account_email")
            .remove("account_installation_id")
            .remove("account_installation_name")
            .remove("account_access_token")
            .remove("account_access_expires_at")
            .remove("account_refresh_token")
            .remove("account_refresh_expires_at")
            .remove("account_selected_device_id")
            .remove("account_selected_device_name")
            .remove("account_pending_refresh_key")
            .remove("account_email_base_url")
            .remove("account_email_mailbox")
            .remove("account_email_challenge_id")
            .remove("account_email_expires_at")
            .remove("account_email_resend_after")
            .remove("account_email_exchange_key")
            .remove(ACCOUNT_DELETION_BASE_URL)
            .remove(ACCOUNT_DELETION_ACCOUNT_ID)
            .remove(ACCOUNT_DELETION_EMAIL)
            .remove(ACCOUNT_DELETION_CHALLENGE_ID)
            .remove(ACCOUNT_DELETION_EXPIRES_AT)
            .remove(ACCOUNT_DELETION_RESEND_AFTER)
            .remove(ACCOUNT_DELETION_REAUTH_KEY)
            .remove(ACCOUNT_DELETION_GRANT)
            .remove(ACCOUNT_DELETION_MUTATION_KEY)
            .putBoolean(ACCOUNT_REAUTHENTICATION_REQUIRED, false)
            .putBoolean(ACCOUNT_EXPLICIT_LEGACY_CONNECTION, false)
            .putBoolean(ACCOUNT_DELETION_COMMITTED, true)
            .commit()
    }

    override fun bind(accountId: String, profile: String?, sessionId: String, deviceId: String) {
        if (accountId.isBlank() || sessionId.isBlank() || deviceId.isBlank()) return
        prefs.edit().putString(conversationDeviceKey(accountId, profile, sessionId), deviceId).apply()
    }

    override fun resolve(accountId: String, profile: String?, sessionId: String): String? =
        prefs.getString(conversationDeviceKey(accountId, profile, sessionId), null)

    override fun remove(accountId: String, profile: String?, sessionId: String) {
        prefs.edit().remove(conversationDeviceKey(accountId, profile, sessionId)).apply()
    }

    private fun conversationDeviceKey(accountId: String, profile: String?, sessionId: String): String {
        fun component(value: String): String = android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        return "account_conversation_device:${component(accountId)}:${component(profile.orEmpty())}:${component(sessionId)}"
    }

    private companion object {
        const val PREFS_NAME = "hermes_credentials"
        // Default Android-keystore alias used by MasterKeys.AES256_GCM_SPEC (the constant itself is
        // package-private). Deleting this entry forces a fresh master key if the keyset reset alone
        // didn't recover.
        const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        const val ACCOUNT_CLIENT_INSTALLATION_ID = "account_client_installation_id"
        const val ACCOUNT_LAST_BASE_URL = "account_last_base_url"
        const val ACCOUNT_REAUTHENTICATION_REQUIRED = "account_reauthentication_required"
        const val ACCOUNT_EXPLICIT_LEGACY_CONNECTION = "account_explicit_legacy_connection"
        const val ACCOUNT_ACTIVATION_PENDING = "account_activation_pending"
        const val ACCOUNT_DEVICE_ROUTE_MODE = "account_device_route_mode"
        const val ACCOUNT_DELETION_COMMITTED = "account_deletion_committed"
        const val ACCOUNT_DELETION_BASE_URL = "account_deletion_base_url"
        const val ACCOUNT_DELETION_ACCOUNT_ID = "account_deletion_account_id"
        const val ACCOUNT_DELETION_EMAIL = "account_deletion_email"
        const val ACCOUNT_DELETION_CHALLENGE_ID = "account_deletion_challenge_id"
        const val ACCOUNT_DELETION_EXPIRES_AT = "account_deletion_expires_at"
        const val ACCOUNT_DELETION_RESEND_AFTER = "account_deletion_resend_after"
        const val ACCOUNT_DELETION_REAUTH_KEY = "account_deletion_reauth_key"
        const val ACCOUNT_DELETION_GRANT = "account_deletion_grant"
        const val ACCOUNT_DELETION_MUTATION_KEY = "account_deletion_mutation_key"
    }
}

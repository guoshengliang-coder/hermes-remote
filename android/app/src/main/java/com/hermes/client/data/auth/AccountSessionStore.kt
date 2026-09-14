package com.hermes.client.data.auth

/**
 * Hermes GO account credentials are deliberately separate from the legacy App Token. Keeping both
 * records lets an existing installation test account mode and roll back without losing its known-
 * good connection.
 */
data class AccountSession(
    val baseUrl: String,
    val accountId: String,
    val accountDisplayName: String? = null,
    val accountEmail: String? = null,
    val installationId: String,
    val installationDisplayName: String,
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
    val selectedDeviceId: String? = null,
    val selectedDeviceName: String? = null,
    /**
     * True only while an existing Legacy connection remains authoritative during opt-in account
     * migration. A successful account-route probe clears it atomically with device selection.
     */
    val activationPending: Boolean = false,
    /** How Hermes traffic is addressed after account activation. */
    val deviceRouteMode: AccountDeviceRouteMode = AccountDeviceRouteMode.EXPLICIT_DEVICE,
    /** Reused after a lost refresh response; cleared only when rotation commits. */
    val pendingRefreshIdempotencyKey: String? = null,
)

enum class AccountDeviceRouteMode {
    /** One unambiguous binding; Bearer traffic uses the compatibility `/api/…` and `/api/ws`. */
    SINGLE_BINDING,
    /** Multiple accessible devices; every request carries an explicit opaque device ID. */
    EXPLICIT_DEVICE,
}

data class PendingEmailChallenge(
    val baseUrl: String,
    val email: String,
    val challengeId: String,
    val expiresAt: String,
    val resendAfter: String,
    /** Stable across process death so a lost exchange response can be replayed safely. */
    val exchangeIdempotencyKey: String,
)

/**
 * Durable account-deletion recovery record. The user's typed confirmation and OTP are deliberately
 * absent; only the server challenge and the minimum replay material live in encrypted storage.
 */
data class PendingAccountDeletion(
    val baseUrl: String,
    val accountId: String,
    val email: String,
    val challengeId: String,
    val expiresAt: String,
    val resendAfter: String,
    val reauthenticationIdempotencyKey: String,
    val grant: String? = null,
    val deletionIdempotencyKey: String? = null,
) {
    val isReadyToCommit: Boolean
        get() = !grant.isNullOrBlank() && !deletionIdempotencyKey.isNullOrBlank()
}

interface AccountSessionStore {
    /** Stable, random ID for this app installation. It is not derived from an email or device name. */
    fun clientInstallationId(): String
    fun loadAccountSession(): AccountSession?
    fun saveAccountSession(session: AccountSession)
    /** Last account-service origin, retained without credentials so reauthentication returns there. */
    fun lastAccountBaseUrl(): String? = loadAccountSession()?.baseUrl
    /** Removes only Hermes GO account credentials; legacy Relay/App Token values stay intact. */
    fun clearAccountSession()
    /** Atomically records whether an unexpected invalidation must block silent legacy fallback. */
    fun clearAccountSession(requireReauthentication: Boolean): Unit =
        clearAccountSession(requireReauthentication, reason = null)

    /**
     * [reason] is the `HR-*` code that ended the session, recorded only when the SERVER ended it.
     * An explicit sign-out leaves it null, and that absence is the whole signal the sign-in page
     * uses to decide whether to explain itself: nobody wants to be told why they were logged out
     * when they are the one who tapped "sign out".
     */
    fun clearAccountSession(requireReauthentication: Boolean, reason: String?) {
        clearAccountSession()
        setAccountReauthenticationRequired(requireReauthentication, reason)
    }
    fun accountReauthenticationRequired(): Boolean = false

    /** The `HR-*` code recorded by [clearAccountSession]; null after an explicit sign-out. */
    fun accountReauthenticationReason(): String? = null
    fun setAccountReauthenticationRequired(required: Boolean): Unit =
        setAccountReauthenticationRequired(required, reason = null)
    fun setAccountReauthenticationRequired(required: Boolean, reason: String?) = Unit
    /** True only after the user explicitly enters the compatibility connection flow. */
    fun explicitLegacyConnectionSelected(): Boolean = false
    fun setExplicitLegacyConnectionSelected(selected: Boolean) = Unit
    fun loadPendingEmailChallenge(): PendingEmailChallenge?
    fun savePendingEmailChallenge(challenge: PendingEmailChallenge)
    fun clearPendingEmailChallenge()
    fun loadPendingAccountDeletion(): PendingAccountDeletion? = null
    fun savePendingAccountDeletion(pending: PendingAccountDeletion) = Unit
    fun clearPendingAccountDeletion() = Unit
    /** Non-secret terminal gate set only after permanent deletion is known to have committed. */
    fun accountDeletionCommitted(): Boolean = false
    fun setAccountDeletionCommitted(committed: Boolean) = Unit
    /** Atomically clears account secrets and records the terminal deletion gate where supported. */
    fun completeAccountDeletion() {
        clearAccountSession(requireReauthentication = false)
        clearPendingEmailChallenge()
        clearPendingAccountDeletion()
        setAccountDeletionCommitted(true)
    }
}

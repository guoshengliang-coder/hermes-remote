import Foundation

public actor DesktopAccountController {
    private let api: any AccountAPIRequesting
    private let sessionStore: any AccountSessionStoring
    private let machineIdentityStore: any ConnectorMachineIdentityStoring
    private let oauth: (any GoogleOAuthPerforming)?
    private let displayName: String
    private let appVersion: String
    private var capabilitiesSnapshot: AccountCapabilities?
    private var sessionRecord: AccountSessionRecord?

    public init(
        api: any AccountAPIRequesting,
        sessionStore: any AccountSessionStoring,
        machineIdentityStore: any ConnectorMachineIdentityStoring,
        oauth: (any GoogleOAuthPerforming)?,
        displayName: String,
        appVersion: String
    ) {
        self.api = api
        self.sessionStore = sessionStore
        self.machineIdentityStore = machineIdentityStore
        self.oauth = oauth
        self.displayName = displayName
        self.appVersion = appVersion
    }

    public func bootstrap() async throws -> DesktopAccountState {
        try await refresh()
    }

    public func signIn() async throws -> DesktopAccountState {
        let capabilities: AccountCapabilities
        if let capabilitiesSnapshot {
            capabilities = capabilitiesSnapshot
        } else {
            capabilities = try await api.capabilities()
        }
        capabilitiesSnapshot = capabilities
        guard capabilities.accountAuth.enabled, capabilities.accountAuth.macos else {
            return .unavailable
        }
        guard let oauth else { throw GoogleOAuthError.configurationMissing }
        let machineIdentity = try machineIdentityStore.loadOrCreate()
        let proof = try await oauth.signIn()
        let record = try await api.exchangeGoogleProof(
            proof,
            clientInstallationID: machineIdentity.clientInstallationID,
            displayName: displayName,
            appVersion: appVersion
        )
        guard record.installation.kind == "desktop",
              record.installation.platform == "macos"
        else { throw AccountClientError.invalidResponse }
        try sessionStore.save(record)
        sessionRecord = record
        return try await loadDashboard(record: record)
    }

    public func refresh() async throws -> DesktopAccountState {
        let capabilities = try await api.capabilities()
        capabilitiesSnapshot = capabilities
        guard capabilities.accountAuth.enabled, capabilities.accountAuth.macos else {
            return .unavailable
        }
        let storedRecord: AccountSessionRecord?
        if let sessionRecord {
            storedRecord = sessionRecord
        } else {
            do {
                storedRecord = try sessionStore.load()
            } catch {
                try? sessionStore.delete()
                self.sessionRecord = nil
                throw error
            }
        }
        guard let record = storedRecord else {
            return .signedOut
        }
        sessionRecord = record
        return try await loadDashboard(record: record)
    }

    public func revokePhone(id: String) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        let refreshed = try await refreshIfNeeded(record)
        let operation = "installation.revoke:\(id.lowercased())"
        var keys = refreshed.pendingOperationIdempotencyKeys ?? [:]
        let idempotencyKey = try validOrNewIdempotencyKey(keys[operation])
        keys[operation] = idempotencyKey
        let pending = replacing(refreshed, pendingOperationIdempotencyKeys: keys)
        try sessionStore.save(pending)
        sessionRecord = pending
        try await api.revokePhone(
            id: id,
            accessToken: pending.session.accessToken,
            idempotencyKey: idempotencyKey
        )
        keys.removeValue(forKey: operation)
        let completed = replacing(
            pending,
            pendingOperationIdempotencyKeys: keys.isEmpty ? nil : keys
        )
        try sessionStore.save(completed)
        sessionRecord = completed
        return try await loadDashboard(record: completed)
    }

    public func signOut() async throws -> DesktopAccountState {
        guard let record = sessionRecord else {
            try sessionStore.delete()
            return .signedOut
        }
        let operation = "auth.sign-out"
        do {
            let refreshed = try await refreshIfNeeded(record)
            var keys = refreshed.pendingOperationIdempotencyKeys ?? [:]
            let idempotencyKey = try validOrNewIdempotencyKey(keys[operation])
            keys[operation] = idempotencyKey
            let pending = replacing(refreshed, pendingOperationIdempotencyKeys: keys)
            try sessionStore.save(pending)
            sessionRecord = pending
            try await api.signOut(
                accessToken: pending.session.accessToken,
                idempotencyKey: idempotencyKey
            )
        } catch AccountClientError.remote(let remote)
            where remote.code == "HR-AUTH-003"
                || remote.code == "HR-AUTH-004"
                || remote.code == "HR-AUTH-005" {
            // The server already considers this management session unusable.
        }
        try sessionStore.delete()
        sessionRecord = nil
        return .signedOut
    }

    public func machineIdentity() throws -> ConnectorMachineIdentity {
        try machineIdentityStore.loadOrCreate()
    }

    private func loadDashboard(record: AccountSessionRecord) async throws -> DesktopAccountState {
        do {
            let refreshed = try await refreshIfNeeded(record)
            async let account = api.account(accessToken: refreshed.session.accessToken)
            async let installations = api.installations(accessToken: refreshed.session.accessToken)
            async let binding = api.binding(accessToken: refreshed.session.accessToken)
            let (accountSnapshot, installationSnapshot, bindingSnapshot) = try await (
                account,
                installations,
                binding
            )
            guard accountSnapshot.account.id == refreshed.account.id,
                  accountSnapshot.installation.id == refreshed.installation.id,
                  accountSnapshot.installation.kind == "desktop"
            else { throw AccountClientError.invalidResponse }
            let current = AccountSessionRecord(
                account: accountSnapshot.account,
                installation: accountSnapshot.installation,
                session: refreshed.session,
                pendingRefreshIdempotencyKey: refreshed.pendingRefreshIdempotencyKey,
                pendingOperationIdempotencyKeys: refreshed.pendingOperationIdempotencyKeys
            )
            if current != refreshed { try sessionStore.save(current) }
            sessionRecord = current
            return .signedIn(AccountDashboard(
                session: current,
                binding: bindingSnapshot,
                installations: installationSnapshot
            ))
        } catch AccountClientError.remote(let remote)
            where remote.code == "HR-AUTH-003"
                || remote.code == "HR-AUTH-004"
                || remote.code == "HR-AUTH-005" {
            try? sessionStore.delete()
            sessionRecord = nil
            let issueCode: DesktopIssueCode = switch remote.code {
            case "HR-AUTH-004": .accountSessionRevoked
            case "HR-AUTH-005": .refreshCredentialReused
            default: .accountSessionExpired
            }
            return .needsSignIn(issueCode)
        }
    }

    private func refreshIfNeeded(_ record: AccountSessionRecord) async throws -> AccountSessionRecord {
        guard record.session.shouldRefresh else { return record }
        let clientInstallationID = try machineIdentityStore.loadOrCreate().clientInstallationID
        let idempotencyKey = try validOrNewIdempotencyKey(record.pendingRefreshIdempotencyKey)
        let pending = AccountSessionRecord(
            account: record.account,
            installation: record.installation,
            session: record.session,
            pendingRefreshIdempotencyKey: idempotencyKey,
            pendingOperationIdempotencyKeys: record.pendingOperationIdempotencyKeys
        )
        if pending != record {
            try sessionStore.save(pending)
            sessionRecord = pending
        }
        let tokens = try await api.refresh(
            refreshToken: pending.session.refreshToken,
            clientInstallationID: clientInstallationID,
            idempotencyKey: idempotencyKey
        )
        let refreshed = AccountSessionRecord(
            account: pending.account,
            installation: pending.installation,
            session: tokens,
            pendingOperationIdempotencyKeys: pending.pendingOperationIdempotencyKeys
        )
        try sessionStore.save(refreshed)
        sessionRecord = refreshed
        return refreshed
    }

    private func replacing(
        _ record: AccountSessionRecord,
        pendingOperationIdempotencyKeys: [String: String]?
    ) -> AccountSessionRecord {
        AccountSessionRecord(
            account: record.account,
            installation: record.installation,
            session: record.session,
            pendingRefreshIdempotencyKey: record.pendingRefreshIdempotencyKey,
            pendingOperationIdempotencyKeys: pendingOperationIdempotencyKeys
        )
    }

    private func validOrNewIdempotencyKey(_ stored: String?) throws -> String {
        guard let stored else { return UUID().uuidString.lowercased() }
        guard UUID(uuidString: stored) != nil else { throw AccountSecretStoreError.decoding }
        return stored.lowercased()
    }
}

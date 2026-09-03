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

    public func createFirstBinding() async throws -> AccountBindingCandidate {
        try await requireBindingCapability(replacement: false)
        let record = try await authenticatedRecord()
        let machineIdentity = try machineIdentityStore.loadOrCreate()
        let operation = "connector.binding.create"
        let pending = try prepareOperations([operation], on: record)
        let candidate = try await api.createBinding(
            bindingInput(record: pending, machineIdentity: machineIdentity),
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(operation, in: pending)
        )
        try completeOperations([operation], on: pending)
        return candidate
    }

    public func confirmFirstBinding(
        id: String,
        generation: Int
    ) async throws -> ActiveAccountBinding {
        try await requireBindingCapability(replacement: false)
        let record = try await authenticatedRecord()
        let operation = "connector.binding.confirm:\(id.lowercased()):\(generation)"
        let pending = try prepareOperations([operation], on: record)
        let binding = try await api.confirmBinding(
            id: id,
            generation: generation,
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(operation, in: pending)
        )
        try completeOperations([operation], on: pending)
        return binding
    }

    public func createReplacement() async throws -> AccountReplacementRequest {
        try await requireBindingCapability(replacement: true)
        guard let oauth else { throw GoogleOAuthError.configurationMissing }
        let record = try await authenticatedRecord()
        let machineIdentity = try machineIdentityStore.loadOrCreate()
        let proof = try await oauth.signIn()
        let reauthentication = "auth.reauthenticate:connector.replace"
        let replacement = "connector.binding.replace"
        let pending = try prepareOperations([reauthentication, replacement], on: record)
        let grant = try await api.reauthenticateGoogle(
            proof,
            scope: .connectorReplace,
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(reauthentication, in: pending)
        )
        guard grant.scope == .connectorReplace else {
            throw AccountClientError.invalidResponse
        }
        let request = try await api.createReplacement(
            bindingInput(record: pending, machineIdentity: machineIdentity),
            grant: grant.grant,
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(replacement, in: pending)
        )
        try completeOperations([reauthentication, replacement], on: pending)
        return request
    }

    public func confirmReplacement(requestID: String) async throws -> ActiveAccountBinding {
        try await requireBindingCapability(replacement: true)
        let record = try await authenticatedRecord()
        let operation = "connector.binding.replace.confirm:\(requestID.lowercased())"
        let pending = try prepareOperations([operation], on: record)
        let binding = try await api.confirmReplacement(
            requestID: requestID,
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(operation, in: pending)
        )
        try completeOperations([operation], on: pending)
        return binding
    }

    public func unbind() async throws {
        try await requireBindingCapability(replacement: false)
        guard let oauth else { throw GoogleOAuthError.configurationMissing }
        let record = try await authenticatedRecord()
        let proof = try await oauth.signIn()
        let reauthentication = "auth.reauthenticate:connector.unbind"
        let unbind = "connector.binding.unbind"
        let pending = try prepareOperations([reauthentication, unbind], on: record)
        let grant = try await api.reauthenticateGoogle(
            proof,
            scope: .connectorUnbind,
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(reauthentication, in: pending)
        )
        guard grant.scope == .connectorUnbind else {
            throw AccountClientError.invalidResponse
        }
        try await api.unbind(
            grant: grant.grant,
            accessToken: pending.session.accessToken,
            idempotencyKey: try operationKey(unbind, in: pending)
        )
        try completeOperations([reauthentication, unbind], on: pending)
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

    private func requireBindingCapability(replacement: Bool) async throws {
        let capabilities: AccountCapabilities
        if let capabilitiesSnapshot {
            capabilities = capabilitiesSnapshot
        } else {
            capabilities = try await api.capabilities()
            capabilitiesSnapshot = capabilities
        }
        guard capabilities.accountAuth.enabled,
              capabilities.accountAuth.macos,
              capabilities.binding.enabled,
              !replacement || capabilities.binding.replacement
        else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-008",
                message: "Desktop binding is not enabled.",
                retryable: false,
                recoveryAction: "continue_legacy",
                correlationId: nil
            ))
        }
    }

    private func authenticatedRecord() async throws -> AccountSessionRecord {
        let stored: AccountSessionRecord?
        if let sessionRecord {
            stored = sessionRecord
        } else {
            stored = try sessionStore.load()
        }
        guard let stored else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-AUTH-003",
                message: "The account session has expired.",
                retryable: false,
                recoveryAction: "sign_in",
                correlationId: nil
            ))
        }
        sessionRecord = stored
        return try await refreshIfNeeded(stored)
    }

    private func bindingInput(
        record: AccountSessionRecord,
        machineIdentity: ConnectorMachineIdentity
    ) -> ConnectorBindingInput {
        ConnectorBindingInput(
            desktopInstallationID: record.installation.id,
            displayName: displayName,
            connectorPublicKey: machineIdentity.connectorPublicKey
        )
    }

    private func prepareOperations(
        _ operations: [String],
        on record: AccountSessionRecord
    ) throws -> AccountSessionRecord {
        var keys = record.pendingOperationIdempotencyKeys ?? [:]
        for operation in operations {
            keys[operation] = try validOrNewIdempotencyKey(keys[operation])
        }
        let pending = replacing(record, pendingOperationIdempotencyKeys: keys)
        if pending != record {
            try sessionStore.save(pending)
            sessionRecord = pending
        }
        return pending
    }

    private func operationKey(
        _ operation: String,
        in record: AccountSessionRecord
    ) throws -> String {
        guard let key = record.pendingOperationIdempotencyKeys?[operation] else {
            throw AccountSecretStoreError.decoding
        }
        return try validOrNewIdempotencyKey(key)
    }

    private func completeOperations(
        _ operations: [String],
        on record: AccountSessionRecord
    ) throws {
        var keys = record.pendingOperationIdempotencyKeys ?? [:]
        operations.forEach { keys.removeValue(forKey: $0) }
        let completed = replacing(
            record,
            pendingOperationIdempotencyKeys: keys.isEmpty ? nil : keys
        )
        try sessionStore.save(completed)
        sessionRecord = completed
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

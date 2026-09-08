import CryptoKit
import Foundation

public actor DesktopAccountController {
    private let api: any AccountAPIRequesting
    private let sessionStore: any AccountSessionStoring
    private let machineIdentityStore: any ConnectorMachineIdentityStoring
    private let deviceSelectionStore: any DeviceSelectionStoring
    private let oauth: (any GoogleOAuthPerforming)?
    private let displayName: String
    private let appVersion: String
    private var capabilitiesSnapshot: AccountCapabilities?
    private var sessionRecord: AccountSessionRecord?
    private var dashboardSnapshot: AccountDashboard?

    public init(
        api: any AccountAPIRequesting,
        sessionStore: any AccountSessionStoring,
        machineIdentityStore: any ConnectorMachineIdentityStoring,
        deviceSelectionStore: any DeviceSelectionStoring = UserDefaultsDeviceSelectionStore(),
        oauth: (any GoogleOAuthPerforming)?,
        displayName: String,
        appVersion: String
    ) {
        self.api = api
        self.sessionStore = sessionStore
        self.machineIdentityStore = machineIdentityStore
        self.deviceSelectionStore = deviceSelectionStore
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

    public func requestEmailSignInCode(
        email: String
    ) async throws -> DesktopEmailVerificationChallenge {
        let capabilities: AccountCapabilities
        if let capabilitiesSnapshot {
            capabilities = capabilitiesSnapshot
        } else {
            capabilities = try await api.capabilities()
        }
        capabilitiesSnapshot = capabilities
        try requireEmailAuthentication(capabilities)
        let normalizedEmail = try normalizedEmail(email)
        let machineIdentity = try machineIdentityStore.loadOrCreate()
        let challenge = try await api.requestEmailSignInChallenge(
            email: normalizedEmail,
            clientInstallationID: machineIdentity.clientInstallationID
        )
        return DesktopEmailVerificationChallenge(
            challenge: challenge,
            email: normalizedEmail,
            idempotencyKey: UUID().uuidString.lowercased()
        )
    }

    public func completeEmailSignIn(
        challenge: DesktopEmailVerificationChallenge,
        code: String
    ) async throws -> DesktopAccountState {
        let capabilities: AccountCapabilities
        if let capabilitiesSnapshot {
            capabilities = capabilitiesSnapshot
        } else {
            capabilities = try await api.capabilities()
        }
        capabilitiesSnapshot = capabilities
        try requireEmailAuthentication(capabilities)
        let machineIdentity = try machineIdentityStore.loadOrCreate()
        let record = try await api.exchangeEmailChallenge(
            challengeID: challenge.challenge.challengeId,
            email: challenge.email,
            code: code.trimmingCharacters(in: .whitespacesAndNewlines),
            clientInstallationID: machineIdentity.clientInstallationID,
            displayName: displayName,
            appVersion: appVersion,
            idempotencyKey: challenge.idempotencyKey
        )
        guard record.installation.kind == "desktop",
              record.installation.platform == "macos",
              record.account.email?.lowercased() == challenge.email
        else { throw AccountClientError.invalidResponse }
        try sessionStore.save(record)
        sessionRecord = record
        return try await loadDashboard(record: record)
    }

    public func refresh() async throws -> DesktopAccountState {
        let capabilities = try await api.capabilities()
        capabilitiesSnapshot = capabilities
        guard capabilities.accountAuth.enabled,
              capabilities.accountAuth.macos,
              capabilities.accountAuth.providers.contains("email_otp")
        else {
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
        if let recovered = try await recoverPendingAccountDeletion(record) {
            return recovered
        }
        return try await loadDashboard(record: record)
    }

    public func requestPhoneRevocationVerification(
        id: String
    ) async throws -> DesktopEmailVerificationChallenge {
        guard let record = sessionRecord else {
            throw accountOperationError(
                code: "HR-AUTH-003",
                message: "Session expired.",
                recoveryAction: "sign_in"
            )
        }
        try requirePhoneRevocation(id: id)
        let current = try await refreshIfNeeded(record)
        guard let accountEmail = current.account.email else {
            throw accountOperationError(
                code: "HR-AUTH-006",
                message: "Email verification is required.",
                recoveryAction: "reauthenticate"
            )
        }
        let email = try normalizedEmail(accountEmail)
        let challenge = try await api.requestEmailReauthenticationChallenge(
            email: email,
            accessToken: current.session.accessToken
        )
        sessionRecord = current
        return DesktopEmailVerificationChallenge(
            challenge: challenge,
            email: email,
            idempotencyKey: UUID().uuidString.lowercased()
        )
    }

    public func revokePhone(
        id: String,
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String
    ) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        try requirePhoneRevocation(id: id)
        let operation = "account.installation.revoke:\(id.lowercased())"
        var current = try await refreshIfNeeded(record)
        var operationKeys = current.pendingOperationIdempotencyKeys ?? [:]
        var grants = current.pendingReauthenticationGrants ?? [:]

        let grant: String
        if let pendingGrant = grants[operation] {
            grant = pendingGrant
        } else {
            guard verification.email == current.account.email?.lowercased() else {
                throw accountOperationError(
                    code: "HR-AUTH-006",
                    message: "Email verification is required.",
                    recoveryAction: "reauthenticate"
                )
            }
            let reauthenticationPrefix = "\(operation):reauth:"
            operationKeys = operationKeys.filter {
                !$0.key.hasPrefix(reauthenticationPrefix)
                    || $0.key == "\(reauthenticationPrefix)\(verification.challenge.challengeId)"
            }
            let reauthenticationOperation =
                "\(reauthenticationPrefix)\(verification.challenge.challengeId)"
            let reauthenticationKey = try validOrProvidedIdempotencyKey(
                operationKeys[reauthenticationOperation],
                provided: verification.idempotencyKey
            )
            operationKeys[reauthenticationOperation] = reauthenticationKey
            current = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys,
                pendingReauthenticationGrants: grants.isEmpty ? nil : grants
            )
            try sessionStore.save(current)
            sessionRecord = current
            let response = try await api.reauthenticateEmail(
                challengeID: verification.challenge.challengeId,
                email: verification.email,
                code: verificationCode.trimmingCharacters(in: .whitespacesAndNewlines),
                scope: "account.installation.revoke",
                accessToken: current.session.accessToken,
                idempotencyKey: reauthenticationKey
            )
            guard response.scope == "account.installation.revoke" else {
                throw AccountClientError.invalidResponse
            }
            grant = response.grant
            grants[operation] = grant
            operationKeys.removeValue(forKey: reauthenticationOperation)
            current = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys,
                pendingReauthenticationGrants: grants
            )
            try sessionStore.save(current)
            sessionRecord = current
        }

        let mutationOperation = "\(operation):commit"
        let mutationKey = try validOrNewIdempotencyKey(operationKeys[mutationOperation])
        operationKeys[mutationOperation] = mutationKey
        current = replacing(
            current,
            pendingOperationIdempotencyKeys: operationKeys,
            pendingReauthenticationGrants: grants
        )
        try sessionStore.save(current)
        sessionRecord = current
        do {
            try await api.revokePhone(
                id: id,
                grant: grant,
                accessToken: current.session.accessToken,
                idempotencyKey: mutationKey
            )
        } catch AccountClientError.remote(let remote) where remote.code == "HR-AUTH-006" {
            grants.removeValue(forKey: operation)
            operationKeys.removeValue(forKey: mutationOperation)
            let cleared = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys.isEmpty ? nil : operationKeys,
                pendingReauthenticationGrants: grants.isEmpty ? nil : grants
            )
            try sessionStore.save(cleared)
            sessionRecord = cleared
            throw AccountClientError.remote(remote)
        }
        grants.removeValue(forKey: operation)
        operationKeys.removeValue(forKey: mutationOperation)
        let completed = replacing(
            current,
            pendingOperationIdempotencyKeys: operationKeys.isEmpty ? nil : operationKeys,
            pendingReauthenticationGrants: grants.isEmpty ? nil : grants
        )
        try sessionStore.save(completed)
        sessionRecord = completed
        return try await loadDashboard(record: completed)
    }

    public func selectDevice(id: String) throws -> DesktopAccountState {
        guard let dashboard = dashboardSnapshot else {
            return sessionRecord == nil ? .signedOut : .checking
        }
        guard dashboard.devices.contains(where: { $0.deviceId == id }) else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-011",
                message: "Device is unavailable.",
                retryable: false,
                recoveryAction: "select_device",
                correlationId: nil
            ))
        }
        deviceSelectionStore.save(id, accountID: dashboard.session.account.id)
        let updated = replacing(dashboard, selectedDeviceID: id)
        dashboardSnapshot = updated
        return .signedIn(updated)
    }

    public func selectDefaultDevice(id: String) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        guard capabilitiesSnapshot?.binding.supportsDeviceSelection == true else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-008",
                message: "Device selection is disabled.",
                retryable: false,
                recoveryAction: "continue_legacy",
                correlationId: nil
            ))
        }
        guard dashboardSnapshot?.devices.contains(where: { $0.deviceId == id }) == true else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-011",
                message: "Device is unavailable.",
                retryable: false,
                recoveryAction: "select_device",
                correlationId: nil
            ))
        }

        let refreshed = try await refreshIfNeeded(record)
        let operation = "device.select-default:\(id)"
        var keys = refreshed.pendingOperationIdempotencyKeys ?? [:]
        let idempotencyKey = try validOrNewIdempotencyKey(keys[operation])
        keys[operation] = idempotencyKey
        let pending = replacing(refreshed, pendingOperationIdempotencyKeys: keys)
        try sessionStore.save(pending)
        sessionRecord = pending
        _ = try await api.selectDefaultDevice(
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
        deviceSelectionStore.save(id, accountID: completed.account.id)
        return try await loadDashboard(record: completed)
    }

    public func createShareInvitation(
        deviceID: String,
        email: String,
        acknowledgedWholeDeviceAccess: Bool,
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String
    ) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        try requireSharing(ownerDeviceID: deviceID)
        guard acknowledgedWholeDeviceAccess else {
            throw sharingError(
                code: "HR-SHARE-006",
                message: "Whole-device access acknowledgement is required."
            )
        }
        let normalizedEmail = try normalizedEmail(email)
        let discriminator = SHA256.hash(data: Data(normalizedEmail.utf8))
            .prefix(8)
            .map { String(format: "%02x", $0) }
            .joined()
        let operation = "device.share.invite:\(deviceID):\(discriminator)"
        var current = try await refreshIfNeeded(record)
        var operationKeys = current.pendingOperationIdempotencyKeys ?? [:]
        var grants = current.pendingReauthenticationGrants ?? [:]

        let grant: String
        if let pendingGrant = grants[operation] {
            grant = pendingGrant
        } else {
            guard verification.email == current.account.email?.lowercased() else {
                throw sharingError(code: "HR-AUTH-006", message: "Email verification is required.")
            }
            let reauthenticationPrefix = "\(operation):reauth:"
            operationKeys = operationKeys.filter {
                !$0.key.hasPrefix(reauthenticationPrefix)
                    || $0.key == "\(reauthenticationPrefix)\(verification.challenge.challengeId)"
            }
            let reauthenticationOperation = "\(reauthenticationPrefix)\(verification.challenge.challengeId)"
            let reauthenticationKey = try validOrProvidedIdempotencyKey(
                operationKeys[reauthenticationOperation],
                provided: verification.idempotencyKey
            )
            operationKeys[reauthenticationOperation] = reauthenticationKey
            current = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys,
                pendingReauthenticationGrants: grants.isEmpty ? nil : grants
            )
            try sessionStore.save(current)
            sessionRecord = current
            let response = try await api.reauthenticateEmail(
                challengeID: verification.challenge.challengeId,
                email: verification.email,
                code: verificationCode.trimmingCharacters(in: .whitespacesAndNewlines),
                scope: "device.share",
                accessToken: current.session.accessToken,
                idempotencyKey: reauthenticationKey
            )
            guard response.scope == "device.share" else { throw AccountClientError.invalidResponse }
            grant = response.grant
            grants[operation] = grant
            operationKeys.removeValue(forKey: reauthenticationOperation)
            current = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys,
                pendingReauthenticationGrants: grants
            )
            try sessionStore.save(current)
            sessionRecord = current
        }

        let invitationOperation = "\(operation):create"
        let invitationKey = try validOrNewIdempotencyKey(operationKeys[invitationOperation])
        operationKeys[invitationOperation] = invitationKey
        current = replacing(
            current,
            pendingOperationIdempotencyKeys: operationKeys,
            pendingReauthenticationGrants: grants
        )
        try sessionStore.save(current)
        sessionRecord = current
        do {
            _ = try await api.createShareInvitation(
                deviceID: deviceID,
                email: normalizedEmail,
                grant: grant,
                acknowledgedWholeDeviceAccess: true,
                accessToken: current.session.accessToken,
                idempotencyKey: invitationKey
            )
        } catch AccountClientError.remote(let remote) where remote.code == "HR-AUTH-006" {
            grants.removeValue(forKey: operation)
            operationKeys.removeValue(forKey: invitationOperation)
            let cleared = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys.isEmpty ? nil : operationKeys,
                pendingReauthenticationGrants: grants.isEmpty ? nil : grants
            )
            try sessionStore.save(cleared)
            sessionRecord = cleared
            throw AccountClientError.remote(remote)
        }
        grants.removeValue(forKey: operation)
        operationKeys.removeValue(forKey: invitationOperation)
        let completed = replacing(
            current,
            pendingOperationIdempotencyKeys: operationKeys.isEmpty ? nil : operationKeys,
            pendingReauthenticationGrants: grants.isEmpty ? nil : grants
        )
        try sessionStore.save(completed)
        sessionRecord = completed
        return try await loadDashboard(record: completed)
    }

    public func requestShareInvitationVerification(
        deviceID: String
    ) async throws -> DesktopEmailVerificationChallenge {
        guard let record = sessionRecord else {
            throw sharingError(code: "HR-AUTH-003", message: "Session expired.")
        }
        try requireSharing(ownerDeviceID: deviceID)
        let current = try await refreshIfNeeded(record)
        guard let accountEmail = current.account.email else {
            throw sharingError(code: "HR-AUTH-006", message: "Email verification is required.")
        }
        let email = try normalizedEmail(accountEmail)
        let challenge = try await api.requestEmailReauthenticationChallenge(
            email: email,
            accessToken: current.session.accessToken
        )
        sessionRecord = current
        return DesktopEmailVerificationChallenge(
            challenge: challenge,
            email: email,
            idempotencyKey: UUID().uuidString.lowercased()
        )
    }

    public func cancelShareInvitation(
        deviceID: String,
        invitationID: String
    ) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        try requireSharing(ownerDeviceID: deviceID)
        let completed = try await performMutation(
            record: record,
            operation: "device.share.invitation.cancel:\(invitationID)"
        ) { accessToken, key in
            try await self.api.cancelShareInvitation(
                deviceID: deviceID,
                invitationID: invitationID,
                accessToken: accessToken,
                idempotencyKey: key
            )
        }
        return try await loadDashboard(record: completed)
    }

    public func revokeDeviceShare(deviceID: String, grantID: String) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        try requireSharing(ownerDeviceID: deviceID)
        let completed = try await performMutation(
            record: record,
            operation: "device.share.grant.revoke:\(grantID)"
        ) { accessToken, key in
            try await self.api.revokeDeviceShare(
                deviceID: deviceID,
                grantID: grantID,
                accessToken: accessToken,
                idempotencyKey: key
            )
        }
        return try await loadDashboard(record: completed)
    }

    public func leaveSharedDevice(deviceID: String) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        guard capabilitiesSnapshot?.binding.supportsDeviceSharing == true,
              dashboardSnapshot?.devices.contains(where: {
                  $0.deviceId == deviceID && $0.access == "operator"
              }) == true
        else { throw sharingError(code: "HR-BIND-011", message: "Device is unavailable.") }
        let completed = try await performMutation(
            record: record,
            operation: "device.share.grant.leave:\(deviceID)"
        ) { accessToken, key in
            try await self.api.leaveSharedDevice(
                deviceID: deviceID,
                accessToken: accessToken,
                idempotencyKey: key
            )
        }
        return try await loadDashboard(record: completed)
    }

    public func acceptShareInvitation(
        token: String,
        acknowledgedWholeDeviceAccess: Bool
    ) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        guard capabilitiesSnapshot?.binding.supportsDeviceSharing == true else {
            throw sharingError(code: "HR-SHARE-001", message: "Device sharing is disabled.")
        }
        guard acknowledgedWholeDeviceAccess else {
            throw sharingError(
                code: "HR-SHARE-006",
                message: "Whole-device access acknowledgement is required."
            )
        }
        let normalizedToken = Self.shareInvitationToken(from: token)
        guard let normalizedToken else {
            throw sharingError(code: "HR-SHARE-004", message: "Invitation is invalid or expired.")
        }
        let discriminator = SHA256.hash(data: Data(normalizedToken.utf8))
            .prefix(8)
            .map { String(format: "%02x", $0) }
            .joined()
        let completed = try await performMutation(
            record: record,
            operation: "device.share.invitation.accept:\(discriminator)"
        ) { accessToken, key in
            _ = try await self.api.acceptShareInvitation(
                token: normalizedToken,
                acknowledgedWholeDeviceAccess: true,
                accessToken: accessToken,
                idempotencyKey: key
            )
        }
        return try await loadDashboard(record: completed)
    }

    public static func shareInvitationToken(from input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.range(of: "^hsi_[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil {
            return trimmed
        }
        guard let components = URLComponents(string: trimmed),
              let fragment = components.fragment,
              fragment.hasPrefix("share-invitation=")
        else { return nil }
        let token = String(fragment.dropFirst("share-invitation=".count))
        return token.range(of: "^hsi_[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil
            ? token
            : nil
    }

    public func beginBinding() async throws -> DesktopBindingPreparation {
        guard let record = sessionRecord else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-AUTH-003",
                message: "Session expired.",
                retryable: false,
                recoveryAction: "sign_in",
                correlationId: nil
            ))
        }
        guard capabilitiesSnapshot?.binding.enabled == true else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-008",
                message: "Binding is disabled.",
                retryable: false,
                recoveryAction: "continue_legacy",
                correlationId: nil
            ))
        }
        if dashboardSnapshot?.binding.state == "binding_pending" {
            return try bindingPreparation(from: dashboardSnapshot!)
        }
        guard dashboardSnapshot?.binding.state == "no_binding" else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-002",
                message: "This Desktop already has a binding.",
                retryable: false,
                recoveryAction: "verify_and_replace",
                correlationId: nil
            ))
        }

        let refreshed = try await refreshIfNeeded(record)
        let operation = "binding.create"
        var keys = refreshed.pendingOperationIdempotencyKeys ?? [:]
        let idempotencyKey = try validOrNewIdempotencyKey(keys[operation])
        keys[operation] = idempotencyKey
        let pending = replacing(refreshed, pendingOperationIdempotencyKeys: keys)
        try sessionStore.save(pending)
        sessionRecord = pending
        let machine = try machineIdentityStore.loadOrCreate()
        let candidate = try await api.createBinding(
            desktopInstallationID: pending.installation.id,
            displayName: displayName,
            connectorPublicKey: machine.connectorPublicKey,
            accessToken: pending.session.accessToken,
            idempotencyKey: idempotencyKey
        )
        guard candidate.state == "binding_pending",
              candidate.publicKeyFingerprint == machine.connectorPublicKeyFingerprint
        else { throw AccountClientError.invalidResponse }
        keys.removeValue(forKey: operation)
        let completed = replacing(
            pending,
            pendingOperationIdempotencyKeys: keys.isEmpty ? nil : keys
        )
        try sessionStore.save(completed)
        sessionRecord = completed
        let state = try await loadDashboard(record: completed)
        guard case .signedIn(let dashboard) = state else {
            throw AccountClientError.invalidResponse
        }
        return try bindingPreparation(from: dashboard)
    }

    public func confirmBinding() async throws -> DesktopAccountState {
        guard let record = sessionRecord,
              let dashboard = dashboardSnapshot,
              dashboard.binding.state == "binding_pending",
              dashboard.binding.keyProved == true,
              dashboard.binding.healthVerified == true,
              let bindingID = dashboard.binding.id,
              let generation = dashboard.binding.generation
        else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-BIND-005",
                message: "Connector proof and health are required.",
                retryable: true,
                recoveryAction: "retry",
                correlationId: nil
            ))
        }
        let refreshed = try await refreshIfNeeded(record)
        let operation = "binding.confirm:\(bindingID):\(generation)"
        var keys = refreshed.pendingOperationIdempotencyKeys ?? [:]
        let idempotencyKey = try validOrNewIdempotencyKey(keys[operation])
        keys[operation] = idempotencyKey
        let pending = replacing(refreshed, pendingOperationIdempotencyKeys: keys)
        try sessionStore.save(pending)
        sessionRecord = pending
        _ = try await api.confirmBinding(
            bindingID: bindingID,
            generation: generation,
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

    public func requestAccountDeletionVerification() async throws -> DesktopEmailVerificationChallenge {
        guard let record = sessionRecord else {
            throw accountOperationError(
                code: "HR-AUTH-003",
                message: "Session expired.",
                recoveryAction: "sign_in"
            )
        }
        try requireAccountDeletion()
        let current = try await refreshIfNeeded(record)
        guard let accountEmail = current.account.email else {
            throw accountOperationError(
                code: "HR-AUTH-006",
                message: "Email verification is required.",
                recoveryAction: "reauthenticate"
            )
        }
        let email = try normalizedEmail(accountEmail)
        let challenge = try await api.requestEmailReauthenticationChallenge(
            email: email,
            accessToken: current.session.accessToken
        )
        sessionRecord = current
        return DesktopEmailVerificationChallenge(
            challenge: challenge,
            email: email,
            idempotencyKey: UUID().uuidString.lowercased()
        )
    }

    public func deleteAccount(
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String,
        acknowledgedPermanentCloudDeletion: Bool
    ) async throws -> DesktopAccountState {
        guard let record = sessionRecord else { return .signedOut }
        try requireAccountDeletion()
        guard acknowledgedPermanentCloudDeletion else {
            throw accountOperationError(
                code: "HR-ACCOUNT-004",
                message: "Permanent Cloud deletion must be acknowledged.",
                recoveryAction: "none"
            )
        }
        let operation = "account.delete"
        var current = record
        var operationKeys = current.pendingOperationIdempotencyKeys ?? [:]
        var grants = current.pendingReauthenticationGrants ?? [:]

        let grant: String
        if let pendingGrant = grants[operation] {
            grant = pendingGrant
        } else {
            current = try await refreshIfNeeded(current)
            operationKeys = current.pendingOperationIdempotencyKeys ?? [:]
            grants = current.pendingReauthenticationGrants ?? [:]
            guard verification.email == current.account.email?.lowercased() else {
                throw accountOperationError(
                    code: "HR-AUTH-006",
                    message: "Email verification is required.",
                    recoveryAction: "reauthenticate"
                )
            }
            let reauthenticationOperation = "\(operation):reauth:\(verification.challenge.challengeId)"
            let reauthenticationKey = try validOrProvidedIdempotencyKey(
                operationKeys[reauthenticationOperation],
                provided: verification.idempotencyKey
            )
            operationKeys[reauthenticationOperation] = reauthenticationKey
            current = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys,
                pendingReauthenticationGrants: grants.isEmpty ? nil : grants
            )
            try sessionStore.save(current)
            sessionRecord = current
            let response = try await api.reauthenticateEmail(
                challengeID: verification.challenge.challengeId,
                email: verification.email,
                code: verificationCode.trimmingCharacters(in: .whitespacesAndNewlines),
                scope: operation,
                accessToken: current.session.accessToken,
                idempotencyKey: reauthenticationKey
            )
            guard response.scope == operation else { throw AccountClientError.invalidResponse }
            grant = response.grant
            grants[operation] = grant
            operationKeys.removeValue(forKey: reauthenticationOperation)
            current = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys,
                pendingReauthenticationGrants: grants
            )
            try sessionStore.save(current)
            sessionRecord = current
        }

        let mutationOperation = "\(operation):commit"
        let mutationKey = try validOrNewIdempotencyKey(operationKeys[mutationOperation])
        operationKeys[mutationOperation] = mutationKey
        current = replacing(
            current,
            pendingOperationIdempotencyKeys: operationKeys,
            pendingReauthenticationGrants: grants
        )
        try sessionStore.save(current)
        sessionRecord = current
        do {
            try await api.deleteAccount(
                grant: grant,
                acknowledgedPermanentCloudDeletion: true,
                accessToken: current.session.accessToken,
                idempotencyKey: mutationKey
            )
        } catch AccountClientError.remote(let remote) where remote.code == "HR-AUTH-006" {
            grants.removeValue(forKey: operation)
            operationKeys.removeValue(forKey: mutationOperation)
            let cleared = replacing(
                current,
                pendingOperationIdempotencyKeys: operationKeys.isEmpty ? nil : operationKeys,
                pendingReauthenticationGrants: grants.isEmpty ? nil : grants
            )
            try sessionStore.save(cleared)
            sessionRecord = cleared
            throw AccountClientError.remote(remote)
        }
        try sessionStore.delete()
        sessionRecord = nil
        dashboardSnapshot = nil
        return .accountDeletionSubmitted
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
        dashboardSnapshot = nil
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
            let devicePage: AccountDevicePage
            if capabilitiesSnapshot?.binding.supportsDeviceSelection == true {
                devicePage = try await api.devices(accessToken: refreshed.session.accessToken)
            } else {
                devicePage = AccountDevicePage(items: [], maxOwnedDevices: 1)
            }
            let (accountSnapshot, installationSnapshot, bindingSnapshot) = try await (
                account,
                installations,
                binding
            )
            let supportsDeviceSharing = capabilitiesSnapshot?.binding.supportsDeviceSharing == true
            var deviceShares: [String: DeviceShareManagement] = [:]
            if supportsDeviceSharing {
                for device in devicePage.items where device.access == "owner" {
                    deviceShares[device.deviceId] = try await api.deviceShares(
                        id: device.deviceId,
                        accessToken: refreshed.session.accessToken
                    )
                }
            }
            guard accountSnapshot.account.id == refreshed.account.id,
                  accountSnapshot.installation.id == refreshed.installation.id,
                  accountSnapshot.installation.kind == "desktop"
            else { throw AccountClientError.invalidResponse }
            let current = AccountSessionRecord(
                account: accountSnapshot.account,
                installation: accountSnapshot.installation,
                session: refreshed.session,
                pendingRefreshIdempotencyKey: refreshed.pendingRefreshIdempotencyKey,
                pendingOperationIdempotencyKeys: refreshed.pendingOperationIdempotencyKeys,
                pendingReauthenticationGrants: refreshed.pendingReauthenticationGrants
            )
            if current != refreshed { try sessionStore.save(current) }
            sessionRecord = current
            let supportsDeviceSelection = capabilitiesSnapshot?.binding.supportsDeviceSelection == true
            let selectedDeviceID: String?
            if supportsDeviceSelection {
                let storedSelection = deviceSelectionStore.load(accountID: current.account.id)
                selectedDeviceID = devicePage.items.contains(where: { $0.deviceId == storedSelection })
                    ? storedSelection
                    : devicePage.items.first(where: \.isDefault)?.deviceId ?? devicePage.items.first?.deviceId
                deviceSelectionStore.save(selectedDeviceID, accountID: current.account.id)
            } else {
                selectedDeviceID = nil
            }
            let dashboard = AccountDashboard(
                session: current,
                binding: bindingSnapshot,
                installations: installationSnapshot,
                devices: devicePage.items,
                maxOwnedDevices: devicePage.maxOwnedDevices,
                selectedDeviceID: selectedDeviceID,
                deviceShares: deviceShares,
                supportsDeviceSharing: supportsDeviceSharing,
                maxSharedDevices: capabilitiesSnapshot?.binding.maxSharedDevices ?? 0,
                accountDeletionEnabled: capabilitiesSnapshot?.accountAuth.accountDeletion == true,
                desktopBootstrapRuntimeContract: capabilitiesSnapshot?.desktopBootstrap?.runtimeContract
            )
            dashboardSnapshot = dashboard
            return .signedIn(dashboard)
        } catch AccountClientError.remote(let remote)
            where remote.code == "HR-AUTH-003"
                || remote.code == "HR-AUTH-004"
                || remote.code == "HR-AUTH-005" {
            try? sessionStore.delete()
            sessionRecord = nil
            dashboardSnapshot = nil
            let issueCode: DesktopIssueCode = switch remote.code {
            case "HR-AUTH-004": .accountSessionRevoked
            case "HR-AUTH-005": .refreshCredentialReused
            default: .accountSessionExpired
            }
            return .needsSignIn(issueCode)
        }
    }

    private func recoverPendingAccountDeletion(
        _ record: AccountSessionRecord
    ) async throws -> DesktopAccountState? {
        guard let grant = record.pendingReauthenticationGrants?["account.delete"],
              let idempotencyKey = record.pendingOperationIdempotencyKeys?["account.delete:commit"]
        else { return nil }
        do {
            try await api.deleteAccount(
                grant: grant,
                acknowledgedPermanentCloudDeletion: true,
                accessToken: record.session.accessToken,
                idempotencyKey: idempotencyKey
            )
        } catch AccountClientError.remote(let remote) where remote.code == "HR-ACCOUNT-012" {
            // The server committed deletion but its completion replay record has already expired.
        }
        try sessionStore.delete()
        sessionRecord = nil
        dashboardSnapshot = nil
        return .accountDeletionSubmitted
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
            pendingOperationIdempotencyKeys: record.pendingOperationIdempotencyKeys,
            pendingReauthenticationGrants: record.pendingReauthenticationGrants
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
            pendingOperationIdempotencyKeys: pending.pendingOperationIdempotencyKeys,
            pendingReauthenticationGrants: pending.pendingReauthenticationGrants
        )
        try sessionStore.save(refreshed)
        sessionRecord = refreshed
        return refreshed
    }

    private func replacing(
        _ record: AccountSessionRecord,
        pendingOperationIdempotencyKeys: [String: String]?
    ) -> AccountSessionRecord {
        replacing(
            record,
            pendingOperationIdempotencyKeys: pendingOperationIdempotencyKeys,
            pendingReauthenticationGrants: record.pendingReauthenticationGrants
        )
    }

    private func replacing(
        _ record: AccountSessionRecord,
        pendingOperationIdempotencyKeys: [String: String]?,
        pendingReauthenticationGrants: [String: String]?
    ) -> AccountSessionRecord {
        AccountSessionRecord(
            account: record.account,
            installation: record.installation,
            session: record.session,
            pendingRefreshIdempotencyKey: record.pendingRefreshIdempotencyKey,
            pendingOperationIdempotencyKeys: pendingOperationIdempotencyKeys,
            pendingReauthenticationGrants: pendingReauthenticationGrants
        )
    }

    private func bindingPreparation(from dashboard: AccountDashboard) throws -> DesktopBindingPreparation {
        let binding = dashboard.binding
        guard binding.state == "binding_pending",
              let bindingID = binding.id,
              let generation = binding.generation,
              let fingerprint = binding.publicKeyFingerprint
        else { throw AccountClientError.invalidResponse }
        let credential = try machineIdentityStore.loadOrCreate().accountConnectorCredential(
            bindingID: bindingID,
            generation: generation,
            expectedFingerprint: fingerprint
        )
        return DesktopBindingPreparation(state: .signedIn(dashboard), credential: credential)
    }

    private func replacing(
        _ dashboard: AccountDashboard,
        selectedDeviceID: String?
    ) -> AccountDashboard {
        AccountDashboard(
            session: dashboard.session,
            binding: dashboard.binding,
            installations: dashboard.installations,
            devices: dashboard.devices,
            maxOwnedDevices: dashboard.maxOwnedDevices,
            selectedDeviceID: selectedDeviceID,
            deviceShares: dashboard.deviceShares,
            supportsDeviceSharing: dashboard.supportsDeviceSharing,
            maxSharedDevices: dashboard.maxSharedDevices,
            accountDeletionEnabled: dashboard.accountDeletionEnabled
        )
    }

    private func requireSharing(ownerDeviceID: String) throws {
        guard capabilitiesSnapshot?.binding.supportsDeviceSharing == true else {
            throw sharingError(code: "HR-SHARE-001", message: "Device sharing is disabled.")
        }
        guard dashboardSnapshot?.devices.contains(where: {
            $0.deviceId == ownerDeviceID && $0.access == "owner"
        }) == true else {
            throw sharingError(code: "HR-BIND-011", message: "Device is unavailable.")
        }
    }

    private func requirePhoneRevocation(id: String) throws {
        guard capabilitiesSnapshot?.accountAuth.identityManagement == true,
              capabilitiesSnapshot?.accountAuth.providers.contains("email_otp") == true
        else {
            throw accountOperationError(
                code: "HR-ACCOUNT-009",
                message: "Identity management is disabled.",
                recoveryAction: "none"
            )
        }
        guard dashboardSnapshot?.phones.contains(where: {
            $0.id == id && !$0.current && $0.status == "active"
        }) == true else {
            throw accountOperationError(
                code: "HR-ACCOUNT-006",
                message: "Phone installation was not found.",
                recoveryAction: "refresh"
            )
        }
    }

    private func requireAccountDeletion() throws {
        guard capabilitiesSnapshot?.accountAuth.accountDeletion == true,
              capabilitiesSnapshot?.accountAuth.providers.contains("email_otp") == true,
              dashboardSnapshot?.accountDeletionEnabled == true
        else {
            throw accountOperationError(
                code: "HR-ACCOUNT-003",
                message: "Account deletion is disabled.",
                recoveryAction: "none"
            )
        }
    }

    private func performMutation(
        record: AccountSessionRecord,
        operation: String,
        action: @Sendable (String, String) async throws -> Void
    ) async throws -> AccountSessionRecord {
        let refreshed = try await refreshIfNeeded(record)
        var keys = refreshed.pendingOperationIdempotencyKeys ?? [:]
        let idempotencyKey = try validOrNewIdempotencyKey(keys[operation])
        keys[operation] = idempotencyKey
        let pending = replacing(refreshed, pendingOperationIdempotencyKeys: keys)
        try sessionStore.save(pending)
        sessionRecord = pending
        try await action(pending.session.accessToken, idempotencyKey)
        keys.removeValue(forKey: operation)
        let completed = replacing(
            pending,
            pendingOperationIdempotencyKeys: keys.isEmpty ? nil : keys
        )
        try sessionStore.save(completed)
        sessionRecord = completed
        return completed
    }

    private func sharingError(code: String, message: String) -> AccountClientError {
        .remote(AccountRemoteError(
            code: code,
            message: message,
            retryable: false,
            recoveryAction: "open_sharing",
            correlationId: nil
        ))
    }

    private func accountOperationError(
        code: String,
        message: String,
        recoveryAction: String
    ) -> AccountClientError {
        .remote(AccountRemoteError(
            code: code,
            message: message,
            retryable: false,
            recoveryAction: recoveryAction,
            correlationId: nil
        ))
    }

    private func requireEmailAuthentication(_ capabilities: AccountCapabilities) throws {
        guard capabilities.accountAuth.enabled,
              capabilities.accountAuth.macos,
              capabilities.accountAuth.providers.contains("email_otp")
        else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-ACCOUNT-003",
                message: "Email sign-in is disabled.",
                retryable: false,
                recoveryAction: "continue_legacy",
                correlationId: nil
            ))
        }
    }

    private func normalizedEmail(_ value: String) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard (3...254).contains(normalized.utf8.count),
              !normalized.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              let separator = normalized.lastIndex(of: "@"),
              separator != normalized.startIndex,
              normalized.index(after: separator) != normalized.endIndex
        else {
            throw AccountClientError.remote(AccountRemoteError(
                code: "HR-ACCOUNT-004",
                message: "Email address is invalid.",
                retryable: false,
                recoveryAction: "retry",
                correlationId: nil
            ))
        }
        return normalized
    }

    private func validOrNewIdempotencyKey(_ stored: String?) throws -> String {
        guard let stored else { return UUID().uuidString.lowercased() }
        guard UUID(uuidString: stored) != nil else { throw AccountSecretStoreError.decoding }
        return stored.lowercased()
    }

    private func validOrProvidedIdempotencyKey(_ stored: String?, provided: String) throws -> String {
        if let stored { return try validOrNewIdempotencyKey(stored) }
        guard UUID(uuidString: provided) != nil else { throw AccountSecretStoreError.decoding }
        return provided.lowercased()
    }
}

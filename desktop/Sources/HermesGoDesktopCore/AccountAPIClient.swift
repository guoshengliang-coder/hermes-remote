import Foundation

public protocol AccountAPIRequesting: Sendable {
    func capabilities() async throws -> AccountCapabilities
    func requestEmailSignInChallenge(
        email: String,
        clientInstallationID: String
    ) async throws -> EmailOtpChallenge
    func exchangeEmailChallenge(
        challengeID: String,
        email: String,
        code: String,
        clientInstallationID: String,
        displayName: String,
        appVersion: String,
        idempotencyKey: String
    ) async throws -> AccountSessionRecord
    func exchangeGoogleProof(
        _ proof: GoogleIdentityProof,
        clientInstallationID: String,
        displayName: String,
        appVersion: String
    ) async throws -> AccountSessionRecord
    func refresh(
        refreshToken: String,
        clientInstallationID: String,
        idempotencyKey: String
    ) async throws -> AccountSessionTokens
    func account(accessToken: String) async throws -> AccountSnapshot
    func installations(accessToken: String) async throws -> [ManagedAccountInstallation]
    func binding(accessToken: String) async throws -> AccountBindingSnapshot
    func devices(accessToken: String) async throws -> AccountDevicePage
    func selectDefaultDevice(
        id: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountDevice
    func reauthenticateGoogle(
        _ proof: GoogleIdentityProof,
        scope: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant
    func requestEmailReauthenticationChallenge(
        email: String,
        accessToken: String
    ) async throws -> EmailOtpChallenge
    func reauthenticateEmail(
        challengeID: String,
        email: String,
        code: String,
        scope: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant
    func deviceShares(id: String, accessToken: String) async throws -> DeviceShareManagement
    func createShareInvitation(
        deviceID: String,
        email: String,
        grant: String,
        acknowledgedWholeDeviceAccess: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> DeviceShareInvitation
    func cancelShareInvitation(
        deviceID: String,
        invitationID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws
    func revokeDeviceShare(
        deviceID: String,
        grantID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws
    func leaveSharedDevice(
        deviceID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws
    func acceptShareInvitation(
        token: String,
        acknowledgedWholeDeviceAccess: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountDevice
    func createBinding(
        desktopInstallationID: String,
        displayName: String,
        connectorPublicKey: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingSnapshot
    func confirmBinding(
        bindingID: String,
        generation: Int,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingSnapshot
    func revokePhone(
        id: String,
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws
    func deleteAccount(
        grant: String,
        acknowledgedPermanentCloudDeletion: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws
    func signOut(accessToken: String, idempotencyKey: String) async throws
}

public actor AccountAPIClient: AccountAPIRequesting {
    private let gatewayURL: URL
    private let session: URLSession
    private let maximumResponseBytes: Int

    public init(
        gatewayURL: URL,
        session: URLSession = .shared,
        maximumResponseBytes: Int = 128 * 1024
    ) {
        self.gatewayURL = gatewayURL
        self.session = session
        self.maximumResponseBytes = maximumResponseBytes
    }

    public func capabilities() async throws -> AccountCapabilities {
        try await send(path: "/v2/capabilities", method: "GET")
    }

    public func requestEmailSignInChallenge(
        email: String,
        clientInstallationID: String
    ) async throws -> EmailOtpChallenge {
        guard isValidEmailInput(email), UUID(uuidString: clientInstallationID) != nil
        else { throw AccountClientError.invalidResponse }
        let envelope: EmailOtpChallengeEnvelope = try await send(
            path: "/v2/auth/email/challenges",
            method: "POST",
            body: EmailChallengeRequest(
                email: email,
                platform: "macos",
                clientInstallationId: clientInstallationID.lowercased()
            )
        )
        return try validated(envelope.challenge)
    }

    public func exchangeEmailChallenge(
        challengeID: String,
        email: String,
        code: String,
        clientInstallationID: String,
        displayName: String,
        appVersion: String,
        idempotencyKey: String
    ) async throws -> AccountSessionRecord {
        guard UUID(uuidString: challengeID) != nil,
              UUID(uuidString: clientInstallationID) != nil,
              UUID(uuidString: idempotencyKey) != nil,
              isValidEmailInput(email),
              isValidEmailCode(code)
        else { throw AccountClientError.invalidResponse }
        return try await send(
            path: "/v2/auth/email/exchange",
            method: "POST",
            idempotencyKey: idempotencyKey.lowercased(),
            body: EmailExchangeRequest(
                challengeId: challengeID.lowercased(),
                email: email,
                code: code,
                platform: "macos",
                clientInstallationId: clientInstallationID.lowercased(),
                displayName: displayName,
                appVersion: appVersion
            )
        )
    }

    public func exchangeGoogleProof(
        _ proof: GoogleIdentityProof,
        clientInstallationID: String,
        displayName: String,
        appVersion: String
    ) async throws -> AccountSessionRecord {
        try await send(
            path: "/v2/auth/google/exchange",
            method: "POST",
            idempotencyKey: UUID().uuidString.lowercased(),
            body: GoogleExchangeRequest(
                platform: "macos",
                idToken: proof.idToken,
                nonce: proof.nonce,
                clientInstallationId: clientInstallationID,
                displayName: displayName,
                appVersion: appVersion
            )
        )
    }

    public func refresh(
        refreshToken: String,
        clientInstallationID: String,
        idempotencyKey: String
    ) async throws -> AccountSessionTokens {
        let response: SessionEnvelope = try await send(
            path: "/v2/auth/refresh",
            method: "POST",
            idempotencyKey: idempotencyKey,
            body: RefreshRequest(
                refreshToken: refreshToken,
                clientInstallationId: clientInstallationID
            )
        )
        return response.session
    }

    public func account(accessToken: String) async throws -> AccountSnapshot {
        try await send(path: "/v2/account", method: "GET", accessToken: accessToken)
    }

    public func installations(accessToken: String) async throws -> [ManagedAccountInstallation] {
        let result: ManagedInstallationPage = try await send(
            path: "/v2/installations",
            method: "GET",
            accessToken: accessToken
        )
        return result.items
    }

    public func binding(accessToken: String) async throws -> AccountBindingSnapshot {
        try await send(
            path: "/v2/connector-binding",
            method: "GET",
            accessToken: accessToken
        )
    }

    public func devices(accessToken: String) async throws -> AccountDevicePage {
        try await send(path: "/v2/devices", method: "GET", accessToken: accessToken)
    }

    public func selectDefaultDevice(
        id: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountDevice {
        guard isValidDeviceID(id) else { throw AccountClientError.invalidResponse }
        let envelope: AccountDeviceEnvelope = try await send(
            path: "/v2/devices/\(id)/select-default",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
        return envelope.device
    }

    public func reauthenticateGoogle(
        _ proof: GoogleIdentityProof,
        scope: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant {
        guard supportedReauthenticationScopes.contains(scope) else {
            throw AccountClientError.invalidResponse
        }
        return try await send(
            path: "/v2/auth/reauth/google",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: GoogleReauthenticationRequest(
                idToken: proof.idToken,
                nonce: proof.nonce,
                scope: scope
            )
        )
    }

    public func requestEmailReauthenticationChallenge(
        email: String,
        accessToken: String
    ) async throws -> EmailOtpChallenge {
        guard isValidEmailInput(email) else { throw AccountClientError.invalidResponse }
        let envelope: EmailOtpChallengeEnvelope = try await send(
            path: "/v2/auth/reauth/email/challenges",
            method: "POST",
            accessToken: accessToken,
            body: EmailReauthenticationChallengeRequest(email: email)
        )
        return try validated(envelope.challenge)
    }

    public func reauthenticateEmail(
        challengeID: String,
        email: String,
        code: String,
        scope: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant {
        guard UUID(uuidString: challengeID) != nil,
              UUID(uuidString: idempotencyKey) != nil,
              isValidEmailInput(email),
              isValidEmailCode(code),
              supportedReauthenticationScopes.contains(scope)
        else { throw AccountClientError.invalidResponse }
        return try await send(
            path: "/v2/auth/reauth/email",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey.lowercased(),
            body: EmailReauthenticationRequest(
                challengeId: challengeID.lowercased(),
                email: email,
                code: code,
                scope: scope
            )
        )
    }

    public func deviceShares(id: String, accessToken: String) async throws -> DeviceShareManagement {
        guard isValidDeviceID(id) else { throw AccountClientError.invalidResponse }
        return try await send(
            path: "/v2/devices/\(id)/shares",
            method: "GET",
            accessToken: accessToken
        )
    }

    public func createShareInvitation(
        deviceID: String,
        email: String,
        grant: String,
        acknowledgedWholeDeviceAccess: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> DeviceShareInvitation {
        guard isValidDeviceID(deviceID), isValidEmailInput(email), acknowledgedWholeDeviceAccess
        else { throw AccountClientError.invalidResponse }
        let envelope: DeviceShareInvitationEnvelope = try await send(
            path: "/v2/devices/\(deviceID)/share-invitations",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: CreateShareInvitationRequest(
                email: email,
                grant: grant,
                acknowledgedWholeDeviceAccess: acknowledgedWholeDeviceAccess
            )
        )
        return envelope.invitation
    }

    public func cancelShareInvitation(
        deviceID: String,
        invitationID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        try await sendShareDeletion(
            path: try shareInvitationPath(deviceID: deviceID, invitationID: invitationID),
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
    }

    public func revokeDeviceShare(
        deviceID: String,
        grantID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        guard isValidDeviceID(deviceID), UUID(uuidString: grantID) != nil
        else { throw AccountClientError.invalidResponse }
        try await sendShareDeletion(
            path: "/v2/devices/\(deviceID)/shares/\(grantID.lowercased())",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
    }

    public func leaveSharedDevice(
        deviceID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        guard isValidDeviceID(deviceID) else { throw AccountClientError.invalidResponse }
        try await sendEmpty(
            path: "/v2/devices/\(deviceID)/leave",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
    }

    public func acceptShareInvitation(
        token: String,
        acknowledgedWholeDeviceAccess: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountDevice {
        guard isValidShareInvitationToken(token), acknowledgedWholeDeviceAccess
        else { throw AccountClientError.invalidResponse }
        let envelope: AccountDeviceEnvelope = try await send(
            path: "/v2/share-invitations/\(token)/accept",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: WholeDeviceAcknowledgementRequest(
                acknowledgedWholeDeviceAccess: acknowledgedWholeDeviceAccess
            )
        )
        return envelope.device
    }

    public func createBinding(
        desktopInstallationID: String,
        displayName: String,
        connectorPublicKey: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingSnapshot {
        guard UUID(uuidString: desktopInstallationID) != nil,
              (1...128).contains(displayName.utf8.count),
              connectorPublicKey.range(
                of: "^[A-Za-z0-9_-]{43}$",
                options: .regularExpression
              ) != nil
        else { throw AccountClientError.invalidResponse }
        return try await send(
            path: "/v2/connector-binding",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: CreateBindingRequest(
                desktopInstallationId: desktopInstallationID.lowercased(),
                displayName: displayName,
                connectorPublicKey: connectorPublicKey,
                keyAlgorithm: "Ed25519"
            )
        )
    }

    public func confirmBinding(
        bindingID: String,
        generation: Int,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingSnapshot {
        guard UUID(uuidString: bindingID) != nil,
              (1...2_147_483_647).contains(generation)
        else { throw AccountClientError.invalidResponse }
        return try await send(
            path: "/v2/connector-binding/confirm",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: ConfirmBindingRequest(
                bindingId: bindingID.lowercased(),
                generation: generation
            )
        )
    }

    public func revokePhone(
        id: String,
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        guard UUID(uuidString: id) != nil,
              !grant.isEmpty,
              grant.utf8.count <= 256
        else { throw AccountClientError.invalidResponse }
        try await sendEmpty(
            path: "/v2/installations/\(id.lowercased())",
            method: "DELETE",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: InstallationRevocationRequest(grant: grant)
        )
    }

    public func deleteAccount(
        grant: String,
        acknowledgedPermanentCloudDeletion: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        guard !grant.isEmpty,
              grant.utf8.count <= 256,
              acknowledgedPermanentCloudDeletion
        else { throw AccountClientError.invalidResponse }
        try await sendEmpty(
            path: "/v2/account",
            method: "DELETE",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: AccountDeletionRequest(
                grant: grant,
                acknowledgedPermanentCloudDeletion: acknowledgedPermanentCloudDeletion
            )
        )
    }

    public func signOut(accessToken: String, idempotencyKey: String) async throws {
        try await sendEmpty(
            path: "/v2/auth/sign-out",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
    }

    private func send<Response: Decodable>(
        path: String,
        method: String,
        accessToken: String? = nil,
        idempotencyKey: String? = nil
    ) async throws -> Response {
        try await send(
            path: path,
            method: method,
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            bodyData: nil
        )
    }

    private func send<Response: Decodable, Body: Encodable>(
        path: String,
        method: String,
        accessToken: String? = nil,
        idempotencyKey: String? = nil,
        body: Body
    ) async throws -> Response {
        let data: Data
        do {
            data = try JSONEncoder().encode(body)
        } catch {
            throw AccountClientError.invalidResponse
        }
        return try await send(
            path: path,
            method: method,
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            bodyData: data
        )
    }

    private func send<Response: Decodable>(
        path: String,
        method: String,
        accessToken: String?,
        idempotencyKey: String?,
        bodyData: Data?
    ) async throws -> Response {
        let (data, response) = try await execute(
            path: path,
            method: method,
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            bodyData: bodyData
        )
        try requireSuccess(response, data: data)
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw AccountClientError.invalidResponse
        }
    }

    private func sendEmpty(
        path: String,
        method: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        let (data, response) = try await execute(
            path: path,
            method: method,
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            bodyData: nil
        )
        try requireSuccess(response, data: data)
    }

    private func sendEmpty<Body: Encodable>(
        path: String,
        method: String,
        accessToken: String,
        idempotencyKey: String,
        body: Body
    ) async throws {
        let bodyData: Data
        do {
            bodyData = try JSONEncoder().encode(body)
        } catch {
            throw AccountClientError.invalidResponse
        }
        let (data, response) = try await execute(
            path: path,
            method: method,
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            bodyData: bodyData
        )
        try requireSuccess(response, data: data)
    }

    private func sendShareDeletion(
        path: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        try await sendEmpty(
            path: path,
            method: "DELETE",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
    }

    private func shareInvitationPath(deviceID: String, invitationID: String) throws -> String {
        guard isValidDeviceID(deviceID), UUID(uuidString: invitationID) != nil
        else { throw AccountClientError.invalidResponse }
        return "/v2/devices/\(deviceID)/share-invitations/\(invitationID.lowercased())"
    }

    private func execute(
        path: String,
        method: String,
        accessToken: String?,
        idempotencyKey: String?,
        bodyData: Data?
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: path, relativeTo: gatewayURL)?.absoluteURL,
              url.host == gatewayURL.host,
              url.scheme == gatewayURL.scheme
        else { throw AccountClientError.invalidConfiguration }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        if let idempotencyKey {
            request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key")
        }
        if let bodyData {
            request.httpBody = bodyData
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let value: (Data, URLResponse)
        do {
            value = try await session.data(for: request)
        } catch {
            throw AccountClientError.transport
        }
        guard let response = value.1 as? HTTPURLResponse else {
            throw AccountClientError.invalidResponse
        }
        guard value.0.count <= maximumResponseBytes else {
            throw AccountClientError.responseTooLarge
        }
        return (value.0, response)
    }

    private func requireSuccess(_ response: HTTPURLResponse, data: Data) throws {
        guard (200..<300).contains(response.statusCode) else {
            guard let envelope = try? JSONDecoder().decode(ErrorEnvelope.self, from: data) else {
                throw AccountClientError.invalidResponse
            }
            throw AccountClientError.remote(envelope.error)
        }
    }
}

private struct GoogleExchangeRequest: Encodable {
    let platform: String
    let idToken: String
    let nonce: String
    let clientInstallationId: String
    let displayName: String
    let appVersion: String
}

private struct EmailChallengeRequest: Encodable {
    let email: String
    let platform: String
    let clientInstallationId: String
}

private struct EmailExchangeRequest: Encodable {
    let challengeId: String
    let email: String
    let code: String
    let platform: String
    let clientInstallationId: String
    let displayName: String
    let appVersion: String
}

private struct GoogleReauthenticationRequest: Encodable {
    let idToken: String
    let nonce: String
    let scope: String
}

private struct EmailReauthenticationChallengeRequest: Encodable {
    let email: String
}

private struct EmailReauthenticationRequest: Encodable {
    let challengeId: String
    let email: String
    let code: String
    let scope: String
}

private struct InstallationRevocationRequest: Encodable {
    let grant: String
}

private struct AccountDeletionRequest: Encodable {
    let grant: String
    let acknowledgedPermanentCloudDeletion: Bool
}

private struct CreateShareInvitationRequest: Encodable {
    let email: String
    let grant: String
    let acknowledgedWholeDeviceAccess: Bool
}

private struct WholeDeviceAcknowledgementRequest: Encodable {
    let acknowledgedWholeDeviceAccess: Bool
}

private struct RefreshRequest: Encodable {
    let refreshToken: String
    let clientInstallationId: String
}

private struct CreateBindingRequest: Encodable {
    let desktopInstallationId: String
    let displayName: String
    let connectorPublicKey: String
    let keyAlgorithm: String
}

private struct ConfirmBindingRequest: Encodable {
    let bindingId: String
    let generation: Int
}

private struct SessionEnvelope: Decodable {
    let session: AccountSessionTokens
}

private struct EmailOtpChallengeEnvelope: Decodable {
    let challenge: EmailOtpChallenge
}

private struct ErrorEnvelope: Decodable {
    let error: AccountRemoteError
}

private func isValidShareInvitationToken(_ value: String) -> Bool {
    value.range(of: "^hsi_[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil
}

private let supportedReauthenticationScopes: Set<String> = [
    "account.installation.revoke",
    "account.delete",
    "device.share",
]

private func isValidEmailInput(_ value: String) -> Bool {
    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard (3...254).contains(normalized.utf8.count),
          !normalized.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
          let separator = normalized.lastIndex(of: "@"),
          separator != normalized.startIndex,
          normalized.index(after: separator) != normalized.endIndex
    else { return false }
    return true
}

private func isValidEmailCode(_ value: String) -> Bool {
    value.range(of: "^[0-9]{6}$", options: .regularExpression) != nil
}

private func validated(_ challenge: EmailOtpChallenge) throws -> EmailOtpChallenge {
    guard UUID(uuidString: challenge.challengeId) != nil,
          parseAccountDate(challenge.expiresAt) != nil,
          parseAccountDate(challenge.resendAfter) != nil
    else { throw AccountClientError.invalidResponse }
    return challenge
}

private func parseAccountDate(_ value: String) -> Date? {
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
}

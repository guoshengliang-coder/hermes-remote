import Foundation

public protocol AccountAPIRequesting: Sendable {
    func capabilities() async throws -> AccountCapabilities
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
    func reauthenticateGoogle(
        _ proof: GoogleIdentityProof,
        scope: AccountReauthenticationScope,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant
    func createBinding(
        _ input: ConnectorBindingInput,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingCandidate
    func confirmBinding(
        id: String,
        generation: Int,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> ActiveAccountBinding
    func createReplacement(
        _ input: ConnectorBindingInput,
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReplacementRequest
    func confirmReplacement(
        requestID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> ActiveAccountBinding
    func unbind(grant: String, accessToken: String, idempotencyKey: String) async throws
    func revokePhone(id: String, accessToken: String, idempotencyKey: String) async throws
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

    public func reauthenticateGoogle(
        _ proof: GoogleIdentityProof,
        scope: AccountReauthenticationScope,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant {
        let grant: AccountReauthenticationGrant = try await send(
            path: "/v2/auth/reauth/google",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: ReauthenticationRequest(
                idToken: proof.idToken,
                nonce: proof.nonce,
                scope: scope
            )
        )
        guard grant.scope == scope,
              grant.grant.hasPrefix("hgg_"),
              grant.grant.count == 47
        else { throw AccountClientError.invalidResponse }
        return grant
    }

    public func createBinding(
        _ input: ConnectorBindingInput,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingCandidate {
        try validateBindingInput(input)
        let candidate: AccountBindingCandidate = try await send(
            path: "/v2/connector-binding",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: BindingRequest(input: input, grant: nil)
        )
        guard valid(candidate: candidate) else { throw AccountClientError.invalidResponse }
        return candidate
    }

    public func confirmBinding(
        id: String,
        generation: Int,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> ActiveAccountBinding {
        guard normalizedUUID(id) != nil, generation > 0 else {
            throw AccountClientError.invalidResponse
        }
        let response: BoundBindingEnvelope = try await send(
            path: "/v2/connector-binding/confirm",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: ConfirmBindingRequest(bindingId: id.lowercased(), generation: generation)
        )
        guard response.state == "bound", valid(binding: response.binding) else {
            throw AccountClientError.invalidResponse
        }
        return response.binding
    }

    public func createReplacement(
        _ input: ConnectorBindingInput,
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReplacementRequest {
        try validateBindingInput(input)
        let replacement: AccountReplacementRequest = try await send(
            path: "/v2/connector-binding/replacement-requests",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: BindingRequest(input: input, grant: grant)
        )
        guard normalizedUUID(replacement.id) != nil,
              replacement.state == "replacement_pending",
              valid(binding: replacement.previousBinding),
              valid(candidate: replacement.candidate)
        else { throw AccountClientError.invalidResponse }
        return replacement
    }

    public func confirmReplacement(
        requestID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> ActiveAccountBinding {
        guard let requestID = normalizedUUID(requestID) else {
            throw AccountClientError.invalidResponse
        }
        let response: BoundBindingEnvelope = try await send(
            path: "/v2/connector-binding/replacement-requests/\(requestID)/confirm",
            method: "POST",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
        )
        guard response.state == "bound", valid(binding: response.binding) else {
            throw AccountClientError.invalidResponse
        }
        return response.binding
    }

    public func unbind(
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        try await sendEmpty(
            path: "/v2/connector-binding",
            method: "DELETE",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey,
            body: GrantRequest(grant: grant)
        )
    }

    public func revokePhone(id: String, accessToken: String, idempotencyKey: String) async throws {
        guard UUID(uuidString: id) != nil else { throw AccountClientError.invalidResponse }
        try await sendEmpty(
            path: "/v2/installations/\(id.lowercased())",
            method: "DELETE",
            accessToken: accessToken,
            idempotencyKey: idempotencyKey
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

    private func execute(
        path: String,
        method: String,
        accessToken: String?,
        idempotencyKey: String?,
        bodyData: Data?
    ) async throws -> (Data, HTTPURLResponse) {
        if let idempotencyKey, UUID(uuidString: idempotencyKey) == nil {
            throw AccountClientError.invalidConfiguration
        }
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

    private func normalizedUUID(_ value: String) -> String? {
        guard UUID(uuidString: value) != nil else { return nil }
        return value.lowercased()
    }

    private func validateBindingInput(_ input: ConnectorBindingInput) throws {
        guard normalizedUUID(input.desktopInstallationID) != nil,
              (1...128).contains(input.displayName.count),
              !input.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !input.displayName.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              input.connectorPublicKey.count == 43
        else { throw AccountClientError.invalidConfiguration }
    }

    private func valid(candidate: AccountBindingCandidate) -> Bool {
        normalizedUUID(candidate.id) != nil
            && candidate.generation > 0
            && candidate.state == "binding_pending"
            && candidate.publicKeyFingerprint.count == 64
    }

    private func valid(binding: ActiveAccountBinding) -> Bool {
        normalizedUUID(binding.id) != nil
            && binding.generation > 0
            && binding.publicKeyFingerprint.count == 64
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

private struct RefreshRequest: Encodable {
    let refreshToken: String
    let clientInstallationId: String
}

private struct ReauthenticationRequest: Encodable {
    let idToken: String
    let nonce: String
    let scope: AccountReauthenticationScope
}

private struct BindingRequest: Encodable {
    let desktopInstallationId: String
    let displayName: String
    let connectorPublicKey: String
    let keyAlgorithm = "Ed25519"
    let grant: String?

    init(input: ConnectorBindingInput, grant: String?) {
        desktopInstallationId = input.desktopInstallationID
        displayName = input.displayName
        connectorPublicKey = input.connectorPublicKey
        self.grant = grant
    }
}

private struct ConfirmBindingRequest: Encodable {
    let bindingId: String
    let generation: Int
}

private struct GrantRequest: Encodable {
    let grant: String
}

private struct BoundBindingEnvelope: Decodable {
    let state: String
    let binding: ActiveAccountBinding
}

private struct SessionEnvelope: Decodable {
    let session: AccountSessionTokens
}

private struct ErrorEnvelope: Decodable {
    let error: AccountRemoteError
}

import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class AccountAPIClientTests: XCTestCase {
    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
    }

    func testCapabilitiesDecodeOptionalDesktopBootstrapRuntimeContract() async throws {
        StubURLProtocol.handler = { _ in
            (200, ["Content-Type": "application/json"], Data(#"{"version":1,"accountAuth":{"enabled":true,"providers":["email_otp"],"android":true,"macos":true,"identityManagement":true,"webAccountCenter":false},"binding":{"enabled":true,"replacement":true,"maxActiveConnectorsPerAccount":3},"legacy":{"appTokenAccepted":true,"connectorTokenAccepted":true},"desktopBootstrap":{"runtimeContract":"hermes-serve-v1"}}"#.utf8))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )

        let capabilities = try await client.capabilities()

        XCTAssertEqual(capabilities.desktopBootstrap?.runtimeContract, "hermes-serve-v1")
    }

    func testRefreshUsesCallerPersistedIdempotencyKeyAndExactClientInstallationID() async throws {
        let requestBox = LockedRequestBox()
        StubURLProtocol.handler = { request in
            requestBox.set(request, body: request.capturedBody())
            return (200, ["Content-Type": "application/json"], Data(#"{"session":{"accessToken":"hga_new","accessExpiresAt":"2099-09-02T01:00:00Z","refreshToken":"hgr_new","refreshExpiresAt":"2099-10-02T00:00:00Z"}}"#.utf8))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let key = "40000000-0000-4000-8000-000000000001"
        let installationID = "50000000-0000-4000-8000-000000000001"

        let tokens = try await client.refresh(
            refreshToken: "hgr_original",
            clientInstallationID: installationID,
            idempotencyKey: key
        )
        let captured = try XCTUnwrap(requestBox.value())
        let request = captured.0
        let body = try XCTUnwrap(captured.1)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])

        XCTAssertEqual(request.url?.path, "/v2/auth/refresh")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), key)
        XCTAssertEqual(json["refreshToken"], "hgr_original")
        XCTAssertEqual(json["clientInstallationId"], installationID)
        XCTAssertEqual(tokens.accessToken, "hga_new")
    }

    func testEmailSignInUsesMacInstallationAndCallerStableExchangeKey() async throws {
        let requests = LockedRequestsBox()
        let challengeID = "30000000-0000-4000-8000-000000000001"
        let installationID = "50000000-0000-4000-8000-000000000001"
        let exchangeKey = "60000000-0000-4000-8000-000000000001"
        StubURLProtocol.handler = { request in
            requests.append(request)
            if request.url?.path == "/v2/auth/email/challenges" {
                return (202, ["Content-Type": "application/json"], Data("""
                {"challenge":{"challengeId":"\(challengeID)","expiresAt":"2099-09-07T00:10:00Z","resendAfter":"2099-09-07T00:01:00Z"}}
                """.utf8))
            }
            return (200, ["Content-Type": "application/json"], Data("""
            {"account":{"id":"10000000-0000-4000-8000-000000000001","displayName":null,"email":"person@example.com","avatarUrl":null},"installation":{"id":"\(installationID)","kind":"desktop","platform":"macos","displayName":"Mac mini"},"session":{"accessToken":"hga_access","accessExpiresAt":"2099-09-07T01:00:00Z","refreshToken":"hgr_refresh","refreshExpiresAt":"2099-10-07T00:00:00Z"}}
            """.utf8))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )

        let challenge = try await client.requestEmailSignInChallenge(
            email: "person@example.com",
            clientInstallationID: installationID
        )
        let record = try await client.exchangeEmailChallenge(
            challengeID: challenge.challengeId,
            email: "person@example.com",
            code: "012345",
            clientInstallationID: installationID,
            displayName: "Mac mini",
            appVersion: "0.4.0",
            idempotencyKey: exchangeKey
        )
        let captured = requests.values
        let challengeBody = try XCTUnwrap(captured[0].capturedBody())
        let challengeJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: challengeBody) as? [String: Any])
        let exchangeBody = try XCTUnwrap(captured[1].capturedBody())
        let exchangeJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: exchangeBody) as? [String: Any])

        XCTAssertEqual(captured.map { $0.url?.path }, [
            "/v2/auth/email/challenges",
            "/v2/auth/email/exchange",
        ])
        XCTAssertEqual(challengeJSON["platform"] as? String, "macos")
        XCTAssertEqual(challengeJSON["clientInstallationId"] as? String, installationID)
        XCTAssertEqual(exchangeJSON["challengeId"] as? String, challengeID)
        XCTAssertEqual(exchangeJSON["code"] as? String, "012345")
        XCTAssertEqual(captured[1].value(forHTTPHeaderField: "Idempotency-Key"), exchangeKey)
        XCTAssertEqual(record.account.email, "person@example.com")
    }

    func testAccountDeletionUsesDeleteWithStableKeyAndExplicitAcknowledgement() async throws {
        let requestBox = LockedRequestBox()
        StubURLProtocol.handler = { request in
            requestBox.set(request, body: request.capturedBody())
            return (204, [:], Data())
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let key = "60000000-0000-4000-8000-000000000001"
        let grant = "hgg_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
        try await client.deleteAccount(
            grant: grant,
            acknowledgedPermanentCloudDeletion: true,
            accessToken: "hga_access",
            idempotencyKey: key
        )
        let captured = try XCTUnwrap(requestBox.value())
        let request = captured.0
        let body = try XCTUnwrap(captured.1)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(request.url?.path, "/v2/account")
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer hga_access")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), key)
        XCTAssertEqual(json["acknowledgedPermanentCloudDeletion"] as? Bool, true)
        XCTAssertEqual(json["grant"] as? String, grant)
    }

    func testRemoteMessageAndBearerNeverEnterDesktopDiagnostics() async throws {
        StubURLProtocol.handler = { _ in
            (401, ["Content-Type": "application/json"], Data(#"{"error":{"code":"HR-AUTH-004","message":"Bearer secret-token provider-proof","retryable":false,"recoveryAction":"sign_in","correlationId":"60000000-0000-4000-8000-000000000001"}}"#.utf8))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )

        do {
            _ = try await client.account(accessToken: "hga_secret-token")
            XCTFail("Expected a remote error")
        } catch let error as AccountClientError {
            let issue = DesktopIssue.account(error)
            XCTAssertEqual(issue.code, .accountSessionRevoked)
            XCTAssertTrue(issue.sanitizedDiagnostic.contains("60000000-0000-4000-8000-000000000001"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("secret-token"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("provider-proof"))
        }
    }

    func testOversizedAccountResponseFailsClosed() async throws {
        StubURLProtocol.handler = { _ in
            (200, ["Content-Type": "application/json"], Data(repeating: 0x20, count: 257))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession(),
            maximumResponseBytes: 256
        )

        do {
            _ = try await client.capabilities()
            XCTFail("Expected oversized response rejection")
        } catch let error as AccountClientError {
            XCTAssertEqual(error, .responseTooLarge)
        }
    }

    func testMultiDeviceDiscoveryAndDefaultSelectionUseExplicitDevicePaths() async throws {
        let requests = LockedRequestsBox()
        let deviceJSON = #"{"id":"40000000-0000-4000-8000-000000000001","generation":1,"deviceId":"hermes-office","desktopDisplayName":"Office Mac","publicKeyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","connector":{"online":true,"lastSeenAt":"2026-09-07T00:00:00Z"},"hermes":{"reachable":true,"version":"1.0.0"},"gateway":{"latencyMs":12},"endToEnd":{"healthy":true,"checkedAt":"2026-09-07T00:00:00Z"},"access":"owner","isDefault":true}"#
        StubURLProtocol.handler = { request in
            requests.append(request)
            if request.url?.path == "/v2/devices" {
                return (200, ["Content-Type": "application/json"], Data("{\"items\":[\(deviceJSON)],\"maxOwnedDevices\":3}".utf8))
            }
            return (200, ["Content-Type": "application/json"], Data("{\"device\":\(deviceJSON)}".utf8))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let key = "70000000-0000-4000-8000-000000000001"

        let page = try await client.devices(accessToken: "hga_secret")
        let selected = try await client.selectDefaultDevice(
            id: "hermes-office",
            accessToken: "hga_secret",
            idempotencyKey: key
        )
        let captured = requests.values

        XCTAssertEqual(page.maxOwnedDevices, 3)
        XCTAssertEqual(page.items.map(\.deviceId), ["hermes-office"])
        XCTAssertEqual(selected.deviceId, "hermes-office")
        XCTAssertEqual(captured.map { $0.url?.path }, [
            "/v2/devices",
            "/v2/devices/hermes-office/select-default",
        ])
        XCTAssertEqual(captured.last?.value(forHTTPHeaderField: "Idempotency-Key"), key)
        XCTAssertEqual(captured.last?.value(forHTTPHeaderField: "Authorization"), "Bearer hga_secret")
    }

    func testDeviceIdentifierCannotEscapeTheVersionedRoute() async throws {
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )

        do {
            _ = try await client.selectDefaultDevice(
                id: "../foreign",
                accessToken: "hga_secret",
                idempotencyKey: UUID().uuidString
            )
            XCTFail("Expected invalid device identifier rejection")
        } catch {
            XCTAssertEqual(error as? AccountClientError, .invalidResponse)
        }
    }

    func testPhoneRevocationSendsRecentAuthenticationGrantInBoundedDeleteBody() async throws {
        let requestBox = LockedRequestBox()
        StubURLProtocol.handler = { request in
            requestBox.set(request, body: request.capturedBody())
            return (204, ["Content-Type": "application/json"], Data())
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let installationID = "30000000-0000-4000-8000-000000000001"
        let idempotencyKey = "70000000-0000-4000-8000-000000000002"
        let grant = "hgg_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"

        try await client.revokePhone(
            id: installationID,
            grant: grant,
            accessToken: "hga_secret",
            idempotencyKey: idempotencyKey
        )

        let captured = try XCTUnwrap(requestBox.value())
        let body = try XCTUnwrap(captured.1)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(captured.0.url?.path, "/v2/installations/\(installationID)")
        XCTAssertEqual(captured.0.httpMethod, "DELETE")
        XCTAssertEqual(captured.0.value(forHTTPHeaderField: "Authorization"), "Bearer hga_secret")
        XCTAssertEqual(captured.0.value(forHTTPHeaderField: "Idempotency-Key"), idempotencyKey)
        XCTAssertEqual(json["grant"], grant)
    }

    func testWholeDeviceSharingUsesEmailReauthenticationAndExplicitDisclosure() async throws {
        let requests = LockedRequestsBox()
        let token = "hsi_" + String(repeating: "a", count: 43)
        let deviceJSON = #"{"id":"40000000-0000-4000-8000-000000000001","generation":1,"deviceId":"hermes-office","desktopDisplayName":"Office Mac","publicKeyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","connector":{"online":true},"hermes":{"reachable":true},"gateway":{},"endToEnd":{"healthy":true},"access":"operator","isDefault":false}"#
        let invitationJSON = #"{"id":"50000000-0000-4000-8000-000000000001","deviceId":"hermes-office","targetEmailHint":"g***@example.invalid","status":"pending","expiresAt":"2099-09-10T00:00:00Z","createdAt":"2099-09-07T00:00:00Z"}"#
        StubURLProtocol.handler = { request in
            requests.append(request)
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/v2/auth/reauth/email/challenges"):
                return (202, ["Content-Type": "application/json"], Data(#"{"challenge":{"challengeId":"30000000-0000-4000-8000-000000000001","expiresAt":"2099-09-07T00:10:00Z","resendAfter":"2099-09-07T00:01:00Z"}}"#.utf8))
            case ("POST", "/v2/auth/reauth/email"):
                return (200, ["Content-Type": "application/json"], Data(#"{"grant":"hgg_grant","scope":"device.share","expiresAt":"2099-09-07T00:10:00Z"}"#.utf8))
            case ("GET", "/v2/devices/hermes-office/shares"):
                return (200, ["Content-Type": "application/json"], Data(#"{"invitations":[],"grants":[],"maxGranteesPerDevice":5}"#.utf8))
            case ("POST", "/v2/devices/hermes-office/share-invitations"):
                return (202, ["Content-Type": "application/json"], Data("{\"invitation\":\(invitationJSON)}".utf8))
            case ("POST", "/v2/share-invitations/\(token)/accept"):
                return (200, ["Content-Type": "application/json"], Data("{\"device\":\(deviceJSON)}".utf8))
            default:
                return (204, [:], Data())
            }
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let keys = (0..<6).map { "70000000-0000-4000-8000-00000000000\($0)" }

        let challenge = try await client.requestEmailReauthenticationChallenge(
            email: "owner@example.invalid",
            accessToken: "hga_secret"
        )
        let reauthentication = try await client.reauthenticateEmail(
            challengeID: challenge.challengeId,
            email: "owner@example.invalid",
            code: "123456",
            scope: "device.share",
            accessToken: "hga_secret",
            idempotencyKey: keys[0]
        )
        let management = try await client.deviceShares(id: "hermes-office", accessToken: "hga_secret")
        let invitation = try await client.createShareInvitation(
            deviceID: "hermes-office",
            email: "guest@example.invalid",
            grant: reauthentication.grant,
            acknowledgedWholeDeviceAccess: true,
            accessToken: "hga_secret",
            idempotencyKey: keys[1]
        )
        try await client.cancelShareInvitation(
            deviceID: "hermes-office",
            invitationID: invitation.id,
            accessToken: "hga_secret",
            idempotencyKey: keys[2]
        )
        try await client.revokeDeviceShare(
            deviceID: "hermes-office",
            grantID: "60000000-0000-4000-8000-000000000001",
            accessToken: "hga_secret",
            idempotencyKey: keys[3]
        )
        try await client.leaveSharedDevice(
            deviceID: "hermes-office",
            accessToken: "hga_secret",
            idempotencyKey: keys[4]
        )
        let accepted = try await client.acceptShareInvitation(
            token: token,
            acknowledgedWholeDeviceAccess: true,
            accessToken: "hga_secret",
            idempotencyKey: keys[5]
        )

        XCTAssertEqual(management.maxGranteesPerDevice, 5)
        XCTAssertEqual(invitation.targetEmailHint, "g***@example.invalid")
        XCTAssertEqual(accepted.access, "operator")
        let captured = requests.values
        XCTAssertEqual(captured.map { $0.url?.path }, [
            "/v2/auth/reauth/email/challenges",
            "/v2/auth/reauth/email",
            "/v2/devices/hermes-office/shares",
            "/v2/devices/hermes-office/share-invitations",
            "/v2/devices/hermes-office/share-invitations/50000000-0000-4000-8000-000000000001",
            "/v2/devices/hermes-office/shares/60000000-0000-4000-8000-000000000001",
            "/v2/devices/hermes-office/leave",
            "/v2/share-invitations/\(token)/accept",
        ])
        let reauthenticationBody = try XCTUnwrap(captured[1].capturedBody())
        let reauthenticationJSON = try XCTUnwrap(
            JSONSerialization.jsonObject(with: reauthenticationBody) as? [String: Any]
        )
        XCTAssertEqual(reauthenticationJSON["email"] as? String, "owner@example.invalid")
        XCTAssertEqual(reauthenticationJSON["code"] as? String, "123456")
        let createBody = try XCTUnwrap(captured[3].capturedBody())
        let createJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: createBody) as? [String: Any])
        XCTAssertEqual(createJSON["acknowledgedWholeDeviceAccess"] as? Bool, true)
        XCTAssertEqual(createJSON["email"] as? String, "guest@example.invalid")
        XCTAssertEqual(captured[3].value(forHTTPHeaderField: "Authorization"), "Bearer hga_secret")
    }

    func testBindingCreationAndConfirmationUseMachineIdentityAndStableRetryHeaders() async throws {
        let requests = LockedRequestsBox()
        let fingerprint = String(repeating: "a", count: 64)
        StubURLProtocol.handler = { request in
            requests.append(request)
            if request.url?.path == "/v2/connector-binding" {
                return (201, ["Content-Type": "application/json"], Data("{\"id\":\"50000000-0000-4000-8000-000000000001\",\"generation\":1,\"deviceId\":\"hermes-pending\",\"displayName\":\"Office Mac\",\"publicKeyFingerprint\":\"\(fingerprint)\",\"state\":\"binding_pending\",\"expiresAt\":\"2099-09-07T00:10:00Z\",\"keyProved\":false,\"healthVerified\":false}".utf8))
            }
            return (200, ["Content-Type": "application/json"], Data("{\"state\":\"bound\",\"binding\":{\"id\":\"50000000-0000-4000-8000-000000000001\",\"generation\":1,\"deviceId\":\"hermes-pending\",\"desktopDisplayName\":\"Office Mac\",\"publicKeyFingerprint\":\"\(fingerprint)\",\"connector\":{\"online\":true},\"hermes\":{\"reachable\":true},\"gateway\":{},\"endToEnd\":{\"healthy\":true}}}".utf8))
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let createKey = "80000000-0000-4000-8000-000000000001"
        let confirmKey = "80000000-0000-4000-8000-000000000002"
        let publicKey = Data(repeating: 1, count: 32).testBase64URL

        let candidate = try await client.createBinding(
            desktopInstallationID: "20000000-0000-4000-8000-000000000001",
            displayName: "Office Mac",
            connectorPublicKey: publicKey,
            accessToken: "hga_secret",
            idempotencyKey: createKey
        )
        let confirmed = try await client.confirmBinding(
            bindingID: candidate.id!,
            generation: candidate.generation!,
            accessToken: "hga_secret",
            idempotencyKey: confirmKey
        )
        let captured = requests.values
        let createBody = try XCTUnwrap(captured[0].capturedBody())
        let createJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: createBody) as? [String: Any])

        XCTAssertEqual(candidate.publicKeyFingerprint, fingerprint)
        XCTAssertEqual(confirmed.state, "bound")
        XCTAssertEqual(captured.map { $0.url?.path }, [
            "/v2/connector-binding",
            "/v2/connector-binding/confirm",
        ])
        XCTAssertEqual(captured.map { $0.value(forHTTPHeaderField: "Idempotency-Key") }, [createKey, confirmKey])
        XCTAssertEqual(createJSON["desktopInstallationId"] as? String, "20000000-0000-4000-8000-000000000001")
        XCTAssertEqual(createJSON["connectorPublicKey"] as? String, publicKey)
        XCTAssertEqual(createJSON["keyAlgorithm"] as? String, "Ed25519")
    }

    private func makeStubSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class LockedRequestsBox: @unchecked Sendable {
    private let lock = NSLock()
    private var requests: [URLRequest] = []

    func append(_ request: URLRequest) {
        lock.withLock { requests.append(request) }
    }

    var values: [URLRequest] { lock.withLock { requests } }
}

private final class LockedRequestBox: @unchecked Sendable {
    private let lock = NSLock()
    private var request: URLRequest?
    private var body: Data?

    func set(_ request: URLRequest, body: Data?) {
        lock.withLock {
            self.request = request
            self.body = body
        }
    }

    func value() -> (URLRequest, Data?)? {
        lock.withLock { request.map { ($0, body) } }
    }
}

private final class StubURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (Int, [String: String], Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: AccountClientError.transport)
            return
        }
        do {
            let result = try handler(request)
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: result.0,
                httpVersion: "HTTP/1.1",
                headerFields: result.1
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: result.2)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

private extension URLRequest {
    func capturedBody() -> Data? {
        if let httpBody { return httpBody }
        guard let stream = httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private extension Data {
    var testBase64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

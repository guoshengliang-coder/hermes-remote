import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class AccountAPIClientTests: XCTestCase {
    override func tearDown() {
        StubURLProtocol.handler = nil
        super.tearDown()
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

    func testReauthenticationUsesScopedProofAndCallerPersistedKey() async throws {
        let requestBox = LockedRequestBox()
        StubURLProtocol.handler = { request in
            requestBox.set(request, body: request.capturedBody())
            return (
                200,
                ["Content-Type": "application/json"],
                Data(#"{"grant":"hgg_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ","scope":"connector.replace","expiresAt":"2026-09-03T01:10:00.000Z"}"#.utf8)
            )
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let key = "70000000-0000-4000-8000-000000000001"

        let grant = try await client.reauthenticateGoogle(
            GoogleIdentityProof(idToken: "provider-proof", nonce: "proof-nonce"),
            scope: .connectorReplace,
            accessToken: "hga_access",
            idempotencyKey: key
        )
        let captured = try XCTUnwrap(requestBox.value())
        let body = try XCTUnwrap(captured.1)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])

        XCTAssertEqual(captured.0.url?.path, "/v2/auth/reauth/google")
        XCTAssertEqual(captured.0.value(forHTTPHeaderField: "Authorization"), "Bearer hga_access")
        XCTAssertEqual(captured.0.value(forHTTPHeaderField: "Idempotency-Key"), key)
        XCTAssertEqual(json["idToken"], "provider-proof")
        XCTAssertEqual(json["nonce"], "proof-nonce")
        XCTAssertEqual(json["scope"], "connector.replace")
        XCTAssertEqual(grant.scope, .connectorReplace)
    }

    func testBindingLifecycleUsesPublishedPathsAndBodies() async throws {
        let history = LockedRequestHistory()
        StubURLProtocol.handler = { request in
            let body = request.capturedBody()
            history.append(request, body: body)
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/v2/connector-binding"):
                return (201, ["Content-Type": "application/json"], Self.candidateJSON)
            case ("POST", "/v2/connector-binding/confirm"):
                return (200, ["Content-Type": "application/json"], Self.boundJSON)
            case ("POST", "/v2/connector-binding/replacement-requests"):
                return (201, ["Content-Type": "application/json"], Self.replacementJSON)
            case ("POST", "/v2/connector-binding/replacement-requests/90000000-0000-4000-8000-000000000001/confirm"):
                return (200, ["Content-Type": "application/json"], Self.boundJSON)
            case ("DELETE", "/v2/connector-binding"):
                return (204, [:], Data())
            default:
                return (404, ["Content-Type": "application/json"], Data())
            }
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let input = ConnectorBindingInput(
            desktopInstallationID: "80000000-0000-4000-8000-000000000001",
            displayName: "Mac mini",
            connectorPublicKey: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        )

        _ = try await client.createBinding(
            input,
            accessToken: "hga_access",
            idempotencyKey: "70000000-0000-4000-8000-000000000002"
        )
        _ = try await client.confirmBinding(
            id: "40000000-0000-4000-8000-000000000001",
            generation: 1,
            accessToken: "hga_access",
            idempotencyKey: "70000000-0000-4000-8000-000000000003"
        )
        _ = try await client.createReplacement(
            input,
            grant: "hgg_replacement",
            accessToken: "hga_access",
            idempotencyKey: "70000000-0000-4000-8000-000000000004"
        )
        _ = try await client.confirmReplacement(
            requestID: "90000000-0000-4000-8000-000000000001",
            accessToken: "hga_access",
            idempotencyKey: "70000000-0000-4000-8000-000000000005"
        )
        try await client.unbind(
            grant: "hgg_unbind",
            accessToken: "hga_access",
            idempotencyKey: "70000000-0000-4000-8000-000000000006"
        )

        let requests = history.value()
        XCTAssertEqual(requests.map { $0.request.url?.path }, [
            "/v2/connector-binding",
            "/v2/connector-binding/confirm",
            "/v2/connector-binding/replacement-requests",
            "/v2/connector-binding/replacement-requests/90000000-0000-4000-8000-000000000001/confirm",
            "/v2/connector-binding",
        ])
        XCTAssertEqual(requests.map { $0.request.httpMethod }, ["POST", "POST", "POST", "POST", "DELETE"])
        XCTAssertTrue(requests.allSatisfy {
            $0.request.value(forHTTPHeaderField: "Authorization") == "Bearer hga_access"
        })
        let createBody = try jsonObject(requests[0].body)
        XCTAssertEqual(createBody["desktopInstallationId"] as? String, input.desktopInstallationID)
        XCTAssertEqual(createBody["connectorPublicKey"] as? String, input.connectorPublicKey)
        XCTAssertEqual(createBody["keyAlgorithm"] as? String, "Ed25519")
        XCTAssertNil(createBody["grant"])
        let confirmBody = try jsonObject(requests[1].body)
        XCTAssertEqual(confirmBody["bindingId"] as? String, "40000000-0000-4000-8000-000000000001")
        XCTAssertEqual(confirmBody["generation"] as? Int, 1)
        XCTAssertEqual(try jsonObject(requests[2].body)["grant"] as? String, "hgg_replacement")
        XCTAssertNil(requests[3].body)
        XCTAssertEqual(try jsonObject(requests[4].body)["grant"] as? String, "hgg_unbind")
    }

    func testReplacementConfirmationRejectsNonUUIDBeforeTransport() async throws {
        let history = LockedRequestHistory()
        StubURLProtocol.handler = { request in
            history.append(request, body: request.capturedBody())
            return (200, ["Content-Type": "application/json"], Self.boundJSON)
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )

        do {
            _ = try await client.confirmReplacement(
                requestID: "../../unexpected",
                accessToken: "hga_access",
                idempotencyKey: "70000000-0000-4000-8000-000000000007"
            )
            XCTFail("Expected invalid response rejection")
        } catch let error as AccountClientError {
            XCTAssertEqual(error, .invalidResponse)
        }
        XCTAssertTrue(history.value().isEmpty)
    }

    func testBindingConflictAndExpiredConfirmationRemainStructured() async throws {
        StubURLProtocol.handler = { request in
            let code = request.url?.path == "/v2/connector-binding"
                ? "HR-BIND-002"
                : "HR-BIND-003"
            return (
                code == "HR-BIND-002" ? 409 : 410,
                ["Content-Type": "application/json"],
                Data(#"{"error":{"code":"\#(code)","message":"sensitive server detail","retryable":false,"recoveryAction":"none","correlationId":"60000000-0000-4000-8000-000000000002"}}"#.utf8)
            )
        }
        let client = AccountAPIClient(
            gatewayURL: URL(string: "https://relay.example")!,
            session: makeStubSession()
        )
        let input = ConnectorBindingInput(
            desktopInstallationID: "80000000-0000-4000-8000-000000000001",
            displayName: "Mac mini",
            connectorPublicKey: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
        )

        do {
            _ = try await client.createBinding(
                input,
                accessToken: "hga_access",
                idempotencyKey: "70000000-0000-4000-8000-000000000008"
            )
            XCTFail("Expected binding conflict")
        } catch let error as AccountClientError {
            XCTAssertEqual(DesktopIssue.account(error).code, .bindingConflict)
        }
        do {
            _ = try await client.confirmBinding(
                id: "40000000-0000-4000-8000-000000000001",
                generation: 1,
                accessToken: "hga_access",
                idempotencyKey: "70000000-0000-4000-8000-000000000009"
            )
            XCTFail("Expected expired binding")
        } catch let error as AccountClientError {
            let issue = DesktopIssue.account(error)
            XCTAssertEqual(issue.code, .bindingExpired)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("sensitive server detail"))
        }
    }

    private func jsonObject(_ data: Data?) throws -> [String: Any] {
        let data = try XCTUnwrap(data)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private static let candidateJSON = Data(#"{"id":"40000000-0000-4000-8000-000000000001","generation":1,"deviceId":"hermes-40000000-0000-4000-8000-000000000001","displayName":"Mac mini","publicKeyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","state":"binding_pending","expiresAt":"2026-09-03T01:10:00.000Z","keyProved":false,"healthVerified":false}"#.utf8)

    private static let activeBindingJSON = #"{"id":"40000000-0000-4000-8000-000000000001","generation":1,"deviceId":"hermes-40000000-0000-4000-8000-000000000001","desktopDisplayName":"Mac mini","publicKeyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","connector":{"online":true,"lastSeenAt":"2026-09-03T01:00:00.000Z"},"hermes":{"reachable":true,"version":"0.0.0-test"},"gateway":{"latencyMs":12},"endToEnd":{"healthy":true,"checkedAt":"2026-09-03T01:00:00.000Z"}}"#

    private static let boundJSON = Data(#"{"state":"bound","binding":\#(activeBindingJSON)}"#.utf8)

    private static let replacementJSON = Data(#"{"id":"90000000-0000-4000-8000-000000000001","state":"replacement_pending","expiresAt":"2026-09-03T01:10:00.000Z","previousBinding":\#(activeBindingJSON),"candidate":{"id":"40000000-0000-4000-8000-000000000002","generation":2,"deviceId":"hermes-40000000-0000-4000-8000-000000000002","displayName":"Replacement Mac","publicKeyFingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","state":"binding_pending","expiresAt":"2026-09-03T01:10:00.000Z","keyProved":false,"healthVerified":false}}"#.utf8)

    private func makeStubSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }
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

private final class LockedRequestHistory: @unchecked Sendable {
    struct Entry {
        let request: URLRequest
        let body: Data?
    }

    private let lock = NSLock()
    private var entries: [Entry] = []

    func append(_ request: URLRequest, body: Data?) {
        lock.withLock { entries.append(Entry(request: request, body: body)) }
    }

    func value() -> [Entry] {
        lock.withLock { entries }
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

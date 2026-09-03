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

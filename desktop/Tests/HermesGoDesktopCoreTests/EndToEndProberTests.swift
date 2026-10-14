import Foundation
import XCTest
@testable import HermesGoDesktopCore

private final class URLProtocolStub: URLProtocol {
    static var responseStatus = 200
    static var responseBody = Data("{}".utf8)
    static var observedRequest: URLRequest?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.observedRequest = request
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: Self.responseStatus,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.responseBody)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class EndToEndProberTests: XCTestCase {
    func testLocalAuthenticatedProbeIsLoopbackOnlyAndRejectsPublicHTMLAndUnauthorized() async throws {
        let prober = HTTPHealthProber(session: session)
        let success = await prober.probeAuthenticatedHermes(sessionToken: "local-private-token")
        XCTAssertEqual(success.level, .healthy)
        XCTAssertEqual(URLProtocolStub.observedRequest?.url?.absoluteString, "http://127.0.0.1:9119/api/profiles/active")
        XCTAssertEqual(URLProtocolStub.observedRequest?.value(forHTTPHeaderField: "X-Hermes-Session-Token"), "local-private-token")
        for (status, body, reason) in [(200, "<html>login local-private-token</html>", "invalid-json"), (401, "local-private-token", "http-401")] {
            URLProtocolStub.responseStatus = status
            URLProtocolStub.responseBody = Data(body.utf8)
            let result = await prober.probeAuthenticatedHermes(sessionToken: "local-private-token")
            XCTAssertEqual(result.level, .failed)
            XCTAssertEqual(result.issue?.code, .hermesUnavailable)
            XCTAssertTrue(result.issue?.sanitizedDiagnostic.contains(reason) == true)
            XCTAssertFalse(result.issue?.sanitizedDiagnostic.contains("local-private-token") == true)
        }
    }

    func testLocalAuthenticatedProbeFallsBackOnlyForMissingRouteAndNeverFollowsARedirect() async throws {
        URLProtocolStub.responseStatus = 404
        _ = await HTTPHealthProber(session: session).probeAuthenticatedHermes(sessionToken: "token")
        XCTAssertEqual(URLProtocolStub.observedRequest?.url?.path, "/api/sessions")
        XCTAssertEqual(URLProtocolStub.observedRequest?.url?.query, "limit=1")
        let response = HTTPURLResponse(url: URL(string: "http://127.0.0.1:9119/api/profiles/active")!, statusCode: 302, httpVersion: nil, headerFields: nil)!
        var followed: URLRequest??
        LoopbackRedirectGuard().urlSession(session, task: session.dataTask(with: response.url!), willPerformHTTPRedirection: response,
            newRequest: URLRequest(url: URL(string: "https://foreign.example/collect")!)) { followed = .some($0) }
        XCTAssertNotNil(followed as Any?)
        XCTAssertNil(followed!)
    }
    private var session: URLSession!

    override func setUp() {
        super.setUp()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [URLProtocolStub.self]
        session = URLSession(configuration: configuration)
        URLProtocolStub.responseStatus = 200
        URLProtocolStub.responseBody = Data("{}".utf8)
        URLProtocolStub.observedRequest = nil
    }

    override func tearDown() {
        session.invalidateAndCancel()
        session = nil
        super.tearDown()
    }

    func testHealthyProbeUsesStatusPathAndHeaderToken() async throws {
        let profile = try ConnectionProfile.validated(
            name: "Mac",
            gatewayAddress: "https://relay.example/base",
            appToken: "secret-app-token"
        )

        let result = await HTTPHealthProber(session: session).probeEndToEnd(profile)

        XCTAssertEqual(result.level, .healthy)
        XCTAssertEqual(URLProtocolStub.observedRequest?.url?.absoluteString, "https://relay.example/base/api/status")
        XCTAssertEqual(
            URLProtocolStub.observedRequest?.value(forHTTPHeaderField: "X-Hermes-Session-Token"),
            "secret-app-token"
        )
        XCTAssertNil(result.issue)
    }

    func testUnauthorizedMapsToStableAuthErrorWithoutTokenLeak() async throws {
        URLProtocolStub.responseStatus = 401
        URLProtocolStub.responseBody = Data(#"{"error":"unauthorized"}"#.utf8)
        let profile = try ConnectionProfile.validated(
            name: "Mac",
            gatewayAddress: "https://relay.example",
            appToken: "secret-app-token"
        )

        let result = await HTTPHealthProber(session: session).probeEndToEnd(profile)

        XCTAssertEqual(result.level, .failed)
        XCTAssertEqual(result.issue?.code, .appTokenRejected)
        XCTAssertFalse(result.detail.contains("secret-app-token"))
        XCTAssertEqual(result.issue?.retryable, false)
    }

    func testOfflineConnectorMapsToExistingConnectionCode() async throws {
        URLProtocolStub.responseStatus = 503
        URLProtocolStub.responseBody = Data(#"{"error":"device_offline"}"#.utf8)
        let profile = try ConnectionProfile.validated(
            name: "Mac",
            gatewayAddress: "https://relay.example",
            appToken: "token"
        )

        let result = await HTTPHealthProber(session: session).probeEndToEnd(profile)

        XCTAssertEqual(result.issue?.code, .connectorOffline)
        XCTAssertEqual(result.issue?.recoveryAction, .startDesktop)
    }

    func testLocalHermesFailureMapsToHermesCodeForStructuredAndLegacyConnectors() async throws {
        let profile = try ConnectionProfile.validated(
            name: "Mac", gatewayAddress: "https://relay.example", appToken: "token"
        )
        URLProtocolStub.responseStatus = 502
        for body in [
            #"{"error":{"code":"HR-CONN-006","message":"Hermes service unavailable","correlationId":"12345678-1234-1234-1234-123456789abc"}}"#,
            #"{"error":"hermes_unreachable"}"#,
        ] {
            URLProtocolStub.responseBody = Data(body.utf8)
            let result = await HTTPHealthProber(session: session).probeEndToEnd(profile)
            XCTAssertEqual(result.issue?.code, .hermesUnavailable)
            XCTAssertTrue(result.issue?.retryable == true)
            XCTAssertFalse(result.detail.contains("12345678-1234"))
        }
    }
}

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
}

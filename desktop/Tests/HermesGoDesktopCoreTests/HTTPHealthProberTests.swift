import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class HTTPHealthProberTests: XCTestCase {
    func testCurrentHermesUsesOnlyTheLightweightUnauthenticatedRoute() async {
        let prober = makeProber(["/api/health": .http(200, body: #"{"ok":true,"version":"0.22.5"}"#)])

        let result = await prober.probeHermesLiveness()

        XCTAssertEqual(result.level, .healthy)
        XCTAssertNotNil(result.latencyMilliseconds)
        XCTAssertNil(result.issue)
        let requests = HealthProbeURLProtocol.requests
        XCTAssertEqual(requests.map { $0.url?.absoluteString }, ["http://127.0.0.1:9119/api/health"])
        XCTAssertEqual(requests.first?.httpMethod, "GET")
        XCTAssertEqual(requests.first?.cachePolicy, .reloadIgnoringLocalCacheData)
        XCTAssertNil(requests.first?.value(forHTTPHeaderField: "X-Hermes-Session-Token"))
    }

    func testMissingRouteFallsBackWithRemainingTimeAndMeasuresBothRequests() async throws {
        for status in [404, 405] {
            let prober = makeProber([
                "/api/health": .http(status, delay: 0.03),
                "/api/status": .http(200),
            ])

            let result = await prober.probeHermesLiveness()

            XCTAssertEqual(result.level, .healthy)
            XCTAssertGreaterThanOrEqual(try XCTUnwrap(result.latencyMilliseconds), 25)
            let requests = HealthProbeURLProtocol.requests
            XCTAssertEqual(requests.map { $0.url?.path }, ["/api/health", "/api/status"])
            XCTAssertEqual(requests.first?.timeoutInterval, 5)
            XCTAssertLessThan(try XCTUnwrap(requests.last?.timeoutInterval), 4.98)
        }
    }

    func testLegacyAuthGateStillIndicatesReachability() async {
        let prober = makeProber(["/api/health": .http(404), "/api/status": .http(401)])

        let result = await prober.probeHermesLiveness()

        XCTAssertEqual(result.level, .degraded)
        XCTAssertEqual(HealthProbeURLProtocol.requests.map { $0.url?.path }, ["/api/health", "/api/status"])
    }

    func testServerFailureAndTimeoutDoNotFallback() async {
        for (reply, reason) in [(HealthProbeURLProtocol.Reply.http(503, body: "fixture-private-body"), "http-503"),
                                (.timeout, "timeout")] {
            let prober = makeProber(["/api/health": reply])

            let result = await prober.probeHermesLiveness()

            XCTAssertEqual(HealthProbeURLProtocol.requests.map { $0.url?.path }, ["/api/health"])
            assertUnavailable(result, reason: reason)
        }
    }

    func testMalformedOrUnhealthySuccessBodyDoesNotFallback() async {
        for body in ["fixture-private-body", #"{"ok":false}"#, #"{"ok":1}"#] {
            let prober = makeProber(["/api/health": .http(200, body: body)])

            let result = await prober.probeHermesLiveness()

            XCTAssertEqual(HealthProbeURLProtocol.requests.map { $0.url?.path }, ["/api/health"])
            assertUnavailable(result, reason: "invalid-health-response")
        }
    }

    func testHealthyLivenessDoesNotReplaceTheManagedAuthenticationCheck() async {
        let prober = makeProber([
            "/api/health": .http(200, body: #"{"ok":true}"#),
            "/api/profiles/active": .http(401),
        ])

        let liveness = await prober.probeHermesLiveness()
        let auth = await prober.probeAuthenticatedHermes(sessionToken: "fixture-token")

        XCTAssertEqual(liveness.level, .healthy)
        XCTAssertEqual(auth.level, .failed)
        XCTAssertEqual(auth.issue?.code, .hermesUnavailable)
        XCTAssertEqual(auth.issue?.technicalCause, "stage=local-hermes-auth result=http-401")
        let requests = HealthProbeURLProtocol.requests
        XCTAssertEqual(requests.map { $0.url?.path }, ["/api/health", "/api/profiles/active"])
        XCTAssertNil(requests.first?.value(forHTTPHeaderField: "X-Hermes-Session-Token"))
        XCTAssertEqual(requests.last?.value(forHTTPHeaderField: "X-Hermes-Session-Token"), "fixture-token")
    }

    private func makeProber(_ replies: [String: HealthProbeURLProtocol.Reply]) -> HTTPHealthProber {
        HealthProbeURLProtocol.reset(replies)
        let configuration = HTTPHealthProber.loopbackDirectConfiguration()
        configuration.protocolClasses = [HealthProbeURLProtocol.self]
        return HTTPHealthProber(session: URLSession(configuration: configuration))
    }

    private func assertUnavailable(_ result: ProbeResult, reason: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(result.level, .failed, file: file, line: line)
        XCTAssertEqual(result.issue?.code, .hermesUnavailable, file: file, line: line)
        XCTAssertTrue(result.detail.contains("Hermes 当前不可访问"), file: file, line: line)
        XCTAssertTrue(result.issue?.displayEnglish.contains("Hermes is unavailable") == true, file: file, line: line)
        XCTAssertEqual(result.issue?.retryable, true, file: file, line: line)
        XCTAssertEqual(result.issue?.technicalCause, "stage=local-hermes-liveness result=\(reason)", file: file, line: line)
        XCTAssertFalse(result.issue?.sanitizedDiagnostic.contains("fixture-private-body") ?? true, file: file, line: line)
    }
}

private final class HealthProbeURLProtocol: URLProtocol, @unchecked Sendable {
    enum Reply {
        case http(Int, body: String = "", delay: TimeInterval = 0)
        case timeout
    }

    private static let lock = NSLock()
    private static var recorded: [URLRequest] = []
    private static var replies: [String: Reply] = [:]

    static var requests: [URLRequest] {
        lock.withLock { recorded }
    }

    static func reset(_ responses: [String: Reply]) {
        lock.withLock {
            recorded.removeAll()
            replies = responses
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let reply = Self.lock.withLock {
            Self.recorded.append(request)
            return Self.replies[url.path]
        }
        switch reply {
        case let .http(status, body, delay):
            Thread.sleep(forTimeInterval: delay)
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        case .timeout:
            client?.urlProtocol(self, didFailWithError: URLError(.timedOut))
        case nil:
            XCTFail("Unexpected probe request to \(url.path)")
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
        }
    }

    override func stopLoading() {}
}

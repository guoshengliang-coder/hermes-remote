import Foundation
import CFNetwork
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesReadinessTests: XCTestCase {
    override func tearDown() {
        HermesReadinessURLProtocol.statusCode = 200
        super.tearDown()
    }

    func testCandidateLoopbackSessionDoesNotInheritTheSystemProxy() {
        let configuration = HTTPHealthProber.loopbackDirectConfiguration()
        let proxy = configuration.connectionProxyDictionary
        XCTAssertEqual(proxy?[kCFNetworkProxiesHTTPEnable as String] as? Bool, false)
        XCTAssertEqual(proxy?[kCFNetworkProxiesHTTPSEnable as String] as? Bool, false)
        XCTAssertEqual(proxy?[kCFNetworkProxiesSOCKSEnable as String] as? Bool, false)
        XCTAssertEqual(proxy?[kCFNetworkProxiesFTPEnable as String] as? Bool, false)
        XCTAssertEqual(proxy?[kCFNetworkProxiesProxyAutoConfigEnable as String] as? Bool, false)
        XCTAssertEqual(proxy?[kCFNetworkProxiesProxyAutoDiscoveryEnable as String] as? Bool, false)
    }

    func testRequiresANewExactMarkerAndHealthyLoopbackResponseTogether() async throws {
        let fixture = try makeFixture(initialLog: "HERMES_BACKEND_READY port=9119\n")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(
            session: makeSession()
        ))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)

        let staleOnly = try await checker.waitUntilReady(
            checkpoint: checkpoint,
            contract: .serveV1,
            maximumAttempts: 1,
            delayNanoseconds: 0
        )
        XCTAssertFalse(staleOnly)

        let handle = try FileHandle(forWritingTo: fixture.log)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("HERMES_BACKEND_READY port=9119\n".utf8))
        try handle.close()

        let ready = try await checker.waitUntilReady(
            checkpoint: checkpoint,
            contract: .serveV1,
            maximumAttempts: 1,
            delayNanoseconds: 0
        )
        XCTAssertTrue(ready)
    }

    func testWrongMarkerOrUnhealthyHTTPNeverPasses() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(
            session: makeSession()
        ))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        try Data("HERMES_BACKEND_READY port=9120\n".utf8).write(to: fixture.log)
        let wrongMarker = try await checker.waitUntilReady(
            checkpoint: checkpoint,
            contract: .serveV1,
            maximumAttempts: 1,
            delayNanoseconds: 0
        )
        XCTAssertFalse(wrongMarker)

        let healthyMarkerCheckpoint = try checker.checkpoint(logURL: fixture.log)
        let handle = try FileHandle(forWritingTo: fixture.log)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("HERMES_BACKEND_READY port=9119\n".utf8))
        try handle.close()
        HermesReadinessURLProtocol.statusCode = 503
        let unhealthy = try await checker.waitUntilReady(
            checkpoint: healthyMarkerCheckpoint,
            contract: .serveV1,
            maximumAttempts: 1,
            delayNanoseconds: 0
        )
        XCTAssertFalse(unhealthy)
    }

    func testFreshPythonStartupFailureCarriesTheCauseInsteadOfTimingOut() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(session: makeSession()))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        let stderr = fixture.root.appendingPathComponent("hermes-server.error.log")
        try Data("""
        File "/Users/customer/.hermes/hermes-agent/agent/turn_scripted_prelude.py"
        APP_TOKEN=secret-not-for-diagnostics
        TypeError: Too few arguments for typing.Generator; actual 2, expected 3
        ERROR:    Application startup failed. Exiting.

        """.utf8).write(to: stderr)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )) { error in
            let issue = DesktopIssue.hermesRuntimeFailure(error)
            XCTAssertEqual(issue.code, .localHermesRuntimeFailed)
            XCTAssertTrue(issue.retryable)
            XCTAssertEqual(issue.recoveryAction, .details)
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertTrue(issue.sanitizedDiagnostic.contains("python-generator-parameters"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("customer"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("secret-not-for-diagnostics"))
        }
    }

    func testHistoricalStartupFailureAndNonFatalUpdateWarningDoNotPoisonFreshReadiness() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let stderr = fixture.root.appendingPathComponent("hermes-server.error.log")
        try Data("ERROR:    Application startup failed. Exiting.\n".utf8).write(to: stderr)
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(session: makeSession()))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        try append("dependency lock still held after 10.0s; continuing without it\n", to: stderr)
        try append("HERMES_BACKEND_READY port=9119\n", to: fixture.log)
        let ready = try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )
        XCTAssertTrue(ready)
    }

    func testFreshPortConflictIsDistinctFromPythonStartupFailure() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(session: makeSession()))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        try append("BACKEND_PORT_IN_USE port=9119\n", to: fixture.log)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )) { error in
            XCTAssertTrue(String(describing: error).contains("port-in-use"))
            XCTAssertFalse(String(describing: error).contains("python-generator-parameters"))
        }
    }

    func testIncompleteFatalLineWaitsAndGenericFatalFailureKeepsNoRawException() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(session: makeSession()))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        let stderr = fixture.root.appendingPathComponent("hermes-server.error.log")
        try Data("ImportError: private-model-name\nERROR:    Application startup failed.".utf8).write(to: stderr)
        let waiting = try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )
        XCTAssertFalse(waiting)
        try append(" Exiting.\n", to: stderr)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .startupFailed(.applicationStartupFailed))
            XCTAssertFalse(String(describing: error).contains("private-model-name"))
        }
    }

    func testErrorLogSymlinkAndExcessiveGrowthAreRefused() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let stderr = fixture.root.appendingPathComponent("hermes-server.error.log")
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(session: makeSession()))
        try FileManager.default.createSymbolicLink(at: stderr, withDestinationURL: fixture.log)
        XCTAssertThrowsError(try checker.checkpoint(logURL: fixture.log)) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .invalidLog)
        }
        try FileManager.default.removeItem(at: stderr)
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        // Also refuse a symlink introduced after the checkpoint.
        try FileManager.default.createSymbolicLink(at: stderr, withDestinationURL: fixture.log)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .invalidLog)
        }
        try FileManager.default.removeItem(at: stderr)
        try Data(repeating: 0x41, count: 64 * 1024 + 1).write(to: stderr)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .logGrowthExceeded)
        }
    }

    func testRotatedErrorLogIsReadFromItsNewBeginning() async throws {
        let fixture = try makeFixture(initialLog: "")
        defer { try? FileManager.default.removeItem(at: fixture.root) }
        let stderr = fixture.root.appendingPathComponent("hermes-server.error.log")
        try Data(repeating: 0x41, count: 1024).write(to: stderr)
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(session: makeSession()))
        let checkpoint = try checker.checkpoint(logURL: fixture.log)
        try FileManager.default.moveItem(at: stderr, to: fixture.root.appendingPathComponent("previous-error.log"))
        try Data("ERROR:    Application startup failed. Exiting.\n".utf8).write(to: stderr)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint, contract: .serveV1, maximumAttempts: 1, delayNanoseconds: 0
        )) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .startupFailed(.applicationStartupFailed))
        }
    }

    private func append(_ text: String, to url: URL) throws {
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: Data(text.utf8))
    }

    func testSymlinkAndUnboundedNewLogDataFailClosed() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-readiness-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let symlink = root.appendingPathComponent("server.log")
        try FileManager.default.createSymbolicLink(
            at: symlink,
            withDestinationURL: root.appendingPathComponent("missing.log")
        )
        let checker = DesktopHermesCandidateReadinessChecker(prober: HTTPHealthProber(
            session: makeSession()
        ))
        XCTAssertThrowsError(try checker.checkpoint(logURL: symlink)) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .invalidLog)
        }

        try FileManager.default.removeItem(at: symlink)
        try Data().write(to: symlink)
        let checkpoint = try checker.checkpoint(logURL: symlink)
        try Data(repeating: 0x41, count: 64 * 1024 + 1).write(to: symlink)
        await XCTAssertThrowsErrorAsync(try await checker.waitUntilReady(
            checkpoint: checkpoint,
            contract: .serveV1,
            maximumAttempts: 1,
            delayNanoseconds: 0
        )) { error in
            XCTAssertEqual(error as? DesktopHermesReadinessError, .logGrowthExceeded)
        }
    }

    private func makeFixture(initialLog: String) throws -> (root: URL, log: URL) {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-readiness-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let log = root.appendingPathComponent("hermes-server.log")
        try Data(initialLog.utf8).write(to: log)
        return (root, log)
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HermesReadinessURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class HermesReadinessURLProtocol: URLProtocol, @unchecked Sendable {
    static var statusCode = 200

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: Self.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: [:]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ errorHandler: (Error) -> Void = { _ in },
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected error", file: file, line: line)
    } catch {
        errorHandler(error)
    }
}

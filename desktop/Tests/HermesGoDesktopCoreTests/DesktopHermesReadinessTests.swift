import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesReadinessTests: XCTestCase {
    override func tearDown() {
        HermesReadinessURLProtocol.statusCode = 200
        super.tearDown()
    }

    func testCandidateLoopbackSessionDoesNotInheritTheSystemProxy() {
        let configuration = HTTPHealthProber.loopbackDirectConfiguration()
        XCTAssertNotNil(configuration.connectionProxyDictionary)
        XCTAssertEqual(configuration.connectionProxyDictionary?.count, 0)
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
        let log = root.appendingPathComponent("server.log")
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

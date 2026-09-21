import Darwin
import Foundation
import Network
import XCTest
@testable import HermesGoDesktopCore

/// The loopback shutdown proof. Before 2026-09-21 it treated only `.failed` as "gone", but on macOS
/// a connection to a free loopback port reports `.waiting(ECONNREFUSED)` and stays there, so every
/// wait timed out once the old Hermes had exited (`docs/DESKTOP_E4_TEST_RECORD.md`).
final class DesktopHermesShutdownTests: XCTestCase {
    func testARefusalProvesTheListenerIsGone() {
        XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.waiting(.posix(.ECONNREFUSED))), .notListening)
        XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.failed(.posix(.ECONNREFUSED))), .notListening)
    }

    func testAnAcceptedConnectionIsAListener() {
        XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.ready), .listening)
    }

    func testAnythingElseStaysUndecided() {
        XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.setup), .undecided)
        XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.preparing), .undecided)
        XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.cancelled), .undecided)
        for code: POSIXErrorCode in [.ENETUNREACH, .EHOSTUNREACH, .ETIMEDOUT, .ECONNRESET, .EMFILE, .EADDRNOTAVAIL] {
            XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.waiting(.posix(code))), .undecided, "\(code)")
            XCTAssertEqual(DesktopLoopbackProbeDecision.decide(.failed(.posix(code))), .undecided, "\(code)")
        }
    }

    /// The real thing, on an ephemeral loopback port nobody listens on: it must be decided at once,
    /// not after the full wait (which is what made the 2026-09-21 switch take ~112 s per wait).
    func testAFreeLoopbackPortIsProvedStoppedQuickly() async throws {
        let port = try Self.freeLoopbackPort()
        let checker = DesktopHermesShutdownChecker(connectionTimeoutNanoseconds: 2_000_000_000, probePort: port)
        let started = Date()

        let stopped = try await checker.waitUntilStopped(
            contract: .serveV1,
            maximumAttempts: 5,
            delayNanoseconds: 1_000_000_000
        )

        XCTAssertTrue(stopped)
        XCTAssertLessThan(Date().timeIntervalSince(started), 1.5, "one probe, decided by the refusal")
    }

    func testALiveLoopbackListenerIsNotStopped() async throws {
        let listener = try LoopbackListener()
        defer { listener.close() }
        let checker = DesktopHermesShutdownChecker(connectionTimeoutNanoseconds: 1_000_000_000, probePort: listener.port)

        let stopped = try await checker.waitUntilStopped(
            contract: .serveV1,
            maximumAttempts: 2,
            delayNanoseconds: 10_000_000
        )

        XCTAssertFalse(stopped)
    }

    func testTheWaitIsLogged() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-shutdown-log-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let log = DesktopServiceOperationLog(url: directory.appendingPathComponent("desktop-runtime.log"))
        let checker = DesktopHermesShutdownChecker(
            connectionTimeoutNanoseconds: 1_000_000_000,
            probePort: try Self.freeLoopbackPort(),
            log: log
        )

        _ = try await checker.waitUntilStopped(contract: .serveV1, maximumAttempts: 2, delayNanoseconds: 0)

        let text = try String(contentsOf: log.url, encoding: .utf8)
        XCTAssertTrue(text.contains("wait-stopped result=stopped attempts=1"), text)
    }

    func testAttemptsOutsideTheBoundAreRefused() async throws {
        let checker = DesktopHermesShutdownChecker(connectionTimeoutNanoseconds: 1_000_000, probePort: 1)
        let refused = try await checker.waitUntilStopped(contract: .serveV1, maximumAttempts: 0, delayNanoseconds: 0)
        XCTAssertFalse(refused, "attempts outside 1...300 are refused, never read as stopped")
    }

    /// Binds port 0 on loopback, reads the port the kernel chose, and closes it again.
    static func freeLoopbackPort() throws -> UInt16 {
        let listener = try LoopbackListener(listen: false)
        let port = listener.port
        listener.close()
        return port
    }
}

/// A bare BSD socket bound to 127.0.0.1 on an ephemeral port. With `listen`, the kernel completes
/// connections into the backlog without any accept, which is all the probe needs.
private final class LoopbackListener {
    private var descriptor: Int32
    let port: UInt16

    init(listen shouldListen: Bool = true) throws {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { Darwin.close(fd); throw POSIXError(.EADDRINUSE) }
        if shouldListen { guard listen(fd, 8) == 0 else { Darwin.close(fd); throw POSIXError(.EIO) } }
        var chosen = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &chosen) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
        }
        guard named == 0 else { Darwin.close(fd); throw POSIXError(.EIO) }
        descriptor = fd
        port = UInt16(bigEndian: chosen.sin_port)
    }

    func close() {
        guard descriptor >= 0 else { return }
        Darwin.close(descriptor)
        descriptor = -1
    }
}

final class DesktopServiceOperationLogTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-operation-log-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testLinesArePrivateRedactedAndSingleLine() throws {
        let log = DesktopServiceOperationLog(url: directory.appendingPathComponent("desktop-runtime.log"))

        log.record("launchctl bootstrap gui/501 /Users/guoshengliang/Library/LaunchAgents/x.plist status=5\nstderr line two")
        log.record("Authorization: Bearer s3cret")

        let text = try String(contentsOf: log.url, encoding: .utf8)
        XCTAssertFalse(text.contains("guoshengliang"))
        XCTAssertTrue(text.contains("/Users/<user>/Library/LaunchAgents/x.plist status=5 | stderr line two"))
        XCTAssertFalse(text.contains("s3cret"))
        XCTAssertEqual(text.split(separator: "\n").count, 2)
        let attributes = try FileManager.default.attributesOfItem(atPath: log.url.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
    }

    func testTheLogIsBoundedByOneRotation() throws {
        let log = DesktopServiceOperationLog(url: directory.appendingPathComponent("desktop-runtime.log"), maximumBytes: 2048)

        for index in 0..<200 { log.record("line \(index) " + String(repeating: "x", count: 40)) }

        let current = try FileManager.default.attributesOfItem(atPath: log.url.path)[.size] as? NSNumber
        let rotated = try FileManager.default.attributesOfItem(atPath: log.url.path + ".1")[.size] as? NSNumber
        XCTAssertLessThanOrEqual(current?.intValue ?? .max, 2048)
        XCTAssertLessThanOrEqual(rotated?.intValue ?? .max, 2048)
        XCTAssertTrue(try String(contentsOf: log.url, encoding: .utf8).contains("line 199"))
    }

    func testNothingIsCreatedWithoutTheManagedLogsDirectory() {
        let log = DesktopServiceOperationLog(url: directory.appendingPathComponent("missing/desktop-runtime.log"))
        log.record("x")
        XCTAssertFalse(FileManager.default.fileExists(atPath: log.url.path))
    }

    func testASymlinkIsNeverFollowed() throws {
        let target = directory.appendingPathComponent("elsewhere")
        try Data().write(to: target)
        let url = directory.appendingPathComponent("desktop-runtime.log")
        try FileManager.default.createSymbolicLink(at: url, withDestinationURL: target)

        DesktopServiceOperationLog(url: url).record("x")

        XCTAssertEqual(try Data(contentsOf: target).count, 0)
    }

    func testTheSystemRunnerKeepsStandardErrorOnlyWhenAsked() {
        let shell = URL(fileURLWithPath: "/bin/sh")
        let arguments = ["-c", "echo 'Bootstrap failed: 5' >&2; exit 5"]

        let kept = SystemCommandRunner(capturesStandardError: true).run(executable: shell, arguments: arguments)
        XCTAssertEqual(kept.status, 5)
        XCTAssertEqual(kept.standardError, "Bootstrap failed: 5")

        let discarded = SystemCommandRunner().run(executable: shell, arguments: arguments)
        XCTAssertEqual(discarded.status, 5)
        XCTAssertNil(discarded.standardError)
    }

    func testTheSystemRunnerBoundsStandardError() {
        let result = SystemCommandRunner(capturesStandardError: true).run(
            executable: URL(fileURLWithPath: "/bin/sh"),
            arguments: ["-c", "head -c 100000 /dev/zero | tr '\\0' 'e' >&2; exit 0"]
        )
        XCTAssertEqual(result.status, 0)
        XCTAssertEqual(result.standardError?.utf8.count, SystemCommandRunner.maximumStandardErrorBytes)
    }
}

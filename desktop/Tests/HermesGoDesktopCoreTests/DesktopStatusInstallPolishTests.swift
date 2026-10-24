import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopStatusInstallPolishTests: XCTestCase {
    func testEmailFallbackDoesNotOverwriteAnActualNickname() {
        XCTAssertEqual(DesktopAccountDisplay.name(displayName: "Alice", email: "long.name@example.com"), "Alice")
        XCTAssertEqual(DesktopAccountDisplay.name(displayName: "long.name@example.com", email: "long.name@example.com"), "long.name")
        XCTAssertEqual(DesktopAccountDisplay.name(displayName: "  ", email: " owner+mac@example.com "), "owner+mac")
        XCTAssertEqual(DesktopAccountDisplay.name(displayName: nil, email: nil), "我的账号")
        XCTAssertEqual(DesktopAccountDisplay.name(displayName: "用户名字", email: nil), "用户名字")
    }

    func testStaleAndFailedLocalEvidenceCannotPaintModelGreen() {
        let now = Date()
        let selected = DesktopModelConfiguration.selected("fixture")
        XCTAssertEqual(selected.current(health: nil, now: now), .unknown)
        for (level, age) in [(HealthLevel.failed, 0.0), (.healthy, 181), (.healthy, -10)] {
            XCTAssertEqual(selected.current(health: ComponentHealth(component: .hermes, level: level, detail: "fixture", checkedAt: now.addingTimeInterval(-age)), now: now), .unknown)
        }
        let fresh = ComponentHealth(component: .hermes, level: .healthy, detail: "fixture", checkedAt: now)
        XCTAssertEqual(selected.current(health: fresh, now: now), selected)
        XCTAssertEqual(DesktopModelConfiguration.missing.current(health: fresh, now: now), .missing)
    }

    func testRetrySucceedsAfterATemporaryFailureAndStopsAtThreeAttempts() async throws {
        var attempts = 0
        var announced: [Int] = []
        let result: Int = try await DesktopInstallRetry.run(sleep: { _ in }, onRetry: { attempt, _ in announced.append(attempt) }) {
            attempts += 1
            if attempts == 1 { throw DesktopHermesInstallFailure.network(stage: "download", detail: "HTTP 503") }
            return 42
        }
        XCTAssertEqual(result, 42)
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(announced, [2])
        attempts = 0
        do {
            let _: Int = try await DesktopInstallRetry.run(sleep: { _ in }, onRetry: { _, _ in }) {
                attempts += 1
                throw DesktopHermesInstallFailure.network(stage: "download", detail: "URLError -1009")
            }
            XCTFail("must fail after retry cap")
        } catch { XCTAssertEqual(attempts, 3) }
    }

    func testPermanentAndSecurityFailuresNeverRetry() async {
        let errors: [DesktopHermesInstallFailure] = [
            .network(stage: "download", detail: "HTTP 404"), .network(stage: "download", detail: "HTTP 401"),
            .network(stage: "download", detail: "URLError -1202"), .protocolMismatch(detail: "checksum"),
            .stageFailed(stage: "config", detail: "permission denied"), .cancelled
        ]
        for failure in errors {
            var attempts = 0
            do {
                let _: Int = try await DesktopInstallRetry.run(sleep: { _ in XCTFail("must not wait") }, onRetry: { _, _ in XCTFail("must not retry") }) {
                    attempts += 1; throw failure
                }
                XCTFail("expected error")
            } catch { XCTAssertEqual(attempts, 1) }
        }
    }

    func testCancelDuringBackoffNeverStartsAnotherAttempt() async {
        var attempts = 0
        do {
            let _: Int = try await DesktopInstallRetry.run(sleep: { _ in throw CancellationError() }, onRetry: { _, _ in }) {
                attempts += 1
                throw DesktopHermesInstallFailure.network(stage: "repository", detail: "Could not resolve host")
            }
            XCTFail("expected cancellation")
        } catch { XCTAssertTrue(error is CancellationError); XCTAssertEqual(attempts, 1) }
    }

    func testDownloadProgressDoesNotInventASizeOrSpeed() async throws {
        var reports: [Int] = []
        let stream = AsyncStream<UInt8> { c in for b in [UInt8(1), 2, 3] { c.yield(b) }; c.finish() }
        let data = try await DesktopHermesInstallerURLSessionFetcher.collect(stream, limit: 3) { count, finished in
            if finished { reports.append(count) }
        }
        XCTAssertEqual(data.count, 3)
        XCTAssertEqual(reports, [3])
        let unknown = DesktopInstallerDownloadProgress(receivedBytes: 100, totalBytes: nil, elapsed: 0.1)
        XCTAssertNil(unknown.totalBytes)
        XCTAssertNil(unknown.bytesPerSecond)
        let known = DesktopInstallerDownloadProgress(receivedBytes: 100, totalBytes: 200, elapsed: 2)
        XCTAssertEqual(known.bytesPerSecond, 50)
        XCTAssertNil(DesktopInstallerDownloadProgress(receivedBytes: 100, totalBytes: 50, elapsed: 1).totalBytes)
        var run = DesktopHermesInstallRun()
        run.apply(.downloading)
        run.apply(.downloadProgress(known))
        run.apply(.retrying(stage: "下载", attempt: 2, delaySeconds: 2))
        XCTAssertNotNil(run.retryMessage)
        run.stop()
        XCTAssertNil(run.retryMessage)
    }
}

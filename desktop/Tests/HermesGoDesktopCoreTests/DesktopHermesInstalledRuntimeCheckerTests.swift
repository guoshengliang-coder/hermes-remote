import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesInstalledRuntimeCheckerTests: XCTestCase {
    func testVerificationLoadsTheSelectedCommandWithoutStartingAServer() async throws {
        let f = try Fixture(script: "printf '%s\\n' \"$@\" > args\nexit 0")
        defer { f.remove() }
        try await DesktopHermesInstalledRuntimeChecker().verify(f.installation, environment: f.environment)
        let args = try String(contentsOf: f.root.appendingPathComponent("args"), encoding: .utf8)
        XCTAssertEqual(args, "serve\n--help\n")
    }

    func testImportFailureDoesNotReportSuccessOrExposeTheOutput() async throws {
        let f = try Fixture(script: "echo 'APP_TOKEN=private-customer-token /Users/customer/secret' >&2\nexit 1")
        defer { f.remove() }
        do {
            try await DesktopHermesInstalledRuntimeChecker().verify(f.installation, environment: f.environment)
            XCTFail("command failure must fail verification")
        } catch let failure as DesktopHermesInstallFailure {
            XCTAssertEqual(failure, .notUsable(stage: "verify", detail: "serve-help exit=1"))
            let issue = try XCTUnwrap(DesktopIssue.hermesInstall(failure))
            XCTAssertEqual(issue.code, .hermesInstallNotUsable)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("private-customer-token"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("customer"))
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
        }
    }

    func testHungRuntimeIsBoundedAndItsProcessGroupIsReaped() async throws {
        let f = try Fixture(script: "sleep 60 &\nwait")
        defer { f.remove() }
        do {
            try await DesktopHermesInstalledRuntimeChecker(timeoutNanoseconds: 100_000_000)
                .verify(f.installation, environment: f.environment)
            XCTFail("a hung launcher cannot pass")
        } catch let failure as DesktopHermesInstallFailure {
            XCTAssertEqual(failure, .notUsable(stage: "verify", detail: "serve-help timed-out"))
        }
        XCTAssertTrue(DesktopPosixProcessRunner.liveProcessGroups.isEmpty)
    }

    private struct Fixture {
        let root: URL
        let installation: DesktopLocalHermesInstallation
        var environment: [String: String] { ["HOME": root.path, "HERMES_HOME": root.path, "PATH": "/usr/bin:/bin"] }

        init(script: String) throws {
            root = FileManager.default.temporaryDirectory.appendingPathComponent("hg212-runtime-\(UUID().uuidString)").resolvingSymlinksInPath()
            let executable = root.appendingPathComponent(".hermes/bin/hermes")
            try FileManager.default.createDirectory(at: executable.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("#!/bin/sh\n\(script)\n".utf8).write(to: executable)
            try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: executable.path)
            installation = DesktopLocalHermesInstallation(executable: executable, checkoutRoot: root,
                hermesHome: root, commit: String(repeating: "a", count: 40), version: "0.21.6",
                identityChangedAt: Date(), installedDistribution: .packageManaged)
        }
        func remove() { try? FileManager.default.removeItem(at: root) }
    }
}

final class DesktopDiagnosticRefreshWiringTests: XCTestCase {
    /// The SwiftUI executable cannot be imported into this core test target. Assert the critical
    /// call sites so a correct core projection cannot mask a button that never refreshes Cloud.
    func testBothDiagnosticActionsRefreshAccountAndLocalEvidence() throws {
        let app = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Sources/HermesGoDesktop")
        let view = try String(contentsOf: app.appendingPathComponent("DiagnosticsView.swift"), encoding: .utf8)
        XCTAssertEqual(view.components(separatedBy: "await model.refreshDiagnostics()").count - 1, 2)
        XCTAssertFalse(view.contains("await model.refresh()"))
        XCTAssertTrue(view.contains("这台 Mac 的连接检查"))
        XCTAssertTrue(view.contains("本机 Hermes 的云端上报"))
        let model = try String(contentsOf: app.appendingPathComponent("DesktopViewModel.swift"), encoding: .utf8)
        XCTAssertTrue(model.contains("currentDashboard?.localDevice"), "diagnostics must use this installation's active binding")
        let start = try XCTUnwrap(model.range(of: "func refreshDiagnostics()"))
        let end = try XCTUnwrap(model.range(of: "var canReconnectManagedConnector", range: start.upperBound..<model.endIndex))
        let body = model[start.lowerBound..<end.lowerBound]
        XCTAssertTrue(body.contains("await refresh()"))
        XCTAssertTrue(body.contains("await refreshAccount()"))
        XCTAssertFalse(model.contains("AccountDeviceHealth.snapshot(for: device, checkedAt: health.checkedAt)"))
    }
}

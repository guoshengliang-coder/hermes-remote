import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesSimpleUpgradeTests: XCTestCase {
    static let target = try! DesktopHermesUpgradeTarget(version: "0.22.5", commit: String(repeating: "b", count: 40))
    static func installation(_ version: String = "0.21.3", commit: String = String(repeating: "a", count: 40)) -> DesktopLocalHermesDetection {
        let home = URL(fileURLWithPath: "/tmp/fixture-hermes-user")
        return .usable(.init(executable: home.appendingPathComponent(".hermes/hermes-agent/venv/bin/hermes"),
                            checkoutRoot: home.appendingPathComponent(".hermes/hermes-agent"),
                            hermesHome: home.appendingPathComponent(".hermes"), commit: commit,
                            version: version, identityChangedAt: Date()))
    }
    static func check(_ level: HealthLevel = .healthy) -> DesktopDiagnosticCheck {
        .init(id: .localHermes, title: "本机 Hermes", source: "fixture", health: .init(component: .hermes, level: level, detail: "fixture", checkedAt: Date()))
    }
    @MainActor func testNoCertificationNeededAndSuccessRunsDiagnosticsAfterVersionRefresh() async {
        let state = UpgradeState(Self.installation())
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { await state.current() }, execute: { target, _, output in
            output("OPENAI_API_KEY=fixture-secret-value")
            await state.updated(Self.installation(target.version, commit: target.commit))
        })
        await manager.check()
        XCTAssertNil(manager.report?.verifiedRecommendation)
        XCTAssertTrue(manager.canUpgrade)
        var diagnosed = false
        await manager.upgrade {
            diagnosed = true
            XCTAssertEqual(manager.installation?.installation?.version, "0.22.5")
            XCTAssertEqual(manager.phase, .checkingRuntime)
            return [Self.check()]
        }
        await Task.yield()
        XCTAssertTrue(diagnosed)
        XCTAssertEqual(manager.phase, .completed)
        XCTAssertEqual(manager.resultSummary, "升级完成，运行检测通过")
        XCTAssertFalse(manager.canUpgrade)
        XCTAssertFalse(manager.output.joined().contains("fixture-secret-value"))
    }
    @MainActor func testSameNewerAbsentNeverOfferDowngradeOrInstall() async {
        for local in [Self.installation("0.22.5"), Self.installation("0.23.0"), .absent(hermesDataPresent: false)] {
            let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { local }, execute: { _, _, _ in XCTFail("should not execute") })
            await manager.check()
            XCTAssertFalse(manager.canUpgrade)
            if local.installation?.version == Self.target.version {
                XCTAssertEqual(manager.comparison, "版本号相同，当前代码与稳定版不同")
            }
            await manager.upgrade { XCTFail("should not diagnose"); return [] }
        }
    }
    @MainActor func testWrongInstalledCommitFailsEvenWithSuccessfulExitAndCanRetry() async {
        let state = UpgradeState(Self.installation())
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { await state.current() }, execute: { _, _, _ in
            await state.updated(Self.installation("0.22.5"))
        })
        await manager.check()
        await manager.upgrade { XCTFail("wrong target must not report completed"); return [] }
        XCTAssertEqual(manager.phase, .failed)
        XCTAssertEqual(manager.issue?.code, .hermesUpdateExecutionFailed)
        XCTAssertTrue(manager.canUpgrade, "same version but failed commit check must remain retryable")
    }
    @MainActor func testInterruptedDependenciesRemainRetryableWhenDetectionIsIncomplete() async {
        let state = UpgradeState(Self.installation())
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { await state.current() }, execute: { target, _, _ in
            if await state.updates == 0 {
                await state.updated(.unsupported(.incompleteInstallation, detail: "interrupted fixture"))
                throw URLError(.networkConnectionLost)
            }
            await state.updated(Self.installation(target.version, commit: target.commit))
        })
        await manager.check()
        await manager.upgrade { [] }
        XCTAssertTrue(manager.canUpgrade)
        await manager.upgrade { [Self.check()] }
        XCTAssertEqual(manager.phase, .completed)
    }
    @MainActor func testExecutionFailureDoesNotClaimRollbackOrRunSuccessChecks() async {
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { Self.installation() }, execute: { _, _, _ in throw URLError(.notConnectedToInternet) })
        await manager.check()
        await manager.upgrade { XCTFail("no success checks"); return [] }
        XCTAssertEqual(manager.phase, .failed)
        XCTAssertEqual(manager.resultSummary, "升级未完成")
        XCTAssertEqual(manager.issue?.code, .hermesUpdateExecutionFailed)
        XCTAssertTrue(manager.canUpgrade)
    }
    @MainActor func testPostCheckFailureAndUnknownAreNotCompatibilityVerdictsAndRetryDoesNotUpgradeAgain() async {
        for health in [HealthLevel.failed, .unavailable] {
            let state = UpgradeState(Self.installation())
            let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { await state.current() }, execute: { target, _, _ in await state.updated(Self.installation(target.version, commit: target.commit)) })
            await manager.check()
            await manager.upgrade { [Self.check(health)] }
            XCTAssertEqual(manager.phase, .completed)
            XCTAssertFalse(manager.resultSummary.contains("兼容"))
            XCTAssertNotEqual(manager.resultSummary, "升级完成，运行检测通过")
            XCTAssertEqual(manager.issue?.code, health == .failed ? .hermesUpdateDiagnosticsFailed : nil)
            await manager.recheckRuntime { [Self.check()] }
            XCTAssertNil(manager.issue)
            XCTAssertEqual(manager.resultSummary, "升级完成，运行检测通过")
            let executions = await state.updates
            XCTAssertEqual(executions, 1)
        }
    }
    @MainActor func testPreUpgradeCloudSuccessCannotProveNewVersionWorks() async {
        let state = UpgradeState(Self.installation())
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { await state.current() }, execute: { target, _, _ in await state.updated(Self.installation(target.version, commit: target.commit)) })
        await manager.check()
        await manager.upgrade {
            [.init(id: .reportedHermes, title: "Hermes 远程可达性", source: "fixture",
                   health: .init(component: .hermes, level: .healthy, detail: "old result", checkedAt: Date().addingTimeInterval(-60)))]
        }
        XCTAssertEqual(manager.checks.first?.health.level, .unavailable)
        XCTAssertEqual(manager.resultSummary, "升级完成，部分运行检测待确认")
    }
    @MainActor func testDuplicateClickCannotExecuteTwice() async {
        let gate = UpgradeGate()
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FixedRelease(target: Self.target)), detect: { Self.installation() }, execute: { _, _, _ in await gate.wait() })
        await manager.check()
        let first = Task { await manager.upgrade { [] } }
        while manager.phase != .installing { await Task.yield() }
        await manager.upgrade { XCTFail("duplicate"); return [] }
        while await gate.calls == 0 { await Task.yield() }
        await gate.finish()
        await first.value
        let count = await gate.calls
        XCTAssertEqual(count, 1)
    }
    @MainActor func testReleaseFailureStillShowsLocalAndDiscardsOldTarget() async {
        let manager = DesktopHermesVersionManager(checker: .init(fetcher: FailingRelease()), detect: { Self.installation() }, execute: { _, _, _ in XCTFail() })
        await manager.check()
        XCTAssertEqual(manager.installation?.installation?.version, "0.21.3")
        XCTAssertNil(manager.report)
        XCTAssertEqual(manager.issue?.code, .hermesUpdateCheckFailed)
        XCTAssertFalse(manager.canUpgrade)
    }
    func testNotesDoNotInventChineseBenefitsForUnknownRelease() throws {
        let missing = Self.target
        XCTAssertTrue(missing.releaseHighlights.isEmpty)
        let target = try DesktopHermesUpgradeTarget(version: "0.22.5", commit: Self.target.commit, releaseNotes: "# Updates\n- Fix something\n- Add something")
        XCTAssertNil(target.highlightsChinese)
        XCTAssertEqual(target.releaseHighlights, ["Fix something", "Add something"])
        XCTAssertEqual(target.releaseURL.absoluteString, "https://github.com/NousResearch/hermes-agent/releases/tag/v0.22.5")
    }
    func testNewErrorContracts() throws {
        for code in [DesktopIssueCode.hermesUpdateExecutionFailed, .hermesUpdateDiagnosticsFailed] {
            let issue = DesktopIssue(code: code, technicalCause: "OPENAI_API_KEY=fixture-secret")
            XCTAssertTrue(issue.retryable)
            XCTAssertEqual(issue.recoveryAction, .retry)
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("fixture-secret"))
            XCTAssertEqual(try JSONDecoder().decode(DesktopIssueCode.self, from: JSONEncoder().encode(code)), code)
        }
    }
    func testInstallerUsesExactCommitAndStopsAtFirstFailedStage() async throws {
        let home = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: home) }
        let runner = StageRunner(fails: "python-deps")
        let installer = DesktopHermesStableInstaller(paths: try .init(homeDirectory: home), proxy: .init(), runner: runner, fetcher: ScriptFixture(), inheritedEnvironment: ["OPENAI_API_KEY": "private"])
        do { try await installer.run(target: Self.target, progress: { _ in }, output: { _ in }); XCTFail() }
        catch {}
        let calls = await runner.calls
        XCTAssertEqual(calls.count, 4) // manifest + repository + venv + failed dependencies
        for call in calls.dropFirst() {
            XCTAssertEqual(call[call.firstIndex(of: "--commit")! + 1], Self.target.commit)
            XCTAssertTrue(call.contains("--non-interactive"))
        }
        XCTAssertNil(installer.environment()["OPENAI_API_KEY"])
    }
    func testActualProcessRunnerExecutesOfficialStageProtocolAdapter() async throws {
        let home = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: home) }
        let installer = DesktopHermesStableInstaller(paths: try .init(homeDirectory: home), proxy: .init(), fetcher: ScriptFixture(), inheritedEnvironment: [:])
        try await installer.run(target: Self.target, progress: { _ in }, output: { _ in })
        let recorded = try String(contentsOf: home.appendingPathComponent("stages"))
        XCTAssertEqual(recorded.components(separatedBy: .newlines).filter { !$0.isEmpty }, DesktopHermesStableInstaller.stages)
    }
}
private struct FixedRelease: DesktopHermesReleaseFetching {
    let target: DesktopHermesUpgradeTarget
    func latestStable() async throws -> DesktopHermesUpgradeTarget { target }
}
private struct FailingRelease: DesktopHermesReleaseFetching {
    func latestStable() async throws -> DesktopHermesUpgradeTarget { throw URLError(.notConnectedToInternet) }
}
private actor UpgradeState {
    var value: DesktopLocalHermesDetection
    var updates = 0
    init(_ value: DesktopLocalHermesDetection) { self.value = value }
    func current() -> DesktopLocalHermesDetection { value }
    func updated(_ value: DesktopLocalHermesDetection) { self.value = value; updates += 1 }
}
private actor UpgradeGate {
    var calls = 0
    var continuation: CheckedContinuation<Void, Never>?
    func wait() async { calls += 1; await withCheckedContinuation { continuation = $0 } }
    func finish() { continuation?.resume() }
}
private let manifest = "{\"protocol_version\":1,\"stages\":[" + DesktopHermesStableInstaller.stages.map { "{\"name\":\"\($0)\",\"title\":\"\($0)\"}" }.joined(separator: ",") + "]}"
private struct ScriptFixture: DesktopHermesInstallerScriptFetching {
    func fetchInstallerScript(from url: URL) async throws -> Data {
        XCTAssertTrue(url.path.contains(DesktopHermesSimpleUpgradeTests.target.commit))
        return Data("""
        #!/bin/bash
        if [ "$1" = "--manifest" ]; then
          echo '\(manifest)'
        else
          echo "$2" >> "$HOME/stages"
          printf '{"ok":true,"stage":"%s","skipped":false}\\n' "$2"
        fi
        """.utf8)
    }
}
private actor StageRunner: DesktopHermesInstallerProcessRunning {
    let fails: String
    var calls: [[String]] = []
    init(fails: String) { self.fails = fails }
    func run(executable: URL, arguments: [String], environment: [String: String], workingDirectory: URL, onLine: @escaping @Sendable (String) -> Void) async throws -> DesktopHermesInstallerProcessResult {
        calls.append(arguments)
        if arguments.contains("--manifest") { return .init(exitStatus: 0, standardOutputTail: [manifest], combinedTail: []) }
        let stage = arguments[2]
        return .init(exitStatus: stage == fails ? 1 : 0, standardOutputTail: ["{\"ok\":true,\"stage\":\"\(stage)\",\"skipped\":false}"], combinedTail: [])
    }
}

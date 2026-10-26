import CryptoKit
import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesPinnedUpgradeInstallerTests: XCTestCase {
    func testActualRunnerDoesNotTreatExitedParentWithLiveChildAsStageSuccess() async throws {
        let f = try Fixture(); defer { f.remove() }
        let pidFile = f.root.appendingPathComponent("child.pid")
        defer {
            if let text = try? String(contentsOf: pidFile), let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)) {
                _ = kill(pid, SIGKILL)
            }
        }
        let result = try await DesktopPosixProcessRunner(requireProcessGroupExit: true).run(executable: URL(fileURLWithPath: "/bin/bash"),
            arguments: ["-c", "sleep 30 </dev/null >/dev/null 2>&1 & echo $! > child.pid; printf '%s\\n' '{\"ok\":true,\"stage\":\"repository\"}'; exit 0"],
            environment: ["PATH": "/usr/bin:/bin"], workingDirectory: f.root, onLine: { _ in })
        let pid = try XCTUnwrap(Int32(String(contentsOf: pidFile).trimmingCharacters(in: .whitespacesAndNewlines)))
        XCTAssertEqual(kill(pid, 0), 0, "the fixture must still have a live descendant")
        XCTAssertNotEqual(result.exitStatus, 0, "parent success cannot prove all stage writers exited")
    }

    func testPreparedScriptIsPinnedBySignedHashAndNeverFetchedFromMain() async throws {
        let f = try Fixture(); defer { f.remove() }
        let adapter = f.adapter()
        _ = try await adapter.prepare(recommendation: f.recommendation, entryID: f.entryID, workRoot: f.root)
        XCTAssertEqual(f.runner.requestedURL?.path, "/NousResearch/hermes-agent/\(f.target)/scripts/install.sh")
        f.runner.bytes = Data("changed script".utf8)
        let count = try FileManager.default.contentsOfDirectory(atPath: f.root.path).count
        do {
            _ = try await adapter.prepare(recommendation: f.recommendation, entryID: f.entryID, workRoot: f.root)
            XCTFail("changed bytes accepted")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: f.root.path).count, count)
        XCTAssertEqual(f.runner.calls.count, 0)
        do {
            _ = try await adapter.prepare(recommendation: f.recommendation, entryID: f.entryID, workRoot: f.root,
                now: f.recommendation.expiresAt)
            XCTFail("expired recommendation accepted")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .preflightBlocked) }
    }

    func testOfficialStagesUseExactCommitAndRetainUpdatingIntentForServiceVerification() async throws {
        let f = try Fixture(); defer { f.remove() }
        let adapter = f.adapter(), operation = try f.updating()
        let prepared = try await adapter.prepare(recommendation: f.recommendation, entryID: f.entryID, workRoot: f.root)
        try await adapter.run(prepared, operation: operation, paths: f.paths, proxy: .init(), beforeStage: {
            XCTAssertThrowsError(try operation.advance(expected: operation.snapshot, to: .restoringServices))
            XCTAssertThrowsError(try DesktopHermesUpgradeOperation.resume(store: f.store, expected: operation.snapshot))
        })
        XCTAssertEqual(f.runner.calls.count, 7)
        XCTAssertEqual(f.runner.calls.first?.arguments.suffix(1), ["--manifest"])
        for (stage, call) in zip(DesktopHermesPinnedUpgradeInstaller.Stage.allCases, f.runner.calls.dropFirst()) {
            XCTAssertEqual(call.arguments[1...4], ["--stage", stage.rawValue, "--non-interactive", "--json"])
            let index = try XCTUnwrap(call.arguments.firstIndex(of: "--commit"))
            XCTAssertEqual(call.arguments[index + 1], f.target)
            XCTAssertFalse(call.arguments.contains("main"))
            XCTAssertEqual(call.environment["HOME"], f.paths.homeDirectory.path)
            XCTAssertEqual(call.environment["HERMES_HOME"], f.paths.hermesHome.path)
            XCTAssertNil(call.environment["SSH_AUTH_SOCK"])
            XCTAssertNil(call.environment["PYTHONPATH"])
            XCTAssertNil(call.environment["BASH_ENV"])
            XCTAssertEqual(call.environment["GIT_CONFIG_GLOBAL"], "/dev/null")
        }
        XCTAssertEqual(operation.snapshot.phase, .updating)
        XCTAssertEqual(try f.store.loadReadOnly(), operation.snapshot)
        _ = try operation.advance(expected: operation.snapshot, to: .restoringServices)
    }

    func testFailureOrChangedScriptStopsWithoutRetryOrRemovingRecoveryIntent() async throws {
        for mode in ["nonzero", "wrong-stage", "tampered", "wrong-head", "guard"] {
            let f = try Fixture(); defer { f.remove() }
            let adapter = f.adapter(), operation = try f.updating()
            let prepared = try await adapter.prepare(recommendation: f.recommendation, entryID: f.entryID, workRoot: f.root)
            f.runner.mode = mode
            if mode == "tampered" {
                let folder = try XCTUnwrap(FileManager.default.contentsOfDirectory(at: f.root, includingPropertiesForKeys: nil)
                    .first(where: { $0.lastPathComponent.hasPrefix("hermes-upgrade-installer-") }))
                try Data("changed".utf8).write(to: folder.appendingPathComponent("install.sh"))
            }
            do {
                try await adapter.run(prepared, operation: operation, paths: f.paths, proxy: .init(), beforeStage: {
                    if mode == "guard" { throw DesktopHermesUpgradeError.preflightBlocked }
                })
                XCTFail("failure accepted: \(mode)")
            } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired, mode) }
            XCTAssertEqual(operation.snapshot.phase, .updating)
            XCTAssertNotNil(operation.snapshot.backup)
            XCTAssertLessThanOrEqual(f.runner.calls.count, 2)
            XCTAssertEqual(try f.store.loadReadOnly(), operation.snapshot)
        }
    }

    func testTimeoutCancelsRunnerAndLeavesRecoverableIntent() async throws {
        let f = try Fixture(); defer { f.remove() }
        f.runner.mode = "wait"
        let adapter = f.adapter(timeout: .milliseconds(25)), operation = try f.updating()
        let prepared = try await adapter.prepare(recommendation: f.recommendation, entryID: f.entryID, workRoot: f.root)
        do {
            try await adapter.run(prepared, operation: operation, paths: f.paths, proxy: .init(), beforeStage: {})
            XCTFail("timeout accepted")
        } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        XCTAssertEqual(f.runner.calls.count, 1)
        XCTAssertEqual(operation.snapshot.phase, .updating)
        _ = try operation.advance(expected: operation.snapshot, to: .recovering)
    }
}

private final class Fixture: @unchecked Sendable {
    let root: URL
    let paths: DesktopLocalHermesPaths
    let store: DesktopHermesUpgradeJournalStore
    let source = String(repeating: "a", count: 40), target = String(repeating: "b", count: 40)
    let entryID = UUID().uuidString.lowercased()
    let runner = FakePinnedRunner()
    let recommendation: VerifiedHermesRecommendation
    init() throws {
        root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().appendingPathComponent("hermes-pinned-installer-test-\(UUID())")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        paths = try .init(homeDirectory: root.appendingPathComponent("user"), systemEntrypoints: [])
        store = .init(root: root.appendingPathComponent("operation"))
        let format = ISO8601DateFormatter(), now = Date()
        let identity: [String: Any] = ["schemaVersion": 1, "maintenancePolicy": "go-controlled-window-v1",
            "candidateId": UUID().uuidString.lowercased(), "version": "0.22.5", "sourceCommit": source,
            "targetCommit": target, "goCommit": String(repeating: "c", count: 40), "suiteSha256": String(repeating: "d", count: 64),
            "installerSha256": SHA256.hash(data: runner.bytes).map { String(format: "%02x", $0) }.joined(),
            "environment": ["os": "macos", "arch": "arm64", "osVersion": "26.0.0", "installation": "legacy-venv-to-pm"],
            "components": ["desktop", "connector", "gateway", "android", "web"].map { ["name": $0, "version": "1.0.0", "artifactSha256": String(repeating: "e", count: 64)] }]
        let payload: [String: Any] = ["schemaVersion": 1, "purpose": "hermes-go.compatibility.recommendations.v1", "origin": "https://fixture.invalid", "revision": 1,
            "issuedAt": format.string(from: now.addingTimeInterval(-1)), "expiresAt": format.string(from: now.addingTimeInterval(3600)),
            "entries": [["schemaVersion": 1, "id": entryID, "runId": UUID().uuidString.lowercased(), "identity": identity, "reportSha256": String(repeating: "f", count: 64)]]]
        let key = Curve25519.Signing.PrivateKey(), bytes = try JSONSerialization.data(withJSONObject: payload)
        func encode(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
        let envelope = try JSONSerialization.data(withJSONObject: ["payload": encode(bytes), "keyId": "test", "algorithm": "Ed25519", "signature": encode(key.signature(for: bytes))])
        recommendation = try HermesCompatibilityRecommendationVerifier(origin: "https://fixture.invalid", keys: ["test": key.publicKey.rawRepresentation]).verify(envelope, minimumRevision: 0)
    }
    func adapter(timeout: Duration = .seconds(2)) -> DesktopHermesPinnedUpgradeInstaller {
        .init(runner: runner, fetcher: runner, stageTimeout: timeout, readCommit: { [runner] _ in runner.commit })
    }
    func updating() throws -> DesktopHermesUpgradeOperation {
        let initial = try DesktopHermesUpgradeJournal(runID: UUID(), target: .init(version: "0.22.5", commit: target),
            sourceCommit: source, sourceIdentity: String(repeating: "d", count: 64), checkout: paths.checkoutRoot,
            hermesHome: paths.hermesHome, services: [], originalChannel: "stable")
        let operation = try DesktopHermesUpgradeOperation.begin(store: store, journal: initial)
        let backing = try operation.advance(expected: initial, to: .backingUp)
        // Synthetic receipt tests the phase boundary; real capture is tested separately.
        _ = try operation.advance(expected: backing, to: .updating, backup: .init(runID: initial.runID, manifestSHA256: String(repeating: "e", count: 64)))
        return operation
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}

private final class FakePinnedRunner: DesktopHermesInstallerProcessRunning, DesktopHermesInstallerScriptFetching, @unchecked Sendable {
    struct Call { let arguments: [String]; let environment: [String: String] }
    private let lock = NSLock()
    var bytes = Data("#!/bin/bash\n# synthetic installer\n".utf8)
    var requestedURL: URL?
    var mode = "normal"
    private var stored: [Call] = []
    private var head = String(repeating: "a", count: 40)
    var calls: [Call] { lock.withLock { stored } }
    var commit: String { lock.withLock { head } }
    func fetchInstallerScript(from url: URL) async throws -> Data { requestedURL = url; return bytes }
    func run(executable: URL, arguments: [String], environment: [String: String], workingDirectory: URL,
             onLine: @escaping @Sendable (String) -> Void) async throws -> DesktopHermesInstallerProcessResult {
        lock.withLock { stored.append(.init(arguments: arguments, environment: environment)) }
        if mode == "wait" { try await Task.sleep(for: .seconds(120)) }
        let output: String
        if arguments.contains("--manifest") {
            let manifest: [String: Any] = ["protocol_version": 1, "stages": DesktopHermesPinnedUpgradeInstaller.Stage.allCases.map {
                ["name": $0.rawValue, "needs_user_input": false] as [String: Any]
            }]
            output = String(data: try JSONSerialization.data(withJSONObject: manifest), encoding: .utf8)!
        } else {
            lock.withLock { head = String(repeating: mode == "wrong-head" ? "c" : "b", count: 40) }
            output = "{\"ok\":true,\"stage\":\"\(mode == "wrong-stage" ? "unknown" : arguments[2])\"}"
        }
        return .init(exitStatus: mode == "nonzero" && !arguments.contains("--manifest") ? 1 : 0,
            standardOutputTail: [output], combinedTail: ["synthetic-private-diagnostic"])
    }
}

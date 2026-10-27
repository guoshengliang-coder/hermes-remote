import CryptoKit
import Darwin
import Foundation
@testable import HermesGoDesktopCore

/// Disposable-lab adapter only. The ephemeral signing key certifies no real release, account or
/// component. It exercises the production byte verification and staged runner against pinned input.
@main struct PinnedUpgradeProbe {
    static func main() async {
        do { try await run() }
        catch {
            print("{\"status\":\"refused\",\"code\":\"\((error as? DesktopHermesUpgradeError)?.issue.code.rawValue ?? "HR-HERMESUPDATE-003")\"}")
            exit(2)
        }
    }
    static func run() async throws {
        guard CommandLine.arguments.count == 4, let runID = UUID(uuidString: CommandLine.arguments[2]),
              ["normal", "dependency-failure"].contains(CommandLine.arguments[3]) else { throw DesktopHermesUpgradeError.preflightBlocked }
        let root = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
        var info = stat()
        guard root == root.resolvingSymlinksInPath(), root.lastPathComponent.hasPrefix("hermes-compat-lab-"),
              root.deletingLastPathComponent() == FileManager.default.temporaryDirectory.resolvingSymlinksInPath(),
              lstat(root.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
        let workspace = root.appendingPathComponent("native-recovery-" + runID.uuidString.lowercased())
        let store = DesktopHermesUpgradeJournalStore(root: workspace.appendingPathComponent("state"))
        guard let intent = try store.loadReadOnly(), intent.runID == runID.uuidString, intent.phase == .updating,
              intent.sourceCommit == "633dda6d7fdcac7e8340471c9f5ed08cdccbd70c",
              intent.target.commit == "bf0203a794a07aac3ff764972ab9087baa4e4f4e"
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let paths = try DesktopLocalHermesPaths(homeDirectory: root.appendingPathComponent("user"), systemEntrypoints: [])
        guard paths.hermesHome.path == intent.hermesHome else { throw DesktopHermesUpgradeError.preflightBlocked }
        let operation = try DesktopHermesUpgradeOperation.resume(store: store, expected: intent)
        print("{\"checkpoint\":\"intent-resumed\"}")
        let stageRunner = LabRunner(root: root,
            profile: root.appendingPathComponent("pinned-upgrade-" + runID.uuidString.lowercased() + "/native-install.sb"),
            injectFailure: CommandLine.arguments[3] == "dependency-failure")
        let adapter = DesktopHermesPinnedUpgradeInstaller(runner: stageRunner, fetcher: stageRunner)
        let entryID = UUID().uuidString.lowercased(), format = ISO8601DateFormatter(), now = Date()
        let components = ["desktop", "connector", "gateway", "android", "web"].map {
            ["name": $0, "version": "1.0.0", "artifactSha256": String(repeating: "d", count: 64)]
        }
        let identity: [String: Any] = ["schemaVersion": 1, "maintenancePolicy": "go-controlled-window-v1",
            "candidateId": UUID().uuidString.lowercased(), "version": "0.22.5", "sourceCommit": intent.sourceCommit,
            "targetCommit": intent.target.commit, "goCommit": String(repeating: "b", count: 40), "suiteSha256": String(repeating: "c", count: 64),
            "installerSha256": "99cb3691d55019c6973a7acb775a915648666cc24e483ca827a1cdbb3531a674",
            "environment": ["os": "macos", "arch": "arm64", "osVersion": "26.0.0", "installation": "legacy-venv-to-pm"],
            "components": components]
        let entry: [String: Any] = ["schemaVersion": 1, "id": entryID, "runId": runID.uuidString.lowercased(),
            "reportSha256": String(repeating: "a", count: 64), "identity": identity]
        let payload: [String: Any] = ["schemaVersion": 1, "purpose": "hermes-go.compatibility.recommendations.v1",
            "origin": "https://fixture.invalid", "revision": 1, "issuedAt": format.string(from: now.addingTimeInterval(-1)),
            "expiresAt": format.string(from: now.addingTimeInterval(3600)), "entries": [entry]]
        let key = Curve25519.Signing.PrivateKey(), bytes = try JSONSerialization.data(withJSONObject: payload)
        func encode(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
        let envelope = try JSONSerialization.data(withJSONObject: ["payload": encode(bytes), "keyId": "test", "algorithm": "Ed25519", "signature": encode(key.signature(for: bytes))])
        let recommendation = try HermesCompatibilityRecommendationVerifier(origin: "https://fixture.invalid", keys: ["test": key.publicKey.rawRepresentation]).verify(envelope, minimumRevision: 0)
        let temporary = root.appendingPathComponent("tmp/pinned-upgrade-" + runID.uuidString)
        guard mkdir(temporary.path, 0o700) == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
        let prepared = try await adapter.prepare(recommendation: recommendation, entryID: entryID, workRoot: temporary)
        print("{\"checkpoint\":\"script-prepared\"}")
        try await adapter.run(prepared, operation: operation, paths: paths, proxy: .none, beforeStage: {
            let process = Process(), output = Pipe()
            process.executableURL = URL(fileURLWithPath: "/usr/sbin/lsof")
            process.arguments = ["-t", paths.stateDatabase.path]
            process.standardOutput = output; process.standardError = FileHandle.nullDevice
            try process.run(); process.waitUntilExit()
            guard [0, 1].contains(process.terminationStatus), output.fileHandleForReading.readDataToEndOfFile().isEmpty
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            print("{\"checkpoint\":\"database-idle\"}")
        }, progress: { stage, completed in
            print("{\"stage\":\"\(stage.rawValue)\",\"completed\":\(completed)}")
        })
        guard operation.snapshot.phase == .updating else { throw DesktopHermesUpgradeError.recoveryRequired }
        print("{\"status\":\"installed\",\"intentRetained\":true}")
    }
}

private struct LabRunner: DesktopHermesInstallerProcessRunning, DesktopHermesInstallerScriptFetching {
    let root: URL
    let profile: URL
    let injectFailure: Bool
    func fetchInstallerScript(from url: URL) async throws -> Data {
        guard url.absoluteString == "https://raw.githubusercontent.com/NousResearch/hermes-agent/bf0203a794a07aac3ff764972ab9087baa4e4f4e/scripts/install.sh"
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        return try Data(contentsOf: root.appendingPathComponent("verified-install.sh"))
    }
    func run(executable: URL, arguments: [String], environment: [String: String], workingDirectory: URL,
             onLine: @escaping @Sendable (String) -> Void) async throws -> DesktopHermesInstallerProcessResult {
        var env = environment
        env["PATH"] = root.appendingPathComponent("lab-tools").path + ":/usr/bin:/bin:/usr/sbin:/sbin"
        env["XDG_CACHE_HOME"] = root.appendingPathComponent("user/.cache").path
        env["XDG_CONFIG_HOME"] = root.appendingPathComponent("user/.config").path
        env["XDG_DATA_HOME"] = root.appendingPathComponent("user/.local/share").path
        for name in DesktopSystemProxy.proxyVariableNames + DesktopSystemProxy.noProxyVariableNames {
            if let value = ProcessInfo.processInfo.environment[name] { env[name] = value }
        }
        let log = root.appendingPathComponent("pinned-upgrade.private.log")
        let fd = Darwin.open(log.path, O_WRONLY | O_CREAT | O_APPEND | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        let result = try await DesktopPosixProcessRunner(requireProcessGroupExit: true).run(executable: URL(fileURLWithPath: "/usr/bin/sandbox-exec"),
            arguments: ["-f", profile.path, executable.path] + arguments,
            environment: env, workingDirectory: workingDirectory, onLine: { line in
                try? handle.write(contentsOf: Data((line + "\n").utf8)); onLine(line)
            })
        if injectFailure && arguments.contains("python-deps") && result.exitStatus == 0 {
            return .init(exitStatus: 97, standardOutputTail: result.standardOutputTail, combinedTail: [])
        }
        return result
    }
}

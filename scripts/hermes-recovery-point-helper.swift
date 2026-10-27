import CryptoKit
import Darwin
import Foundation
import HermesGoDesktopCore

/// Disposable-lab adapter to the actual Core. No updater, service control or live-path replacement.
@main struct RecoveryPointProbe {
    static func main() {
        do { try run() }
        catch {
            print("{\"status\":\"refused\",\"code\":\"HR-HERMESUPDATE-003\"}")
            exit(2)
        }
    }
    static func run() throws {
        guard CommandLine.arguments.count == 4, let runID = UUID(uuidString: CommandLine.arguments[3]) else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        let action = CommandLine.arguments[1]
        let lab = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
        var info = stat()
        guard lab == lab.resolvingSymlinksInPath(), lab.lastPathComponent.hasPrefix("hermes-compat-lab-"),
              lab.deletingLastPathComponent() == FileManager.default.temporaryDirectory.resolvingSymlinksInPath(),
              lstat(lab.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
        let workspace = lab.appendingPathComponent("native-recovery-" + runID.uuidString.lowercased())
        let store = DesktopHermesUpgradeJournalStore(root: workspace.appendingPathComponent("state"))
        let recovery = DesktopHermesRecoveryPointStore(root: workspace.appendingPathComponent("points"))
        if action == "capture" {
            guard mkdir(workspace.path, 0o700) == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
            let home = lab.appendingPathComponent("user/.hermes")
            let checkout = home.appendingPathComponent("hermes-agent")
            let python = checkout.appendingPathComponent("venv/bin/python").resolvingSymlinksInPath()
            let runtime = python.deletingLastPathComponent().deletingLastPathComponent()
            let runtimeParent = lab.appendingPathComponent("user/.local/share/uv/python")
            guard runtime.deletingLastPathComponent().path == runtimeParent.path,
                  runtime.lastPathComponent.hasPrefix("cpython-"), python.deletingLastPathComponent().lastPathComponent == "bin"
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            // The driver independently checks the pinned Git source. This adapter's identity binds
            // its disposable source paths, not a production installation attestation.
            let identity = SHA256.hash(data: Data((checkout.path + "\n" + runtime.path).utf8)).map { String(format: "%02x", $0) }.joined()
            let initial = try DesktopHermesUpgradeJournal(runID: runID,
                target: .init(version: "0.22.5", commit: "bf0203a794a07aac3ff764972ab9087baa4e4f4e"),
                sourceCommit: "633dda6d7fdcac7e8340471c9f5ed08cdccbd70c", sourceIdentity: identity,
                checkout: checkout, hermesHome: home, services: [], originalChannel: "stable")
            let operation = try DesktopHermesUpgradeOperation.begin(store: store, journal: initial)
            let backingUp = try operation.advance(expected: initial, to: .backingUp)
            let receipt = try operation.captureRecoveryPoint(in: recovery, externalRuntimeRoots: [runtime])
            try operation.advance(expected: backingUp, to: .updating, backup: receipt)
            print("{\"status\":\"captured\"}")
        } else if action == "materialize" {
            guard let prior = try store.loadReadOnly(), prior.runID == runID.uuidString, prior.phase == .updating else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            let operation = try DesktopHermesUpgradeOperation.resume(store: store, expected: prior)
            try operation.advance(expected: prior, to: .recovering)
            let material = try operation.materializeRecoveryPoint(in: recovery)
            let plan: [String: Any] = ["runID": material.runID, "roots": material.roots.map {
                ["original": $0.original.path, "staged": $0.staged.path, "sha256": $0.sha256]
            }]
            let data = try JSONSerialization.data(withJSONObject: plan, options: [.sortedKeys])
            let fd = Darwin.open(workspace.appendingPathComponent("material.json").path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }; defer { Darwin.close(fd) }
            try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
            guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            print("{\"status\":\"materialized\"}")
        } else { throw DesktopHermesUpgradeError.preflightBlocked }
    }
}

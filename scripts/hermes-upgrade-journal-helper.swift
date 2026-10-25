import Darwin
import Foundation
@testable import HermesGoDesktopCore

/// Test-only process boundary for durable GO intent. Never starts services or upstream code.
@main struct JournalProcessProbe {
    static func main() {
        do { try run() }
        catch {
            let code = (error as? DesktopHermesUpgradeError)?.issue.code.rawValue ?? "HR-HERMESUPDATE-003"
            print("{\"status\":\"refused\",\"code\":\"\(code)\"}")
            exit(2)
        }
    }
    static func run() throws {
        guard CommandLine.arguments.count == 3 else { throw DesktopHermesUpgradeError.preflightBlocked }
        let action = CommandLine.arguments[1]
        let root = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
        var info = stat()
        guard root == root.resolvingSymlinksInPath(), root.lastPathComponent.hasPrefix("hermes-upgrade-operation-lab-"),
              root.deletingLastPathComponent() == FileManager.default.temporaryDirectory.resolvingSymlinksInPath(),
              lstat(root.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let store = DesktopHermesUpgradeJournalStore(root: root)
        if action == "resume" || action == "contend" {
            guard let prior = try store.loadReadOnly() else { throw DesktopHermesUpgradeError.recoveryRequired }
            var operation: DesktopHermesUpgradeOperation? = try .resume(store: store, expected: prior)
            if action == "contend" { throw DesktopHermesUpgradeError.recoveryRequired }
            let cancelling = try operation!.advance(expected: prior, to: .cancelling)
            let cancelled = try operation!.advance(expected: cancelling, to: .cancelled)
            guard cancelled.runID == prior.runID else { throw DesktopHermesUpgradeError.recoveryRequired }
            operation = nil
            let lease = try DesktopMigrationJournalStore(root: root).acquireOperationLease()
            withExtendedLifetime(lease) { print("{\"status\":\"cancelled\",\"sameRun\":true,\"migrationLeaseAvailable\":true}") }
            return
        }
        guard ["hold", "before-replace", "after-replace"].contains(action), try store.loadReadOnly() == nil
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let home = root.appendingPathComponent("synthetic-home")
        let initial = try DesktopHermesUpgradeJournal(runID: UUID(),
            target: .init(version: "0.22.5", commit: String(repeating: "b", count: 40)),
            sourceCommit: String(repeating: "a", count: 40), sourceIdentity: String(repeating: "c", count: 64),
            checkout: home.appendingPathComponent("hermes-agent"), hermesHome: home, services: [], originalChannel: "stable")
        var first: DesktopHermesUpgradeOperation? = try .begin(store: store, journal: initial)
        guard first?.snapshot == initial else { throw DesktopHermesUpgradeError.recoveryRequired }
        first = nil
        let operation = try DesktopHermesUpgradeOperation(store: store, expected: initial, resuming: true,
            persistenceHook: { point in
                if (action == "before-replace" && point == .temporarySynced)
                    || (action == "after-replace" && point == .replaced) { hold() }
            })
        try operation.advance(expected: initial, to: .backingUp)
        withExtendedLifetime(operation) { hold() }
    }
    static func hold() {
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        _ = FileHandle.standardInput.readDataToEndOfFile()
    }
}

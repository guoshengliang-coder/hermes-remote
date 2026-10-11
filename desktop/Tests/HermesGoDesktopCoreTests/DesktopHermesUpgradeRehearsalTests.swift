import Foundation
import XCTest
@testable import HermesGoDesktopCore

/// Opt-in, destructive only inside a marked disposable installation. Never targets the owner's
/// home, starts a server, or certifies service recovery. Provision the official old venv first.
final class DesktopHermesUpgradeRehearsalTests: XCTestCase {
    func testIsolatedOldVenvToPinnedOfficialTargetAndSeparateHistoryRecovery() async throws {
        guard let text = ProcessInfo.processInfo.environment["HG199_REHEARSAL_ROOT"],
              let sourceText = ProcessInfo.processInfo.environment["HG199_REHEARSAL_SOURCE"]
        else { throw XCTSkip("explicit disposable installation required") }
        let root = URL(fileURLWithPath: text).resolvingSymlinksInPath()
        let ownerHome = FileManager.default.homeDirectoryForCurrentUser.resolvingSymlinksInPath()
        guard (root.path.hasPrefix("/private/tmp/hg199-") || root.path.hasPrefix("/tmp/hg199-")),
              !root.path.hasPrefix(ownerHome.path + "/"),
              FileManager.default.fileExists(atPath: root.appendingPathComponent(".hg199-isolated-rehearsal").path)
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let userHome = root.appendingPathComponent("home"), home = userHome.appendingPathComponent(".hermes")
        let checkout = home.appendingPathComponent("hermes-agent"), run = root.appendingPathComponent("run")
        let oldPython = checkout.appendingPathComponent("venv/bin/python")
        let oldCommit = "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121"
        let newCommit = "818c13be1dc4fd28987e1e881a9408224afd4535"
        let source = URL(fileURLWithPath: sourceText).resolvingSymlinksInPath()
        let guardPath = root.appendingPathComponent("guard")
        let sandbox = root.appendingPathComponent("sandbox.sb")
        // Every interpreter and its child inherits the deny-write/deny-signal profile.
        let python = guardPath.appendingPathComponent("sandbox-python")
        let quoted = { (s: String) in "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }
        try "#!/bin/sh\nexec /usr/bin/sandbox-exec -f \(quoted(sandbox.path)) \(quoted(oldPython.path)) \"$@\"\n"
            .write(to: python, atomically: false, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: python.path)
        let environment = ["HOME": userHome.path, "HERMES_HOME": home.path,
            "PATH": guardPath.path + ":/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "TMPDIR": root.appendingPathComponent("tmp").path, "XDG_CACHE_HOME": root.appendingPathComponent("cache").path,
            "UV_CACHE_DIR": root.appendingPathComponent("cache/uv").path, "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_NOSYSTEM": "1", "NO_COLOR": "1"]
        XCTAssertEqual(try command("/usr/bin/git", ["-C", checkout.path, "rev-parse", "HEAD"], environment), oldCommit)
        let originalBranch = try command("/usr/bin/git", ["-C", checkout.path, "symbolic-ref", "-q", "HEAD"], environment)
        _ = try command(python.path, ["-I", "-B", "-c", """
        import sys
        sys.path.insert(0, \(String(reflecting: checkout.path)))
        from hermes_cli import __version__
        from hermes_state import SessionDB
        assert __version__ == '0.21.3'
        db = SessionDB()
        db.create_session('hg199-before', 'cli')
        db.append_message('hg199-before', 'user', '合成升级前历史')
        db.close()
        print('old-history-written')
        """], environment)
        let runID = UUID().uuidString, updateID = UUID().uuidString
        let target = try DesktopHermesUpgradeTarget(version: "0.21.6", commit: newCommit)
        let journal = DesktopHermesUpgradeJournalStore(root: run)
        let installation = DesktopLocalHermesInstallation(executable: checkout.appendingPathComponent("venv/bin/hermes"),
            checkoutRoot: checkout, hermesHome: home, commit: oldCommit, version: "0.21.3", identityChangedAt: Date())
        // This disposable installation has no started runtimes. Never use this empty fixture fleet
        // as the production inventory: that requires independent admission/retirement proof.
        let plan = Data("""
        {"install_method":"git","updatable_in_place":true,"update_mechanism":"hermes update",
         "expected_sha":"\(oldCommit)","expected_version":"0.21.3","profiles":["default"],"runtimes":[]}
        """.utf8)
        let worker = try await DesktopHermesUpgradeOfficialWorker.acquire(configuration: .init(
            runID: runID, upstreamUpdateID: updateID, sourceRoot: source, checkout: checkout, hermesHome: home,
            runDirectory: run, journal: journal.journalURL, sourceCommit: oldCommit, targetCommit: newCommit,
            targetVersion: "0.21.6", preUpdateVersion: "0.21.3", plan: plan), python: python, userHome: userHome, path: environment["PATH"]!)
        defer { try? worker.release() }
        try journal.begin(.init(runID: runID, target: target, installation: installation,
                                services: [], originalChannel: "stable", upstreamUpdateID: updateID))
        _ = try journal.transition(runID: runID, to: .backingUp)
        let backups = DesktopHermesUpgradeBackupStore(root: root.appendingPathComponent("backups"))
        print("HG199_REHEARSAL backing-up")
        let backup = try backups.create(home: home, runID: runID, verifyStoppedWriters: {})
        _ = try journal.transition(runID: runID, to: .updating, backup: backup)
        print("HG199_REHEARSAL official-update")
        let exit = try await worker.runOfficialUpdate()
        XCTAssertEqual(exit, 0)
        XCTAssertEqual(try command("/usr/bin/git", ["-C", checkout.path, "rev-parse", "HEAD"], environment), newCommit)
        XCTAssertEqual(try command("/usr/bin/git", ["-C", checkout.path, "symbolic-ref", "-q", "HEAD"], environment), originalBranch)
        _ = try command(python.path, ["-I", "-B", "-c", """
        import json
        from pathlib import Path
        receipt = json.loads(Path(\(String(reflecting: home.appendingPathComponent("logs/update_receipts/latest.json").path))).read_text())
        assert receipt['correlation_id'] == '\(runID)' and receipt['update_id'] == '\(updateID)'
        assert receipt['outcome'] == 'success' and receipt['finished_at']
        assert receipt['pm_sync_outcome'] == 'ok' and receipt['pm_exit_code'] == 0
        assert receipt['post_update']['sha'] == '\(newCommit)' and receipt['post_update']['version'] == '0.21.6'
        # The sandbox forbids launchctl: exit 0 is deliberately insufficient for GO completion.
        assert receipt['gateway_restart']['incomplete'] is True
        assert any(item['step'] == 'gateway_restart' for item in receipt['followups'])
        print('official-identities-and-service-debt-preserved')
        """], environment)
        let newPython = try command(python.path, ["-I", "-S", "-B", "-c", """
        import sys
        from pathlib import Path
        sys.path.insert(0, \(String(reflecting: checkout.path)))
        from hermes_cli._launchers import resolve_store_python
        print(resolve_store_python(Path(\(String(reflecting: checkout.path)))))
        """], environment)
        _ = try command("/usr/bin/sandbox-exec", ["-f", sandbox.path, newPython, "-I", "-B", "-c", """
        import sys
        sys.path.insert(0, \(String(reflecting: checkout.path)))
        from hermes_cli.version_info import get_code_identity
        from hermes_state import SessionDB
        identity = get_code_identity(refresh=True)
        assert identity['sha'] == '\(newCommit)' and identity['version'] == '0.21.6'
        db = SessionDB()
        assert any(m['content'] == '合成升级前历史' for m in db.get_messages('hg199-before'))
        db.create_session('hg199-after', 'cli')
        db.append_message('hg199-after', 'user', '合成升级后新增历史')
        db.close()
        print('new-history-preserved-and-written')
        """], environment)
        let exported = root.appendingPathComponent("exported-databases")
        try backups.exportDatabases(backup, to: exported)
        _ = try command(python.path, ["-I", "-B", "-c", """
        import sqlite3
        from pathlib import Path
        saved = sqlite3.connect(\(String(reflecting: exported.appendingPathComponent("state.db").path)))
        live = sqlite3.connect(\(String(reflecting: home.appendingPathComponent("state.db").path)))
        assert saved.execute('PRAGMA quick_check').fetchone()[0] == 'ok'
        assert saved.execute("SELECT count(*) FROM sessions WHERE id='hg199-before'").fetchone()[0] == 1
        assert saved.execute("SELECT count(*) FROM sessions WHERE id='hg199-after'").fetchone()[0] == 0
        assert live.execute("SELECT count(*) FROM sessions WHERE id='hg199-after'").fetchone()[0] == 1
        print('separate-recovery-keeps-later-writes')
        """], environment)
        XCTAssertEqual(try journal.loadReadOnly()?.phase, .updating)
        print("HG199_ISOLATED_SOURCE_HISTORY_RECOVERY_OK run=\(runID) upstream=\(updateID)")
        // No production certification: sandbox forbids genuine service restart/boot proof.
    }
    private func command(_ executable: String, _ arguments: [String], _ environment: [String: String]) throws -> String {
        let process = Process(), output = Pipe()
        process.executableURL = URL(fileURLWithPath: executable); process.arguments = arguments
        process.environment = environment; process.standardOutput = output; process.standardError = FileHandle.nullDevice
        try process.run()
        let data = output.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw DesktopHermesUpgradeError.verificationFailed }
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

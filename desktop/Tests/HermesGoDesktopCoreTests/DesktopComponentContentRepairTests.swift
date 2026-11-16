import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentContentRepairTests: XCTestCase {
    func testKernelExecutableMustMatchEvenWhenArgvClaimsTrustedNode() {
        let node = "/managed/node/bin/node", script = "/managed/app/connector/dist/index.js"
        var count = Int32(2)
        var bytes = withUnsafeBytes(of: &count) { Array($0) }
        bytes += Array("/other/bin/node\0\0".utf8)
        bytes += Array((node + "\0" + script + "\0").utf8)
        let identity = DesktopConnectorRuntimeIdentity.decodeProcessIdentity(bytes)
        XCTAssertEqual(identity?.arguments, [node, script])
        XCTAssertFalse(DesktopConnectorRuntimeIdentity.matches(executable: identity!.executable,
            actual: identity!.arguments, node: node, script: script))
        XCTAssertTrue(DesktopConnectorRuntimeIdentity.matches(executable: node,
            actual: [node, script], node: node, script: script))
        XCTAssertNil(DesktopConnectorRuntimeIdentity.decodeProcessIdentity(Array(bytes.dropLast())))
    }

    private func fixture() throws -> (URL, URL, URL, DesktopComponentRepairSnapshot.Entry) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let old = root.appendingPathComponent("old"), new = root.appendingPathComponent("new"), backup = root.appendingPathComponent("quarantine")
        for (url, value) in [(old, "damaged"), (new, "verified")] {
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
            try Data(value.utf8).write(to: url.appendingPathComponent("binary"))
        }
        let entry = DesktopComponentRepairSnapshot.Entry(kind: .connector, contentHash: String(repeating: "a", count: 64),
            beforeDigest: try DesktopAppUpdateTransaction.digest(old), candidateDigest: try DesktopAppUpdateTransaction.digest(new), source: new)
        return (old, new, backup, entry)
    }
    func testExactReplacementKeepsDamagedBytesInQuarantine() throws {
        let (old, _, backup, entry) = try fixture()
        try DesktopComponentContentRepair.replace(entry: entry, destination: old, backup: backup)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(old), entry.candidateDigest)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(backup), entry.beforeDigest)
        // Replaying an exact completed file step is inert.
        try DesktopComponentContentRepair.replace(entry: entry, destination: old, backup: backup)
    }
    func testChangedCandidateCannotTouchLiveFiles() throws {
        let (old, new, backup, entry) = try fixture()
        try Data("tampered".utf8).write(to: new.appendingPathComponent("binary"))
        XCTAssertThrowsError(try DesktopComponentContentRepair.replace(entry: entry, destination: old, backup: backup))
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(old), entry.beforeDigest)
    }
    func testExternalLiveChangeCannotBeOverwritten() throws {
        let (old, _, backup, entry) = try fixture()
        try Data("external".utf8).write(to: old.appendingPathComponent("binary"))
        XCTAssertThrowsError(try DesktopComponentContentRepair.replace(entry: entry, destination: old, backup: backup))
        XCTAssertEqual(try String(contentsOf: old.appendingPathComponent("binary")), "external")
    }
    func testInterruptedRenameResumesOnlyWithMatchingQuarantine() throws {
        let (old, _, backup, entry) = try fixture()
        try FileManager.default.moveItem(at: old, to: backup)
        try DesktopComponentContentRepair.replace(entry: entry, destination: old, backup: backup)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(old), entry.candidateDigest)
        XCTAssertEqual(try DesktopAppUpdateTransaction.digest(backup), entry.beforeDigest)
    }
    func testMissingOldContentAndQuarantineIsAmbiguous() throws {
        let (old, _, backup, entry) = try fixture()
        try FileManager.default.removeItem(at: old)
        XCTAssertThrowsError(try DesktopComponentContentRepair.replace(entry: entry, destination: old, backup: backup))
    }
    func testPendingRepairBlocksOrdinaryMigrationLease() throws {
        let (root, _, _, _) = try fixture()
        let state = root.appendingPathComponent("state")
        let store = try DesktopMigrationJournalStore(root: state)
        do { let lease = try store.acquireOperationLease(); withExtendedLifetime(lease) {} }
        try Data("pending".utf8).write(to: state.appendingPathComponent("component-content-repair.json"))
        XCTAssertThrowsError(try store.acquireOperationLease())
        let recoveryLease = try store.acquireOperationLease(allowComponentRepair: true)
        withExtendedLifetime(recoveryLease) {}
    }
    func testRuntimeProofRequiresExactExecutableAndConnectorScript() {
        XCTAssertTrue(DesktopConnectorRuntimeIdentity.matches(actual: ["/node/bin/node", "/connector/app/connector/dist/index.js"],
            node: "/node/bin/node", script: "/connector/app/connector/dist/index.js"))
        for args in [["/other/node", "/connector/app/connector/dist/index.js"],
                     ["/node/bin/node", "/old/app/connector/dist/index.js"],
                     ["/node/bin/node", "--require", "hook.js", "/connector/app/connector/dist/index.js"]] {
            XCTAssertFalse(DesktopConnectorRuntimeIdentity.matches(actual: args,
                node: "/node/bin/node", script: "/connector/app/connector/dist/index.js"))
        }
    }
}

import Foundation
import SQLite3
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeBackupTests: XCTestCase {
    func testWALCommitsAndPrivateConfigurationAreBackedUpAndNewWritesAreNeverOverwritten() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let live = try fixture.openDatabase()
        defer { sqlite3_close(live) }
        try execute(live, "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0; CREATE TABLE sessions(id TEXT); INSERT INTO sessions VALUES('before');")
        try Data("PRIVATE_TEST_CREDENTIAL".utf8).write(to: fixture.home.appendingPathComponent(".env"))
        try FileManager.default.createDirectory(at: fixture.home.appendingPathComponent("cron"), withIntermediateDirectories: true)
        try Data("fixture cron".utf8).write(to: fixture.home.appendingPathComponent("cron/jobs.json"))
        var checks = 0
        let receipt = try fixture.store.create(home: fixture.home, runID: fixture.runID) { checks += 1 }
        XCTAssertEqual(checks, 2)
        XCTAssertEqual(try fixture.store.create(home: fixture.home, runID: fixture.runID) {}, receipt)
        try fixture.store.verify(receipt)
        let saved = fixture.store.root.appendingPathComponent(fixture.runID + "/home")
        XCTAssertEqual(try String(contentsOf: saved.appendingPathComponent(".env")), "PRIVATE_TEST_CREDENTIAL")
        XCTAssertEqual(count(saved.appendingPathComponent("state.db")), 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: saved.appendingPathComponent("state.db-wal").path))
        let attrs = try FileManager.default.attributesOfItem(atPath: saved.appendingPathComponent(".env").path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        try execute(live, "INSERT INTO sessions VALUES('after');")
        XCTAssertThrowsError(try fixture.store.create(home: fixture.home, runID: fixture.runID) {})
        let recovery = fixture.root.appendingPathComponent("recovered")
        try fixture.store.exportDatabases(receipt, to: recovery)
        XCTAssertEqual(count(recovery.appendingPathComponent("state.db")), 1)
        XCTAssertEqual(count(fixture.home.appendingPathComponent("state.db")), 2)
        XCTAssertThrowsError(try fixture.store.exportDatabases(receipt, to: fixture.home))
        XCTAssertThrowsError(try fixture.store.exportDatabases(receipt, to: recovery))
        XCTAssertEqual(count(fixture.home.appendingPathComponent("state.db")), 2)
    }

    func testWriterOrConfigChangeDuringSnapshotInvalidatesBackup() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let config = fixture.home.appendingPathComponent("config.yaml")
        try Data("before".utf8).write(to: config)
        var checks = 0
        XCTAssertThrowsError(try fixture.store.create(home: fixture.home, runID: fixture.runID) {
            checks += 1
            if checks == 2 { try Data("new writes".utf8).write(to: config) }
        })
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.store.root.appendingPathComponent(fixture.runID).path))
    }

    func testWritersNotStoppedHaveNoBackupEffects() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        XCTAssertThrowsError(try fixture.store.create(home: fixture.home, runID: fixture.runID) {
            throw DesktopHermesUpgradeError.preflightBlocked
        })
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.store.root.path))
    }

    func testTamperedBackupAndUnsafeLocationAreRejected() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        try Data("before".utf8).write(to: fixture.home.appendingPathComponent("config.yaml"))
        let receipt = try fixture.store.create(home: fixture.home, runID: fixture.runID) {}
        let saved = fixture.store.root.appendingPathComponent(fixture.runID + "/home/config.yaml")
        try Data("tampered".utf8).write(to: saved)
        XCTAssertThrowsError(try fixture.store.verify(receipt))
        XCTAssertThrowsError(try fixture.store.exportDatabases(receipt, to: fixture.root.appendingPathComponent("recovery")))
        let inside = DesktopHermesUpgradeBackupStore(root: fixture.home.appendingPathComponent("backups"))
        XCTAssertThrowsError(try inside.create(home: fixture.home, runID: UUID().uuidString) {})
    }

    func testLinksArePreservedAsLinksAndNeverFollowed() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let foreign = fixture.root.appendingPathComponent("foreign.txt")
        try Data("must not copy".utf8).write(to: foreign)
        try FileManager.default.createSymbolicLink(atPath: fixture.home.appendingPathComponent("alias").path, withDestinationPath: foreign.path)
        let receipt = try fixture.store.create(home: fixture.home, runID: fixture.runID) {}
        try fixture.store.verify(receipt)
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath:
            fixture.store.root.appendingPathComponent(fixture.runID + "/home/alias").path), foreign.path)
    }

    private func execute(_ database: OpaquePointer?, _ sql: String) throws {
        guard sqlite3_exec(database, sql, nil, nil, nil) == SQLITE_OK else {
            throw DesktopHermesUpgradeError.verificationFailed
        }
    }
    private func count(_ url: URL) -> Int {
        var database: OpaquePointer?, statement: OpaquePointer?
        defer { sqlite3_finalize(statement); sqlite3_close(database) }
        guard sqlite3_open_v2(url.path, &database, SQLITE_OPEN_READONLY, nil) == SQLITE_OK,
              sqlite3_prepare_v2(database, "SELECT COUNT(*) FROM sessions", -1, &statement, nil) == SQLITE_OK,
              sqlite3_step(statement) == SQLITE_ROW else { return -1 }
        return Int(sqlite3_column_int(statement, 0))
    }
}
private struct Fixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    let runID = UUID().uuidString
    var home: URL { root.appendingPathComponent(".hermes") }
    var store: DesktopHermesUpgradeBackupStore { .init(root: root.appendingPathComponent("backups")) }
    init() throws { try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
    func remove() { try? FileManager.default.removeItem(at: root) }
    func openDatabase() throws -> OpaquePointer? {
        var database: OpaquePointer?
        guard sqlite3_open(home.appendingPathComponent("state.db").path, &database) == SQLITE_OK else {
            sqlite3_close(database); throw DesktopHermesUpgradeError.verificationFailed
        }
        return database
    }
}

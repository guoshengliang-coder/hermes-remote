import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedSchemaDriftTests: XCTestCase {
    /// The 2026-09-19 incident, as data. These are the real column names and the real tables.
    func testReportsTheColumnsThatCausedTheIncident() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id", "session_id", "role", "content"]],
            live: ["messages": ["id", "session_id", "role", "content", "display_identity", "display_order"]]
        )

        XCTAssertTrue(drift.hasDrift)
        XCTAssertEqual(drift.summary, "messages: display_identity, display_order")
    }

    func testMatchingSchemasReportNothing() {
        let columns = ["id", "session_id", "role", "content"]
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": columns],
            live: ["messages": columns]
        )

        XCTAssertFalse(drift.hasDrift)
        XCTAssertEqual(drift.summary, "")
    }

    /// The opposite direction is not this check's business: the managed copy would simply read
    /// nothing there, which is a different problem and not one the owner can fix by updating.
    func testAColumnMissingFromTheDatabaseIsNotDrift() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id", "content", "api_content"]],
            live: ["messages": ["id", "content"]]
        )

        XCTAssertFalse(drift.hasDrift)
    }

    /// A table the baseline does not mention is skipped rather than reported wholesale — otherwise
    /// every column of every unwatched table would surface the first time Desktop looked.
    func testTablesOutsideTheBaselineAreIgnored() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id"]],
            live: ["messages": ["id"], "cron_jobs": ["id", "schedule"]]
        )

        XCTAssertFalse(drift.hasDrift)
    }

    func testSummaryIsStableAcrossTablesAndColumns() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["sessions": ["id"], "messages": ["id"]],
            live: ["sessions": ["id", "transport_profile"], "messages": ["id", "display_order", "display_identity"]]
        )

        // Sorted both ways: a summary that reorders between launches is one nobody can compare.
        XCTAssertEqual(
            drift.summary,
            "messages: display_identity, display_order; sessions: transport_profile"
        )
    }

    func testInspectorReadsTheBaselineAndTheLiveSchema() throws {
        let directory = try makeTemporaryDirectory()
        let identity = directory.appendingPathComponent("BUILD-IDENTITY.json")
        try #"{"schemaVersion":1,"schemaBaseline":{"messages":["id","content"]}}"#
            .write(to: identity, atomically: true, encoding: .utf8)

        let inspector = DesktopManagedSchemaInspector(
            identityURL: identity,
            databaseURL: directory.appendingPathComponent("state.db"),
            runner: { _, arguments in
                XCTAssertTrue(arguments.contains("-readonly"), "the owner's database is never opened for writing")
                return "0|id|INTEGER|0||1\n1|content|TEXT|0||0\n2|display_identity|BLOB|0||0\n"
            }
        )

        let drift = try XCTUnwrap(inspector.inspect())
        XCTAssertEqual(drift.summary, "messages: display_identity")
    }

    /// A release built before this existed has no baseline. Silence is the right answer for one of
    /// those; a false alarm on every older installation would teach people to ignore the real one.
    func testAMissingBaselineIsSilentRatherThanAlarming() throws {
        let directory = try makeTemporaryDirectory()
        let identity = directory.appendingPathComponent("BUILD-IDENTITY.json")
        try #"{"schemaVersion":1,"component":"hermes_server"}"#
            .write(to: identity, atomically: true, encoding: .utf8)

        let inspector = DesktopManagedSchemaInspector(
            identityURL: identity,
            databaseURL: directory.appendingPathComponent("state.db"),
            runner: { _, _ in XCTFail("the database must not be read without a baseline"); return nil }
        )

        XCTAssertNil(inspector.inspect())
    }

    func testAnUnreadableDatabaseIsSilentRatherThanAlarming() throws {
        let directory = try makeTemporaryDirectory()
        let identity = directory.appendingPathComponent("BUILD-IDENTITY.json")
        try #"{"schemaBaseline":{"messages":["id"]}}"#
            .write(to: identity, atomically: true, encoding: .utf8)

        let inspector = DesktopManagedSchemaInspector(
            identityURL: identity,
            databaseURL: directory.appendingPathComponent("state.db"),
            runner: { _, _ in nil }
        )

        XCTAssertNil(inspector.inspect())
    }

    private func makeTemporaryDirectory() throws -> URL {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("schema-drift-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }
}

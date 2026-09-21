import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedSchemaDriftTests: XCTestCase {
    /// The 2026-09-19 incident, as data. These are the real column names and the real tables.
    func testReportsTheColumnsThatCausedTheIncident() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id", "session_id", "role", "content"]],
            live: ["messages": [c("id"), c("session_id"), c("role"), c("content"), c("display_identity"), c("display_order")]]
        )

        XCTAssertTrue(drift.hasDrift)
        XCTAssertEqual(drift.summary, "messages: display_identity, display_order")
    }

    func testMatchingSchemasReportNothing() {
        let columns = ["id", "session_id", "role", "content"]
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": columns],
            live: ["messages": columns.map { c($0) }]
        )

        XCTAssertFalse(drift.hasDrift)
        XCTAssertEqual(drift.summary, "")
    }

    /// The opposite direction is not this check's business: the managed copy would simply read
    /// nothing there, which is a different problem and not one the owner can fix by updating.
    func testAColumnMissingFromTheDatabaseIsNotDrift() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id", "content", "api_content"]],
            live: ["messages": [c("id"), c("content")]]
        )

        XCTAssertFalse(drift.hasDrift)
    }

    /// A table the baseline does not mention is skipped rather than reported wholesale — otherwise
    /// every column of every unwatched table would surface the first time Desktop looked.
    func testTablesOutsideTheBaselineAreIgnored() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id"]],
            live: ["messages": [c("id")], "cron_jobs": [c("id"), c("schedule")]]
        )

        XCTAssertFalse(drift.hasDrift)
    }

    func testSummaryIsStableAcrossTablesAndColumns() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["sessions": ["id"], "messages": ["id"]],
            live: ["sessions": [c("id"), c("transport_profile")], "messages": [c("id"), c("display_order"), c("display_identity")]]
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

    /// Local runtime mode: the code that writes `state.db` and the code that reads it are the same
    /// checkout, so the baseline of a bundled release that is not running describes nothing. The
    /// same drifted database that reports above must be silent, and must not even be read.
    func testLocalRuntimeModeIsSilentEvenWithADriftedDatabase() throws {
        let directory = try makeTemporaryDirectory()
        let identity = directory.appendingPathComponent("BUILD-IDENTITY.json")
        try #"{"schemaVersion":1,"schemaBaseline":{"messages":["id","content"]}}"#
            .write(to: identity, atomically: true, encoding: .utf8)

        let inspector = DesktopManagedSchemaInspector(
            identityURL: identity,
            databaseURL: directory.appendingPathComponent("state.db"),
            runner: { _, _ in
                XCTFail("local mode has no second codebase to compare against")
                return "0|id|INTEGER|0||1\n1|content|TEXT|0||0\n2|display_identity|BLOB|0||0\n"
            },
            runtimeMode: { .localHermes(executable: URL(fileURLWithPath: "/Users/o/.hermes/hermes-agent/venv/bin/hermes")) }
        )

        XCTAssertNil(inspector.inspect())
        XCTAssertNil(DesktopIssue.managedSchemaDrift(inspector.inspect()))
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

    /// The paths the app actually hands the inspector. `current` rather than a pinned release
    /// directory, because activation flips that symlink underneath a running Desktop.
    func testDerivesTheInstalledPathsFromTheManagedPaths() throws {
        let home = URL(fileURLWithPath: "/private/tmp/hermes-schema-home")
        let paths = try DesktopManagedBootstrapPaths(homeDirectory: home)
        let inspector = DesktopManagedSchemaInspector(managedPaths: paths)

        XCTAssertTrue(
            inspector.identityURL.path.hasSuffix(
                "Library/Application Support/Hermes Go/Managed/current/hermes_server/BUILD-IDENTITY.json"
            ),
            inspector.identityURL.path
        )
        XCTAssertTrue(inspector.databaseURL.path.hasSuffix(".hermes/state.db"), inspector.databaseURL.path)
    }

    func testDriftBecomesTheRegisteredIssueWithTheColumnsAsTheCause() {
        let issue = DesktopIssue.managedSchemaDrift(
            DesktopManagedSchemaDrift(unknownColumns: ["messages": ["display_order", "display_identity"]])
        )

        XCTAssertEqual(issue?.code, .managedHermesBehindDatabase)
        XCTAssertEqual(issue?.technicalCause, "messages: display_identity, display_order")
        XCTAssertEqual(issue?.retryable, false)
    }

    /// "No drift" and "could not tell" are the same answer to the person reading the menu bar.
    func testNoDriftAndNoAnswerBothProduceNoIssue() {
        XCTAssertNil(DesktopIssue.managedSchemaDrift(nil))
        XCTAssertNil(DesktopIssue.managedSchemaDrift(DesktopManagedSchemaDrift(unknownColumns: [:])))
        XCTAssertNil(
            DesktopIssue.managedSchemaDrift(DesktopManagedSchemaDrift(unknownColumns: ["messages": []]))
        )
    }

    // MARK: - Acknowledged drift

    /// The state this Mac is in on 2026-09-20: three drifted columns, all examined, a release that
    /// carries patch 010. Nothing to tell the owner, so nothing is said.
    func testExaminedColumnsOnAReleaseThatNeutralisesThemAreSilent() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id", "content"], "sessions": ["id"]],
            live: [
                "messages": [c("id"), c("content"), c("display_identity", "BLOB"), c("display_order", "INTEGER")],
                "sessions": [c("id"), c("transport_profile", "TEXT")],
            ],
            acknowledged: DesktopManagedSchemaAcknowledgement.known,
            appliedPatches: ["010-message-dict-drops-unknown-columns.patch"]
        )

        XCTAssertFalse(drift.hasDrift, drift.summary)
    }

    /// The same three columns on a release built without patch 010 — which is exactly the
    /// 2026-09-19 incident. The exemption names the patch, so dropping the patch brings the notice
    /// back rather than leaving a Mac quietly broken.
    func testTheSameColumnsAreReportedWhenThePatchIsAbsent() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["messages": ["id", "content"]],
            live: ["messages": [c("id"), c("content"), c("display_identity", "BLOB")]],
            acknowledged: DesktopManagedSchemaAcknowledgement.known,
            appliedPatches: []
        )

        XCTAssertEqual(drift.summary, "messages: display_identity")
    }

    /// An acknowledgement is about a column of a given type. Upstream turning `transport_profile`
    /// into a BLOB is a new fact, and BLOB is the shape that broke the encoder.
    func testAnAcknowledgedColumnThatChangesTypeIsReportedAgain() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["sessions": ["id"]],
            live: ["sessions": [c("id"), c("transport_profile", "BLOB")]],
            acknowledged: DesktopManagedSchemaAcknowledgement.known
        )

        XCTAssertEqual(drift.summary, "sessions: transport_profile")
    }

    /// The whole point of narrowing the notice: a column nobody has looked at still reports, and
    /// now it reports alone instead of arriving in a list of three that are always there.
    func testAnUnexaminedColumnIsStillReported() {
        let drift = DesktopManagedSchemaDrift.compare(
            baseline: ["sessions": ["id"], "messages": ["id"]],
            live: [
                "sessions": [c("id"), c("transport_profile", "TEXT"), c("routing_blob", "BLOB")],
                "messages": [c("id"), c("display_identity", "BLOB")],
            ],
            acknowledged: DesktopManagedSchemaAcknowledgement.known,
            appliedPatches: ["010-message-dict-drops-unknown-columns.patch"]
        )

        XCTAssertEqual(drift.summary, "sessions: routing_blob")
    }

    /// Every acknowledgement carries a reason, because each one is a decision to stay quiet.
    func testEveryAcknowledgementStatesWhatWasChecked() {
        for entry in DesktopManagedSchemaAcknowledgement.known {
            XCTAssertFalse(entry.expectedType.isEmpty, "\(entry.table).\(entry.column)")
            XCTAssertGreaterThan(entry.reason.count, 60, "\(entry.table).\(entry.column) needs a reason")
        }
    }

    /// End to end through the file and the sqlite3 output, with the patch list the release records.
    func testInspectorAppliesAcknowledgementsUsingTheRecordedPatches() throws {
        let directory = try makeTemporaryDirectory()
        let identity = directory.appendingPathComponent("BUILD-IDENTITY.json")
        try #"""
        {"schemaBaseline":{"messages":["id","content"]},"patches":[{"name":"010-message-dict-drops-unknown-columns.patch","sha256":"x"}]}
        """#
            .write(to: identity, atomically: true, encoding: .utf8)

        let inspector = DesktopManagedSchemaInspector(
            identityURL: identity,
            databaseURL: directory.appendingPathComponent("state.db"),
            runner: { _, _ in "0|id|INTEGER|0||1\n1|content|TEXT|0||0\n2|display_identity|BLOB|0||0\n" }
        )

        let drift = try XCTUnwrap(inspector.inspect())
        XCTAssertFalse(drift.hasDrift, drift.summary)
    }

    private func makeTemporaryDirectory() throws -> URL {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("schema-drift-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        return directory
    }
}

/// Columns as `pragma table_info` hands them over. The type is spelled out wherever a test turns
/// on it, because an acknowledgement is only valid for the type it was made about.
private func c(_ name: String, _ type: String = "TEXT") -> DesktopManagedSchemaColumn {
    DesktopManagedSchemaColumn(name: name, type: type)
}

import XCTest
@testable import HermesGoDesktopCore

/// Asserts that the schema-drift check has a caller.
///
/// HG-71 built the check, registered `HR-MIGRATE-006`, and shipped eight passing tests — and the
/// Mac stayed silent, because `DesktopManagedSchemaInspector` was never called from anywhere. Every
/// one of those tests exercised the mechanism in isolation; none of them could tell that the last
/// link was missing, and neither could a release, because a check that never runs never fails.
///
/// The app target is an `executableTarget`, so SwiftPM cannot import it into a test target and this
/// wiring cannot be asserted by calling it. Reading the source is the weaker check — it proves the
/// call is written, not that it executes — but the alternative on offer is no check at all, and the
/// failure it guards against is precisely "nobody wrote the call".
final class ManagedSchemaWiringTests: XCTestCase {
    func testTheViewModelConstructsAndRunsTheInspector() throws {
        let source = try appSource("DesktopViewModel.swift")

        XCTAssertTrue(
            source.contains("DesktopManagedSchemaInspector(managedPaths:"),
            "DesktopViewModel no longer builds the schema inspector — the drift check cannot run."
        )
        XCTAssertTrue(
            source.contains("await refreshManagedSchemaIssue()"),
            "Nothing calls refreshManagedSchemaIssue(); the check exists but never runs."
        )
        XCTAssertGreaterThanOrEqual(
            source.components(separatedBy: "refreshManagedSchemaIssue()").count - 1, 2,
            "refreshManagedSchemaIssue() is defined but has no call site."
        )
        XCTAssertTrue(
            source.contains("DesktopIssue.managedSchemaDrift("),
            "The drift is never turned into a user-visible issue."
        )
    }

    func testTheIssueReachesTheWindow() throws {
        let source = try appSource("SecondaryViews.swift")

        XCTAssertTrue(
            source.contains("model.managedSchemaIssue"),
            "No view renders managedSchemaIssue, so HR-MIGRATE-006 would never be shown."
        )
    }

    private func appSource(_ name: String) throws -> String {
        let packageRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let url = packageRoot
            .appendingPathComponent("Sources/HermesGoDesktop", isDirectory: true)
            .appendingPathComponent(name)
        // Deliberately a failure and not a skip: a guard that quietly stops running is the same
        // shape of hole as the one it was written for.
        return try String(contentsOf: url, encoding: .utf8)
    }
}

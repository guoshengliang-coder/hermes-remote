import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeReadinessTests: XCTestCase {
    func testPMStartEligibilityIsNotAnUpgradeConditionVerdict() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let installation = DesktopLocalHermesInstallation(executable: fixture.installation.checkoutRoot.appendingPathComponent(".hermes/bin/hermes"),
            checkoutRoot: fixture.installation.checkoutRoot, hermesHome: fixture.installation.hermesHome,
            commit: fixture.installation.commit, version: fixture.installation.version, identityChangedAt: .distantPast,
            installedDistribution: .packageManaged)
        XCTAssertTrue(installation.dependenciesConsistent)
        let report = DesktopHermesUpgradeReadinessChecker(git: GitFixture(state: .clean)).check(
            detection: .usable(installation), target: fixture.target, journal: fixture.store,
            officialUpdateInProgress: false, certifiedTargets: [fixture.target])
        XCTAssertFalse(report.dependenciesVerified)
        XCTAssertEqual(report.issue?.code, .hermesUpdateBlocked)
    }
    func testCertifiedTargetCannotHideUnavailableProfileInspection() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let report = DesktopHermesUpgradeReadinessChecker(git: GitFixture(state: .clean)).check(
            detection: .usable(fixture.installation), target: fixture.target, journal: fixture.store,
            officialUpdateInProgress: false, certifiedTargets: [fixture.target])
        XCTAssertTrue(report.targetCertified)
        XCTAssertFalse(try XCTUnwrap(report.inspection).profileDirectoryScanComplete)
        XCTAssertEqual(report.issue?.code, .hermesUpdateBlocked)
    }
    func testReadinessCollectsObservationWithoutCreatingJournalOrGivingAdmissionAuthority() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let report = DesktopHermesUpgradeReadinessChecker(git: GitFixture(state: .clean)).check(
            detection: .usable(fixture.installation), target: fixture.target, journal: fixture.store,
            officialUpdateInProgress: false)
        let observation = try XCTUnwrap(report.inspection)
        XCTAssertEqual(observation.sourceIdentity, fixture.installation.runtimeIdentity)
        XCTAssertFalse(observation.writerAdmissionProven)
        XCTAssertFalse(observation.taskInactivityProven)
        XCTAssertFalse(observation.profileDirectoryScanComplete)
        XCTAssertEqual(report.issue?.code, .hermesUpdateBlocked)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.root.path))
    }
    func testRealGitInspectorSeesTrackedAndUntrackedChangesWithoutWritingIndex() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try git(["init", "-q", root.path])
        let file = root.appendingPathComponent("fixture.txt")
        try Data("before".utf8).write(to: file)
        let inspector = DesktopHermesUpgradeGitInspector()
        XCTAssertEqual(inspector.workingTree(checkout: root), .dirty)
        try git(["-C", root.path, "add", "fixture.txt"])
        try git(["-C", root.path, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"])
        let index = root.appendingPathComponent(".git/index")
        let before = try FileManager.default.attributesOfItem(atPath: index.path)[.modificationDate] as? Date
        XCTAssertEqual(inspector.workingTree(checkout: root), .clean)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: index.path)[.modificationDate] as? Date, before)
        try Data("after".utf8).write(to: file)
        XCTAssertEqual(inspector.workingTree(checkout: root), .dirty)
        XCTAssertEqual(inspector.workingTree(checkout: root.appendingPathComponent("missing")), .unknown)
    }

    func testReadOnlyConditionsDoNotCertifyLatestAndExplainLocalChanges() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let report = DesktopHermesUpgradeReadinessChecker(git: GitFixture(state: .dirty)).check(
            detection: .usable(fixture.installation), target: fixture.target, journal: fixture.store,
            officialUpdateInProgress: false)
        XCTAssertFalse(report.targetCertified)
        XCTAssertEqual(report.workingTree, .dirty)
        XCTAssertEqual(report.issue?.code, .hermesUpdateBlocked)
        XCTAssertTrue(report.workingTreeSummaryChinese.contains("本地改动"))
        XCTAssertFalse(report.workingTreeSummaryEnglish.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.root.path))
    }

    func testPendingJournalAndOfficialUpdatePreventReadingHalfWrittenCheckout() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        try FileManager.default.createDirectory(at: fixture.root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try Data("{}".utf8).write(to: fixture.store.journalURL)
        let checker = DesktopHermesUpgradeReadinessChecker(git: NoGitReads(), inspector: NoInspectionReads())
        let recovery = checker.check(detection: .usable(fixture.installation), target: fixture.target,
            journal: fixture.store, officialUpdateInProgress: false)
        XCTAssertTrue(recovery.recoveryRequired)
        XCTAssertEqual(recovery.issue?.code, .hermesUpdateRecoveryRequired)
        try FileManager.default.removeItem(at: fixture.store.journalURL)
        let updating = checker.check(detection: .usable(fixture.installation), target: fixture.target,
            journal: fixture.store, officialUpdateInProgress: true)
        XCTAssertTrue(updating.officialUpdateInProgress)
        XCTAssertEqual(updating.workingTree, .unknown)
        XCTAssertEqual(updating.issue?.code, .hermesUpdateBlocked)
    }
    private func git(_ arguments: [String]) throws {
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
        process.arguments = arguments; process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        try process.run(); process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
    }
}
private struct GitFixture: DesktopHermesUpgradeGitInspecting {
    let state: DesktopHermesUpgradeWorkingTree
    func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree { state }
}
private struct NoGitReads: DesktopHermesUpgradeGitInspecting {
    func workingTree(checkout: URL) -> DesktopHermesUpgradeWorkingTree { XCTFail("unsafe git inspection"); return .clean }
}
private struct NoInspectionReads: DesktopHermesUpgradeInspecting {
    func inspect(installation: DesktopLocalHermesInstallation) -> DesktopHermesUpgradeInspection { fatalError("unsafe inspection") }
    func inspect(journal: DesktopHermesUpgradeJournal) -> DesktopHermesUpgradeInspection { fatalError("unsafe inspection") }
}
private struct Fixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
    var store: DesktopHermesUpgradeJournalStore { .init(root: root) }
    var target: DesktopHermesUpgradeTarget { try! .init(version: "0.21.6", commit: String(repeating: "b", count: 40)) }
    var installation: DesktopLocalHermesInstallation {
        .init(executable: root.appendingPathComponent(".hermes/hermes-agent/venv/bin/hermes"),
              checkoutRoot: root.appendingPathComponent(".hermes/hermes-agent"), hermesHome: root.appendingPathComponent(".hermes"),
              commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
    }
    init() throws {}
    func remove() { try? FileManager.default.removeItem(at: root) }
}

import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeInspectionTests: XCTestCase {
    func testNativeKernelFindsDisposableProcessWithoutRetainingArgsOrEnvironment() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        guard FileManager.default.isExecutableFile(atPath: "/usr/bin/python3") else { throw XCTSkip("system Python unavailable") }
        let child = Process()
        child.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        child.arguments = ["-c", "import time; time.sleep(20)", f.installation.checkoutRoot.appendingPathComponent("fixture.py").path,
                           "--token", "synthetic-command-secret"]
        child.environment = ["PATH": "/usr/bin:/bin", "FIXTURE_SECRET": "synthetic-environment-secret"]
        child.standardOutput = FileHandle.nullDevice; child.standardError = FileHandle.nullDevice
        try child.run()
        defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
        let reader = UpgradeKernelProcesses()
        let identities = try XCTUnwrap(reader.identities())
        let identity = try XCTUnwrap(identities.first { $0.pid == child.processIdentifier })
        XCTAssertEqual(identity.startedAt, DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.startDate(pid: child.processIdentifier))
        let inspector = DesktopHermesUpgradeInspector()
        let report = inspector.inspect(installation: f.installation)
        XCTAssertTrue(report.candidates.contains { $0.identity == identity && $0.association == .checkout })
        let serialized = String(decoding: try JSONEncoder().encode(report), as: UTF8.self)
        for value in ["synthetic-command-secret", "synthetic-environment-secret", "FIXTURE_SECRET", f.root.path, "time.sleep"] {
            XCTAssertFalse(serialized.contains(value))
        }
        XCTAssertFalse(report.writerAdmissionProven)
        XCTAssertFalse(report.taskInactivityProven)
        XCTAssertFalse(report.dependencyRecoveryProven)
        child.terminate(); child.waitUntilExit()
        XCTAssertFalse(inspector.inspect(installation: f.installation).candidates.contains { $0.identity.pid == child.processIdentifier })
    }
    func testKernelArgDecoderStopsAtArgcPreservesEmptyArgumentsAndIgnoresInvalidEnvironmentBytes() throws {
        let data = argv(["/usr/bin/python3", "", "-m", "hermes_cli", "--profile", "work"], environment: Data([0xff, 0, 1]))
        let command = try XCTUnwrap(UpgradeKernelProcesses.decode(data))
        XCTAssertEqual(command.arguments, ["/usr/bin/python3", "", "-m", "hermes_cli", "--profile", "work"])
        XCTAssertEqual(command.executable, "/usr/bin/python3")
    }
    func testKernelArgDecoderRejectsTruncatedInvalidAndUnboundedArgv() {
        XCTAssertNil(UpgradeKernelProcesses.decode(Data([1, 0])))
        for count: Int32 in [0, -1, 4097] {
            var n = count; let data = withUnsafeBytes(of: &n) { Data($0) } + Data("/bin/python\0\0/bin/python\0".utf8)
            XCTAssertNil(UpgradeKernelProcesses.decode(data))
        }
        XCTAssertNil(UpgradeKernelProcesses.decode(argv(["python", "arg"]).dropLast()))
        XCTAssertNil(UpgradeKernelProcesses.decode(Data(repeating: 0, count: 1_048_577)))
    }
    func testNoCandidatesCannotProveAdmissionTasksOrDependencyRecovery() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        let report = f.inspect(rows: [process(10)], commands: [10: .init(executable: "/bin/sleep", arguments: ["sleep", "1"])])
        XCTAssertTrue(report.candidates.isEmpty)
        XCTAssertTrue(report.processSampleStable)
        XCTAssertEqual(report.profileDirectories, ["default"])
        XCTAssertFalse(report.writerAdmissionProven)
        XCTAssertFalse(report.taskInactivityProven)
        XCTAssertFalse(report.dependencyRecoveryProven)
        // This method adds a negative check only, after an independent fence/retirement proof.
        XCTAssertNoThrow(try report.requireNoObservedWriters(for: f.journal))
    }
    func testUnknownArgsAndUnavailableOrChangingKernelSamplesBlockRetirement() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        XCTAssertThrowsError(try f.inspect(rows: [process(10)], commands: [:]).requireNoObservedWriters(for: f.journal))
        let missing = DesktopHermesUpgradeInspector(processes: InspectionProcesses(before: nil, after: nil, commands: [:]))
        XCTAssertThrowsError(try missing.inspect(journal: f.journal).requireNoObservedWriters(for: f.journal))
        let reused = InspectionProcesses(before: [process(10)], after: [process(10, birth: 2000)],
            commands: [10: .init(executable: "/bin/sleep", arguments: ["sleep"])])
        XCTAssertFalse(DesktopHermesUpgradeInspector(processes: reused).inspect(journal: f.journal).processSampleStable)
    }
    func testDescendantsOutsideCheckoutRemainCandidates() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        let rows = [process(10), process(20, parent: 10), process(30, parent: 20)]
        let report = f.inspect(rows: rows, commands: [10: .init(executable: "/bin/python", arguments: [f.installation.executable.path]),
            20: .init(executable: "/bin/sh", arguments: ["sh"]), 30: .init(executable: "/bin/sleep", arguments: ["sleep"])])
        XCTAssertEqual(report.candidates.map { $0.identity.pid }, [10, 20, 30])
        XCTAssertEqual(report.candidates.map(\.association), [.checkout, .descendant, .descendant])
        XCTAssertThrowsError(try report.requireNoObservedWriters(for: f.journal))
    }
    func testUnscopedModuleAndExplicitSelectorsAreObservationsNotDefaultHome() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        for args in [["python", "-m", "hermes_cli"], ["python", "-m", "hermes_cli", "--profile", "../private"],
                     ["python", "-m", "hermes_cli", "-p", "one", "-p", "two"]] {
            let report = f.inspect(rows: [process(10)], commands: [10: .init(executable: "/bin/python", arguments: args)])
            XCTAssertEqual(report.candidates.first?.association, .unscopedHermes)
            XCTAssertNil(report.candidates.first?.profile)
        }
        let report = f.inspect(rows: [process(10)], commands: [10: .init(executable: "/usr/local/bin/hermes", arguments: ["hermes", "--profile=work"])])
        XCTAssertEqual(report.candidates.first?.profile, "work")
        XCTAssertThrowsError(try report.requireOnlyOriginalServices(for: f.journal))
    }
    func testOnlyExactOriginalServiceBirthIsAllowedBeforeStopping() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        let report = f.inspect(rows: [process(10)], commands: [10: .init(executable: "/bin/python", arguments: [f.installation.executable.path])])
        let good = f.journal(services: [.init(label: DesktopManagedInstallLayout.hermesLabel, wasLoaded: true,
            configurationDigest: String(repeating: "a", count: 64), processID: 10, processStartedAt: process(10).startedAt)])
        XCTAssertNoThrow(try report.requireOnlyOriginalServices(for: good))
        let reused = f.journal(services: [.init(label: DesktopManagedInstallLayout.hermesLabel, wasLoaded: true,
            configurationDigest: String(repeating: "a", count: 64), processID: 10, processStartedAt: process(10, birth: 2000).startedAt)])
        XCTAssertThrowsError(try report.requireOnlyOriginalServices(for: reused))
        XCTAssertThrowsError(try report.requireNoObservedWriters(for: good))
    }
    func testFreshnessAndSourceBindingCannotBeReusedForAnotherInstallation() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        let report = f.inspect(rows: [process(10)], commands: [10: .init(executable: "/bin/sleep", arguments: ["sleep"])])
        XCTAssertThrowsError(try report.requireNoObservedWriters(for: f.journal, now: report.checkedAt.addingTimeInterval(6)))
        XCTAssertThrowsError(try report.requireNoObservedWriters(for: f.journal, now: report.checkedAt.addingTimeInterval(-1)))
        let other = try InspectionFixture(); defer { other.remove() }
        XCTAssertThrowsError(try report.requireNoObservedWriters(for: other.journal))
    }
    func testProfileDirectoriesIncludeGhostShellsButNeverReadContents() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        try f.profile("work"); try f.profile("ghost")
        let privateFile = f.home.appendingPathComponent("profiles/work/.env")
        try Data("synthetic-profile-secret".utf8).write(to: privateFile)
        let before = try Data(contentsOf: privateFile)
        let result = UpgradeProfileDirectories.inspect(home: f.home)
        XCTAssertEqual(result.names, ["default", "ghost", "work"])
        XCTAssertTrue(result.complete)
        XCTAssertEqual(try Data(contentsOf: privateFile), before)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.home.appendingPathComponent("profiles/ghost/config.yaml").path))
    }
    func testUnsafeProfileLinksNamesAndWritableDirectoriesAreIncompleteWithoutRepair() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        try f.profile("work")
        let external = f.root.appendingPathComponent("external"); try FileManager.default.createDirectory(at: external, withIntermediateDirectories: false)
        try FileManager.default.createSymbolicLink(at: f.home.appendingPathComponent("profiles/alias"), withDestinationURL: external)
        XCTAssertFalse(UpgradeProfileDirectories.inspect(home: f.home).complete)
        try FileManager.default.removeItem(at: f.home.appendingPathComponent("profiles/alias"))
        try f.profile("bad\n")
        XCTAssertFalse(UpgradeProfileDirectories.inspect(home: f.home).complete)
        try FileManager.default.removeItem(at: f.home.appendingPathComponent("profiles/bad\n"))
        try FileManager.default.setAttributes([.posixPermissions: 0o777], ofItemAtPath: f.home.appendingPathComponent("profiles/work").path)
        XCTAssertFalse(UpgradeProfileDirectories.inspect(home: f.home).complete)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: f.home.appendingPathComponent("profiles/work").path)[.posixPermissions] as? Int, 0o777)
    }
    func testProfileRootLinkAndBoundedListingDoNotFollowOrRecurse() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        let external = f.root.appendingPathComponent("external"); try FileManager.default.createDirectory(at: external, withIntermediateDirectories: false)
        try FileManager.default.createSymbolicLink(at: f.home.appendingPathComponent("profiles"), withDestinationURL: external)
        XCTAssertFalse(UpgradeProfileDirectories.inspect(home: f.home).complete)
        try FileManager.default.removeItem(at: f.home.appendingPathComponent("profiles"))
        for index in 0..<129 { try f.profile("p\(index)") }
        XCTAssertFalse(UpgradeProfileDirectories.inspect(home: f.home).complete)
    }
    func testLegacyExternalInterpreterIsObservedWithoutExecutingAndNotClaimedRecoverable() throws {
        let f = try InspectionFixture(); defer { f.remove() }
        let bin = f.installation.executable.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: bin.appendingPathComponent("python"), withDestinationURL: URL(fileURLWithPath: "/usr/bin/python3"))
        let report = f.inspect(rows: [process(10)], commands: [10: .init(executable: "/bin/sleep", arguments: ["sleep"])])
        XCTAssertEqual(report.interpreter, .outsideHome)
        XCTAssertFalse(report.dependencyRecoveryProven)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.home.appendingPathComponent("state.db").path))
    }
    private func argv(_ arguments: [String], environment: Data = Data()) -> Data {
        var count = Int32(arguments.count)
        var data = withUnsafeBytes(of: &count) { Data($0) }
        data.append(Data("/usr/bin/python3\0\0\0".utf8))
        for arg in arguments { data.append(Data(arg.utf8)); data.append(0) }
        data.append(environment); return data
    }
}
private func process(_ pid: Int32, parent: Int32 = 1, birth: Double = 1000) -> DesktopHermesUpgradeInspection.ProcessIdentity {
    .init(pid: pid, parentPID: parent, startedAt: Date(timeIntervalSince1970: birth))
}
private final class InspectionProcesses: UpgradeProcessReading, @unchecked Sendable {
    let before: [DesktopHermesUpgradeInspection.ProcessIdentity]?
    let after: [DesktopHermesUpgradeInspection.ProcessIdentity]?
    let commands: [Int32: UpgradeProcessCommand]
    let mutex = NSLock(); var calls = 0
    init(before: [DesktopHermesUpgradeInspection.ProcessIdentity]?, after: [DesktopHermesUpgradeInspection.ProcessIdentity]?, commands: [Int32: UpgradeProcessCommand]) {
        self.before = before; self.after = after; self.commands = commands
    }
    func identities() -> [DesktopHermesUpgradeInspection.ProcessIdentity]? { mutex.withLock { calls += 1; return calls == 1 ? before : after } }
    func command(pid: Int32) -> UpgradeProcessCommand? { commands[pid] }
}
private struct InspectionFixture {
    let root: URL; let home: URL
    let installation: DesktopLocalHermesInstallation
    var journal: DesktopHermesUpgradeJournal { journal(services: []) }
    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("hg199-inspect-" + UUID().uuidString).resolvingSymlinksInPath()
        home = root.appendingPathComponent("home")
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        installation = .init(executable: home.appendingPathComponent("hermes-agent/venv/bin/hermes"), checkoutRoot: home.appendingPathComponent("hermes-agent"),
            hermesHome: home, commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: .distantPast)
    }
    func inspect(rows: [DesktopHermesUpgradeInspection.ProcessIdentity], commands: [Int32: UpgradeProcessCommand]) -> DesktopHermesUpgradeInspection {
        DesktopHermesUpgradeInspector(processes: InspectionProcesses(before: rows, after: rows, commands: commands)).inspect(installation: installation)
    }
    func journal(services: [DesktopHermesUpgradeService]) -> DesktopHermesUpgradeJournal {
        .init(target: try! .init(version: "0.21.6", commit: String(repeating: "b", count: 40)), installation: installation, services: services, originalChannel: "stable")
    }
    func profile(_ name: String) throws {
        try FileManager.default.createDirectory(at: home.appendingPathComponent("profiles/" + name), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}

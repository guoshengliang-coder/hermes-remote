import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

// MARK: - Detection

final class DesktopLocalHermesDetectorTests: XCTestCase {
    func testNoCheckoutIsAbsentAndSaysWhetherHermesDataExists() throws {
        let home = try LocalHermesHome(installed: false)
        XCTAssertEqual(home.detect(), .absent(hermesDataPresent: false))

        try home.write(".hermes/state.db", "")
        XCTAssertEqual(home.detect(), .absent(hermesDataPresent: true))
    }

    func testTheStandardInstallIsUsableAndIdentifiedFromGitFilesAlone() throws {
        let home = try LocalHermesHome()
        let changed = Date(timeIntervalSince1970: 1_790_000_000)
        try home.touch(".hermes/hermes-agent/.git/HEAD", Date(timeIntervalSince1970: 1_780_000_000))
        try home.touch(".hermes/hermes-agent/.git/refs/heads/main", changed)

        let installation = try XCTUnwrap(home.detect().installation)

        XCTAssertEqual(installation.commit, LocalHermesHome.commit)
        XCTAssertEqual(installation.version, "0.21.3")
        XCTAssertEqual(installation.executable.path, home.path(".hermes/hermes-agent/venv/bin/hermes"))
        XCTAssertEqual(installation.hermesHome.path, home.path(".hermes"))
        XCTAssertEqual(installation.identityChangedAt.timeIntervalSince1970, changed.timeIntervalSince1970, accuracy: 1)
    }

    func testAPackedRefIsResolved() throws {
        let home = try LocalHermesHome()
        try FileManager.default.removeItem(atPath: home.path(".hermes/hermes-agent/.git/refs/heads/main"))
        try home.write(
            ".hermes/hermes-agent/.git/packed-refs",
            "# pack-refs with: peeled fully-peeled sorted\n"
                + "\(String(repeating: "d", count: 40)) refs/heads/feature\n"
                + "\(LocalHermesHome.commit) refs/heads/main\n"
        )

        XCTAssertEqual(home.detect().installation?.commit, LocalHermesHome.commit)
    }

    func testADetachedHeadIsItsOwnCommit() throws {
        let home = try LocalHermesHome()
        let detached = String(repeating: "e", count: 40)
        try home.write(".hermes/hermes-agent/.git/HEAD", detached + "\n")

        XCTAssertEqual(home.detect().installation?.commit, detached)
    }

    func testTheUpstreamShimInLocalBinIsAccepted() throws {
        let home = try LocalHermesHome()
        try home.write(".local/bin/hermes", """
            #!/usr/bin/env bash
            unset PYTHONPATH
            exec "\(home.path(".hermes/hermes-agent/venv/bin/python"))" "\(home.path(".hermes/hermes-agent/hermes"))" "$@"
            """, mode: 0o755)

        XCTAssertNotNil(home.detect().installation)
    }

    func testASymlinkIntoTheCheckoutIsAccepted() throws {
        let home = try LocalHermesHome()
        try FileManager.default.createDirectory(atPath: home.path("sys"), withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(
            atPath: home.path("sys/hermes"),
            withDestinationPath: home.path(".hermes/hermes-agent/venv/bin/hermes")
        )

        XCTAssertNotNil(home.detect().installation)
    }

    func testASecondEntrypointElsewhereIsNotGuessedAt() throws {
        let home = try LocalHermesHome()
        try home.write("sys/hermes", "#!/bin/sh\nexec /opt/elsewhere/hermes \"$@\"\n", mode: 0o755)

        XCTAssertEqual(home.reason(), .nonStandardLocation)
    }

    func testAPipxInstallIsAnotherCopyEvenWithoutTheCheckout() throws {
        let home = try LocalHermesHome(installed: false)
        try FileManager.default.createDirectory(
            atPath: home.path(".local/pipx/venvs/hermes-agent"),
            withIntermediateDirectories: true
        )

        XCTAssertEqual(home.reason(), .nonStandardLocation)
    }

    func testProfilesAreSurfaced() throws {
        let home = try LocalHermesHome()
        try FileManager.default.createDirectory(atPath: home.path(".hermes/profiles/work"), withIntermediateDirectories: true)

        XCTAssertEqual(home.reason(), .multipleProfiles)
    }

    func testANonDefaultActiveProfileIsSurfacedButDefaultIsNot() throws {
        let home = try LocalHermesHome()
        try home.write(".hermes/active_profile", "default\n")
        XCTAssertNotNil(home.detect().installation)

        try home.write(".hermes/active_profile", "work\n")
        XCTAssertEqual(home.reason(), .multipleProfiles)
    }

    func testACustomHermesHomeInTheEnvironmentIsSurfaced() throws {
        let home = try LocalHermesHome()
        XCTAssertEqual(home.reason(environment: ["HERMES_HOME": "/Volumes/Data/hermes"]), .customHermesHome)
        XCTAssertNotNil(home.detect(environment: ["HERMES_HOME": home.path(".hermes")]).installation)
    }

    func testTheOwnersOwnLaunchAgentsDecideWhereHermesRuns() throws {
        let home = try LocalHermesHome()
        // Shaped like the real ai.hermes.gateway agent on the owner's Mac mini.
        try home.writeLaunchAgent("ai.hermes.gateway.plist", [
            "ProgramArguments": [home.path(".hermes/hermes-agent/venv/bin/python"), "-m", "hermes_cli.main", "gateway", "run"],
            "EnvironmentVariables": ["HERMES_HOME": home.path(".hermes")],
        ])
        XCTAssertNotNil(home.detect().installation)

        try home.writeLaunchAgent("ai.hermes.dashboard.plist", [
            "ProgramArguments": [home.path(".hermes/hermes-agent/venv/bin/python"), "-m", "hermes_cli.main", "dashboard"],
            "EnvironmentVariables": ["HERMES_HOME": "/Volumes/Data/hermes"],
        ])
        XCTAssertEqual(home.reason(), .customHermesHome)

        try home.writeLaunchAgent("ai.hermes.dashboard.plist", [
            "ProgramArguments": ["/opt/hermes/bin/python", "-m", "hermes_cli.main", "dashboard"],
        ])
        XCTAssertEqual(home.reason(), .nonStandardLocation)
    }

    func testAMissingVirtualenvIsIncompleteRatherThanAbsent() throws {
        let home = try LocalHermesHome()
        try FileManager.default.removeItem(atPath: home.path(".hermes/hermes-agent/venv/bin/hermes"))

        XCTAssertEqual(home.reason(), .incompleteInstallation)
    }

    func testAGroupWritableEntrypointIsUnsafe() throws {
        let home = try LocalHermesHome()
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o775],
            ofItemAtPath: home.path(".hermes/hermes-agent/venv/bin/hermes")
        )

        XCTAssertEqual(home.reason(), .unsafeInstallation)
    }

    func testAnOlderHermesIsRefused() throws {
        // 0.21.0 is f159e581: its cron ticker gates on the owner's gateway only with >1 profile.
        let home = try LocalHermesHome(version: "0.21.0")
        XCTAssertEqual(home.reason(), .versionTooOld)

        let newer = try LocalHermesHome(version: "0.22.0")
        XCTAssertNotNil(newer.detect().installation)
    }

    func testAnUnreadableHeadIsSurfaced() throws {
        let home = try LocalHermesHome()
        try home.write(".hermes/hermes-agent/.git/HEAD", "ref: refs/heads/../../etc\n")

        XCTAssertEqual(home.reason(), .unreadableIdentity)
    }

    func testTheUpdateMarkerCountsOnlyWhileUpstreamWouldHonourIt() throws {
        let home = try LocalHermesHome()
        let detector = home.detector()
        XCTAssertFalse(detector.updateInProgress())

        try home.write(".hermes/.hermes-update-in-progress", "{\"pid\": 1}")
        XCTAssertTrue(detector.updateInProgress())

        try home.touch(".hermes/.hermes-update-in-progress", Date().addingTimeInterval(-21 * 60))
        XCTAssertFalse(detector.updateInProgress())
    }

    func testVersionOrdering() {
        XCTAssertTrue(DesktopLocalHermesDetector.version("0.21.2", isOlderThan: "0.21.3"))
        XCTAssertTrue(DesktopLocalHermesDetector.version("0.9.9", isOlderThan: "0.21.3"))
        XCTAssertFalse(DesktopLocalHermesDetector.version("0.21.3", isOlderThan: "0.21.3"))
        XCTAssertFalse(DesktopLocalHermesDetector.version("0.21.10", isOlderThan: "0.21.3"))
        XCTAssertFalse(DesktopLocalHermesDetector.version("1.0.0", isOlderThan: "0.21.3"))
    }
}

// MARK: - Planning

final class DesktopHermesRuntimePlannerTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let executable = URL(fileURLWithPath: "/Users/o/.hermes/hermes-agent/venv/bin/hermes")

    private func installation(commit: String = "b", changedSecondsAgo: TimeInterval = 3_600) -> DesktopLocalHermesInstallation {
        DesktopLocalHermesInstallation(
            executable: executable,
            checkoutRoot: URL(fileURLWithPath: "/Users/o/.hermes/hermes-agent"),
            hermesHome: URL(fileURLWithPath: "/Users/o/.hermes"),
            commit: String(repeating: commit, count: 40),
            version: "0.21.3",
            identityChangedAt: now.addingTimeInterval(-changedSecondsAgo)
        )
    }

    private func plan(
        _ detection: DesktopLocalHermesDetection,
        mode: DesktopHermesRuntimeMode = .bundled,
        enabled: Bool = true,
        updateInProgress: Bool = false,
        startedSecondsAgo: TimeInterval? = 10,
        service: DesktopHermesServiceProcess? = nil,
        record: DesktopLocalHermesRuntimeRecord? = nil,
        fallback: Bool = true
    ) -> DesktopHermesRuntimePlan {
        DesktopHermesRuntimePlanner.plan(DesktopHermesRuntimeObservation(
            enabled: enabled,
            detection: detection,
            mode: mode,
            updateInProgress: updateInProgress,
            service: service ?? startedSecondsAgo.map { .running(startedAt: now.addingTimeInterval(-$0)) } ?? .stopped,
            record: record,
            bundledFallbackAvailable: fallback,
            now: now
        ))
    }

    private var local: DesktopHermesRuntimeMode { .localHermes(executable: executable) }

    func testABundledMacWithAUsableHermesSwitches() {
        let usable = installation()
        XCTAssertEqual(plan(.usable(usable)), .switchToLocal(usable))
    }

    func testNothingSwitchesWhileTheSettingIsOff() {
        XCTAssertEqual(plan(.usable(installation()), enabled: false), .keep)
    }

    func testTurningTheSettingOffReturnsALocalMacToBundled() {
        XCTAssertEqual(plan(.usable(installation()), mode: local, enabled: false), .restoreBundled)
        XCTAssertEqual(
            plan(.usable(installation()), mode: local, enabled: false, fallback: false),
            .surface(.localHermesMissingWithoutFallback)
        )
    }

    func testAnUpdateInProgressIsWaitedOut() {
        XCTAssertEqual(plan(.usable(installation()), updateInProgress: true), .wait(.updateInProgress))
        XCTAssertEqual(plan(.usable(installation()), mode: local, updateInProgress: true), .wait(.updateInProgress))
    }

    func testFreshlyMovedCodeIsGivenTimeToSettle() {
        XCTAssertEqual(plan(.usable(installation(changedSecondsAgo: 10))), .wait(.settling))
        XCTAssertEqual(
            plan(.usable(installation(changedSecondsAgo: 10)), mode: local, startedSecondsAgo: 600),
            .wait(.settling)
        )
    }

    func testAServerStartedAfterTheCheckoutMovedIsCurrent() {
        XCTAssertEqual(plan(.usable(installation(changedSecondsAgo: 600)), mode: local, startedSecondsAgo: 300), .keep)
    }

    func testAServerStartedBeforeTheCheckoutMovedIsStale() {
        let usable = installation(changedSecondsAgo: 300)
        XCTAssertEqual(
            plan(.usable(usable), mode: local, startedSecondsAgo: 600),
            .restartLocal(usable, .codeChanged)
        )
        let record = DesktopLocalHermesRuntimeRecord(
            executable: executable.path,
            commit: String(repeating: "a", count: 40),
            version: "0.21.3",
            launchedAt: now.addingTimeInterval(-601)
        )
        XCTAssertEqual(
            plan(.usable(usable), mode: local, startedSecondsAgo: 600, record: record),
            .restartLocal(usable, .codeChanged)
        )
    }

    /// `git pack-refs` moves the timestamp without moving the commit. The record of what Desktop
    /// launched keeps that from costing a restart.
    func testGitHousekeepingOnTheSameCommitIsNotARestart() {
        let usable = installation(changedSecondsAgo: 300)
        let record = DesktopLocalHermesRuntimeRecord(
            executable: executable.path,
            commit: usable.commit,
            version: "0.21.3",
            launchedAt: now.addingTimeInterval(-601)
        )
        XCTAssertEqual(plan(.usable(usable), mode: local, startedSecondsAgo: 600, record: record), .keep)
    }

    func testALoadedJobWithNoProcessIsRestarted() {
        let usable = installation()
        XCTAssertEqual(
            plan(.usable(usable), mode: local, startedSecondsAgo: nil),
            .restartLocal(usable, .notRunning)
        )
    }

    /// A probe that cannot read launchd must never become a restart on every refresh.
    func testAnUnreadableServiceStateChangesNothing() {
        XCTAssertEqual(plan(.usable(installation()), mode: local, service: .unknown), .keep)
    }

    func testLaunchctlPrintIsReadForThePidOrAnExplicitStop() {
        typealias Inspector = DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>
        let started = Date(timeIntervalSince1970: 1_790_000_000)
        XCTAssertEqual(
            Inspector.process(fromLaunchctlPrint: "gui/501/x = {\n\tstate = running\n\tpid = 4242\n}", startDate: { $0 == 4242 ? started : nil }),
            .running(startedAt: started)
        )
        XCTAssertEqual(
            Inspector.process(fromLaunchctlPrint: "gui/501/x = {\n\tstate = not running\n}", startDate: { _ in nil }),
            .stopped
        )
        XCTAssertEqual(Inspector.process(fromLaunchctlPrint: "garbage", startDate: { _ in nil }), .unknown)
        XCTAssertEqual(
            Inspector.process(fromLaunchctlPrint: "\tpid = 4242\n", startDate: { _ in nil }),
            .unknown,
            "a PID whose start time cannot be read is not evidence of anything"
        )
    }

    func testALocalAgentForAnotherExecutableIsRewritten() {
        let usable = installation()
        let other = DesktopHermesRuntimeMode.localHermes(executable: URL(fileURLWithPath: "/Users/x/.hermes/hermes-agent/venv/bin/hermes"))
        XCTAssertEqual(plan(.usable(usable), mode: other), .switchToLocal(usable))
    }

    func testUnsupportedIsShownAndChangesNothingInEitherMode() {
        let unsupported = DesktopLocalHermesDetection.unsupported(.multipleProfiles, detail: "1 profile(s)")
        let expected = DesktopHermesRuntimePlan.surface(.unsupported(.multipleProfiles, detail: "1 profile(s)"))
        XCTAssertEqual(plan(unsupported), expected)
        XCTAssertEqual(plan(unsupported, mode: local), expected)
        XCTAssertFalse(expected.mutates)
    }

    func testAMacWithoutHermesKeepsTheBundledCopy() {
        XCTAssertEqual(plan(.absent(hermesDataPresent: true)), .keep)
    }

    func testALocalMacWhoseHermesWasRemovedGoesBackToBundled() {
        XCTAssertEqual(plan(.absent(hermesDataPresent: true), mode: local), .restoreBundled)
        XCTAssertEqual(
            plan(.absent(hermesDataPresent: true), mode: local, fallback: false),
            .surface(.localHermesMissingWithoutFallback)
        )
    }

    func testAnAgentDesktopDidNotWriteIsNeverTouched() {
        XCTAssertEqual(plan(.usable(installation()), mode: .unrecognised), .keep)
        XCTAssertEqual(plan(.usable(installation()), mode: .absent), .keep)
    }
}

// MARK: - Launcher and LaunchAgent

final class DesktopLocalHermesLaunchTests: XCTestCase {
    private static let token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"

    func testTheLauncherHandsTheTokenFromTheFileToHermesAndExecsIt() throws {
        let sandbox = try LauncherSandbox()
        let result = try sandbox.run(arguments: sandbox.expectedArguments)

        XCTAssertEqual(result.status, 0, result.error)
        XCTAssertEqual(result.output, "\(Self.token)|serve --host 127.0.0.1 --port 9119|unset")
    }

    func testTheLauncherRefusesATokenFileOthersCanRead() throws {
        let sandbox = try LauncherSandbox()
        try FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: sandbox.tokenFile.path)

        let result = try sandbox.run(arguments: sandbox.expectedArguments)

        XCTAssertEqual(result.status, DesktopLocalHermesLauncher.exitConfiguration)
        XCTAssertEqual(result.output, "", "Hermes must not start without a private token")
    }

    func testTheLauncherRefusesAMalformedToken() throws {
        let sandbox = try LauncherSandbox(token: "not a token")
        let result = try sandbox.run(arguments: sandbox.expectedArguments)

        XCTAssertEqual(result.status, DesktopLocalHermesLauncher.exitConfiguration)
        XCTAssertEqual(result.output, "")
    }

    func testTheLauncherIsNotAGeneralPurposeTokenWrapper() throws {
        let sandbox = try LauncherSandbox()
        let result = try sandbox.run(arguments: ["/bin/echo", "serve", "--host", "127.0.0.1", "--port", "9119"])

        XCTAssertEqual(result.status, DesktopLocalHermesLauncher.exitUsage)
        XCTAssertEqual(result.output, "")
    }

    func testTheLauncherRefusesAPathItCannotQuote() {
        XCTAssertThrowsError(try DesktopLocalHermesLauncher.script(
            executable: URL(fileURLWithPath: "/Users/o'brien/.hermes/hermes-agent/venv/bin/hermes")
        ))
    }

    func testTheLaunchAgentRunsTheOwnersHermesWithTheManagedContract() throws {
        let installation = DesktopLocalHermesInstallation(
            executable: URL(fileURLWithPath: "/Users/bs/.hermes/hermes-agent/venv/bin/hermes"),
            checkoutRoot: URL(fileURLWithPath: "/Users/bs/.hermes/hermes-agent"),
            hermesHome: URL(fileURLWithPath: "/Users/bs/.hermes"),
            commit: String(repeating: "1", count: 40),
            version: "0.21.3",
            identityChangedAt: Date()
        )
        let managed = "/Users/bs/Library/Application Support/Hermes Go/Managed"
        let agent = DesktopLocalHermesLaunchAgent(
            launcher: URL(fileURLWithPath: "\(managed)/bin/hermes-local-serve"),
            installation: installation,
            sessionTokenFile: URL(fileURLWithPath: "\(managed)/secrets/hermes-session-token"),
            standardOutput: URL(fileURLWithPath: "\(managed)/logs/hermes-server.log"),
            standardError: URL(fileURLWithPath: "\(managed)/logs/hermes-server.error.log")
        )
        let object = try XCTUnwrap(PropertyListSerialization.propertyList(
            from: agent.encodedPropertyList(), options: [], format: nil
        ) as? [String: Any])

        XCTAssertEqual(object["Label"] as? String, "com.hermesgo.hermes-server")
        let arguments = try XCTUnwrap(object["ProgramArguments"] as? [String])
        XCTAssertEqual(arguments, [
            "\(managed)/bin/hermes-local-serve",
            "/Users/bs/.hermes/hermes-agent/venv/bin/hermes",
            "serve", "--host", "127.0.0.1", "--port", "9119",
        ])
        XCTAssertEqual(object["EnvironmentVariables"] as? [String: String], [
            "HERMES_HOME": "/Users/bs/.hermes",
            "HERMES_DESKTOP": "1",
            "HERMES_SESSION_TOKEN_FILE": "\(managed)/secrets/hermes-session-token",
            "PATH": "/Users/bs/.hermes/hermes-agent/venv/bin:" + DesktopHermesRuntimeContract.searchPath,
            "VIRTUAL_ENV": "/Users/bs/.hermes/hermes-agent/venv",
        ])
        // upstream `hermes update` restarts a launchd job through `launchctl kickstart` only when
        // `shlex.join(ProgramArguments)` contains "hermes serve"; otherwise it kills the process and
        // respawns a detached copy on our port without our token.
        XCTAssertTrue(Self.shlexJoin(arguments).contains("hermes serve"), Self.shlexJoin(arguments))
    }

    /// Python's `shlex.join`, which is what upstream matches against.
    private static func shlexJoin(_ arguments: [String]) -> String {
        arguments.map { value in
            value.range(of: "^[A-Za-z0-9_@%+=:,./-]+$", options: .regularExpression) != nil
                ? value
                : "'" + value.replacingOccurrences(of: "'", with: "'\"'\"'") + "'"
        }.joined(separator: " ")
    }
}

// MARK: - Setting and presentation

final class DesktopLocalHermesPresentationTests: XCTestCase {
    func testTheRuntimeIsOffUnlessTurnedOn() throws {
        let suite = "hermes-local-runtime-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let bundle = Bundle(for: Self.self)

        XCTAssertFalse(DesktopLocalHermesRuntimeSetting.isEnabled(environment: [:], defaults: defaults, bundle: bundle))
        defaults.set(true, forKey: DesktopLocalHermesRuntimeSetting.defaultsKey)
        XCTAssertTrue(DesktopLocalHermesRuntimeSetting.isEnabled(environment: [:], defaults: defaults, bundle: bundle))
        XCTAssertFalse(DesktopLocalHermesRuntimeSetting.isEnabled(
            environment: [DesktopLocalHermesRuntimeSetting.environmentKey: "0"],
            defaults: defaults,
            bundle: bundle
        ))
    }

    func testUnsupportedLocalHermesIsARegisteredNonRetryableIssue() {
        let issue = DesktopIssue(code: .localHermesUnsupported, technicalCause: "reason=multipleProfiles token=abc")

        XCTAssertEqual(issue.code.rawValue, "HR-MIGRATE-008")
        XCTAssertTrue(issue.detailChinese.contains("第二份"))
        XCTAssertTrue(issue.detailEnglish.contains("second copy"))
        XCTAssertFalse(issue.retryable)
        XCTAssertEqual(issue.recoveryAction, .details)
        XCTAssertTrue(issue.displayChinese.contains("HR-MIGRATE-008"))
        XCTAssertTrue(issue.displayEnglish.contains("HR-MIGRATE-008"))
    }

    func testARuntimeFailureIsARegisteredRetryableIssueWithARedactedCause() {
        let issue = DesktopIssue.hermesRuntimeFailure(DesktopMigrationCoordinatorError.hermesHealthTimedOut)

        XCTAssertEqual(issue.code.rawValue, "HR-MIGRATE-009")
        XCTAssertTrue(issue.summaryChinese.contains("本机 Hermes"))
        XCTAssertTrue(issue.summaryEnglish.contains("this Mac's Hermes"))
        XCTAssertTrue(issue.retryable)
        XCTAssertTrue(issue.sanitizedDiagnostic.contains("hermesHealthTimedOut"))

        let secret = DesktopIssue(code: .localHermesRuntimeFailed, technicalCause: "Authorization: Bearer s3cret")
        XCTAssertFalse(secret.sanitizedDiagnostic.contains("s3cret"))
    }

    func testReconciliationOutcomesMapToIssues() {
        XCTAssertNil(DesktopIssue.hermesRuntime(.notInstalled))
        XCTAssertNil(DesktopIssue.hermesRuntime(.unchanged(.keep)))
        XCTAssertNil(DesktopIssue.hermesRuntime(.restoredBundled))
        XCTAssertEqual(
            DesktopIssue.hermesRuntime(.unchanged(.surface(.unsupported(.customHermesHome, detail: "x"))))?.code,
            .localHermesUnsupported
        )
        XCTAssertEqual(
            DesktopIssue.hermesRuntime(.unchanged(.surface(.localHermesMissingWithoutFallback)))?.code,
            .localHermesRuntimeFailed
        )
    }

    /// A fresh install on a Mac that already has Hermes it cannot use would be a second copy.
    func testOnlyAFreshInstallIsBlockedAndOnlyWhenTheRuntimeIsOn() {
        let unsupported = DesktopLocalHermesDetection.unsupported(.multipleProfiles, detail: "x")
        XCTAssertEqual(
            DesktopIssue.localHermesInstallBlock(unsupported, freshInstall: true, localRuntimeEnabled: true)?.code,
            .localHermesUnsupported
        )
        XCTAssertEqual(
            DesktopIssue.localHermesInstallBlock(.absent(hermesDataPresent: true), freshInstall: true, localRuntimeEnabled: true)?.code,
            .localHermesUnsupported
        )
        XCTAssertNil(DesktopIssue.localHermesInstallBlock(unsupported, freshInstall: false, localRuntimeEnabled: true))
        XCTAssertNil(DesktopIssue.localHermesInstallBlock(unsupported, freshInstall: true, localRuntimeEnabled: false))
        XCTAssertNil(DesktopIssue.localHermesInstallBlock(.absent(hermesDataPresent: false), freshInstall: true, localRuntimeEnabled: true))
    }

    func testTheSchemaInspectorBuiltFromManagedPathsIsSilentInLocalMode() throws {
        let home = try LocalHermesHome()
        let paths = try DesktopManagedBootstrapPaths(homeDirectory: home.root)
        let layout = try DesktopManagedInstallLayout(root: paths.managedRoot, launchAgentsRoot: paths.launchAgentsRoot)
        let installer = DesktopManagedInstaller(layout: layout)
        try installer.writeBundledAgentForTesting(layout: layout, hermesHome: paths.hermesHome)
        let identity = layout.currentRelease.appendingPathComponent("hermes_server/BUILD-IDENTITY.json")
        try FileManager.default.createDirectory(at: identity.deletingLastPathComponent(), withIntermediateDirectories: true)
        try #"{"schemaBaseline":{"messages":["id"]}}"#.write(to: identity, atomically: true, encoding: .utf8)
        let drifted: @Sendable (String, [String]) -> String? = { _, _ in "0|id|INTEGER|0||1\n1|display_identity|BLOB|0||0\n" }

        XCTAssertNotNil(DesktopManagedSchemaInspector(managedPaths: paths, runner: drifted).inspect()?.hasDrift)

        _ = try installer.prepareLocalHermesRuntime(try XCTUnwrap(home.detect().installation))
        XCTAssertNil(DesktopManagedSchemaInspector(managedPaths: paths, runner: drifted).inspect())
    }
}

/// The app target cannot be imported into tests, so the wiring is asserted on its source — the same
/// weaker-but-only check `ManagedSchemaWiringTests` explains.
final class LocalHermesWiringTests: XCTestCase {
    func testTheRefreshReconcilesTheRuntimeAndTheWindowShowsIt() throws {
        let model = try appSource("DesktopViewModel.swift")
        XCTAssertTrue(model.contains("await refreshHermesRuntime()"), "nothing reconciles the Hermes runtime")
        XCTAssertTrue(model.contains("runtime.reconcileHermesRuntime(detector: detector, enabled: enabled)"))
        XCTAssertTrue(model.contains("localHermesInstallBlock(for: installation)"), "fresh managed install is not gated")
        XCTAssertTrue(model.contains("localHermesInstallBlock(for: machine.installation)"), "fresh component install is not gated")
        XCTAssertTrue(try appSource("SecondaryViews.swift").contains("model.localHermesIssue"))
    }

    private func appSource(_ name: String) throws -> String {
        let packageRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try String(
            contentsOf: packageRoot.appendingPathComponent("Sources/HermesGoDesktop/\(name)"),
            encoding: .utf8
        )
    }
}

// MARK: - Fixtures

private final class LocalHermesHome {
    static let commit = "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121"
    let root: URL

    init(installed: Bool = true, version: String = "0.21.3") throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("local-hermes-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: root.path)
        guard installed else { return }
        try write(".hermes/hermes-agent/.git/HEAD", "ref: refs/heads/main\n")
        try write(".hermes/hermes-agent/.git/refs/heads/main", Self.commit + "\n")
        try write(".hermes/hermes-agent/hermes_cli/__init__.py", "\"\"\"Hermes.\"\"\"\n\n__version__ = \"\(version)\"\n")
        try write(".hermes/hermes-agent/venv/bin/hermes", "#!/usr/bin/env python3\n", mode: 0o755)
    }

    deinit { try? FileManager.default.removeItem(at: root) }

    func path(_ relative: String) -> String { root.appendingPathComponent(relative).path }

    func write(_ relative: String, _ text: String, mode: Int = 0o644) throws {
        let url = root.appendingPathComponent(relative)
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o755]
        )
        try Data(text.utf8).write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: mode], ofItemAtPath: url.path)
    }

    func touch(_ relative: String, _ date: Date) throws {
        try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: path(relative))
    }

    func writeLaunchAgent(_ name: String, _ object: [String: Any]) throws {
        let data = try PropertyListSerialization.data(fromPropertyList: object, format: .xml, options: 0)
        try write("Library/LaunchAgents/\(name)", String(decoding: data, as: UTF8.self))
    }

    func detector(environment: [String: String] = [:]) -> DesktopLocalHermesDetector {
        DesktopLocalHermesDetector(
            paths: try! DesktopLocalHermesPaths(
                homeDirectory: root,
                systemEntrypoints: [root.appendingPathComponent("sys/hermes")]
            ),
            environment: environment
        )
    }

    func detect(environment: [String: String] = [:]) -> DesktopLocalHermesDetection {
        detector(environment: environment).detect()
    }

    func reason(environment: [String: String] = [:]) -> DesktopLocalHermesUnsupportedReason? {
        if case .unsupported(let reason, _) = detect(environment: environment) { return reason }
        return nil
    }
}

private struct LauncherSandbox {
    let root: URL
    let launcher: URL
    let executable: URL
    let tokenFile: URL

    init(token: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456") throws {
        root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("local-hermes-launcher-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
        let bin = root.appendingPathComponent("hermes-agent/venv/bin", isDirectory: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        executable = bin.appendingPathComponent("hermes")
        // Stands in for Hermes: proves which token and arguments it received, and that the launcher
        // cleared PYTHONPATH the way upstream's own shim does.
        try Data("#!/bin/sh\nprintf '%s|%s|%s' \"$HERMES_DASHBOARD_SESSION_TOKEN\" \"$*\" \"${PYTHONPATH-unset}\"\n".utf8)
            .write(to: executable)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: executable.path)
        launcher = root.appendingPathComponent("hermes-local-serve")
        try DesktopLocalHermesLauncher.script(executable: executable).write(to: launcher)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: launcher.path)
        tokenFile = root.appendingPathComponent("hermes-session-token")
        try Data(token.utf8).write(to: tokenFile)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: tokenFile.path)
    }

    var expectedArguments: [String] {
        [executable.path, "serve", "--host", "127.0.0.1", "--port", "9119"]
    }

    func run(arguments: [String]) throws -> (status: Int32, output: String, error: String) {
        let process = Process()
        process.executableURL = launcher
        process.arguments = arguments
        process.environment = [
            "HERMES_SESSION_TOKEN_FILE": tokenFile.path,
            "PYTHONPATH": "/should/be/cleared",
            "PATH": "/usr/bin:/bin",
        ]
        let output = Pipe()
        let error = Pipe()
        process.standardOutput = output
        process.standardError = error
        try process.run()
        process.waitUntilExit()
        return (
            process.terminationStatus,
            String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self),
            String(decoding: error.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        )
    }
}

private extension DesktopManagedInstaller {
    /// A committed bundled agent, as a v1 migration leaves it.
    func writeBundledAgentForTesting(layout: DesktopManagedInstallLayout, hermesHome: URL) throws {
        _ = try ensureHermesSessionToken()
        let manifest = DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            artifacts: [
                .init(component: .hermesServer, version: "0.21.0",
                      fileName: "Hermes-Server-0.21.0-arm64.tar.gz", entrypoint: "bin/hermes-server",
                      downloadURL: "https://downloads.example/Hermes-Server-0.21.0-arm64.tar.gz",
                      sizeBytes: 1, sha256: String(repeating: "a", count: 64)),
                .init(component: .connector, version: "0.2.0",
                      fileName: "Hermes-Connector-0.2.0-arm64.tar.gz", entrypoint: "bin/hermes-connector",
                      downloadURL: "https://downloads.example/Hermes-Connector-0.2.0-arm64.tar.gz",
                      sizeBytes: 1, sha256: String(repeating: "b", count: 64)),
            ]
        )
        _ = try writeHermesLaunchAgent(
            DesktopHermesServerLaunchAgent(
                hermesExecutable: layout.currentRelease.appendingPathComponent("hermes_server/bin/hermes-server"),
                hermesHome: hermesHome,
                runtimeContract: .serveV1,
                sessionTokenFile: layout.hermesSessionToken,
                standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
                standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log")
            ),
            manifest: manifest
        )
    }
}

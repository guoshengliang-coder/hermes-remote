import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

// Every test here runs a *fake* installer script with the real process runner inside a throwaway
// home directory. Nothing touches the network, the real `~/.hermes`, launchd or `~/Library`.

final class DesktopHermesInstallerTests: XCTestCase {
    private var sandbox: InstallerSandbox!

    override func setUpWithError() throws {
        sandbox = try InstallerSandbox()
    }

    override func tearDownWithError() throws {
        sandbox.remove()
    }

    // MARK: Success

    func testASuccessfulInstallRunsEveryManifestStageThroughTheProtocol() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "repository", "setup", "complete"], userInputStages: ["setup"])
        let events = EventRecorder()
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)])

        let installed = try await installer.install(sandbox.offer().confirm()) { events.append($0) }

        XCTAssertEqual(installed, sandbox.installation)
        let stage = { (name: String) in DesktopHermesInstallerStage(name: name, title: name, category: "runtime", needsUserInput: name == "setup") }
        XCTAssertEqual(events.values, [
            .downloading,
            .manifest(["prerequisites", "repository", "setup", "complete"].map(stage)),
            .stageStarted(stage("prerequisites")), .stageSucceeded(stage("prerequisites")),
            .stageStarted(stage("repository")), .stageSucceeded(stage("repository")),
            .stageStarted(stage("setup")), .stageSkipped(stage("setup")),
            .stageStarted(stage("complete")), .stageSucceeded(stage("complete")),
            .verifying,
        ])

        let calls = try sandbox.invocations()
        let common = "--dir \(sandbox.paths.checkoutRoot.path) --hermes-home \(sandbox.paths.hermesHome.path) --branch main"
        XCTAssertEqual(calls.filter { $0.hasPrefix("ARGS ") }, [
            "ARGS --manifest \(common)",
            "ARGS --stage prerequisites --non-interactive --json \(common)",
            "ARGS --stage repository --non-interactive --json \(common)",
            "ARGS --stage setup --non-interactive --json \(common)",
            "ARGS --stage complete --non-interactive --json \(common)",
        ])
        XCTAssertTrue(calls.contains("HERMES_HOME=\(sandbox.paths.hermesHome.path)"))
        XCTAssertTrue(calls.contains("HOME=\(sandbox.home.path)"), "the child must see the sandbox home, never the real one")
        XCTAssertTrue(calls.contains("STDIN_IS_TTY=no"))
    }

    func testTheInstallLogIsPrivateRedactedAndRecordsTheScriptDigest() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "complete"])
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)])

        _ = try await installer.install(sandbox.offer(proxy: DesktopSystemProxy(https: .init(host: "127.0.0.1", port: 7890))).confirm()) { _ in }

        let logURL = sandbox.logURL
        var status = stat()
        XCTAssertEqual(lstat(logURL.path, &status), 0)
        XCTAssertEqual(status.st_mode & 0o777, 0o600)
        let text = try String(contentsOf: logURL, encoding: .utf8)
        XCTAssertTrue(text.contains("sha256=\(DesktopHermesInstaller.sha256(Data(script.source.utf8)))"))
        XCTAssertTrue(text.contains("proxy https=127.0.0.1:7890"))
        XCTAssertTrue(text.contains("stage=prerequisites ✓ prerequisites done"), "ANSI colour must be stripped from logged lines")
        XCTAssertFalse(text.contains("\u{1B}["))
        XCTAssertFalse(text.contains("sk-live-secret"), "credentials in installer output must be redacted")
        XCTAssertFalse(text.contains(NSUserName() + "/"), "home-directory names must be redacted")
        XCTAssertFalse(FileManager.default.fileExists(atPath: sandbox.workRoot.appendingPathComponent("install.sh").path))
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: sandbox.workRoot.path)
        XCTAssertEqual(leftovers, [], "the private run directory must be removed afterwards")
    }

    // MARK: Failures

    func testANetworkFailureInAStageIsTheRetryableProxyCode() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "repository", "complete"], failing: "repository", failure: .network)
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])

        let failure = await installFailure(installer)

        guard case .network(let stage, let detail) = failure else { return XCTFail("\(String(describing: failure))") }
        XCTAssertEqual(stage, "repository")
        XCTAssertTrue(detail.contains("Could not resolve host"))
        let issue = try XCTUnwrap(DesktopIssue.hermesInstall(try XCTUnwrap(failure)))
        XCTAssertEqual(issue.code.rawValue, "HR-MIGRATE-015")
        XCTAssertTrue(issue.retryable)
        XCTAssertEqual(issue.recoveryAction, .retry)
        XCTAssertTrue(issue.detailChinese.contains("代理"))
        XCTAssertTrue(issue.detailEnglish.contains("proxy"))
        XCTAssertFalse(try sandbox.invocations().contains("ARGS --stage complete"), "no stage runs after a failure")
    }

    func testAStageFailureThatIsNotTheNetworkIsHRMigrate016() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "python-deps", "complete"], failing: "python-deps", failure: .plain)
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])

        let failure = await installFailure(installer)

        XCTAssertEqual(failure?.stage, "python-deps")
        guard case .stageFailed = failure else { return XCTFail("\(String(describing: failure))") }
        let issue = try XCTUnwrap(DesktopIssue.hermesInstall(try XCTUnwrap(failure)))
        XCTAssertEqual(issue.code.rawValue, "HR-MIGRATE-016")
        XCTAssertTrue(issue.retryable)
        XCTAssertTrue(issue.detailChinese.contains("内置"))
        XCTAssertTrue(issue.detailEnglish.contains("built into Hermes GO"))
    }

    func testAStageWithoutAResultFrameIsAProtocolMismatch() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "complete"], failing: "prerequisites", failure: .noFrame)
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])

        let failure = await installFailure(installer)

        guard case .protocolMismatch = failure else { return XCTFail("\(String(describing: failure))") }
        XCTAssertEqual(DesktopIssue.hermesInstall(try XCTUnwrap(failure))?.code.rawValue, "HR-MIGRATE-017")
    }

    func testAnUnknownProtocolVersionIsRefusedBeforeAnyStageRuns() async throws {
        let script = FakeInstaller(stages: ["prerequisites"], protocolVersion: 2)
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])

        let failure = await installFailure(installer)

        guard case .protocolMismatch(let detail) = failure else { return XCTFail("\(String(describing: failure))") }
        XCTAssertTrue(detail.contains("protocol_version 2"))
        XCTAssertEqual(try sandbox.invocations().filter { $0.hasPrefix("ARGS --stage") }, [])
        let issue = try XCTUnwrap(DesktopIssue.hermesInstall(try XCTUnwrap(failure)))
        XCTAssertFalse(issue.retryable)
    }

    func testADownloadThatIsNotAScriptRunsNothing() async throws {
        let installer = sandbox.installer(
            fetcher: StubFetcher(result: .success(Data("<html>captive portal</html>".utf8))),
            detections: [.absent(hermesDataPresent: false)]
        )

        let failure = await installFailure(installer)

        guard case .protocolMismatch = failure else { return XCTFail("\(String(describing: failure))") }
        XCTAssertFalse(FileManager.default.fileExists(atPath: sandbox.invocationsURL.path))
    }

    func testAnUnreachableDownloadIsTheNetworkCode() async throws {
        let installer = sandbox.installer(
            fetcher: StubFetcher(result: .failure(DesktopHermesInstallFailure.network(stage: "download", detail: "URLError -1003"))),
            detections: [.absent(hermesDataPresent: false)]
        )

        let failure = await installFailure(installer)

        XCTAssertEqual(failure, .network(stage: "download", detail: "URLError -1003"))
        XCTAssertEqual(DesktopIssue.hermesInstall(try XCTUnwrap(failure))?.code, .hermesInstallNetworkFailed)
    }

    func testAnInstallThatDetectionDoesNotAcceptIsHRMigrate018() async throws {
        let script = FakeInstaller(stages: ["complete"])
        let installer = sandbox.installer(script: script, detections: [
            .absent(hermesDataPresent: false),
            .unsupported(.versionTooOld, detail: "version 0.20.0 is older than 0.21.3"),
        ])

        let failure = await installFailure(installer)

        XCTAssertEqual(failure?.stage, "verify")
        let issue = try XCTUnwrap(DesktopIssue.hermesInstall(try XCTUnwrap(failure)))
        XCTAssertEqual(issue.code.rawValue, "HR-MIGRATE-018")
        XCTAssertFalse(issue.retryable)
        XCTAssertTrue(issue.technicalCause?.contains("versionTooOld") == true)
    }

    // MARK: Cancellation

    func testCancellingStopsTheWholeProcessGroup() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "python-deps", "complete"], hanging: "python-deps")
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])
        let offer = sandbox.offer()

        let task = Task { try await installer.install(offer.confirm()) { _ in } }
        let childPID = try await sandbox.waitForChildPID()
        XCTAssertEqual(kill(childPID, 0), 0, "the stage's own child must be running before cancel")
        let cancelledAt = Date()
        task.cancel()
        let result = await task.result
        // TERM must work on its own; the KILL fallback only fires after five seconds.
        XCTAssertLessThan(Date().timeIntervalSince(cancelledAt), 4, "SIGTERM did not reach the stage's process group")

        guard case .failure(let error) = result, error as? DesktopHermesInstallFailure == .cancelled else {
            return XCTFail("expected cancellation, got \(result)")
        }
        XCTAssertNil(DesktopIssue.hermesInstall(.cancelled), "a cancellation is the owner's choice, not an error")
        var gone = false
        for _ in 0..<50 where !gone {
            gone = kill(childPID, 0) != 0
            if !gone { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        XCTAssertTrue(gone, "the installer's child process outlived the cancellation")
        XCTAssertFalse(try sandbox.invocations().contains { $0.contains("--stage complete") })
    }

    // MARK: Preconditions — nothing runs

    func testRootIsRefusedBeforeAnythingRuns() async throws {
        let fetcher = StubFetcher(result: .success(Data(FakeInstaller(stages: ["complete"]).source.utf8)))
        let installer = sandbox.installer(fetcher: fetcher, detections: [.absent(hermesDataPresent: false)], userID: 0)

        let failure = await installFailure(installer)

        XCTAssertEqual(failure, .runningAsRoot)
        XCTAssertEqual(fetcher.calls, 0)
        XCTAssertEqual(DesktopIssue.hermesInstall(.runningAsRoot)?.code, .hermesInstallerUnsupported)
    }

    func testAHermesThatAppearedMeanwhileIsUsedWithoutRunningTheInstaller() async throws {
        let fetcher = StubFetcher(result: .success(Data(FakeInstaller(stages: ["complete"]).source.utf8)))
        let installer = sandbox.installer(fetcher: fetcher, detections: [.usable(sandbox.installation)])

        let installed = try await installer.install(sandbox.offer().confirm()) { _ in }

        XCTAssertEqual(installed, sandbox.installation)
        XCTAssertEqual(fetcher.calls, 0)
    }

    func testAnUnusualHermesThatAppearedMeanwhileIsNotTouched() async throws {
        let fetcher = StubFetcher(result: .success(Data(FakeInstaller(stages: ["complete"]).source.utf8)))
        let installer = sandbox.installer(fetcher: fetcher, detections: [.unsupported(.multipleProfiles, detail: "1 profile(s)")])

        let failure = await installFailure(installer)

        XCTAssertEqual(failure?.stage, "precondition")
        XCTAssertEqual(fetcher.calls, 0)
    }

    func testAnOfferThatWasOnlyShownRunsNothing() async throws {
        let fetcher = StubFetcher(result: .success(Data(FakeInstaller(stages: ["complete"]).source.utf8)))
        _ = sandbox.installer(fetcher: fetcher, detections: [.absent(hermesDataPresent: false)])
        _ = DesktopHermesInstallOffer.evaluate(
            detection: .absent(hermesDataPresent: false),
            paths: sandbox.paths,
            localRuntimeEnabled: true,
            freshInstall: true,
            unfinishedCheckout: nil,
            proxy: .none
        )

        XCTAssertEqual(fetcher.calls, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: sandbox.invocationsURL.path))
    }

    // MARK: Environment

    func testTheChildEnvironmentIsAnAllowlistWithTheSudoGuardFirst() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "complete"])
        let installer = sandbox.installer(
            script: script,
            detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)],
            inherited: [
                "PYTHONPATH": "/somewhere/else",
                "HERMES_HOME": "/Users/other/.custom",
                "VIRTUAL_ENV": "/tmp/venv",
                "USER": "tester",
                "PATH": "/evil/bin",
            ]
        )
        let environment = installer.childEnvironment(offer: sandbox.offer(), runDirectory: URL(fileURLWithPath: "/run/x"))

        XCTAssertNil(environment["PYTHONPATH"])
        XCTAssertNil(environment["VIRTUAL_ENV"])
        XCTAssertEqual(environment["HERMES_HOME"], sandbox.paths.hermesHome.path)
        XCTAssertEqual(environment["HOME"], sandbox.home.path)
        XCTAssertEqual(environment["USER"], "tester")
        XCTAssertTrue(environment["PATH"]?.hasPrefix("/run/x/bin:") == true)
        XCTAssertFalse(environment["PATH"]?.contains("/evil/bin") == true)
        XCTAssertEqual(environment["GIT_TERMINAL_PROMPT"], "0")
        XCTAssertEqual(environment["NONINTERACTIVE"], "1")

        _ = try await installer.install(sandbox.offer().confirm()) { _ in }
        XCTAssertTrue(try sandbox.invocations().contains("SUDO_EXIT=1"), "sudo must resolve to the refusing guard")
    }

    func testTheSystemProxyReachesTheInstallerAndLoopbackBypassesIt() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "complete"])
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)])
        let proxy = DesktopSystemProxy(
            http: .init(host: "127.0.0.1", port: 7890),
            https: .init(host: "127.0.0.1", port: 7890),
            exceptions: [".internal"]
        )

        _ = try await installer.install(sandbox.offer(proxy: proxy).confirm()) { _ in }

        let calls = try sandbox.invocations()
        XCTAssertTrue(calls.contains("https_proxy=http://127.0.0.1:7890"))
        XCTAssertTrue(calls.contains("HTTPS_PROXY=http://127.0.0.1:7890"))
        XCTAssertTrue(calls.contains("no_proxy=localhost,127.0.0.1,::1,.internal"))
    }

    // MARK: Review fixes

    /// Finding 1: a failure after `python-deps` leaves a Hermes detection reads as usable. The
    /// install must stay unfinished — offered for resume, not used for setup — until upstream's
    /// completion marker exists, and Retry must run the stages again.
    func testAFailureAfterPythonDepsKeepsTheInstallUnfinishedAndRetryRunsTheStagesAgain() async throws {
        let failing = FakeInstaller(stages: ["repository", "python-deps", "node-deps", "complete"], failing: "node-deps", failure: .plain)
        let first = sandbox.installer(script: failing, detections: [.absent(hermesDataPresent: false)])

        let failure = await installFailure(first)

        XCTAssertEqual(failure?.stage, "node-deps")
        XCTAssertEqual(DesktopIssue.hermesInstall(try XCTUnwrap(failure))?.code, .hermesInstallStageFailed)
        let checkout = try XCTUnwrap(DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot))
        XCTAssertEqual(sandbox.attempts.load()?.checkout, checkout, "the checkout Desktop's stages created must be recorded")
        XCTAssertTrue(DesktopHermesInstallResume.pending(sandbox.attempts, paths: sandbox.paths), "setup must stay blocked")
        let resume = try XCTUnwrap(
            sandbox.resumeOffer(detection: .usable(sandbox.installation)),
            "a half-installed Hermes that detection reads as usable must keep the failed card and its retry"
        )
        XCTAssertEqual(resume.resumeCheckout, checkout)

        let succeeding = FakeInstaller(stages: ["repository", "python-deps", "node-deps", "complete"])
        let retry = sandbox.installer(script: succeeding, detections: [.usable(sandbox.installation), .usable(sandbox.installation)])
        let installed = try await retry.install(resume.confirm()) { _ in }

        XCTAssertEqual(installed, sandbox.installation)
        XCTAssertEqual(try sandbox.invocations().filter { $0.hasPrefix("ARGS --stage repository") }.count, 2, "Retry must re-run the stages")
        XCTAssertNil(sandbox.attempts.load(), "a finished install clears the record")
        XCTAssertFalse(DesktopHermesInstallResume.pending(sandbox.attempts, paths: sandbox.paths))
    }

    func testFinishingWithoutUpstreamsCompletionMarkerIsNotUsable() async throws {
        let script = FakeInstaller(stages: ["repository", "python-deps"])
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)])

        let failure = await installFailure(installer)

        XCTAssertEqual(failure?.stage, "verify")
        XCTAssertEqual(DesktopIssue.hermesInstall(try XCTUnwrap(failure))?.code, .hermesInstallNotUsable)
        XCTAssertTrue(DesktopHermesInstallResume.pending(sandbox.attempts, paths: sandbox.paths))
    }

    /// Finding 2: a resume must never run upstream's repository stage (stash, checkout, reset) over
    /// a checkout the owner created meanwhile.
    func testAResumeRefusesACheckoutThatIsNoLongerDesktops() async throws {
        let manager = FileManager.default
        try manager.createDirectory(at: sandbox.paths.checkoutRoot, withIntermediateDirectories: true)
        let recorded = try XCTUnwrap(DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot))
        sandbox.attempts.save(DesktopHermesInstallAttempt(checkoutPath: sandbox.paths.checkoutRoot.path, checkout: recorded, startedAt: Date()))
        let resume = try XCTUnwrap(sandbox.resumeOffer(detection: .unsupported(.incompleteInstallation, detail: "")))
        // The owner replaces it (a manual clone, a terminal install.sh).
        try manager.createDirectory(at: sandbox.home.appendingPathComponent("owners-clone"), withIntermediateDirectories: true)
        try manager.removeItem(at: sandbox.paths.checkoutRoot)
        try manager.moveItem(at: sandbox.home.appendingPathComponent("owners-clone"), to: sandbox.paths.checkoutRoot)
        XCTAssertNotEqual(DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot), recorded)
        let fetcher = StubFetcher(result: .success(Data(FakeInstaller(stages: ["repository"]).source.utf8)))
        let installer = sandbox.installer(fetcher: fetcher, detections: [.unsupported(.incompleteInstallation, detail: "")])

        let failure: DesktopHermesInstallFailure?
        do {
            _ = try await installer.install(resume.confirm()) { _ in }
            failure = nil
        } catch {
            failure = error as? DesktopHermesInstallFailure
        }

        XCTAssertEqual(failure?.stage, "precondition")
        XCTAssertEqual(fetcher.calls, 0, "nothing may run over somebody else's checkout")
        XCTAssertNil(sandbox.attempts.load(), "a record for a replaced checkout is stale")
    }

    func testAFirstInstallDoesNotRunOverACheckoutThatAppearedMeanwhile() async throws {
        let fetcher = StubFetcher(result: .success(Data(FakeInstaller(stages: ["repository"]).source.utf8)))
        let installer = sandbox.installer(fetcher: fetcher, detections: [.unsupported(.incompleteInstallation, detail: "")])

        let failure = await installFailure(installer)

        XCTAssertEqual(failure?.stage, "precondition")
        XCTAssertEqual(fetcher.calls, 0)
    }

    func testStaleAttemptRecordsAreDropped() throws {
        let path = sandbox.paths.checkoutRoot.path
        // No stage had created a checkout, and now one exists: someone else's.
        try FileManager.default.createDirectory(at: sandbox.paths.checkoutRoot, withIntermediateDirectories: true)
        sandbox.attempts.save(DesktopHermesInstallAttempt(checkoutPath: path, checkout: nil, startedAt: Date().addingTimeInterval(60)))
        XCTAssertNil(DesktopHermesInstallResume.reconcile(sandbox.attempts, paths: sandbox.paths))
        XCTAssertNil(sandbox.attempts.load())

        // Desktop's checkout, finished by other means: the completion marker exists.
        let ours = try XCTUnwrap(DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot))
        sandbox.attempts.save(DesktopHermesInstallAttempt(checkoutPath: path, checkout: ours, startedAt: Date()))
        XCTAssertEqual(DesktopHermesInstallResume.reconcile(sandbox.attempts, paths: sandbox.paths), ours)
        try Data().write(to: sandbox.paths.checkoutRoot.appendingPathComponent(DesktopHermesInstallResume.completionMarkerName))
        XCTAssertNil(DesktopHermesInstallResume.reconcile(sandbox.attempts, paths: sandbox.paths))
        XCTAssertNil(sandbox.attempts.load())

        // No checkout yet and nothing created: still Desktop's, kept.
        try FileManager.default.removeItem(at: sandbox.paths.checkoutRoot)
        sandbox.attempts.save(DesktopHermesInstallAttempt(checkoutPath: path, checkout: nil, startedAt: Date()))
        XCTAssertNil(DesktopHermesInstallResume.reconcile(sandbox.attempts, paths: sandbox.paths))
        XCTAssertNotNil(sandbox.attempts.load())
    }

    /// Finding 5: the SSH clone upstream tries first must not reach the owner's SSH agent.
    func testTheSSHAgentIsNotPassedToTheInstaller() async throws {
        let installer = sandbox.installer(
            script: FakeInstaller(stages: ["complete"]),
            detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)],
            inherited: ["SSH_AUTH_SOCK": "/private/tmp/agent.sock", "USER": "tester"]
        )
        XCTAssertNil(installer.childEnvironment(offer: sandbox.offer(), runDirectory: URL(fileURLWithPath: "/run/x"))["SSH_AUTH_SOCK"])
        _ = try await installer.install(sandbox.offer().confirm()) { _ in }
        XCTAssertTrue(try sandbox.invocations().contains("SSH_AUTH_SOCK="))
    }

    /// Finding 6: the prerequisites probe's warning and generic "failed to download" wording must
    /// not turn an ordinary failure into the network code.
    func testAConnectivityWarningDoesNotMakeAnOrdinaryFailureANetworkFailure() async throws {
        let script = FakeInstaller(
            stages: ["prerequisites", "complete"],
            failing: "prerequisites",
            failure: .plain,
            failurePreamble: ["⚠ Could not reach https://duckduckgo.com/", "Failed to download optional ripgrep"]
        )
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])

        let failure = await installFailure(installer)

        guard case .stageFailed(let stage, _) = failure else { return XCTFail("\(String(describing: failure))") }
        XCTAssertEqual(stage, "prerequisites")
    }

    /// Finding 7: a manifest line longer than 4096 bytes must reach the parser whole.
    func testALongManifestLineIsNotCut() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "repository", "venv", "complete"], titleLength: 1500)
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false), .usable(sandbox.installation)])
        let events = EventRecorder()

        _ = try await installer.install(sandbox.offer().confirm()) { events.append($0) }

        XCTAssertTrue(events.values.contains { if case .manifest(let stages) = $0 { return stages.count == 4 } else { return false } })
    }

    /// Finding 9: quitting Desktop ends the running installer's process group.
    func testTerminatingAllProcessGroupsEndsARunningStage() async throws {
        let script = FakeInstaller(stages: ["prerequisites", "python-deps", "complete"], hanging: "python-deps")
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])
        let offer = sandbox.offer()
        let task = Task { try await installer.install(offer.confirm()) { _ in } }
        let childPID = try await sandbox.waitForChildPID()
        XCTAssertFalse(DesktopPosixProcessRunner.liveProcessGroups.isEmpty)

        DesktopPosixProcessRunner.terminateAllProcessGroups()

        var gone = false
        for _ in 0..<50 where !gone {
            gone = kill(childPID, 0) != 0
            if !gone { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        XCTAssertTrue(gone, "the stage's child outlived Desktop's termination")
        _ = await task.result
        XCTAssertTrue(DesktopPosixProcessRunner.liveProcessGroups.isEmpty)
    }

    /// Re-review finding: a cancel while the first install's `repository` stage is still running
    /// (the clone has landed, the stage has not returned) must leave the checkout recorded as
    /// Desktop's. Before, recording happened only after the stage returned, the record kept
    /// `checkout: nil`, the next refresh called the checkout someone else's, and the card vanished.
    func testCancellingDuringTheRepositoryStageKeepsTheCheckoutResumable() async throws {
        let script = FakeInstaller(stages: ["repository", "python-deps", "complete"], hanging: "repository")
        let installer = sandbox.installer(script: script, detections: [.absent(hermesDataPresent: false)])
        let offer = sandbox.offer()
        let task = Task { try await installer.install(offer.confirm()) { _ in } }
        _ = try await sandbox.waitForChildPID()
        XCTAssertNotNil(DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot), "the fake clone must have landed")

        task.cancel()
        let result = await task.result

        guard case .failure(let error) = result, error as? DesktopHermesInstallFailure == .cancelled else {
            return XCTFail("expected cancellation, got \(result)")
        }
        XCTAssertEqual(sandbox.attempts.load()?.checkout, DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot))
        XCTAssertTrue(DesktopHermesInstallResume.pending(sandbox.attempts, paths: sandbox.paths))
        let resume = try XCTUnwrap(sandbox.resumeOffer(detection: .unsupported(.incompleteInstallation, detail: "")))
        XCTAssertTrue(resume.resumesEarlierAttempt)
    }

    /// The quit path: Desktop dies with the stage, so nothing records the checkout. A checkout born
    /// after Desktop's install started is claimed as Desktop's; an older one is not.
    func testACheckoutBornAfterDesktopsInstallStartedIsClaimedAfterAQuit() throws {
        let path = sandbox.paths.checkoutRoot.path
        sandbox.attempts.save(DesktopHermesInstallAttempt(checkoutPath: path, checkout: nil, startedAt: Date().addingTimeInterval(-30)))
        try FileManager.default.createDirectory(at: sandbox.paths.checkoutRoot, withIntermediateDirectories: true)

        let claimed = DesktopHermesInstallResume.reconcile(sandbox.attempts, paths: sandbox.paths)

        XCTAssertEqual(claimed, DesktopCheckoutIdentity.read(sandbox.paths.checkoutRoot))
        XCTAssertEqual(sandbox.attempts.load()?.checkout, claimed)
        XCTAssertNotNil(sandbox.resumeOffer(detection: .unsupported(.incompleteInstallation, detail: ""))?.resumeCheckout)

        // A checkout older than the attempt is somebody else's.
        sandbox.attempts.save(DesktopHermesInstallAttempt(checkoutPath: path, checkout: nil, startedAt: Date().addingTimeInterval(60)))
        XCTAssertNil(DesktopHermesInstallResume.reconcile(sandbox.attempts, paths: sandbox.paths))
        XCTAssertNil(sandbox.attempts.load())
    }

    // MARK: Helpers

    private func installFailure(_ installer: DesktopHermesInstaller) async -> DesktopHermesInstallFailure? {
        do {
            _ = try await installer.install(sandbox.offer().confirm()) { _ in }
            return nil
        } catch {
            return error as? DesktopHermesInstallFailure
        }
    }
}

// MARK: - Offer, protocol parsing, run state

final class DesktopHermesInstallOfferTests: XCTestCase {
    private let paths = try! DesktopLocalHermesPaths(homeDirectory: URL(fileURLWithPath: "/Users/tester"), systemEntrypoints: [])

    private func offer(
        _ detection: DesktopLocalHermesDetection,
        enabled: Bool = true,
        fresh: Bool = true,
        unfinished: DesktopCheckoutIdentity? = nil
    ) -> DesktopHermesInstallOffer? {
        DesktopHermesInstallOffer.evaluate(
            detection: detection,
            paths: paths,
            localRuntimeEnabled: enabled,
            freshInstall: fresh,
            unfinishedCheckout: unfinished,
            proxy: .none
        )
    }

    private let ours = DesktopCheckoutIdentity(device: 1, inode: 42, birthSeconds: 1_790_000_000, birthNanoseconds: 5)

    func testOnlyAMacWithNoHermesAtAllIsOffered() throws {
        let offered = try XCTUnwrap(offer(.absent(hermesDataPresent: false)))
        XCTAssertEqual(offered.scriptURL.absoluteString, "https://hermes-agent.nousresearch.com/install.sh")
        XCTAssertEqual(offered.branch, "main")
        XCTAssertEqual(offered.hermesHome.path, "/Users/tester/.hermes")
        XCTAssertEqual(offered.checkoutRoot.path, "/Users/tester/.hermes/hermes-agent")
        XCTAssertFalse(offered.resumesEarlierAttempt)

        XCTAssertNil(offer(.absent(hermesDataPresent: true)), "Hermes data without a checkout is someone else's")
        XCTAssertNil(offer(.unsupported(.multipleProfiles, detail: "")))
        XCTAssertNil(offer(.unsupported(.customHermesHome, detail: "")))
        XCTAssertNil(offer(.unsupported(.nonStandardLocation, detail: "pipx")))
        XCTAssertNil(offer(.unsupported(.versionTooOld, detail: "")))
    }

    func testTheSettingOffOrAnExistingInstallationMeansNoOffer() {
        XCTAssertNil(offer(.absent(hermesDataPresent: false), enabled: false))
        XCTAssertNil(offer(.absent(hermesDataPresent: false), fresh: false))
    }

    func testOnlyDesktopsOwnUnfinishedCheckoutMayBeResumed() throws {
        XCTAssertNil(offer(.unsupported(.incompleteInstallation, detail: "venv/bin/hermes is missing")))
        let resumed = try XCTUnwrap(offer(.unsupported(.incompleteInstallation, detail: ""), unfinished: ours))
        XCTAssertEqual(resumed.resumeCheckout, ours)
        XCTAssertTrue(resumed.resumesEarlierAttempt)
        XCTAssertNotNil(offer(.unsupported(.unreadableIdentity, detail: ""), unfinished: ours))
        XCTAssertNil(offer(.unsupported(.multipleProfiles, detail: ""), unfinished: ours))
        XCTAssertNil(offer(.absent(hermesDataPresent: false), unfinished: ours)?.resumeCheckout)
    }

    /// Review finding 1: after `python-deps`, `venv/bin/hermes` exists and detection reads
    /// `.usable` although the install is unfinished. The old code returned no offer, which hid the
    /// failed card and unblocked setup onto a half-installed Hermes.
    func testAHalfInstalledHermesFromDesktopsOwnAttemptIsStillOffered() throws {
        let installation = DesktopLocalHermesInstallation(
            executable: paths.executable, checkoutRoot: paths.checkoutRoot, hermesHome: paths.hermesHome,
            commit: "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121", version: "0.21.3", identityChangedAt: Date()
        )
        XCTAssertNil(offer(.usable(installation)), "somebody else's usable Hermes is used, not offered")
        XCTAssertEqual(offer(.usable(installation), unfinished: ours)?.resumeCheckout, ours)
    }

    func testOnlyUpstreamsOwnOriginsCountAsOfficial() {
        let official = [
            "https://hermes-agent.nousresearch.com/install.sh",
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh",
        ]
        let foreign = [
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/evil-branch/scripts/install.sh",
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/other.sh",
            "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/README.md",
            "http://hermes-agent.nousresearch.com/install.sh",
            "https://raw.githubusercontent.com/someone-else/hermes-agent/main/scripts/install.sh",
            "https://hermes-agent.nousresearch.com.evil.example/install.sh",
            "https://hermes-agent.nousresearch.com:8443/install.sh",
            "https://captive.portal/login",
        ]
        for url in official { XCTAssertTrue(DesktopHermesInstallerSource.isOfficial(URL(string: url)!), url) }
        for url in foreign { XCTAssertFalse(DesktopHermesInstallerSource.isOfficial(URL(string: url)!), url) }
        XCTAssertTrue(DesktopHermesInstallerSource.isOfficial(DesktopHermesInstallerSource.scriptURL))
    }

    /// Finding 4: every redirect hop is checked, not only the final URL.
    func testARedirectOffTheOriginIsRefusedAtTheHop() throws {
        let guardDelegate = DesktopHermesInstallerURLSessionFetcher.RedirectGuard()
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let task = session.dataTask(with: DesktopHermesInstallerSource.scriptURL)
        let response = try XCTUnwrap(HTTPURLResponse(url: DesktopHermesInstallerSource.scriptURL, statusCode: 302, httpVersion: nil, headerFields: nil))
        var followed: URLRequest?? = .none

        guardDelegate.urlSession(session, task: task, willPerformHTTPRedirection: response,
                                 newRequest: URLRequest(url: URL(string: "https://mirror.example/install.sh")!)) { followed = .some($0) }
        XCTAssertEqual(followed, .some(nil))
        XCTAssertEqual(guardDelegate.refusedRedirect?.host, "mirror.example")

        let official = URL(string: "https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh")!
        let other = DesktopHermesInstallerURLSessionFetcher.RedirectGuard()
        other.urlSession(session, task: task, willPerformHTTPRedirection: response, newRequest: URLRequest(url: official)) { followed = .some($0) }
        XCTAssertEqual(followed??.url, official)
        XCTAssertNil(other.refusedRedirect)
    }

    func testTheDownloadStopsReadingPastTheLimit() async throws {
        var produced = 0
        let stream = AsyncStream<UInt8> { continuation in
            for _ in 0..<5000 { continuation.yield(0x41) }
            continuation.finish()
        }
        do {
            _ = try await DesktopHermesInstallerURLSessionFetcher.collect(stream, limit: 1024)
            XCTFail("expected the limit to stop the download")
        } catch let failure as DesktopHermesInstallFailure {
            guard case .protocolMismatch = failure else { return XCTFail("\(failure)") }
        }
        produced = try await DesktopHermesInstallerURLSessionFetcher.collect(
            AsyncStream { continuation in
                for _ in 0..<1024 { continuation.yield(0x41) }
                continuation.finish()
            },
            limit: 1024
        ).count
        XCTAssertEqual(produced, 1024)
    }

    func testLongOutputLinesAreSplitForTheLogNotCut() {
        let line = String(repeating: "é", count: 3000) // 6000 UTF-8 bytes
        let pieces = DesktopPosixProcessRunner.pieces(of: line, maximumBytes: 4096)
        XCTAssertEqual(pieces.count, 2)
        XCTAssertEqual(pieces.joined(), line)
        XCTAssertTrue(pieces.allSatisfy { $0.utf8.count <= 4096 })
        XCTAssertEqual(DesktopPosixProcessRunner.LineReader.clean(Data(String(repeating: "a", count: 9000).utf8)[...]).count, 9000)
    }

    /// Finding 8.
    func testInstallerOutputCredentialsAreRedacted() {
        let text = SecretRedactor.redact("""
        OPENAI_API_KEY=sk-proj-abcdefghijklmnop1234 ANTHROPIC_API_KEY: "abc123secret"
        token ghp_abcdefghijklmnopqrstuvwxyz0123456789 key sk-abcdefghijklmnopqrstu
        proxy http://alice:p@ss/w0rd@10.0.0.2:3128 done
        """)
        for secret in ["sk-proj-abcdefghijklmnop1234", "abc123secret", "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                       "sk-abcdefghijklmnopqrstu", "p@ss/w0rd", "alice"] {
            XCTAssertFalse(text.contains(secret), secret)
        }
        XCTAssertTrue(text.contains("10.0.0.2:3128"))
        XCTAssertTrue(text.contains("OPENAI_API_KEY=<redacted>"))
        XCTAssertEqual(
            SecretRedactor.redact("npm GET https://registry.npmjs.org/@scope/pkg"),
            "npm GET https://registry.npmjs.org/@scope/pkg"
        )
    }

    func testTheManifestParserReadsUpstreamsRealManifestLine() throws {
        // The line `install.sh --manifest` printed at upstream 17b5df02.
        let line = #"{"protocol_version":1,"stages":[{"name":"prerequisites","title":"System prerequisites","category":"runtime","needs_user_input":false},{"name":"repository","title":"Download Hermes Agent","category":"runtime","needs_user_input":false},{"name":"venv","title":"Create Python virtual environment","category":"runtime","needs_user_input":false},{"name":"python-deps","title":"Install Python dependencies","category":"runtime","needs_user_input":false},{"name":"node-deps","title":"Install browser-tool dependencies","category":"runtime","needs_user_input":false},{"name":"path","title":"Install hermes command","category":"runtime","needs_user_input":false},{"name":"config","title":"Prepare config and skills","category":"configuration","needs_user_input":false},{"name":"setup","title":"Configure API keys and settings","category":"configuration","needs_user_input":true},{"name":"gateway","title":"Configure gateway service","category":"configuration","needs_user_input":true},{"name":"complete","title":"Finish install","category":"runtime","needs_user_input":false}]}"#
        let manifest = try DesktopHermesInstallerManifest.parse("⚕ Hermes banner\n" + line + "\n")

        XCTAssertEqual(manifest.protocolVersion, 1)
        XCTAssertEqual(manifest.stages.map(\.name), [
            "prerequisites", "repository", "venv", "python-deps", "node-deps", "path", "config", "setup", "gateway", "complete",
        ])
        XCTAssertEqual(manifest.stages.filter(\.needsUserInput).map(\.name), ["setup", "gateway"])
        XCTAssertEqual(manifest.stages[1].titleChinese, "下载 Hermes")
    }

    func testMalformedManifestsAreRefused() {
        XCTAssertThrowsError(try DesktopHermesInstallerManifest.parse("no json here"))
        XCTAssertThrowsError(try DesktopHermesInstallerManifest.parse(#"{"stages":[{"name":"a"}]}"#))
        XCTAssertThrowsError(try DesktopHermesInstallerManifest.parse(#"{"protocol_version":1,"stages":[]}"#))
        XCTAssertThrowsError(try DesktopHermesInstallerManifest.parse(#"{"protocol_version":1,"stages":[{"name":"../etc"}]}"#))
        XCTAssertThrowsError(try DesktopHermesInstallerManifest.parse(#"{"protocol_version":1,"stages":[{"name":"a"},{"name":"a"}]}"#))
    }

    func testTheStageResultIsTheLastFrameOnStandardOutput() {
        let result = DesktopHermesInstallerStageResult.parse([
            "{\"not\":\"a frame\"}",
            "✓ done",
            #"{"ok":false,"stage":"repository","skipped":false,"reason":"exit code 128"}"#,
        ])
        XCTAssertEqual(result, DesktopHermesInstallerStageResult(ok: false, stage: "repository", skipped: false, reason: "exit code 128"))
        XCTAssertNil(DesktopHermesInstallerStageResult.parse(["plain", "lines"]))
    }

    func testTheRunFoldsProgressIntoRows() {
        let a = DesktopHermesInstallerStage(name: "a", title: "A", category: "runtime", needsUserInput: false)
        let b = DesktopHermesInstallerStage(name: "b", title: "B", category: "configuration", needsUserInput: true)
        var run = DesktopHermesInstallRun()
        run.apply(.downloading)
        XCTAssertTrue(run.downloading)
        run.apply(.manifest([a, b]))
        run.apply(.stageStarted(a))
        XCTAssertEqual(run.current?.stage, a)
        run.apply(.stageSucceeded(a))
        run.apply(.stageStarted(b))
        run.apply(.stageSkipped(b))
        XCTAssertEqual(run.rows.map(\.state), [.succeeded, .skipped])
        XCTAssertEqual(run.completedCount, 2)

        var failed = DesktopHermesInstallRun()
        failed.apply(.manifest([a, b]))
        failed.apply(.stageStarted(a))
        failed.fail(stage: "a")
        XCTAssertEqual(failed.rows.map(\.state), [.failed, .pending])

        var stopped = DesktopHermesInstallRun()
        stopped.apply(.manifest([a]))
        stopped.apply(.stageStarted(a))
        stopped.stop()
        XCTAssertEqual(stopped.rows.map(\.state), [.pending])
    }

    func testNetworkSignaturesAreRecognisedAndOrdinaryFailuresAreNot() {
        XCTAssertTrue(DesktopHermesInstaller.looksLikeNetworkFailure(["fatal: unable to access 'https://github.com/x.git/': Failed to connect to github.com port 443"]))
        XCTAssertTrue(DesktopHermesInstaller.looksLikeNetworkFailure(["error: Request failed after 3 retries: error sending request for url (https://pypi.org/simple/)"]))
        XCTAssertTrue(DesktopHermesInstaller.looksLikeNetworkFailure(["curl: (28) Operation timed out after 8000 milliseconds"]))
        XCTAssertTrue(DesktopHermesInstaller.looksLikeNetworkFailure(["Received HTTP code 407 from proxy after CONNECT"]))
        XCTAssertFalse(DesktopHermesInstaller.looksLikeNetworkFailure(["error: command 'clang' failed with exit code 1"]))
    }

    func testOutputLinesAreCleanedLikeATerminalWouldShowThem() {
        XCTAssertEqual(DesktopPosixProcessRunner.LineReader.clean(Data("\u{1B}[0;32m✓\u{1B}[0m ok".utf8)[...]), "✓ ok")
        XCTAssertEqual(DesktopPosixProcessRunner.LineReader.clean(Data("10%\r50%\r100% done".utf8)[...]), "100% done")
        XCTAssertEqual(DesktopPosixProcessRunner.LineReader.clean(Data("\u{1B}]0;title\u{07}text".utf8)[...]), "text")
    }

    func testInstallIssuesAreRegisteredBilingualAndCarryTheirRecovery() {
        let expectations: [(DesktopIssueCode, String, Bool, DesktopRecoveryAction)] = [
            (.hermesInstallNetworkFailed, "HR-MIGRATE-015", true, .retry),
            (.hermesInstallStageFailed, "HR-MIGRATE-016", true, .retry),
            (.hermesInstallerUnsupported, "HR-MIGRATE-017", false, .details),
            (.hermesInstallNotUsable, "HR-MIGRATE-018", false, .details),
        ]
        for (code, raw, retryable, recovery) in expectations {
            let issue = DesktopIssue(code: code, technicalCause: "stage=x https://user:pw@proxy.example:8080 /Users/somebody/.hermes")
            XCTAssertEqual(issue.code.rawValue, raw)
            XCTAssertEqual(issue.retryable, retryable, raw)
            XCTAssertEqual(issue.recoveryAction, recovery, raw)
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertTrue(issue.displayChinese.contains(raw))
            XCTAssertTrue(issue.displayEnglish.contains(raw))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("pw@"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("somebody"))
        }
    }
}

// MARK: - System proxy

final class DesktopSystemProxyTests: XCTestCase {
    func testTheSystemSettingsDictionaryIsParsed() {
        let proxy = DesktopSystemProxy.parse([
            "HTTPEnable": 1, "HTTPProxy": "127.0.0.1", "HTTPPort": 7890,
            "HTTPSEnable": 1, "HTTPSProxy": "127.0.0.1", "HTTPSPort": 7890,
            "SOCKSEnable": 1, "SOCKSProxy": "127.0.0.1", "SOCKSPort": 7891,
            "ExceptionsList": ["*.local", "169.254/16", "bad entry", "*"],
        ])
        XCTAssertEqual(proxy.http, .init(host: "127.0.0.1", port: 7890))
        XCTAssertEqual(proxy.https, .init(host: "127.0.0.1", port: 7890))
        XCTAssertEqual(proxy.socks, .init(host: "127.0.0.1", port: 7891))
        XCTAssertEqual(proxy.exceptions, [".local", "169.254/16"])
        XCTAssertTrue(proxy.isConfigured)
    }

    func testDisabledOrInvalidProxiesAreIgnored() {
        let proxy = DesktopSystemProxy.parse([
            "HTTPEnable": 0, "HTTPProxy": "127.0.0.1", "HTTPPort": 7890,
            "HTTPSEnable": 1, "HTTPSProxy": "bad host; rm -rf", "HTTPSPort": 7890,
            "SOCKSEnable": 1, "SOCKSProxy": "127.0.0.1", "SOCKSPort": 0,
            "ProxyAutoConfigEnable": 1,
        ])
        XCTAssertFalse(proxy.isConfigured)
        XCTAssertTrue(proxy.usesAutomaticConfiguration)
        XCTAssertTrue(proxy.summaryChinese.contains("PAC"))
        XCTAssertEqual(proxy.summaryForLog, "proxy pac=not-exported")
    }

    func testEachConfiguredProxyIsExportedInBothCasesAndOnlyThen() {
        let none = DesktopSystemProxy.none.environment(inheriting: [:])
        for name in DesktopSystemProxy.proxyVariableNames { XCTAssertNil(none[name], name) }
        XCTAssertEqual(none["no_proxy"], "localhost,127.0.0.1,::1")
        XCTAssertEqual(none["NO_PROXY"], "localhost,127.0.0.1,::1")

        let httpsOnly = DesktopSystemProxy(https: .init(host: "10.0.0.2", port: 3128)).environment(inheriting: [:])
        XCTAssertEqual(httpsOnly["https_proxy"], "http://10.0.0.2:3128")
        XCTAssertEqual(httpsOnly["HTTPS_PROXY"], "http://10.0.0.2:3128")
        XCTAssertNil(httpsOnly["http_proxy"])
        XCTAssertNil(httpsOnly["all_proxy"])

        let socks = DesktopSystemProxy(socks: .init(host: "::1", port: 1080)).environment(inheriting: [:])
        XCTAssertEqual(socks["all_proxy"], "socks5h://[::1]:1080")
        XCTAssertEqual(socks["ALL_PROXY"], "socks5h://[::1]:1080")
    }

    func testExplicitProxyVariablesWinAndBypassListsMerge() {
        let proxy = DesktopSystemProxy(https: .init(host: "127.0.0.1", port: 7890), exceptions: [".corp"])
        let environment = proxy.environment(inheriting: [
            "HTTPS_PROXY": "http://owner.example:8080",
            "NO_PROXY": "example.org, 127.0.0.1",
        ])
        XCTAssertEqual(environment["HTTPS_PROXY"], "http://owner.example:8080")
        XCTAssertNil(environment["https_proxy"], "the system proxy must not be mixed into an explicit choice")
        XCTAssertEqual(environment["no_proxy"], "example.org,127.0.0.1,localhost,::1,.corp")
    }

    func testTheSummaryNamesTheProxyWithoutCredentials() {
        let proxy = DesktopSystemProxy(http: .init(host: "127.0.0.1", port: 7890), https: .init(host: "127.0.0.1", port: 7890))
        XCTAssertEqual(proxy.summaryChinese, "系统代理（HTTPS 127.0.0.1:7890，HTTP 127.0.0.1:7890）")
        XCTAssertEqual(DesktopSystemProxy.none.summaryChinese, "未设置系统代理，将直接联网")
    }

    func testURLCredentialsAreRedactedEverywhere() {
        XCTAssertEqual(
            SecretRedactor.redact("proxy=http://alice:s3cret@10.0.0.2:3128 ok"),
            "proxy=http://<redacted>@10.0.0.2:3128 ok"
        )
        XCTAssertEqual(SecretRedactor.redact("https://github.com/NousResearch/hermes-agent.git"), "https://github.com/NousResearch/hermes-agent.git")
    }
}

// MARK: - Wiring

/// The app target cannot be imported into tests, so the wiring is asserted on its source — the same
/// weaker-but-only check `ManagedSchemaWiringTests` explains.
final class HermesInstallWiringTests: XCTestCase {
    func testTheInstallerRunsOnlyFromAnExplicitOwnerAction() throws {
        let model = try appSource("DesktopViewModel.swift")
        XCTAssertEqual(model.components(separatedBy: "installer.install(").count - 1, 1, "exactly one call site may run the installer")
        XCTAssertEqual(model.components(separatedBy: ".confirm()").count - 1, 1)
        let start = try XCTUnwrap(model.range(of: "func startHermesInstall()"))
        let cancel = try XCTUnwrap(model.range(of: "func cancelHermesInstall()"))
        let install = try XCTUnwrap(model.range(of: "installer.install("))
        XCTAssertTrue(start.upperBound < install.lowerBound && install.lowerBound < cancel.lowerBound, "the installer must run only inside startHermesInstall")
        XCTAssertTrue(model.contains("await refreshHermesInstallOffer(scopedManagedInstallation)"), "the refresh never offers an install")
        XCTAssertTrue(model.contains("guard !isManagedBootstrapAccountLocked, !isHermesInstallDecisionPending else { return }"))
        XCTAssertTrue(model.contains("!isHermesInstallDecisionPending,"), "component setup must wait for the owner's Hermes decision")
        // Finding 2: the re-evaluation before running reads the record as it is, writes nothing,
        // must match the shown offer, and aborts when there is no offer any more.
        XCTAssertFalse(model.contains("earlierAttemptRecorded: true"))
        XCTAssertFalse(model.contains("?? shownOffer"))
        XCTAssertTrue(model.contains("guard let offer = reevaluated, offer.resumeCheckout == shownOffer.resumeCheckout else {"))
        // Finding 9.
        XCTAssertTrue(try appSource("HermesGoDesktopApp.swift").contains("DesktopPosixProcessRunner.terminateAllProcessGroups()"))
        // Re-review: "use the bundled Hermes" stays reachable whenever a fresh setup is refused.
        XCTAssertEqual(model.components(separatedBy: "isFreshInstallBlockedByLocalHermes = true").count - 1, 2)
        let views = try appSource("SecondaryViews.swift")
        XCTAssertTrue(views.contains("if model.isFreshInstallBlockedByLocalHermes, model.hermesInstallPhase == .hidden {"))
        XCTAssertTrue(views.contains("Button(\"改用内置 Hermes\") { model.useBundledHermes() }"))

        let card = try appSource("HermesInstallCard.swift")
        XCTAssertEqual(card.components(separatedBy: "model.startHermesInstall()").count - 1, 2, "only the confirmation sheet and Retry may start it")
        let sheet = try XCTUnwrap(card.range(of: "private func confirmationSheet"))
        let lastStart = try XCTUnwrap(card.range(of: "model.startHermesInstall()", options: .backwards))
        XCTAssertTrue(sheet.lowerBound < lastStart.lowerBound, "the first start must come from the confirmation sheet")
        XCTAssertTrue(try appSource("SecondaryViews.swift").contains("HermesInstallCard()"))
    }

    private func appSource(_ name: String) throws -> String {
        let packageRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return try String(contentsOf: packageRoot.appendingPathComponent("Sources/HermesGoDesktop/\(name)"), encoding: .utf8)
    }
}

// MARK: - Fixtures

private struct FakeInstaller {
    enum Failure { case network, plain, noFrame }

    let stages: [String]
    var userInputStages: [String] = []
    var failing: String?
    var failure: Failure = .plain
    var hanging: String?
    var protocolVersion = 1
    /// Lines the failing stage prints before its own failure.
    var failurePreamble: [String] = []
    var titleLength = 0

    var source: String {
        let manifestStages = stages.map { name in
            #"{"name":"\#(name)","title":"\#(name + String(repeating: "x", count: titleLength))","category":"runtime","needs_user_input":\#(userInputStages.contains(name))}"#
        }.joined(separator: ",")
        let manifest = #"{"protocol_version":\#(protocolVersion),"stages":[\#(manifestStages)]}"#
        let failureBody: String = switch failure {
        case .network:
            #"""
            echo "fatal: unable to access 'https://github.com/NousResearch/hermes-agent.git/': Could not resolve host: github.com" >&2
            echo '{"ok":false,"stage":"'"$stage"'","skipped":false,"reason":"exit code 128"}'
            exit 128
            """#
        case .plain:
            #"""
            echo "error: command 'clang' failed with exit code 1" >&2
            echo '{"ok":false,"stage":"'"$stage"'","skipped":false,"reason":"exit code 1"}'
            exit 1
            """#
        case .noFrame:
            #"""
            echo "something went wrong"
            exit 3
            """#
        }
        return #"""
        #!/bin/bash
        log="$HOME/invocations"
        echo "ARGS $*" >> "$log"
        if [ "$1" = "--manifest" ]; then
          echo "⚕ Hermes Agent Installer"
          echo '\#(manifest)'
          exit 0
        fi
        stage="$2"
        dir=""; prev=""
        for a in "$@"; do [ "$prev" = "--dir" ] && dir="$a"; prev="$a"; done
        {
          echo "HOME=$HOME"
          echo "SSH_AUTH_SOCK=${SSH_AUTH_SOCK:-}"
          echo "HERMES_HOME=$HERMES_HOME"
          echo "https_proxy=${https_proxy:-}"
          echo "HTTPS_PROXY=${HTTPS_PROXY:-}"
          echo "no_proxy=${no_proxy:-}"
          if [ -t 0 ]; then echo "STDIN_IS_TTY=yes"; else echo "STDIN_IS_TTY=no"; fi
          sudo true 2>/dev/null; echo "SUDO_EXIT=$?"
        } >> "$log"
        case " \#(userInputStages.joined(separator: " ")) " in
          *" $stage "*) echo '{"ok":true,"stage":"'"$stage"'","skipped":true}'; exit 0 ;;
        esac
        [ "$stage" = "repository" ] && mkdir -p "$dir/.git"
        if [ "$stage" = "\#(hanging ?? "")" ]; then
          sleep 60 &
          echo "$!" > "$HOME/child.pid"
          wait
        fi
        if [ "$stage" = "\#(failing ?? "")" ]; then
        \#(failurePreamble.map { "echo '\($0)' >&2" }.joined(separator: "\n"))
        \#(failureBody)
        fi
        case "$stage" in
          repository) mkdir -p "$dir/.git" ;;
          complete) mkdir -p "$dir"; : > "$dir/.hermes-bootstrap-complete" ;;
        esac
        printf '\033[0;32m✓\033[0m %s done\n' "$stage"
        echo "OPENAI_API_KEY check: password=sk-live-secret"
        echo "path /Users/\#(NSUserName())/.hermes"
        echo '{"ok":true,"stage":"'"$stage"'","skipped":false}'
        """#
    }
}

final class MemoryAttemptStore: DesktopHermesInstallAttemptStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var value: DesktopHermesInstallAttempt?
    func load() -> DesktopHermesInstallAttempt? { lock.withLock { value } }
    func save(_ attempt: DesktopHermesInstallAttempt) { lock.withLock { value = attempt } }
    func clear() { lock.withLock { value = nil } }
}

private final class StubFetcher: DesktopHermesInstallerScriptFetching, @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    let result: Result<Data, Error>

    init(result: Result<Data, Error>) { self.result = result }

    var calls: Int { lock.withLock { count } }

    func fetchInstallerScript(from url: URL) async throws -> Data {
        lock.withLock { count += 1 }
        return try result.get()
    }
}

private final class DetectionSequence: @unchecked Sendable {
    private let lock = NSLock()
    private var remaining: [DesktopLocalHermesDetection]

    init(_ values: [DesktopLocalHermesDetection]) { remaining = values }

    func next() -> DesktopLocalHermesDetection {
        lock.withLock { remaining.count > 1 ? remaining.removeFirst() : remaining[0] }
    }
}

private final class EventRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [DesktopHermesInstallProgress] = []
    var values: [DesktopHermesInstallProgress] { lock.withLock { events } }
    func append(_ event: DesktopHermesInstallProgress) { lock.withLock { events.append(event) } }
}

private final class InstallerSandbox {
    let root: URL
    let home: URL
    let paths: DesktopLocalHermesPaths
    let workRoot: URL
    let logURL: URL
    let attempts = MemoryAttemptStore()

    init() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-installer-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
        home = root.appendingPathComponent("home", isDirectory: true)
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        paths = try DesktopLocalHermesPaths(homeDirectory: home, systemEntrypoints: [])
        workRoot = root.appendingPathComponent("cache", isDirectory: true)
        logURL = root.appendingPathComponent("Managed/logs/\(DesktopHermesInstaller.logFileName)")
    }

    func remove() { try? FileManager.default.removeItem(at: root) }

    var invocationsURL: URL { home.appendingPathComponent("invocations") }

    var installation: DesktopLocalHermesInstallation {
        DesktopLocalHermesInstallation(
            executable: paths.executable,
            checkoutRoot: paths.checkoutRoot,
            hermesHome: paths.hermesHome,
            commit: "17b5df02f2a729d8f46fbbf78cfc1f5a8cf0f121",
            version: "0.21.3",
            identityChangedAt: Date(timeIntervalSince1970: 1_790_000_000)
        )
    }

    func offer(proxy: DesktopSystemProxy = .none) -> DesktopHermesInstallOffer {
        DesktopHermesInstallOffer.evaluate(
            detection: .absent(hermesDataPresent: false),
            paths: paths,
            localRuntimeEnabled: true,
            freshInstall: true,
            unfinishedCheckout: nil,
            proxy: proxy
        )!
    }

    func resumeOffer(detection: DesktopLocalHermesDetection) -> DesktopHermesInstallOffer? {
        DesktopHermesInstallOffer.evaluate(
            detection: detection,
            paths: paths,
            localRuntimeEnabled: true,
            freshInstall: true,
            unfinishedCheckout: DesktopHermesInstallResume.reconcile(attempts, paths: paths),
            proxy: .none
        )
    }

    func installer(
        script: FakeInstaller? = nil,
        fetcher: StubFetcher? = nil,
        detections: [DesktopLocalHermesDetection],
        userID: UInt32 = Darwin.getuid(),
        inherited: [String: String] = [:]
    ) -> DesktopHermesInstaller {
        let fetcher = fetcher ?? StubFetcher(result: .success(Data((script ?? FakeInstaller(stages: ["complete"])).source.utf8)))
        let sequence = DetectionSequence(detections)
        return DesktopHermesInstaller(
            paths: paths,
            workRoot: workRoot,
            log: DesktopServiceOperationLog(url: logURL, maximumBytes: 1024 * 1024),
            fetcher: fetcher,
            detect: { sequence.next() },
            attempts: attempts,
            inheritedEnvironment: inherited,
            userID: userID
        )
    }

    func invocations() throws -> [String] {
        try String(contentsOf: invocationsURL, encoding: .utf8).split(separator: "\n").map(String.init)
    }

    func waitForChildPID() async throws -> pid_t {
        let url = home.appendingPathComponent("child.pid")
        for _ in 0..<100 {
            if let text = try? String(contentsOf: url, encoding: .utf8),
               let pid = pid_t(text.trimmingCharacters(in: .whitespacesAndNewlines)) {
                return pid
            }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw XCTSkip("the fake stage never started its child")
    }
}

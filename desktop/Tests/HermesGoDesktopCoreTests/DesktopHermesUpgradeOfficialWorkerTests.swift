import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeOfficialWorkerTests: XCTestCase {
    private let python = URL(fileURLWithPath: "/usr/bin/python3")
    func testWorkerResourceIsAvailableToSwiftPMAndPackagedApps() throws {
        let script = try XCTUnwrap(DesktopHermesUpgradeOfficialWorker.bundledScript)
        XCTAssertTrue(try String(contentsOf: script).contains("popen_post_commit"))
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        XCTAssertTrue(try String(contentsOf: root.appendingPathComponent("scripts/build-app.sh"))
            .contains("HermesGoDesktop_HermesGoDesktopCore.bundle"))
    }
    func testTypedControlProtocolAndPrivateEnvironment() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let worker = try launch(fixture)
        XCTAssertEqual(worker.runID, fixture.configuration.runID)
        XCTAssertEqual(worker.upstreamUpdateID, fixture.configuration.upstreamUpdateID)
        let exit = try await worker.runOfficialUpdate()
        XCTAssertEqual(exit, 0)
        do { _ = try await worker.runOfficialUpdate(); XCTFail("a lock handle ran twice") }
        catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .recoveryRequired) }
        try worker.release()
        let environment = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture.run.appendingPathComponent("environment.json"))) as! [String: String]
        XCTAssertEqual(environment["HERMES_UPDATE_CORRELATION_ID"], fixture.configuration.runID)
        XCTAssertEqual(environment["HERMES_HOME"], fixture.configuration.hermesHome.path)
        XCTAssertEqual(environment["HOME"], fixture.home.path)
        XCTAssertEqual(environment["HTTPS_PROXY"], "http://127.0.0.1:1")
        XCTAssertNil(environment["AWS_ACCESS_KEY_ID"])
        XCTAssertNil(environment["PYTHONPATH"])
        XCTAssertEqual(environment["GIT_NO_REPLACE_OBJECTS"], "1")
        XCTAssertEqual(environment["GIT_GRAFT_FILE"], "/dev/null")
        let mode = try FileManager.default.attributesOfItem(atPath: fixture.run.appendingPathComponent("worker-configuration.json").path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(mode?.intValue, 0o600)
    }
    func testWaitingTaskCancellationDoesNotTerminateTheWorker() async throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let worker = try launch(fixture)
        let task = Task { try await worker.runOfficialUpdate() }
        task.cancel()
        let exit = try await task.value
        XCTAssertEqual(exit, 0)
        try worker.release()
    }
    func testResumingRequiresExactlyTheSameFrozenConfiguration() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let worker = try launch(fixture)
        try worker.release()
        let changed = fixture.configuration(upstreamID: UUID().uuidString)
        XCTAssertThrowsError(try launch(fixture, configuration: changed)) {
            XCTAssertEqual($0 as? DesktopHermesUpgradeError, .recoveryRequired)
        }
    }
    func testMalformedControlEventHasStableFailureAndNeverRunsUpdate() throws {
        let fixture = try Fixture(script: "print('raw private failure', flush=True)\n"); defer { fixture.remove() }
        XCTAssertThrowsError(try launch(fixture)) {
            XCTAssertEqual($0 as? DesktopHermesUpgradeError, .preflightBlocked)
        }
    }
    func testOversizedControlLineFailsBeforeAcceptingLock() throws {
        let fixture = try Fixture(script: "import json\nprint(json.dumps({'schema':1,'event':'locked','padding':'x'*5000}), flush=True)\n")
        defer { fixture.remove() }
        XCTAssertThrowsError(try launch(fixture)) {
            XCTAssertEqual($0 as? DesktopHermesUpgradeError, .preflightBlocked)
        }
    }
    func testUnsafeExistingConfigurationIsNotFollowedOrOverwritten() throws {
        let fixture = try Fixture(); defer { fixture.remove() }
        let external = fixture.root.appendingPathComponent("external")
        try Data("retain".utf8).write(to: external)
        try FileManager.default.createSymbolicLink(at: fixture.run.appendingPathComponent("worker-configuration.json"), withDestinationURL: external)
        XCTAssertThrowsError(try launch(fixture))
        XCTAssertEqual(try String(contentsOf: external), "retain")
    }
    private func launch(_ fixture: Fixture, configuration: DesktopHermesUpgradeOfficialWorker.Configuration? = nil) throws -> DesktopHermesUpgradeOfficialWorker {
        guard FileManager.default.isExecutableFile(atPath: python.path) else { throw XCTSkip("system Python unavailable") }
        return try DesktopHermesUpgradeOfficialWorker.launch(configuration: configuration ?? fixture.configuration,
            python: python, userHome: fixture.home, path: "/usr/bin:/bin",
            proxyEnvironment: ["HTTPS_PROXY": "http://127.0.0.1:1", "AWS_ACCESS_KEY_ID": "fixture-secret"], script: fixture.script)
    }
}

private final class Fixture {
    let root: URL
    let home: URL
    let run: URL
    let script: URL
    let runID = UUID().uuidString
    let updateID = UUID().uuidString
    init(script text: String? = nil) throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("hg199-worker-\(UUID().uuidString)").resolvingSymlinksInPath()
        home = root.appendingPathComponent("home"); run = root.appendingPathComponent("run")
        script = root.appendingPathComponent("fixture.py")
        for directory in [root, home, run] {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        }
        // IPC/lifetime fixture only. Official locking and process death are tested against the
        // pinned upstream source by scripts/compat/hermes-update-lock-matrix.py.
        try (text ?? """
        import json, os, pathlib, sys, time
        cfg = json.load(open(sys.argv[1]))
        pathlib.Path(cfg['runDirectory'], 'environment.json').write_text(json.dumps(dict(os.environ)))
        print(json.dumps({'schema':1, 'event':'locked'}), flush=True)
        cmd = json.loads(sys.stdin.readline())
        if cmd['command'] == 'update':
            print(json.dumps({'schema':1, 'event':'updating'}), flush=True)
            time.sleep(.05)
            print(json.dumps({'schema':1, 'event':'finished', 'exitCode':0}), flush=True)
            cmd = json.loads(sys.stdin.readline())
        assert cmd == {'command':'release', 'runID':cfg['runID']}
        """).write(to: script, atomically: false, encoding: .utf8)
    }
    var configuration: DesktopHermesUpgradeOfficialWorker.Configuration { configuration(upstreamID: updateID) }
    func configuration(upstreamID: String) -> DesktopHermesUpgradeOfficialWorker.Configuration {
        .init(runID: runID, upstreamUpdateID: upstreamID, sourceRoot: root.appendingPathComponent("official-source"),
              checkout: home.appendingPathComponent(".hermes/hermes-agent"), hermesHome: home.appendingPathComponent(".hermes"),
              runDirectory: run, journal: run.appendingPathComponent("journal.json"), sourceCommit: String(repeating: "a", count: 40),
              targetCommit: String(repeating: "b", count: 40), targetVersion: "0.21.6", preUpdateVersion: "0.21.3", plan: Data("{}".utf8))
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}

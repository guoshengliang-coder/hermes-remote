import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedServiceRepairTests: XCTestCase {
    func testPreparationIsReadOnlyAndKeepsOriginalIdentity() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        let repair = try f.makeRepair()
        let before = try f.identityBytes()
        let needed = try await repair.needsRepair()
        XCTAssertTrue(needed)
        let plan = try await repair.prepare()
        XCTAssertEqual(plan.releaseVersion, "0.3.4")
        XCTAssertEqual(try f.identityBytes(), before)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.snapshot.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.layout.connectorLaunchAgent.path))
        XCTAssertTrue(f.runner.mutations.isEmpty)
        await repair.cancel(plan)
        do { try await repair.commit(plan, confirmed: true); XCTFail("cancelled handle") }
        catch { XCTAssertEqual(error as? DesktopManagedServiceRepairError, .stalePreparation) }
    }

    func testAllMissingAndUnloadedCombinationsRestoreSameBinding() async throws {
        for hermesFile in [false, true] {
            for connectorFile in [false, true] {
                for hermesLoaded in [false, true] {
                    for connectorLoaded in [false, true] {
                        let f = try RepairFixture(); defer { f.remove() }
                        try f.configure(hermesFile: hermesFile, connectorFile: connectorFile,
                                        hermesLoaded: hermesLoaded, connectorLoaded: connectorLoaded)
                        let repair = try f.makeRepair()
                        let before = try f.identityBytes()
                        let needed = try await repair.needsRepair()
                        if hermesFile && connectorFile && hermesLoaded && connectorLoaded {
                            XCTAssertFalse(needed); continue
                        }
                        XCTAssertTrue(needed)
                        let plan = try await repair.prepare()
                        try await repair.commit(plan, confirmed: true)
                        XCTAssertEqual(try f.identityBytes(), before)
                        XCTAssertEqual(f.runner.loaded.count, 2)
                        XCTAssertTrue(FileManager.default.fileExists(atPath: f.layout.connectorLaunchAgent.path))
                        XCTAssertTrue(FileManager.default.fileExists(atPath: f.layout.hermesLaunchAgent.path))
                        XCTAssertFalse(FileManager.default.fileExists(atPath: f.snapshot.path))
                        if hermesLoaded { XCTAssertFalse(f.runner.mutations.contains("stop hermes")) }
                        if !hermesLoaded, let connectorStart = f.runner.mutations.firstIndex(of: "start connector") {
                            XCTAssertLessThan(try XCTUnwrap(f.runner.mutations.firstIndex(of: "start hermes")),
                                              connectorStart)
                        }
                        XCTAssertEqual(f.readiness.waits, hermesLoaded ? 0 : 1)
                    }
                }
            }
        }
    }

    func testConfirmationAndStalePreparationCannotMutate() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        let repair = try f.makeRepair()
        let plan = try await repair.prepare()
        do { try await repair.commit(plan, confirmed: false); XCTFail("confirmation required") }
        catch { XCTAssertEqual(error as? DesktopManagedServiceRepairError, .confirmationRequired) }
        try f.write(f.hermesPlist, f.layout.hermesLaunchAgent)
        do { try await repair.commit(plan, confirmed: true); XCTFail("stale file") }
        catch { XCTAssertEqual(error as? DesktopManagedServiceRepairError, .stalePreparation) }
        XCTAssertTrue(f.runner.mutations.isEmpty)
    }

    func testTamperedContentAccountAndCredentialAreBlockedBeforeMutation() async throws {
        for cause in ["account", "fingerprint", "credential", "token", "unknown", "port", "symlink"] {
            let f = try RepairFixture(); defer { f.remove() }
            switch cause {
            case "account": f.runner.bindingID = UUID().uuidString.lowercased()
            case "fingerprint": f.runner.fingerprint = String(repeating: "0", count: 64)
            case "credential": try f.write(Data("{}".utf8), f.layout.connectorCredential)
            case "token": try f.write(Data("short".utf8), f.layout.hermesSessionToken)
            case "unknown": f.runner.loaded[DesktopLaunchAgentController<RepairRunner>.legacyLabel] = ["/unknown"]
            case "port": f.shutdown.free = false
            case "symlink":
                try FileManager.default.createSymbolicLink(at: f.layout.hermesLaunchAgent, withDestinationURL: f.layout.connectorCredential)
            default: break
            }
            do { _ = try await f.makeRepair().prepare(); XCTFail("must block \(cause)") }
            catch { XCTAssertTrue(f.runner.mutations.isEmpty, cause) }
            XCTAssertFalse(FileManager.default.fileExists(atPath: f.snapshot.path))
        }
    }

    func testContentChangedAfterPreparationCannotRun() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        try f.write(Data("#!/bin/sh\nexit 9\n".utf8), f.connectorExecutable, mode: 0o700)
        do { try await repair.commit(plan, confirmed: true); XCTFail("changed release") }
        catch { XCTAssertEqual(error as? DesktopManagedServiceRepairError, .unsafeInstallation) }
        XCTAssertTrue(f.runner.mutations.isEmpty)
    }

    func testLaunchFailureRollsBackOnlyNewFilesAndServices() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        f.runner.failConnectorStart = true
        let before = try f.identityBytes()
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        do { try await repair.commit(plan, confirmed: true); XCTFail("launch failed") }
        catch { XCTAssertEqual(error as? DesktopLaunchAgentControllerError, .accountStartFailed) }
        XCTAssertEqual(try f.identityBytes(), before)
        XCTAssertTrue(f.runner.loaded.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.layout.hermesLaunchAgent.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.layout.connectorLaunchAgent.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.snapshot.path))
    }

    func testExistingHermesSurvivesFailedConnectorRepairByteForByte() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        try f.configure(hermesFile: true, connectorFile: false, hermesLoaded: true, connectorLoaded: false)
        let original = try Data(contentsOf: f.layout.hermesLaunchAgent)
        f.runner.failConnectorStart = true
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        do { try await repair.commit(plan, confirmed: true); XCTFail("launch failed") } catch {}
        XCTAssertEqual(try Data(contentsOf: f.layout.hermesLaunchAgent), original)
        XCTAssertNotNil(f.runner.loaded[DesktopManagedInstallLayout.hermesLabel])
        XCTAssertFalse(f.runner.mutations.contains("stop hermes"))
    }

    func testOldCloudHealthFailsAndUnfinishedRollbackCanRecoverInNewRuntime() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        f.runner.staleHealth = true; f.runner.failStop = true
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        do { try await repair.commit(plan, confirmed: true); XCTFail("old health") }
        catch { XCTAssertEqual(error as? DesktopManagedServiceRepairError, .restorationFailed) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: f.snapshot.path))
        f.runner.failStop = false
        let restarted = try f.makeRepair()
        let recovery = try await restarted.prepare()
        XCTAssertTrue(recovery.resumesInterruptedRepair)
        try await restarted.commit(recovery, confirmed: true)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.snapshot.path))
        XCTAssertTrue(f.runner.loaded.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.layout.connectorLaunchAgent.path))
        let needed = try await restarted.needsRepair()
        XCTAssertTrue(needed)
    }

    func testOperationLeaseBlocksSecondRepairWithoutChangingFiles() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        let lease = try f.journal.acquireOperationLease()
        do { try await repair.commit(plan, confirmed: true); XCTFail("concurrent operation") }
        catch { XCTAssertEqual(error as? DesktopMigrationJournalError, .lockUnavailable) }
        withExtendedLifetime(lease) {}
        XCTAssertTrue(f.runner.mutations.isEmpty)
    }

    func testLocalHermesReconstructionIncludesLauncherAndNeverRunsBundledCopy() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        let local = try f.localInstallation()
        f.detection = .usable(local)
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        XCTAssertTrue(plan.usesLocalHermes)
        XCTAssertTrue(plan.filePaths.contains(f.layout.localHermesLauncher.path))
        try await repair.commit(plan, confirmed: true)
        XCTAssertEqual(f.runner.loaded[DesktopManagedInstallLayout.hermesLabel]?.prefix(2),
                       [f.layout.localHermesLauncher.path, local.executable.path].prefix(2))
        XCTAssertTrue(f.installer.localHermesLauncherIsCurrent(executable: local.executable))
    }

    func testComponentStoreIsRehashedAndKeepsExactConnectorAndNodeRoots() async throws {
        let f = try RepairFixture(componentStore: true); defer { f.remove() }
        f.detection = .usable(try f.localInstallation())
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        try await repair.commit(plan, confirmed: true)
        XCTAssertEqual(f.runner.loaded[DesktopManagedInstallLayout.connectorLabel], [f.connectorExecutable.path])
        let data = try Data(contentsOf: f.layout.connectorLaunchAgent)
        let plist = try PropertyListSerialization.propertyList(from: data, options: [], format: nil) as! [String: Any]
        XCTAssertEqual((plist["EnvironmentVariables"] as? [String: String])?["HERMES_NODE_RUNTIME_ROOT"], f.nodeRoot?.path)
    }

    func testHermesReadinessFailureNeverStartsConnectorAndRestoresFiles() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        f.readiness.ready = false
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        do { try await repair.commit(plan, confirmed: true); XCTFail("unready Hermes") }
        catch { XCTAssertEqual(error as? DesktopManagedServiceRepairError, .healthFailed) }
        XCTAssertFalse(f.runner.mutations.contains("start connector"))
        XCTAssertTrue(f.runner.loaded.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.layout.hermesLaunchAgent.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: f.snapshot.path))
    }

    func testReferenceAndAccountChangesBlockInterruptedRecovery() async throws {
        for change in ["account", "current", "snapshot"] {
            let f = try RepairFixture(); defer { f.remove() }
            f.runner.staleHealth = true; f.runner.failStop = true
            let repair = try f.makeRepair(); let plan = try await repair.prepare()
            do { try await repair.commit(plan, confirmed: true); XCTFail("must leave recovery") } catch {}
            XCTAssertTrue(FileManager.default.fileExists(atPath: f.snapshot.path))
            f.runner.failStop = false
            let before = f.runner.mutations
            if change == "account" { f.runner.bindingID = UUID().uuidString }
            if change == "current" {
                try FileManager.default.removeItem(at: f.layout.currentRelease)
                try FileManager.default.createSymbolicLink(atPath: f.layout.currentRelease.path, withDestinationPath: "releases/other")
            }
            if change == "snapshot" {
                var object = try JSONSerialization.jsonObject(with: Data(contentsOf: f.snapshot)) as! [String: Any]
                object["originalConnector"] = Data("untrusted".utf8).base64EncodedString()
                try f.write(JSONSerialization.data(withJSONObject: object), f.snapshot)
            }
            do { _ = try await f.makeRepair().prepare(); XCTFail("changed \(change)") } catch {}
            XCTAssertEqual(f.runner.mutations, before)
            XCTAssertTrue(FileManager.default.fileExists(atPath: f.snapshot.path))
        }
    }

    func testSurvivingLegacyConnectorWithoutModeIsPreserved() async throws {
        let f = try RepairFixture(); defer { f.remove() }
        try f.configure(hermesFile: true, connectorFile: false, hermesLoaded: false, connectorLoaded: false)
        var plist = try PropertyListSerialization.propertyList(from: f.connectorPlist, options: [], format: nil) as! [String: Any]
        var environment = plist["EnvironmentVariables"] as! [String: String]
        environment.removeValue(forKey: "HERMES_MODE"); plist["EnvironmentVariables"] = environment
        let original = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
        try f.write(original, f.layout.connectorLaunchAgent)
        let repair = try f.makeRepair(); let plan = try await repair.prepare()
        try await repair.commit(plan, confirmed: true)
        XCTAssertEqual(try Data(contentsOf: f.layout.connectorLaunchAgent), original)
    }

    func testRepairErrorCodesHaveBilingualRetryAndRedactionContracts() throws {
        for (code, retryable) in [(DesktopIssueCode.managedServicesNeedRepair, true), (.managedServiceRepairBlocked, false), (.managedServiceRepairFailed, true)] {
            let issue = DesktopIssue(code: code, technicalCause: "privateKey=secret-key Authorization: Bearer secret-token")
            XCTAssertEqual(issue.retryable, retryable)
            XCTAssertEqual(issue.recoveryAction, .details)
            XCTAssertFalse(issue.summaryChinese.isEmpty); XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertTrue(issue.displayEnglish.contains(code.rawValue)); XCTAssertTrue(issue.displayChinese.contains(code.rawValue))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("secret-token"))
            XCTAssertFalse(issue.sanitizedDiagnostic.contains("secret-key"))
            XCTAssertEqual(try JSONDecoder().decode(DesktopIssueCode.self, from: JSONEncoder().encode(code)), code)
        }
    }
}

private final class RepairFixture: @unchecked Sendable {
    let root: URL
    let layout: DesktopManagedInstallLayout
    let installer: DesktopManagedInstaller
    let journal: DesktopMigrationJournalStore
    let runner = RepairRunner()
    let readiness = RepairReadiness()
    let shutdown = RepairShutdown()
    var detection: DesktopLocalHermesDetection = .absent(hermesDataPresent: true)
    let hermesHome: URL
    let hermesPlist: Data
    let connectorPlist: Data
    let connectorExecutable: URL
    var nodeRoot: URL?
    var snapshot: URL { layout.stateRoot.appendingPathComponent("service-repair.json") }

    init(componentStore: Bool = false) throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        layout = try DesktopManagedInstallLayout(root: root.appendingPathComponent("managed"), launchAgentsRoot: root.appendingPathComponent("agents"))
        installer = DesktopManagedInstaller(layout: layout)
        hermesHome = root.appendingPathComponent(".hermes")
        journal = try DesktopMigrationJournalStore(root: layout.stateRoot)
        for dir in [layout.root, layout.stateRoot, layout.secretsRoot, layout.logsRoot, layout.launchAgentsRoot] {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        let machine = ConnectorMachineIdentity()
        runner.fingerprint = machine.connectorPublicKeyFingerprint
        try machine.accountConnectorCredential(bindingID: runner.bindingID, generation: 8, expectedFingerprint: runner.fingerprint).data
            .write(to: layout.connectorCredential)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: layout.connectorCredential.path)
        try Data(String(repeating: "a", count: 64).utf8).write(to: layout.hermesSessionToken)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: layout.hermesSessionToken.path)
        let record = DesktopMigrationJournal(runID: UUID().uuidString.lowercased(), state: .accountActive,
            lastKnownGoodMode: .account, releaseVersion: "0.3.4", releaseLayout: componentStore ? .componentStore : .bundledRelease,
            bindingID: runner.bindingID, bindingGeneration: 8, updatedAt: "2026-09-13T20:41:00Z")
        try JSONEncoder().encode(record).write(to: layout.stateRoot.appendingPathComponent("migration-state.json"))
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: layout.stateRoot.appendingPathComponent("migration-state.json").path)
        if componentStore {
            var refs: [DesktopManagedComponentReference] = []
            var roots: [DesktopManagedComponentKind: URL] = [:]
            for (kind, entry) in [(DesktopManagedComponentKind.connector, "bin/hermes-connector"), (.nodeRuntime, "bin/node")] {
                let source = root.appendingPathComponent(kind.rawValue)
                try FileManager.default.createDirectory(at: source.appendingPathComponent("bin"), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
                try Data("#!/bin/sh\nexit 0\n".utf8).write(to: source.appendingPathComponent(entry))
                try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: source.appendingPathComponent(entry).path)
                let hash = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
                let container = layout.root.appendingPathComponent("components/\(kind.rawValue)/\(hash)")
                try FileManager.default.createDirectory(at: container, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
                let content = container.appendingPathComponent("content")
                try FileManager.default.copyItem(at: source, to: content)
                let receipt = DesktopManagedComponentReceipt(kind: kind, version: "1.0.0", architecture: "arm64", contentSHA256: hash)
                try JSONEncoder().encode(receipt).write(to: container.appendingPathComponent("receipt.json"))
                try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: container.appendingPathComponent("receipt.json").path)
                refs.append(.init(kind: kind, contentSHA256: hash)); roots[kind] = content
            }
            let references = layout.root.appendingPathComponent("references")
            try FileManager.default.createDirectory(at: references, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            let url = references.appendingPathComponent("0.3.4.json")
            try JSONEncoder().encode(DesktopManagedComponentReferenceSet(releaseVersion: "0.3.4", components: refs)).write(to: url)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            connectorExecutable = roots[.connector]!.appendingPathComponent("bin/hermes-connector")
            nodeRoot = roots[.nodeRuntime]
        } else {
            let release = try layout.release("0.3.4")
            for component in ["connector", "hermes_server"] {
                let bin = release.appendingPathComponent("\(component)/bin")
                try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
                let file = bin.appendingPathComponent(component == "connector" ? "hermes-connector" : "hermes-server")
                try Data("#!/bin/sh\nexit 0\n".utf8).write(to: file)
                try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: file.path)
            }
            let marker = release.appendingPathComponent(".hermes-go-managed-release.json")
            try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "runID": record.runID, "releaseVersion": "0.3.4"]).write(to: marker)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: marker.path)
            try FileManager.default.createSymbolicLink(atPath: layout.currentRelease.path, withDestinationPath: "releases/0.3.4")
            connectorExecutable = layout.currentRelease.appendingPathComponent("connector/bin/hermes-connector")
        }
        connectorPlist = try DesktopAccountConnectorLaunchAgent(connectorExecutable: connectorExecutable,
            credentialFile: layout.connectorCredential, gatewayURL: URL(string: "wss://gateway.example/v2/connect")!,
            hermesBaseURL: DesktopHermesRuntimeContract.serveV1.baseURL, sessionTokenFile: layout.hermesSessionToken,
            standardOutput: layout.logsRoot.appendingPathComponent("connector.log"),
            standardError: layout.logsRoot.appendingPathComponent("connector.error.log"), nodeRuntimeRoot: nodeRoot).encodedPropertyList()
        hermesPlist = try DesktopHermesServerLaunchAgent(
            hermesExecutable: layout.currentRelease.appendingPathComponent("hermes_server/bin/hermes-server"), hermesHome: hermesHome,
            runtimeContract: .serveV1, sessionTokenFile: layout.hermesSessionToken,
            standardOutput: layout.logsRoot.appendingPathComponent("hermes-server.log"),
            standardError: layout.logsRoot.appendingPathComponent("hermes-server.error.log")).encodedPropertyList()
    }

    func makeRepair() throws -> DesktopManagedServiceRepair<RepairRunner> {
        DesktopManagedServiceRepair(installer: installer, journal: journal,
            agents: try DesktopLaunchAgentController(userID: 501, launchAgentsRoot: layout.launchAgentsRoot,
                runner: runner, convergenceAttempts: 1, convergenceDelay: 0),
            gatewayURL: URL(string: "https://gateway.example")!, hermesHome: hermesHome,
            identity: { self.runner.remote() }, loadedArguments: { self.runner.loaded[$0] },
            localDetector: { self.detection }, readiness: readiness, shutdown: shutdown, polls: 2, delay: 0)
    }
    func write(_ data: Data, _ url: URL, mode: Int = 0o600) throws {
        try data.write(to: url); try FileManager.default.setAttributes([.posixPermissions: mode], ofItemAtPath: url.path)
    }
    func configure(hermesFile: Bool, connectorFile: Bool, hermesLoaded: Bool, connectorLoaded: Bool) throws {
        if hermesFile { try write(hermesPlist, layout.hermesLaunchAgent) }
        if connectorFile { try write(connectorPlist, layout.connectorLaunchAgent) }
        if hermesLoaded { runner.loaded[DesktopManagedInstallLayout.hermesLabel] = try args(hermesPlist) }
        if connectorLoaded { runner.loaded[DesktopManagedInstallLayout.connectorLabel] = try args(connectorPlist) }
    }
    func identityBytes() throws -> [Data] {
        try [Data(contentsOf: layout.stateRoot.appendingPathComponent("migration-state.json")), Data(contentsOf: layout.connectorCredential), Data(contentsOf: layout.hermesSessionToken)]
    }
    func localInstallation() throws -> DesktopLocalHermesInstallation {
        let checkout = hermesHome.appendingPathComponent("hermes-agent")
        let executable = checkout.appendingPathComponent("venv/bin/hermes")
        try FileManager.default.createDirectory(at: executable.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try write(Data("#!/bin/sh\nexit 0\n".utf8), executable, mode: 0o700)
        return DesktopLocalHermesInstallation(executable: executable, checkoutRoot: checkout, hermesHome: hermesHome,
            commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: Date())
    }
    func remove() { try? FileManager.default.removeItem(at: root) }
}

private func args(_ data: Data) throws -> [String] {
    (try PropertyListSerialization.propertyList(from: data, options: [], format: nil) as! [String: Any])["ProgramArguments"] as! [String]
}
private final class RepairRunner: CommandRunning, @unchecked Sendable {
    var loaded: [String: [String]] = [:]
    var mutations: [String] = []
    var failConnectorStart = false
    var failStop = false
    var staleHealth = false
    var fingerprint = ""
    var bindingID = "70000000-0000-4000-8000-000000000007"
    var ticks = 100.0
    func remote() -> DesktopManagedServiceRepairIdentity {
        if !staleHealth { ticks += 1 }
        return .init(bindingID: bindingID, generation: 8, fingerprint: fingerprint,
                     healthy: loaded.count == 2, checkedAt: Date(timeIntervalSince1970: ticks))
    }
    func run(executable: URL, arguments: [String]) -> CommandResult {
        let label = arguments.last!.split(separator: "/").last.map(String.init)!
        switch arguments[0] {
        case "print": return .init(status: loaded[label] == nil ? 113 : 0)
        case "bootout":
            if failStop { return .init(status: 1) }
            mutations.append(label == DesktopManagedInstallLayout.hermesLabel ? "stop hermes" : "stop connector")
            loaded[label] = nil
            return .init(status: 0)
        case "bootstrap":
            let url = URL(fileURLWithPath: arguments.last!)
            let data = try! Data(contentsOf: url)
            let object = try! PropertyListSerialization.propertyList(from: data, options: [], format: nil) as! [String: Any]
            let actualLabel = object["Label"] as! String
            if actualLabel == DesktopManagedInstallLayout.connectorLabel && failConnectorStart { return .init(status: 1) }
            mutations.append(actualLabel == DesktopManagedInstallLayout.hermesLabel ? "start hermes" : "start connector")
            loaded[actualLabel] = try! args(data)
            return .init(status: 0)
        default: return .init(status: 0)
        }
    }
}
private final class RepairReadiness: DesktopHermesCandidateReadinessChecking, @unchecked Sendable {
    var waits = 0
    var ready = true
    func checkpoint(logURL: URL) throws -> DesktopHermesReadinessCheckpoint { .init(logURL: logURL) }
    func waitUntilReady(checkpoint: DesktopHermesReadinessCheckpoint, contract: DesktopHermesRuntimeContract,
                        maximumAttempts: Int, delayNanoseconds: UInt64) async throws -> Bool { waits += 1; return ready }
}
private final class RepairShutdown: DesktopHermesShutdownChecking, @unchecked Sendable {
    var free = true
    func waitUntilStopped(contract: DesktopHermesRuntimeContract, maximumAttempts: Int,
                          delayNanoseconds: UInt64) async throws -> Bool { free }
}

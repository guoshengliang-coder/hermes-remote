import Foundation
import Network
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeActivityTests: XCTestCase {
    func testIdleIsOnlyOneProcessObservationAndLoopbackTicketFlagMayBeFalse() async throws {
        let f = ActivityFixture()
        let report = await f.probe().inspect(f.installation, token: f.token)
        XCTAssertEqual(report.state, .idleObserved)
        XCTAssertEqual(report.process, f.process)
        XCTAssertTrue(report.isFresh(for: f.installation.runtimeIdentity))
        XCTAssertFalse(report.writerAdmissionProven)
        XCTAssertFalse(report.allProfileInactivityProven)
    }
    func testBusyLedgerNamesAreReducedWithoutRetainingIDsOrRawReasons() async throws {
        let f = ActivityFixture()
        for (detail, expected) in [("retirement_admission", DesktopHermesUpgradeActivity.Ledger.admission),
            ("session:synthetic-private-session", .session), ("delegation", .delegation),
            ("cron:synthetic-private-job", .cron), ("future-ledger-private-value", .unclassified)] {
            let report = await f.probe(idle: #"{"ok":true,"idle":false,"reason":"turn_in_flight","detail":"DETAIL"}"#.replacingOccurrences(of: "DETAIL", with: detail))
                .inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .busy); XCTAssertEqual(report.ledger, expected)
            let encoded = String(decoding: try JSONEncoder().encode(report), as: UTF8.self)
            if detail.contains("private") { XCTAssertFalse(encoded.contains(detail)) }
            XCTAssertFalse(encoded.contains("turn_in_flight")); XCTAssertFalse(encoded.contains(f.token))
            XCTAssertFalse(encoded.contains(f.installation.checkoutRoot.path))
        }
    }
    func testPendingHumanInputIsBusyAndUnavailableLedgersAreUnknown() async {
        let f = ActivityFixture()
        let pending = await f.probe(idle: #"{"ok":true,"idle":false,"reason":"awaiting_human_input"}"#).inspect(f.installation, token: f.token)
        XCTAssertEqual(pending.state, .busy); XCTAssertEqual(pending.ledger, .humanInput)
        for reason in ["turn_probe_unavailable", "input_probe_unavailable"] {
            let report = await f.probe(idle: #"{"ok":true,"idle":null,"reason":"REASON"}"#.replacingOccurrences(of: "REASON", with: reason)).inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .unknown)
        }
    }
    func testOldIdentityEndpointAndOldHealthWithoutBootCommitRemainUnknown() async {
        let f = ActivityFixture()
        for probe in [f.probe(identity: "{}"), f.probe(health: #"{"ok":true,"version":"0.21.3","auth_required":false}"#)] {
            let report = await probe.inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .unknown)
        }
    }
    func testForeignPIDRoleSPAProtocolAndMalformedNumericIdentitiesAreUnknown() async {
        let f = ActivityFixture()
        for (key, replacement) in [("\"pid\":42", "\"pid\":43"), ("\"pid\":42", "\"pid\":true"),
            ("\"pid\":42", "\"pid\":42.5"), ("\"pid\":42", "\"pid\":2147483648"),
            ("\"role\":\"desktop-serve\"", "\"role\":\"gateway\""),
            ("\"servesSpa\":false", "\"servesSpa\":true"), ("\"protocolVersion\":1", "\"protocolVersion\":2")] {
            let report = await f.probe(identity: f.identity.replacingOccurrences(of: key, with: replacement)).inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .unknown)
        }
    }
    func testBootCommitAndVersionMismatchCannotBeHiddenByIdle() async {
        let f = ActivityFixture()
        for health in [f.health.replacingOccurrences(of: f.installation.commit, with: String(repeating: "b", count: 40)),
                       f.health.replacingOccurrences(of: "0.21.6", with: "0.21.3")] {
            let report = await f.probe(health: health).inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .unknown)
        }
    }
    func testMissingReusedChangedAndUnscopedProcessIdentityAreUnknown() async {
        let f = ActivityFixture()
        let reused = DesktopHermesUpgradeInspection.ProcessIdentity(pid: 42, parentPID: 1, startedAt: Date(timeIntervalSince1970: 2000))
        for reader in [ActivityProcesses(before: nil, after: nil, command: f.command),
            ActivityProcesses(before: [f.process], after: [], command: f.command),
            ActivityProcesses(before: [f.process], after: [reused], command: f.command),
            ActivityProcesses(before: [f.process], after: [f.process], command: f.command,
                afterCommand: .init(executable: "/bin/python", arguments: [f.installation.executable.path, "serve", "--profile", "work"])),
            ActivityProcesses(before: [f.process], after: [f.process], command: .init(executable: "/bin/sleep", arguments: ["sleep"])),
            ActivityProcesses(before: [f.process], after: [f.process], command: .init(executable: "/bin/python", arguments: ["python", "-m", "hermes_cli", "serve"]))] {
            let report = await f.probe(processes: reader).inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .unknown)
        }
    }
    func testChangedListenerOnSecondIdentityReadInvalidatesSnapshot() async {
        let f = ActivityFixture()
        let transport = ActivityResponses(identity: f.identity, health: f.health, idle: f.idle,
            finalIdentity: f.identity.replacingOccurrences(of: "\"pid\":42", with: "\"pid\":43"))
        let report = await UpgradeActivityProbe(transport: transport, processes: f.processes).inspect(f.installation, token: f.token)
        XCTAssertEqual(report.state, .unknown)
    }
    func testMalformedAmbiguousContradictoryAndFutureIdleRepliesStayUnknown() async {
        let f = ActivityFixture()
        for idle in ["{}", "not json", #"{"ok":true,"idle":1,"reason":null}"#,
            #"{"ok":true,"idle":true,"idle":false,"reason":null}"#,
            #"{"ok":true,"idle":true,"reason":"awaiting_human_input"}"#,
            #"{"ok":true,"idle":true}"#, #"{"ok":true,"idle":true,"reason":null,"detail":"cron:private-job"}"#,
            #"{"ok":true,"idle":true,"reason":null,"newField":0}"#,
            #"{"ok":true,"idle":false,"reason":"future-reason"}"#,
            #"{"ok":true,"idle":true,"reason":null,"detail":"\u0000"}"#,
            String(repeating: " ", count: 4097)] {
            let report = await f.probe(idle: idle).inspect(f.installation, token: f.token)
            XCTAssertEqual(report.state, .unknown)
        }
    }
    func testUnsafeTokenAndUnsafePrivateInstallationNeverReadNetwork() async throws {
        let f = ActivityFixture(), transport = NoActivityReads()
        let badToken = await UpgradeActivityProbe(transport: transport, processes: f.processes).inspect(f.installation, token: "bad")
        XCTAssertEqual(badToken.state, .unknown)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
        let layout = try DesktopManagedInstallLayout(root: root, launchAgentsRoot: root.appendingPathComponent("agents"))
        let absent = await DesktopManagedInstaller(layout: layout).inspectHermesUpgradeActivity(installation: f.installation,
            probe: UpgradeActivityProbe(transport: transport, processes: f.processes))
        XCTAssertEqual(absent.state, .unknown)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }
    func testSnapshotFreshnessAndSourceAreRequired() {
        let f = ActivityFixture(), now = Date()
        for at in [now.addingTimeInterval(-6), now.addingTimeInterval(1), Date(timeIntervalSince1970: .infinity)] {
            XCTAssertFalse(DesktopHermesUpgradeActivity.unknown(f.installation, at: at).isFresh(for: f.installation.runtimeIdentity, now: now))
        }
        XCTAssertFalse(DesktopHermesUpgradeActivity.unknown(f.installation).isFresh(for: String(repeating: "b", count: 64)))
    }
    func testPrivateInstallationReadIsNonMutatingAndDetectsTokenOrLauncherChanges() async throws {
        let f = ActivityFixture()
        for mutation in 0..<3 {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).resolvingSymlinksInPath()
            defer { try? FileManager.default.removeItem(at: root) }
            let layout = try DesktopManagedInstallLayout(root: root, launchAgentsRoot: root.appendingPathComponent("agents"))
            try FileManager.default.createDirectory(at: layout.secretsRoot, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try Data(f.token.utf8).write(to: layout.hermesSessionToken)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: layout.hermesSessionToken.path)
            let installer = DesktopManagedInstaller(layout: layout)
            _ = try installer.writeLocalHermesLaunchAgent(f.installation)
            let before = try Data(contentsOf: layout.hermesLaunchAgent)
            let transport = ActivityResponses(identity: f.identity, health: f.health, idle: f.idle, duringIdle: {
                if mutation == 1 { try Data(String(repeating: "b", count: 43).utf8).write(to: layout.hermesSessionToken) }
                if mutation == 2 { try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: layout.localHermesLauncher.path) }
            })
            let report = await installer.inspectHermesUpgradeActivity(installation: f.installation,
                probe: UpgradeActivityProbe(transport: transport, processes: f.processes))
            XCTAssertEqual(report.state, mutation == 0 ? .idleObserved : .unknown)
            XCTAssertEqual(try Data(contentsOf: layout.hermesLaunchAgent), before)
            if mutation == 0 { XCTAssertEqual(try Data(contentsOf: layout.hermesSessionToken), Data(f.token.utf8)) }
        }
    }
    func testRealLoopbackGETIncludesOnlyPrivateHeaderAndNoMaintenanceMutation() async throws {
        let server = try ActivityHTTPServer(reply: "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
        defer { server.stop() }
        let data = try await UpgradeActivityHTTP(testPort: server.port).read(.idle, token: ActivityFixture().token)
        XCTAssertEqual(data, Data("{}".utf8))
        let request = try XCTUnwrap(server.requests.first)
        XCTAssertTrue(request.hasPrefix("GET /api/health/idle HTTP/1.1"))
        XCTAssertTrue(request.lowercased().contains("x-hermes-session-token: " + ActivityFixture().token))
        XCTAssertFalse(request.contains("?token=")); XCTAssertFalse(request.contains("Cookie:"))
    }
    func testRealLoopbackRedirectNeverForwardsCredentialToAnotherPort() async throws {
        let destination = try ActivityHTTPServer(reply: "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}")
        defer { destination.stop() }
        let source = try ActivityHTTPServer(reply: "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:\(destination.port)/api/health/idle\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        defer { source.stop() }
        do { _ = try await UpgradeActivityHTTP(testPort: source.port).read(.idle, token: ActivityFixture().token); XCTFail("redirect admitted") } catch {}
        XCTAssertEqual(source.requests.count, 1); XCTAssertTrue(destination.requests.isEmpty)
    }
    func testRealLoopbackUnboundedBodyHTTPRefusalAndStallAreRejected() async throws {
        for reply in ["HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + String(repeating: "x", count: 8192),
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}", nil] {
            let server = try ActivityHTTPServer(reply: reply); defer { server.stop() }
            let began = Date()
            do { _ = try await UpgradeActivityHTTP(testPort: server.port).read(.idle, token: ActivityFixture().token); XCTFail("unsafe response admitted") } catch {}
            XCTAssertLessThan(Date().timeIntervalSince(began), 3)
        }
    }
}

private struct ActivityFixture {
    let token = String(repeating: "a", count: 43)
    let process = DesktopHermesUpgradeInspection.ProcessIdentity(pid: 42, parentPID: 1, startedAt: Date(timeIntervalSince1970: 1000))
    let installation = DesktopLocalHermesInstallation(executable: URL(fileURLWithPath: "/tmp/hg199-activity/checkout/venv/bin/hermes"),
        checkoutRoot: URL(fileURLWithPath: "/tmp/hg199-activity/checkout"), hermesHome: URL(fileURLWithPath: "/tmp/hg199-activity/home"),
        commit: String(repeating: "a", count: 40), version: "0.21.6", identityChangedAt: .distantPast)
    var command: UpgradeProcessCommand { .init(executable: "/bin/python", arguments: [installation.executable.path, "serve"]) }
    var processes: ActivityProcesses { .init(before: [process], after: [process], command: command) }
    var identity: String { #"{"ok":true,"protocolVersion":1,"pid":42,"role":"desktop-serve","servesSpa":false}"# }
    var health: String { #"{"ok":true,"version":"0.21.6","commit":"COMMIT","auth_required":false}"#.replacingOccurrences(of: "COMMIT", with: installation.commit) }
    var idle: String { #"{"ok":true,"idle":true,"reason":null}"# }
    func probe(identity: String? = nil, health: String? = nil, idle: String? = nil, processes: ActivityProcesses? = nil) -> UpgradeActivityProbe {
        .init(transport: ActivityResponses(identity: identity ?? self.identity, health: health ?? self.health, idle: idle ?? self.idle), processes: processes ?? self.processes)
    }
}
private actor ActivityResponses: UpgradeActivityTransport {
    let identity: String; let health: String; let idle: String; let finalIdentity: String?
    let duringIdle: (@Sendable () throws -> Void)?
    private var identities = 0
    init(identity: String, health: String, idle: String, finalIdentity: String? = nil, duringIdle: (@Sendable () throws -> Void)? = nil) {
        self.identity = identity; self.health = health; self.idle = idle; self.finalIdentity = finalIdentity
        self.duringIdle = duringIdle
    }
    func read(_ endpoint: UpgradeActivityEndpoint, token: String) async throws -> Data {
        switch endpoint {
        case .identity: identities += 1; return Data((identities > 1 ? finalIdentity ?? identity : identity).utf8)
        case .health: return Data(health.utf8)
        case .idle: try duringIdle?(); return Data(idle.utf8)
        }
    }
}
private final class ActivityProcesses: UpgradeProcessReading, @unchecked Sendable {
    let before: [DesktopHermesUpgradeInspection.ProcessIdentity]?
    let after: [DesktopHermesUpgradeInspection.ProcessIdentity]?
    let commandValue: UpgradeProcessCommand
    let afterCommand: UpgradeProcessCommand?
    private let lock = NSLock(); private var reads = 0
    init(before: [DesktopHermesUpgradeInspection.ProcessIdentity]?, after: [DesktopHermesUpgradeInspection.ProcessIdentity]?, command: UpgradeProcessCommand,
         afterCommand: UpgradeProcessCommand? = nil) {
        self.before = before; self.after = after; self.commandValue = command; self.afterCommand = afterCommand
    }
    func identities() -> [DesktopHermesUpgradeInspection.ProcessIdentity]? {
        lock.lock(); defer { lock.unlock() }; reads += 1
        return reads == 1 ? before : after
    }
    func command(pid: Int32) -> UpgradeProcessCommand? {
        lock.lock(); defer { lock.unlock() }
        return reads > 1 ? afterCommand ?? commandValue : commandValue
    }
}
private struct NoActivityReads: UpgradeActivityTransport {
    func read(_ endpoint: UpgradeActivityEndpoint, token: String) async throws -> Data {
        XCTFail("unsafe authenticated read"); throw DesktopHermesUpgradeError.preflightBlocked
    }
}
private final class ActivityHTTPServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "hg199-activity-http")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var recorded: [String] = []
    private(set) var port: UInt16 = 0
    var requests: [String] { lock.lock(); defer { lock.unlock() }; return recorded }
    init(reply: String?) throws {
        let parameters = NWParameters.tcp
        listener = try NWListener(using: parameters, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() }; if case .failed = $0 { ready.signal() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.lock.lock(); self.connections.append(connection); self.lock.unlock()
            connection.start(queue: self.queue)
            connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, _, _, _ in
                guard let self else { connection.cancel(); return }
                self.lock.lock(); self.recorded.append(String(decoding: data ?? Data(), as: UTF8.self)); self.lock.unlock()
                if let reply { connection.send(content: Data(reply.utf8), completion: .contentProcessed { _ in connection.cancel() }) }
            }
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 2) == .success, let port = listener.port?.rawValue, port > 0 else {
            listener.cancel(); throw DesktopHermesUpgradeError.checkFailed
        }
        self.port = port
    }
    func stop() {
        listener.cancel(); lock.lock(); let active = connections; lock.unlock()
        active.forEach { $0.cancel() }
    }
}

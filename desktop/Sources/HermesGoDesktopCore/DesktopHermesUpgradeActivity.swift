import Foundation

/// A diagnostic of one authenticated serve process, never an all-profile idle/retirement permit.
/// Raw reasons, ledger IDs, responses and credentials are deliberately not retained.
public struct DesktopHermesUpgradeActivity: Equatable, Codable, Sendable {
    public enum State: String, Codable, Sendable { case idleObserved, busy, unknown }
    public enum Ledger: String, Codable, Sendable { case admission, session, delegation, cron, humanInput, unclassified }
    public let sourceIdentity: String
    public let checkedAt: Date
    public let process: DesktopHermesUpgradeInspection.ProcessIdentity?
    public let state: State
    public let ledger: Ledger?
    public var writerAdmissionProven: Bool { false }
    public var allProfileInactivityProven: Bool { false }

    public func isFresh(for source: String, now: Date = Date()) -> Bool {
        sourceIdentity == source && checkedAt.timeIntervalSince1970.isFinite &&
            (0...5).contains(now.timeIntervalSince(checkedAt))
    }
    static func unknown(_ installation: DesktopLocalHermesInstallation, at date: Date = Date()) -> Self {
        .init(sourceIdentity: installation.runtimeIdentity, checkedAt: date, process: nil, state: .unknown, ledger: nil)
    }
}

enum UpgradeActivityEndpoint: String, Sendable {
    case identity = "/api/host/identity", health = "/api/health", idle = "/api/health/idle"
}
protocol UpgradeActivityTransport: Sendable {
    func read(_ endpoint: UpgradeActivityEndpoint, token: String) async throws -> Data
}

/// Fixed loopback destination, no proxies/cookies/cache/redirects; bounded while receiving bytes.
struct UpgradeActivityHTTP: UpgradeActivityTransport {
    private let base: URL
    init() { base = DesktopHermesRuntimeContract.serveV1.baseURL }
    // Disposable loopback tests only. Product callers cannot choose a credential destination.
    init(testPort: UInt16) { base = URL(string: "http://127.0.0.1:\(testPort)")! }
    func read(_ endpoint: UpgradeActivityEndpoint, token: String) async throws -> Data {
        let configuration = HTTPHealthProber.loopbackDirectConfiguration()
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 1
        configuration.timeoutIntervalForResource = 1
        let session = URLSession(configuration: configuration, delegate: LoopbackRedirectGuard(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let url = URL(string: endpoint.rawValue, relativeTo: base)!.absoluteURL
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 1)
        request.httpMethod = "GET"
        request.setValue(token, forHTTPHeaderField: "X-Hermes-Session-Token")
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, http.url == url,
              response.expectedContentLength <= 4096 else { throw DesktopHermesUpgradeError.preflightBlocked }
        var data = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard data.count < 4096 else { throw DesktopHermesUpgradeError.preflightBlocked }
            data.append(byte)
        }
        return data
    }
}

struct UpgradeActivityProbe: Sendable {
    private let transport: any UpgradeActivityTransport
    private let processes: any UpgradeProcessReading
    init(transport: any UpgradeActivityTransport = UpgradeActivityHTTP(), processes: any UpgradeProcessReading = UpgradeKernelProcesses()) {
        self.transport = transport; self.processes = processes
    }
    func inspect(_ installation: DesktopLocalHermesInstallation, token: String) async -> DesktopHermesUpgradeActivity {
        let began = Date()
        do {
            guard DesktopManagedInstaller.validSessionToken(token), !Task.isCancelled,
                  let before = processes.identities() else { return .unknown(installation, at: began) }
            let identity = try await transport.read(.identity, token: token)
            let pid = try hostPID(identity)
            guard let process = before.first(where: { $0.pid == pid }),
                  let command = processes.command(pid: pid),
                  DesktopHermesUpgradeInspector.candidate(process, command: command, checkout: installation.checkoutRoot)?.association == .checkout,
                  command.arguments.contains("serve") else { return .unknown(installation, at: began) }
            let health = try object(await transport.read(.health, token: token), keys: ["ok", "version", "displayVersion", "commit", "auth_required"])
            // auth_required describes dashboard ticket authentication, not the private session
            // token checked by identity/idle. GO's Desktop-owned loopback backend sets it false.
            guard bool(health["ok"]) == true, bool(health["auth_required"]) != nil,
                  health["commit"] as? String == installation.commit,
                  health["version"] as? String == installation.version else { return .unknown(installation, at: began) }
            let idle = try object(await transport.read(.idle, token: token), keys: ["ok", "idle", "reason", "detail"])
            guard bool(idle["ok"]) == true,
                  try hostPID(await transport.read(.identity, token: token)) == pid,
                  processes.identities()?.first(where: { $0.pid == pid }) == process,
                  let afterCommand = processes.command(pid: pid), afterCommand.executable == command.executable,
                  afterCommand.arguments == command.arguments, !Task.isCancelled,
                  (0...5).contains(Date().timeIntervalSince(began)) else { return .unknown(installation, at: began) }
            let state: DesktopHermesUpgradeActivity.State
            let ledger: DesktopHermesUpgradeActivity.Ledger?
            switch (bool(idle["idle"]), idle["reason"] as? String) {
            case (true, nil) where idle["reason"] is NSNull && (idle["detail"] == nil || idle["detail"] is NSNull):
                state = .idleObserved; ledger = nil
            case (false, "awaiting_human_input"):
                state = .busy; ledger = .humanInput
            case (false, "turn_in_flight"):
                state = .busy
                let detail = idle["detail"] as? String ?? ""
                if detail == "retirement_admission" { ledger = .admission }
                else if detail.hasPrefix("session:") { ledger = .session }
                else if detail == "delegation" { ledger = .delegation }
                else if detail.hasPrefix("cron:") { ledger = .cron }
                else { ledger = .unclassified }
            default: state = .unknown; ledger = nil
            }
            return .init(sourceIdentity: installation.runtimeIdentity, checkedAt: began, process: process, state: state, ledger: ledger)
        } catch { return .unknown(installation, at: began) }
    }
    private func hostPID(_ data: Data) throws -> Int32 {
        let value = try object(data, keys: ["ok", "protocolVersion", "pid", "role", "servesSpa"])
        guard bool(value["ok"]) == true, integer(value["protocolVersion"]) == 1,
              let pid = integer(value["pid"]), pid > 0, pid <= Int(Int32.max),
              ["serve", "desktop-serve"].contains(value["role"] as? String ?? ""), bool(value["servesSpa"]) == false
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        return Int32(pid)
    }
    private func bool(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
    private func integer(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              number.doubleValue.isFinite, number.doubleValue == Double(number.int64Value),
              number.int64Value >= 0, number.int64Value <= Int64(Int32.max) else { return nil }
        return number.intValue
    }
    private func object(_ data: Data, keys: Set<String>) throws -> [String: Any] {
        // The pinned diagnostic vocabulary needs no escapes. Reject ambiguous/duplicate keys,
        // unexpected nested data or future shapes rather than normalizing them into idle.
        guard data.count <= 4096, let text = String(data: data, encoding: .utf8), !text.contains("\\"),
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(value.keys).isSubset(of: keys),
              value.values.allSatisfy({ $0 is NSNull || $0 is String || $0 is NSNumber }),
              let expression = try? NSRegularExpression(pattern: "\"(?:" + keys.sorted().joined(separator: "|") + ")\"\\s*:"),
              expression.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text)) == value.count
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        return value
    }
}

extension DesktopManagedInstaller {
    /// Read-only and opt-in via the existing condition check. No prepare/commit/cancel POSTs.
    public func inspectHermesUpgradeActivity(installation: DesktopLocalHermesInstallation) async -> DesktopHermesUpgradeActivity {
        await inspectHermesUpgradeActivity(installation: installation, probe: UpgradeActivityProbe())
    }
    func inspectHermesUpgradeActivity(installation: DesktopLocalHermesInstallation, probe: UpgradeActivityProbe) async -> DesktopHermesUpgradeActivity {
        do {
            let local = try loadLocalHermesLaunchAgent()
            let tokenData = try UpgradePrivateFiles.read(layout.hermesSessionToken, limit: 128)
            guard local.executable == installation.executable, localHermesLauncherIsCurrent(executable: local.executable),
                  let token = String(data: tokenData, encoding: .utf8),
                  Self.validSessionToken(token) else { return .unknown(installation) }
            let report = await probe.inspect(installation, token: token)
            guard try UpgradePrivateFiles.read(layout.hermesSessionToken, limit: 128) == tokenData,
                  try loadLocalHermesLaunchAgent().executable == installation.executable,
                  localHermesLauncherIsCurrent(executable: installation.executable) else { return .unknown(installation) }
            return report
        } catch { return .unknown(installation) }
    }
}

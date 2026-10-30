import Foundation

public enum DesktopConnectorProcessState: Equatable, Sendable {
    case running, stopped, notLoaded, unknown

    public static func inspect(userID: UInt32) -> Self {
        let output = SystemOutputCommandRunner().run(
            executable: URL(fileURLWithPath: "/bin/launchctl"),
            arguments: ["print", "gui/\(userID)/\(DesktopManagedInstallLayout.connectorLabel)"],
            maximumOutputBytes: 256 * 1024
        )
        guard !output.outputLimitExceeded else { return .unknown }
        if output.status == 113 { return .notLoaded }
        guard output.status == 0, let text = String(data: output.stdout, encoding: .utf8) else { return .unknown }
        return parse(text)
    }

    static func parse(_ text: String, processExists: (Int32) -> Bool = {
        DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.startDate(pid: $0) != nil
    }) -> Self {
        // Resource-coalition sections have their own state; only the service's top-level fields count.
        let fields = text.components(separatedBy: "\n").filter { $0.hasPrefix("\t") && !$0.hasPrefix("\t\t") }
        if let line = fields.first(where: { $0.hasPrefix("\tpid = ") }) {
            guard let pid = Int32(line.dropFirst("\tpid = ".count)), pid > 0, processExists(pid) else { return .unknown }
            return .running
        }
        return fields.contains("\tstate = not running") ? .stopped : .unknown
    }
}

/// At most three attempts per app session, spaced apart. Manual recovery remains available.
public struct DesktopConnectorRecoveryPolicy: Sendable {
    private var failures = 0
    private var lastAttempt: TimeInterval?
    public init() {}

    public mutating func begin(process: DesktopConnectorProcessState, eligible: Bool, now: TimeInterval) -> Bool {
        guard eligible, process == .stopped, failures < 3,
              lastAttempt.map({ now - $0 >= 60 }) ?? true else { return false }
        lastAttempt = now
        failures += 1
        return true
    }

    public mutating func succeeded() { failures = 0; lastAttempt = nil }
    public mutating func blocked() { failures = 3 }
}

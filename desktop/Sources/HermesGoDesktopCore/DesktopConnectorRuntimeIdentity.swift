import Darwin
import Foundation

/// Disk/launchd declarations are insufficient: prove the live Node executable and its exact
/// Connector script. Only bounded kernel argv is read; it is never logged or returned as diagnostics.
public enum DesktopConnectorRuntimeIdentity {
    public static func verify(launchAgentURL: URL, userID: UInt32) -> Bool {
        let output = SystemOutputCommandRunner().run(executable: URL(fileURLWithPath: "/bin/launchctl"),
            arguments: ["print", "gui/\(userID)/\(DesktopManagedInstallLayout.connectorLabel)"], maximumOutputBytes: 256 * 1024)
        guard output.status == 0, !output.outputLimitExceeded,
              let text = String(data: output.stdout, encoding: .utf8),
              let pid = DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.pid(fromLaunchctlPrint: text),
              DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.startDate(pid: pid) != nil,
              let data = try? Data(contentsOf: launchAgentURL),
              let object = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let expected = object["ProgramArguments"] as? [String], expected.count == 1,
              expected == DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.arguments(fromLaunchctlPrint: text),
              let program = expected.first,
              let environment = object["EnvironmentVariables"] as? [String: String],
              let actual = processIdentity(pid: pid)
        else { return false }
        let connector = URL(fileURLWithPath: program).deletingLastPathComponent().deletingLastPathComponent().resolvingSymlinksInPath()
        let node: URL
        if let root = environment["HERMES_NODE_RUNTIME_ROOT"] {
            let nodeRoot = URL(fileURLWithPath: root).resolvingSymlinksInPath()
            guard validContentRoot(nodeRoot), validContentRoot(connector) else { return false }
            node = nodeRoot.appendingPathComponent("bin/node")
        } else {
            node = connector.appendingPathComponent("runtime/node/bin/node")
        }
        guard let paths = try? DesktopManagedBootstrapPaths.currentUser(),
              let live = processes(managedRoot: paths.managedRoot, userID: userID), live.count == 1, live[pid] != nil else { return false }
        return matches(executable: actual.executable, actual: actual.arguments, node: node.path,
                       script: connector.appendingPathComponent("app/connector/dist/index.js").path)
    }

    /// Include orphaned managed processes: an unloaded job alone cannot prove they have exited.
    /// Only PID/comm is listed; argv is inspected solely for Node and never exposed as diagnostics.
    static func processes(managedRoot: URL, userID: UInt32) -> [Int32: Date]? {
        let result = SystemOutputCommandRunner().run(executable: URL(fileURLWithPath: "/bin/ps"),
            arguments: ["-U", String(userID), "-o", "pid=,comm="], maximumOutputBytes: 512 * 1024)
        guard result.status == 0, !result.outputLimitExceeded, let text = String(data: result.stdout, encoding: .utf8) else { return nil }
        var found: [Int32: Date] = [:]
        let prefix = managedRoot.resolvingSymlinksInPath().path + "/"
        for line in text.split(separator: "\n") {
            let fields = line.split(maxSplits: 1, whereSeparator: { $0.isWhitespace })
            guard fields.count == 2, let pid = Int32(fields[0]) else { continue }
            let command = String(fields[1]).trimmingCharacters(in: .whitespaces)
            guard command == "node" || command.hasSuffix("/node") else { continue }
            guard let args = arguments(pid: pid) else {
                if command.hasPrefix(prefix) { return nil }
                continue
            }
            if args.contains(where: { $0.hasPrefix(prefix) && $0.hasSuffix("/app/connector/dist/index.js") }) {
                guard let started = DesktopLaunchdHermesServiceProcessInspector<SystemOutputCommandRunner>.startDate(pid: pid) else { return nil }
                found[pid] = started
            }
        }
        return found
    }

    static func matches(actual: [String], node: String, script: String) -> Bool {
        actual == [node, script]
    }

    private static func validContentRoot(_ root: URL) -> Bool {
        let hash = root.deletingLastPathComponent().lastPathComponent
        return root.lastPathComponent == "content" && hash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
            && (try? DesktopManagedComponentContentHasher().identify(directory: root).sha256) == hash
    }

    static func matches(executable: String, actual: [String], node: String, script: String) -> Bool {
        executable == node && matches(actual: actual, node: node, script: script)
    }

    struct ProcessIdentity: Equatable {
        let executable: String
        let arguments: [String]
    }

    static func arguments(pid: Int32) -> [String]? { processIdentity(pid: pid)?.arguments }

    static func processIdentity(pid: Int32) -> ProcessIdentity? {
        var mib: [Int32] = [CTL_KERN, KERN_PROCARGS2, pid]
        var size = 0
        guard sysctl(&mib, 3, nil, &size, nil, 0) == 0, size > 4, size <= 1_048_576 else { return nil }
        var bytes = [UInt8](repeating: 0, count: size)
        guard sysctl(&mib, 3, &bytes, &size, nil, 0) == 0 else { return nil }
        return decodeProcessIdentity(Array(bytes.prefix(size)))
    }

    static func decodeProcessIdentity(_ bytes: [UInt8]) -> ProcessIdentity? {
        let size = bytes.count
        guard size > 4 else { return nil }
        let argc = bytes.withUnsafeBytes { $0.loadUnaligned(as: Int32.self) }
        guard argc > 0, argc <= 128 else { return nil }
        var index = 4
        // Kernel executable path precedes argv, padded with NULs.
        while index < size && bytes[index] != 0 { index += 1 }
        guard index < size, let executable = String(bytes: bytes[4..<index], encoding: .utf8),
              executable.hasPrefix("/") else { return nil }
        while index < size && bytes[index] == 0 { index += 1 }
        var result: [String] = []
        for _ in 0..<argc {
            let start = index
            while index < size && bytes[index] != 0 { index += 1 }
            guard index < size, let value = String(bytes: bytes[start..<index], encoding: .utf8) else { return nil }
            result.append(value); index += 1
        }
        return ProcessIdentity(executable: executable, arguments: result)
    }
}

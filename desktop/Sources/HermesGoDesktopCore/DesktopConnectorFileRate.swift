import Foundation

/// A per-Mac operator override for the Connector's aggregate file-return budget.
/// Keep this allowlist narrow: arbitrary Node environment must never become trusted by repair.
enum DesktopConnectorFileRate {
    static let isolationKey = "CONNECTOR_TRANSPORT_ISOLATION"
    static func overrides(from environment: [String: String]) throws -> [String: String] {
        var result: [String: String] = [:]
        if let rate = try read(from: environment) { result[environmentKey] = rate }
        if let isolation = environment[isolationKey] {
            guard isolation == "0" || isolation == "1" else { throw DesktopLaunchAgentError.invalidConfiguration }
            result[isolationKey] = isolation
        }
        return result
    }
    static let environmentKey = "FILE_DOWNLOAD_BYTES_PER_SECOND"
    static let maximum = 128 * 1024 * 1024

    static func read(from environment: [String: String]) throws -> String? {
        guard let raw = environment[environmentKey] else { return nil }
        guard let value = Int(raw), (1...maximum).contains(value), String(value) == raw else {
            throw DesktopLaunchAgentError.invalidConfiguration
        }
        return raw
    }
}

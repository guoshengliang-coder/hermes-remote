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
        if let roots = try externalReadRoots(from: environment) { result[externalReadRootsKey] = roots }
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

    /// HG-245: authorized external read-only attachment roots must survive managed reinstalls
    /// and stay acceptable to repair's exact-environment comparison. The value is validated but
    /// passed through verbatim — normalizing it here would make repair reject the operator's own
    /// plist over whitespace the Connector itself tolerates. A relative or filesystem-root entry
    /// refuses the migration loudly instead of silently shifting the read boundary; the Connector
    /// applies the same contract at startup (`resolveExternalReadRoots`).
    static let externalReadRootsKey = "EXTRA_READ_ROOTS"

    static func externalReadRoots(from environment: [String: String]) throws -> String? {
        guard let raw = environment[externalReadRootsKey] else { return nil }
        var hasEntry = false
        for entry in raw.split(separator: ";", omittingEmptySubsequences: true) {
            let trimmed = entry.trimmingCharacters(in: .whitespacesAndNewlines)
            // Blank segments are ignored, matching the Connector's own EXTRA_READ_ROOTS parsing.
            guard !trimmed.isEmpty else { continue }
            hasEntry = true
            // Reject relative entries and every spelling of the filesystem root ("/", "//"),
            // which would otherwise widen the grant to a whole-disk read.
            guard trimmed.hasPrefix("/"), !trimmed.allSatisfy({ $0 == "/" }),
                  !trimmed.contains("\0") else {
                throw DesktopLaunchAgentError.invalidConfiguration
            }
        }
        return hasEntry ? raw : nil
    }
}

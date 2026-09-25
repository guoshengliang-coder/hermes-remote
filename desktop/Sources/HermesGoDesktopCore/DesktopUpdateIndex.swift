import Foundation

/// Semantic version rules shared by Desktop update discovery. Identical to the manifest verifier's
/// rule: exactly three numeric components with no leading zeros.
public enum DesktopSemanticVersion {
    public static func components(_ value: String) -> [Int]? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        let parsed = parts.compactMap { Int($0) }
        guard parsed.count == 3,
              zip(parts, parsed).allSatisfy({ String($0.1) == $0.0 })
        else { return nil }
        return parsed
    }

    public static func isNewer(_ candidate: String, than installed: String) -> Bool {
        guard let candidate = components(candidate),
              let installed = components(installed)
        else { return false }
        return candidate != installed && !candidate.lexicographicallyPrecedes(installed)
    }
}

public enum DesktopAppUpdateIndexError: Error, Equatable, Sendable {
    case invalidIndex
    case unsafeDownloadURL
}

public struct DesktopAppUpdateReference: Equatable, Sendable {
    public let appVersion: String
    public let buildNumber: Int
    public let minimumMacOS: String
    public let downloadURL: URL
    public let sizeBytes: Int64
    public let sha256: String
    public let releaseNotes: [String]
    public let sourceCommit: String

    public init(
        appVersion: String,
        buildNumber: Int,
        minimumMacOS: String,
        downloadURL: URL,
        sizeBytes: Int64,
        sha256: String,
        releaseNotes: [String],
        sourceCommit: String
    ) {
        self.appVersion = appVersion
        self.buildNumber = buildNumber
        self.minimumMacOS = minimumMacOS
        self.downloadURL = downloadURL
        self.sizeBytes = sizeBytes
        self.sha256 = sha256
        self.releaseNotes = releaseNotes
        self.sourceCommit = sourceCommit
    }
}

/// Discovery only, like `DesktopReleaseIndex`. The DMG this points at is a self-contained app, so the
/// download URL rule is the only authorization here: the user confirms the install, and the staged
/// copy is checked for the exact signed-off version before it replaces anything.
public enum DesktopAppUpdateIndex {
    private static let maximumIndexBytes = 256 * 1024
    private static let maximumReleaseNotes = 20
    private static let maximumNoteLength = 500
    private static let maximumAppBytes: Int64 = 2 * 1024 * 1024 * 1024

    public static func resolve(
        _ data: Data,
        indexURL: URL,
        expectedChannel: String,
        expectedArchitecture: String
    ) throws -> DesktopAppUpdateReference {
        guard data.count <= maximumIndexBytes,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set([
                  "schemaVersion", "channel", "architecture", "appVersion", "buildNumber",
                  "minimumMacOS", "downloadURL", "sizeBytes", "sha256", "releaseNotes",
                  "sourceCommit", "updatedAt",
              ]),
              object["schemaVersion"] as? Int == 1,
              object["channel"] as? String == expectedChannel,
              object["architecture"] as? String == expectedArchitecture,
              let appVersion = object["appVersion"] as? String,
              DesktopSemanticVersion.components(appVersion) != nil,
              let buildNumber = object["buildNumber"] as? Int,
              buildNumber > 0,
              let minimumMacOS = object["minimumMacOS"] as? String,
              parseOperatingSystemVersion(minimumMacOS) != nil,
              let rawURL = object["downloadURL"] as? String,
              let downloadURL = URL(string: rawURL),
              let size = object["sizeBytes"] as? Int,
              size > 0,
              Int64(size) <= maximumAppBytes,
              let sha256 = object["sha256"] as? String,
              sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              let rawNotes = object["releaseNotes"] as? [String],
              validReleaseNotes(rawNotes),
              let sourceCommit = object["sourceCommit"] as? String,
              sourceCommit.range(of: "^[0-9a-f]{40}$", options: .regularExpression) != nil,
              let updatedAt = object["updatedAt"] as? String,
              canonicalDate(updatedAt)
        else { throw DesktopAppUpdateIndexError.invalidIndex }

        try validateDownloadURL(downloadURL, indexURL: indexURL, appVersion: appVersion)
        return DesktopAppUpdateReference(
            appVersion: appVersion,
            buildNumber: buildNumber,
            minimumMacOS: minimumMacOS,
            downloadURL: downloadURL,
            sizeBytes: Int64(size),
            sha256: sha256,
            releaseNotes: rawNotes,
            sourceCommit: sourceCommit
        )
    }

    static func validReleaseNotes(_ notes: [String]) -> Bool {
        guard notes.count <= maximumReleaseNotes else { return false }
        return notes.allSatisfy { note in
            !note.isEmpty
                && note.utf8.count <= maximumNoteLength
                && !note.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        }
    }

    private static func validateDownloadURL(
        _ value: URL,
        indexURL: URL,
        appVersion: String
    ) throws {
        guard value.scheme == "https",
              value.scheme == indexURL.scheme,
              value.host?.lowercased() == indexURL.host?.lowercased(),
              value.port == indexURL.port,
              value.user == nil, value.password == nil,
              value.query == nil, value.fragment == nil,
              value.lastPathComponent == "Hermes-Go-Desktop-\(appVersion).dmg",
              !value.pathComponents.contains(".."),
              let encoded = URLComponents(url: value, resolvingAgainstBaseURL: false)?
                .percentEncodedPath.lowercased(),
              !encoded.contains("%2f"), !encoded.contains("%5c")
        else { throw DesktopAppUpdateIndexError.unsafeDownloadURL }

        let base = indexURL.deletingLastPathComponent().path
        let expectedPrefix = base + "/" + appVersion + "/"
        guard value.path.hasPrefix(expectedPrefix) else {
            throw DesktopAppUpdateIndexError.unsafeDownloadURL
        }
    }

    private static func parseOperatingSystemVersion(_ value: String) -> OperatingSystemVersion? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (2...3).contains(parts.count),
              parts.allSatisfy({ $0.range(of: "^(0|[1-9][0-9]*)$", options: .regularExpression) != nil })
        else { return nil }
        return OperatingSystemVersion(
            majorVersion: Int(parts[0])!,
            minorVersion: Int(parts[1])!,
            patchVersion: parts.count == 3 ? Int(parts[2])! : 0
        )
    }

    private static func canonicalDate(_ value: String) -> Bool {
        guard value.range(
            of: #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$"#,
            options: .regularExpression
        ) != nil else { return false }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = value.contains(".")
            ? [.withInternetDateTime, .withFractionalSeconds]
            : [.withInternetDateTime]
        return formatter.date(from: value) != nil
    }
}

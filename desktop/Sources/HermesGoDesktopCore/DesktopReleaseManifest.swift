import CryptoKit
import Foundation

public enum DesktopReleaseComponentKind: String, Codable, CaseIterable, Sendable {
    case hermesServer = "hermes_server"
    case connector
}

public struct DesktopReleaseArtifact: Codable, Equatable, Sendable {
    public let component: DesktopReleaseComponentKind
    public let version: String
    public let fileName: String
    public let entrypoint: String
    public let downloadURL: String
    public let sizeBytes: Int64
    public let sha256: String

    public init(
        component: DesktopReleaseComponentKind,
        version: String,
        fileName: String,
        entrypoint: String,
        downloadURL: String,
        sizeBytes: Int64,
        sha256: String
    ) {
        self.component = component
        self.version = version
        self.fileName = fileName
        self.entrypoint = entrypoint
        self.downloadURL = downloadURL
        self.sizeBytes = sizeBytes
        self.sha256 = sha256
    }
}

public struct DesktopReleaseManifest: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let releaseVersion: String
    public let channel: String
    public let platform: String
    public let architecture: String
    public let minimumMacOS: String
    public let createdAt: String
    public let expiresAt: String
    public let artifacts: [DesktopReleaseArtifact]

    public init(
        schemaVersion: Int = 1,
        releaseVersion: String,
        channel: String,
        platform: String = "macos",
        architecture: String,
        minimumMacOS: String,
        createdAt: String,
        expiresAt: String,
        artifacts: [DesktopReleaseArtifact]
    ) {
        self.schemaVersion = schemaVersion
        self.releaseVersion = releaseVersion
        self.channel = channel
        self.platform = platform
        self.architecture = architecture
        self.minimumMacOS = minimumMacOS
        self.createdAt = createdAt
        self.expiresAt = expiresAt
        self.artifacts = artifacts
    }
}

public enum DesktopReleaseVerificationError: Error, Equatable, Sendable {
    case invalidConfiguration
    case responseTooLarge
    case invalidEnvelope
    case unknownField
    case unknownSigningKey
    case invalidSignature
    case invalidManifest
    case incompatibleRelease
}

public struct DesktopReleaseManifestVerifier: Sendable {
    private static let maximumEnvelopeBytes = 256 * 1024
    private static let maximumPayloadBytes = 128 * 1024
    private static let maximumArtifactBytes: Int64 = 2 * 1024 * 1024 * 1024
    private static let allowedClockSkew: TimeInterval = 5 * 60
    private static let maximumManifestLifetime: TimeInterval = 30 * 24 * 60 * 60

    private let expectedOrigin: URL
    private let expectedChannel: String
    private let expectedArchitecture: String
    private let currentMacOS: OperatingSystemVersion
    private let signingKeys: [String: Data]
    private let now: @Sendable () -> Date

    public init(
        expectedOrigin: URL,
        expectedChannel: String,
        expectedArchitecture: String,
        currentMacOS: OperatingSystemVersion = ProcessInfo.processInfo.operatingSystemVersion,
        signingKeys: [String: Data],
        now: @escaping @Sendable () -> Date = { Date() }
    ) throws {
        guard Self.validHTTPSOrigin(expectedOrigin),
              Self.validIdentifier(expectedChannel, maximum: 32),
              ["arm64", "x86_64", "universal"].contains(expectedArchitecture),
              !signingKeys.isEmpty,
              signingKeys.allSatisfy({ key, value in
                  Self.validIdentifier(key, maximum: 64) && value.count == 32
              })
        else { throw DesktopReleaseVerificationError.invalidConfiguration }
        self.expectedOrigin = expectedOrigin
        self.expectedChannel = expectedChannel
        self.expectedArchitecture = expectedArchitecture
        self.currentMacOS = currentMacOS
        self.signingKeys = signingKeys
        self.now = now
    }

    public func verify(_ envelopeData: Data) throws -> DesktopReleaseManifest {
        guard envelopeData.count <= Self.maximumEnvelopeBytes else {
            throw DesktopReleaseVerificationError.responseTooLarge
        }
        let envelopeObject = try jsonObject(envelopeData)
        try requireExactKeys(
            envelopeObject,
            expected: ["payload", "keyId", "algorithm", "signature"]
        )
        guard let payloadValue = envelopeObject["payload"] as? String,
              let keyID = envelopeObject["keyId"] as? String,
              let algorithm = envelopeObject["algorithm"] as? String,
              let signatureValue = envelopeObject["signature"] as? String,
              algorithm == "Ed25519",
              Self.validIdentifier(keyID, maximum: 64),
              let payload = Data(canonicalBase64URL: payloadValue),
              payload.count <= Self.maximumPayloadBytes,
              let signature = Data(canonicalBase64URL: signatureValue),
              signature.count == 64
        else { throw DesktopReleaseVerificationError.invalidEnvelope }
        guard let rawKey = signingKeys[keyID] else {
            throw DesktopReleaseVerificationError.unknownSigningKey
        }
        let publicKey: Curve25519.Signing.PublicKey
        do {
            publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: rawKey)
        } catch {
            throw DesktopReleaseVerificationError.invalidConfiguration
        }
        guard publicKey.isValidSignature(signature, for: payload) else {
            throw DesktopReleaseVerificationError.invalidSignature
        }

        let payloadObject = try jsonObject(payload)
        try requireExactKeys(payloadObject, expected: [
            "schemaVersion", "releaseVersion", "channel", "platform", "architecture",
            "minimumMacOS", "createdAt", "expiresAt", "artifacts",
        ])
        guard let artifactObjects = payloadObject["artifacts"] as? [[String: Any]],
              artifactObjects.count == DesktopReleaseComponentKind.allCases.count
        else { throw DesktopReleaseVerificationError.invalidManifest }
        for artifact in artifactObjects {
            try requireExactKeys(artifact, expected: [
                "component", "version", "fileName", "downloadURL", "sizeBytes", "sha256",
                "entrypoint",
            ])
        }

        let manifest: DesktopReleaseManifest
        do {
            manifest = try JSONDecoder().decode(DesktopReleaseManifest.self, from: payload)
        } catch {
            throw DesktopReleaseVerificationError.invalidManifest
        }
        try validate(manifest)
        return manifest
    }

    private func validate(_ manifest: DesktopReleaseManifest) throws {
        let currentTime = now()
        guard manifest.schemaVersion == 1,
              manifest.channel == expectedChannel,
              manifest.platform == "macos",
              manifest.architecture == expectedArchitecture || manifest.architecture == "universal",
              Self.validSemanticVersion(manifest.releaseVersion),
              let minimumMacOS = Self.parseOperatingSystemVersion(manifest.minimumMacOS),
              Self.compare(currentMacOS, minimumMacOS) != .orderedAscending,
              let createdAt = Self.parseCanonicalDate(manifest.createdAt),
              let expiresAt = Self.parseCanonicalDate(manifest.expiresAt),
              createdAt <= currentTime.addingTimeInterval(Self.allowedClockSkew),
              expiresAt > currentTime,
              expiresAt.timeIntervalSince(createdAt) <= Self.maximumManifestLifetime,
              Set(manifest.artifacts.map(\.component)) == Set(DesktopReleaseComponentKind.allCases)
        else { throw DesktopReleaseVerificationError.incompatibleRelease }

        for artifact in manifest.artifacts {
            guard Self.validSemanticVersion(artifact.version),
                  Self.validArtifactFileName(artifact.fileName, component: artifact.component),
                  Self.validRelativePath(artifact.entrypoint),
                  artifact.sizeBytes > 0,
                  artifact.sizeBytes <= Self.maximumArtifactBytes,
                  artifact.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                  let url = URL(string: artifact.downloadURL),
                  validArtifactURL(url, fileName: artifact.fileName)
            else { throw DesktopReleaseVerificationError.invalidManifest }
        }
    }

    private func validArtifactURL(_ value: URL, fileName: String) -> Bool {
        guard value.scheme == "https",
              value.host?.lowercased() == expectedOrigin.host?.lowercased(),
              value.port == expectedOrigin.port,
              value.user == nil,
              value.password == nil,
              value.query == nil,
              value.fragment == nil,
              value.lastPathComponent == fileName,
              !value.pathComponents.contains("..")
        else { return false }
        guard let encodedPath = URLComponents(url: value, resolvingAgainstBaseURL: false)?
            .percentEncodedPath.lowercased()
        else { return false }
        return !encodedPath.contains("%2f") && !encodedPath.contains("%5c")
    }

    private func jsonObject(_ data: Data) throws -> [String: Any] {
        let value: Any
        do {
            value = try JSONSerialization.jsonObject(with: data, options: [])
        } catch {
            throw DesktopReleaseVerificationError.invalidEnvelope
        }
        guard let object = value as? [String: Any] else {
            throw DesktopReleaseVerificationError.invalidEnvelope
        }
        return object
    }

    private func requireExactKeys(_ object: [String: Any], expected: Set<String>) throws {
        guard Set(object.keys) == expected else {
            throw DesktopReleaseVerificationError.unknownField
        }
    }

    private static func validHTTPSOrigin(_ value: URL) -> Bool {
        value.scheme == "https"
            && value.host != nil
            && value.user == nil
            && value.password == nil
            && value.query == nil
            && value.fragment == nil
            && (value.path.isEmpty || value.path == "/")
    }

    private static func validIdentifier(_ value: String, maximum: Int) -> Bool {
        guard (1...maximum).contains(value.utf8.count) else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._")
        return value.unicodeScalars.allSatisfy(allowed.contains)
    }

    private static func validSemanticVersion(_ value: String) -> Bool {
        value.range(of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$", options: .regularExpression) != nil
    }

    private static func validArtifactFileName(
        _ value: String,
        component: DesktopReleaseComponentKind
    ) -> Bool {
        let prefix = component == .connector ? "Hermes-Connector-" : "Hermes-Server-"
        let pattern = "^\(prefix)(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-(arm64|x86_64|universal)\\.tar\\.gz$"
        return value.range(of: pattern, options: .regularExpression) != nil
    }

    private static func validRelativePath(_ value: String) -> Bool {
        guard !value.isEmpty,
              value.utf8.count <= 256,
              !value.hasPrefix("/"),
              !value.hasSuffix("/"),
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { return false }
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        return parts.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    private static func parseCanonicalDate(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: value), formatter.string(from: date) == value { return date }
        let plain = ISO8601DateFormatter()
        guard let date = plain.date(from: value), plain.string(from: date) == value else { return nil }
        return date
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

    private static func compare(
        _ lhs: OperatingSystemVersion,
        _ rhs: OperatingSystemVersion
    ) -> ComparisonResult {
        let left = [lhs.majorVersion, lhs.minorVersion, lhs.patchVersion]
        let right = [rhs.majorVersion, rhs.minorVersion, rhs.patchVersion]
        for (a, b) in zip(left, right) {
            if a < b { return .orderedAscending }
            if a > b { return .orderedDescending }
        }
        return .orderedSame
    }
}

private extension Data {
    init?(canonicalBase64URL value: String) {
        guard !value.isEmpty,
              value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil
        else { return nil }
        let padding = String(repeating: "=", count: (4 - value.count % 4) % 4)
        let standard = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + padding
        guard let decoded = Data(base64Encoded: standard), decoded.base64URLEncoded == value else {
            return nil
        }
        self = decoded
    }

    var base64URLEncoded: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

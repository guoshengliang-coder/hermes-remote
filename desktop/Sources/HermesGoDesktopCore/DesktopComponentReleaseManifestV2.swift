import CryptoKit
import Foundation

public enum DesktopComponentReuseContract: String, Codable, Sendable {
    case exactContent = "exact_content"
    case verifiedCompatibility = "verified_compatibility"
}

public struct DesktopComponentReleaseDependency: Codable, Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let contentSHA256: String

    public init(kind: DesktopManagedComponentKind, contentSHA256: String) {
        self.kind = kind
        self.contentSHA256 = contentSHA256
    }
}

public struct DesktopComponentReleaseArtifactV2: Codable, Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let version: String
    public let architecture: String
    public let installPhase: DesktopManagedComponentInstallPhase
    public let requiredForBootstrap: Bool
    public let onDemandTrigger: String?
    public let reuseContract: DesktopComponentReuseContract
    public let compatibilityIdentifier: String?
    public let fileName: String
    public let entrypoint: String
    public let downloadURL: String
    public let sizeBytes: Int64
    public let sha256: String
    public let contentSHA256: String
    public let dependencies: [DesktopComponentReleaseDependency]

    public init(
        kind: DesktopManagedComponentKind,
        version: String,
        architecture: String,
        installPhase: DesktopManagedComponentInstallPhase,
        requiredForBootstrap: Bool,
        onDemandTrigger: String? = nil,
        reuseContract: DesktopComponentReuseContract,
        compatibilityIdentifier: String? = nil,
        fileName: String,
        entrypoint: String,
        downloadURL: String,
        sizeBytes: Int64,
        sha256: String,
        contentSHA256: String,
        dependencies: [DesktopComponentReleaseDependency]
    ) {
        self.kind = kind
        self.version = version
        self.architecture = architecture
        self.installPhase = installPhase
        self.requiredForBootstrap = requiredForBootstrap
        self.onDemandTrigger = onDemandTrigger
        self.reuseContract = reuseContract
        self.compatibilityIdentifier = compatibilityIdentifier
        self.fileName = fileName
        self.entrypoint = entrypoint
        self.downloadURL = downloadURL
        self.sizeBytes = sizeBytes
        self.sha256 = sha256
        self.contentSHA256 = contentSHA256
        self.dependencies = dependencies
    }

    public var preflightRequirement: DesktopManagedComponentRequirement {
        get throws {
            let policy: DesktopManagedComponentReusePolicy
            switch reuseContract {
            case .exactContent:
                guard compatibilityIdentifier == nil else {
                    throw DesktopComponentReleaseVerificationError.invalidManifest
                }
                policy = .exactContent(sha256: contentSHA256)
            case .verifiedCompatibility:
                guard let compatibilityIdentifier else {
                    throw DesktopComponentReleaseVerificationError.invalidManifest
                }
                policy = .verifiedCompatibility(
                    contentSHA256: contentSHA256,
                    identifier: compatibilityIdentifier
                )
            }
            return DesktopManagedComponentRequirement(
                kind: kind,
                version: version,
                architecture: architecture,
                downloadBytes: sizeBytes,
                installPhase: installPhase,
                reusePolicy: policy
            )
        }
    }
}

public struct DesktopComponentReleaseManifestV2: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let releaseVersion: String
    public let channel: String
    public let platform: String
    public let architecture: String
    public let minimumMacOS: String
    public let createdAt: String
    public let expiresAt: String
    public let components: [DesktopComponentReleaseArtifactV2]

    public init(
        schemaVersion: Int = 2,
        releaseVersion: String,
        channel: String,
        platform: String = "macos",
        architecture: String,
        minimumMacOS: String,
        createdAt: String,
        expiresAt: String,
        components: [DesktopComponentReleaseArtifactV2]
    ) {
        self.schemaVersion = schemaVersion
        self.releaseVersion = releaseVersion
        self.channel = channel
        self.platform = platform
        self.architecture = architecture
        self.minimumMacOS = minimumMacOS
        self.createdAt = createdAt
        self.expiresAt = expiresAt
        self.components = components
    }

    public var preflightRequirements: [DesktopManagedComponentRequirement] {
        get throws { try components.map { try $0.preflightRequirement } }
    }
}

public enum DesktopComponentReleaseVerificationError: Error, Equatable, Sendable {
    case invalidConfiguration
    case responseTooLarge
    case invalidEnvelope
    case unknownField
    case unknownSigningKey
    case invalidSignature
    case invalidManifest
    case incompatibleRelease
}

public struct DesktopComponentReleaseManifestV2Verifier: Sendable {
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
              Self.validArchitecture(expectedArchitecture),
              !signingKeys.isEmpty,
              signingKeys.allSatisfy({ key, value in
                  Self.validIdentifier(key, maximum: 64) && value.count == 32
              })
        else { throw DesktopComponentReleaseVerificationError.invalidConfiguration }
        self.expectedOrigin = expectedOrigin
        self.expectedChannel = expectedChannel
        self.expectedArchitecture = expectedArchitecture
        self.currentMacOS = currentMacOS
        self.signingKeys = signingKeys
        self.now = now
    }

    public func verify(_ envelopeData: Data) throws -> DesktopComponentReleaseManifestV2 {
        guard envelopeData.count <= Self.maximumEnvelopeBytes else {
            throw DesktopComponentReleaseVerificationError.responseTooLarge
        }
        let envelope = try jsonObject(envelopeData, invalid: .invalidEnvelope)
        try requireExactKeys(envelope, expected: ["payload", "keyId", "algorithm", "signature"])
        guard let payloadValue = envelope["payload"] as? String,
              let keyID = envelope["keyId"] as? String,
              envelope["algorithm"] as? String == "Ed25519",
              let signatureValue = envelope["signature"] as? String,
              Self.validIdentifier(keyID, maximum: 64),
              let payload = Data(componentManifestBase64URL: payloadValue),
              payload.count <= Self.maximumPayloadBytes,
              let signature = Data(componentManifestBase64URL: signatureValue),
              signature.count == 64
        else { throw DesktopComponentReleaseVerificationError.invalidEnvelope }
        guard let keyData = signingKeys[keyID] else {
            throw DesktopComponentReleaseVerificationError.unknownSigningKey
        }
        let publicKey: Curve25519.Signing.PublicKey
        do {
            publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: keyData)
        } catch {
            throw DesktopComponentReleaseVerificationError.invalidConfiguration
        }
        guard publicKey.isValidSignature(signature, for: payload) else {
            throw DesktopComponentReleaseVerificationError.invalidSignature
        }

        let object = try jsonObject(payload, invalid: .invalidManifest)
        try requireExactKeys(object, expected: [
            "schemaVersion", "releaseVersion", "channel", "platform", "architecture",
            "minimumMacOS", "createdAt", "expiresAt", "components",
        ])
        guard let components = object["components"] as? [[String: Any]],
              (2...DesktopManagedComponentKind.allCases.count).contains(components.count)
        else { throw DesktopComponentReleaseVerificationError.invalidManifest }
        for component in components {
            var expected: Set<String> = [
                "kind", "version", "architecture", "installPhase", "requiredForBootstrap",
                "reuseContract", "fileName", "entrypoint", "downloadURL", "sizeBytes", "sha256",
                "contentSHA256", "dependencies",
            ]
            if component["onDemandTrigger"] != nil { expected.insert("onDemandTrigger") }
            if component["compatibilityIdentifier"] != nil { expected.insert("compatibilityIdentifier") }
            try requireExactKeys(component, expected: expected)
            guard let dependencies = component["dependencies"] as? [[String: Any]] else {
                throw DesktopComponentReleaseVerificationError.invalidManifest
            }
            for dependency in dependencies {
                try requireExactKeys(dependency, expected: ["kind", "contentSHA256"])
            }
        }

        let manifest: DesktopComponentReleaseManifestV2
        do {
            manifest = try JSONDecoder().decode(DesktopComponentReleaseManifestV2.self, from: payload)
        } catch {
            throw DesktopComponentReleaseVerificationError.invalidManifest
        }
        try validate(manifest)
        return manifest
    }

    private func validate(_ manifest: DesktopComponentReleaseManifestV2) throws {
        let currentTime = now()
        guard manifest.schemaVersion == 2,
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
              expiresAt.timeIntervalSince(createdAt) <= Self.maximumManifestLifetime
        else { throw DesktopComponentReleaseVerificationError.incompatibleRelease }

        guard Set(manifest.components.map(\.kind)).count == manifest.components.count else {
            throw DesktopComponentReleaseVerificationError.invalidManifest
        }
        let byKind = Dictionary(uniqueKeysWithValues: manifest.components.map { ($0.kind, $0) })
        guard byKind[.hermesCore]?.installPhase == .bootstrap,
              byKind[.connector]?.installPhase == .bootstrap
        else { throw DesktopComponentReleaseVerificationError.invalidManifest }

        for component in manifest.components {
            guard Self.validSemanticVersion(component.version),
                  Self.validArchitecture(component.architecture),
                  component.architecture == manifest.architecture || component.architecture == "universal",
                  Self.validArtifactFileName(component.fileName, component: component),
                  Self.validRelativePath(component.entrypoint),
                  component.sizeBytes > 0,
                  component.sizeBytes <= Self.maximumArtifactBytes,
                  Self.validSHA256(component.sha256),
                  Self.validSHA256(component.contentSHA256),
                  let url = URL(string: component.downloadURL),
                  validArtifactURL(url, fileName: component.fileName),
                  validActivation(component),
                  validReuseContract(component),
                  Set(component.dependencies.map(\.kind)).count == component.dependencies.count
            else { throw DesktopComponentReleaseVerificationError.invalidManifest }
            for dependency in component.dependencies {
                guard dependency.kind != component.kind,
                      Self.validSHA256(dependency.contentSHA256),
                      byKind[dependency.kind]?.contentSHA256 == dependency.contentSHA256,
                      component.installPhase != .bootstrap
                        || byKind[dependency.kind]?.installPhase == .bootstrap
                else { throw DesktopComponentReleaseVerificationError.invalidManifest }
            }
        }
        guard !Self.hasDependencyCycle(manifest.components) else {
            throw DesktopComponentReleaseVerificationError.invalidManifest
        }
    }

    private func validActivation(_ component: DesktopComponentReleaseArtifactV2) -> Bool {
        switch component.installPhase {
        case .bootstrap:
            return component.requiredForBootstrap && component.onDemandTrigger == nil
        case .onDemand:
            return !component.requiredForBootstrap
                && component.onDemandTrigger.map { Self.validIdentifier($0, maximum: 96) } == true
        }
    }

    private func validReuseContract(_ component: DesktopComponentReleaseArtifactV2) -> Bool {
        switch component.reuseContract {
        case .exactContent:
            return component.compatibilityIdentifier == nil
        case .verifiedCompatibility:
            return component.kind == .browserAutomation
                && component.compatibilityIdentifier.map {
                    Self.validIdentifier($0, maximum: 96)
                } == true
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
              !value.pathComponents.contains(".."),
              let encodedPath = URLComponents(url: value, resolvingAgainstBaseURL: false)?.percentEncodedPath.lowercased()
        else { return false }
        return !encodedPath.contains("%2f") && !encodedPath.contains("%5c")
    }

    private func jsonObject(
        _ data: Data,
        invalid: DesktopComponentReleaseVerificationError
    ) throws -> [String: Any] {
        do {
            guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw invalid
            }
            return object
        } catch let error as DesktopComponentReleaseVerificationError {
            throw error
        } catch {
            throw invalid
        }
    }

    private func requireExactKeys(_ object: [String: Any], expected: Set<String>) throws {
        guard Set(object.keys) == expected else {
            throw DesktopComponentReleaseVerificationError.unknownField
        }
    }

    private static func hasDependencyCycle(_ components: [DesktopComponentReleaseArtifactV2]) -> Bool {
        let graph = Dictionary(uniqueKeysWithValues: components.map { component in
            (component.kind, component.dependencies.map(\.kind))
        })
        var visiting = Set<DesktopManagedComponentKind>()
        var visited = Set<DesktopManagedComponentKind>()
        func visit(_ kind: DesktopManagedComponentKind) -> Bool {
            if visiting.contains(kind) { return true }
            if visited.contains(kind) { return false }
            visiting.insert(kind)
            for dependency in graph[kind, default: []] where visit(dependency) { return true }
            visiting.remove(kind)
            visited.insert(kind)
            return false
        }
        return graph.keys.contains(where: visit)
    }

    private static func validHTTPSOrigin(_ value: URL) -> Bool {
        value.scheme == "https" && value.host != nil && value.user == nil && value.password == nil
            && value.query == nil && value.fragment == nil && (value.path.isEmpty || value.path == "/")
    }

    private static func validIdentifier(_ value: String, maximum: Int) -> Bool {
        guard (1...maximum).contains(value.utf8.count) else { return false }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._")
        return value.unicodeScalars.allSatisfy(allowed.contains)
    }

    private static func validSemanticVersion(_ value: String) -> Bool {
        value.range(of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$", options: .regularExpression) != nil
    }

    private static func validArchitecture(_ value: String) -> Bool {
        ["arm64", "x86_64", "universal"].contains(value)
    }

    private static func validSHA256(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }

    private static func validArtifactFileName(
        _ value: String,
        component: DesktopComponentReleaseArtifactV2
    ) -> Bool {
        value == "Hermes-Component-\(component.kind.rawValue)-\(component.version)-\(component.architecture).tar.gz"
    }

    private static func validRelativePath(_ value: String) -> Bool {
        guard !value.isEmpty, value.utf8.count <= 256, !value.hasPrefix("/"), !value.hasSuffix("/"),
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { return false }
        return value.split(separator: "/", omittingEmptySubsequences: false)
            .allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    private static func parseCanonicalDate(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value), fractional.string(from: date) == value { return date }
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
            majorVersion: Int(parts[0])!, minorVersion: Int(parts[1])!,
            patchVersion: parts.count == 3 ? Int(parts[2])! : 0
        )
    }

    private static func compare(
        _ lhs: OperatingSystemVersion,
        _ rhs: OperatingSystemVersion
    ) -> ComparisonResult {
        for (left, right) in zip(
            [lhs.majorVersion, lhs.minorVersion, lhs.patchVersion],
            [rhs.majorVersion, rhs.minorVersion, rhs.patchVersion]
        ) {
            if left < right { return .orderedAscending }
            if left > right { return .orderedDescending }
        }
        return .orderedSame
    }
}

private extension Data {
    init?(componentManifestBase64URL value: String) {
        guard !value.isEmpty,
              value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil
        else { return nil }
        let padding = String(repeating: "=", count: (4 - value.count % 4) % 4)
        let standard = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + padding
        guard let decoded = Data(base64Encoded: standard), decoded.componentManifestBase64URLEncoded == value else {
            return nil
        }
        self = decoded
    }

    var componentManifestBase64URLEncoded: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

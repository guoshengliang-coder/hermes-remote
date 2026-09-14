import CryptoKit
import Foundation

public struct DesktopManagedComponentIdentity: Equatable, Hashable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let contentSHA256: String

    public init(kind: DesktopManagedComponentKind, contentSHA256: String) {
        self.kind = kind
        self.contentSHA256 = contentSHA256
    }
}

public struct DesktopManagedComponentGarbageCollectionCandidate: Equatable, Sendable {
    public let identity: DesktopManagedComponentIdentity
    public let containerURL: URL
    public let fileBytes: Int64

    public init(
        identity: DesktopManagedComponentIdentity,
        containerURL: URL,
        fileBytes: Int64
    ) {
        self.identity = identity
        self.containerURL = containerURL
        self.fileBytes = fileBytes
    }
}

public struct DesktopManagedComponentGarbageCollectionPlan: Equatable, Sendable {
    public let retained: [DesktopManagedComponentIdentity]
    public let candidates: [DesktopManagedComponentGarbageCollectionCandidate]
    public let storeSnapshotSHA256: String

    public var reclaimableFileBytes: Int64 {
        candidates.reduce(0) { $0 + $1.fileBytes }
    }
}

public enum DesktopManagedComponentGarbageCollectionError: Error, Equatable, Sendable {
    case invalidRoot
    case unsafeStore
    case storeTooLarge
    case invalidReference
    case invalidComponent
    case missingProtectedReference
    case missingReferencedComponent
}

/// Produces a read-only, fail-closed snapshot of the shared component store. A later deleter must
/// hold the installation operation lease and reproduce the same snapshot before removing any exact
/// candidate URL; this planner never mutates the store.
public struct DesktopManagedComponentGarbageCollectionPlanner: @unchecked Sendable {
    private static let maximumReferences = 1_024
    private static let maximumComponentsPerKind = 4_096
    private static let maximumReferenceBytes = 64 * 1_024

    private let root: URL
    private let currentUserID: UInt32
    private let fileManager: FileManager
    private let hasher: DesktopManagedComponentContentHasher

    public init(
        root: URL,
        currentUserID: UInt32,
        fileManager: FileManager = .default
    ) throws {
        let normalized = root.standardizedFileURL
        guard normalized.isFileURL, normalized.path.hasPrefix("/"), normalized.path != "/" else {
            throw DesktopManagedComponentGarbageCollectionError.invalidRoot
        }
        self.root = normalized
        self.currentUserID = currentUserID
        self.fileManager = fileManager
        hasher = DesktopManagedComponentContentHasher(fileManager: fileManager)
    }

    public func plan(
        protectedReleaseVersions: Set<String>
    ) throws -> DesktopManagedComponentGarbageCollectionPlan {
        guard protectedReleaseVersions.allSatisfy(Self.validVersion) else {
            throw DesktopManagedComponentGarbageCollectionError.invalidReference
        }
        guard fileManager.fileExists(atPath: root.path) else {
            if try isSymbolicLink(root) {
                throw DesktopManagedComponentGarbageCollectionError.unsafeStore
            }
            guard protectedReleaseVersions.isEmpty else {
                throw DesktopManagedComponentGarbageCollectionError.missingProtectedReference
            }
            return emptyPlan()
        }
        try requireOwnedObject(root, kind: .privateDirectory)

        var snapshot = SHA256()
        let references = try readReferences(snapshot: &snapshot)
        guard protectedReleaseVersions.isSubset(of: Set(references.keys)) else {
            throw DesktopManagedComponentGarbageCollectionError.missingProtectedReference
        }
        let capabilityReferences = try readCapabilityReferences(
            baseReleaseVersions: Set(references.keys),
            snapshot: &snapshot
        )
        let referenced = Set(references.values.flatMap { $0 }).union(capabilityReferences)
        let inventory = try readComponents(snapshot: &snapshot)
        guard referenced.isSubset(of: Set(inventory.keys)) else {
            throw DesktopManagedComponentGarbageCollectionError.missingReferencedComponent
        }

        let retained = referenced.sorted(by: Self.identityOrder)
        let candidates = inventory
            .filter { !referenced.contains($0.key) }
            .map { identity, value in
                DesktopManagedComponentGarbageCollectionCandidate(
                    identity: identity,
                    containerURL: value.container,
                    fileBytes: value.fileBytes
                )
            }
            .sorted { Self.identityOrder($0.identity, $1.identity) }
        return DesktopManagedComponentGarbageCollectionPlan(
            retained: retained,
            candidates: candidates,
            storeSnapshotSHA256: Self.hex(snapshot.finalize())
        )
    }

    private func readReferences(
        snapshot: inout SHA256
    ) throws -> [String: Set<DesktopManagedComponentIdentity>] {
        let directory = root.appendingPathComponent("references", isDirectory: true)
        guard fileManager.fileExists(atPath: directory.path) else {
            if try isSymbolicLink(directory) {
                throw DesktopManagedComponentGarbageCollectionError.unsafeStore
            }
            return [:]
        }
        try requireOwnedObject(directory, kind: .privateDirectory)
        let entries = try contents(of: directory)
        guard entries.count <= Self.maximumReferences else {
            throw DesktopManagedComponentGarbageCollectionError.storeTooLarge
        }

        var result: [String: Set<DesktopManagedComponentIdentity>] = [:]
        for entry in entries.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            try requireOwnedObject(entry, kind: .privateFile)
            let filename = entry.lastPathComponent
            guard filename.hasSuffix(".json") else {
                throw DesktopManagedComponentGarbageCollectionError.invalidReference
            }
            let version = String(filename.dropLast(5))
            guard Self.validVersion(version) else {
                throw DesktopManagedComponentGarbageCollectionError.invalidReference
            }
            let data = try Data(contentsOf: entry, options: [.mappedIfSafe])
            guard data.count <= Self.maximumReferenceBytes else {
                throw DesktopManagedComponentGarbageCollectionError.invalidReference
            }
            let reference = try decodeReference(data, filenameVersion: version)
            guard result[version] == nil else {
                throw DesktopManagedComponentGarbageCollectionError.invalidReference
            }
            let identities = reference.components.map {
                DesktopManagedComponentIdentity(kind: $0.kind, contentSHA256: $0.contentSHA256)
            }
            guard Set(identities.map(\.kind)).count == identities.count else {
                throw DesktopManagedComponentGarbageCollectionError.invalidReference
            }
            result[version] = Set(identities)
            snapshot.update(data: Data("R\0\(filename)\0".utf8))
            snapshot.update(data: data)
            snapshot.update(data: Data([0]))
        }
        return result
    }

    private func readCapabilityReferences(
        baseReleaseVersions: Set<String>,
        snapshot: inout SHA256
    ) throws -> Set<DesktopManagedComponentIdentity> {
        let directory = root.appendingPathComponent(
            "capability-references", isDirectory: true
        )
        guard fileManager.fileExists(atPath: directory.path) else {
            if try isSymbolicLink(directory) {
                throw DesktopManagedComponentGarbageCollectionError.unsafeStore
            }
            return []
        }
        try requireOwnedObject(directory, kind: .privateDirectory)
        let releaseDirectories = try contents(of: directory)
        guard releaseDirectories.count <= Self.maximumReferences else {
            throw DesktopManagedComponentGarbageCollectionError.storeTooLarge
        }
        let capabilityKinds: Set<DesktopManagedComponentKind> = [
            .browserAutomation, .speechRuntime, .documentTools,
        ]
        var result: Set<DesktopManagedComponentIdentity> = []
        for releaseDirectory in releaseDirectories.sorted(by: {
            $0.lastPathComponent < $1.lastPathComponent
        }) {
            let releaseVersion = releaseDirectory.lastPathComponent
            guard Self.validVersion(releaseVersion),
                  baseReleaseVersions.contains(releaseVersion)
            else { throw DesktopManagedComponentGarbageCollectionError.invalidReference }
            try requireOwnedObject(releaseDirectory, kind: .privateDirectory)
            let entries = try contents(of: releaseDirectory)
            guard !entries.isEmpty, entries.count <= capabilityKinds.count else {
                throw DesktopManagedComponentGarbageCollectionError.invalidReference
            }
            for entry in entries.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                try requireOwnedObject(entry, kind: .privateFile)
                let filename = entry.lastPathComponent
                guard filename.hasSuffix(".json"),
                      let kind = DesktopManagedComponentKind(
                        rawValue: String(filename.dropLast(5))
                      ),
                      capabilityKinds.contains(kind)
                else { throw DesktopManagedComponentGarbageCollectionError.invalidReference }
                let data = try Data(contentsOf: entry, options: [.mappedIfSafe])
                guard data.count <= Self.maximumReferenceBytes,
                      let reference = try? decodeCapabilityReference(
                        data,
                        releaseVersion: releaseVersion,
                        kind: kind
                      )
                else { throw DesktopManagedComponentGarbageCollectionError.invalidReference }
                let identity = DesktopManagedComponentIdentity(
                    kind: reference.component.kind,
                    contentSHA256: reference.component.contentSHA256
                )
                result.insert(identity)
                snapshot.update(data: Data(
                    "O\0\(releaseVersion)\0\(filename)\0".utf8
                ))
                snapshot.update(data: data)
                snapshot.update(data: Data([0]))
            }
        }
        return result
    }

    private func readComponents(
        snapshot: inout SHA256
    ) throws -> [DesktopManagedComponentIdentity: (container: URL, fileBytes: Int64)] {
        let directory = root.appendingPathComponent("components", isDirectory: true)
        guard fileManager.fileExists(atPath: directory.path) else {
            if try isSymbolicLink(directory) {
                throw DesktopManagedComponentGarbageCollectionError.unsafeStore
            }
            return [:]
        }
        try requireOwnedObject(directory, kind: .privateDirectory)
        let kindEntries = try contents(of: directory)
        guard kindEntries.count <= DesktopManagedComponentKind.allCases.count else {
            throw DesktopManagedComponentGarbageCollectionError.invalidComponent
        }

        var result: [DesktopManagedComponentIdentity: (container: URL, fileBytes: Int64)] = [:]
        for kindDirectory in kindEntries.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            guard let kind = DesktopManagedComponentKind(rawValue: kindDirectory.lastPathComponent) else {
                throw DesktopManagedComponentGarbageCollectionError.invalidComponent
            }
            try requireOwnedObject(kindDirectory, kind: .privateDirectory)
            let containers = try contents(of: kindDirectory)
            guard containers.count <= Self.maximumComponentsPerKind else {
                throw DesktopManagedComponentGarbageCollectionError.storeTooLarge
            }
            for container in containers.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let hash = container.lastPathComponent
                guard Self.validSHA256(hash) else {
                    throw DesktopManagedComponentGarbageCollectionError.invalidComponent
                }
                try requireOwnedObject(container, kind: .privateDirectory)
                let children = try contents(of: container)
                guard Set(children.map(\.lastPathComponent)) == ["content", "receipt.json"],
                      children.count == 2
                else { throw DesktopManagedComponentGarbageCollectionError.invalidComponent }
                let content = container.appendingPathComponent("content", isDirectory: true)
                let receiptURL = container.appendingPathComponent("receipt.json")
                try requireOwnedObject(content, kind: .safeDirectory)
                try requireOwnedObject(receiptURL, kind: .privateFile)
                let receiptData = try Data(contentsOf: receiptURL, options: [.mappedIfSafe])
                guard receiptData.count <= 16 * 1_024,
                      let receipt = try? decodeReceipt(receiptData),
                      receipt.kind == kind,
                      receipt.contentSHA256 == hash
                else { throw DesktopManagedComponentGarbageCollectionError.invalidComponent }
                let requirement = DesktopManagedComponentRequirement(
                    kind: receipt.kind,
                    version: receipt.version,
                    architecture: receipt.architecture,
                    downloadBytes: 1,
                    installPhase: .bootstrap,
                    reusePolicy: .exactContent(sha256: receipt.contentSHA256)
                )
                guard (try? DesktopManagedComponentPreflightPlanner.plan(
                    requirements: [requirement], candidates: []
                )) != nil else {
                    throw DesktopManagedComponentGarbageCollectionError.invalidComponent
                }
                let contentIdentity = try hasher.identify(directory: content)
                guard contentIdentity.sha256 == hash else {
                    throw DesktopManagedComponentGarbageCollectionError.invalidComponent
                }
                let identity = DesktopManagedComponentIdentity(
                    kind: kind, contentSHA256: hash
                )
                guard result[identity] == nil else {
                    throw DesktopManagedComponentGarbageCollectionError.invalidComponent
                }
                result[identity] = (container, contentIdentity.fileBytes)
                snapshot.update(data: Data("C\0\(kind.rawValue)\0\(hash)\0".utf8))
                snapshot.update(data: receiptData)
                snapshot.update(data: Data([0]))
            }
        }
        return result
    }

    private func decodeReference(
        _ data: Data,
        filenameVersion: String
    ) throws -> DesktopManagedComponentReferenceSet {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any],
              Set(dictionary.keys) == ["schemaVersion", "releaseVersion", "components"],
              let rawComponents = dictionary["components"] as? [[String: Any]],
              !rawComponents.isEmpty,
              rawComponents.allSatisfy({
                  Set($0.keys) == ["kind", "contentSHA256"]
              }),
              let reference = try? JSONDecoder().decode(
                  DesktopManagedComponentReferenceSet.self, from: data
              ),
              reference.schemaVersion == 1,
              reference.releaseVersion == filenameVersion,
              reference.components.allSatisfy({ Self.validSHA256($0.contentSHA256) })
        else { throw DesktopManagedComponentGarbageCollectionError.invalidReference }
        return reference
    }

    private func decodeCapabilityReference(
        _ data: Data,
        releaseVersion: String,
        kind: DesktopManagedComponentKind
    ) throws -> DesktopManagedCapabilityReference {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any],
              Set(dictionary.keys) == ["schemaVersion", "releaseVersion", "component"],
              let rawComponent = dictionary["component"] as? [String: Any],
              Set(rawComponent.keys) == ["kind", "contentSHA256"],
              let reference = try? JSONDecoder().decode(
                DesktopManagedCapabilityReference.self, from: data
              ),
              reference.schemaVersion == 1,
              reference.releaseVersion == releaseVersion,
              reference.component.kind == kind,
              Self.validSHA256(reference.component.contentSHA256)
        else { throw DesktopManagedComponentGarbageCollectionError.invalidReference }
        return reference
    }

    private func decodeReceipt(_ data: Data) throws -> DesktopManagedComponentReceipt {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let dictionary = object as? [String: Any],
              Set(dictionary.keys) == [
                  "schemaVersion", "kind", "version", "architecture", "contentSHA256",
              ],
              let receipt = try? JSONDecoder().decode(
                  DesktopManagedComponentReceipt.self, from: data
              ),
              receipt.schemaVersion == 1
        else { throw DesktopManagedComponentGarbageCollectionError.invalidComponent }
        return receipt
    }

    private enum ObjectKind {
        case privateDirectory
        case safeDirectory
        case privateFile
    }

    private func requireOwnedObject(_ value: URL, kind: ObjectKind) throws {
        let resource = try value.resourceValues(forKeys: [
            .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey,
        ])
        let attributes = try fileManager.attributesOfItem(atPath: value.path)
        let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value
        let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0
        let correctType: Bool
        switch kind {
        case .privateDirectory, .safeDirectory:
            correctType = resource.isDirectory == true
        case .privateFile:
            correctType = resource.isRegularFile == true
        }
        let forbiddenPermissions = kind == .safeDirectory ? 0o022 : 0o077
        guard correctType, resource.isSymbolicLink != true,
              owner == currentUserID, permissions & forbiddenPermissions == 0
        else { throw DesktopManagedComponentGarbageCollectionError.unsafeStore }
    }

    private func contents(of directory: URL) throws -> [URL] {
        do {
            return try fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: [
                    .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey,
                ],
                options: []
            )
        } catch {
            throw DesktopManagedComponentGarbageCollectionError.unsafeStore
        }
    }

    private func isSymbolicLink(_ value: URL) throws -> Bool {
        (try? value.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true
    }

    private func emptyPlan() -> DesktopManagedComponentGarbageCollectionPlan {
        DesktopManagedComponentGarbageCollectionPlan(
            retained: [],
            candidates: [],
            storeSnapshotSHA256: Self.hex(SHA256.hash(data: Data()))
        )
    }

    private static func identityOrder(
        _ lhs: DesktopManagedComponentIdentity,
        _ rhs: DesktopManagedComponentIdentity
    ) -> Bool {
        (lhs.kind.rawValue, lhs.contentSHA256) < (rhs.kind.rawValue, rhs.contentSHA256)
    }

    private static func validVersion(_ value: String) -> Bool {
        value.range(
            of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$",
            options: .regularExpression
        ) != nil
    }

    private static func validSHA256(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }

    private static func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
        digest.map { String(format: "%02x", $0) }.joined()
    }
}

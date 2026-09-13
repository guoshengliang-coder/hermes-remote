import CryptoKit
import Foundation

public struct DesktopManagedComponentContentIdentity: Equatable, Sendable {
    public let sha256: String
    public let entryCount: Int
    public let fileBytes: Int64
}

public enum DesktopManagedComponentStoreError: Error, Equatable, Sendable {
    case invalidRoot
    case invalidRunID
    case unsafeTree
    case treeTooLarge
    case invalidReceipt
    case identityMismatch
    case healthProbeFailed
    case workspaceAlreadyExists
    case componentConflict
    case referenceConflict
    case cleanupFailed
}

/// Computes a stable identity over relative paths, file bytes, and the executable bit. Component
/// trees may contain only directories and regular files; links and special files fail closed.
public struct DesktopManagedComponentContentHasher: @unchecked Sendable {
    public static let maximumEntries = 65_536
    public static let maximumFileBytes: Int64 = 2 * 1_024 * 1_024 * 1_024

    private let fileManager: FileManager

    public init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    public func identify(directory: URL) throws -> DesktopManagedComponentContentIdentity {
        let suppliedRoot = directory.standardizedFileURL
        guard suppliedRoot.isFileURL, suppliedRoot.path.hasPrefix("/"), suppliedRoot.path != "/",
              try safeDirectory(suppliedRoot)
        else { throw DesktopManagedComponentStoreError.unsafeTree }
        let root = suppliedRoot.resolvingSymlinksInPath()

        let keys: [URLResourceKey] = [
            .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey,
        ]
        var enumerationFailed = false
        guard let enumerator = fileManager.enumerator(
            at: root,
            includingPropertiesForKeys: keys,
            options: [],
            errorHandler: { _, _ in
                enumerationFailed = true
                return false
            }
        ) else { throw DesktopManagedComponentStoreError.unsafeTree }
        let entries = enumerator.compactMap { $0 as? URL }.sorted { $0.path < $1.path }
        guard !enumerationFailed else { throw DesktopManagedComponentStoreError.unsafeTree }
        guard entries.count <= Self.maximumEntries else {
            throw DesktopManagedComponentStoreError.treeTooLarge
        }

        var digest = SHA256()
        var fileBytes: Int64 = 0
        for entry in entries {
            let values = try entry.resourceValues(forKeys: Set(keys))
            guard values.isSymbolicLink != true else {
                throw DesktopManagedComponentStoreError.unsafeTree
            }
            let normalizedEntry = entry.standardizedFileURL.resolvingSymlinksInPath()
            let relative = try relativePath(normalizedEntry, beneath: root)
            if values.isDirectory == true {
                digest.update(data: Data("D\0\(relative)\0".utf8))
                continue
            }
            guard values.isRegularFile == true,
                  let size = values.fileSize,
                  size >= 0
            else { throw DesktopManagedComponentStoreError.unsafeTree }
            let byteCount = Int64(size)
            guard fileBytes <= Self.maximumFileBytes - byteCount else {
                throw DesktopManagedComponentStoreError.treeTooLarge
            }
            fileBytes += byteCount
            let attributes = try fileManager.attributesOfItem(atPath: entry.path)
            let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0
            let executable = permissions & 0o111 == 0 ? "0" : "1"
            digest.update(data: Data("F\0\(relative)\0\(executable)\0\(size)\0".utf8))
            try update(&digest, withContentsOf: entry, expectedBytes: byteCount)
            digest.update(data: Data([0]))
        }
        return DesktopManagedComponentContentIdentity(
            sha256: digest.finalize().map { String(format: "%02x", $0) }.joined(),
            entryCount: entries.count,
            fileBytes: fileBytes
        )
    }

    private func safeDirectory(_ value: URL) throws -> Bool {
        let values = try value.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        return values.isDirectory == true && values.isSymbolicLink != true
    }

    private func relativePath(_ value: URL, beneath root: URL) throws -> String {
        let prefix = root.path.hasSuffix("/") ? root.path : root.path + "/"
        guard value.path.hasPrefix(prefix) else {
            throw DesktopManagedComponentStoreError.unsafeTree
        }
        let relative = String(value.path.dropFirst(prefix.count))
        guard !relative.isEmpty,
              !relative.split(separator: "/", omittingEmptySubsequences: false).contains(where: {
                  $0.isEmpty || $0 == "." || $0 == ".."
              })
        else { throw DesktopManagedComponentStoreError.unsafeTree }
        return relative
    }

    private func update(
        _ digest: inout SHA256,
        withContentsOf file: URL,
        expectedBytes: Int64
    ) throws {
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        var observed: Int64 = 0
        while let data = try handle.read(upToCount: 1_024 * 1_024), !data.isEmpty {
            observed += Int64(data.count)
            guard observed <= expectedBytes else {
                throw DesktopManagedComponentStoreError.unsafeTree
            }
            digest.update(data: data)
        }
        guard observed == expectedBytes else {
            throw DesktopManagedComponentStoreError.unsafeTree
        }
    }
}

public struct DesktopManagedComponentReceipt: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let kind: DesktopManagedComponentKind
    public let version: String
    public let architecture: String
    public let contentSHA256: String

    public init(
        schemaVersion: Int = 1,
        kind: DesktopManagedComponentKind,
        version: String,
        architecture: String,
        contentSHA256: String
    ) {
        self.schemaVersion = schemaVersion
        self.kind = kind
        self.version = version
        self.architecture = architecture
        self.contentSHA256 = contentSHA256
    }
}

/// Read-only inventory for the future shared component store. A matching receipt is only a lookup
/// hint: the complete tree identity and a caller-supplied bounded health probe must still pass.
public struct DesktopManagedComponentStoreInspector: @unchecked Sendable {
    public typealias HealthProbe = @Sendable (URL) throws -> Bool

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
            throw DesktopManagedComponentStoreError.invalidRoot
        }
        self.root = normalized
        self.currentUserID = currentUserID
        self.fileManager = fileManager
        hasher = DesktopManagedComponentContentHasher(fileManager: fileManager)
    }

    public func candidate(
        for requirement: DesktopManagedComponentRequirement,
        healthProbe: HealthProbe
    ) throws -> DesktopManagedComponentCandidate? {
        let expectedIdentity: String
        switch requirement.reusePolicy {
        case .exactContent(let sha256), .verifiedCompatibility(let sha256, _):
            expectedIdentity = sha256
        }
        let component = root
            .appendingPathComponent("components", isDirectory: true)
            .appendingPathComponent(requirement.kind.rawValue, isDirectory: true)
            .appendingPathComponent(expectedIdentity, isDirectory: true)
            .appendingPathComponent("content", isDirectory: true)
        let container = component.deletingLastPathComponent()
        let receiptURL = container.appendingPathComponent("receipt.json")
        guard fileManager.fileExists(atPath: component.path),
              fileManager.fileExists(atPath: receiptURL.path)
        else { return nil }
        try requireOwnedSafeObject(container, directory: true)
        try requireOwnedSafeObject(component, directory: true)
        try requireOwnedSafeObject(receiptURL, directory: false)
        let receiptData = try Data(contentsOf: receiptURL, options: [.mappedIfSafe])
        guard receiptData.count <= 16 * 1_024 else {
            throw DesktopManagedComponentStoreError.invalidReceipt
        }
        let object = try JSONSerialization.jsonObject(with: receiptData)
        guard let dictionary = object as? [String: Any],
              Set(dictionary.keys) == [
                  "schemaVersion", "kind", "version", "architecture", "contentSHA256",
              ],
              let receipt = try? JSONDecoder().decode(DesktopManagedComponentReceipt.self, from: receiptData),
              receipt.schemaVersion == 1,
              receipt.kind == requirement.kind,
              receipt.version == requirement.version,
              receipt.architecture == requirement.architecture,
              receipt.contentSHA256 == expectedIdentity
        else { throw DesktopManagedComponentStoreError.invalidReceipt }
        let identity = try hasher.identify(directory: component)
        guard identity.sha256 == expectedIdentity else {
            throw DesktopManagedComponentStoreError.identityMismatch
        }
        return DesktopManagedComponentCandidate(
            kind: receipt.kind,
            version: receipt.version,
            architecture: receipt.architecture,
            source: .managedStore,
            contentSHA256: receipt.contentSHA256,
            healthProbePassed: try healthProbe(component)
        )
    }

    private func requireOwnedSafeObject(_ value: URL, directory: Bool) throws {
        let resource = try value.resourceValues(forKeys: [
            .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey,
        ])
        guard resource.isSymbolicLink != true,
              directory ? resource.isDirectory == true : resource.isRegularFile == true
        else { throw DesktopManagedComponentStoreError.unsafeTree }
        let attributes = try fileManager.attributesOfItem(atPath: value.path)
        let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value
        let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0
        guard owner == currentUserID, permissions & 0o022 == 0 else {
            throw DesktopManagedComponentStoreError.unsafeTree
        }
    }
}

public struct DesktopManagedComponentReference: Codable, Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let contentSHA256: String

    public init(kind: DesktopManagedComponentKind, contentSHA256: String) {
        self.kind = kind
        self.contentSHA256 = contentSHA256
    }
}

public struct DesktopManagedComponentReferenceSet: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let releaseVersion: String
    public let components: [DesktopManagedComponentReference]

    public init(
        schemaVersion: Int = 1,
        releaseVersion: String,
        components: [DesktopManagedComponentReference]
    ) {
        self.schemaVersion = schemaVersion
        self.releaseVersion = releaseVersion
        self.components = components
    }
}

/// Atomically commits one already verified component container. The final move carries content and
/// receipt together, so a power loss cannot expose one without the other. Release references are
/// written separately and idempotently; later garbage collection may retain every referenced hash.
public final class DesktopManagedComponentStoreWriter: @unchecked Sendable {
    public typealias HealthProbe = @Sendable (URL) throws -> Bool

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
            throw DesktopManagedComponentStoreError.invalidRoot
        }
        self.root = normalized
        self.currentUserID = currentUserID
        self.fileManager = fileManager
        hasher = DesktopManagedComponentContentHasher(fileManager: fileManager)
    }

    @discardableResult
    public func commit(
        sourceDirectory: URL,
        receipt: DesktopManagedComponentReceipt,
        runID: String,
        healthProbe: HealthProbe
    ) throws -> URL {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopManagedComponentStoreError.invalidRunID
        }
        guard receipt.schemaVersion == 1,
              receipt.contentSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
        else { throw DesktopManagedComponentStoreError.invalidReceipt }
        let requirement = DesktopManagedComponentRequirement(
            kind: receipt.kind,
            version: receipt.version,
            architecture: receipt.architecture,
            downloadBytes: 1,
            installPhase: .bootstrap,
            reusePolicy: .exactContent(sha256: receipt.contentSHA256)
        )
        _ = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [requirement], candidates: []
        )
        let sourceIdentity = try hasher.identify(directory: sourceDirectory)
        guard sourceIdentity.sha256 == receipt.contentSHA256 else {
            throw DesktopManagedComponentStoreError.identityMismatch
        }

        try preparePrivateDirectory(root)
        let componentsRoot = root.appendingPathComponent("components", isDirectory: true)
        let kindRoot = componentsRoot.appendingPathComponent(receipt.kind.rawValue, isDirectory: true)
        let stagingRoot = root.appendingPathComponent(".staging", isDirectory: true)
        try preparePrivateDirectory(componentsRoot)
        try preparePrivateDirectory(kindRoot)
        try preparePrivateDirectory(stagingRoot)
        let destination = kindRoot.appendingPathComponent(receipt.contentSHA256, isDirectory: true)
        if fileManager.fileExists(atPath: destination.path) {
            return try requireExisting(requirement, healthProbe: healthProbe)
        }

        let workspace = stagingRoot.appendingPathComponent(normalizedRunID, isDirectory: true)
        guard !fileManager.fileExists(atPath: workspace.path) else {
            throw DesktopManagedComponentStoreError.workspaceAlreadyExists
        }
        do {
            try fileManager.createDirectory(
                at: workspace, withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
            let content = workspace.appendingPathComponent("content", isDirectory: true)
            try fileManager.copyItem(at: sourceDirectory, to: content)
            guard try hasher.identify(directory: content).sha256 == receipt.contentSHA256 else {
                throw DesktopManagedComponentStoreError.identityMismatch
            }
            guard try healthProbe(content) else {
                throw DesktopManagedComponentStoreError.healthProbeFailed
            }
            let receiptURL = workspace.appendingPathComponent("receipt.json")
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(receipt).write(to: receiptURL, options: [.withoutOverwriting])
            try fileManager.setAttributes(
                [.posixPermissions: 0o600], ofItemAtPath: receiptURL.path
            )
            do {
                try fileManager.moveItem(at: workspace, to: destination)
            } catch {
                if fileManager.fileExists(atPath: destination.path) {
                    try cleanup(workspace, beneath: stagingRoot)
                    return try requireExisting(requirement, healthProbe: healthProbe)
                }
                throw DesktopManagedComponentStoreError.componentConflict
            }
            return destination.appendingPathComponent("content", isDirectory: true)
        } catch {
            if fileManager.fileExists(atPath: workspace.path) {
                do { try cleanup(workspace, beneath: stagingRoot) }
                catch { throw DesktopManagedComponentStoreError.cleanupFailed }
            }
            throw error
        }
    }

    public func recordReferences(
        releaseVersion: String,
        receipts: [DesktopManagedComponentReceipt],
        runID: String
    ) throws -> URL {
        guard UUID(uuidString: runID) != nil,
              releaseVersion.range(
                of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$",
                options: .regularExpression
              ) != nil,
              Set(receipts.map(\.kind)).count == receipts.count,
              !receipts.isEmpty,
              receipts.allSatisfy({ $0.schemaVersion == 1 })
        else { throw DesktopManagedComponentStoreError.invalidReceipt }
        let sorted = receipts.sorted { $0.kind.rawValue < $1.kind.rawValue }
        for receipt in sorted {
            let requirement = DesktopManagedComponentRequirement(
                kind: receipt.kind,
                version: receipt.version,
                architecture: receipt.architecture,
                downloadBytes: 1,
                installPhase: .bootstrap,
                reusePolicy: .exactContent(sha256: receipt.contentSHA256)
            )
            _ = try requireExisting(requirement) { _ in true }
        }
        let reference = DesktopManagedComponentReferenceSet(
            releaseVersion: releaseVersion,
            components: sorted.map {
                DesktopManagedComponentReference(
                    kind: $0.kind, contentSHA256: $0.contentSHA256
                )
            }
        )
        let referencesRoot = root.appendingPathComponent("references", isDirectory: true)
        try preparePrivateDirectory(referencesRoot)
        let destination = referencesRoot.appendingPathComponent("\(releaseVersion).json")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(reference)
        if fileManager.fileExists(atPath: destination.path) {
            try requireOwnedSafeFile(destination)
            guard let existing = try? Data(contentsOf: destination), existing == data else {
                throw DesktopManagedComponentStoreError.referenceConflict
            }
            return destination
        }
        let temporary = referencesRoot.appendingPathComponent(".\(runID.lowercased()).tmp")
        guard !fileManager.fileExists(atPath: temporary.path) else {
            throw DesktopManagedComponentStoreError.workspaceAlreadyExists
        }
        do {
            try data.write(to: temporary, options: [.withoutOverwriting])
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: temporary.path)
            try fileManager.moveItem(at: temporary, to: destination)
            return destination
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw error
        }
    }

    private func requireExisting(
        _ requirement: DesktopManagedComponentRequirement,
        healthProbe: HealthProbe
    ) throws -> URL {
        let inspector = try DesktopManagedComponentStoreInspector(
            root: root, currentUserID: currentUserID, fileManager: fileManager
        )
        guard let candidate = try inspector.candidate(
            for: requirement, healthProbe: healthProbe
        ), candidate.healthProbePassed else {
            throw DesktopManagedComponentStoreError.componentConflict
        }
        let identity: String
        switch requirement.reusePolicy {
        case .exactContent(let sha256), .verifiedCompatibility(let sha256, _):
            identity = sha256
        }
        return root.appendingPathComponent(
            "components/\(requirement.kind.rawValue)/\(identity)/content", isDirectory: true
        )
    }

    private func preparePrivateDirectory(_ value: URL) throws {
        if !fileManager.fileExists(atPath: value.path) {
            try fileManager.createDirectory(
                at: value, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
        }
        let resource = try value.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        let attributes = try fileManager.attributesOfItem(atPath: value.path)
        let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value
        let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0
        guard resource.isDirectory == true, resource.isSymbolicLink != true,
              owner == currentUserID, permissions & 0o077 == 0
        else { throw DesktopManagedComponentStoreError.unsafeTree }
    }

    private func requireOwnedSafeFile(_ value: URL) throws {
        let resource = try value.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey,
        ])
        let attributes = try fileManager.attributesOfItem(atPath: value.path)
        let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value
        let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue ?? 0
        guard resource.isRegularFile == true, resource.isSymbolicLink != true,
              owner == currentUserID, permissions & 0o077 == 0
        else { throw DesktopManagedComponentStoreError.unsafeTree }
    }

    private func cleanup(_ value: URL, beneath parent: URL) throws {
        let normalized = value.standardizedFileURL
        let prefix = parent.standardizedFileURL.path + "/"
        guard normalized.path.hasPrefix(prefix), normalized.path != parent.path else {
            throw DesktopManagedComponentStoreError.cleanupFailed
        }
        try fileManager.removeItem(at: normalized)
    }
}

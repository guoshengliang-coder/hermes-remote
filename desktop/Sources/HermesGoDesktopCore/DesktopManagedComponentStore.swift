import CryptoKit
import Foundation

public struct DesktopManagedComponentContentIdentity: Equatable, Sendable {
    public let sha256: String
    public let entryCount: Int
    public let fileBytes: Int64
}

public enum DesktopManagedComponentStoreError: Error, Equatable, Sendable {
    case invalidRoot
    case unsafeTree
    case treeTooLarge
    case invalidReceipt
    case identityMismatch
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
        guard case .exactContent(let expectedIdentity) = requirement.reusePolicy else { return nil }
        let component = root
            .appendingPathComponent("components", isDirectory: true)
            .appendingPathComponent(requirement.kind.rawValue, isDirectory: true)
            .appendingPathComponent(expectedIdentity, isDirectory: true)
        let receiptURL = root
            .appendingPathComponent("receipts", isDirectory: true)
            .appendingPathComponent("\(requirement.kind.rawValue)-\(expectedIdentity).json")
        guard fileManager.fileExists(atPath: component.path),
              fileManager.fileExists(atPath: receiptURL.path)
        else { return nil }
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

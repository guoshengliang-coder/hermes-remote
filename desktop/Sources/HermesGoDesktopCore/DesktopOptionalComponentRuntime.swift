import CryptoKit
import Darwin
import Foundation

public struct DesktopOptionalComponentRuntimeEnvironment: Equatable, Sendable {
    public let lazyInstallTarget: URL?
    public let browserExecutable: URL?

    public init(lazyInstallTarget: URL? = nil, browserExecutable: URL? = nil) {
        self.lazyInstallTarget = lazyInstallTarget
        self.browserExecutable = browserExecutable
    }
}

public enum DesktopOptionalComponentRuntimeError: Error, Equatable, Sendable {
    case invalidInput
    case duplicateComponent
    case invalidComponent
    case unsafeFilesystemObject
    case persistenceFailed
}

/// Projects immutable, signed optional components onto the runtime paths already supported by
/// upstream Hermes. Python packages are exposed through a read-only `HERMES_LAZY_INSTALL_TARGET`
/// containing only Desktop-generated `.pth` entries; browser automation uses the existing
/// `AGENT_BROWSER_EXECUTABLE_PATH`. This type does not touch LaunchAgents or processes.
public final class DesktopOptionalComponentRuntimeWriter: @unchecked Sendable {
    public static let pathFileName = "_hermes_go_optional_components.pth"
    public static let abiFileName = ".python-abi"

    private let storeRoot: URL
    private let projectionRoot: URL
    private let currentUserID: UInt32
    private let fileManager: FileManager
    private let runner: any OutputCommandRunning

    public init(
        storeRoot: URL,
        projectionRoot: URL,
        currentUserID: UInt32,
        runner: any OutputCommandRunning = SystemOutputCommandRunner(),
        fileManager: FileManager = .default
    ) throws {
        guard let store = Self.absolutePath(storeRoot),
              let projection = Self.absolutePath(projectionRoot),
              store.path != projection.path
        else { throw DesktopOptionalComponentRuntimeError.invalidInput }
        self.storeRoot = store
        self.projectionRoot = projection
        self.currentUserID = currentUserID
        self.runner = runner
        self.fileManager = fileManager
    }

    public func write(
        releaseVersion: String,
        pythonRuntimeRoot: URL? = nil,
        components: [DesktopResolvedOnDemandComponent]
    ) throws -> DesktopOptionalComponentRuntimeEnvironment {
        guard DesktopManagedInstallLayout.validVersion(releaseVersion),
              !components.isEmpty
        else { throw DesktopOptionalComponentRuntimeError.invalidInput }
        guard Set(components.map(\.kind)).count == components.count else {
            throw DesktopOptionalComponentRuntimeError.duplicateComponent
        }

        var pythonRoots: [(DesktopManagedComponentKind, URL)] = []
        var browserExecutable: URL?
        for component in components {
            switch (component.kind, component.location) {
            case (.speechRuntime, .managed(let root, let entrypoint)),
                 (.documentTools, .managed(let root, let entrypoint)):
                let managed = try validateManaged(
                    kind: component.kind, root: root, entrypoint: entrypoint
                )
                let sitePackages = managed.root.appendingPathComponent(
                    "site-packages", isDirectory: true
                )
                try validateOwnedDirectory(sitePackages)
                pythonRoots.append((component.kind, sitePackages))

            case (.browserAutomation, .managed(let root, let entrypoint)):
                browserExecutable = try validateManaged(
                    kind: component.kind, root: root, entrypoint: entrypoint
                ).entrypoint

            case (.browserAutomation, .external(let executable)):
                browserExecutable = try validateExternalBrowser(executable)

            default:
                throw DesktopOptionalComponentRuntimeError.invalidComponent
            }
        }

        guard !pythonRoots.isEmpty else {
            return DesktopOptionalComponentRuntimeEnvironment(
                browserExecutable: browserExecutable
            )
        }
        guard let pythonRuntimeRoot else {
            throw DesktopOptionalComponentRuntimeError.invalidInput
        }
        let python = try validatePythonRuntime(pythonRuntimeRoot)
        let pythonABITag = try probePythonABI(python)
        pythonRoots.sort { $0.0.rawValue < $1.0.rawValue }
        let pathText = pythonRoots.map(\.1.path).joined(separator: "\n") + "\n"
        let identityInput = ([
            "schema=1", "release=\(releaseVersion)", "python=\(python.path)",
            "abi=\(pythonABITag)",
        ]
            + pythonRoots.map { "\($0.0.rawValue)=\($0.1.path)" }).joined(separator: "\n")
        let identity = SHA256.hash(data: Data(identityInput.utf8)).map {
            String(format: "%02x", $0)
        }.joined()

        try preparePrivateDirectory(projectionRoot)
        let releaseRoot = projectionRoot.appendingPathComponent(releaseVersion, isDirectory: true)
        try preparePrivateDirectory(releaseRoot)
        let destination = releaseRoot.appendingPathComponent(identity, isDirectory: true)
        if fileManager.fileExists(atPath: destination.path) {
            try validateProjection(destination, abi: pythonABITag, paths: pathText)
            return DesktopOptionalComponentRuntimeEnvironment(
                lazyInstallTarget: destination,
                browserExecutable: browserExecutable
            )
        }

        let staging = releaseRoot.appendingPathComponent(".\(UUID().uuidString.lowercased())", isDirectory: true)
        do {
            try fileManager.createDirectory(
                at: staging, withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
            try Data((pythonABITag + "\n").utf8).write(
                to: staging.appendingPathComponent(Self.abiFileName)
            )
            try Data(pathText.utf8).write(
                to: staging.appendingPathComponent(Self.pathFileName)
            )
            for file in [Self.abiFileName, Self.pathFileName] {
                try fileManager.setAttributes(
                    [.posixPermissions: 0o400],
                    ofItemAtPath: staging.appendingPathComponent(file).path
                )
            }
            try fileManager.setAttributes([.posixPermissions: 0o500], ofItemAtPath: staging.path)
            if Darwin.rename(staging.path, destination.path) != 0 {
                let renameError = errno
                guard fileManager.fileExists(atPath: destination.path) else {
                    #if DEBUG
                    fputs("DesktopOptionalComponentRuntime rename errno=\(renameError)\n", stderr)
                    #endif
                    throw DesktopOptionalComponentRuntimeError.persistenceFailed
                }
                try? fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: staging.path)
                try? fileManager.removeItem(at: staging)
            }
            try validateProjection(destination, abi: pythonABITag, paths: pathText)
        } catch let error as DesktopOptionalComponentRuntimeError {
            try? fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: staging.path)
            try? fileManager.removeItem(at: staging)
            throw error
        } catch {
            try? fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: staging.path)
            try? fileManager.removeItem(at: staging)
            throw DesktopOptionalComponentRuntimeError.persistenceFailed
        }
        return DesktopOptionalComponentRuntimeEnvironment(
            lazyInstallTarget: destination,
            browserExecutable: browserExecutable
        )
    }

    private func validateManaged(
        kind: DesktopManagedComponentKind,
        root: URL,
        entrypoint: URL
    ) throws -> (root: URL, entrypoint: URL) {
        guard let normalizedRoot = Self.absolutePath(root),
              let normalizedEntrypoint = Self.absolutePath(entrypoint),
              normalizedEntrypoint.path.hasPrefix(normalizedRoot.path + "/"),
              normalizedRoot.path.hasPrefix(
                storeRoot.appendingPathComponent("components/\(kind.rawValue)").path + "/"
              ),
              normalizedRoot.lastPathComponent == "content",
              normalizedRoot.deletingLastPathComponent().lastPathComponent.range(
                of: "^[0-9a-f]{64}$", options: .regularExpression
              ) != nil
        else { throw DesktopOptionalComponentRuntimeError.invalidComponent }
        try validateOwnedDirectory(normalizedRoot)
        try validateExecutable(normalizedEntrypoint, allowedOwners: [currentUserID])
        return (normalizedRoot, normalizedEntrypoint)
    }

    private func validatePythonRuntime(_ value: URL) throws -> URL {
        guard let root = Self.absolutePath(value),
              root.path.hasPrefix(
                storeRoot.appendingPathComponent("components/python_runtime").path + "/"
              ),
              root.lastPathComponent == "content",
              root.deletingLastPathComponent().lastPathComponent.range(
                of: "^[0-9a-f]{64}$", options: .regularExpression
              ) != nil
        else { throw DesktopOptionalComponentRuntimeError.invalidComponent }
        try validateOwnedDirectory(root)
        try validateExecutable(
            root.appendingPathComponent("bin/python3"), allowedOwners: [currentUserID]
        )
        return root
    }

    private func probePythonABI(_ root: URL) throws -> String {
        let script = "import sys, sysconfig; print(f'{sys.version_info.major}.{sys.version_info.minor}:{sysconfig.get_config_var(\"EXT_SUFFIX\") or \"\"}')"
        let result = runner.run(
            executable: root.appendingPathComponent("bin/python3"),
            arguments: ["-s", "-c", script],
            maximumOutputBytes: 512
        )
        guard result.status == 0, !result.outputLimitExceeded,
              let output = String(data: result.stdout, encoding: .utf8),
              output.last == "\n",
              !output.dropLast().contains("\n")
        else { throw DesktopOptionalComponentRuntimeError.invalidComponent }
        let tag = String(output.dropLast())
        guard Self.validLine(tag),
              tag.range(of: "^[0-9]+\\.[0-9]+:.{0,480}$", options: .regularExpression) != nil
        else { throw DesktopOptionalComponentRuntimeError.invalidComponent }
        return tag
    }

    private func validateExternalBrowser(_ value: URL) throws -> URL {
        guard let executable = Self.absolutePath(value) else {
            throw DesktopOptionalComponentRuntimeError.invalidComponent
        }
        try validateExecutable(executable, allowedOwners: [0, currentUserID])
        return executable
    }

    private func validateOwnedDirectory(_ value: URL) throws {
        var metadata = stat()
        guard Darwin.lstat(value.path, &metadata) == 0,
              (metadata.st_mode & S_IFMT) == S_IFDIR,
              metadata.st_uid == currentUserID,
              metadata.st_mode & 0o022 == 0
        else { throw DesktopOptionalComponentRuntimeError.unsafeFilesystemObject }
    }

    private func validateExecutable(_ value: URL, allowedOwners: Set<UInt32>) throws {
        var metadata = stat()
        guard Darwin.lstat(value.path, &metadata) == 0,
              (metadata.st_mode & S_IFMT) == S_IFREG,
              allowedOwners.contains(metadata.st_uid),
              metadata.st_mode & 0o022 == 0,
              metadata.st_mode & 0o111 != 0
        else { throw DesktopOptionalComponentRuntimeError.unsafeFilesystemObject }
    }

    private func preparePrivateDirectory(_ value: URL) throws {
        if fileManager.fileExists(atPath: value.path) {
            try validateOwnedDirectory(value)
            return
        }
        do {
            try fileManager.createDirectory(
                at: value, withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
        } catch {
            throw DesktopOptionalComponentRuntimeError.persistenceFailed
        }
        try validateOwnedDirectory(value)
    }

    private func validateProjection(_ root: URL, abi: String, paths: String) throws {
        var directory = stat()
        guard Darwin.lstat(root.path, &directory) == 0,
              (directory.st_mode & S_IFMT) == S_IFDIR,
              directory.st_uid == currentUserID,
              directory.st_mode & 0o277 == 0
        else { throw DesktopOptionalComponentRuntimeError.unsafeFilesystemObject }
        let expected = [Self.abiFileName: abi + "\n", Self.pathFileName: paths]
        let names = try Set(fileManager.contentsOfDirectory(atPath: root.path))
        guard names == Set(expected.keys) else {
            throw DesktopOptionalComponentRuntimeError.unsafeFilesystemObject
        }
        for (name, text) in expected {
            let file = root.appendingPathComponent(name)
            var metadata = stat()
            guard Darwin.lstat(file.path, &metadata) == 0,
                  (metadata.st_mode & S_IFMT) == S_IFREG,
                  metadata.st_uid == currentUserID,
                  metadata.st_mode & 0o277 == 0,
                  (try? String(contentsOf: file, encoding: .utf8)) == text
            else { throw DesktopOptionalComponentRuntimeError.unsafeFilesystemObject }
        }
    }

    private static func absolutePath(_ value: URL) -> URL? {
        let normalized = value.standardizedFileURL
        guard normalized.isFileURL,
              normalized.path.hasPrefix("/"), normalized.path != "/",
              validLine(normalized.path),
              normalized.resolvingSymlinksInPath().path == normalized.path
        else { return nil }
        return normalized
    }

    private static func validLine(_ value: String) -> Bool {
        (1...512).contains(value.utf8.count)
            && !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
    }
}

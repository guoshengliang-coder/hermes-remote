import Darwin
import Foundation
import Security

public struct DesktopManagedInstallLayout: Equatable, Sendable {
    public static let connectorLabel = "com.hermesgo.connector"
    public static let hermesLabel = "com.hermesgo.hermes-server"

    public let root: URL
    public let launchAgentsRoot: URL

    public init(root: URL, launchAgentsRoot: URL) throws {
        self.root = try Self.canonicalRoot(root)
        self.launchAgentsRoot = try Self.canonicalRoot(launchAgentsRoot)
    }

    public var releasesRoot: URL { root.appendingPathComponent("releases", isDirectory: true) }
    public var stagingRoot: URL { root.appendingPathComponent("staging", isDirectory: true) }
    public var secretsRoot: URL { root.appendingPathComponent("secrets", isDirectory: true) }
    public var logsRoot: URL { root.appendingPathComponent("logs", isDirectory: true) }
    public var currentRelease: URL { root.appendingPathComponent("current") }
    public var connectorCredential: URL { secretsRoot.appendingPathComponent("connector-account.json") }
    public var hermesSessionToken: URL { secretsRoot.appendingPathComponent("hermes-session-token") }
    public var hermesSessionTokenContractMarker: URL {
        secretsRoot.appendingPathComponent("hermes-session-token-v1.complete")
    }
    public var connectorLaunchAgent: URL {
        launchAgentsRoot.appendingPathComponent(Self.connectorLabel + ".plist")
    }
    public var hermesLaunchAgent: URL {
        launchAgentsRoot.appendingPathComponent(Self.hermesLabel + ".plist")
    }

    public func release(_ version: String) throws -> URL {
        guard Self.validVersion(version) else { throw DesktopManagedInstallError.invalidInput }
        return releasesRoot.appendingPathComponent(version, isDirectory: true)
    }

    private static func canonicalRoot(_ value: URL) throws -> URL {
        let canonical = value.standardizedFileURL.resolvingSymlinksInPath()
        guard canonical.isFileURL, canonical.path.hasPrefix("/"), canonical.path != "/" else {
            throw DesktopManagedInstallError.invalidInput
        }
        return canonical
    }

    fileprivate static func validVersion(_ value: String) -> Bool {
        value.range(
            of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$",
            options: .regularExpression
        ) != nil
    }
}

public struct DesktopManagedReleaseSource: Equatable, Sendable {
    public let component: DesktopReleaseComponentKind
    public let directory: URL

    init(component: DesktopReleaseComponentKind, directory: URL) {
        self.component = component
        self.directory = directory
    }
}

public struct DesktopReleaseActivation: Equatable, Sendable {
    public let releaseVersion: String
    public let previousRelativeTarget: String?

    public init(releaseVersion: String, previousRelativeTarget: String?) {
        self.releaseVersion = releaseVersion
        self.previousRelativeTarget = previousRelativeTarget
    }
}

public enum DesktopManagedInstallError: Error, Equatable, Sendable {
    case invalidInput
    case unsafeFilesystemObject
    case releaseAlreadyExists
    case missingEntrypoint
    case activationFailed
    case persistenceFailed
}

public struct DesktopHermesSessionTokenMigration: Sendable {
    public let hermesLaunchAgentURL: URL
    public let connectorLaunchAgentURL: URL
    public let hermesLogURL: URL

    fileprivate let originalHermesLaunchAgent: Data
    fileprivate let originalConnectorLaunchAgent: Data
    fileprivate let originalSessionToken: Data?
}

private enum ManagedSessionTokenStorage: Equatable {
    case file
    case inline(String)
}

private struct ManagedLaunchAgentPropertyList {
    let data: Data
    let object: [String: Any]
    let logURL: URL
}

public final class DesktopManagedInstaller: @unchecked Sendable {
    private static let releaseMarkerName = ".hermes-go-managed-release.json"
    private let layout: DesktopManagedInstallLayout
    private let fileManager: FileManager

    public init(layout: DesktopManagedInstallLayout, fileManager: FileManager = .default) {
        self.layout = layout
        self.fileManager = fileManager
    }

    /// Installs already checksum-verified, safely extracted component trees. It never reads or
    /// modifies a legacy Connector directory.
    public func stageRelease(
        manifest: DesktopReleaseManifest,
        runID: String,
        sources: [DesktopManagedReleaseSource]
    ) throws -> URL {
        guard DesktopManagedInstallLayout.validVersion(manifest.releaseVersion),
              let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased(),
              sources.count == DesktopReleaseComponentKind.allCases.count,
              Set(sources.map(\.component)) == Set(DesktopReleaseComponentKind.allCases),
              Set(manifest.artifacts.map(\.component)) == Set(DesktopReleaseComponentKind.allCases)
        else { throw DesktopManagedInstallError.invalidInput }

        try ensurePrivateDirectory(layout.root)
        try ensurePrivateDirectory(layout.releasesRoot)
        try ensurePrivateDirectory(layout.stagingRoot)
        let incoming = layout.stagingRoot.appendingPathComponent(normalizedRunID, isDirectory: true)
        guard !fileManager.fileExists(atPath: incoming.path) else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        let destination = try layout.release(manifest.releaseVersion)
        let destinationAlreadyExists = fileManager.fileExists(atPath: destination.path)
        if destinationAlreadyExists {
            try validateReplaceableRelease(destination, releaseVersion: manifest.releaseVersion)
        }

        do {
            try fileManager.createDirectory(
                at: incoming,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
            for source in sources {
                try validateExtractedTree(source.directory)
                let componentRoot = incoming.appendingPathComponent(source.component.rawValue, isDirectory: true)
                try fileManager.copyItem(at: source.directory, to: componentRoot)
                guard let artifact = manifest.artifacts.first(where: { $0.component == source.component }) else {
                    throw DesktopManagedInstallError.invalidInput
                }
                try validateEntrypoint(componentRoot.appendingPathComponent(artifact.entrypoint))
            }
            try writeReleaseMarker(
                runID: normalizedRunID,
                releaseVersion: manifest.releaseVersion,
                at: incoming.appendingPathComponent(Self.releaseMarkerName)
            )
            if destinationAlreadyExists {
                _ = try fileManager.replaceItemAt(destination, withItemAt: incoming)
            } else {
                try fileManager.moveItem(at: incoming, to: destination)
            }
            return destination
        } catch let error as DesktopManagedInstallError {
            try? fileManager.removeItem(at: incoming)
            throw error
        } catch {
            try? fileManager.removeItem(at: incoming)
            throw DesktopManagedInstallError.persistenceFailed
        }
    }

    public func writeCredential(_ credential: AccountConnectorCredentialPayload) throws -> URL {
        guard credential.data.count <= 4 * 1024 else { throw DesktopManagedInstallError.invalidInput }
        try ensurePrivateDirectory(layout.root)
        try ensurePrivateDirectory(layout.secretsRoot)
        try atomicWrite(credential.data, to: layout.connectorCredential, permissions: 0o600)
        return layout.connectorCredential
    }

    /// Returns a stable, installation-local credential shared only by the managed Hermes process
    /// and its Connector. The value never enters either LaunchAgent plist or the Cloud credential.
    public func ensureHermesSessionToken() throws -> URL {
        try ensurePrivateDirectory(layout.root)
        try ensurePrivateDirectory(layout.secretsRoot)
        var metadata = stat()
        if Darwin.lstat(layout.hermesSessionToken.path, &metadata) == 0 {
            guard (metadata.st_mode & S_IFMT) == S_IFREG,
                  metadata.st_uid == Darwin.getuid(),
                  metadata.st_mode & 0o077 == 0,
                  let value = try? String(contentsOf: layout.hermesSessionToken, encoding: .utf8),
                  Self.validSessionToken(value)
            else { throw DesktopManagedInstallError.unsafeFilesystemObject }
            return layout.hermesSessionToken
        }
        guard errno == ENOENT else { throw DesktopManagedInstallError.unsafeFilesystemObject }

        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw DesktopManagedInstallError.persistenceFailed
        }
        let token = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        guard token.utf8.count == 43 else { throw DesktopManagedInstallError.persistenceFailed }
        try atomicWrite(Data(token.utf8), to: layout.hermesSessionToken, permissions: 0o600)
        return layout.hermesSessionToken
    }

    /// Converts pre-contract managed LaunchAgents that carry the local Hermes token inline to the
    /// private file contract. Preparation is crash-resumable: services keep their current process
    /// environment until the coordinator restarts them, and a missing completion marker requires a
    /// later startup to repeat the health proof before the migration is considered complete.
    public func prepareHermesSessionTokenFileMigration() throws -> DesktopHermesSessionTokenMigration? {
        try ensurePrivateDirectory(layout.root)
        try ensurePrivateDirectory(layout.secretsRoot)
        let markerPresent = try validatedCompletionMarkerIfPresent()
        let hermes = try loadManagedLaunchAgent(
            layout.hermesLaunchAgent,
            label: DesktopManagedInstallLayout.hermesLabel,
            component: .hermesServer,
            trailingArguments: ["serve", "--host", "127.0.0.1", "--port", "9119"],
            expectedLog: layout.logsRoot.appendingPathComponent("hermes-server.log"),
            expectedErrorLog: layout.logsRoot.appendingPathComponent("hermes-server.error.log")
        )
        let connector = try loadManagedLaunchAgent(
            layout.connectorLaunchAgent,
            label: DesktopManagedInstallLayout.connectorLabel,
            component: .connector,
            trailingArguments: [],
            expectedLog: layout.logsRoot.appendingPathComponent("connector.log"),
            expectedErrorLog: layout.logsRoot.appendingPathComponent("connector.error.log")
        )
        let hermesStorage = try sessionTokenStorage(in: hermes.object)
        let connectorStorage = try sessionTokenStorage(in: connector.object)

        if markerPresent {
            guard hermesStorage == .file, connectorStorage == .file else {
                throw DesktopManagedInstallError.unsafeFilesystemObject
            }
            _ = try validatedSessionTokenIfPresent(required: true)
            return nil
        }

        let originalToken = try validatedSessionTokenIfPresent(required: false)
        let inlineTokens = [hermesStorage, connectorStorage].compactMap { storage -> String? in
            guard case .inline(let value) = storage else { return nil }
            return value
        }
        guard Set(inlineTokens).count <= 1 else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        if hermesStorage == .file || connectorStorage == .file {
            guard originalToken != nil else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        }
        if let inlineToken = inlineTokens.first, let originalToken {
            guard originalToken == Data(inlineToken.utf8) else {
                throw DesktopManagedInstallError.unsafeFilesystemObject
            }
        } else if let inlineToken = inlineTokens.first {
            try atomicWrite(
                Data(inlineToken.utf8),
                to: layout.hermesSessionToken,
                permissions: 0o600
            )
        } else {
            guard originalToken != nil else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        }

        let migratedHermes = try launchAgentReplacingInlineToken(in: hermes.object)
        let migratedConnector = try launchAgentReplacingInlineToken(in: connector.object)
        do {
            try atomicWrite(migratedHermes, to: layout.hermesLaunchAgent, permissions: 0o600)
            try atomicWrite(migratedConnector, to: layout.connectorLaunchAgent, permissions: 0o600)
        } catch {
            try? atomicWrite(hermes.data, to: layout.hermesLaunchAgent, permissions: 0o600)
            try? atomicWrite(connector.data, to: layout.connectorLaunchAgent, permissions: 0o600)
            if originalToken == nil { try? removeOwnedRegularFileIfPresent(layout.hermesSessionToken) }
            throw error
        }
        return DesktopHermesSessionTokenMigration(
            hermesLaunchAgentURL: layout.hermesLaunchAgent,
            connectorLaunchAgentURL: layout.connectorLaunchAgent,
            hermesLogURL: hermes.logURL,
            originalHermesLaunchAgent: hermes.data,
            originalConnectorLaunchAgent: connector.data,
            originalSessionToken: originalToken
        )
    }

    public func commitHermesSessionTokenFileMigration() throws {
        let hermes = try loadManagedLaunchAgent(
            layout.hermesLaunchAgent,
            label: DesktopManagedInstallLayout.hermesLabel,
            component: .hermesServer,
            trailingArguments: ["serve", "--host", "127.0.0.1", "--port", "9119"],
            expectedLog: layout.logsRoot.appendingPathComponent("hermes-server.log"),
            expectedErrorLog: layout.logsRoot.appendingPathComponent("hermes-server.error.log")
        )
        let connector = try loadManagedLaunchAgent(
            layout.connectorLaunchAgent,
            label: DesktopManagedInstallLayout.connectorLabel,
            component: .connector,
            trailingArguments: [],
            expectedLog: layout.logsRoot.appendingPathComponent("connector.log"),
            expectedErrorLog: layout.logsRoot.appendingPathComponent("connector.error.log")
        )
        guard try sessionTokenStorage(in: hermes.object) == .file,
              try sessionTokenStorage(in: connector.object) == .file
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        _ = try validatedSessionTokenIfPresent(required: true)
        try atomicWrite(
            Data("1\n".utf8),
            to: layout.hermesSessionTokenContractMarker,
            permissions: 0o600
        )
    }

    public func rollbackHermesSessionTokenFileMigration(
        _ migration: DesktopHermesSessionTokenMigration
    ) throws {
        guard migration.hermesLaunchAgentURL.standardizedFileURL.path
                == layout.hermesLaunchAgent.standardizedFileURL.path,
              migration.connectorLaunchAgentURL.standardizedFileURL.path
                == layout.connectorLaunchAgent.standardizedFileURL.path
        else { throw DesktopManagedInstallError.invalidInput }
        try removeOwnedRegularFileIfPresent(layout.hermesSessionTokenContractMarker)
        try atomicWrite(
            migration.originalHermesLaunchAgent,
            to: layout.hermesLaunchAgent,
            permissions: 0o600
        )
        try atomicWrite(
            migration.originalConnectorLaunchAgent,
            to: layout.connectorLaunchAgent,
            permissions: 0o600
        )
        if let original = migration.originalSessionToken {
            try atomicWrite(original, to: layout.hermesSessionToken, permissions: 0o600)
        } else {
            try removeOwnedRegularFileIfPresent(layout.hermesSessionToken)
        }
    }

    public func writeLaunchAgent(
        _ configuration: DesktopAccountConnectorLaunchAgent,
        manifest: DesktopReleaseManifest
    ) throws -> URL {
        guard let connector = manifest.artifacts.first(where: { $0.component == .connector }) else {
            throw DesktopManagedInstallError.invalidInput
        }
        let expectedExecutable = layout.currentRelease
            .appendingPathComponent(DesktopReleaseComponentKind.connector.rawValue)
            .appendingPathComponent(connector.entrypoint)
            .standardizedFileURL
        guard configuration.connectorExecutable.standardizedFileURL.path == expectedExecutable.path,
              configuration.credentialFile.standardizedFileURL.path == layout.connectorCredential.path,
              configuration.sessionTokenFile.standardizedFileURL.path == layout.hermesSessionToken.path,
              configuration.standardOutput.standardizedFileURL.path
                == layout.logsRoot.appendingPathComponent("connector.log").path,
              configuration.standardError.standardizedFileURL.path
                == layout.logsRoot.appendingPathComponent("connector.error.log").path
        else { throw DesktopManagedInstallError.invalidInput }
        let data: Data
        do { data = try configuration.encodedPropertyList() }
        catch { throw DesktopManagedInstallError.invalidInput }
        guard data.count <= 64 * 1024 else { throw DesktopManagedInstallError.invalidInput }
        try ensureOwnedDirectory(layout.launchAgentsRoot)
        try ensurePrivateDirectory(layout.logsRoot)
        try atomicWrite(data, to: layout.connectorLaunchAgent, permissions: 0o600)
        return layout.connectorLaunchAgent
    }

    public func writeHermesLaunchAgent(
        _ configuration: DesktopHermesServerLaunchAgent,
        manifest: DesktopReleaseManifest
    ) throws -> URL {
        guard let hermes = manifest.artifacts.first(where: { $0.component == .hermesServer }) else {
            throw DesktopManagedInstallError.invalidInput
        }
        let expectedExecutable = layout.currentRelease
            .appendingPathComponent(DesktopReleaseComponentKind.hermesServer.rawValue)
            .appendingPathComponent(hermes.entrypoint)
            .standardizedFileURL
        guard configuration.hermesExecutable.standardizedFileURL.path == expectedExecutable.path,
              configuration.sessionTokenFile.standardizedFileURL.path == layout.hermesSessionToken.path,
              configuration.standardOutput.standardizedFileURL.path
                == layout.logsRoot.appendingPathComponent("hermes-server.log").path,
              configuration.standardError.standardizedFileURL.path
                == layout.logsRoot.appendingPathComponent("hermes-server.error.log").path
        else { throw DesktopManagedInstallError.invalidInput }
        let data: Data
        do { data = try configuration.encodedPropertyList() }
        catch { throw DesktopManagedInstallError.invalidInput }
        guard data.count <= 64 * 1024 else { throw DesktopManagedInstallError.invalidInput }
        try ensureOwnedDirectory(layout.launchAgentsRoot)
        try ensurePrivateDirectory(layout.logsRoot)
        try atomicWrite(data, to: layout.hermesLaunchAgent, permissions: 0o600)
        return layout.hermesLaunchAgent
    }

    public func activate(releaseVersion: String, runID: String) throws -> DesktopReleaseActivation {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopManagedInstallError.invalidInput
        }
        let release = try layout.release(releaseVersion)
        try requireOwnedDirectory(release)
        let previous = try existingCurrentTarget()
        let relativeTarget = "releases/\(releaseVersion)"
        let temporary = layout.root.appendingPathComponent(".current-\(normalizedRunID)")
        guard Darwin.symlink(relativeTarget, temporary.path) == 0 else {
            throw DesktopManagedInstallError.activationFailed
        }
        defer { try? fileManager.removeItem(at: temporary) }
        guard Darwin.rename(temporary.path, layout.currentRelease.path) == 0 else {
            throw DesktopManagedInstallError.activationFailed
        }
        return DesktopReleaseActivation(
            releaseVersion: releaseVersion,
            previousRelativeTarget: previous
        )
    }

    public func rollback(_ activation: DesktopReleaseActivation, runID: String) throws {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased(),
              try existingCurrentTarget() == "releases/\(activation.releaseVersion)"
        else { throw DesktopManagedInstallError.activationFailed }
        guard let target = activation.previousRelativeTarget else {
            do { try fileManager.removeItem(at: layout.currentRelease) }
            catch { throw DesktopManagedInstallError.activationFailed }
            return
        }
        guard validRelativeReleaseTarget(target) else {
            throw DesktopManagedInstallError.activationFailed
        }
        let previousRelease = layout.root.appendingPathComponent(target).standardizedFileURL
        try requireOwnedDirectory(previousRelease)
        let temporary = layout.root.appendingPathComponent(".rollback-current-\(normalizedRunID)")
        guard Darwin.symlink(target, temporary.path) == 0 else {
            throw DesktopManagedInstallError.activationFailed
        }
        defer { try? fileManager.removeItem(at: temporary) }
        guard Darwin.rename(temporary.path, layout.currentRelease.path) == 0 else {
            throw DesktopManagedInstallError.activationFailed
        }
    }

    public func deactivateExpectedRelease(_ releaseVersion: String) throws {
        guard DesktopManagedInstallLayout.validVersion(releaseVersion) else {
            throw DesktopManagedInstallError.invalidInput
        }
        guard let current = try existingCurrentTarget() else { return }
        guard current == "releases/\(releaseVersion)" else {
            throw DesktopManagedInstallError.activationFailed
        }
        do { try fileManager.removeItem(at: layout.currentRelease) }
        catch { throw DesktopManagedInstallError.activationFailed }
    }

    private func validatedCompletionMarkerIfPresent() throws -> Bool {
        var metadata = stat()
        if Darwin.lstat(layout.hermesSessionTokenContractMarker.path, &metadata) != 0 {
            guard errno == ENOENT else { throw DesktopManagedInstallError.unsafeFilesystemObject }
            return false
        }
        guard (metadata.st_mode & S_IFMT) == S_IFREG,
              metadata.st_uid == Darwin.getuid(),
              metadata.st_mode & 0o077 == 0,
              metadata.st_size == 2,
              (try? Data(contentsOf: layout.hermesSessionTokenContractMarker)) == Data("1\n".utf8)
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        return true
    }

    private func validatedSessionTokenIfPresent(required: Bool) throws -> Data? {
        var metadata = stat()
        if Darwin.lstat(layout.hermesSessionToken.path, &metadata) != 0 {
            guard errno == ENOENT, !required else {
                throw DesktopManagedInstallError.unsafeFilesystemObject
            }
            return nil
        }
        guard (metadata.st_mode & S_IFMT) == S_IFREG,
              metadata.st_uid == Darwin.getuid(),
              metadata.st_mode & 0o077 == 0,
              metadata.st_size <= 128,
              let data = try? Data(contentsOf: layout.hermesSessionToken),
              let value = String(data: data, encoding: .utf8),
              Self.validSessionToken(value)
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        return data
    }

    private func loadManagedLaunchAgent(
        _ url: URL,
        label: String,
        component: DesktopReleaseComponentKind,
        trailingArguments: [String],
        expectedLog: URL,
        expectedErrorLog: URL
    ) throws -> ManagedLaunchAgentPropertyList {
        var metadata = stat()
        guard Darwin.lstat(url.path, &metadata) == 0,
              (metadata.st_mode & S_IFMT) == S_IFREG,
              metadata.st_uid == Darwin.getuid(),
              metadata.st_mode & 0o077 == 0,
              metadata.st_size > 0,
              metadata.st_size <= 64 * 1024,
              let data = try? Data(contentsOf: url),
              let raw = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil),
              let object = raw as? [String: Any],
              object["Label"] as? String == label,
              let arguments = object["ProgramArguments"] as? [String],
              let executable = arguments.first,
              arguments == [executable] + trailingArguments,
              URL(fileURLWithPath: executable).standardizedFileURL.path == executable,
              executable.hasPrefix(
                layout.currentRelease
                    .appendingPathComponent(component.rawValue, isDirectory: true)
                    .standardizedFileURL.path + "/"
              ),
              !executable.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
              let logPath = object["StandardOutPath"] as? String,
              logPath == expectedLog.standardizedFileURL.path,
              object["StandardErrorPath"] as? String == expectedErrorLog.standardizedFileURL.path,
              object["EnvironmentVariables"] as? [String: Any] != nil
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        return ManagedLaunchAgentPropertyList(
            data: data,
            object: object,
            logURL: URL(fileURLWithPath: logPath)
        )
    }

    private func sessionTokenStorage(in object: [String: Any]) throws -> ManagedSessionTokenStorage {
        guard let rawEnvironment = object["EnvironmentVariables"] as? [String: Any],
              rawEnvironment.allSatisfy({ $0.value is String })
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        let environment = rawEnvironment.compactMapValues { $0 as? String }
        let inlineValues = [
            environment["HERMES_SESSION_TOKEN"],
            environment["HERMES_DASHBOARD_SESSION_TOKEN"],
        ].compactMap { $0 }
        guard inlineValues.count <= 1 else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        let fileValue = environment["HERMES_SESSION_TOKEN_FILE"]
        guard !(fileValue != nil && !inlineValues.isEmpty) else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        if let fileValue {
            guard fileValue == layout.hermesSessionToken.standardizedFileURL.path else {
                throw DesktopManagedInstallError.unsafeFilesystemObject
            }
            return .file
        }
        guard let inline = inlineValues.first,
              Self.validSessionToken(inline)
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        return .inline(inline)
    }

    private static func validSessionToken(_ value: String) -> Bool {
        (value.utf8.count == 43
            && value.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil)
            || (value.utf8.count == 64
                && value.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil)
    }

    private func launchAgentReplacingInlineToken(in original: [String: Any]) throws -> Data {
        guard var environment = original["EnvironmentVariables"] as? [String: Any] else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        environment.removeValue(forKey: "HERMES_SESSION_TOKEN")
        environment.removeValue(forKey: "HERMES_DASHBOARD_SESSION_TOKEN")
        environment["HERMES_SESSION_TOKEN_FILE"] = layout.hermesSessionToken.standardizedFileURL.path
        var migrated = original
        migrated["EnvironmentVariables"] = environment
        do {
            let data = try PropertyListSerialization.data(
                fromPropertyList: migrated,
                format: .xml,
                options: 0
            )
            guard data.count <= 64 * 1024 else { throw DesktopManagedInstallError.persistenceFailed }
            return data
        } catch let error as DesktopManagedInstallError {
            throw error
        } catch {
            throw DesktopManagedInstallError.persistenceFailed
        }
    }

    private func removeOwnedRegularFileIfPresent(_ url: URL) throws {
        var metadata = stat()
        if Darwin.lstat(url.path, &metadata) != 0 {
            guard errno == ENOENT else { throw DesktopManagedInstallError.unsafeFilesystemObject }
            return
        }
        guard (metadata.st_mode & S_IFMT) == S_IFREG,
              metadata.st_uid == Darwin.getuid()
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        do { try fileManager.removeItem(at: url) }
        catch { throw DesktopManagedInstallError.persistenceFailed }
    }

    private func existingCurrentTarget() throws -> String? {
        guard fileManager.fileExists(atPath: layout.currentRelease.path)
                || (try? layout.currentRelease.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) == true
        else { return nil }
        let values = try layout.currentRelease.resourceValues(forKeys: [.isSymbolicLinkKey])
        guard values.isSymbolicLink == true else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        let target = try fileManager.destinationOfSymbolicLink(atPath: layout.currentRelease.path)
        guard validRelativeReleaseTarget(target) else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        return target
    }

    private func validRelativeReleaseTarget(_ value: String) -> Bool {
        guard value.hasPrefix("releases/"),
              !value.hasPrefix("/"),
              !value.contains("..")
        else { return false }
        return DesktopManagedInstallLayout.validVersion(String(value.dropFirst("releases/".count)))
    }

    private func validateReplaceableRelease(
        _ release: URL,
        releaseVersion: String
    ) throws {
        do {
            try requireOwnedDirectory(release)
            guard try existingCurrentTarget() != "releases/\(releaseVersion)" else {
                throw DesktopManagedInstallError.releaseAlreadyExists
            }
            let markerURL = release.appendingPathComponent(Self.releaseMarkerName)
            let values = try markerURL.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey,
            ])
            guard values.isRegularFile == true,
                  values.isSymbolicLink != true,
                  (values.fileSize ?? 0) <= 4 * 1024,
                  ownedByCurrentUser(markerURL),
                  let attributes = try? fileManager.attributesOfItem(atPath: markerURL.path),
                  let permissions = attributes[.posixPermissions] as? NSNumber,
                  permissions.intValue & 0o077 == 0,
                  let data = try? Data(contentsOf: markerURL),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  Set(object.keys) == Set(["schemaVersion", "runID", "releaseVersion"]),
                  let marker = try? JSONDecoder().decode(ManagedReleaseMarker.self, from: data),
                  marker.schemaVersion == 1,
                  UUID(uuidString: marker.runID)?.uuidString.lowercased() == marker.runID,
                  marker.releaseVersion == releaseVersion
            else { throw DesktopManagedInstallError.releaseAlreadyExists }
        } catch let error as DesktopManagedInstallError {
            throw error
        } catch {
            throw DesktopManagedInstallError.releaseAlreadyExists
        }
    }

    private func writeReleaseMarker(
        runID: String,
        releaseVersion: String,
        at url: URL
    ) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        do {
            let data = try encoder.encode(ManagedReleaseMarker(
                schemaVersion: 1,
                runID: runID,
                releaseVersion: releaseVersion
            ))
            guard data.count <= 4 * 1024 else { throw DesktopManagedInstallError.persistenceFailed }
            try data.write(to: url, options: [.atomic])
            try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch let error as DesktopManagedInstallError {
            throw error
        } catch {
            throw DesktopManagedInstallError.persistenceFailed
        }
    }

    private func validateExtractedTree(_ source: URL) throws {
        try requireOwnedDirectory(source)
        guard let enumerator = fileManager.enumerator(
            at: source,
            includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey],
            options: [],
            errorHandler: { _, _ in false }
        ) else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        for case let item as URL in enumerator {
            let values = try item.resourceValues(forKeys: [
                .isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey,
            ])
            guard values.isSymbolicLink != true,
                  values.isDirectory == true || values.isRegularFile == true,
                  ownedByCurrentUser(item)
            else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        }
    }

    private func validateEntrypoint(_ url: URL) throws {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true,
              values.isSymbolicLink != true,
              ownedByCurrentUser(url),
              let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let permissions = attributes[.posixPermissions] as? NSNumber,
              permissions.intValue & 0o111 != 0
        else { throw DesktopManagedInstallError.missingEntrypoint }
    }

    private func ensurePrivateDirectory(_ url: URL) throws {
        if !fileManager.fileExists(atPath: url.path) {
            do {
                try fileManager.createDirectory(
                    at: url,
                    withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopManagedInstallError.persistenceFailed }
        }
        try requireOwnedDirectory(url)
        do { try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.path) }
        catch { throw DesktopManagedInstallError.persistenceFailed }
    }

    private func ensureOwnedDirectory(_ url: URL) throws {
        if !fileManager.fileExists(atPath: url.path) {
            do {
                try fileManager.createDirectory(
                    at: url,
                    withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopManagedInstallError.persistenceFailed }
        }
        try requireOwnedDirectory(url)
    }

    private func requireOwnedDirectory(_ url: URL) throws {
        let values: URLResourceValues
        do { values = try url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]) }
        catch { throw DesktopManagedInstallError.unsafeFilesystemObject }
        guard values.isDirectory == true,
              values.isSymbolicLink != true,
              ownedByCurrentUser(url)
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
    }

    private func ownedByCurrentUser(_ url: URL) -> Bool {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path),
              let owner = attributes[.ownerAccountID] as? NSNumber
        else { return false }
        return owner.uint32Value == Darwin.getuid()
    }

    private func atomicWrite(_ data: Data, to destination: URL, permissions: Int) throws {
        let temporary = destination.deletingLastPathComponent()
            .appendingPathComponent(".\(destination.lastPathComponent)-\(UUID().uuidString.lowercased()).tmp")
        do {
            try data.write(to: temporary, options: [.atomic])
            try fileManager.setAttributes([.posixPermissions: permissions], ofItemAtPath: temporary.path)
            if fileManager.fileExists(atPath: destination.path) {
                let values = try destination.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
                guard values.isRegularFile == true,
                      values.isSymbolicLink != true,
                      ownedByCurrentUser(destination)
                else { throw DesktopManagedInstallError.unsafeFilesystemObject }
                _ = try fileManager.replaceItemAt(destination, withItemAt: temporary)
            } else {
                try fileManager.moveItem(at: temporary, to: destination)
            }
            try fileManager.setAttributes([.posixPermissions: permissions], ofItemAtPath: destination.path)
        } catch let error as DesktopManagedInstallError {
            try? fileManager.removeItem(at: temporary)
            throw error
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw DesktopManagedInstallError.persistenceFailed
        }
    }
}

private struct ManagedReleaseMarker: Codable {
    let schemaVersion: Int
    let runID: String
    let releaseVersion: String
}

public enum DesktopLaunchAgentError: Error, Equatable, Sendable {
    case invalidConfiguration
    case encodingFailed
}

public struct DesktopHermesServerLaunchAgent: Sendable {
    public let hermesExecutable: URL
    public let hermesHome: URL
    public let runtimeContract: DesktopHermesRuntimeContract
    public let sessionTokenFile: URL
    public let standardOutput: URL
    public let standardError: URL

    public init(
        hermesExecutable: URL,
        hermesHome: URL,
        runtimeContract: DesktopHermesRuntimeContract,
        sessionTokenFile: URL,
        standardOutput: URL,
        standardError: URL
    ) {
        self.hermesExecutable = hermesExecutable
        self.hermesHome = hermesHome
        self.runtimeContract = runtimeContract
        self.sessionTokenFile = sessionTokenFile
        self.standardOutput = standardOutput
        self.standardError = standardError
    }

    public func encodedPropertyList() throws -> Data {
        guard [hermesExecutable, standardOutput, standardError].allSatisfy({
            $0.isFileURL && $0.path.hasPrefix("/") && $0.path != "/"
        }) else { throw DesktopLaunchAgentError.invalidConfiguration }
        let environment = try runtimeContract.environmentVariables(
            hermesHome: hermesHome,
            sessionTokenFile: sessionTokenFile
        )
        let object: [String: Any] = [
            "Label": DesktopManagedInstallLayout.hermesLabel,
            "ProgramArguments": [hermesExecutable.path] + runtimeContract.programArguments,
            "RunAtLoad": true,
            "ProcessType": "Background",
            "ThrottleInterval": 30,
            "StandardOutPath": standardOutput.path,
            "StandardErrorPath": standardError.path,
            "EnvironmentVariables": environment,
        ]
        do {
            return try PropertyListSerialization.data(
                fromPropertyList: object,
                format: .xml,
                options: 0
            )
        } catch { throw DesktopLaunchAgentError.encodingFailed }
    }
}

public struct DesktopAccountConnectorLaunchAgent: Sendable {
    public let connectorExecutable: URL
    public let credentialFile: URL
    public let gatewayURL: URL
    public let hermesBaseURL: URL
    public let sessionTokenFile: URL
    public let standardOutput: URL
    public let standardError: URL

    public init(
        connectorExecutable: URL,
        credentialFile: URL,
        gatewayURL: URL,
        hermesBaseURL: URL,
        sessionTokenFile: URL,
        standardOutput: URL,
        standardError: URL
    ) {
        self.connectorExecutable = connectorExecutable
        self.credentialFile = credentialFile
        self.gatewayURL = gatewayURL
        self.hermesBaseURL = hermesBaseURL
        self.sessionTokenFile = sessionTokenFile
        self.standardOutput = standardOutput
        self.standardError = standardError
    }

    public func encodedPropertyList() throws -> Data {
        guard [connectorExecutable, credentialFile, sessionTokenFile, standardOutput, standardError].allSatisfy({
            $0.isFileURL && $0.path.hasPrefix("/") && $0.path != "/"
        }), Self.validGateway(gatewayURL), Self.validHermes(hermesBaseURL) else {
            throw DesktopLaunchAgentError.invalidConfiguration
        }
        let object: [String: Any] = [
            "Label": DesktopManagedInstallLayout.connectorLabel,
            "ProgramArguments": [connectorExecutable.path],
            "RunAtLoad": true,
            "KeepAlive": ["NetworkState": true],
            "ProcessType": "Background",
            "StandardOutPath": standardOutput.path,
            "StandardErrorPath": standardError.path,
            "EnvironmentVariables": [
                "CONNECTOR_MODE": "account",
                "ACCOUNT_CONNECTOR_CREDENTIAL_FILE": credentialFile.path,
                "GATEWAY_URL": gatewayURL.absoluteString,
                "HERMES_BASE_URL": hermesBaseURL.absoluteString,
                "HERMES_SESSION_TOKEN_FILE": sessionTokenFile.path,
            ],
        ]
        do {
            return try PropertyListSerialization.data(
                fromPropertyList: object,
                format: .xml,
                options: 0
            )
        } catch { throw DesktopLaunchAgentError.encodingFailed }
    }

    private static func validGateway(_ url: URL) -> Bool {
        guard url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path == "/v2/connect", let host = url.host?.lowercased()
        else { return false }
        if url.scheme == "wss" { return true }
        return url.scheme == "ws" && ["127.0.0.1", "::1", "localhost"].contains(host)
    }

    private static func validHermes(_ url: URL) -> Bool {
        guard url.scheme == "http", url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil,
              let host = url.host?.lowercased()
        else { return false }
        return ["127.0.0.1", "::1", "localhost"].contains(host)
    }
}

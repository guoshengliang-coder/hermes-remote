import CryptoKit
import Darwin
import Foundation

/// A Hermes LaunchAgent rewritten by a runtime switch, plus the exact bytes to put back if the
/// restart that follows cannot prove a healthy server.
public enum DesktopHermesRuntimeError: Error, Equatable, Sendable {
    /// The bundled agent still stores the session token inline; the token-file migration must run
    /// before Desktop will switch.
    case bundledAgentCarriesInlineToken
}

public struct DesktopHermesRuntimeSwitch: Sendable {
    public let launchAgentURL: URL
    public let logURL: URL

    fileprivate let originalLaunchAgent: Data
    /// nil when the switch did not write the launcher; otherwise what was there before (nil inside
    /// meaning "nothing", so a rollback removes it).
    fileprivate let originalLauncher: Data??
}

extension DesktopManagedInstaller {
    private static var serveArguments: [String] { DesktopHermesRuntimeContract.serveV1.programArguments }
    private var hermesLog: URL { layout.logsRoot.appendingPathComponent("hermes-server.log") }
    private var hermesErrorLog: URL { layout.logsRoot.appendingPathComponent("hermes-server.error.log") }

    /// Which program the Hermes LaunchAgent runs. Throws only when the agent file itself is unsafe
    /// (wrong owner, readable by others, a symlink); a well-formed agent Desktop did not write is
    /// `.unrecognised`.
    public func currentHermesRuntimeMode() throws -> DesktopHermesRuntimeMode {
        var metadata = stat()
        if Darwin.lstat(layout.hermesLaunchAgent.path, &metadata) != 0 {
            guard errno == ENOENT else { throw DesktopManagedInstallError.unsafeFilesystemObject }
            return .absent
        }
        let data = try readOwnedPrivateFile(layout.hermesLaunchAgent)
        guard let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)
                as? [String: Any],
              let first = (object["ProgramArguments"] as? [String])?.first
        else { return .unrecognised }
        if first == layout.localHermesLauncher.standardizedFileURL.path {
            guard let local = try? loadLocalHermesLaunchAgent() else { return .unrecognised }
            return .localHermes(executable: local.executable)
        }
        return (try? loadBundledHermesLaunchAgent(at: layout.hermesLaunchAgent)) != nil
            ? .bundled
            : .unrecognised
    }

    /// The Hermes agent in whichever shape Desktop wrote it, for the token-file contract checks.
    func loadHermesLaunchAgentForTokenContract() throws -> ManagedLaunchAgentPropertyList {
        if try currentHermesRuntimeMode().isLocal { return try loadLocalHermesLaunchAgent().plist }
        return try loadManagedLaunchAgent(
            layout.hermesLaunchAgent,
            label: DesktopManagedInstallLayout.hermesLabel,
            component: .hermesServer,
            trailingArguments: Self.serveArguments,
            expectedLog: hermesLog,
            expectedErrorLog: hermesErrorLog
        )
    }

    /// Validates a local-mode agent completely: label, program arguments, log paths, ownership, the
    /// token-file environment, and that the launcher on disk is byte-for-byte the one this build
    /// would write for that executable.
    func loadLocalHermesLaunchAgent(
        at url: URL? = nil
    ) throws -> (plist: ManagedLaunchAgentPropertyList, executable: URL) {
        let url = url ?? layout.hermesLaunchAgent
        let data = try readOwnedPrivateFile(url)
        guard let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)
                as? [String: Any],
              let arguments = object["ProgramArguments"] as? [String],
              arguments.count == 2 + Self.serveArguments.count
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        let executable = arguments[1]
        guard DesktopLocalHermesDetector.isScriptSafePath(executable),
              executable.hasSuffix("/venv/bin/hermes"),
              URL(fileURLWithPath: executable).standardizedFileURL.path == executable
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        let plist = try loadManagedLaunchAgent(
            url,
            label: DesktopManagedInstallLayout.hermesLabel,
            expectedExecutable: layout.localHermesLauncher,
            trailingArguments: [executable] + Self.serveArguments,
            expectedLog: hermesLog,
            expectedErrorLog: hermesErrorLog
        )
        guard try sessionTokenStorage(in: plist.object) == .file else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        // Recognised by the agent's shape alone. The launcher is checked separately
        // (`localHermesLauncherIsCurrent`): a launcher from an older build, or one whose mode a
        // backup restore loosened, is something to rewrite — not a reason to stop recognising a
        // Mac as being in local mode, which would strand it there (no rollback, no upgrade).
        return (plist, URL(fileURLWithPath: executable))
    }

    /// Whether the launcher on disk is exactly what this build writes for `executable`, owned by
    /// this user and not readable or writable by anyone else.
    public func localHermesLauncherIsCurrent(executable: URL) -> Bool {
        var metadata = stat()
        guard Darwin.lstat(layout.localHermesLauncher.path, &metadata) == 0,
              metadata.st_mode & S_IFMT == S_IFREG,
              metadata.st_uid == Darwin.getuid(),
              metadata.st_mode & 0o777 == 0o700,
              let expected = try? DesktopLocalHermesLauncher.script(executable: executable),
              let actual = try? Data(contentsOf: layout.localHermesLauncher)
        else { return false }
        return actual == expected
    }

    public func writeLocalHermesLauncher(executable: URL) throws {
        let script: Data
        do { script = try DesktopLocalHermesLauncher.script(executable: executable) }
        catch { throw DesktopManagedInstallError.invalidInput }
        try ensurePrivateDirectory(layout.localHermesLauncher.deletingLastPathComponent())
        try atomicWrite(script, to: layout.localHermesLauncher, permissions: 0o700)
    }

    /// A cheap, non-throwing look at the agent: does its first program argument name the local
    /// launcher? Used to skip everything else while the setting is off.
    public var hermesAgentLooksLocal: Bool {
        guard let data = try? Data(contentsOf: layout.hermesLaunchAgent),
              let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)
                as? [String: Any],
              let first = (object["ProgramArguments"] as? [String])?.first
        else { return false }
        return first == layout.localHermesLauncher.standardizedFileURL.path
    }

    /// `ProgramArguments` of the agent file, for comparison with what launchd loaded.
    public var hermesAgentProgramArguments: [String]? {
        guard let data = try? Data(contentsOf: layout.hermesLaunchAgent),
              let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)
                as? [String: Any]
        else { return nil }
        return object["ProgramArguments"] as? [String]
    }

    /// A bundled agent from the historical v1 `current/hermes_server` layout.
    private func loadBundledHermesLaunchAgent(at url: URL) throws -> ManagedLaunchAgentPropertyList {
        if let bundled = try? loadManagedLaunchAgent(
            url,
            label: DesktopManagedInstallLayout.hermesLabel,
            expectedExecutable: layout.currentRelease
                .appendingPathComponent(DesktopReleaseComponentKind.hermesServer.rawValue, isDirectory: true),
            executableMustBeDescendant: true,
            trailingArguments: Self.serveArguments,
            expectedLog: hermesLog,
            expectedErrorLog: hermesErrorLog
        ) {
            return bundled
        }
        return try loadManagedLaunchAgent(
            url,
            label: DesktopManagedInstallLayout.hermesLabel,
            expectedExecutable: layout.root
                .appendingPathComponent("components", isDirectory: true)
                .appendingPathComponent(DesktopManagedComponentKind.hermesCore.rawValue, isDirectory: true),
            executableMustBeDescendant: true,
            trailingArguments: Self.serveArguments,
            expectedLog: hermesLog,
            expectedErrorLog: hermesErrorLog
        )
    }

    /// Whether a recognised bundled agent is stored to return to.
    ///
    /// The shape is not enough: a component-store agent names a content-addressed directory that
    /// garbage collection may have removed, so the program and the Python runtime it names must
    /// still exist.
    public var bundledHermesFallbackAvailable: Bool {
        guard let bundled = try? loadBundledHermesLaunchAgent(at: layout.bundledHermesLaunchAgentBackup),
              (try? sessionTokenStorage(in: bundled.object)) == .file,
              let program = (bundled.object["ProgramArguments"] as? [String])?.first,
              FileManager.default.isExecutableFile(atPath: URL(fileURLWithPath: program).resolvingSymlinksInPath().path)
        else { return false }
        if let python = (bundled.object["EnvironmentVariables"] as? [String: Any])?["HERMES_PYTHON_RUNTIME_ROOT"] as? String {
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: python, isDirectory: &isDirectory), isDirectory.boolValue
            else { return false }
        }
        return true
    }

    /// Store `data` (an encoded bundled agent) as the one to return to. Used by upgrades in local
    /// mode, so the kept agent always names the release that is actually installed.
    func writeBundledHermesBackup(_ data: Data) throws {
        try ensurePrivateDirectory(layout.stateRoot)
        try atomicWrite(data, to: layout.bundledHermesLaunchAgentBackup, permissions: 0o600)
    }

    /// The local-mode agent for this installation, bound to this layout's launcher, token and logs.
    public func localHermesLaunchAgent(
        for installation: DesktopLocalHermesInstallation
    ) -> DesktopLocalHermesLaunchAgent {
        DesktopLocalHermesLaunchAgent(
            launcher: layout.localHermesLauncher,
            installation: installation,
            sessionTokenFile: layout.hermesSessionToken,
            standardOutput: hermesLog,
            standardError: hermesErrorLog
        )
    }

    /// Create the initial Hermes service from the owner's standard local installation. Component
    /// releases no longer carry a bundled Hermes fallback, so this is the only valid fresh-install
    /// path for their Hermes LaunchAgent.
    public func writeLocalHermesLaunchAgent(
        _ installation: DesktopLocalHermesInstallation
    ) throws -> URL {
        _ = try validatedSessionTokenIfPresent(required: true)
        let configuration = localHermesLaunchAgent(for: installation)
        let replacement: Data
        let script: Data
        do {
            replacement = try configuration.encodedPropertyList()
            script = try DesktopLocalHermesLauncher.script(executable: installation.executable)
        } catch { throw DesktopManagedInstallError.invalidInput }
        guard replacement.count <= 64 * 1024 else { throw DesktopManagedInstallError.invalidInput }

        try ensureOwnedDirectory(layout.launchAgentsRoot)
        try ensurePrivateDirectory(layout.logsRoot)
        try ensurePrivateDirectory(layout.localHermesLauncher.deletingLastPathComponent())
        let originalLauncher = try existingOwnedFile(layout.localHermesLauncher)
        try atomicWrite(script, to: layout.localHermesLauncher, permissions: 0o700)
        do {
            try atomicWrite(replacement, to: layout.hermesLaunchAgent, permissions: 0o600)
        } catch {
            try? restoreLauncher(originalLauncher)
            throw error
        }
        return layout.hermesLaunchAgent
    }

    /// Point the Hermes agent at this Mac's own Hermes. Only the files change; the caller restarts
    /// the service and either commits or calls `rollbackHermesRuntimeSwitch`.
    ///
    /// When the agent being replaced is the bundled one, its exact bytes are kept first — they are
    /// the only record of which release to go back to, and nothing else in local mode refers to it.
    public func prepareLocalHermesRuntime(
        _ installation: DesktopLocalHermesInstallation
    ) throws -> DesktopHermesRuntimeSwitch {
        let configuration = localHermesLaunchAgent(for: installation)
        let mode = try currentHermesRuntimeMode()
        if mode == .bundled,
           let bundled = try? loadBundledHermesLaunchAgent(at: layout.hermesLaunchAgent),
           try sessionTokenStorage(in: bundled.object) != .file {
            throw DesktopHermesRuntimeError.bundledAgentCarriesInlineToken
        }
        _ = try validatedSessionTokenIfPresent(required: true)
        let original = try readOwnedPrivateFile(layout.hermesLaunchAgent)
        let replacement: Data
        let script: Data
        do {
            replacement = try configuration.encodedPropertyList()
            script = try DesktopLocalHermesLauncher.script(executable: installation.executable)
        } catch { throw DesktopManagedInstallError.invalidInput }
        guard replacement.count <= 64 * 1024 else { throw DesktopManagedInstallError.invalidInput }

        switch mode {
        case .bundled:
            // An agent that still carries the token inline would copy the secret into `state/`, and
            // could never be restored (restore requires the file contract). The startup token
            // reconciler migrates such agents; switch only after it has.
            guard let bundled = try? loadBundledHermesLaunchAgent(at: layout.hermesLaunchAgent),
                  try sessionTokenStorage(in: bundled.object) == .file
            else { throw DesktopHermesRuntimeError.bundledAgentCarriesInlineToken }
            try ensurePrivateDirectory(layout.stateRoot)
            try atomicWrite(original, to: layout.bundledHermesLaunchAgentBackup, permissions: 0o600)
        case .localHermes:
            break
        case .absent, .unrecognised:
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }

        try ensurePrivateDirectory(layout.localHermesLauncher.deletingLastPathComponent())
        let originalLauncher = try existingOwnedFile(layout.localHermesLauncher)
        try atomicWrite(script, to: layout.localHermesLauncher, permissions: 0o700)
        do {
            try atomicWrite(replacement, to: layout.hermesLaunchAgent, permissions: 0o600)
        } catch {
            try? restoreLauncher(originalLauncher)
            throw error
        }
        return DesktopHermesRuntimeSwitch(
            launchAgentURL: layout.hermesLaunchAgent,
            logURL: hermesLog,
            originalLaunchAgent: original,
            originalLauncher: .some(originalLauncher)
        )
    }

    /// Put the stored bundled agent back, for a Mac whose own Hermes has been removed.
    public func prepareBundledHermesRuntimeRestore() throws -> DesktopHermesRuntimeSwitch {
        guard try currentHermesRuntimeMode().isLocal else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        guard bundledHermesFallbackAvailable else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        let original = try readOwnedPrivateFile(layout.hermesLaunchAgent)
        let bundled = try loadBundledHermesLaunchAgent(at: layout.bundledHermesLaunchAgentBackup)
        guard try sessionTokenStorage(in: bundled.object) == .file else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        try atomicWrite(bundled.data, to: layout.hermesLaunchAgent, permissions: 0o600)
        return DesktopHermesRuntimeSwitch(
            launchAgentURL: layout.hermesLaunchAgent,
            logURL: hermesLog,
            originalLaunchAgent: original,
            originalLauncher: .none
        )
    }

    public func rollbackHermesRuntimeSwitch(_ runtimeSwitch: DesktopHermesRuntimeSwitch) throws {
        guard runtimeSwitch.launchAgentURL.standardizedFileURL.path
                == layout.hermesLaunchAgent.standardizedFileURL.path
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        try atomicWrite(runtimeSwitch.originalLaunchAgent, to: layout.hermesLaunchAgent, permissions: 0o600)
        if case .some(let launcher) = runtimeSwitch.originalLauncher {
            try restoreLauncher(launcher)
        }
    }

    /// Finish a restore: the backup has served its purpose and local-mode bookkeeping goes with it.
    public func commitBundledHermesRuntimeRestore() throws {
        guard try currentHermesRuntimeMode() == .bundled else {
            throw DesktopManagedInstallError.unsafeFilesystemObject
        }
        try removeOwnedRegularFileIfPresent(layout.bundledHermesLaunchAgentBackup)
        try removeOwnedRegularFileIfPresent(layout.localHermesRuntimeRecord)
    }

    public func readLocalHermesRuntimeRecord() -> DesktopLocalHermesRuntimeRecord? {
        guard let data = try? readOwnedPrivateFile(layout.localHermesRuntimeRecord) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        guard let record = try? decoder.decode(DesktopLocalHermesRuntimeRecord.self, from: data),
              record.schemaVersion == 1
        else { return nil }
        return record
    }

    public func writeLocalHermesRuntimeRecord(_ record: DesktopLocalHermesRuntimeRecord) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.dateEncodingStrategy = .secondsSince1970
        let data: Data
        do { data = try encoder.encode(record) } catch { throw DesktopManagedInstallError.persistenceFailed }
        try ensurePrivateDirectory(layout.stateRoot)
        try atomicWrite(data, to: layout.localHermesRuntimeRecord, permissions: 0o600)
    }

    public func readHermesRuntimeFailures() -> DesktopHermesRuntimeFailures {
        guard let data = try? readOwnedPrivateFile(layout.localHermesRuntimeFailures),
              let failures = try? JSONDecoder().decode(DesktopHermesRuntimeFailures.self, from: data)
        else { return DesktopHermesRuntimeFailures() }
        return failures
    }

    public func writeHermesRuntimeFailures(_ failures: DesktopHermesRuntimeFailures) throws {
        if failures == DesktopHermesRuntimeFailures() {
            try removeOwnedRegularFileIfPresent(layout.localHermesRuntimeFailures)
            return
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data: Data
        do { data = try encoder.encode(failures) } catch { throw DesktopManagedInstallError.persistenceFailed }
        try ensurePrivateDirectory(layout.stateRoot)
        try atomicWrite(data, to: layout.localHermesRuntimeFailures, permissions: 0o600)
    }

    /// SHA-256 of the kept bundled agent, so a paused rollback resumes when the agent changes.
    public var bundledHermesBackupDigest: String? {
        guard let data = try? readOwnedPrivateFile(layout.bundledHermesLaunchAgentBackup) else { return nil }
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func existingOwnedFile(_ url: URL) throws -> Data? {
        var metadata = stat()
        if Darwin.lstat(url.path, &metadata) != 0 {
            guard errno == ENOENT else { throw DesktopManagedInstallError.unsafeFilesystemObject }
            return nil
        }
        return try readOwnedPrivateFile(url)
    }

    private func restoreLauncher(_ data: Data?) throws {
        if let data {
            try atomicWrite(data, to: layout.localHermesLauncher, permissions: 0o700)
        } else {
            try removeOwnedRegularFileIfPresent(layout.localHermesLauncher)
        }
    }
}

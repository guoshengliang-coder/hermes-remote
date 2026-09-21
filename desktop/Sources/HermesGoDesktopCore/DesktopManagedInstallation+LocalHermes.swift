import Darwin
import Foundation

/// A Hermes LaunchAgent rewritten by a runtime switch, plus the exact bytes to put back if the
/// restart that follows cannot prove a healthy server.
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
        let executableURL = URL(fileURLWithPath: executable)
        guard try readOwnedPrivateFile(layout.localHermesLauncher)
                == DesktopLocalHermesLauncher.script(executable: executableURL)
        else { throw DesktopManagedInstallError.unsafeFilesystemObject }
        return (plist, executableURL)
    }

    /// A bundled agent: the v1 `current/hermes_server` release, or a v2 component-store `hermes_core`.
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
    public var bundledHermesFallbackAvailable: Bool {
        (try? loadBundledHermesLaunchAgent(at: layout.bundledHermesLaunchAgentBackup)) != nil
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

    /// Point the Hermes agent at this Mac's own Hermes. Only the files change; the caller restarts
    /// the service and either commits or calls `rollbackHermesRuntimeSwitch`.
    ///
    /// When the agent being replaced is the bundled one, its exact bytes are kept first — they are
    /// the only record of which release to go back to, and nothing else in local mode refers to it.
    public func prepareLocalHermesRuntime(
        _ installation: DesktopLocalHermesInstallation
    ) throws -> DesktopHermesRuntimeSwitch {
        let configuration = localHermesLaunchAgent(for: installation)
        _ = try validatedSessionTokenIfPresent(required: true)
        let mode = try currentHermesRuntimeMode()
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

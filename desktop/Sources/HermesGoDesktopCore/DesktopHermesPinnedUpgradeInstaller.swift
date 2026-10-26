import CryptoKit
import Darwin
import Foundation

/// A bounded adapter for the official staged installer, not a service coordinator. The worker
/// must independently check recommendation freshness, installation identity and stopped writers.
/// It runs only under durable `updating` intent with a recovery point and never marks success or
/// starts a service. Every failure leaves that intent for reconciliation, without an automatic retry.
public struct DesktopHermesPinnedUpgradeInstaller: Sendable {
    public enum Stage: String, CaseIterable, Sendable {
        case repository, venv
        case pythonDependencies = "python-deps"
        case config, products, complete
    }

    public struct Prepared: Sendable {
        public let identity: HermesCompatibilityIdentity
        fileprivate let script: URL
        fileprivate let directory: URL
        fileprivate let bytes: Data
        fileprivate init(identity: HermesCompatibilityIdentity, script: URL, directory: URL, bytes: Data) {
            self.identity = identity; self.script = script; self.directory = directory; self.bytes = bytes
        }
    }

    private let runner: any DesktopHermesInstallerProcessRunning
    private let fetcher: any DesktopHermesInstallerScriptFetching
    private let readCommit: @Sendable (URL) -> String?
    private let stageTimeout: Duration

    public init() {
        runner = DesktopPosixProcessRunner(requireProcessGroupExit: true); fetcher = DesktopHermesInstallerURLSessionFetcher()
        readCommit = { Self.commit($0) }; stageTimeout = .seconds(900)
    }
    init(runner: any DesktopHermesInstallerProcessRunning, fetcher: any DesktopHermesInstallerScriptFetching,
         stageTimeout: Duration = .seconds(900), readCommit: @escaping @Sendable (URL) -> String? = { Self.commit($0) }) {
        self.runner = runner; self.fetcher = fetcher; self.stageTimeout = stageTimeout; self.readCommit = readCommit
    }

    /// Preparation happens before stopping services. Only bytes named by the verified decision
    /// are written; neither a moving `main` script nor a changed download can be executed.
    public func prepare(recommendation: VerifiedHermesRecommendation, entryID: String,
                        workRoot: URL, now: Date = Date()) async throws -> Prepared {
        guard getuid() != 0, now < recommendation.expiresAt,
              let entry = recommendation.entries.first(where: { $0.id == entryID }),
              entry.identity.maintenancePolicy == .goControlledWindowV1,
              entry.identity.environment.installation == "legacy-venv-to-pm"
        else { throw DesktopHermesUpgradeError.preflightBlocked }
        let identity = entry.identity
        let url = URL(string: "https://raw.githubusercontent.com/NousResearch/hermes-agent/\(identity.targetCommit)/scripts/install.sh")!
        do {
            let bytes = try await fetcher.fetchInstallerScript(from: url)
            guard !bytes.isEmpty, bytes.count <= DesktopHermesInstallerSource.maximumScriptBytes,
                  Self.hash(bytes) == identity.installerSha256 else { throw DesktopHermesUpgradeError.preflightBlocked }
            try Self.privateDirectory(workRoot)
            let directory = workRoot.appendingPathComponent("hermes-upgrade-installer-\(UUID().uuidString)")
            guard mkdir(directory.path, 0o700) == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
            let script = directory.appendingPathComponent("install.sh")
            let descriptor = Darwin.open(script.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard descriptor >= 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
            defer { Darwin.close(descriptor) }
            try FileHandle(fileDescriptor: descriptor, closeOnDealloc: false).write(contentsOf: bytes)
            guard fsync(descriptor) == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
            return Prepared(identity: identity, script: script, directory: directory, bytes: bytes)
        } catch { throw DesktopHermesUpgradeError.preflightBlocked }
    }

    /// `beforeStage` must recheck the worker's live maintenance-window conditions. The adapter
    /// does not infer quiescence from a marker or an empty session list. No upstream log is sent
    /// to the UI callback: it carries only the fixed stage and whether that stage completed.
    public func run(_ prepared: Prepared, operation: DesktopHermesUpgradeOperation,
                    paths: DesktopLocalHermesPaths, proxy: DesktopSystemProxy,
                    beforeStage: @escaping @Sendable () async throws -> Void,
                    progress: @escaping @Sendable (Stage, Bool) -> Void = { _, _ in }) async throws {
        try await operation.withUpdatingEffect { intent in
            guard intent.target.commit == prepared.identity.targetCommit,
                  intent.target.version == prepared.identity.version,
                  intent.sourceCommit == prepared.identity.sourceCommit,
                  intent.checkout == paths.checkoutRoot.path, intent.hermesHome == paths.hermesHome.path,
                  readCommit(paths.checkoutRoot) == intent.sourceCommit
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            let environment = Self.environment(paths: paths, temporary: prepared.directory, proxy: proxy)
            let common = ["--dir", paths.checkoutRoot.path, "--hermes-home", paths.hermesHome.path,
                          "--commit", prepared.identity.targetCommit, "--skip-browser", "--skip-computer-use"]
            do {
                try await beforeStage()
                try operation.requireUpdatingEffect(intent)
                try Self.verifyFile(prepared)
                let manifestRun = try await invoke(prepared, arguments: ["--manifest"], environment: environment)
                guard manifestRun.exitStatus == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
                let manifest = try DesktopHermesInstallerManifest.parse(manifestRun.standardOutputTail.joined(separator: "\n"))
                guard Stage.allCases.allSatisfy({ stage in
                    manifest.stages.contains(where: { $0.name == stage.rawValue && !$0.needsUserInput })
                }) else { throw DesktopHermesUpgradeError.recoveryRequired }
                for stage in Stage.allCases {
                    try Task.checkCancellation()
                    try await beforeStage()
                    try operation.requireUpdatingEffect(intent)
                    try Self.verifyFile(prepared)
                    let expected = stage == .repository ? intent.sourceCommit : intent.target.commit
                    guard readCommit(paths.checkoutRoot) == expected else { throw DesktopHermesUpgradeError.recoveryRequired }
                    progress(stage, false)
                    let result = try await invoke(prepared,
                        arguments: ["--stage", stage.rawValue, "--non-interactive", "--json"] + common,
                        environment: environment)
                    guard result.exitStatus == 0,
                          let frame = DesktopHermesInstallerStageResult.parse(result.standardOutputTail),
                          frame.ok, frame.stage == stage.rawValue,
                          readCommit(paths.checkoutRoot) == intent.target.commit
                    else { throw DesktopHermesUpgradeError.recoveryRequired }
                    try operation.requireUpdatingEffect(intent)
                    progress(stage, true)
                }
                try await beforeStage()
            } catch {
                // Cancellation or a nonzero result may follow disk mutation. Preserve intent and
                // backup; never infer that it is safe to re-run repository/reset from the source.
                throw DesktopHermesUpgradeError.recoveryRequired
            }
        }
    }

    private func invoke(_ prepared: Prepared, arguments: [String], environment: [String: String]) async throws -> DesktopHermesInstallerProcessResult {
        try await withThrowingTaskGroup(of: DesktopHermesInstallerProcessResult.self) { group in
            group.addTask {
                try await runner.run(executable: URL(fileURLWithPath: "/bin/bash"),
                    arguments: [prepared.script.path] + arguments, environment: environment,
                    workingDirectory: prepared.directory, onLine: { _ in })
            }
            group.addTask {
                try await Task.sleep(for: stageTimeout)
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw DesktopHermesUpgradeError.recoveryRequired }
            return result
        }
    }

    static func environment(paths: DesktopLocalHermesPaths, temporary: URL, proxy: DesktopSystemProxy) -> [String: String] {
        var value = ["HOME": paths.homeDirectory.path, "HERMES_HOME": paths.hermesHome.path,
            "TMPDIR": temporary.path, "PATH": "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin:/usr/local/bin:" + paths.homeDirectory.appendingPathComponent(".local/bin").path,
            "SHELL": "/bin/bash", "LANG": "en_US.UTF-8", "TERM": "dumb", "NO_COLOR": "1", "NONINTERACTIVE": "1",
            "GIT_TERMINAL_PROMPT": "0", "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_COUNT": "2", "GIT_CONFIG_KEY_0": "core.hooksPath", "GIT_CONFIG_VALUE_0": "/dev/null",
            "GIT_CONFIG_KEY_1": "core.fsmonitor", "GIT_CONFIG_VALUE_1": "false",
            "PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1", "SUDO_ASKPASS": "/usr/bin/false"]
        for (key, item) in proxy.environment(inheriting: [:]) { value[key] = item }
        return value
    }

    static func commit(_ checkout: URL) -> String? {
        guard let bytes = DesktopHermesUpgradeGitInspector.readOnlyOutput(checkout: checkout,
            arguments: ["rev-parse", "HEAD"], deadline: ProcessInfo.processInfo.systemUptime + 3),
              let text = String(data: bytes, encoding: .utf8)?.trimmingCharacters(in: .newlines),
              DesktopLocalHermesDetector.isObjectID(text) else { return nil }
        return text
    }
    private static func hash(_ bytes: Data) -> String { SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined() }
    private static func privateDirectory(_ url: URL) throws {
        var info = stat()
        guard url.isFileURL, url.path != "/", url.standardizedFileURL == url.resolvingSymlinksInPath(),
              lstat(url.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
    }
    private static func verifyFile(_ prepared: Prepared) throws {
        try privateDirectory(prepared.directory)
        let descriptor = Darwin.open(prepared.script.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK)
        guard descriptor >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(descriptor) }
        var info = stat()
        guard fstat(descriptor, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFREG,
              info.st_nlink == 1, info.st_mode & 0o077 == 0, info.st_size == prepared.bytes.count,
              try FileHandle(fileDescriptor: descriptor, closeOnDealloc: false).read(upToCount: prepared.bytes.count + 1) == prepared.bytes
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
}

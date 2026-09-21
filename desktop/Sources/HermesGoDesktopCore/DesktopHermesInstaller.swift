import CryptoKit
import Darwin
import Foundation

// Install-when-missing, phase 1 (`docs/MANAGED_HERMES_STRATEGY.md`, Order of work step 4).
//
// A Mac with no Hermes gets upstream's own installer, run into upstream's standard location, after
// which it is simply "a Mac with Hermes" and local runtime mode takes over. Desktop drives the
// installer's machine-readable stage protocol (`install.sh --manifest`, then `--stage <name>
// --non-interactive --json` per stage) — the same protocol upstream's own desktop bootstrap uses —
// rather than scraping its human output. Nothing here runs without an explicit owner confirmation
// (`DesktopHermesInstallConfirmation`), never as root, never through `sudo`, and never over an
// existing or unusual Hermes: those stay "surface, don't touch".

// MARK: - Configuration

public enum DesktopHermesInstallerSource {
    /// Upstream's documented one-liner source (`curl -fsSL … | bash`), fetched over HTTPS.
    public static let scriptURL = URL(string: "https://hermes-agent.nousresearch.com/install.sh")!
    /// Where the download may end up after redirects. The official origin, or upstream's own file
    /// on GitHub (which upstream's bootstrap installer downloads from).
    public static let allowedHosts = ["hermes-agent.nousresearch.com", "raw.githubusercontent.com"]

    /// Whether a (post-redirect) URL is still upstream's: HTTPS, and either the official origin or
    /// a file inside upstream's own repository on GitHub.
    public static func isOfficial(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", let host = url.host?.lowercased(),
              allowedHosts.contains(host), url.port == nil || url.port == 443
        else { return false }
        return host != "raw.githubusercontent.com" || url.path.hasPrefix("/NousResearch/hermes-agent/")
    }
    public static let branch = "main"
    /// The only `protocol_version` Desktop speaks. A different one is refused, not guessed at.
    public static let protocolVersion = 1
    public static let maximumScriptBytes = 2 * 1024 * 1024
}

// MARK: - Protocol

public struct DesktopHermesInstallerStage: Equatable, Sendable, Identifiable {
    public let name: String
    public let title: String
    public let category: String
    public let needsUserInput: Bool

    public var id: String { name }

    public init(name: String, title: String, category: String, needsUserInput: Bool) {
        self.name = name
        self.title = title
        self.category = category
        self.needsUserInput = needsUserInput
    }

    /// Chinese titles for the stages upstream's manifest names today; an unknown stage keeps
    /// upstream's English title rather than being hidden.
    public var titleChinese: String {
        switch name {
        case "prerequisites": "检查系统依赖"
        case "repository": "下载 Hermes"
        case "venv": "创建 Python 虚拟环境"
        case "python-deps": "安装 Python 依赖"
        case "node-deps": "安装浏览器工具依赖"
        case "path": "安装 hermes 命令"
        case "config": "准备配置与技能"
        case "setup": "配置模型服务"
        case "gateway": "配置消息网关"
        case "complete": "完成安装"
        default: title
        }
    }
}

public struct DesktopHermesInstallerManifest: Equatable, Sendable {
    public let protocolVersion: Int
    public let stages: [DesktopHermesInstallerStage]

    /// The manifest is the last line of `--manifest` output that parses as a JSON object with a
    /// `stages` array (upstream's runner reads it the same way: banners may precede it).
    public static func parse(_ output: String) throws -> DesktopHermesInstallerManifest {
        for line in output.split(whereSeparator: \.isNewline).reversed() {
            guard let data = line.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let rawStages = object["stages"] as? [Any]
            else { continue }
            guard let version = (object["protocol_version"] as? NSNumber)?.intValue else {
                throw DesktopHermesInstallFailure.protocolMismatch(detail: "manifest carries no protocol_version")
            }
            guard version == DesktopHermesInstallerSource.protocolVersion else {
                throw DesktopHermesInstallFailure.protocolMismatch(detail: "unsupported protocol_version \(version)")
            }
            var stages: [DesktopHermesInstallerStage] = []
            for raw in rawStages {
                guard let stage = raw as? [String: Any],
                      let name = stage["name"] as? String,
                      name.range(of: "^[a-z][a-z0-9-]{0,31}$", options: .regularExpression) != nil,
                      !stages.contains(where: { $0.name == name })
                else {
                    throw DesktopHermesInstallFailure.protocolMismatch(detail: "manifest stage is malformed")
                }
                let title = (stage["title"] as? String).map { String($0.prefix(80)) } ?? name
                stages.append(DesktopHermesInstallerStage(
                    name: name,
                    title: title,
                    category: stage["category"] as? String ?? "runtime",
                    needsUserInput: (stage["needs_user_input"] as? NSNumber)?.boolValue ?? false
                ))
            }
            guard !stages.isEmpty, stages.count <= 32 else {
                throw DesktopHermesInstallFailure.protocolMismatch(detail: "manifest has \(stages.count) stages")
            }
            return DesktopHermesInstallerManifest(protocolVersion: version, stages: stages)
        }
        throw DesktopHermesInstallFailure.protocolMismatch(detail: "no manifest in --manifest output")
    }
}

/// `{"ok":…,"stage":"…","skipped":…,"reason":"…"}`, the last such line of a stage's stdout.
public struct DesktopHermesInstallerStageResult: Equatable, Sendable {
    public let ok: Bool
    public let stage: String
    public let skipped: Bool
    public let reason: String?

    public static func parse(_ lines: [String]) -> DesktopHermesInstallerStageResult? {
        for line in lines.reversed() {
            guard let data = line.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let ok = object["ok"] as? Bool,
                  let stage = object["stage"] as? String
            else { continue }
            return DesktopHermesInstallerStageResult(
                ok: ok,
                stage: stage,
                skipped: object["skipped"] as? Bool ?? false,
                reason: (object["reason"] as? String).map { String($0.prefix(200)) }
            )
        }
        return nil
    }
}

// MARK: - Outcomes

public enum DesktopHermesInstallFailure: Error, Equatable, Sendable {
    /// The installer or one of its downloads could not reach the network (or the proxy refused it).
    case network(stage: String, detail: String)
    /// A stage reported failure for a reason that does not look like the network.
    case stageFailed(stage: String, detail: String)
    /// The download is not an installer, or its stage protocol is not one Desktop speaks.
    case protocolMismatch(detail: String)
    /// Desktop is running as root. Upstream's installer would pick a system-wide layout; never.
    case runningAsRoot
    /// The Mac no longer qualifies (Hermes appeared, or something unusual did) — before anything
    /// ran, or after the installer finished without leaving a usable standard install.
    case notUsable(stage: String, detail: String)
    case cancelled

    public var stage: String {
        switch self {
        case .network(let stage, _), .stageFailed(let stage, _), .notUsable(let stage, _): stage
        case .protocolMismatch: "manifest"
        case .runningAsRoot: "precondition"
        case .cancelled: "cancelled"
        }
    }
}

public enum DesktopHermesInstallProgress: Equatable, Sendable {
    case downloading
    case manifest([DesktopHermesInstallerStage])
    case stageStarted(DesktopHermesInstallerStage)
    case stageSucceeded(DesktopHermesInstallerStage)
    case stageSkipped(DesktopHermesInstallerStage)
    case verifying
}

/// What the install card shows: one row per manifest stage, folded from the driver's progress.
public struct DesktopHermesInstallRun: Equatable, Sendable {
    public enum StageState: Equatable, Sendable {
        case pending, running, succeeded, skipped, failed
    }

    public struct Row: Equatable, Sendable, Identifiable {
        public let stage: DesktopHermesInstallerStage
        public var state: StageState
        public var id: String { stage.name }
    }

    public private(set) var downloading = false
    public private(set) var verifying = false
    public private(set) var rows: [Row] = []

    public init() {}

    public var completedCount: Int { rows.filter { $0.state == .succeeded || $0.state == .skipped }.count }
    public var current: Row? { rows.first { $0.state == .running } }

    public mutating func apply(_ progress: DesktopHermesInstallProgress) {
        switch progress {
        case .downloading:
            downloading = true
        case .manifest(let stages):
            downloading = false
            rows = stages.map { Row(stage: $0, state: .pending) }
        case .stageStarted(let stage):
            set(stage.name, .running)
        case .stageSucceeded(let stage):
            set(stage.name, .succeeded)
        case .stageSkipped(let stage):
            set(stage.name, .skipped)
        case .verifying:
            verifying = true
        }
    }

    /// Marks whatever was running as failed (or the named stage, when it is known).
    public mutating func fail(stage name: String?) {
        downloading = false
        verifying = false
        if let index = rows.firstIndex(where: { $0.state == .running }) {
            rows[index].state = .failed
        } else if let name, let index = rows.firstIndex(where: { $0.stage.name == name }) {
            rows[index].state = .failed
        }
    }

    /// Stops the spinner on a cancelled run without claiming a failure.
    public mutating func stop() {
        downloading = false
        verifying = false
        for index in rows.indices where rows[index].state == .running { rows[index].state = .pending }
    }

    private mutating func set(_ name: String, _ state: StageState) {
        guard let index = rows.firstIndex(where: { $0.stage.name == name }) else { return }
        rows[index].state = state
    }
}

// MARK: - Offer and confirmation

/// What Desktop proposes to a Mac without Hermes, shown to the owner before anything runs.
public struct DesktopHermesInstallOffer: Equatable, Sendable {
    public let scriptURL: URL
    public let branch: String
    public let hermesHome: URL
    public let checkoutRoot: URL
    public let proxy: DesktopSystemProxy
    /// True when this retries Desktop's own earlier, unfinished attempt.
    public let resumesEarlierAttempt: Bool

    /// Only a Mac with no Hermes code, no Hermes data and no other install qualifies — or one whose
    /// only Hermes is the unfinished checkout Desktop's own earlier attempt left behind. Everything
    /// else (profiles, a custom `HERMES_HOME`, pipx, Homebrew, data without a checkout) stays
    /// "surface, don't touch": the offer is nil and `HR-MIGRATE-008` keeps saying why.
    public static func evaluate(
        detection: DesktopLocalHermesDetection,
        paths: DesktopLocalHermesPaths,
        localRuntimeEnabled: Bool,
        freshInstall: Bool,
        earlierAttemptRecorded: Bool,
        proxy: DesktopSystemProxy
    ) -> DesktopHermesInstallOffer? {
        guard localRuntimeEnabled, freshInstall else { return nil }
        let resumes: Bool
        switch detection {
        case .absent(hermesDataPresent: false):
            resumes = false
        case .unsupported(.incompleteInstallation, _), .unsupported(.unreadableIdentity, _):
            guard earlierAttemptRecorded else { return nil }
            resumes = true
        case .absent(hermesDataPresent: true), .unsupported, .usable:
            return nil
        }
        return DesktopHermesInstallOffer(
            scriptURL: DesktopHermesInstallerSource.scriptURL,
            branch: DesktopHermesInstallerSource.branch,
            hermesHome: paths.hermesHome,
            checkoutRoot: paths.checkoutRoot,
            proxy: proxy,
            resumesEarlierAttempt: resumes
        )
    }

    /// The owner's explicit "install". The installer accepts nothing else, so no code path can run
    /// it from an offer that was only shown.
    public func confirm() -> DesktopHermesInstallConfirmation {
        DesktopHermesInstallConfirmation(offer: self)
    }
}

public struct DesktopHermesInstallConfirmation: Equatable, Sendable {
    public let offer: DesktopHermesInstallOffer
    fileprivate init(offer: DesktopHermesInstallOffer) { self.offer = offer }
}

// MARK: - Seams

public protocol DesktopHermesInstallerScriptFetching: Sendable {
    func fetchInstallerScript(from url: URL) async throws -> Data
}

public struct DesktopHermesInstallerProcessResult: Equatable, Sendable {
    public let exitStatus: Int32
    /// The last lines of standard output (bounded), where the protocol's JSON lines are.
    public let standardOutputTail: [String]
    /// The last lines of both streams (bounded), for failure classification and diagnostics.
    public let combinedTail: [String]

    public init(exitStatus: Int32, standardOutputTail: [String], combinedTail: [String]) {
        self.exitStatus = exitStatus
        self.standardOutputTail = standardOutputTail
        self.combinedTail = combinedTail
    }
}

public protocol DesktopHermesInstallerProcessRunning: Sendable {
    /// Runs one process to completion, calling `onLine` for every output line (ANSI stripped).
    /// Task cancellation terminates the whole process group and throws `CancellationError`.
    func run(
        executable: URL,
        arguments: [String],
        environment: [String: String],
        workingDirectory: URL,
        onLine: @escaping @Sendable (String) -> Void
    ) async throws -> DesktopHermesInstallerProcessResult
}

// MARK: - Driver

public struct DesktopHermesInstaller: Sendable {
    public let paths: DesktopLocalHermesPaths
    /// Private per-run directory for the downloaded script and the `sudo` guard.
    public let workRoot: URL
    public let log: DesktopServiceOperationLog
    private let fetcher: any DesktopHermesInstallerScriptFetching
    private let runner: any DesktopHermesInstallerProcessRunning
    private let detect: @Sendable () -> DesktopLocalHermesDetection
    private let inheritedEnvironment: [String: String]
    private let userID: UInt32
    private let bash: URL

    public static let logFileName = "hermes-install.log"

    public init(
        paths: DesktopLocalHermesPaths,
        workRoot: URL,
        log: DesktopServiceOperationLog,
        fetcher: any DesktopHermesInstallerScriptFetching = DesktopHermesInstallerURLSessionFetcher(),
        runner: any DesktopHermesInstallerProcessRunning = DesktopPosixProcessRunner(),
        detect: (@Sendable () -> DesktopLocalHermesDetection)? = nil,
        inheritedEnvironment: [String: String] = ProcessInfo.processInfo.environment,
        userID: UInt32 = Darwin.getuid(),
        bash: URL = URL(fileURLWithPath: "/bin/bash")
    ) {
        self.paths = paths
        self.workRoot = workRoot
        self.log = log
        self.fetcher = fetcher
        self.runner = runner
        let detector = DesktopLocalHermesDetector(paths: paths, environment: inheritedEnvironment, currentUserID: userID)
        self.detect = detect ?? { detector.detect() }
        self.inheritedEnvironment = inheritedEnvironment
        self.userID = userID
        self.bash = bash
    }

    /// Downloads upstream's installer, runs every stage its manifest lists, and returns the usable
    /// installation detection then sees. Throws `DesktopHermesInstallFailure` for everything the
    /// owner should see; `.cancelled` when the task was cancelled.
    public func install(
        _ confirmation: DesktopHermesInstallConfirmation,
        progress: @escaping @Sendable (DesktopHermesInstallProgress) -> Void
    ) async throws -> DesktopLocalHermesInstallation {
        let offer = confirmation.offer
        guard userID != 0 else {
            log.record("hermes-install refused: running as root")
            throw DesktopHermesInstallFailure.runningAsRoot
        }
        // The offer was evaluated when it was shown; the Mac may have changed since. Re-check that
        // it still has no Hermes (or only Desktop's own unfinished one) before running anything.
        switch detect() {
        case .absent(hermesDataPresent: false):
            break
        case .unsupported(.incompleteInstallation, _) where offer.resumesEarlierAttempt,
             .unsupported(.unreadableIdentity, _) where offer.resumesEarlierAttempt:
            break
        case .usable(let installation):
            log.record("hermes-install skipped: a usable Hermes \(installation.version) is already present")
            return installation
        case let other:
            throw DesktopHermesInstallFailure.notUsable(stage: "precondition", detail: Self.describe(other))
        }

        try? FileManager.default.createDirectory(
            at: log.url.deletingLastPathComponent(),
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let runDirectory = try prepareRunDirectory()
        defer { try? FileManager.default.removeItem(at: runDirectory) }
        let environment = childEnvironment(offer: offer, runDirectory: runDirectory)
        log.record(
            "hermes-install start url=\(offer.scriptURL.absoluteString) branch=\(offer.branch) "
                + "home=\(offer.hermesHome.path) \(offer.proxy.summaryForLog)"
        )

        do {
            try Task.checkCancellation()
            progress(.downloading)
            let script = try await download(offer.scriptURL)
            let scriptURL = runDirectory.appendingPathComponent("install.sh")
            try writePrivate(script, to: scriptURL)
            log.record("hermes-install script bytes=\(script.count) sha256=\(Self.sha256(script))")

            let common = ["--dir", offer.checkoutRoot.path, "--hermes-home", offer.hermesHome.path, "--branch", offer.branch]
            let manifestRun = try await runner.run(
                executable: bash,
                arguments: [scriptURL.path, "--manifest"] + common,
                environment: environment,
                workingDirectory: runDirectory,
                onLine: { _ in }
            )
            guard manifestRun.exitStatus == 0 else {
                throw DesktopHermesInstallFailure.protocolMismatch(
                    detail: "--manifest exited \(manifestRun.exitStatus): \(Self.tail(manifestRun.combinedTail))"
                )
            }
            let manifest = try DesktopHermesInstallerManifest.parse(manifestRun.standardOutputTail.joined(separator: "\n"))
            log.record("hermes-install manifest protocol=\(manifest.protocolVersion) stages=\(manifest.stages.map(\.name).joined(separator: ","))")
            progress(.manifest(manifest.stages))

            for stage in manifest.stages {
                try Task.checkCancellation()
                progress(.stageStarted(stage))
                let log = self.log
                let name = stage.name
                let run = try await runner.run(
                    executable: bash,
                    arguments: [scriptURL.path, "--stage", stage.name, "--non-interactive", "--json"] + common,
                    environment: environment,
                    workingDirectory: runDirectory,
                    onLine: { line in log.record("stage=\(name) \(line)") }
                )
                guard let result = DesktopHermesInstallerStageResult.parse(run.standardOutputTail),
                      result.stage == stage.name
                else {
                    log.record("hermes-install stage=\(stage.name) no result frame exit=\(run.exitStatus)")
                    if Self.looksLikeNetworkFailure(run.combinedTail) {
                        throw DesktopHermesInstallFailure.network(stage: stage.name, detail: Self.tail(run.combinedTail))
                    }
                    throw DesktopHermesInstallFailure.protocolMismatch(
                        detail: "stage \(stage.name) produced no result frame (exit \(run.exitStatus))"
                    )
                }
                if result.ok {
                    log.record("hermes-install stage=\(stage.name) \(result.skipped ? "skipped" : "ok")")
                    progress(result.skipped ? .stageSkipped(stage) : .stageSucceeded(stage))
                    continue
                }
                let detail = "\(result.reason ?? "exit \(run.exitStatus)"): \(Self.tail(run.combinedTail))"
                log.record("hermes-install stage=\(stage.name) failed \(result.reason ?? "")")
                if Self.looksLikeNetworkFailure(run.combinedTail) {
                    throw DesktopHermesInstallFailure.network(stage: stage.name, detail: detail)
                }
                throw DesktopHermesInstallFailure.stageFailed(stage: stage.name, detail: detail)
            }

            progress(.verifying)
            let detection = detect()
            guard let installation = detection.installation, installation.dependenciesConsistent else {
                let detail = detection.installation.map { "dependencies: \($0.installedDistribution.summary)" }
                    ?? Self.describe(detection)
                log.record("hermes-install finished but not usable: \(detail)")
                throw DesktopHermesInstallFailure.notUsable(stage: "verify", detail: detail)
            }
            log.record("hermes-install done version=\(installation.version) commit=\(installation.shortCommit)")
            return installation
        } catch is CancellationError {
            log.record("hermes-install cancelled by the owner")
            throw DesktopHermesInstallFailure.cancelled
        } catch let failure as DesktopHermesInstallFailure {
            if failure != .cancelled { log.record("hermes-install failed \(failure)") }
            throw failure
        } catch {
            log.record("hermes-install failed to run the installer: \(error)")
            throw DesktopHermesInstallFailure.stageFailed(stage: "spawn", detail: String(describing: error))
        }
    }

    // MARK: Environment

    /// The installer's environment, built from an allowlist rather than inherited wholesale so that
    /// nothing from Desktop's own process (a `PYTHONPATH`, a `HERMES_*` override, a virtualenv)
    /// changes what gets installed, and so a test can state exactly what the child sees.
    public func childEnvironment(offer: DesktopHermesInstallOffer, runDirectory: URL) -> [String: String] {
        var environment: [String: String] = [:]
        for key in ["USER", "LOGNAME", "SHELL", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "SSH_AUTH_SOCK"] {
            if let value = inheritedEnvironment[key], !value.isEmpty { environment[key] = value }
        }
        environment["HOME"] = paths.homeDirectory.path
        environment["HERMES_HOME"] = offer.hermesHome.path
        if environment["LANG"] == nil { environment["LANG"] = "en_US.UTF-8" }
        // The guard directory comes first, so `sudo` resolves to a script that refuses.
        environment["PATH"] = ([runDirectory.appendingPathComponent("bin").path]
            + ["/usr/bin", "/bin", "/usr/sbin", "/sbin", "/opt/homebrew/bin", "/usr/local/bin"]
            + [paths.homeDirectory.appendingPathComponent(".local/bin").path])
            .joined(separator: ":")
        environment["TERM"] = "dumb"
        environment["NO_COLOR"] = "1"
        // Homebrew and git must never wait for an answer nobody can give.
        environment["NONINTERACTIVE"] = "1"
        environment["GIT_TERMINAL_PROMPT"] = "0"
        environment["SUDO_ASKPASS"] = "/usr/bin/false"
        for (key, value) in offer.proxy.environment(inheriting: inheritedEnvironment) {
            environment[key] = value
        }
        return environment
    }

    // MARK: Helpers

    private func prepareRunDirectory() throws -> URL {
        let manager = FileManager.default
        do {
            try manager.createDirectory(at: workRoot, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try Self.requirePrivateDirectory(workRoot, userID: userID)
            let run = workRoot.appendingPathComponent("hermes-install-\(UUID().uuidString.lowercased())", isDirectory: true)
            try manager.createDirectory(at: run, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            let bin = run.appendingPathComponent("bin", isDirectory: true)
            try manager.createDirectory(at: bin, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            let guardScript = "#!/bin/sh\necho 'Hermes GO does not allow the Hermes installer to use sudo.' >&2\nexit 1\n"
            let sudo = bin.appendingPathComponent("sudo")
            try writePrivate(Data(guardScript.utf8), to: sudo)
            try manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: sudo.path)
            return run
        } catch let failure as DesktopHermesInstallFailure {
            throw failure
        } catch {
            throw DesktopHermesInstallFailure.stageFailed(stage: "workspace", detail: String(describing: error))
        }
    }

    private static func requirePrivateDirectory(_ url: URL, userID: UInt32) throws {
        var status = stat()
        guard lstat(url.path, &status) == 0,
              status.st_mode & S_IFMT == S_IFDIR,
              status.st_uid == userID
        else { throw DesktopHermesInstallFailure.stageFailed(stage: "workspace", detail: "work directory is not a private directory") }
        if status.st_mode & 0o077 != 0 { _ = chmod(url.path, 0o700) }
    }

    private func writePrivate(_ data: Data, to url: URL) throws {
        let descriptor = open(url.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard descriptor >= 0 else {
            throw DesktopHermesInstallFailure.stageFailed(stage: "workspace", detail: "cannot create \(url.lastPathComponent)")
        }
        defer { close(descriptor) }
        let written = data.withUnsafeBytes { buffer in
            buffer.baseAddress.map { write(descriptor, $0, buffer.count) } ?? 0
        }
        guard written == data.count else {
            throw DesktopHermesInstallFailure.stageFailed(stage: "workspace", detail: "short write of \(url.lastPathComponent)")
        }
    }

    private func download(_ url: URL) async throws -> Data {
        let data = try await fetcher.fetchInstallerScript(from: url)
        guard data.count > 2, data.count <= DesktopHermesInstallerSource.maximumScriptBytes,
              data.starts(with: Data("#!".utf8)),
              String(data: data, encoding: .utf8) != nil
        else {
            throw DesktopHermesInstallFailure.protocolMismatch(detail: "download is not an installer script (\(data.count) bytes)")
        }
        return data
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func tail(_ lines: [String]) -> String {
        lines.suffix(8).joined(separator: " | ")
    }

    static func describe(_ detection: DesktopLocalHermesDetection) -> String {
        switch detection {
        case .absent(let data): "no Hermes found (hermesDataPresent=\(data))"
        case .usable(let installation): "usable \(installation.version)"
        case .unsupported(let reason, let detail): "reason=\(reason.rawValue) \(detail)"
        }
    }

    /// Output that means "could not reach the network", from the tools the installer drives:
    /// curl, git, uv/reqwest, pip, npm and the installer's own connectivity probe. Matched
    /// case-insensitively against the failed stage's recent output.
    static let networkSignatures = [
        "could not resolve host", "could not resolve proxy", "failed to connect to", "connection timed out",
        "operation timed out", "connection refused", "connection reset", "network is unreachable",
        "unable to access 'http", "ssl_error", "ssl connect error", "tls handshake", "error sending request",
        "dns error", "failed to lookup address", "tcp connect error", "name or service not known",
        "nodename nor servname", "temporary failure in name resolution", "received http code 407",
        "proxy connect aborted", "proxy authentication required", "could not reach https://",
        "curl: (6)", "curl: (7)", "curl: (28)", "curl: (35)", "curl: (56)", "rpc failed", "early eof",
        "etimedout", "econnreset", "econnrefused", "enotfound", "eai_again", "request timed out",
        "failed to download", "failed to fetch",
    ]

    static func looksLikeNetworkFailure(_ lines: [String]) -> Bool {
        lines.contains { line in
            let lowered = line.lowercased()
            return networkSignatures.contains { lowered.contains($0) }
        }
    }
}

// MARK: - Presentation

public extension DesktopIssue {
    /// The registered issue for a failed install. nil for a cancellation, which is the owner's own
    /// choice and is shown as a neutral state, not an error.
    static func hermesInstall(_ failure: DesktopHermesInstallFailure) -> DesktopIssue? {
        switch failure {
        case .cancelled:
            return nil
        case .network(let stage, let detail):
            return DesktopIssue(code: .hermesInstallNetworkFailed, technicalCause: "stage=\(stage) \(detail)")
        case .stageFailed(let stage, let detail):
            return DesktopIssue(code: .hermesInstallStageFailed, technicalCause: "stage=\(stage) \(detail)")
        case .protocolMismatch(let detail):
            return DesktopIssue(code: .hermesInstallerUnsupported, technicalCause: "stage=manifest \(detail)")
        case .runningAsRoot:
            return DesktopIssue(code: .hermesInstallerUnsupported, technicalCause: "stage=precondition Desktop is running as root")
        case .notUsable(let stage, let detail):
            return DesktopIssue(code: .hermesInstallNotUsable, technicalCause: "stage=\(stage) \(detail)")
        }
    }
}

// MARK: - Real seams

/// Fetches the installer with `URLSession`, which applies the system proxy (manual or PAC) itself.
public struct DesktopHermesInstallerURLSessionFetcher: DesktopHermesInstallerScriptFetching {
    public init() {}

    public func fetchInstallerScript(from url: URL) async throws -> Data {
        guard url.scheme == "https" else {
            throw DesktopHermesInstallFailure.protocolMismatch(detail: "installer URL is not HTTPS")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 120
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration)
        defer { session.finishTasksAndInvalidate() }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(from: url)
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as URLError {
            throw DesktopHermesInstallFailure.network(stage: "download", detail: "URLError \(error.code.rawValue)")
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw DesktopHermesInstallFailure.network(stage: "download", detail: String(describing: error))
        }
        guard let http = response as? HTTPURLResponse,
              let final = http.url,
              DesktopHermesInstallerSource.isOfficial(final)
        else {
            throw DesktopHermesInstallFailure.protocolMismatch(detail: "installer download left the official origin")
        }
        guard http.statusCode == 200 else {
            throw DesktopHermesInstallFailure.network(stage: "download", detail: "HTTP \(http.statusCode)")
        }
        return data
    }
}

/// Spawns a process in its own process group, streams its output line by line, and on task
/// cancellation terminates the whole group (TERM, then KILL after five seconds) — the installer's
/// `git`, `uv` and `curl` children included. Standard input is `/dev/null`; only the three standard
/// descriptors are inherited.
public struct DesktopPosixProcessRunner: DesktopHermesInstallerProcessRunning {
    public static let maximumLineBytes = 4096
    public static let tailLines = 200

    public init() {}

    public func run(
        executable: URL,
        arguments: [String],
        environment: [String: String],
        workingDirectory: URL,
        onLine: @escaping @Sendable (String) -> Void
    ) async throws -> DesktopHermesInstallerProcessResult {
        let control = ProcessControl()
        let result = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<DesktopHermesInstallerProcessResult, Error>) in
                DispatchQueue.global(qos: .utility).async {
                    do {
                        continuation.resume(returning: try Self.spawnAndWait(
                            executable: executable,
                            arguments: arguments,
                            environment: environment,
                            workingDirectory: workingDirectory,
                            control: control,
                            onLine: onLine
                        ))
                    } catch {
                        continuation.resume(throwing: error)
                    }
                }
            }
        } onCancel: {
            control.cancel()
        }
        if control.isCancelled { throw CancellationError() }
        return result
    }

    final class ProcessControl: @unchecked Sendable {
        private let lock = NSLock()
        private var pid: pid_t = 0
        private var cancelledAt: Date?

        var isCancelled: Bool { lock.withLock { cancelledAt != nil } }
        var cancellationTime: Date? { lock.withLock { cancelledAt } }

        func cancel() {
            let target: pid_t = lock.withLock {
                if cancelledAt == nil { cancelledAt = Date() }
                return pid
            }
            if target > 0 { _ = kill(-target, SIGTERM) }
        }

        func started(_ value: pid_t) {
            let cancelled: Bool = lock.withLock {
                pid = value
                return cancelledAt != nil
            }
            if cancelled { _ = kill(-value, SIGTERM) }
        }
    }

    static func spawnAndWait(
        executable: URL,
        arguments: [String],
        environment: [String: String],
        workingDirectory: URL,
        control: ProcessControl,
        onLine: @escaping @Sendable (String) -> Void
    ) throws -> DesktopHermesInstallerProcessResult {
        var outPipe: [Int32] = [0, 0]
        var errPipe: [Int32] = [0, 0]
        guard pipe(&outPipe) == 0 else { throw POSIXError(.EMFILE) }
        guard pipe(&errPipe) == 0 else {
            close(outPipe[0]); close(outPipe[1])
            throw POSIXError(.EMFILE)
        }

        var actions: posix_spawn_file_actions_t?
        posix_spawn_file_actions_init(&actions)
        defer { posix_spawn_file_actions_destroy(&actions) }
        posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0)
        posix_spawn_file_actions_adddup2(&actions, outPipe[1], 1)
        posix_spawn_file_actions_adddup2(&actions, errPipe[1], 2)
        posix_spawn_file_actions_addchdir_np(&actions, workingDirectory.path)

        var attributes: posix_spawnattr_t?
        posix_spawnattr_init(&attributes)
        defer { posix_spawnattr_destroy(&attributes) }
        // Own process group, so cancellation reaches every descendant; close everything else.
        // Reset the signal mask and dispositions: this runs on a dispatch worker thread, whose
        // blocked signals a spawned child would otherwise inherit — with SIGTERM blocked,
        // cancellation would only ever take effect through the SIGKILL fallback.
        var emptyMask = sigset_t()
        sigemptyset(&emptyMask)
        var defaultSignals = sigset_t()
        sigfillset(&defaultSignals)
        sigdelset(&defaultSignals, SIGKILL)
        sigdelset(&defaultSignals, SIGSTOP)
        posix_spawnattr_setsigmask(&attributes, &emptyMask)
        posix_spawnattr_setsigdefault(&attributes, &defaultSignals)
        posix_spawnattr_setflags(
            &attributes,
            Int16(POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_CLOEXEC_DEFAULT | POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF)
        )
        posix_spawnattr_setpgroup(&attributes, 0)

        let argv = ([executable.path] + arguments).map { strdup($0) } + [nil]
        let envp = environment.sorted { $0.key < $1.key }.map { strdup("\($0.key)=\($0.value)") } + [nil]
        defer {
            argv.forEach { free($0) }
            envp.forEach { free($0) }
        }

        var pid: pid_t = 0
        let status = posix_spawn(&pid, executable.path, &actions, &attributes, argv, envp)
        close(outPipe[1])
        close(errPipe[1])
        guard status == 0 else {
            close(outPipe[0]); close(errPipe[0])
            throw POSIXError(POSIXErrorCode(rawValue: status) ?? .EIO)
        }
        control.started(pid)

        var readers = [LineReader(fd: outPipe[0], isStandardOutput: true), LineReader(fd: errPipe[0], isStandardOutput: false)]
        var stdoutTail: [String] = []
        var combinedTail: [String] = []
        func emit(_ line: String, standardOutput: Bool) {
            if standardOutput { append(&stdoutTail, line) }
            append(&combinedTail, line)
            onLine(line)
        }

        var exitStatus: Int32?
        var exitedAt: Date?
        var killed = false
        while true {
            var fds = readers.filter { !$0.closed }.map { pollfd(fd: $0.fd, events: Int16(POLLIN), revents: 0) }
            if !fds.isEmpty { _ = poll(&fds, nfds_t(fds.count), 200) } else { usleep(100_000) }
            for index in readers.indices where !readers[index].closed {
                for line in readers[index].drain() { emit(line, standardOutput: readers[index].isStandardOutput) }
            }
            if exitStatus == nil {
                var raw: Int32 = 0
                if waitpid(pid, &raw, WNOHANG) == pid {
                    exitStatus = Self.exitCode(raw)
                    exitedAt = Date()
                }
            }
            if let cancelled = control.cancellationTime, !killed, Date().timeIntervalSince(cancelled) > 5 {
                _ = kill(-pid, SIGKILL)
                killed = true
            }
            let drained = readers.allSatisfy(\.closed)
            if exitStatus != nil, drained { break }
            // A descendant that outlives the installer may keep the pipe open; do not wait on it.
            if let exitedAt, Date().timeIntervalSince(exitedAt) > 5 { break }
        }
        for index in readers.indices {
            for line in readers[index].finish() { emit(line, standardOutput: readers[index].isStandardOutput) }
            if !readers[index].closed { close(readers[index].fd) }
        }
        return DesktopHermesInstallerProcessResult(
            exitStatus: exitStatus ?? -1,
            standardOutputTail: stdoutTail,
            combinedTail: combinedTail
        )
    }

    private static func append(_ tail: inout [String], _ line: String) {
        tail.append(line)
        if tail.count > tailLines { tail.removeFirst(tail.count - tailLines) }
    }

    private static func exitCode(_ raw: Int32) -> Int32 {
        let signal = raw & 0x7f
        return signal == 0 ? (raw >> 8) & 0xff : 128 + signal
    }

    struct LineReader {
        let fd: Int32
        let isStandardOutput: Bool
        var closed = false
        var buffer = Data()

        init(fd: Int32, isStandardOutput: Bool) {
            self.fd = fd
            self.isStandardOutput = isStandardOutput
            _ = fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK)
        }

        mutating func drain() -> [String] {
            var chunk = [UInt8](repeating: 0, count: 16 * 1024)
            while true {
                let count = read(fd, &chunk, chunk.count)
                if count > 0 {
                    buffer.append(contentsOf: chunk[0..<count])
                    continue
                }
                if count == 0 {
                    close(fd)
                    closed = true
                }
                break
            }
            return lines(final: false)
        }

        mutating func finish() -> [String] { lines(final: true) }

        private mutating func lines(final: Bool) -> [String] {
            var result: [String] = []
            while let newline = buffer.firstIndex(of: 0x0A) {
                result.append(Self.clean(buffer[buffer.startIndex..<newline]))
                buffer.removeSubrange(buffer.startIndex...newline)
            }
            if buffer.count > DesktopPosixProcessRunner.maximumLineBytes || (final && !buffer.isEmpty) {
                result.append(Self.clean(buffer[...]))
                buffer.removeAll()
            }
            return result.filter { !$0.isEmpty }
        }

        /// Strips ANSI/OSC escapes and keeps the last carriage-return frame — what a terminal would
        /// finally show for a progress bar redrawn in place.
        static func clean(_ bytes: Data.SubSequence) -> String {
            let text = String(decoding: bytes.prefix(DesktopPosixProcessRunner.maximumLineBytes), as: UTF8.self)
            let lastFrame = text.split(separator: "\r", omittingEmptySubsequences: true).last.map(String.init) ?? ""
            let stripped = lastFrame.replacingOccurrences(
                of: "\u{1B}(\\[[0-9;?]*[ -/]*[@-~]|\\][^\u{07}\u{1B}]*(\u{07}|\u{1B}\\\\)|[@-Z\\\\-_])",
                with: "",
                options: .regularExpression
            )
            return stripped.trimmingCharacters(in: .whitespaces)
        }
    }
}

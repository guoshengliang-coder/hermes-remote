import Darwin
import Foundation

/// Which Hermes code the `com.hermesgo.hermes-server` LaunchAgent runs.
///
/// The label, the loopback port, the private session token and the log paths are the same in both
/// modes — Desktop owns the process either way. Only the program changes: Hermes GO's bundled copy,
/// or the Mac's own standard install (`docs/MANAGED_HERMES_STRATEGY.md`, decision of 2026-09-21:
/// one copy of Hermes code per Mac).
public enum DesktopHermesRuntimeMode: Equatable, Sendable {
    /// No Hermes LaunchAgent on disk.
    case absent
    /// The bundled `hermes_server` release or a component-store `hermes_core`.
    case bundled
    /// This Mac's own Hermes, started through Desktop's launcher.
    case localHermes(executable: URL)
    /// A LaunchAgent under our label that Desktop did not write in either shape.
    case unrecognised

    public var isLocal: Bool {
        if case .localHermes = self { return true }
        return false
    }
}

/// The small script that sits between launchd and the owner's `hermes serve`.
///
/// It exists for one reason: the bundled `hermes-server` launcher turns `HERMES_SESSION_TOKEN_FILE`
/// into `HERMES_DASHBOARD_SESSION_TOKEN`, and upstream's own `hermes` entrypoint does not. Putting
/// the token into the plist instead would undo the token-file contract (the value would sit in a
/// LaunchAgent again). So Desktop writes a launcher that reads the same private file with the same
/// checks and then `exec`s the owner's entrypoint — the PID launchd supervises is Hermes itself.
///
/// It refuses anything but the exact argument vector Desktop wrote, so the file cannot be used as a
/// general "run this with the token" helper.
public enum DesktopLocalHermesLauncher {
    public static let exitUsage: Int32 = 64
    public static let exitConfiguration: Int32 = 78

    public static func script(executable: URL) throws -> Data {
        let path = executable.standardizedFileURL.path
        guard DesktopLocalHermesDetector.isScriptSafePath(path) else {
            throw DesktopLaunchAgentError.invalidConfiguration
        }
        let contract = DesktopHermesRuntimeContract.serveV1
        let expected = [path] + contract.programArguments
        let checks = expected.enumerated().map { index, value in
            "[ \"${\(index + 1)}\" = '\(value)' ]"
        }.joined(separator: " && ")
        let text = """
        #!/bin/sh
        # Written by Hermes GO Desktop. Starts this Mac's own Hermes as the private loopback backend
        # for the Hermes GO Connector. Rewritten whenever Desktop switches runtime; do not edit.
        set -eu
        if ! { [ "$#" -eq \(expected.count) ] && \(checks); }; then
          echo "hermes-local-serve: unexpected arguments" >&2
          exit \(exitUsage)
        fi
        token_file="${HERMES_SESSION_TOKEN_FILE:-}"
        if [ -z "$token_file" ] || [ ! -f "$token_file" ] || [ -L "$token_file" ] || [ ! -O "$token_file" ]; then
          echo "hermes-local-serve: the session token file is missing or not a private regular file" >&2
          exit \(exitConfiguration)
        fi
        case "$(/usr/bin/stat -f %Lp "$token_file")" in
          600|400) ;;
          *) echo "hermes-local-serve: the session token file is readable by others" >&2; exit \(exitConfiguration) ;;
        esac
        token=''
        IFS= read -r token < "$token_file" || true
        case "$token" in
          ''|*[!A-Za-z0-9_-]*) echo "hermes-local-serve: the session token is malformed" >&2; exit \(exitConfiguration) ;;
        esac
        case "${#token}" in
          43|64) ;;
          *) echo "hermes-local-serve: the session token is malformed" >&2; exit \(exitConfiguration) ;;
        esac
        HERMES_DASHBOARD_SESSION_TOKEN="$token"
        export HERMES_DASHBOARD_SESSION_TOKEN
        unset token PYTHONPATH PYTHONHOME
        exec "$@"

        """
        return Data(text.utf8)
    }
}

/// The LaunchAgent that runs the owner's Hermes in place of the bundled copy.
///
/// `ProgramArguments` is `[launcher, <checkout>/venv/bin/hermes, serve, --host, 127.0.0.1, --port,
/// 9119]`. Keeping the real entrypoint visible as `argv[1]` is deliberate: upstream's `hermes update`
/// (`_kill_stale_dashboard_processes` → `_loaded_launchd_backend_jobs`) recognises a launchd job as a
/// backend when `shlex.join(ProgramArguments)` contains `hermes serve`, and then restarts it with
/// `launchctl kickstart` after updating instead of respawning a detached copy on our port. The
/// launcher `exec`s, so the PID launchd reports is the Hermes process upstream scans for.
public struct DesktopLocalHermesLaunchAgent: Sendable {
    public let launcher: URL
    public let installation: DesktopLocalHermesInstallation
    public let sessionTokenFile: URL
    public let standardOutput: URL
    public let standardError: URL
    public let runtimeContract: DesktopHermesRuntimeContract

    public init(
        launcher: URL,
        installation: DesktopLocalHermesInstallation,
        sessionTokenFile: URL,
        standardOutput: URL,
        standardError: URL,
        runtimeContract: DesktopHermesRuntimeContract = .serveV1
    ) {
        self.launcher = launcher
        self.installation = installation
        self.sessionTokenFile = sessionTokenFile
        self.standardOutput = standardOutput
        self.standardError = standardError
        self.runtimeContract = runtimeContract
    }

    public var programArguments: [String] {
        [launcher.standardizedFileURL.path, installation.executable.standardizedFileURL.path]
            + runtimeContract.programArguments
    }

    /// `HERMES_DESKTOP=1` is kept on purpose; see `docs/DESKTOP_PHASE0.md`, "Local Hermes runtime".
    /// In short: it is what exempts this loopback backend from the ticket-only `/api/ws` gate when the
    /// owner has set a non-loopback `dashboard.public_url`, and from 0.21.3 the cron ticker it starts
    /// stands down on every tick while the owner's own gateway is running.
    public func environmentVariables() throws -> [String: String] {
        var environment = try runtimeContract.environmentVariables(
            hermesHome: installation.hermesHome,
            sessionTokenFile: sessionTokenFile
        )
        let venvBin = installation.executable.deletingLastPathComponent().standardizedFileURL.path
        environment["PATH"] = venvBin + ":" + DesktopHermesRuntimeContract.searchPath
        environment["VIRTUAL_ENV"] = installation.executable
            .deletingLastPathComponent().deletingLastPathComponent().standardizedFileURL.path
        return environment
    }

    public func encodedPropertyList() throws -> Data {
        guard [launcher, installation.executable, standardOutput, standardError].allSatisfy({
            $0.isFileURL && $0.path.hasPrefix("/") && $0.path != "/"
                && !$0.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        }), DesktopLocalHermesDetector.isScriptSafePath(installation.executable.standardizedFileURL.path)
        else { throw DesktopLaunchAgentError.invalidConfiguration }
        let object: [String: Any] = [
            "Label": DesktopManagedInstallLayout.hermesLabel,
            "ProgramArguments": programArguments,
            "RunAtLoad": true,
            "ProcessType": "Background",
            "ThrottleInterval": 30,
            "StandardOutPath": standardOutput.standardizedFileURL.path,
            "StandardErrorPath": standardError.standardizedFileURL.path,
            "EnvironmentVariables": try environmentVariables(),
        ]
        do {
            return try PropertyListSerialization.data(fromPropertyList: object, format: .xml, options: 0)
        } catch { throw DesktopLaunchAgentError.encodingFailed }
    }
}

/// What Desktop wrote down the last time it started the owner's Hermes itself.
public struct DesktopLocalHermesRuntimeRecord: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let executable: String
    public let commit: String
    public let version: String
    /// Taken immediately before `launchctl bootstrap`, so a process that started at or after it is
    /// the one Desktop launched (or a later one).
    public let launchedAt: Date

    public init(executable: String, commit: String, version: String, launchedAt: Date) {
        schemaVersion = 1
        self.executable = executable
        self.commit = commit
        self.version = version
        self.launchedAt = launchedAt
    }
}

// MARK: - Planning

/// Whether this Mac may run its own Hermes in place of the bundled copy.
///
/// Off unless turned on. Switching a Mac is a production change (it restarts the Hermes the phone is
/// talking to, and puts the phone on whatever upstream version the owner runs), so it is an explicit
/// per-Mac decision rather than a side effect of installing a newer Desktop:
///
///     defaults write com.hermesgo.desktop HermesGoLocalHermesRuntimeEnabled -bool true
///
/// Turning it off again on a Mac in local mode is the rollback: the next refresh restores the
/// bundled agent Desktop kept when it switched. Precedence: environment, then user defaults, then
/// the app's `Info.plist`, so a packaged build can ship it on later without code changes.
public enum DesktopLocalHermesRuntimeSetting {
    public static let environmentKey = "HERMES_GO_LOCAL_HERMES_RUNTIME_ENABLED"
    public static let defaultsKey = "HermesGoLocalHermesRuntimeEnabled"

    public static func isEnabled(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        defaults: UserDefaults = .standard,
        bundle: Bundle = .main
    ) -> Bool {
        if let value = environment[environmentKey] { return value == "1" }
        if defaults.object(forKey: defaultsKey) != nil { return defaults.bool(forKey: defaultsKey) }
        if let number = bundle.object(forInfoDictionaryKey: defaultsKey) as? NSNumber { return number.boolValue }
        return false
    }
}

public struct DesktopHermesRuntimeObservation: Equatable, Sendable {
    public let enabled: Bool
    public let detection: DesktopLocalHermesDetection
    public let mode: DesktopHermesRuntimeMode
    public let updateInProgress: Bool
    public let service: DesktopHermesServiceProcess
    public let record: DesktopLocalHermesRuntimeRecord?
    public let bundledFallbackAvailable: Bool
    public let now: Date

    public init(
        enabled: Bool = true,
        detection: DesktopLocalHermesDetection,
        mode: DesktopHermesRuntimeMode,
        updateInProgress: Bool,
        service: DesktopHermesServiceProcess,
        record: DesktopLocalHermesRuntimeRecord?,
        bundledFallbackAvailable: Bool,
        now: Date
    ) {
        self.enabled = enabled
        self.detection = detection
        self.mode = mode
        self.updateInProgress = updateInProgress
        self.service = service
        self.record = record
        self.bundledFallbackAvailable = bundledFallbackAvailable
        self.now = now
    }
}

public enum DesktopHermesRuntimeRestartReason: String, Equatable, Sendable {
    /// The checkout moved after the running process started, so it is serving code that is no
    /// longer on disk.
    case codeChanged
    /// The job is loaded but no process is running (for example `hermes update` stopped it and
    /// its own `launchctl kickstart` did not bring it back).
    case notRunning
}

public enum DesktopHermesRuntimeWaitReason: String, Equatable, Sendable {
    case updateInProgress
    case settling
}

public enum DesktopHermesRuntimePlan: Equatable, Sendable {
    case keep
    case wait(DesktopHermesRuntimeWaitReason)
    case switchToLocal(DesktopLocalHermesInstallation)
    case restartLocal(DesktopLocalHermesInstallation, DesktopHermesRuntimeRestartReason)
    case restoreBundled
    /// This Mac has a Hermes Desktop may not use (or is left without one); nothing is changed, and
    /// the reason is shown instead.
    case surface(DesktopHermesRuntimeAttention)

    public var mutates: Bool {
        switch self {
        case .switchToLocal, .restartLocal, .restoreBundled: true
        case .keep, .wait, .surface: false
        }
    }
}

public enum DesktopHermesRuntimeAttention: Equatable, Sendable {
    case unsupported(DesktopLocalHermesUnsupportedReason, detail: String)
    /// Local mode is configured but the owner's Hermes is gone and there is no bundled agent to
    /// return to.
    case localHermesMissingWithoutFallback
}

public enum DesktopHermesRuntimePlanner {
    /// How long the checkout must stay unchanged before Desktop restarts onto it. `hermes update`
    /// holds its marker for the whole run; this covers a bare `git pull`, where the code moves
    /// before the owner has had a chance to reinstall dependencies.
    public static let settleInterval: TimeInterval = 60

    public static func plan(_ observation: DesktopHermesRuntimeObservation) -> DesktopHermesRuntimePlan {
        switch observation.mode {
        case .absent, .unrecognised:
            // Nothing Desktop wrote to reconcile. An unrecognised agent is someone else's file and
            // the token/PATH reconcilers already report it.
            return .keep
        case .bundled, .localHermes:
            break
        }
        guard observation.enabled else {
            // Turned off: a Mac in local mode goes back to the agent it had before the switch.
            guard observation.mode.isLocal else { return .keep }
            if observation.updateInProgress { return .wait(.updateInProgress) }
            return observation.bundledFallbackAvailable
                ? .restoreBundled
                : .surface(.localHermesMissingWithoutFallback)
        }
        switch observation.detection {
        case .unsupported(let reason, let detail):
            return .surface(.unsupported(reason, detail: detail))
        case .absent:
            guard observation.mode.isLocal else { return .keep }
            if observation.updateInProgress { return .wait(.updateInProgress) }
            return observation.bundledFallbackAvailable
                ? .restoreBundled
                : .surface(.localHermesMissingWithoutFallback)
        case .usable(let installation):
            if observation.updateInProgress { return .wait(.updateInProgress) }
            if observation.now.timeIntervalSince(installation.identityChangedAt) < settleInterval {
                return .wait(.settling)
            }
            guard case .localHermes(let executable) = observation.mode,
                  executable.standardizedFileURL.path == installation.executable.standardizedFileURL.path
            else { return .switchToLocal(installation) }
            switch observation.service {
            case .unknown:
                // Never restart on a reading that failed: a broken probe must not turn into a
                // restart every refresh.
                return .keep
            case .stopped:
                return .restartLocal(installation, .notRunning)
            case .running(let started):
                return isFresh(started: started, installation: installation, record: observation.record)
                    ? .keep
                    : .restartLocal(installation, .codeChanged)
            }
        }
    }

    /// A running local Hermes is serving the code on disk when either
    ///  - it started after the checkout last moved (this covers `hermes update`, which restarts our
    ///    job itself through `launchctl kickstart`), or
    ///  - Desktop launched it with exactly this commit — which keeps a git housekeeping rewrite of
    ///    `packed-refs`, which moves the timestamp but not the commit, from costing a restart.
    static func isFresh(
        started: Date,
        installation: DesktopLocalHermesInstallation,
        record: DesktopLocalHermesRuntimeRecord?
    ) -> Bool {
        if started >= installation.identityChangedAt { return true }
        guard let record else { return false }
        return record.commit == installation.commit
            && record.executable == installation.executable.standardizedFileURL.path
            && started >= record.launchedAt
    }
}

// MARK: - Process start time

/// What launchd reports for the managed Hermes label.
public enum DesktopHermesServiceProcess: Equatable, Sendable {
    case running(startedAt: Date)
    /// launchd answered and the job has no process.
    case stopped
    /// launchd could not be asked, or its answer could not be read.
    case unknown
}

public protocol DesktopHermesServiceProcessInspecting: Sendable {
    func hermesServiceProcess() -> DesktopHermesServiceProcess
}

/// Reads the PID from `launchctl print` and its start time from the kernel. Read-only.
public struct DesktopLaunchdHermesServiceProcessInspector<Runner: OutputCommandRunning & Sendable>:
    DesktopHermesServiceProcessInspecting {
    private let runner: Runner
    private let userID: UInt32

    public init(runner: Runner, userID: UInt32 = Darwin.getuid()) {
        self.runner = runner
        self.userID = userID
    }

    public func hermesServiceProcess() -> DesktopHermesServiceProcess {
        let output = runner.run(
            executable: URL(fileURLWithPath: "/bin/launchctl"),
            arguments: ["print", "gui/\(userID)/\(DesktopManagedInstallLayout.hermesLabel)"],
            maximumOutputBytes: 256 * 1024
        )
        guard output.status == 0, !output.outputLimitExceeded,
              let text = String(data: output.stdout, encoding: .utf8)
        else { return .unknown }
        return Self.process(fromLaunchctlPrint: text, startDate: Self.startDate(pid:))
    }

    static func process(
        fromLaunchctlPrint text: String,
        startDate: (pid_t) -> Date?
    ) -> DesktopHermesServiceProcess {
        if let pid = pid(fromLaunchctlPrint: text) {
            return startDate(pid).map { .running(startedAt: $0) } ?? .unknown
        }
        let stopped = text.split(separator: "\n").contains {
            $0.trimmingCharacters(in: .whitespaces) == "state = not running"
        }
        return stopped ? .stopped : .unknown
    }

    static func pid(fromLaunchctlPrint text: String) -> pid_t? {
        for line in text.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("pid = ") else { continue }
            return pid_t(trimmed.dropFirst("pid = ".count))
        }
        return nil
    }

    static func startDate(pid: pid_t) -> Date? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, pid]
        guard sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0, size > 0,
              info.kp_proc.p_pid == pid
        else { return nil }
        let start = info.kp_proc.p_starttime
        return Date(timeIntervalSince1970: TimeInterval(start.tv_sec) + TimeInterval(start.tv_usec) / 1_000_000)
    }
}


// MARK: - Presentation

public extension DesktopIssue {
    /// What a runtime reconciliation that did not fail should show. nil means nothing to say.
    static func hermesRuntime(_ reconciliation: DesktopHermesRuntimeReconciliation) -> DesktopIssue? {
        guard case .unchanged(.surface(let attention)) = reconciliation else { return nil }
        switch attention {
        case .unsupported(let reason, let detail):
            return DesktopIssue(code: .localHermesUnsupported, technicalCause: "reason=\(reason.rawValue) \(detail)")
        case .localHermesMissingWithoutFallback:
            return DesktopIssue(
                code: .localHermesRuntimeFailed,
                technicalCause: "stage=restore-bundled this Mac's Hermes is gone and no bundled agent is stored"
            )
        }
    }

    static func hermesRuntimeFailure(_ error: Error) -> DesktopIssue {
        DesktopIssue(code: .localHermesRuntimeFailed, technicalCause: "stage=runtime \(String(describing: error))")
    }

    /// Whether a fresh managed install must stop because this Mac already has Hermes.
    ///
    /// Only a *fresh* install is gated — a Mac that already runs a managed Hermes is upgraded in
    /// whatever runtime mode it is in. Hermes data without the standard checkout also stops a fresh
    /// install: some other Hermes wrote that database, and a bundled copy beside it is exactly the
    /// two-codebases-one-database arrangement the 2026-09-21 decision rules out.
    static func localHermesInstallBlock(
        _ detection: DesktopLocalHermesDetection,
        freshInstall: Bool,
        localRuntimeEnabled: Bool
    ) -> DesktopIssue? {
        guard freshInstall, localRuntimeEnabled else { return nil }
        switch detection {
        case .unsupported(let reason, let detail):
            return DesktopIssue(code: .localHermesUnsupported, technicalCause: "reason=\(reason.rawValue) \(detail)")
        case .absent(hermesDataPresent: true):
            return DesktopIssue(
                code: .localHermesUnsupported,
                technicalCause: "reason=\(DesktopLocalHermesUnsupportedReason.nonStandardLocation.rawValue) "
                    + "~/.hermes/state.db exists but the standard checkout does not"
            )
        case .absent, .usable:
            return nil
        }
    }
}

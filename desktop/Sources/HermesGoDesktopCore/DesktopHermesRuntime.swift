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

/// Which commit the running local Hermes process loaded, as far as Desktop knows.
///
/// Written when Desktop starts the owner's Hermes itself, and *adopted* when a process it did not
/// start (typically `hermes update`'s own `launchctl kickstart`) provably started after the checkout
/// last moved. That is what lets the planner restart only when the commit actually changed, rather
/// than whenever a git file's timestamp moved.
public struct DesktopLocalHermesRuntimeRecord: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let executable: String
    public let commit: String
    public let version: String
    /// Lower bound: taken immediately before `launchctl bootstrap` (or the process start, when
    /// adopted).
    public let launchedAt: Date
    /// The exact kernel start time of the process this record describes, once known.
    public let processStartedAt: Date?
    /// Desktop-initiated starts, newest last, for the crash-loop back-off.
    public let recentLaunches: [Date]

    public init(
        executable: String,
        commit: String,
        version: String,
        launchedAt: Date,
        processStartedAt: Date? = nil,
        recentLaunches: [Date] = []
    ) {
        schemaVersion = 1
        self.executable = executable
        self.commit = commit
        self.version = version
        self.launchedAt = launchedAt
        self.processStartedAt = processStartedAt
        self.recentLaunches = recentLaunches
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, executable, commit, version, launchedAt, processStartedAt, recentLaunches
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
        executable = try container.decode(String.self, forKey: .executable)
        commit = try container.decode(String.self, forKey: .commit)
        version = try container.decode(String.self, forKey: .version)
        launchedAt = try container.decode(Date.self, forKey: .launchedAt)
        processStartedAt = try container.decodeIfPresent(Date.self, forKey: .processStartedAt)
        recentLaunches = try container.decodeIfPresent([Date].self, forKey: .recentLaunches) ?? []
    }

    /// Whether this record describes the process launchd is running now.
    func describes(processStartedAt started: Date) -> Bool {
        if let exact = processStartedAt { return abs(exact.timeIntervalSince(started)) < 1 }
        return started >= launchedAt.addingTimeInterval(-1)
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

    /// The local Hermes a *fresh* managed install should run directly, or nil for the bundled copy.
    /// Only a usable, dependency-consistent standard install qualifies, and only with the setting on.
    public static func freshInstallProvider(
        detector: DesktopLocalHermesDetector?,
        enabled: @escaping @Sendable () -> Bool = { isEnabled() }
    ) -> @Sendable () -> DesktopLocalHermesInstallation? {
        {
            guard enabled(), let detector,
                  !detector.updateInProgress(),
                  let installation = detector.detect().installation,
                  installation.dependenciesConsistent
            else { return nil }
            return installation
        }
    }
}

public struct DesktopHermesRuntimeObservation: Equatable, Sendable {
    public let enabled: Bool
    /// nil when detection was skipped (setting off and the agent is not in local mode).
    public let detection: DesktopLocalHermesDetection?
    public let mode: DesktopHermesRuntimeMode
    /// Whether the launcher on disk is exactly what this build writes, privately.
    public let launcherCurrent: Bool
    public let updateInProgress: Bool
    public let service: DesktopHermesServiceProcess
    /// `ProgramArguments` of the agent file on disk.
    public let agentArguments: [String]?
    public let record: DesktopLocalHermesRuntimeRecord?
    public let bundledFallbackAvailable: Bool
    /// Repeated failures Desktop remembers, so a switch or rollback that cannot succeed is not
    /// retried every five minutes at the phone's expense.
    public let failures: DesktopHermesRuntimeFailures
    /// SHA-256 of the kept bundled agent, nil when there is none.
    public let bundledBackupDigest: String?
    public let now: Date

    public init(
        enabled: Bool = true,
        detection: DesktopLocalHermesDetection?,
        mode: DesktopHermesRuntimeMode,
        launcherCurrent: Bool = true,
        updateInProgress: Bool,
        service: DesktopHermesServiceProcess,
        agentArguments: [String]? = nil,
        record: DesktopLocalHermesRuntimeRecord?,
        bundledFallbackAvailable: Bool,
        failures: DesktopHermesRuntimeFailures = DesktopHermesRuntimeFailures(),
        bundledBackupDigest: String? = nil,
        now: Date
    ) {
        self.enabled = enabled
        self.detection = detection
        self.mode = mode
        self.launcherCurrent = launcherCurrent
        self.updateInProgress = updateInProgress
        self.service = service
        self.agentArguments = agentArguments
        self.record = record
        self.bundledFallbackAvailable = bundledFallbackAvailable
        self.failures = failures
        self.bundledBackupDigest = bundledBackupDigest
        self.now = now
    }
}

/// Failed switches and rollbacks, keyed by what would have to change for a retry to make sense.
///
/// A local Hermes that never becomes ready fails the switch after the full readiness window, and
/// the bundled copy is restored; retried every five minutes, that costs the phone its Hermes for
/// minutes at a time, forever. So after `DesktopHermesRuntimePlanner.maximumAttempts` failures the
/// attempt pauses until the thing it failed on changes:
///  - a switch, until the owner's commit changes or the setting is turned off and on again;
///  - a setting-off rollback, until the kept bundled agent changes or the setting is turned on.
public struct DesktopHermesRuntimeFailures: Codable, Equatable, Sendable {
    public var switchCommit: String?
    public var switchFailures: Int
    public var restoreBackupDigest: String?
    public var restoreFailures: Int

    public init(
        switchCommit: String? = nil,
        switchFailures: Int = 0,
        restoreBackupDigest: String? = nil,
        restoreFailures: Int = 0
    ) {
        self.switchCommit = switchCommit
        self.switchFailures = switchFailures
        self.restoreBackupDigest = restoreBackupDigest
        self.restoreFailures = restoreFailures
    }

    public func recordingSwitchFailure(commit: String) -> Self {
        var next = self
        next.switchFailures = switchCommit == commit ? switchFailures + 1 : 1
        next.switchCommit = commit
        return next
    }

    public func recordingRestoreFailure(backupDigest: String?) -> Self {
        var next = self
        next.restoreFailures = restoreBackupDigest == backupDigest ? restoreFailures + 1 : 1
        next.restoreBackupDigest = backupDigest
        return next
    }

    public var clearingSwitch: Self { Self(restoreBackupDigest: restoreBackupDigest, restoreFailures: restoreFailures) }
    public var clearingRestore: Self { Self(switchCommit: switchCommit, switchFailures: switchFailures) }
}

public enum DesktopHermesRuntimeRestartReason: String, Equatable, Sendable {
    /// The commit the running process loaded is no longer the one on disk.
    case codeChanged
    /// launchd reports no process for the job, or the job is not loaded.
    case notRunning
}

public enum DesktopHermesRuntimeWaitReason: String, Equatable, Sendable {
    case updateInProgress
    case settling
    /// The checkout's version and the version installed in its venv differ: dependencies have not
    /// been reinstalled for the code on disk, so starting it could fail with nothing to roll back to.
    case dependenciesPending
    /// Another Desktop operation holds the migration lease; try again on the next refresh.
    case busy
}

public enum DesktopHermesRuntimePlan: Equatable, Sendable {
    case keep
    case wait(DesktopHermesRuntimeWaitReason)
    case switchToLocal(DesktopLocalHermesInstallation)
    case restartLocal(DesktopLocalHermesInstallation, DesktopHermesRuntimeRestartReason)
    /// Return to the kept bundled agent. `localUsable` says whether the owner's Hermes is still
    /// there to fall back to if the bundled one cannot be started (the setting-off rollback).
    case restoreBundled(localUsable: Bool)
    /// launchd is running arguments that differ from the agent file (Desktop stopped between writing
    /// the file and restarting). The file is the intent; restart it. `recording` is set in local mode.
    case reloadAgent(recording: DesktopLocalHermesInstallation?)
    /// A bundled agent whose job launchd does not have loaded at all: nothing runs Hermes and
    /// nothing else will load it. Load the agent file as it is.
    case loadAgent
    /// Local mode is intact but the launcher differs from this build's, or is not private.
    case repairLauncher(executable: URL)
    /// A process Desktop did not start is provably on the current commit; remember that.
    case adopt(DesktopLocalHermesRuntimeRecord)
    /// This Mac has a Hermes Desktop may not use (or is left without one); nothing is changed, and
    /// the reason is shown instead.
    case surface(DesktopHermesRuntimeAttention)

    /// Whether executing the plan touches services or LaunchAgent files (and so needs the lease).
    public var mutates: Bool {
        switch self {
        case .switchToLocal, .restartLocal, .restoreBundled, .reloadAgent, .loadAgent, .repairLauncher: true
        case .keep, .wait, .surface, .adopt: false
        }
    }
}

public enum DesktopHermesRuntimeAttention: Equatable, Sendable {
    case unsupported(DesktopLocalHermesUnsupportedReason, detail: String)
    /// Local mode is configured but the owner's Hermes is gone and there is no usable bundled agent
    /// to return to.
    case localHermesMissingWithoutFallback
    /// Desktop restarted the owner's Hermes repeatedly and it keeps stopping.
    case localHermesKeepsStopping(launches: Int)
    /// The setting is off and the owner's Hermes is intact, but no usable bundled agent is kept to
    /// return to. Nothing was removed; Hermes GO keeps running the Mac's own Hermes.
    case bundledFallbackUnavailableLocalIntact
    /// Switching to the owner's Hermes failed repeatedly on this commit; paused.
    case switchToLocalPaused(commit: String, failures: Int)
    /// Returning to the bundled copy failed repeatedly with this kept agent; paused.
    case restoreBundledPaused(failures: Int)
    /// The venv does not match the checkout (version mismatch for too long, or missing/several
    /// `hermes_agent` dist-info), so Desktop will not start the new code.
    case dependenciesInconsistent(detail: String)
}

public enum DesktopHermesRuntimePlanner {
    /// How long the checkout must stay unchanged before Desktop restarts onto it. `hermes update`
    /// holds its marker for the whole run; this covers a bare `git pull` or `git checkout`.
    public static let settleInterval: TimeInterval = 60
    /// Crash-loop back-off: at most this many Desktop-initiated starts within `launchWindow`.
    public static let maximumLaunches = 3
    public static let launchWindow: TimeInterval = 30 * 60
    /// Failed switches (or setting-off rollbacks) before automatic retries pause.
    public static let maximumAttempts = 2
    /// How long a version mismatch between checkout and venv is waited out before it is shown.
    public static let dependencyGrace: TimeInterval = 10 * 60

    public static func plan(_ observation: DesktopHermesRuntimeObservation) -> DesktopHermesRuntimePlan {
        switch observation.mode {
        case .absent, .unrecognised:
            // Nothing Desktop wrote to reconcile. An unrecognised agent is someone else's file and
            // the token/PATH reconcilers already report it.
            return .keep
        case .bundled, .localHermes:
            break
        }
        // A committed installation whose bundled job is not loaded has no Hermes at all, and no
        // other path loads it again (2026-09-21: a failed switch left it unloaded until an operator
        // bootstrapped it). Load it first — whatever else is planned can follow once it runs. This
        // does not read the checkout, so an update in progress does not delay it. The coordinator
        // re-checks under the migration lease, so an upgrade that has the job stopped on purpose
        // is not interfered with.
        if observation.mode == .bundled, observation.service == .notLoaded { return .loadAgent }
        // First, before anything reads the checkout: an update briefly removes the entrypoint and
        // rewrites HEAD, and nothing seen during it is a fact about the Mac.
        if observation.updateInProgress { return .wait(.updateInProgress) }

        let usable = observation.detection?.installation
        if case .running(_, let loaded?) = observation.service,
           let agent = observation.agentArguments, loaded != agent {
            if case .localHermes(let executable) = observation.mode {
                let recording = usable.flatMap { $0.executable.path == executable.path ? $0 : nil }
                return .reloadAgent(recording: recording)
            }
            return .reloadAgent(recording: nil)
        }

        guard observation.enabled else {
            // Turned off: a Mac in local mode goes back to the agent it had before the switch.
            guard observation.mode.isLocal else { return .keep }
            guard observation.bundledFallbackAvailable else {
                return .surface(usable != nil ? .bundledFallbackUnavailableLocalIntact : .localHermesMissingWithoutFallback)
            }
            let failures = observation.failures
            if usable != nil, failures.restoreFailures >= maximumAttempts,
               failures.restoreBackupDigest == observation.bundledBackupDigest {
                return .surface(.restoreBundledPaused(failures: failures.restoreFailures))
            }
            return .restoreBundled(localUsable: usable != nil)
        }
        guard let detection = observation.detection else { return .keep }
        switch detection {
        case .unsupported(let reason, let detail):
            return .surface(.unsupported(reason, detail: detail))
        case .absent:
            guard observation.mode.isLocal else { return .keep }
            return observation.bundledFallbackAvailable
                ? .restoreBundled(localUsable: false)
                : .surface(.localHermesMissingWithoutFallback)
        case .usable(let installation):
            return planUsable(installation, observation)
        }
    }

    private static func planUsable(
        _ installation: DesktopLocalHermesInstallation,
        _ observation: DesktopHermesRuntimeObservation
    ) -> DesktopHermesRuntimePlan {
        let settled = observation.now.timeIntervalSince(installation.identityChangedAt) >= settleInterval
        guard case .localHermes(let executable) = observation.mode,
              executable.standardizedFileURL.path == installation.executable.standardizedFileURL.path
        else {
            guard settled else { return .wait(.settling) }
            if let blocked = dependencyBlock(installation, observation) { return blocked }
            let failures = observation.failures
            if failures.switchCommit == installation.commit, failures.switchFailures >= maximumAttempts {
                return .surface(.switchToLocalPaused(commit: installation.commit, failures: failures.switchFailures))
            }
            return .switchToLocal(installation)
        }
        if !observation.launcherCurrent { return .repairLauncher(executable: executable) }

        switch observation.service {
        case .unknown:
            // Never restart on a reading that failed: a broken probe must not turn into a
            // restart every refresh.
            return .keep
        case .stopped, .notLoaded:
            let recent = (observation.record?.recentLaunches ?? [])
                .filter { observation.now.timeIntervalSince($0) < launchWindow }
            if recent.count >= maximumLaunches {
                return .surface(.localHermesKeepsStopping(launches: recent.count))
            }
            // A stopped Hermes is started again whatever its venv looks like: leaving it down is
            // never better for the phone, and the crash back-off above bounds a start that fails.
            return .restartLocal(installation, .notRunning)
        case .running(let started, _):
            switch loadedCommit(started: started, installation: installation, record: observation.record) {
            case .current(let adopt):
                return adopt.map { .adopt($0) } ?? .keep
            case .stale:
                break
            }
        }
        let reason = DesktopHermesRuntimeRestartReason.codeChanged
        guard settled else { return .wait(.settling) }
        if let blocked = dependencyBlock(installation, observation) { return blocked }
        return .restartLocal(installation, reason)
    }

    /// Whether the venv lets Desktop start the checkout's code: switching to it, or replacing a
    /// running process with it. A version mismatch is waited out for `dependencyGrace` (the owner is
    /// probably mid-reinstall) and then shown; a missing or duplicated dist-info is shown at once.
    private static func dependencyBlock(
        _ installation: DesktopLocalHermesInstallation,
        _ observation: DesktopHermesRuntimeObservation
    ) -> DesktopHermesRuntimePlan? {
        guard !installation.dependenciesConsistent else { return nil }
        let detail = "checkout \(installation.version), \(installation.installedDistribution.summary)"
        if case .version = installation.installedDistribution,
           observation.now.timeIntervalSince(installation.identityChangedAt) < dependencyGrace {
            return .wait(.dependenciesPending)
        }
        return .surface(.dependenciesInconsistent(detail: detail))
    }

    enum LoadedCommit: Equatable {
        /// Serving the commit on disk; a record to write when that was learned just now.
        case current(adopt: DesktopLocalHermesRuntimeRecord?)
        case stale
    }

    /// Which commit a running process loaded.
    ///
    ///  - A record that describes this process knows: restart only if that commit differs from the
    ///    one on disk. A `git pack-refs` that moves a timestamp but not the commit costs nothing.
    ///  - Otherwise a process that started after the checkout last moved loaded the code on disk
    ///    (this is `hermes update`'s own kickstart) — adopt it, so a later timestamp-only change is
    ///    judged by commit too.
    ///  - Otherwise the loaded commit is unknowable and the checkout has moved since the process
    ///    started; that is treated as stale. It needs a record to have been lost, so it is rare.
    static func loadedCommit(
        started: Date,
        installation: DesktopLocalHermesInstallation,
        record: DesktopLocalHermesRuntimeRecord?
    ) -> LoadedCommit {
        let executable = installation.executable.standardizedFileURL.path
        if let record, record.executable == executable, record.describes(processStartedAt: started) {
            guard record.commit == installation.commit else { return .stale }
            return .current(adopt: record.processStartedAt == nil
                ? record.adopting(processStartedAt: started, commit: installation)
                : nil)
        }
        guard started >= installation.identityChangedAt else { return .stale }
        return .current(adopt: DesktopLocalHermesRuntimeRecord(
            executable: executable,
            commit: installation.commit,
            version: installation.version,
            launchedAt: started,
            processStartedAt: started,
            recentLaunches: record?.recentLaunches ?? []
        ))
    }
}

extension DesktopLocalHermesRuntimeRecord {
    func adopting(processStartedAt started: Date, commit installation: DesktopLocalHermesInstallation)
        -> DesktopLocalHermesRuntimeRecord {
        DesktopLocalHermesRuntimeRecord(
            executable: executable,
            commit: installation.commit,
            version: installation.version,
            launchedAt: launchedAt,
            processStartedAt: started,
            recentLaunches: recentLaunches
        )
    }
}

// MARK: - Process state

/// What launchd reports for the managed Hermes label.
public enum DesktopHermesServiceProcess: Equatable, Sendable {
    /// `arguments` is the vector launchd loaded, when it could be read.
    case running(startedAt: Date, arguments: [String]?)
    /// launchd answered and the job has no process.
    case stopped
    /// The job is not loaded at all.
    case notLoaded
    /// launchd could not be asked, or its answer could not be read.
    case unknown

    public var startedAt: Date? {
        if case .running(let started, _) = self { return started }
        return nil
    }
}

public protocol DesktopHermesServiceProcessInspecting: Sendable {
    func hermesServiceProcess() -> DesktopHermesServiceProcess
}

/// Reads the PID and loaded arguments from `launchctl print` and the start time from the kernel.
/// Read-only.
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
        guard !output.outputLimitExceeded else { return .unknown }
        // `launchctl print` exits 113 ("Could not find service") for an unloaded label.
        if output.status == 113 { return .notLoaded }
        guard output.status == 0, let text = String(data: output.stdout, encoding: .utf8)
        else { return .unknown }
        return Self.process(fromLaunchctlPrint: text, startDate: Self.startDate(pid:))
    }

    static func process(
        fromLaunchctlPrint text: String,
        startDate: (pid_t) -> Date?
    ) -> DesktopHermesServiceProcess {
        if let pid = pid(fromLaunchctlPrint: text) {
            return startDate(pid).map { .running(startedAt: $0, arguments: arguments(fromLaunchctlPrint: text)) }
                ?? .unknown
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

    /// The top-level `arguments = { … }` block: one argument per line, tab-indented one level
    /// deeper than the key. Arguments containing a newline cannot be represented; nil then.
    static func arguments(fromLaunchctlPrint text: String) -> [String]? {
        let lines = text.components(separatedBy: "\n")
        guard let start = lines.firstIndex(where: { $0 == "\targuments = {" }) else { return nil }
        var result: [String] = []
        for line in lines[(start + 1)...] {
            if line == "\t}" { return result }
            guard line.hasPrefix("\t\t") else { return nil }
            result.append(String(line.dropFirst(2)))
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
                code: .localHermesMissingWithoutFallback,
                technicalCause: "stage=restore-bundled no usable bundled agent is stored to return to"
            )
        case .bundledFallbackUnavailableLocalIntact:
            return DesktopIssue(
                code: .localHermesFallbackUnavailable,
                technicalCause: "stage=restore-bundled setting off; kept bundled agent unusable; local Hermes intact and still running"
            )
        case .switchToLocalPaused(let commit, let failures):
            return DesktopIssue(
                code: .localHermesRuntimePaused,
                technicalCause: "stage=switch-to-local commit=\(commit.prefix(12)) failed \(failures) times; paused until the commit changes or the setting is toggled"
            )
        case .restoreBundledPaused(let failures):
            return DesktopIssue(
                code: .localHermesRuntimePaused,
                technicalCause: "stage=restore-bundled failed \(failures) times; paused until the kept agent changes or the setting is turned on"
            )
        case .dependenciesInconsistent(let detail):
            return DesktopIssue(
                code: .localHermesUnsupported,
                technicalCause: "reason=dependenciesInconsistent \(detail)"
            )
        case .localHermesKeepsStopping(let launches):
            return DesktopIssue(
                code: .localHermesRuntimeFailed,
                technicalCause: "stage=restart-local stopped again after \(launches) restarts in 30 minutes; paused"
            )
        }
    }

    /// A failed runtime reconciliation. `HR-MIGRATE-013` when the Hermes job was left unloaded,
    /// `HR-MIGRATE-009` otherwise; either way the cause names the operation, the original error and
    /// the recovery error (`DesktopServiceRecoveryFailure`).
    static func hermesRuntimeFailure(_ error: Error) -> DesktopIssue {
        let code: DesktopIssueCode = DesktopServiceRecoveryFailure.classification(of: error) == .hermesReloadFailed
            ? .managedHermesNotLoaded
            : .localHermesRuntimeFailed
        return DesktopIssue(code: code, technicalCause: "stage=runtime \(String(describing: error))")
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

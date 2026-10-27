import Darwin
import Foundation

/// Where a standard upstream Hermes install lives for one macOS user.
///
/// "Standard" is the layout upstream's own installer produces: `HERMES_HOME=~/.hermes`, a git
/// checkout at `~/.hermes/hermes-agent`, with its published PM launcher or historical `venv/`. It is
/// the only layout Hermes GO will run on the owner's behalf (owner decision 2026-09-21: one copy of
/// Hermes code per Mac). Anything else is surfaced rather than guessed at, because guessing wrong
/// means either a second copy writing the same `state.db` — the 2026-09-19 incident — or running
/// code Desktop does not understand with the owner's credentials.
public struct DesktopLocalHermesPaths: Equatable, Sendable {
    public let homeDirectory: URL
    public let hermesHome: URL
    public let checkoutRoot: URL
    public let executable: URL
    public let launchAgentsRoot: URL
    /// Machine-wide places a `hermes` entrypoint is commonly installed (Homebrew prefixes).
    public let systemEntrypoints: [URL]

    public static let defaultSystemEntrypoints = [
        URL(fileURLWithPath: "/opt/homebrew/bin/hermes"),
        URL(fileURLWithPath: "/usr/local/bin/hermes"),
    ]

    public init(homeDirectory: URL, systemEntrypoints: [URL] = Self.defaultSystemEntrypoints) throws {
        let home = homeDirectory.standardizedFileURL.resolvingSymlinksInPath()
        guard home.isFileURL, home.path.hasPrefix("/"), home.path != "/",
              !home.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { throw DesktopManagedBootstrapPathsError.invalidHomeDirectory }
        self.homeDirectory = home
        hermesHome = home.appendingPathComponent(".hermes", isDirectory: true)
        checkoutRoot = hermesHome.appendingPathComponent("hermes-agent", isDirectory: true)
        executable = checkoutRoot.appendingPathComponent("venv/bin/hermes")
        launchAgentsRoot = home.appendingPathComponent("Library/LaunchAgents", isDirectory: true)
        self.systemEntrypoints = systemEntrypoints
    }

    public static func currentUser(fileManager: FileManager = .default) throws -> DesktopLocalHermesPaths {
        try DesktopLocalHermesPaths(homeDirectory: fileManager.homeDirectoryForCurrentUser)
    }

    public var profilesRoot: URL { hermesHome.appendingPathComponent("profiles", isDirectory: true) }
    public var activeProfileFile: URL { hermesHome.appendingPathComponent("active_profile") }
    public var stateDatabase: URL { hermesHome.appendingPathComponent("state.db") }
    /// Installation-bound upstream command; unlike ~/.local/bin/hermes it cannot select another
    /// checkout. PM owns interpreter/generation selection behind this stable entrypoint.
    public var publishedExecutable: URL { checkoutRoot.appendingPathComponent(".hermes/bin/hermes") }
    /// Upstream's cross-process update lock (`hermes_cli/update_lock.py`, `MARKER_NAME`). Every update
    /// entry point — `hermes update`, the Tauri updater, the Electron app — holds it for its whole run.
    public var updateMarker: URL { hermesHome.appendingPathComponent(".hermes-update-in-progress") }

    /// Other places a `hermes` entrypoint is commonly installed. Each one is acceptable only when it
    /// demonstrably leads back to the standard checkout; otherwise it is a second install.
    public var alternativeEntrypoints: [URL] {
        [homeDirectory.appendingPathComponent(".local/bin/hermes")] + systemEntrypoints
    }

    /// Installs that are a different copy of Hermes by construction, never a shim for this one.
    public var foreignInstallRoots: [URL] {
        [homeDirectory.appendingPathComponent(".local/pipx/venvs/hermes-agent", isDirectory: true)]
    }
}

/// What the standard checkout is running, read from files only — no Hermes code is executed.
///
/// `hermes --version` would have been the obvious probe, and was rejected: it imports the whole CLI
/// (over a second on this Mac), and Desktop reads this every refresh. Git's own files are the
/// authoritative answer to "which code is on disk", and they are what `hermes update` changes.
public struct DesktopLocalHermesInstallation: Equatable, Sendable {
    public let executable: URL
    public let checkoutRoot: URL
    public let hermesHome: URL
    /// Full commit id of the checkout's `HEAD`.
    public let commit: String
    /// `hermes_cli.__version__`, e.g. `0.21.3`.
    public let version: String
    /// When the checkout last moved: the newest modification time among the git files `HEAD` was
    /// resolved through. `git pull`, `git checkout` and `hermes update` all rewrite one of them.
    public let identityChangedAt: Date
    /// What the checkout's venv says is installed (`hermes_agent-*.dist-info`).
    public let installedDistribution: DesktopLocalHermesInstalledDistribution

    public init(
        executable: URL,
        checkoutRoot: URL,
        hermesHome: URL,
        commit: String,
        version: String,
        identityChangedAt: Date,
        installedDistribution: DesktopLocalHermesInstalledDistribution? = nil
    ) {
        self.executable = executable
        self.checkoutRoot = checkoutRoot
        self.hermesHome = hermesHome
        self.commit = commit
        self.version = version
        self.identityChangedAt = identityChangedAt
        self.installedDistribution = installedDistribution ?? .version(version)
    }

    /// Whether this installation is eligible for a bounded start. PM owns dependency selection
    /// through its published command; this property never interprets PM facts as health. For a
    /// legacy venv, as far as files can tell: the installed
    /// distribution's version equals the checkout's `__version__`. A `git pull` that bumps the
    /// version before dependencies are reinstalled fails this. A pull that changes dependencies
    /// without a version bump does not — that is the limit of a check that runs no Hermes code.
    public var dependenciesConsistent: Bool {
        installedDistribution == .packageManaged || installedDistribution == .version(version)
    }

    public var usesPackageManager: Bool {
        executable.standardizedFileURL == checkoutRoot.appendingPathComponent(".hermes/bin/hermes").standardizedFileURL
    }

    public var shortCommit: String { String(commit.prefix(8)) }
}

/// What the venv records as installed. Anything but exactly one distribution is an inconsistency
/// the owner has to resolve; it is never guessed around.
public enum DesktopLocalHermesInstalledDistribution: Equatable, Sendable {
    /// Eligible to start through PM, not proof of runtime health. Only PM may interpret its facts
    /// and dependency stamps; installation verifies the published command and launch verifies HTTP.
    case packageManaged
    case version(String)
    case missing
    case multiple([String])

    public var summary: String {
        switch self {
        case .packageManaged: "dependencies selected by the published PM launcher"
        case .version(let version): "installed \(version)"
        case .missing: "no hermes_agent dist-info in the venv"
        case .multiple(let versions): "several hermes_agent dist-info in the venv: \(versions.joined(separator: ", "))"
        }
    }
}

public enum DesktopLocalHermesUnsupportedReason: String, Equatable, Sendable {
    /// Historical diagnostic value. Profiles alone no longer make an installation unsupported;
    /// the managed launcher pins the existing default data home explicitly.
    case multipleProfiles
    /// Hermes on this Mac is configured with a `HERMES_HOME` other than `~/.hermes`.
    case customHermesHome
    /// A Hermes entrypoint exists outside the standard checkout, or Hermes data exists without it.
    case nonStandardLocation
    /// The checkout or its entrypoint is not a private regular file/directory of this user.
    case unsafeInstallation
    /// The checkout exists but its virtualenv entrypoint does not — possibly mid-install.
    case incompleteInstallation
    /// `HEAD` or `__version__` could not be read.
    case unreadableIdentity
    /// Older than `DesktopLocalHermesDetector.minimumVersion`.
    case versionTooOld
}

extension DesktopLocalHermesUnsupportedReason {
    public var repairGuidanceChinese: String {
        switch self {
        case .multipleProfiles:
            "已有 profiles 会保留。请更新 Desktop 后重新检查，无需整理或删除 Hermes 数据。"
        case .customHermesHome, .nonStandardLocation:
            "已找到 Hermes，但暂时无法确认原连接使用的安装或数据目录。请在诊断中核对安装路径与 HERMES_HOME；不会自动切换到另一个目录，也无需删除现有数据。"
        case .unsafeInstallation:
            "本机 Hermes 的启动文件或目录权限未通过检查。请检查文件所属用户、权限和链接目标后重新检查；Desktop 不会修改 Hermes 文件。"
        case .incompleteInstallation:
            "本机 Hermes 的启动程序缺失。请用 Hermes 原有的安装方式修复启动程序，再重新检查；配置、profiles 和会话应保留。"
        case .unreadableIdentity:
            "无法读取本机 Hermes 的版本或代码记录。请先修复 Hermes 安装，再重新检查；无需删除数据目录。"
        case .versionTooOld:
            "本机 Hermes 版本较旧，无法使用当前连接方式。请通过 Hermes 自身的更新方式升级后重新检查；保留配置与会话。"
        }
    }
}

public enum DesktopLocalHermesDetection: Equatable, Sendable {
    /// No Hermes code in the standard location and no other install found. `hermesDataPresent`
    /// records whether `~/.hermes/state.db` exists anyway, which a fresh install must not ignore.
    case absent(hermesDataPresent: Bool)
    case usable(DesktopLocalHermesInstallation)
    case unsupported(DesktopLocalHermesUnsupportedReason, detail: String)

    public var installation: DesktopLocalHermesInstallation? {
        if case .usable(let installation) = self { return installation }
        return nil
    }
}

/// Decides whether this Mac already has a Hermes that Hermes GO may run instead of its own copy.
///
/// Conservative by construction: every branch that is not the plain standard layout ends in
/// `.unsupported`, never in `.absent`, so an unusual setup can never quietly lead to a second
/// install. The whole detection is file reads; it is cheap enough to run on every Desktop refresh.
public struct DesktopLocalHermesDetector: Sendable {
    /// The oldest upstream Hermes Desktop will run in local mode.
    ///
    /// 0.21.3 is the version this integration was verified against (`17b5df02`, 2026-09-19). It is
    /// also the first version known to carry the single-profile cron gate in
    /// `_start_desktop_cron_ticker` ("Even one profile needs the per-tick gateway gate"): with
    /// `HERMES_DESKTOP=1`, 0.21.0 (`f159e581`) attaches that gate only for more than one profile, so
    /// a single-profile Mac's serve races the owner's own gateway for `cron/.tick.lock` — the race
    /// observed on 2026-09-20. The upstream checkout is shallow, so the exact introducing release
    /// cannot be named; the floor is the version that was read, not a guess below it.
    public static let minimumVersion = "0.21.3"

    private static let maximumSmallFileBytes = 64 * 1024
    private static let maximumPackedRefsBytes = 4 * 1024 * 1024

    public let paths: DesktopLocalHermesPaths
    private let environment: [String: String]
    private let currentUserID: UInt32

    public init(
        paths: DesktopLocalHermesPaths,
        environment: [String: String] = ProcessInfo.processInfo.environment,
        currentUserID: UInt32 = Darwin.getuid()
    ) {
        self.paths = paths
        self.environment = environment
        self.currentUserID = currentUserID
    }

    public func detect() -> DesktopLocalHermesDetection {
        if let detail = customHermesHome() {
            return .unsupported(.customHermesHome, detail: detail)
        }
        guard entryExists(paths.checkoutRoot) else {
            if let detail = foreignInstall(checkoutPresent: false) {
                return .unsupported(.nonStandardLocation, detail: detail)
            }
            return .absent(hermesDataPresent: entryExists(paths.stateDatabase))
        }
        guard isOwnedDirectory(paths.checkoutRoot) else {
            return .unsupported(.unsafeInstallation, detail: "checkout is not a private directory of this user")
        }
        // Never fall back to a stale venv when PM's published command exists but is unsafe.
        let executable = entryExists(paths.publishedExecutable) ? paths.publishedExecutable : paths.executable
        guard entryExists(executable) else {
            return .unsupported(.incompleteInstallation, detail: "no published PM or legacy Hermes entrypoint")
        }
        let bin = executable.deletingLastPathComponent()
        guard executable.resolvingSymlinksInPath() == executable,
              isOwnedDirectory(bin), isOwnedDirectory(bin.deletingLastPathComponent()),
              isSafeExecutable(executable), Self.isScriptSafePath(executable.path) else {
            return .unsupported(.unsafeInstallation, detail: "Hermes entrypoint is not a private executable file")
        }
        if let detail = foreignInstall(checkoutPresent: true) {
            return .unsupported(.nonStandardLocation, detail: detail)
        }
        guard let identity = readGitIdentity() else {
            return .unsupported(.unreadableIdentity, detail: "HEAD could not be resolved")
        }
        guard let version = readVersion(commit: identity.commit) else {
            return .unsupported(.unreadableIdentity, detail: "hermes_cli.__version__ could not be read")
        }
        guard !Self.version(version, isOlderThan: Self.minimumVersion) else {
            return .unsupported(
                .versionTooOld,
                detail: "version \(version) is older than \(Self.minimumVersion)"
            )
        }
        return .usable(DesktopLocalHermesInstallation(
            executable: executable,
            checkoutRoot: paths.checkoutRoot,
            hermesHome: paths.hermesHome,
            commit: identity.commit,
            version: version,
            identityChangedAt: identity.changedAt,
            installedDistribution: executable == paths.publishedExecutable ? .packageManaged : readInstalledDistribution()
        ))
    }

    /// Legacy v1 markers expire after 20 minutes. v2 markers remain live while their exact
    /// owner or delegate process survives, regardless of age (PID reuse does not retain a lock).
    public func updateInProgress(now: Date = Date()) -> Bool {
        guard let modified = modificationDate(paths.updateMarker) else { return false }
        if let data = readSmallFile(paths.updateMarker),
           let text = String(data: data, encoding: .utf8) {
            let lines = text.split(separator: "\n").map(String.init)
            if lines.count >= 3, let pid = Int32(lines[0]), pid > 0,
               lines[2].hasPrefix("ct:"), let created = Double(lines[2].dropFirst(3)), created.isFinite {
                var owners: [(Int32, Double)] = [(pid, created)]
                for line in lines.dropFirst(3) {
                    let parts = line.split(separator: " ")
                    if parts.count == 2, parts[0].hasPrefix("delegate:"), parts[1].hasPrefix("ct:"),
                       let delegate = Int32(parts[0].dropFirst(9)), delegate > 0,
                       let ct = Double(parts[1].dropFirst(3)), ct.isFinite {
                        owners.append((delegate, ct))
                    }
                }
                return owners.contains { pid, created in
                    var info = kinfo_proc()
                    var size = MemoryLayout<kinfo_proc>.stride
                    var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, pid]
                    if sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0,
                       size > 0, info.kp_proc.p_pid == pid {
                        let start = info.kp_proc.p_starttime
                        let actual = Double(start.tv_sec) + Double(start.tv_usec) / 1_000_000
                        return abs(actual - created) <= 2
                    }
                    // An alive process whose creation time is unreadable stays exclusive.
                    return kill(pid, 0) == 0 || errno == EPERM
                }
            }
        }
        return now.timeIntervalSince(modified) < 20 * 60
    }

    /// The launchd label of the job serving this Mac's Hermes loopback port, when one can be named.
    ///
    /// Desktop never stops a job it did not create, so when 9119 is already held the operator has to
    /// release it by hand. "Port 9119 is in use" is not actionable; a label they can boot out is.
    ///
    /// Returns nil when no owner job looks like a loopback server, so a listener Desktop cannot
    /// attribute stays unattributed rather than being blamed on the nearest Hermes-shaped plist.
    public func loopbackPortOccupantLabel() -> String? {
        ownerLaunchAgents()
            .first { servesLoopbackPort($0) }
            .map { String($0.name.dropLast(".plist".count)) }
    }

    // MARK: - Checks

    private func customHermesHome() -> String? {
        if let value = environment["HERMES_HOME"], !value.isEmpty,
           !samePath(value, paths.hermesHome) {
            return "HERMES_HOME is set to a non-standard directory in Desktop's environment"
        }
        for plist in ownerLaunchAgents() {
            if let home = plist.environment["HERMES_HOME"], !samePath(home, paths.hermesHome) {
                return "\(plist.name) runs Hermes with a non-standard HERMES_HOME"
            }
        }
        return nil
    }

    private func foreignInstall(checkoutPresent: Bool) -> String? {
        for root in paths.foreignInstallRoots where entryExists(root) {
            return "a separate install exists at \(root.path)"
        }
        for entrypoint in paths.alternativeEntrypoints where entryExists(entrypoint) {
            guard checkoutPresent, leadsToCheckout(entrypoint) else {
                return "a hermes entrypoint at \(entrypoint.path) does not lead to the standard checkout"
            }
        }
        if checkoutPresent {
            for plist in ownerLaunchAgents() {
                if plist.unverifiedWrapper {
                    return "\(plist.name) osascript target could not be verified; the launch script was not executed"
                }
                guard let program = plist.program else { continue }
                if !isInside(program, paths.checkoutRoot) {
                    return "\(plist.name) runs Hermes from outside the standard checkout"
                }
            }
        }
        return nil
    }

    // MARK: - Identity

    func readGitIdentity() -> (commit: String, changedAt: Date)? {
        let gitDirectory = paths.checkoutRoot.appendingPathComponent(".git", isDirectory: true)
        guard isOwnedDirectory(gitDirectory) else { return nil }
        let headURL = gitDirectory.appendingPathComponent("HEAD")
        guard let headData = readSmallFile(headURL),
              let head = String(data: headData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
              let headModified = modificationDate(headURL)
        else { return nil }
        if Self.isObjectID(head) { return (head, headModified) }
        guard head.hasPrefix("ref: ") else { return nil }
        let reference = String(head.dropFirst(5))
        guard reference.hasPrefix("refs/"),
              !reference.contains(".."),
              reference.range(of: "^[A-Za-z0-9._/-]+$", options: .regularExpression) != nil
        else { return nil }
        let looseURL = gitDirectory.appendingPathComponent(reference)
        if entryExists(looseURL) {
            guard let data = readSmallFile(looseURL),
                  let value = String(data: data, encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                  Self.isObjectID(value),
                  let modified = modificationDate(looseURL)
            else { return nil }
            return (value, max(headModified, modified))
        }
        let packedURL = gitDirectory.appendingPathComponent("packed-refs")
        guard let data = readFile(packedURL, maximumBytes: Self.maximumPackedRefsBytes),
              let text = String(data: data, encoding: .utf8),
              let modified = modificationDate(packedURL)
        else { return nil }
        for line in text.split(separator: "\n") where !line.hasPrefix("#") && !line.hasPrefix("^") {
            let fields = line.split(separator: " ", maxSplits: 1)
            guard fields.count == 2, String(fields[1]) == reference else { continue }
            let value = String(fields[0])
            return Self.isObjectID(value) ? (value, max(headModified, modified)) : nil
        }
        return nil
    }

    private func readVersion(commit: String) -> String? {
        let url = paths.checkoutRoot.appendingPathComponent("hermes_cli/__init__.py")
        guard let data = readSmallFile(url), let text = String(data: data, encoding: .utf8) else { return nil }
        let pattern = "(?m)^__version__\\s*=\\s*[\"']((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))[\"']"
        if let expression = try? NSRegularExpression(pattern: pattern),
           let match = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
           let range = Range(match.range(at: 1), in: text) {
            return String(text[range])
        }
        // v0.22.5 serves __version__ lazily from this stamp. Never import its
        // Python or execute `hermes --version` during a periodic file scan.
        // A source stamp identifies disk code, not completed dependencies or a
        // healthy running service; the existing bounded start still proves those.
        let stampURL = paths.checkoutRoot.appendingPathComponent("install-stamp.json")
        var metadata = stat()
        guard Darwin.lstat(stampURL.path, &metadata) == 0,
              metadata.st_uid == currentUserID, metadata.st_mode & 0o022 == 0,
              let data = readSmallFile(stampURL),
              let stamp = try? JSONDecoder().decode(SourceInstallStamp.self, from: data),
              stamp.schemaVersion == 2, stamp.commit == commit,
              stamp.source == "git", stamp.updateMechanism == "self", stamp.payload == "bootstrap",
              stamp.baseVersion != "0.0.0", stamp.baseVersion.utf8.count <= 64,
              stamp.baseVersion.range(of: "\\A(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\z",
                                      options: .regularExpression) != nil
        else { return nil }
        return stamp.baseVersion
    }

    private struct SourceInstallStamp: Decodable {
        let schemaVersion: Int
        let commit: String
        let baseVersion: String
        let source: String
        let updateMechanism: String
        let payload: String
    }

    /// Every `hermes_agent-*.dist-info` in every `venv/lib/python*/site-packages`, sorted, so the
    /// answer does not depend on directory order. Zero or several is itself the finding.
    private func readInstalledDistribution() -> DesktopLocalHermesInstalledDistribution {
        let lib = paths.checkoutRoot.appendingPathComponent("venv/lib", isDirectory: true)
        var versions: [String] = []
        let pythons = (try? FileManager.default.contentsOfDirectory(atPath: lib.path)) ?? []
        for python in pythons.sorted() where python.hasPrefix("python") {
            let sitePackages = lib.appendingPathComponent(python).appendingPathComponent("site-packages")
            let entries = (try? FileManager.default.contentsOfDirectory(atPath: sitePackages.path)) ?? []
            for entry in entries.sorted() where entry.hasPrefix("hermes_agent-") && entry.hasSuffix(".dist-info") {
                versions.append(String(entry.dropFirst("hermes_agent-".count).dropLast(".dist-info".count)))
            }
        }
        switch versions.count {
        case 0: return .missing
        case 1: return .version(versions[0])
        default: return .multiple(versions)
        }
    }

    // MARK: - Owner LaunchAgents

    private struct OwnerLaunchAgent {
        let name: String
        let program: String?
        let arguments: [String]
        let environment: [String: String]
        let unverifiedWrapper: Bool
    }

    /// LaunchAgents that run *this Mac's* Hermes — the most reliable record of how the owner
    /// actually starts it, because upstream wrote them.
    ///
    /// Upstream's own services are labelled `ai.hermes.*` (`gateway`, `dashboard`, per-profile
    /// variants), and for a long time that prefix was also the whole match rule. It is not a
    /// contract: on a machine whose owner configured the dashboard by hand the job is called
    /// `com.hermes.dashboard`, and the prefix rule made it invisible. That job held port 9119,
    /// so Desktop saw a healthy Hermes it could not account for and refused to proceed
    /// (2026-09-30). A plist therefore also counts when the program it runs lives inside the
    /// standard checkout, which is the property that actually matters.
    ///
    /// Read only; a file that cannot be parsed is skipped, not trusted.
    private func ownerLaunchAgents() -> [OwnerLaunchAgent] {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: paths.launchAgentsRoot.path)
        else { return [] }
        var agents: [OwnerLaunchAgent] = []
        for name in names.sorted() where name.hasSuffix(".plist") {
            let url = paths.launchAgentsRoot.appendingPathComponent(name)
            guard let data = readSmallFile(url),
                  let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)
                    as? [String: Any]
            else { continue }
            let originalArguments = (object["ProgramArguments"] as? [String]) ?? []
            let originalProgram = (object["Program"] as? String) ?? originalArguments.first
            let isWrapper = originalProgram == "/usr/bin/osascript" || originalArguments.first == "/usr/bin/osascript"
            let decoded = isWrapper && originalProgram == originalArguments.first
                ? DesktopHermesLaunchdCommand.unwrap(originalArguments) : nil
            let arguments = decoded ?? originalArguments
            let program = decoded?.first ?? originalProgram
            let runsCheckout = program.map { isInside($0, paths.checkoutRoot) } ?? false
            guard name.hasPrefix("ai.hermes.") || runsCheckout else { continue }
            let environment = (object["EnvironmentVariables"] as? [String: Any])?
                .compactMapValues { $0 as? String } ?? [:]
            agents.append(OwnerLaunchAgent(
                name: name,
                program: program,
                arguments: arguments,
                environment: environment,
                unverifiedWrapper: isWrapper && decoded == nil
            ))
        }
        return agents
    }

    /// Upstream's server entry points. `hermes serve` and `hermes dashboard` are the same server
    /// family and both default to port 9119, so a job invoking one of them without naming another
    /// port is serving the loopback port.
    private static let serveSubcommands: Set<String> = ["serve", "dashboard", "gui", "desktop"]

    private func servesLoopbackPort(_ agent: OwnerLaunchAgent) -> Bool {
        guard !agent.arguments.isEmpty else { return false }
        if agent.arguments.contains(String(DesktopHermesRuntimeContract.loopbackPort)) { return true }
        if let index = agent.arguments.firstIndex(of: "--port"), index + 1 < agent.arguments.count {
            return false
        }
        return agent.arguments.contains { Self.serveSubcommands.contains($0) }
    }

    // MARK: - Filesystem helpers

    private func leadsToCheckout(_ entrypoint: URL) -> Bool {
        var metadata = stat()
        guard Darwin.lstat(entrypoint.path, &metadata) == 0 else { return false }
        if metadata.st_mode & S_IFMT == S_IFLNK {
            return isInside(entrypoint.resolvingSymlinksInPath().path, paths.checkoutRoot)
        }
        // Upstream's installer writes `~/.local/bin/hermes` as a small shell shim that execs the
        // checkout's venv python; accept a shim only when it names the checkout itself.
        guard metadata.st_mode & S_IFMT == S_IFREG,
              let data = readSmallFile(entrypoint),
              let text = String(data: data, encoding: .utf8)
        else { return false }
        return text.contains(paths.checkoutRoot.path + "/")
    }

    private func isSafeExecutable(_ url: URL) -> Bool {
        var metadata = stat()
        return Darwin.lstat(url.path, &metadata) == 0
            && metadata.st_mode & S_IFMT == S_IFREG
            && metadata.st_uid == currentUserID
            && metadata.st_mode & 0o022 == 0
            && metadata.st_mode & 0o100 != 0
    }

    private func isOwnedDirectory(_ url: URL) -> Bool {
        var metadata = stat()
        return Darwin.lstat(url.path, &metadata) == 0
            && metadata.st_mode & S_IFMT == S_IFDIR
            && metadata.st_uid == currentUserID
            && metadata.st_mode & 0o022 == 0
    }

    private func entryExists(_ url: URL) -> Bool {
        var metadata = stat()
        return Darwin.lstat(url.path, &metadata) == 0
    }

    private func modificationDate(_ url: URL) -> Date? {
        var metadata = stat()
        guard Darwin.lstat(url.path, &metadata) == 0 else { return nil }
        let time = metadata.st_mtimespec
        return Date(timeIntervalSince1970: TimeInterval(time.tv_sec) + TimeInterval(time.tv_nsec) / 1_000_000_000)
    }

    private func readSmallFile(_ url: URL) -> Data? {
        readFile(url, maximumBytes: Self.maximumSmallFileBytes)
    }

    private func readFile(_ url: URL, maximumBytes: Int) -> Data? {
        var metadata = stat()
        guard Darwin.lstat(url.path, &metadata) == 0,
              metadata.st_mode & S_IFMT == S_IFREG,
              metadata.st_size <= maximumBytes
        else { return nil }
        return try? Data(contentsOf: url)
    }

    private func samePath(_ value: String, _ expected: URL) -> Bool {
        let expanded = (value as NSString).expandingTildeInPath
        let candidate = URL(fileURLWithPath: expanded).standardizedFileURL.resolvingSymlinksInPath().path
        let wanted = expected.standardizedFileURL.resolvingSymlinksInPath().path
        return candidate.trimmingTrailingSlash == wanted.trimmingTrailingSlash
    }

    private func isInside(_ path: String, _ root: URL) -> Bool {
        let base = root.standardizedFileURL.path.trimmingTrailingSlash + "/"
        return URL(fileURLWithPath: path).standardizedFileURL.path.hasPrefix(base)
    }

    // MARK: - Pure helpers

    static func isObjectID(_ value: String) -> Bool {
        (value.utf8.count == 40 || value.utf8.count == 64)
            && value.range(of: "^[0-9a-f]+$", options: .regularExpression) != nil
    }

    /// A path Desktop can embed in its launcher script between single quotes, and one whose
    /// `ProgramArguments` upstream's `hermes update` recognises (`shlex.join` must not quote it).
    static func isScriptSafePath(_ value: String) -> Bool {
        value.hasPrefix("/")
            && value.range(of: "^[A-Za-z0-9@%+=:,./_-]+$", options: .regularExpression) != nil
    }

    static func version(_ candidate: String, isOlderThan minimum: String) -> Bool {
        let lhs = candidate.split(separator: ".").compactMap { Int($0) }
        let rhs = minimum.split(separator: ".").compactMap { Int($0) }
        guard lhs.count == 3, rhs.count == 3 else { return true }
        return lhs.lexicographicallyPrecedes(rhs)
    }
}

private extension String {
    var trimmingTrailingSlash: String {
        count > 1 && hasSuffix("/") ? String(dropLast()) : self
    }
}

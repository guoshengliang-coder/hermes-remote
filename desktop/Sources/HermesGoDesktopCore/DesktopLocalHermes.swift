import Darwin
import Foundation

/// Where a standard upstream Hermes install lives for one macOS user.
///
/// "Standard" is the layout upstream's own installer produces: `HERMES_HOME=~/.hermes`, a git
/// checkout at `~/.hermes/hermes-agent`, and its virtualenv at `venv/` inside that checkout. It is
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

    public init(
        executable: URL,
        checkoutRoot: URL,
        hermesHome: URL,
        commit: String,
        version: String,
        identityChangedAt: Date
    ) {
        self.executable = executable
        self.checkoutRoot = checkoutRoot
        self.hermesHome = hermesHome
        self.commit = commit
        self.version = version
        self.identityChangedAt = identityChangedAt
    }

    public var shortCommit: String { String(commit.prefix(8)) }
}

public enum DesktopLocalHermesUnsupportedReason: String, Equatable, Sendable {
    /// `~/.hermes/profiles/` holds a profile, or `active_profile` names one. Desktop's serve would
    /// tick every profile's cron store while the phone only reaches the default one.
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
        guard entryExists(paths.executable) else {
            return .unsupported(.incompleteInstallation, detail: "venv/bin/hermes is missing")
        }
        guard isSafeExecutable(paths.executable), Self.isScriptSafePath(paths.executable.path) else {
            return .unsupported(.unsafeInstallation, detail: "venv/bin/hermes is not a private executable file")
        }
        if let detail = foreignInstall(checkoutPresent: true) {
            return .unsupported(.nonStandardLocation, detail: detail)
        }
        if let detail = profilesInUse() {
            return .unsupported(.multipleProfiles, detail: detail)
        }
        guard let identity = readGitIdentity() else {
            return .unsupported(.unreadableIdentity, detail: "HEAD could not be resolved")
        }
        guard let version = readVersion() else {
            return .unsupported(.unreadableIdentity, detail: "hermes_cli.__version__ could not be read")
        }
        guard !Self.version(version, isOlderThan: Self.minimumVersion) else {
            return .unsupported(
                .versionTooOld,
                detail: "version \(version) is older than \(Self.minimumVersion)"
            )
        }
        return .usable(DesktopLocalHermesInstallation(
            executable: paths.executable,
            checkoutRoot: paths.checkoutRoot,
            hermesHome: paths.hermesHome,
            commit: identity.commit,
            version: version,
            identityChangedAt: identity.changedAt
        ))
    }

    /// Whether an upstream update currently holds its lock. A marker older than upstream's own
    /// ceiling (`UPDATE_MARKER_MAX_AGE_SECONDS`, 20 minutes) is stale by upstream's definition and
    /// does not count — otherwise a crashed update would freeze Desktop's restart logic forever.
    public func updateInProgress(now: Date = Date()) -> Bool {
        guard let modified = modificationDate(paths.updateMarker) else { return false }
        return now.timeIntervalSince(modified) < 20 * 60
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
                guard let program = plist.program else { continue }
                if !isInside(program, paths.checkoutRoot) {
                    return "\(plist.name) runs Hermes from outside the standard checkout"
                }
            }
        }
        return nil
    }

    private func profilesInUse() -> String? {
        if let entries = try? FileManager.default.contentsOfDirectory(atPath: paths.profilesRoot.path) {
            let profiles = entries.filter { name in
                guard !name.hasPrefix(".") else { return false }
                var isDirectory: ObjCBool = false
                return FileManager.default.fileExists(
                    atPath: paths.profilesRoot.appendingPathComponent(name).path,
                    isDirectory: &isDirectory
                ) && isDirectory.boolValue
            }
            if !profiles.isEmpty { return "\(profiles.count) profile(s) under ~/.hermes/profiles" }
        }
        if let data = readSmallFile(paths.activeProfileFile),
           let value = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !value.isEmpty, value != "default" {
            return "active_profile names a non-default profile"
        }
        return nil
    }

    // MARK: - Identity

    private func readGitIdentity() -> (commit: String, changedAt: Date)? {
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

    private func readVersion() -> String? {
        let url = paths.checkoutRoot.appendingPathComponent("hermes_cli/__init__.py")
        guard let data = readSmallFile(url), let text = String(data: data, encoding: .utf8) else { return nil }
        let pattern = "(?m)^__version__\\s*=\\s*[\"']((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))[\"']"
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range(at: 1), in: text)
        else { return nil }
        return String(text[range])
    }

    // MARK: - Owner LaunchAgents

    private struct OwnerLaunchAgent {
        let name: String
        let program: String?
        let environment: [String: String]
    }

    /// Upstream's own services (`ai.hermes.gateway`, `ai.hermes.dashboard`, per-profile variants).
    /// They are the most reliable record of how the owner actually runs Hermes, because upstream
    /// wrote them. Read only; a file that cannot be parsed is skipped, not trusted.
    private func ownerLaunchAgents() -> [OwnerLaunchAgent] {
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: paths.launchAgentsRoot.path)
        else { return [] }
        return names.sorted().compactMap { name in
            guard name.hasPrefix("ai.hermes."), name.hasSuffix(".plist") else { return nil }
            let url = paths.launchAgentsRoot.appendingPathComponent(name)
            guard let data = readSmallFile(url),
                  let object = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil)
                    as? [String: Any]
            else { return nil }
            let environment = (object["EnvironmentVariables"] as? [String: Any])?
                .compactMapValues { $0 as? String } ?? [:]
            let program = (object["ProgramArguments"] as? [String])?.first
                ?? (object["Program"] as? String)
            return OwnerLaunchAgent(name: name, program: program, environment: environment)
        }
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

import Foundation

public enum DesktopExternalEnvironmentStatus: String, Equatable, Sendable {
    case detected
    case reusable
    case incompatible
    case unsafe
}

public struct DesktopExternalEnvironmentObservation: Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let executableURL: URL
    public let version: String?
    public let architecture: String?
    public let status: DesktopExternalEnvironmentStatus
    public let candidate: DesktopManagedComponentCandidate?

    public init(
        kind: DesktopManagedComponentKind,
        executableURL: URL,
        version: String?,
        architecture: String?,
        status: DesktopExternalEnvironmentStatus,
        candidate: DesktopManagedComponentCandidate?
    ) {
        self.kind = kind
        self.executableURL = executableURL
        self.version = version
        self.architecture = architecture
        self.status = status
        self.candidate = candidate
    }
}

public struct DesktopExternalEnvironmentScan: Equatable, Sendable {
    public let observations: [DesktopExternalEnvironmentObservation]

    public var reusableCandidates: [DesktopManagedComponentCandidate] {
        observations.compactMap(\.candidate)
    }
}

public struct DesktopExternalEnvironmentPath: Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let executableURL: URL

    public init(kind: DesktopManagedComponentKind, executableURL: URL) {
        self.kind = kind
        self.executableURL = executableURL
    }
}

/// Reads only an allowlisted set of executable paths. Python and Node observations never receive a
/// reusable candidate from version output alone. Browser reuse additionally requires an explicit
/// compatibility policy, a safe regular executable, matching architecture, an output-bounded version
/// probe, and a successful Desktop-owned compatibility probe.
public struct DesktopExternalEnvironmentScanner<Runner: OutputCommandRunning> {
    public typealias BrowserCompatibilityProbe = (
        _ executable: URL,
        _ version: String,
        _ identifier: String
    ) -> Bool

    private static var maximumOutputBytes: Int { 4 * 1_024 }

    private let paths: [DesktopExternalEnvironmentPath]
    private let runner: Runner
    private let fileManager: FileManager
    private let currentUserID: UInt32
    private let browserCompatibilityProbe: BrowserCompatibilityProbe
    private let fileExecutable = URL(fileURLWithPath: "/usr/bin/file")

    public init(
        paths: [DesktopExternalEnvironmentPath] = Self.defaultPaths,
        runner: Runner,
        currentUserID: UInt32,
        browserCompatibilityProbe: @escaping BrowserCompatibilityProbe = { _, _, _ in false },
        fileManager: FileManager = .default
    ) {
        self.paths = paths
        self.runner = runner
        self.currentUserID = currentUserID
        self.browserCompatibilityProbe = browserCompatibilityProbe
        self.fileManager = fileManager
    }

    public func scan(
        requirements: [DesktopManagedComponentRequirement]
    ) -> DesktopExternalEnvironmentScan {
        let requirementsByKind = Dictionary(
            requirements.map { ($0.kind, $0) }, uniquingKeysWith: { first, _ in first }
        )
        let observations = paths.compactMap { path -> DesktopExternalEnvironmentObservation? in
            guard fileManager.fileExists(atPath: path.executableURL.path) else { return nil }
            guard let safeExecutable = inspectSafeExecutable(
                path.executableURL,
                kind: path.kind
            ) else {
                return DesktopExternalEnvironmentObservation(
                    kind: path.kind,
                    executableURL: path.executableURL,
                    version: nil,
                    architecture: nil,
                    status: .unsafe,
                    candidate: nil
                )
            }
            let architecture = inspectArchitecture(safeExecutable)
            let version = inspectVersion(kind: path.kind, executable: safeExecutable)
            guard let requirement = requirementsByKind[path.kind],
                  let architecture,
                  let version
            else {
                return DesktopExternalEnvironmentObservation(
                    kind: path.kind,
                    executableURL: path.executableURL,
                    version: version,
                    architecture: architecture,
                    status: .detected,
                    candidate: nil
                )
            }
            guard architecture == requirement.architecture || architecture == "universal" else {
                return DesktopExternalEnvironmentObservation(
                    kind: path.kind,
                    executableURL: path.executableURL,
                    version: version,
                    architecture: architecture,
                    status: .incompatible,
                    candidate: nil
                )
            }
            guard path.kind == .browserAutomation,
                  case .verifiedCompatibility(_, let identifier) = requirement.reusePolicy,
                  browserCompatibilityProbe(safeExecutable, version, identifier)
            else {
                return DesktopExternalEnvironmentObservation(
                    kind: path.kind,
                    executableURL: path.executableURL,
                    version: version,
                    architecture: architecture,
                    status: .detected,
                    candidate: nil
                )
            }
            let candidate = DesktopManagedComponentCandidate(
                kind: path.kind,
                version: version,
                architecture: architecture,
                source: .external,
                compatibilityIdentifier: identifier,
                healthProbePassed: true
            )
            return DesktopExternalEnvironmentObservation(
                kind: path.kind,
                executableURL: path.executableURL,
                version: version,
                architecture: architecture,
                status: .reusable,
                candidate: candidate
            )
        }
        return DesktopExternalEnvironmentScan(observations: observations)
    }

    private func inspectSafeExecutable(
        _ value: URL,
        kind: DesktopManagedComponentKind
    ) -> URL? {
        let supplied = value.standardizedFileURL
        guard supplied.isFileURL, supplied.path.hasPrefix("/"), supplied.path != "/" else {
            return nil
        }
        let suppliedValues = try? supplied.resourceValues(forKeys: [.isSymbolicLinkKey])
        // Homebrew's fixed Python and Node entrypoints are normally symlinks. They may be resolved
        // for reporting, but external runtimes still never become reuse candidates from this scan.
        if suppliedValues?.isSymbolicLink == true, kind == .browserAutomation {
            return nil
        }
        let resolved = supplied.resolvingSymlinksInPath()
        guard let values = try? resolved.resourceValues(forKeys: [
            .isRegularFileKey, .isSymbolicLinkKey, .isExecutableKey,
        ]),
              values.isRegularFile == true,
              values.isSymbolicLink != true,
              values.isExecutable == true,
              let attributes = try? fileManager.attributesOfItem(atPath: resolved.path),
              let owner = (attributes[.ownerAccountID] as? NSNumber)?.uint32Value,
              owner == 0 || owner == currentUserID,
              let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue,
              permissions & 0o022 == 0
        else { return nil }
        return resolved
    }

    private func inspectArchitecture(_ executable: URL) -> String? {
        let output = runner.run(
            executable: fileExecutable,
            arguments: ["-b", executable.path],
            maximumOutputBytes: Self.maximumOutputBytes
        )
        guard output.status == 0, !output.outputLimitExceeded,
              let text = String(data: output.stdout, encoding: .utf8)
        else { return nil }
        let arm64 = text.range(of: "arm64", options: .caseInsensitive) != nil
        let x86 = text.range(of: "x86_64", options: .caseInsensitive) != nil
        if arm64 && x86 { return "universal" }
        if arm64 { return "arm64" }
        if x86 { return "x86_64" }
        return nil
    }

    private func inspectVersion(
        kind: DesktopManagedComponentKind,
        executable: URL
    ) -> String? {
        let arguments: [String]
        switch kind {
        case .pythonRuntime:
            arguments = ["-c", "import platform; print(platform.python_version())"]
        case .nodeRuntime, .browserAutomation:
            arguments = ["--version"]
        default:
            return nil
        }
        let output = runner.run(
            executable: executable,
            arguments: arguments,
            maximumOutputBytes: Self.maximumOutputBytes
        )
        guard output.status == 0, !output.outputLimitExceeded,
              let text = String(data: output.stdout, encoding: .utf8),
              let match = text.range(
                of: "(?<![0-9])(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?![0-9])",
                options: .regularExpression
              )
        else { return nil }
        return String(text[match])
    }

    public static var defaultPaths: [DesktopExternalEnvironmentPath] {
        [
            .init(
                kind: .pythonRuntime,
                executableURL: URL(fileURLWithPath: "/opt/homebrew/bin/python3")
            ),
            .init(
                kind: .pythonRuntime,
                executableURL: URL(fileURLWithPath: "/usr/local/bin/python3")
            ),
            .init(
                kind: .nodeRuntime,
                executableURL: URL(fileURLWithPath: "/opt/homebrew/bin/node")
            ),
            .init(
                kind: .nodeRuntime,
                executableURL: URL(fileURLWithPath: "/usr/local/bin/node")
            ),
            .init(
                kind: .browserAutomation,
                executableURL: URL(
                    fileURLWithPath: "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                )
            ),
            .init(
                kind: .browserAutomation,
                executableURL: URL(
                    fileURLWithPath: "/Applications/Chromium.app/Contents/MacOS/Chromium"
                )
            ),
        ]
    }
}

import Darwin
import Foundation

/// The running app still uses the original bundle bytes. This is not compatibility approval or
/// protection against privileged process injection; a fresh check is required before execution.
public struct DesktopAppRuntimeIdentity: Equatable, Sendable {
    public let component: HermesCompatibilityComponent
    public let processID: Int32
    public let startedAt: Date
    public let contentSHA256: String
    fileprivate init(observation: DesktopAppIdentityObservation, process: ConnectorKernelIdentity) {
        component = observation.component; processID = process.pid
        startedAt = process.startedAt; contentSHA256 = observation.contentSHA256
    }
}

public struct DesktopAppRuntimeIdentityInspector {
    private let readProcess: (Int32) -> ConnectorKernelIdentity?
    public init() { readProcess = ConnectorKernelIdentity.read }
    init(readProcess: @escaping (Int32) -> ConnectorKernelIdentity?) { self.readProcess = readProcess }

    public func inspect(observation: DesktopAppIdentityObservation, installedApp: URL,
                        processID: Int32 = getpid()) throws -> DesktopAppRuntimeIdentity {
        var stage = "inspect_process"
        do {
            let app = installedApp.standardizedFileURL
            guard app.pathExtension == "app", app == app.resolvingSymlinksInPath(),
                  let before = readProcess(processID), before.pid == processID,
                  before.userID == getuid(), !before.injectedEnvironment else { throw Self.issue }
            stage = "inspect_bundle"
            let infoURL = app.appendingPathComponent("Contents/Info.plist")
            let size = try infoURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard size > 0, size <= 1024 * 1024,
                  let info = try PropertyListSerialization.propertyList(from: Data(contentsOf: infoURL), format: nil) as? [String: Any],
                  info["CFBundleIdentifier"] as? String == "com.hermesgo.desktop",
                  info["CFBundleShortVersionString"] as? String == observation.component.version,
                  info["CFBundleVersion"] as? String == observation.buildNumber,
                  let executable = info["CFBundleExecutable"] as? String,
                  executable.range(of: #"\A[A-Za-z0-9_-]{1,128}\z"#, options: .regularExpression) != nil,
                  before.executable == app.appendingPathComponent("Contents/MacOS/" + executable).path,
                  Self.accepts(arguments: before.arguments, executable: before.executable) else { throw Self.issue }
            stage = "verify_content"
            guard try DesktopManagedComponentContentHasher().identify(directory: app).sha256 == observation.contentSHA256
            else { throw Self.issue }
            stage = "verify_continuity"
            try DesktopConnectorRuntimeIdentityInspector.requireUnchangedTree(app, since: before.startedAt,
                                                                              allowedOwners: [0, getuid()])
            stage = "recheck_process"
            guard readProcess(processID) == before else { throw Self.issue }
            return DesktopAppRuntimeIdentity(observation: observation, process: before)
        } catch {
            throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid,
                               technicalCause: "desktop_app_runtime_identity=\(stage)")
        }
    }

    static func accepts(arguments: [String], executable: String) -> Bool {
        if arguments == [executable] { return true }
        return arguments.count == 2 && arguments[0] == executable
            && arguments[1].range(of: #"\A-psn_[0-9]+_[0-9]+\z"#, options: .regularExpression) != nil
    }
    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}

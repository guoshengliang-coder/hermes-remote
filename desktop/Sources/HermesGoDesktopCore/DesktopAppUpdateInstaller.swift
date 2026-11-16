import CryptoKit
import Darwin
import Foundation

public enum DesktopAppUpdateInstallError: Error, Equatable, Sendable {
    case invalidRequest
    case incompatibleSystem
    case downloadFailed
    case verificationFailed
    case mountFailed
    case bundleMismatch
    case stagingFailed
    case launchFailed
    case recoveryRequired
}

/// A fully downloaded, digest-checked, and staged replacement app. Nothing has been changed on disk
/// yet: `commit` is the only step that schedules the swap.
public struct DesktopPreparedAppUpdate: Equatable, Sendable {
    public let reference: DesktopAppUpdateReference
    public let destinationAppURL: URL
    public let stagedAppURL: URL
    public let workspaceURL: URL
    public let mountPointURL: URL
    public let destinationDigest: String
    public let stagedDigest: String
}

/// Installs a new Desktop app from the index's signed-off DMG. Internal-test builds are ad-hoc signed,
/// so this is a best-effort in-place replacement, not a notarized auto-updater: the caller must have
/// shown the exact version and notes before `commit`.
public final class DesktopAppUpdateInstaller: @unchecked Sendable {
    private let downloader: DesktopReleaseDownloader
    private let fileManager: FileManager
    private let bundleIdentifier: String?
    private let configuration: DesktopAppUpdateConfigurationState
    private let componentConfiguration: DesktopComponentPreflightConfigurationState
    private let processID: Int32

    public init(
        downloader: DesktopReleaseDownloader = DesktopReleaseDownloader(),
        fileManager: FileManager = .default,
        bundleIdentifier: String? = Bundle.main.bundleIdentifier,
        processID: Int32 = getpid(),
        configuration: DesktopAppUpdateConfigurationState = .load(),
        componentConfiguration: DesktopComponentPreflightConfigurationState = .load()
    ) {
        self.configuration = configuration
        self.componentConfiguration = componentConfiguration
        self.downloader = downloader
        self.fileManager = fileManager
        self.bundleIdentifier = bundleIdentifier
        self.processID = processID
    }

    public func prepare(
        reference: DesktopAppUpdateReference,
        destinationAppURL: URL,
        workspaceRoot: URL,
        repair: Bool = false,
        currentMacOS: OperatingSystemVersion = ProcessInfo.processInfo.operatingSystemVersion
    ) async throws -> DesktopPreparedAppUpdate {
        guard Self.validDestination(destinationAppURL),
              case .configured(let expected) = configuration,
              reference.downloadURL.scheme == "https",
              reference.downloadURL.host == expected.indexURL.host, reference.downloadURL.port == expected.indexURL.port,
              reference.downloadURL.user == nil, reference.downloadURL.password == nil,
              reference.downloadURL.query == nil, reference.downloadURL.fragment == nil,
              reference.downloadURL.path == expected.indexURL.deletingLastPathComponent().path + "/\(reference.appVersion)/Hermes-Go-Desktop-\(reference.appVersion).dmg",
              DesktopDistributionConfiguration.validSource(reference.sourceCommit), reference.buildNumber > 0,
              reference.sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
        else {
            throw DesktopAppUpdateInstallError.invalidRequest
        }
        if let minimum = Self.parseOperatingSystemVersion(reference.minimumMacOS),
           Self.compare(currentMacOS, minimum) == .orderedAscending {
            throw DesktopAppUpdateInstallError.incompatibleSystem
        }

        guard let currentBundle = Bundle(url: destinationAppURL),
              let currentVersion = currentBundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              let rawBuild = currentBundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
              let currentBuild = Int(rawBuild),
              DesktopSemanticVersion.isNewer(reference.appVersion, than: currentVersion)
                || (reference.appVersion == currentVersion && reference.buildNumber > currentBuild)
                || (repair && reference.appVersion == currentVersion && reference.buildNumber == currentBuild
                    && (Self.identityNeedsRepair(bundle: currentBundle)
                        || currentBundle.object(forInfoDictionaryKey: "HermesGoSourceCommit") as? String != reference.sourceCommit))
        else { throw DesktopAppUpdateInstallError.invalidRequest }
        let destinationDigest = try DesktopAppUpdateTransaction.digest(destinationAppURL)
        try DesktopAppUpdateTransactions.withLock(root: workspaceRoot) {
            if let previous = try DesktopAppUpdateTransactions.load(root: workspaceRoot),
               previous.state != .completed && previous.state != .restored {
                throw DesktopAppUpdateInstallError.recoveryRequired
            }
        }
        let workspace = workspaceRoot.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let downloads = workspace.appendingPathComponent("downloads", isDirectory: true)
        let mountPoint = workspace.appendingPathComponent("mount", isDirectory: true)
        do {
            try Self.createPrivateDirectory(workspace, fileManager: fileManager)
            try Self.createPrivateDirectory(downloads, fileManager: fileManager)
            try Self.createPrivateDirectory(mountPoint, fileManager: fileManager)
        } catch {
            throw DesktopAppUpdateInstallError.stagingFailed
        }

        var stagedApp: URL?
        var mounted = false
        defer { if mounted { _ = detach(mountPoint) } }
        do {
            let dmg = try await downloader.download(
                from: reference.downloadURL,
                fileName: reference.downloadURL.lastPathComponent,
                sizeBytes: reference.sizeBytes,
                into: downloads
            )
            guard try Self.sha256(ofFileAt: dmg) == reference.sha256 else {
                throw DesktopAppUpdateInstallError.verificationFailed
            }
            try attach(dmg, at: mountPoint)
            mounted = true
            let mountedApp: URL
            do {
                mountedApp = try findMountedApp(at: mountPoint, reference: reference)
            } catch {
                if detach(mountPoint) { mounted = false }
                throw error
            }
            try Task.checkCancellation()
            let staged = try stage(
                mountedApp,
                destinationAppURL: destinationAppURL
            )
            stagedApp = staged
            guard detach(mountPoint) else { throw DesktopAppUpdateInstallError.mountFailed }
            mounted = false
            try Task.checkCancellation()
            return DesktopPreparedAppUpdate(
                reference: reference,
                destinationAppURL: destinationAppURL,
                stagedAppURL: staged,
                workspaceURL: workspace,
                mountPointURL: mountPoint,
                destinationDigest: destinationDigest,
                stagedDigest: try DesktopAppUpdateTransaction.digest(staged)
            )
        } catch let error as DesktopAppUpdateInstallError {
            if mounted, detach(mountPoint) { mounted = false }
            if let stagedApp { try? fileManager.removeItem(at: stagedApp) }
            if !mounted { try? fileManager.removeItem(at: workspace) }
            throw error
        } catch {
            if mounted, detach(mountPoint) { mounted = false }
            if let stagedApp { try? fileManager.removeItem(at: stagedApp) }
            if !mounted { try? fileManager.removeItem(at: workspace) }
            if Task.isCancelled { throw CancellationError() }
            throw DesktopAppUpdateInstallError.downloadFailed
        }
    }

    /// Schedules the swap to run after this process exits, then returns. The caller is expected to
    /// terminate the app so the helper can replace it and relaunch.
    public func commit(_ prepared: DesktopPreparedAppUpdate) throws {
        let root = prepared.workspaceURL.deletingLastPathComponent()
        try DesktopAppUpdateTransactions.withLock(root: root) {
            if let previous = try DesktopAppUpdateTransactions.load(root: root),
               previous.state != .completed && previous.state != .restored {
                throw DesktopAppUpdateInstallError.recoveryRequired
            }
            guard try DesktopAppUpdateTransaction.digest(prepared.destinationAppURL) == prepared.destinationDigest,
                  try DesktopAppUpdateTransaction.digest(prepared.stagedAppURL) == prepared.stagedDigest else {
                throw DesktopAppUpdateInstallError.verificationFailed
            }
            try verifyBundle(prepared.stagedAppURL, reference: prepared.reference)
            if let previous = try DesktopAppUpdateTransactions.load(root: root) {
                let history = root.appendingPathComponent("update-result-\(previous.id.uuidString).json")
                if !fileManager.fileExists(atPath: history.path) {
                    try JSONEncoder().encode(previous).write(to: history, options: .withoutOverwriting)
                    try fileManager.setAttributes([.posixPermissions: 0o600], ofItemAtPath: history.path)
                }
            }
            let record = try DesktopAppUpdateTransaction(prepared: prepared, processID: processID)
            try DesktopAppUpdateTransactions.save(record, root: root)
            try scheduleHelper(root: root, id: record.id, restoring: false)
        }
    }

    /// Only a confirmed exact transaction may restore its previous application. Unknown content
    /// is never overwritten. The helper waits for this process to exit before moving anything.
    public func restorePreviousApplication(root: URL, expectedID: UUID) throws {
        try DesktopAppUpdateTransactions.withLock(root: root) {
            guard var record = try DesktopAppUpdateTransactions.load(root: root), record.id == expectedID,
                  [.recoveryRequired, .awaitingStartup, .replacing, .restoring].contains(record.state)
            else { throw DesktopAppUpdateInstallError.recoveryRequired }
            try DesktopAppUpdateTransactions.validateRestoration(record)
            record.oldPID = processID; record.state = .restoring; record.changedAt = Date()
            try DesktopAppUpdateTransactions.save(record, root: root)
            try scheduleHelper(root: root, id: record.id, restoring: true)
        }
    }

    private func scheduleHelper(root: URL, id: UUID, restoring: Bool) throws {
        let helper = Bundle.main.bundleURL.appendingPathComponent("Contents/Helpers/HermesGoUpdateHelper")
        guard fileManager.isExecutableFile(atPath: helper.path) else { throw DesktopAppUpdateInstallError.launchFailed }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = ["submit", "-l", "com.hermesgo.desktop.appupdate.\(id.uuidString).\(UUID().uuidString)", "--",
                             helper.path, root.path, id.uuidString, restoring ? "restore" : "apply"]
        process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { throw DesktopAppUpdateInstallError.launchFailed }
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw DesktopAppUpdateInstallError.launchFailed }
    }

    public func discard(_ prepared: DesktopPreparedAppUpdate) throws {
        let root = prepared.workspaceURL.deletingLastPathComponent()
        try DesktopAppUpdateTransactions.withLock(root: root) {
            if let active = try DesktopAppUpdateTransactions.load(root: root),
               active.workspace == prepared.workspaceURL { throw DesktopAppUpdateInstallError.recoveryRequired }
            guard try DesktopAppUpdateTransaction.digest(prepared.stagedAppURL) == prepared.stagedDigest else {
                throw DesktopAppUpdateInstallError.verificationFailed
            }
            try fileManager.removeItem(at: prepared.stagedAppURL)
            try fileManager.removeItem(at: prepared.workspaceURL)
        }
    }

    // MARK: - Internals

    private func attach(_ dmg: URL, at mountPoint: URL) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
        process.arguments = [
            "attach", "-readonly", "-nobrowse", "-noautoopen",
            "-mountpoint", mountPoint.path, dmg.path,
        ]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            throw DesktopAppUpdateInstallError.mountFailed
        }
        process.waitUntilExit()
        guard process.terminationStatus == 0,
              let contents = try? fileManager.contentsOfDirectory(atPath: mountPoint.path),
              !contents.isEmpty
        else { throw DesktopAppUpdateInstallError.mountFailed }
    }

    @discardableResult
    private func detach(_ mountPoint: URL) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
        process.arguments = ["detach", mountPoint.path, "-force"]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return false }
        process.waitUntilExit()
        return process.terminationStatus == 0
    }

    private func findMountedApp(
        at mountPoint: URL,
        reference: DesktopAppUpdateReference
    ) throws -> URL {
        let entries: [URL]
        do {
            entries = try fileManager.contentsOfDirectory(
                at: mountPoint,
                includingPropertiesForKeys: [.isDirectoryKey],
                options: [.skipsHiddenFiles]
            )
        } catch {
            throw DesktopAppUpdateInstallError.mountFailed
        }
        for entry in entries where entry.pathExtension == "app" {
            guard let bundle = Bundle(url: entry),
                  let version = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                  let identifier = bundle.object(forInfoDictionaryKey: "CFBundleIdentifier") as? String
            else { continue }
            guard version == reference.appVersion else {
                throw DesktopAppUpdateInstallError.bundleMismatch
            }
            if let bundleIdentifier, identifier != bundleIdentifier {
                throw DesktopAppUpdateInstallError.bundleMismatch
            }
            try verifyBundle(entry, reference: reference)
            return entry
        }
        throw DesktopAppUpdateInstallError.mountFailed
    }

    public static func identityNeedsRepair(bundle: Bundle) -> Bool {
        guard (try? DesktopDistributionConfiguration.validate(bundle: bundle)) != nil else { return true }
        let p = Process(); p.executableURL = URL(fileURLWithPath: "/usr/bin/codesign")
        p.arguments = ["--verify", "--deep", "--strict", bundle.bundleURL.path]
        p.standardOutput = FileHandle.nullDevice; p.standardError = FileHandle.nullDevice
        guard (try? p.run()) != nil else { return true }; p.waitUntilExit()
        return p.terminationStatus != 0
    }

    private func verifyBundle(_ url: URL, reference: DesktopAppUpdateReference) throws {
        guard let bundle = Bundle(url: url) else { throw DesktopAppUpdateInstallError.bundleMismatch }
        guard case .configured(let expected) = configuration,
              case .configured(let actual) = DesktopAppUpdateConfigurationState.load(bundle: bundle, environment: [:]),
              expected == actual,
              case .configured(let oldComponents) = componentConfiguration,
              case .configured(let newComponents) = DesktopComponentPreflightConfigurationState.load(bundle: bundle, environment: [:]),
              oldComponents.signingKeys.contains(where: { newComponents.signingKeys[$0.key] == $0.value }) else {
            throw DesktopAppUpdateInstallError.bundleMismatch
        }
        try DesktopDistributionConfiguration.validate(bundle: bundle, version: reference.appVersion,
                                                       build: reference.buildNumber, source: reference.sourceCommit)
        guard bundle.object(forInfoDictionaryKey: "LSMinimumSystemVersion") as? String == reference.minimumMacOS else {
            throw DesktopAppUpdateInstallError.bundleMismatch
        }
        let signature = Process(); signature.executableURL = URL(fileURLWithPath: "/usr/bin/codesign")
        signature.arguments = ["--verify", "--deep", "--strict", url.path]
        signature.standardOutput = FileHandle.nullDevice; signature.standardError = FileHandle.nullDevice
        do { try signature.run() } catch { throw DesktopAppUpdateInstallError.verificationFailed }
        signature.waitUntilExit()
        guard signature.terminationStatus == 0 else { throw DesktopAppUpdateInstallError.verificationFailed }
        let architecture = Process(), output = Pipe()
        architecture.executableURL = URL(fileURLWithPath: "/usr/bin/lipo")
        architecture.arguments = ["-archs", url.appendingPathComponent("Contents/MacOS/HermesGoDesktop").path]
        architecture.standardOutput = output; architecture.standardError = FileHandle.nullDevice
        do { try architecture.run() } catch { throw DesktopAppUpdateInstallError.verificationFailed }
        let data = output.fileHandleForReading.readDataToEndOfFile(); architecture.waitUntilExit()
        let configured = bundle.object(forInfoDictionaryKey: "HermesGoDesktopAppUpdateArchitecture") as? String
        let values = Set((String(data: data, encoding: .utf8) ?? "").split(whereSeparator: { $0.isWhitespace }).map(String.init))
        guard architecture.terminationStatus == 0,
              configured == "universal" ? values == Set(["arm64", "x86_64"]) : values == Set([configured ?? ""]) else {
            throw DesktopAppUpdateInstallError.bundleMismatch
        }
    }

    private func stage(_ mountedApp: URL, destinationAppURL: URL) throws -> URL {
        let parent = destinationAppURL.deletingLastPathComponent()
        let staged = parent.appendingPathComponent(
            ".HermesGoDesktopUpdate-\(UUID().uuidString).app",
            isDirectory: true
        )
        do {
            try fileManager.copyItem(at: mountedApp, to: staged)
        } catch {
            try? fileManager.removeItem(at: staged)
            throw DesktopAppUpdateInstallError.stagingFailed
        }
        return staged
    }

    private static func validDestination(_ value: URL) -> Bool {
        guard value.isFileURL,
              value.path.hasPrefix("/"),
              value.path != "/",
              value.pathExtension == "app",
              value.standardizedFileURL.path == value.resolvingSymlinksInPath().path,
              !value.pathComponents.contains("..")
        else { return false }
        return true
    }

    private static func createPrivateDirectory(_ value: URL, fileManager: FileManager) throws {
        if !fileManager.fileExists(atPath: value.path) {
            try fileManager.createDirectory(
                at: value,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
        }
    }

    private static func sha256(ofFileAt url: URL) throws -> String {
        guard let handle = try? FileHandle(forReadingFrom: url) else {
            throw DesktopAppUpdateInstallError.verificationFailed
        }
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            guard let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty else {
                break
            }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func parseOperatingSystemVersion(_ value: String) -> OperatingSystemVersion? {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (2...3).contains(parts.count),
              parts.allSatisfy({ Int($0) != nil })
        else { return nil }
        return OperatingSystemVersion(
            majorVersion: Int(parts[0])!,
            minorVersion: Int(parts[1])!,
            patchVersion: parts.count == 3 ? Int(parts[2])! : 0
        )
    }

    private static func compare(
        _ lhs: OperatingSystemVersion,
        _ rhs: OperatingSystemVersion
    ) -> ComparisonResult {
        let left = [lhs.majorVersion, lhs.minorVersion, lhs.patchVersion]
        let right = [rhs.majorVersion, rhs.minorVersion, rhs.patchVersion]
        for (a, b) in zip(left, right) {
            if a < b { return .orderedAscending }
            if a > b { return .orderedDescending }
        }
        return .orderedSame
    }
}

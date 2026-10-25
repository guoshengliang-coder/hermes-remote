import CryptoKit
import Darwin
import Foundation

/// Installed bundle bytes matched to a supplied original DMG identity. This is an observation,
/// not a recommendation, signature attestation or proof about an already-running process.
public struct DesktopAppIdentityObservation: Equatable, Sendable {
    public let component: HermesCompatibilityComponent
    public let contentSHA256: String
    public let buildNumber: String
    fileprivate init(component: HermesCompatibilityComponent, contentSHA256: String, buildNumber: String) {
        self.component = component; self.contentSHA256 = contentSHA256; self.buildNumber = buildNumber
    }
}

/// The caller must obtain `expected` from its verified compatibility record. No package code is
/// executed, and no receipt or installation file is written. Current packages have no bundle links;
/// unsupported trees fail closed through the existing component content hasher.
public struct DesktopAppIdentityInspector {
    private let runner: CommandRunning
    private let fileManager: FileManager
    public init(runner: CommandRunning = SystemCommandRunner(), fileManager: FileManager = .default) {
        self.runner = runner; self.fileManager = fileManager
    }

    public func inspect(expected: HermesCompatibilityComponent, archive: URL, installedApp: URL,
                        workspaceRoot: URL) throws -> DesktopAppIdentityObservation {
        var stage = "validate_input"
        do {
            guard expected.name == "desktop", HermesCompatibilitySnapshot.version(expected.version),
                  expected.artifactSha256.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil,
                  installedApp.pathExtension == "app", archive.pathExtension == "dmg"
            else { throw Self.issue }
            try requireDirectory(installedApp, privateWorkspace: false)
            try requireDirectory(workspaceRoot, privateWorkspace: true)
            let app = installedApp.standardizedFileURL
            let workspace = workspaceRoot.standardizedFileURL
            guard app != workspace, !workspace.path.hasPrefix(app.path + "/"),
                  !app.path.hasPrefix(workspace.path + "/") else { throw Self.issue }
            let hasher = DesktopManagedComponentContentHasher(fileManager: fileManager)
            stage = "inspect_installation"
            let before = try hasher.identify(directory: app)
            let build = try bundleBuild(app, version: expected.version)
            stage = "verify_archive"
            try verifyArchive(archive, sha256: expected.artifactSha256)
            stage = "prepare_reference"
            let run = workspace.appendingPathComponent(UUID().uuidString.lowercased(), isDirectory: true)
            try fileManager.createDirectory(at: run, withIntermediateDirectories: false,
                                            attributes: [.posixPermissions: 0o700])
            let mount = run.appendingPathComponent("mount", isDirectory: true)
            var mountAttempted = false
            var detached = false
            defer {
                // Never recursively remove a mount when detach failed. Retain only this private
                // reference workspace for diagnosis; installed files are never cleanup targets.
                if !mountAttempted || detached { try? fileManager.removeItem(at: run) }
            }
            do {
                let image = run.appendingPathComponent("reference.dmg")
                try fileManager.copyItem(at: archive, to: image)
                stage = "verify_reference"
                try verifyArchive(image, sha256: expected.artifactSha256)
                try fileManager.createDirectory(at: mount, withIntermediateDirectories: false,
                                                attributes: [.posixPermissions: 0o700])
                stage = "mount_reference"
                mountAttempted = true
                guard command(["attach", "-readonly", "-nobrowse", "-noautoopen", "-mountpoint", mount.path, image.path])
                else { throw Self.issue }
                stage = "compare_content"
                let apps = try fileManager.contentsOfDirectory(at: mount, includingPropertiesForKeys: nil)
                    .filter { $0.pathExtension == "app" }
                guard apps.count == 1 else { throw Self.issue }
                let source = apps[0]
                let reference = try hasher.identify(directory: source)
                guard try bundleBuild(source, version: expected.version) == build,
                      reference == before,
                      try hasher.identify(directory: app) == reference else { throw Self.issue }
                try requireDirectory(app, privateWorkspace: false)
                stage = "detach_reference"
                detached = command(["detach", mount.path])
                guard detached else { throw Self.issue }
                stage = "cleanup_reference"
                try fileManager.removeItem(at: run)
                return DesktopAppIdentityObservation(component: expected, contentSHA256: reference.sha256, buildNumber: build)
            } catch {
                if mountAttempted && !detached && stage != "detach_reference" {
                    detached = command(["detach", mount.path])
                    if !detached { stage = "detach_reference" }
                }
                throw error
            }
        } catch {
            // No command stderr, filesystem error text or private paths enter diagnostics.
            throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid,
                               technicalCause: "desktop_app_identity=\(stage)")
        }
    }

    private func command(_ arguments: [String]) -> Bool {
        let result = runner.run(executable: URL(fileURLWithPath: "/usr/bin/hdiutil"), arguments: arguments)
        return result.status == 0
    }

    private func requireDirectory(_ url: URL, privateWorkspace: Bool) throws {
        let normalized = url.standardizedFileURL
        var info = stat()
        guard normalized.isFileURL, normalized.path != "/",
              normalized == normalized.resolvingSymlinksInPath(), Darwin.lstat(normalized.path, &info) == 0,
              info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o022 == 0,
              info.st_uid == Darwin.getuid() || (!privateWorkspace && info.st_uid == 0)
        else { throw Self.issue }
    }

    private func verifyArchive(_ url: URL, sha256: String) throws {
        let normalized = url.standardizedFileURL
        var info = stat()
        guard normalized.isFileURL, normalized == normalized.resolvingSymlinksInPath(),
              Darwin.lstat(normalized.path, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_size > 0, info.st_size <= 2 * 1024 * 1024 * 1024 else { throw Self.issue }
        let handle = try FileHandle(forReadingFrom: normalized)
        defer { try? handle.close() }
        var digest = SHA256(), count: Int64 = 0
        while let chunk = try handle.read(upToCount: 1024 * 1024), !chunk.isEmpty {
            count += Int64(chunk.count)
            guard count <= info.st_size else { throw Self.issue }
            digest.update(data: chunk)
        }
        guard count == info.st_size, digest.finalize().map({ String(format: "%02x", $0) }).joined() == sha256
        else { throw Self.issue }
    }

    private func bundleBuild(_ app: URL, version: String) throws -> String {
        let info = app.appendingPathComponent("Contents/Info.plist")
        let values = try info.resourceValues(forKeys: [.fileSizeKey])
        guard let size = values.fileSize, size > 0, size <= 1024 * 1024,
              let plist = try PropertyListSerialization.propertyList(from: Data(contentsOf: info), format: nil) as? [String: Any],
              plist["CFBundleIdentifier"] as? String == "com.hermesgo.desktop",
              plist["CFBundleShortVersionString"] as? String == version,
              let build = plist["CFBundleVersion"] as? String, let number = Int(build), number > 0,
              String(number) == build,
              let executable = plist["CFBundleExecutable"] as? String,
              executable.range(of: #"\A[A-Za-z0-9_-]{1,128}\z"#, options: .regularExpression) != nil
        else { throw Self.issue }
        var executableInfo = stat()
        guard Darwin.lstat(app.appendingPathComponent("Contents/MacOS/" + executable).path, &executableInfo) == 0,
              executableInfo.st_mode & S_IFMT == S_IFREG, executableInfo.st_mode & 0o111 != 0 else { throw Self.issue }
        return build
    }

    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}

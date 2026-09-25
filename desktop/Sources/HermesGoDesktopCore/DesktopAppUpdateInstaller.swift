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
}

/// A fully downloaded, digest-checked, and staged replacement app. Nothing has been changed on disk
/// yet: `commit` is the only step that schedules the swap.
public struct DesktopPreparedAppUpdate: Equatable, Sendable {
    public let reference: DesktopAppUpdateReference
    public let destinationAppURL: URL
    public let stagedAppURL: URL
    public let workspaceURL: URL
    public let mountPointURL: URL
}

/// Installs a new Desktop app from the index's signed-off DMG. Internal-test builds are ad-hoc signed,
/// so this is a best-effort in-place replacement, not a notarized auto-updater: the caller must have
/// shown the exact version and notes before `commit`.
public final class DesktopAppUpdateInstaller: @unchecked Sendable {
    private let downloader: DesktopReleaseDownloader
    private let fileManager: FileManager
    private let bundleIdentifier: String?
    private let processID: Int32

    public init(
        downloader: DesktopReleaseDownloader = DesktopReleaseDownloader(),
        fileManager: FileManager = .default,
        bundleIdentifier: String? = Bundle.main.bundleIdentifier,
        processID: Int32 = getpid()
    ) {
        self.downloader = downloader
        self.fileManager = fileManager
        self.bundleIdentifier = bundleIdentifier
        self.processID = processID
    }

    public func prepare(
        reference: DesktopAppUpdateReference,
        destinationAppURL: URL,
        workspaceRoot: URL,
        currentMacOS: OperatingSystemVersion = ProcessInfo.processInfo.operatingSystemVersion
    ) async throws -> DesktopPreparedAppUpdate {
        guard Self.validDestination(destinationAppURL) else {
            throw DesktopAppUpdateInstallError.invalidRequest
        }
        if let minimum = Self.parseOperatingSystemVersion(reference.minimumMacOS),
           Self.compare(currentMacOS, minimum) == .orderedAscending {
            throw DesktopAppUpdateInstallError.incompatibleSystem
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
            let mountedApp: URL
            do {
                mountedApp = try findMountedApp(at: mountPoint, reference: reference)
            } catch {
                detach(mountPoint)
                throw error
            }
            let staged = try stage(
                mountedApp,
                destinationAppURL: destinationAppURL
            )
            detach(mountPoint)
            return DesktopPreparedAppUpdate(
                reference: reference,
                destinationAppURL: destinationAppURL,
                stagedAppURL: staged,
                workspaceURL: workspace,
                mountPointURL: mountPoint
            )
        } catch let error as DesktopAppUpdateInstallError {
            try? fileManager.removeItem(at: workspace)
            throw error
        } catch {
            try? fileManager.removeItem(at: workspace)
            throw DesktopAppUpdateInstallError.downloadFailed
        }
    }

    /// Schedules the swap to run after this process exits, then returns. The caller is expected to
    /// terminate the app so the helper can replace it and relaunch.
    public func commit(_ prepared: DesktopPreparedAppUpdate) throws {
        let script = prepared.workspaceURL.appendingPathComponent("apply-update.sh")
        guard let data = Self.helperScript.data(using: .utf8) else {
            throw DesktopAppUpdateInstallError.launchFailed
        }
        do {
            try data.write(to: script, options: [.atomic])
            try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: script.path)
        } catch {
            throw DesktopAppUpdateInstallError.launchFailed
        }

        let label = "com.hermesgo.desktop.appupdate.\(UUID().uuidString)"
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = [
            "submit", "-l", label, "--",
            "/bin/sh", script.path,
            String(processID),
            prepared.destinationAppURL.path,
            prepared.stagedAppURL.path,
            prepared.workspaceURL.path,
        ]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
        } catch {
            throw DesktopAppUpdateInstallError.launchFailed
        }
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw DesktopAppUpdateInstallError.launchFailed
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

    private func detach(_ mountPoint: URL) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/hdiutil")
        process.arguments = ["detach", mountPoint.path, "-force"]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        guard (try? process.run()) != nil else { return }
        process.waitUntilExit()
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
            return entry
        }
        throw DesktopAppUpdateInstallError.mountFailed
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
            throw DesktopAppUpdateInstallError.stagingFailed
        }
        return staged
    }

    static let helperScript = """
    #!/bin/sh
    set -eu
    pid="$1"
    dest="$2"
    staged="$3"
    work="$4"
    while kill -0 "$pid" 2>/dev/null; do
      sleep 0.5
    done
    backup="${dest}.previous-$$"
    rm -rf "$backup"
    if [ -e "$dest" ]; then
      mv "$dest" "$backup"
    fi
    if mv "$staged" "$dest"; then
      xattr -dr com.apple.quarantine "$dest" 2>/dev/null || true
      rm -rf "$backup"
    else
      if [ -e "$backup" ]; then
        mv "$backup" "$dest" 2>/dev/null || true
      fi
    fi
    open "$dest" >/dev/null 2>&1 || true
    rm -rf "$work"
    """

    private static func validDestination(_ value: URL) -> Bool {
        guard value.isFileURL,
              value.path.hasPrefix("/"),
              value.path != "/",
              value.pathExtension == "app",
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

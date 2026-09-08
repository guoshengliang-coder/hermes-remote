import CryptoKit
import Foundation

public enum DesktopArtifactVerificationError: Error, Equatable, Sendable {
    case invalidRoot
    case outsideManagedRoot
    case unsafeFile
    case identityMismatch
    case sizeMismatch
    case digestMismatch
    case readFailed
}

public struct VerifiedDesktopArtifact: Equatable, Sendable {
    public let metadata: DesktopReleaseArtifact
    public let fileURL: URL
}

public struct DesktopArtifactVerifier: Sendable {
    private let downloadRoot: URL

    public init(downloadRoot: URL) throws {
        let root = downloadRoot.standardizedFileURL.resolvingSymlinksInPath()
        guard root.isFileURL, root.path.hasPrefix("/"), root.path != "/" else {
            throw DesktopArtifactVerificationError.invalidRoot
        }
        self.downloadRoot = root
    }

    public func verify(
        fileURL: URL,
        metadata: DesktopReleaseArtifact
    ) throws -> VerifiedDesktopArtifact {
        let standardized = fileURL.standardizedFileURL
        let resolved = standardized.resolvingSymlinksInPath()
        guard standardized == resolved,
              resolved.deletingLastPathComponent() == downloadRoot,
              resolved.lastPathComponent == metadata.fileName
        else { throw DesktopArtifactVerificationError.outsideManagedRoot }
        let values: URLResourceValues
        do {
            values = try resolved.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey,
            ])
        } catch {
            throw DesktopArtifactVerificationError.readFailed
        }
        guard values.isRegularFile == true, values.isSymbolicLink != true else {
            throw DesktopArtifactVerificationError.unsafeFile
        }
        guard Int64(values.fileSize ?? -1) == metadata.sizeBytes else {
            throw DesktopArtifactVerificationError.sizeMismatch
        }
        let digest = try sha256(resolved)
        guard digest == metadata.sha256 else {
            throw DesktopArtifactVerificationError.digestMismatch
        }
        return VerifiedDesktopArtifact(metadata: metadata, fileURL: resolved)
    }

    private func sha256(_ url: URL) throws -> String {
        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            throw DesktopArtifactVerificationError.readFailed
        }
        defer { try? handle.close() }
        var digest = SHA256()
        do {
            while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
                digest.update(data: data)
            }
        } catch {
            throw DesktopArtifactVerificationError.readFailed
        }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

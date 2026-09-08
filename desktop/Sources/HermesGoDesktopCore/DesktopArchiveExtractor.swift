import Darwin
import Foundation

public struct DesktopCommandOutput: Equatable, Sendable {
    public let status: Int32
    public let stdout: Data
    public let outputLimitExceeded: Bool
}

public protocol OutputCommandRunning {
    func run(executable: URL, arguments: [String], maximumOutputBytes: Int) -> DesktopCommandOutput
}

public struct SystemOutputCommandRunner: OutputCommandRunning {
    public init() {}

    public func run(
        executable: URL,
        arguments: [String],
        maximumOutputBytes: Int
    ) -> DesktopCommandOutput {
        let process = Process()
        let pipe = Pipe()
        process.executableURL = executable
        process.arguments = arguments
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do { try process.run() }
        catch { return DesktopCommandOutput(status: -1, stdout: Data(), outputLimitExceeded: false) }

        var output = Data()
        var exceeded = false
        while let chunk = try? pipe.fileHandleForReading.read(upToCount: 64 * 1024),
              !chunk.isEmpty {
            if output.count + chunk.count <= maximumOutputBytes {
                output.append(chunk)
            } else {
                exceeded = true
            }
        }
        process.waitUntilExit()
        return DesktopCommandOutput(
            status: process.terminationStatus,
            stdout: output,
            outputLimitExceeded: exceeded
        )
    }
}

public enum DesktopArchiveExtractionError: Error, Equatable, Sendable {
    case invalidDestination
    case unsafeArchive
    case listingFailed
    case extractionFailed
}

public struct DesktopTarArchiveExtractor<Runner: OutputCommandRunning> {
    private static var maximumListingBytes: Int { 1024 * 1024 }
    private static var maximumEntries: Int { 4096 }

    private let runner: Runner
    private let fileManager: FileManager
    private let tar = URL(fileURLWithPath: "/usr/bin/tar")

    public init(runner: Runner, fileManager: FileManager = .default) {
        self.runner = runner
        self.fileManager = fileManager
    }

    public func extract(
        _ artifact: VerifiedDesktopArtifact,
        into destinationRoot: URL,
        runID: String
    ) throws -> DesktopManagedReleaseSource {
        guard let normalizedRunID = UUID(uuidString: runID)?.uuidString.lowercased() else {
            throw DesktopArchiveExtractionError.invalidDestination
        }
        let root = destinationRoot.standardizedFileURL.resolvingSymlinksInPath()
        guard root.isFileURL, root.path.hasPrefix("/"), root.path != "/" else {
            throw DesktopArchiveExtractionError.invalidDestination
        }
        try ensurePrivateRoot(root)
        let destination = root.appendingPathComponent(
            "\(normalizedRunID)-\(artifact.metadata.component.rawValue)",
            isDirectory: true
        )
        guard !fileManager.fileExists(atPath: destination.path) else {
            throw DesktopArchiveExtractionError.invalidDestination
        }

        try validateArchiveTable(artifact.fileURL)
        do {
            try fileManager.createDirectory(
                at: destination,
                withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700]
            )
            let extraction = runner.run(
                executable: tar,
                arguments: [
                    "-xzf", artifact.fileURL.path,
                    "-C", destination.path,
                    "--no-same-owner", "--no-same-permissions",
                ],
                maximumOutputBytes: 1
            )
            guard extraction.status == 0 else {
                throw DesktopArchiveExtractionError.extractionFailed
            }
            let entrypoint = destination.appendingPathComponent(artifact.metadata.entrypoint)
            guard let values = try? entrypoint.resourceValues(forKeys: [
                .isRegularFileKey, .isSymbolicLinkKey,
            ]), values.isRegularFile == true, values.isSymbolicLink != true else {
                throw DesktopArchiveExtractionError.unsafeArchive
            }
            try fileManager.setAttributes(
                [.posixPermissions: 0o700],
                ofItemAtPath: entrypoint.path
            )
            return DesktopManagedReleaseSource(
                component: artifact.metadata.component,
                directory: destination
            )
        } catch let error as DesktopArchiveExtractionError {
            try? fileManager.removeItem(at: destination)
            throw error
        } catch {
            try? fileManager.removeItem(at: destination)
            throw DesktopArchiveExtractionError.extractionFailed
        }
    }

    private func validateArchiveTable(_ archive: URL) throws {
        let namesResult = runner.run(
            executable: tar,
            arguments: ["-tzf", archive.path],
            maximumOutputBytes: Self.maximumListingBytes
        )
        let typesResult = runner.run(
            executable: tar,
            arguments: ["-tvzf", archive.path],
            maximumOutputBytes: Self.maximumListingBytes
        )
        guard namesResult.status == 0,
              typesResult.status == 0,
              !namesResult.outputLimitExceeded,
              !typesResult.outputLimitExceeded,
              let namesText = String(data: namesResult.stdout, encoding: .utf8),
              let typesText = String(data: typesResult.stdout, encoding: .utf8)
        else { throw DesktopArchiveExtractionError.listingFailed }
        let names = namesText.split(whereSeparator: \.isNewline).map(String.init)
        let typeLines = typesText.split(whereSeparator: \.isNewline)
        let normalizedNames = names.compactMap(normalizedMemberName)
        guard !names.isEmpty,
              names.count <= Self.maximumEntries,
              names.count == typeLines.count,
              normalizedNames.count == names.count,
              Set(normalizedNames).count == normalizedNames.count
        else { throw DesktopArchiveExtractionError.unsafeArchive }
        for typeLine in typeLines {
            guard let type = typeLine.first,
                  type == "-" || type == "d"
            else { throw DesktopArchiveExtractionError.unsafeArchive }
        }
    }

    private func normalizedMemberName(_ value: String) -> String? {
        guard !value.isEmpty,
              value.utf8.count <= 512,
              !value.hasPrefix("/"),
              !value.contains("\\"),
              !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
        else { return nil }
        var normalized = value.hasSuffix("/") ? String(value.dropLast()) : value
        while normalized.hasPrefix("./") { normalized.removeFirst(2) }
        if normalized == "." { return "." }
        let parts = normalized.split(separator: "/", omittingEmptySubsequences: false)
        guard !parts.isEmpty,
              parts.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." })
        else { return nil }
        return normalized
    }

    private func ensurePrivateRoot(_ root: URL) throws {
        if !fileManager.fileExists(atPath: root.path) {
            do {
                try fileManager.createDirectory(
                    at: root,
                    withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700]
                )
            } catch { throw DesktopArchiveExtractionError.invalidDestination }
        }
        guard let values = try? root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]),
              values.isDirectory == true,
              values.isSymbolicLink != true,
              let attributes = try? fileManager.attributesOfItem(atPath: root.path),
              let owner = attributes[.ownerAccountID] as? NSNumber,
              owner.uint32Value == Darwin.getuid()
        else { throw DesktopArchiveExtractionError.invalidDestination }
        try fileManager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
    }
}

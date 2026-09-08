import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopReleaseAcquirerTests: XCTestCase {
    func testAcquiresBothComponentsInCanonicalOrderAndDiscardsWorkspace() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let downloader = FixtureDownloader(artifacts: fixture.archiveData)
        let acquirer = DesktopReleaseAcquirer(
            downloader: downloader,
            manifestVerifier: FixtureManifestVerifier(manifest: fixture.manifest)
        )
        let runID = "10000000-0000-4000-8000-000000000001"
        try FileManager.default.removeItem(at: fixture.workspace)

        let release = try await acquirer.acquire(
            manifestURL: URL(string: "https://downloads.example/desktop/manifest.json")!,
            workspaceRoot: fixture.workspace,
            runID: runID
        )

        XCTAssertEqual(release.manifest, fixture.manifest)
        XCTAssertEqual(release.sources.map(\.component), [.hermesServer, .connector])
        let requestedComponents = await downloader.requestedComponents()
        XCTAssertEqual(requestedComponents, [.hermesServer, .connector])
        for source in release.sources {
            let artifact = try XCTUnwrap(fixture.manifest.artifacts.first {
                $0.component == source.component
            })
            XCTAssertTrue(FileManager.default.isExecutableFile(
                atPath: source.directory.appendingPathComponent(artifact.entrypoint).path
            ))
        }

        try acquirer.discard(release)
        XCTAssertFalse(FileManager.default.fileExists(atPath: release.workspaceDirectory.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixture.workspace.path))
    }

    func testDigestFailureRemovesPartialDownloadsAndExtractions() async throws {
        let fixture = try Fixture(corruptConnectorDigest: true)
        defer { fixture.remove() }
        let runID = "20000000-0000-4000-8000-000000000002"
        let runRoot = fixture.workspace.appendingPathComponent(runID)
        let acquirer = DesktopReleaseAcquirer(
            downloader: FixtureDownloader(artifacts: fixture.archiveData),
            manifestVerifier: FixtureManifestVerifier(manifest: fixture.manifest)
        )

        do {
            _ = try await acquirer.acquire(
                manifestURL: URL(string: "https://downloads.example/desktop/manifest.json")!,
                workspaceRoot: fixture.workspace,
                runID: runID
            )
            XCTFail("Expected digest rejection")
        } catch {
            XCTAssertEqual(error as? DesktopArtifactVerificationError, .digestMismatch)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: runRoot.path))
    }

    func testRejectsSymlinkWorkspaceAndExistingRunWithoutMutation() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let acquirer = DesktopReleaseAcquirer(
            downloader: FixtureDownloader(artifacts: fixture.archiveData),
            manifestVerifier: FixtureManifestVerifier(manifest: fixture.manifest)
        )
        let link = fixture.root.appendingPathComponent("workspace-link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: fixture.workspace)

        await XCTAssertThrowsErrorAsync(try await acquirer.acquire(
            manifestURL: URL(string: "https://downloads.example/desktop/manifest.json")!,
            workspaceRoot: link,
            runID: "30000000-0000-4000-8000-000000000003"
        )) { error in
            XCTAssertEqual(error as? DesktopReleaseAcquisitionError, .invalidWorkspace)
        }

        let existingRunID = "40000000-0000-4000-8000-000000000004"
        let existing = fixture.workspace.appendingPathComponent(existingRunID)
        try FileManager.default.createDirectory(at: existing, withIntermediateDirectories: false)
        await XCTAssertThrowsErrorAsync(try await acquirer.acquire(
            manifestURL: URL(string: "https://downloads.example/desktop/manifest.json")!,
            workspaceRoot: fixture.workspace,
            runID: existingRunID
        )) { error in
            XCTAssertEqual(error as? DesktopReleaseAcquisitionError, .workspaceAlreadyExists)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: existing.path))
    }

    func testRejectsAnIncompleteOrDuplicateComponentSetBeforeDownloading() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let first = try XCTUnwrap(fixture.manifest.artifacts.first)
        let invalidManifest = DesktopReleaseManifest(
            releaseVersion: fixture.manifest.releaseVersion,
            channel: fixture.manifest.channel,
            architecture: fixture.manifest.architecture,
            minimumMacOS: fixture.manifest.minimumMacOS,
            createdAt: fixture.manifest.createdAt,
            expiresAt: fixture.manifest.expiresAt,
            artifacts: [first, first]
        )
        let downloader = FixtureDownloader(artifacts: fixture.archiveData)
        let runID = "50000000-0000-4000-8000-000000000005"
        let acquirer = DesktopReleaseAcquirer(
            downloader: downloader,
            manifestVerifier: FixtureManifestVerifier(manifest: invalidManifest)
        )

        await XCTAssertThrowsErrorAsync(try await acquirer.acquire(
            manifestURL: URL(string: "https://downloads.example/desktop/manifest.json")!,
            workspaceRoot: fixture.workspace,
            runID: runID
        )) { error in
            XCTAssertEqual(error as? DesktopReleaseAcquisitionError, .incompleteManifest)
        }
        let requestedComponents = await downloader.requestedComponents()
        XCTAssertEqual(requestedComponents, [])
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
    }
}

private actor FixtureDownloader: DesktopReleaseDownloading {
    private let artifacts: [DesktopReleaseComponentKind: Data]
    private var requested: [DesktopReleaseComponentKind] = []

    init(artifacts: [DesktopReleaseComponentKind: Data]) {
        self.artifacts = artifacts
    }

    func fetchManifest(from url: URL) async throws -> Data {
        Data("signed-envelope".utf8)
    }

    func download(_ artifact: DesktopReleaseArtifact, into downloadRoot: URL) async throws -> URL {
        requested.append(artifact.component)
        try FileManager.default.createDirectory(
            at: downloadRoot,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let destination = downloadRoot.appendingPathComponent(artifact.fileName)
        try artifacts[artifact.component]!.write(to: destination)
        return destination
    }

    func requestedComponents() -> [DesktopReleaseComponentKind] { requested }
}

private struct FixtureManifestVerifier: DesktopReleaseManifestVerifying {
    let manifest: DesktopReleaseManifest

    func verify(_ envelopeData: Data) throws -> DesktopReleaseManifest {
        guard envelopeData == Data("signed-envelope".utf8) else {
            throw DesktopReleaseVerificationError.invalidEnvelope
        }
        return manifest
    }
}

private final class Fixture {
    let root: URL
    let workspace: URL
    let manifest: DesktopReleaseManifest
    let archiveData: [DesktopReleaseComponentKind: Data]

    init(corruptConnectorDigest: Bool = false) throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-release-acquirer-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
        workspace = root.appendingPathComponent("workspace", isDirectory: true)
        try FileManager.default.createDirectory(
            at: workspace,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )

        var artifacts: [DesktopReleaseArtifact] = []
        var dataByComponent: [DesktopReleaseComponentKind: Data] = [:]
        for component in DesktopReleaseComponentKind.allCases {
            let version = component == .hermesServer ? "0.20.6" : "0.2.0"
            let executable = component == .hermesServer ? "hermes-server" : "hermes-connector"
            let fileName = component == .hermesServer
                ? "Hermes-Server-\(version)-arm64.tar.gz"
                : "Hermes-Connector-\(version)-arm64.tar.gz"
            let archive = root.appendingPathComponent(fileName)
            try Self.createArchive(at: archive, executable: executable, root: root)
            let data = try Data(contentsOf: archive)
            dataByComponent[component] = data
            let actualDigest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            artifacts.append(DesktopReleaseArtifact(
                component: component,
                version: version,
                fileName: fileName,
                entrypoint: "bin/\(executable)",
                downloadURL: "https://downloads.example/desktop/releases/\(fileName)",
                sizeBytes: Int64(data.count),
                sha256: corruptConnectorDigest && component == .connector
                    ? String(repeating: "0", count: 64)
                    : actualDigest
            ))
        }
        archiveData = dataByComponent
        manifest = DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-08T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            artifacts: Array(artifacts.reversed())
        )
    }

    func remove() {
        try? FileManager.default.removeItem(at: root)
    }

    private static func createArchive(at archive: URL, executable: String, root: URL) throws {
        let source = root.appendingPathComponent("source-\(executable)", isDirectory: true)
        let bin = source.appendingPathComponent("bin", isDirectory: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        try Data(executable.utf8).write(to: bin.appendingPathComponent(executable))
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
        process.arguments = ["-czf", archive.path, "-C", source.path, "."]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw DesktopArchiveExtractionError.extractionFailed
        }
        try FileManager.default.removeItem(at: source)
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ errorHandler: (Error) -> Void = { _ in },
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected error", file: file, line: line)
    } catch {
        errorHandler(error)
    }
}

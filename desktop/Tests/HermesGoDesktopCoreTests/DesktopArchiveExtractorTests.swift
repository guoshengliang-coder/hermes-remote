import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopArchiveExtractorTests: XCTestCase {
    func testExtractsRegularFilesIntoAUniquePrivateDirectory() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source")
        let bin = source.appendingPathComponent("bin")
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        try Data("executable".utf8).write(to: bin.appendingPathComponent("hermes-connector"))
        let archive = root.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        try createArchive(source: source, archive: archive)
        let verified = verifiedArtifact(archive)

        let result = try DesktopTarArchiveExtractor(runner: SystemOutputCommandRunner()).extract(
            verified,
            into: root.appendingPathComponent("extracted"),
            runID: "10000000-0000-4000-8000-000000000001"
        )

        XCTAssertEqual(result.component, .connector)
        XCTAssertEqual(
            try String(contentsOf: result.directory.appendingPathComponent("bin/hermes-connector")),
            "executable"
        )
        let entrypointAttributes = try FileManager.default.attributesOfItem(
            atPath: result.directory.appendingPathComponent("bin/hermes-connector").path
        )
        XCTAssertEqual((entrypointAttributes[.posixPermissions] as? NSNumber)?.intValue, 0o700)
        let attributes = try FileManager.default.attributesOfItem(atPath: result.directory.deletingLastPathComponent().path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o700)
    }

    func testRejectsSymlinkMembersBeforeExtraction() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source")
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(
            at: source.appendingPathComponent("escape"),
            withDestinationURL: URL(fileURLWithPath: "/tmp")
        )
        let archive = root.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        try createArchive(source: source, archive: archive)
        let extractionRoot = root.appendingPathComponent("extracted")

        XCTAssertThrowsError(try DesktopTarArchiveExtractor(runner: SystemOutputCommandRunner()).extract(
            verifiedArtifact(archive),
            into: extractionRoot,
            runID: "10000000-0000-4000-8000-000000000001"
        )) { error in
            XCTAssertEqual(error as? DesktopArchiveExtractionError, .unsafeArchive)
        }
        XCTAssertEqual((try? FileManager.default.contentsOfDirectory(atPath: extractionRoot.path)) ?? [], [])
    }

    func testAcceptsARealisticDependencyTreeBeyondTheOldEntryLimit() throws {
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("source")
        let packages = source.appendingPathComponent("packages")
        let bin = source.appendingPathComponent("bin")
        try FileManager.default.createDirectory(at: packages, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        try Data("executable".utf8).write(to: bin.appendingPathComponent("hermes-connector"))
        for index in 0..<4_100 {
            FileManager.default.createFile(
                atPath: packages.appendingPathComponent(String(format: "%04d", index)).path,
                contents: Data()
            )
        }
        let archive = root.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        try createArchive(source: source, archive: archive)

        let result = try DesktopTarArchiveExtractor(runner: SystemOutputCommandRunner()).extract(
            verifiedArtifact(archive),
            into: root.appendingPathComponent("extracted"),
            runID: "10000000-0000-4000-8000-000000000002"
        )

        XCTAssertTrue(FileManager.default.fileExists(
            atPath: result.directory.appendingPathComponent("packages/4099").path
        ))
    }

    func testRejectsAnArchiveBeyondTheNewBoundBeforeExtraction() throws {
        let names = (0...65_536).map { "f\($0)" }.joined(separator: "\n") + "\n"
        let types = String(repeating: "-\n", count: 65_537)
        let runner = ListingOnlyRunner(outputs: [
            DesktopCommandOutput(status: 0, stdout: Data(names.utf8), outputLimitExceeded: false),
            DesktopCommandOutput(status: 0, stdout: Data(types.utf8), outputLimitExceeded: false),
        ])
        let root = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let archive = root.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        try Data("placeholder".utf8).write(to: archive)

        XCTAssertThrowsError(try DesktopTarArchiveExtractor(runner: runner).extract(
            verifiedArtifact(archive),
            into: root.appendingPathComponent("extracted"),
            runID: "10000000-0000-4000-8000-000000000003"
        )) { error in
            XCTAssertEqual(error as? DesktopArchiveExtractionError, .unsafeArchive)
        }
        XCTAssertEqual(runner.callCount, 2)
    }

    private func createArchive(source: URL, archive: URL) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
        process.arguments = ["-czf", archive.path, "-C", source.path, "."]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
    }

    private func verifiedArtifact(_ archive: URL) -> VerifiedDesktopArtifact {
        let data = try! Data(contentsOf: archive)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        return VerifiedDesktopArtifact(
            metadata: DesktopReleaseArtifact(
                component: .connector,
                version: "0.2.0",
                fileName: archive.lastPathComponent,
                entrypoint: "bin/hermes-connector",
                downloadURL: "https://downloads.example/\(archive.lastPathComponent)",
                sizeBytes: Int64(data.count),
                sha256: digest
            ),
            fileURL: archive
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-archive-\(UUID().uuidString)", isDirectory: true)
            .resolvingSymlinksInPath()
    }
}

private final class ListingOnlyRunner: OutputCommandRunning {
    private var outputs: [DesktopCommandOutput]
    private(set) var callCount = 0

    init(outputs: [DesktopCommandOutput]) {
        self.outputs = outputs
    }

    func run(executable: URL, arguments: [String], maximumOutputBytes: Int) -> DesktopCommandOutput {
        defer { callCount += 1 }
        return outputs.removeFirst()
    }
}

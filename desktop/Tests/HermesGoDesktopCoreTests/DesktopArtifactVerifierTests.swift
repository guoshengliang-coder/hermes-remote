import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopArtifactVerifierTests: XCTestCase {
    func testArtifactRequiresExactManagedFileSizeAndDigest() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let canonicalRoot = root.resolvingSymlinksInPath()
        let data = Data("verified artifact".utf8)
        let file = canonicalRoot.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        try data.write(to: file)
        let metadata = artifact(
            fileName: file.lastPathComponent,
            size: Int64(data.count),
            digest: SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        )

        let verified = try DesktopArtifactVerifier(downloadRoot: canonicalRoot)
            .verify(fileURL: file, metadata: metadata)

        XCTAssertEqual(verified.fileURL, file)
        XCTAssertThrowsError(try DesktopArtifactVerifier(downloadRoot: canonicalRoot).verify(
            fileURL: file,
            metadata: artifact(fileName: file.lastPathComponent, size: Int64(data.count), digest: String(repeating: "0", count: 64))
        )) { error in
            XCTAssertEqual(error as? DesktopArtifactVerificationError, .digestMismatch)
        }
    }

    func testSymlinkAndOutsideRootAreRejectedBeforeReading() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let outside = root.deletingLastPathComponent().appendingPathComponent("outside-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: outside) }
        try Data("outside".utf8).write(to: outside)
        let link = root.appendingPathComponent("Hermes-Connector-0.2.0-arm64.tar.gz")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)
        let verifier = try DesktopArtifactVerifier(downloadRoot: root)
        let metadata = artifact(fileName: link.lastPathComponent, size: 7, digest: String(repeating: "0", count: 64))

        XCTAssertThrowsError(try verifier.verify(fileURL: link, metadata: metadata)) { error in
            XCTAssertEqual(error as? DesktopArtifactVerificationError, .outsideManagedRoot)
        }
        XCTAssertThrowsError(try verifier.verify(fileURL: outside, metadata: metadata)) { error in
            XCTAssertEqual(error as? DesktopArtifactVerificationError, .outsideManagedRoot)
        }
    }

    private func artifact(fileName: String, size: Int64, digest: String) -> DesktopReleaseArtifact {
        DesktopReleaseArtifact(
            component: .connector,
            version: "0.2.0",
            fileName: fileName,
            entrypoint: "bin/hermes-connector",
            downloadURL: "https://downloads.example/desktop/releases/\(fileName)",
            sizeBytes: size,
            sha256: digest
        )
    }
}

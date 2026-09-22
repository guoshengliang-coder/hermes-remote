import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopReleaseIndexTests: XCTestCase {
    func testStableIndexResolvesOnlySameOriginImmutableManifest() throws {
        let envelope = Data("signed envelope".utf8)
        let index = try indexData(envelope: envelope)

        let reference = try DesktopReleaseIndex.resolve(
            index,
            indexURL: URL(string: "https://updates.example/desktop/releases/index.json")!,
            expectedChannel: "stable",
            expectedArchitecture: "arm64"
        )

        XCTAssertEqual(reference.releaseVersion, "1.2.3")
        XCTAssertEqual(
            reference.manifestURL.absoluteString,
            "https://updates.example/desktop/releases/1.2.3/Hermes-Desktop-1.2.3-arm64.manifest.json"
        )
        XCTAssertNoThrow(try DesktopReleaseIndex.verifyEnvelope(envelope, reference: reference))
    }

    func testIndexRejectsCrossOriginUnknownFieldsAndManifestSubstitution() throws {
        let envelope = Data("signed envelope".utf8)
        var crossOrigin = try object(indexData(envelope: envelope))
        crossOrigin["manifestURL"] =
            "https://evil.example/desktop/releases/1.2.3/Hermes-Desktop-1.2.3-arm64.manifest.json"
        XCTAssertThrowsError(try resolve(crossOrigin)) { error in
            XCTAssertEqual(error as? DesktopReleaseIndexError, .unsafeManifestURL)
        }

        var unknown = try object(indexData(envelope: envelope))
        unknown["futureAction"] = true
        XCTAssertThrowsError(try resolve(unknown)) { error in
            XCTAssertEqual(error as? DesktopReleaseIndexError, .invalidIndex)
        }

        let reference = try resolve(try object(indexData(envelope: envelope)))
        XCTAssertThrowsError(
            try DesktopReleaseIndex.verifyEnvelope(Data("other envelope".utf8), reference: reference)
        ) { error in
            XCTAssertEqual(error as? DesktopReleaseIndexError, .manifestIdentityMismatch)
        }
    }

    private func indexData(envelope: Data) throws -> Data {
        let digest = SHA256.hash(data: envelope).map { String(format: "%02x", $0) }.joined()
        return try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1,
            "channel": "stable",
            "architecture": "arm64",
            "releaseVersion": "1.2.3",
            "manifestURL":
                "https://updates.example/desktop/releases/1.2.3/Hermes-Desktop-1.2.3-arm64.manifest.json",
            "manifestSizeBytes": envelope.count,
            "manifestSHA256": digest,
            "updatedAt": "2026-09-22T00:00:00Z",
        ], options: [.sortedKeys])
    }

    private func object(_ data: Data) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func resolve(_ object: [String: Any]) throws -> DesktopReleaseIndexReference {
        try DesktopReleaseIndex.resolve(
            JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
            indexURL: URL(string: "https://updates.example/desktop/releases/index.json")!,
            expectedChannel: "stable",
            expectedArchitecture: "arm64"
        )
    }
}

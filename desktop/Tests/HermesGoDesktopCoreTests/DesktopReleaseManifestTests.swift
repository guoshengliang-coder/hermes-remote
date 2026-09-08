import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopReleaseManifestTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_788_710_400) // 2026-09-06T00:00:00Z

    func testValidSignedManifestPinsEveryInstallArtifact() throws {
        let signingKey = Curve25519.Signing.PrivateKey()
        let verifier = try makeVerifier(signingKey: signingKey)
        let manifest = fixtureManifest()

        let verified = try verifier.verify(try envelope(manifest, signingKey: signingKey))

        XCTAssertEqual(verified, manifest)
        XCTAssertEqual(Set(verified.artifacts.map(\.component)), Set(DesktopReleaseComponentKind.allCases))
    }

    func testPayloadMutationFailsSignatureBeforeManifestUse() throws {
        let signingKey = Curve25519.Signing.PrivateKey()
        let verifier = try makeVerifier(signingKey: signingKey)
        let valid = try envelope(fixtureManifest(), signingKey: signingKey)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: valid) as? [String: Any])
        var payload = try XCTUnwrap(Data(canonicalTestBase64URL: object["payload"] as! String))
        payload[payload.startIndex] ^= 1
        object["payload"] = payload.testBase64URL
        let changed = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])

        XCTAssertThrowsError(try verifier.verify(changed)) { error in
            XCTAssertEqual(error as? DesktopReleaseVerificationError, .invalidSignature)
        }
    }

    func testUnknownFieldsAreRejectedEvenWhenPayloadIsValidlySigned() throws {
        let signingKey = Curve25519.Signing.PrivateKey()
        let verifier = try makeVerifier(signingKey: signingKey)
        var payload = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded(fixtureManifest())) as? [String: Any]
        )
        payload["futureUnsafeAction"] = true
        let raw = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])

        XCTAssertThrowsError(try verifier.verify(try envelope(raw, signingKey: signingKey))) { error in
            XCTAssertEqual(error as? DesktopReleaseVerificationError, .unknownField)
        }
    }

    func testWrongOriginExpiredAndIncompatibleArtifactsFailClosed() throws {
        let signingKey = Curve25519.Signing.PrivateKey()
        let verifier = try makeVerifier(signingKey: signingKey)
        let invalid = [
            fixtureManifest(downloadOrigin: "https://evil.example"),
            fixtureManifest(expiresAt: "2026-09-05T00:00:00Z"),
            fixtureManifest(architecture: "x86_64"),
        ]

        for manifest in invalid {
            XCTAssertThrowsError(try verifier.verify(try envelope(manifest, signingKey: signingKey)))
        }
    }

    func testOversizedEnvelopeAndUnknownSigningKeyAreRejected() throws {
        let signingKey = Curve25519.Signing.PrivateKey()
        let verifier = try makeVerifier(signingKey: signingKey)
        XCTAssertThrowsError(try verifier.verify(Data(repeating: 0, count: 256 * 1024 + 1))) { error in
            XCTAssertEqual(error as? DesktopReleaseVerificationError, .responseTooLarge)
        }
        XCTAssertThrowsError(
            try verifier.verify(try envelope(fixtureManifest(), signingKey: signingKey, keyID: "next-key"))
        ) { error in
            XCTAssertEqual(error as? DesktopReleaseVerificationError, .unknownSigningKey)
        }
    }

    private func makeVerifier(
        signingKey: Curve25519.Signing.PrivateKey
    ) throws -> DesktopReleaseManifestVerifier {
        try DesktopReleaseManifestVerifier(
            expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal",
            expectedArchitecture: "arm64",
            currentMacOS: OperatingSystemVersion(majorVersion: 14, minorVersion: 8, patchVersion: 0),
            signingKeys: ["test-key": signingKey.publicKey.rawRepresentation],
            now: { self.now }
        )
    }

    private func fixtureManifest(
        downloadOrigin: String = "https://downloads.example",
        expiresAt: String = "2026-09-20T00:00:00Z",
        architecture: String = "arm64"
    ) -> DesktopReleaseManifest {
        DesktopReleaseManifest(
            releaseVersion: "1.2.3",
            channel: "internal",
            architecture: architecture,
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: expiresAt,
            artifacts: [
                DesktopReleaseArtifact(
                    component: .hermesServer,
                    version: "0.20.6",
                    fileName: "Hermes-Server-0.20.6-arm64.tar.gz",
                    entrypoint: "bin/hermes-server",
                    downloadURL: "\(downloadOrigin)/desktop/releases/Hermes-Server-0.20.6-arm64.tar.gz",
                    sizeBytes: 1_024,
                    sha256: String(repeating: "a", count: 64)
                ),
                DesktopReleaseArtifact(
                    component: .connector,
                    version: "0.2.0",
                    fileName: "Hermes-Connector-0.2.0-arm64.tar.gz",
                    entrypoint: "bin/hermes-connector",
                    downloadURL: "\(downloadOrigin)/desktop/releases/Hermes-Connector-0.2.0-arm64.tar.gz",
                    sizeBytes: 2_048,
                    sha256: String(repeating: "b", count: 64)
                ),
            ]
        )
    }

    private func envelope(
        _ manifest: DesktopReleaseManifest,
        signingKey: Curve25519.Signing.PrivateKey,
        keyID: String = "test-key"
    ) throws -> Data {
        try envelope(encoded(manifest), signingKey: signingKey, keyID: keyID)
    }

    private func envelope(
        _ payload: Data,
        signingKey: Curve25519.Signing.PrivateKey,
        keyID: String = "test-key"
    ) throws -> Data {
        let signature = try signingKey.signature(for: payload)
        return try JSONSerialization.data(withJSONObject: [
            "payload": payload.testBase64URL,
            "keyId": keyID,
            "algorithm": "Ed25519",
            "signature": signature.testBase64URL,
        ], options: [.sortedKeys])
    }

    private func encoded(_ manifest: DesktopReleaseManifest) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(manifest)
    }
}

private extension Data {
    init?(canonicalTestBase64URL value: String) {
        let padding = String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + padding)
    }

    var testBase64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentReleaseManifestV2Tests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_788_710_400)

    func testSignedManifestPinsComponentsDependenciesAndPreflightPolicies() throws {
        let key = Curve25519.Signing.PrivateKey()
        let manifest = fixtureManifest()
        let verified = try verifier(key).verify(try envelope(manifest, signer: key))
        let installationManifest = try verifier(key).verifyForInstallation(
            try envelope(manifest, signer: key)
        )

        XCTAssertEqual(verified, manifest)
        XCTAssertEqual(installationManifest.manifest, manifest)
        let requirements = try verified.preflightRequirements
        XCTAssertEqual(requirements.count, 4)
        XCTAssertEqual(requirements[0].reusePolicy, .exactContent(sha256: hash("a")))
        XCTAssertEqual(
            requirements[3].reusePolicy,
            .verifiedCompatibility(contentSHA256: hash("d"), identifier: "chromium-cdp-1")
        )
    }

    func testUnknownFieldsAndUnknownKindsFailClosed() throws {
        let key = Curve25519.Signing.PrivateKey()
        var payload = try payloadObject(fixtureManifest())
        var components = payload["components"] as! [[String: Any]]
        components[0]["futureAction"] = true
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key))) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseVerificationError, .unknownField)
        }

        payload = try payloadObject(fixtureManifest())
        components = payload["components"] as! [[String: Any]]
        components[0]["kind"] = "future_runtime"
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key))) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseVerificationError, .invalidManifest)
        }
    }

    func testDuplicateComponentsAndDependencyIdentityMismatchFailClosed() throws {
        let key = Curve25519.Signing.PrivateKey()
        var duplicate = fixtureManifest().components
        duplicate[1] = duplicate[0]
        XCTAssertThrowsError(try verifier(key).verify(try envelope(
            fixtureManifest(components: duplicate), signer: key
        )))

        var payload = try payloadObject(fixtureManifest())
        var components = payload["components"] as! [[String: Any]]
        var connector = components[2]
        var dependencies = connector["dependencies"] as! [[String: Any]]
        dependencies[0]["contentSHA256"] = hash("f")
        connector["dependencies"] = dependencies
        components[2] = connector
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key))) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseVerificationError, .invalidManifest)
        }
    }

    func testDependencyCyclesFailClosed() throws {
        let key = Curve25519.Signing.PrivateKey()
        var payload = try payloadObject(fixtureManifest())
        var components = payload["components"] as! [[String: Any]]
        components[0]["dependencies"] = [["kind": "connector", "contentSHA256": hash("c")]]
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key))) { error in
            XCTAssertEqual(error as? DesktopComponentReleaseVerificationError, .invalidManifest)
        }
    }

    func testActivationAndCompatibilityContractsAreStrict() throws {
        let key = Curve25519.Signing.PrivateKey()
        var payload = try payloadObject(fixtureManifest())
        var components = payload["components"] as! [[String: Any]]
        components[0]["requiredForBootstrap"] = false
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key)))

        payload = try payloadObject(fixtureManifest())
        components = payload["components"] as! [[String: Any]]
        components[0]["reuseContract"] = "verified_compatibility"
        components[0]["compatibilityIdentifier"] = "python-any"
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key)))

        payload = try payloadObject(fixtureManifest())
        components = payload["components"] as! [[String: Any]]
        components[1]["installPhase"] = "on_demand"
        components[1]["requiredForBootstrap"] = false
        components[1]["onDemandTrigger"] = "hermes"
        payload["components"] = components
        XCTAssertThrowsError(try verifier(key).verify(try envelope(payload, signer: key)))
    }

    private func verifier(
        _ signer: Curve25519.Signing.PrivateKey
    ) throws -> DesktopComponentReleaseManifestV2Verifier {
        try DesktopComponentReleaseManifestV2Verifier(
            expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal",
            expectedArchitecture: "arm64",
            currentMacOS: OperatingSystemVersion(majorVersion: 14, minorVersion: 8, patchVersion: 0),
            signingKeys: ["test-key": signer.publicKey.rawRepresentation],
            now: { self.now }
        )
    }

    private func fixtureManifest(
        components: [DesktopComponentReleaseArtifactV2]? = nil
    ) -> DesktopComponentReleaseManifestV2 {
        let python = component(.pythonRuntime, hash: "a", entrypoint: "bin/python3")
        let hermes = component(
            .hermesCore,
            hash: "b",
            entrypoint: "bin/hermes",
            dependencies: [.init(kind: .pythonRuntime, contentSHA256: hash("a"))]
        )
        let connector = component(
            .connector,
            hash: "c",
            entrypoint: "bin/hermes-connector",
            dependencies: [.init(kind: .hermesCore, contentSHA256: hash("b"))]
        )
        let browser = component(
            .browserAutomation,
            hash: "d",
            entrypoint: "bin/chromium",
            phase: .onDemand,
            trigger: "browser_automation",
            reuse: .verifiedCompatibility,
            compatibility: "chromium-cdp-1"
        )
        return DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.0",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: components ?? [python, hermes, connector, browser]
        )
    }

    private func component(
        _ kind: DesktopManagedComponentKind,
        hash value: String,
        entrypoint: String,
        dependencies: [DesktopComponentReleaseDependency] = [],
        phase: DesktopManagedComponentInstallPhase = .bootstrap,
        trigger: String? = nil,
        reuse: DesktopComponentReuseContract = .exactContent,
        compatibility: String? = nil
    ) -> DesktopComponentReleaseArtifactV2 {
        let version = "1.2.3"
        let name = "Hermes-Component-\(kind.rawValue)-\(version)-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: version,
            architecture: "arm64",
            installPhase: phase,
            requiredForBootstrap: phase == .bootstrap,
            onDemandTrigger: trigger,
            reuseContract: reuse,
            compatibilityIdentifier: compatibility,
            fileName: name,
            entrypoint: entrypoint,
            downloadURL: "https://downloads.example/desktop/components/\(name)",
            sizeBytes: 1_024,
            sha256: hash(value),
            contentSHA256: hash(value),
            dependencies: dependencies
        )
    }

    private func hash(_ value: String) -> String { String(repeating: value, count: 64) }

    private func payloadObject(_ manifest: DesktopComponentReleaseManifestV2) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: encoded(manifest)) as! [String: Any]
    }

    private func envelope(
        _ manifest: DesktopComponentReleaseManifestV2,
        signer: Curve25519.Signing.PrivateKey
    ) throws -> Data {
        try envelope(encoded(manifest), signer: signer)
    }

    private func envelope(
        _ object: [String: Any],
        signer: Curve25519.Signing.PrivateKey
    ) throws -> Data {
        try envelope(
            JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
            signer: signer
        )
    }

    private func envelope(_ payload: Data, signer: Curve25519.Signing.PrivateKey) throws -> Data {
        let signature = try signer.signature(for: payload)
        return try JSONSerialization.data(withJSONObject: [
            "algorithm": "Ed25519", "keyId": "test-key",
            "payload": payload.v2TestBase64URL, "signature": signature.v2TestBase64URL,
        ], options: [.sortedKeys])
    }

    private func encoded(_ manifest: DesktopComponentReleaseManifestV2) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(manifest)
    }
}

private extension Data {
    var v2TestBase64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

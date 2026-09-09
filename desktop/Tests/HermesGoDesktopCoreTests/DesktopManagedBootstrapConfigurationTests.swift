import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedBootstrapConfigurationTests: XCTestCase {
    func testPackagedAppAllowsLocalHealthChecksWithoutDisablingPublicATS() throws {
        let desktopRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let plistURL = desktopRoot.appendingPathComponent("Packaging/Info.plist")
        let data = try Data(contentsOf: plistURL)
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        )
        let ats = try XCTUnwrap(object["NSAppTransportSecurity"] as? [String: Any])

        XCTAssertEqual(ats["NSAllowsLocalNetworking"] as? Bool, true)
        XCTAssertNil(ats["NSAllowsArbitraryLoads"])
        XCTAssertNil(ats["NSAllowsArbitraryLoadsInWebContent"])
    }

    func testRuntimeContractFreezesOfficialLoopbackServeInterface() {
        let contract = DesktopHermesRuntimeContract.serveV1

        XCTAssertEqual(contract.programArguments, [
            "serve", "--host", "127.0.0.1", "--port", "9119",
        ])
        XCTAssertEqual(contract.baseURL.absoluteString, "http://127.0.0.1:9119")
        XCTAssertEqual(DesktopHermesRuntimeContract.readyLinePrefix, "HERMES_BACKEND_READY port=")
        XCTAssertEqual(DesktopHermesRuntimeContract.portInUseLinePrefix, "BACKEND_PORT_IN_USE port=")
        XCTAssertEqual(
            try contract.environmentVariables(hermesHome: URL(fileURLWithPath: "/Users/test/.hermes")),
            ["HERMES_HOME": "/Users/test/.hermes"]
        )
        XCTAssertTrue(contract.isReadyAnnouncement("HERMES_BACKEND_READY port=9119"))
        XCTAssertFalse(contract.isReadyAnnouncement("HERMES_BACKEND_READY port=9120"))
        XCTAssertTrue(contract.isPortConflictAnnouncement("BACKEND_PORT_IN_USE port=9119"))
        XCTAssertThrowsError(try contract.environmentVariables(hermesHome: URL(fileURLWithPath: "/")))
    }

    func testManagedBootstrapIsDisabledWhenFlagIsAbsentOrZero() {
        XCTAssertEqual(load([:]), .disabled)
        XCTAssertEqual(load(["HERMES_GO_MANAGED_BOOTSTRAP_ENABLED": "0"]), .disabled)
    }

    func testEnabledFlagStillFailsClosedWhenAnyTrustInputIsMissingOrInvalid() {
        var values = validValues()
        values.removeValue(forKey: "HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY")
        XCTAssertEqual(load(values), .invalid)

        values = validValues()
        values["HERMES_GO_DESKTOP_RELEASE_MANIFEST_URL"] = "http://downloads.example/manifest.json"
        XCTAssertEqual(load(values), .invalid)

        values = validValues()
        values["HERMES_GO_DESKTOP_HERMES_RUNTIME_CONTRACT"] = "dashboard-v0"
        XCTAssertEqual(load(values), .invalid)
    }

    func testCompleteConfigurationBuildsPinnedVerifierButNeedsMatchingServerCapability() throws {
        let state = load(validValues())
        guard case .configured(let configuration) = state else {
            return XCTFail("expected a complete configuration")
        }
        XCTAssertEqual(configuration.manifestURL.absoluteString, "https://updates.example/releases/manifest.json")
        XCTAssertEqual(configuration.artifactOrigin.absoluteString, "https://downloads.example")
        XCTAssertEqual(configuration.signingPublicKey, Data(repeating: 7, count: 32))
        XCTAssertNoThrow(try configuration.makeManifestVerifier())

        XCTAssertEqual(
            DesktopManagedBootstrapAvailability.evaluate(
                configuration: state,
                serverRuntimeContract: nil
            ),
            .serverCapabilityUnavailable
        )
        XCTAssertEqual(
            DesktopManagedBootstrapAvailability.evaluate(
                configuration: state,
                serverRuntimeContract: "hermes-dashboard-v0"
            ),
            .runtimeContractMismatch
        )
        XCTAssertEqual(
            DesktopManagedBootstrapAvailability.evaluate(
                configuration: state,
                serverRuntimeContract: "hermes-serve-v1"
            ),
            .ready
        )
    }

    private func load(_ environment: [String: String]) -> DesktopManagedBootstrapConfigurationState {
        DesktopManagedBootstrapConfigurationState.load(environment: environment)
    }

    private func validValues() -> [String: String] {
        [
            "HERMES_GO_MANAGED_BOOTSTRAP_ENABLED": "1",
            "HERMES_GO_DESKTOP_RELEASE_MANIFEST_URL": "https://updates.example/releases/manifest.json",
            "HERMES_GO_DESKTOP_RELEASE_ARTIFACT_ORIGIN": "https://downloads.example",
            "HERMES_GO_DESKTOP_RELEASE_CHANNEL": "internal",
            "HERMES_GO_DESKTOP_RELEASE_ARCHITECTURE": "arm64",
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID": "desktop-release-test",
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY": Data(repeating: 7, count: 32)
                .base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: ""),
            "HERMES_GO_DESKTOP_HERMES_RUNTIME_CONTRACT": "hermes-serve-v1",
        ]
    }
}

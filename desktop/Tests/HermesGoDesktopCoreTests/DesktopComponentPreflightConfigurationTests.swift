import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentPreflightConfigurationTests: XCTestCase {
    func testPackagedComponentPreflightIsDefaultOffAndHasNoManifestURL() throws {
        let desktopRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let plistURL = desktopRoot.appendingPathComponent("Packaging/Info.plist")
        let data = try Data(contentsOf: plistURL)
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        )

        XCTAssertEqual(object["HermesGoComponentPreflightEnabled"] as? Bool, false)
        XCTAssertEqual(object["HermesGoDesktopComponentManifestURL"] as? String, "")
    }

    func testComponentPreflightIsDisabledWhenFlagIsAbsentOrZero() {
        XCTAssertEqual(load([:]), .disabled)
        XCTAssertEqual(load(["HERMES_GO_COMPONENT_PREFLIGHT_ENABLED": "0"]), .disabled)
    }

    func testEnabledConfigurationFailsClosedForMissingOrInvalidTrustInput() {
        var values = validValues()
        values.removeValue(forKey: "HERMES_GO_DESKTOP_COMPONENT_MANIFEST_URL")
        XCTAssertEqual(load(values), .invalid)

        values = validValues()
        values["HERMES_GO_DESKTOP_COMPONENT_MANIFEST_URL"] =
            "https://updates.example/releases%2fcomponent-v2.json"
        XCTAssertEqual(load(values), .invalid)

        values = validValues()
        values["HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY"] = "not+canonical"
        XCTAssertEqual(load(values), .invalid)

        values = validValues()
        values["HERMES_GO_COMPONENT_PREFLIGHT_ENABLED"] = "yes"
        XCTAssertEqual(load(values), .invalid)
    }

    func testCompleteConfigurationBuildsPinnedSchemaV2Verifier() throws {
        let state = load(validValues())
        guard case .configured(let configuration) = state else {
            return XCTFail("expected complete component preflight configuration")
        }

        XCTAssertEqual(
            configuration.manifestURL.absoluteString,
            "https://updates.example/releases/component-v2.json"
        )
        XCTAssertEqual(configuration.artifactOrigin.absoluteString, "https://downloads.example")
        XCTAssertEqual(configuration.channel, "internal")
        XCTAssertEqual(configuration.architecture, "arm64")
        XCTAssertEqual(configuration.signingKeys, ["desktop-release-test": Data(repeating: 7, count: 32)])
        XCTAssertNoThrow(try configuration.makeManifestVerifier())
    }

    func testRotationTrustSetLoadsMultipleKeys() {
        var values = validValues()
        values.removeValue(forKey: "HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID")
        values.removeValue(forKey: "HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY")
        values["HERMES_GO_DESKTOP_RELEASE_SIGNING_KEYS"] =
            #"{"desktop-release-next":"CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg"}"#

        guard case .configured(let configuration) = load(values) else {
            return XCTFail("expected rotation trust set")
        }
        XCTAssertEqual(configuration.signingKeys, ["desktop-release-next": Data(repeating: 8, count: 32)])
    }

    func testComponentBootstrapRequiresSeparateSchemaV2ServerCapability() {
        let configured = load(validValues())
        XCTAssertEqual(
            DesktopComponentBootstrapAvailability.evaluate(
                configuration: .disabled,
                serverManifestSchemaVersion: 2,
                serverRuntimeContract: "hermes-serve-v1"
            ),
            .disabled
        )
        XCTAssertEqual(
            DesktopComponentBootstrapAvailability.evaluate(
                configuration: configured,
                serverManifestSchemaVersion: nil,
                serverRuntimeContract: "hermes-serve-v1"
            ),
            .serverCapabilityUnavailable
        )
        XCTAssertEqual(
            DesktopComponentBootstrapAvailability.evaluate(
                configuration: configured,
                serverManifestSchemaVersion: 1,
                serverRuntimeContract: "hermes-serve-v1"
            ),
            .manifestSchemaMismatch
        )
        XCTAssertEqual(
            DesktopComponentBootstrapAvailability.evaluate(
                configuration: configured,
                serverManifestSchemaVersion: 2,
                serverRuntimeContract: "future-contract"
            ),
            .runtimeContractMismatch
        )
        XCTAssertEqual(
            DesktopComponentBootstrapAvailability.evaluate(
                configuration: configured,
                serverManifestSchemaVersion: 2,
                serverRuntimeContract: "hermes-serve-v1"
            ),
            .ready
        )
    }

    private func load(_ environment: [String: String]) -> DesktopComponentPreflightConfigurationState {
        DesktopComponentPreflightConfigurationState.load(environment: environment)
    }

    private func validValues() -> [String: String] {
        [
            "HERMES_GO_COMPONENT_PREFLIGHT_ENABLED": "1",
            "HERMES_GO_DESKTOP_COMPONENT_MANIFEST_URL":
                "https://updates.example/releases/component-v2.json",
            "HERMES_GO_DESKTOP_RELEASE_ARTIFACT_ORIGIN": "https://downloads.example",
            "HERMES_GO_DESKTOP_RELEASE_CHANNEL": "internal",
            "HERMES_GO_DESKTOP_RELEASE_ARCHITECTURE": "arm64",
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_KEY_ID": "desktop-release-test",
            "HERMES_GO_DESKTOP_RELEASE_SIGNING_PUBLIC_KEY": Data(repeating: 7, count: 32)
                .base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: ""),
        ]
    }
}

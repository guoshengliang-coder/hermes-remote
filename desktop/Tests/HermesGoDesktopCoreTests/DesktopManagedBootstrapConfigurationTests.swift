import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedBootstrapConfigurationTests: XCTestCase {
    func testPackagedAppIsDockVisibleAndDeclaresTheCanonicalBundleIcon() throws {
        let desktopRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let plistURL = desktopRoot.appendingPathComponent("Packaging/Info.plist")
        let data = try Data(contentsOf: plistURL)
        let object = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        )

        XCTAssertEqual(object["CFBundleIconFile"] as? String, "AppIcon")
        XCTAssertEqual(object["LSUIElement"] as? Bool, false)
    }

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
            try contract.environmentVariables(
                hermesHome: URL(fileURLWithPath: "/Users/test/.hermes"),
                sessionTokenFile: URL(fileURLWithPath: "/Users/test/.hermes-go/secrets/hermes-session-token")
            ),
            [
                "HERMES_HOME": "/Users/test/.hermes",
                "HERMES_DESKTOP": "1",
                "HERMES_SESSION_TOKEN_FILE": "/Users/test/.hermes-go/secrets/hermes-session-token",
                // HG-58. launchd hands an agent /usr/bin:/bin:/usr/sbin:/sbin and nothing more, so
                // without this the managed server cannot see anything the user installed: a PDF
                // attachment was refused with "pdftoppm not installed" while pdftoppm sat in
                // /opt/homebrew/bin, installed four and a half hours earlier.
                "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            ]
        )
        // Both Homebrew prefixes, because the Intel one differs; the launchd four kept at the tail,
        // because this widens the search rather than redirecting it.
        XCTAssertTrue(DesktopHermesRuntimeContract.searchPath.hasSuffix("/usr/bin:/bin:/usr/sbin:/sbin"))
        XCTAssertTrue(DesktopHermesRuntimeContract.searchPath.contains("/opt/homebrew/bin"))
        XCTAssertTrue(DesktopHermesRuntimeContract.searchPath.contains("/usr/local/bin"))
        XCTAssertTrue(contract.isReadyAnnouncement("HERMES_BACKEND_READY port=9119"))
        XCTAssertFalse(contract.isReadyAnnouncement("HERMES_BACKEND_READY port=9120"))
        XCTAssertTrue(contract.isPortConflictAnnouncement("BACKEND_PORT_IN_USE port=9119"))
        XCTAssertThrowsError(try contract.environmentVariables(
            hermesHome: URL(fileURLWithPath: "/"),
            sessionTokenFile: URL(fileURLWithPath: "/tmp/hermes-session-token")
        ))
    }

    /// The `PATH` on an existing LaunchAgent is compared out of the base when a replacement is
    /// validated (an agent written before HG-58 has none at all), so it has to be checked for shape
    /// rather than trusted: "allowed to differ" must not become "allowed to be anything".
    func testSearchPathShapeIsCheckedRatherThanTrusted() {
        XCTAssertTrue(DesktopHermesRuntimeContract.isValidSearchPath(DesktopHermesRuntimeContract.searchPath))
        XCTAssertTrue(DesktopHermesRuntimeContract.isValidSearchPath("/usr/bin"))

        XCTAssertFalse(DesktopHermesRuntimeContract.isValidSearchPath(""))
        // A relative entry would resolve against whatever directory launchd happened to start in.
        XCTAssertFalse(DesktopHermesRuntimeContract.isValidSearchPath("/usr/bin:bin"))
        // An empty entry means "the current directory" to execvp — the classic PATH foot-gun.
        XCTAssertFalse(DesktopHermesRuntimeContract.isValidSearchPath("/usr/bin::/bin"))
        XCTAssertFalse(DesktopHermesRuntimeContract.isValidSearchPath("/"))
        XCTAssertFalse(DesktopHermesRuntimeContract.isValidSearchPath("/usr/bin/../bin"))
        XCTAssertFalse(DesktopHermesRuntimeContract.isValidSearchPath("/usr/bin:/b\u{0}in"))
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

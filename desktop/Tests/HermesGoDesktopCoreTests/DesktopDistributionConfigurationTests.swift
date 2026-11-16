import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopDistributionConfigurationTests: XCTestCase {
    private var values: [String: Any] {
        ["CFBundleIdentifier": "com.hermesgo.desktop", "CFBundleExecutable": "HermesGoDesktop",
         "CFBundleShortVersionString": "0.2.53", "CFBundleVersion": "56", "LSMinimumSystemVersion": "14.0",
         "HermesGoBuildPurpose": "distribution", "HermesGoSourceCommit": String(repeating: "a", count: 40),
         "HermesGoDesktopAppUpdateEnabled": true,
         "HermesGoDesktopAppUpdateIndexURL": "https://example.test/desktop/apps/index.json",
         "HermesGoDesktopAppUpdateChannel": "internal", "HermesGoDesktopAppUpdateArchitecture": "arm64",
         "HermesGoManagedBootstrapEnabled": true, "HermesGoComponentPreflightEnabled": true,
         "HermesGoDesktopReleaseManifestURL": "https://example.test/desktop/releases/index.json",
         "HermesGoDesktopComponentManifestURL": "https://example.test/desktop/components/index.json",
         "HermesGoDesktopReleaseArtifactOrigin": "https://example.test",
         "HermesGoDesktopReleaseChannel": "internal", "HermesGoDesktopReleaseArchitecture": "arm64",
         "HermesGoDesktopReleaseSigningKeyID": "test",
         "HermesGoDesktopReleaseSigningPublicKey": Data(repeating: 1, count: 32).base64EncodedString().replacingOccurrences(of: "=", with: ""),
         "HermesGoDesktopHermesRuntimeContract": "hermes-serve-v1"]
    }
    private func bundle(_ values: [String: Any]) throws -> Bundle {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".app")
        let contents = root.appendingPathComponent("Contents")
        try FileManager.default.createDirectory(at: contents, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        try PropertyListSerialization.data(fromPropertyList: values, format: .xml, options: 0)
            .write(to: contents.appendingPathComponent("Info.plist"))
        return try XCTUnwrap(Bundle(url: root))
    }
    func testCompleteReleaseAcceptedAndEnvironmentCannotDisableIt() throws {
        let b = try bundle(values)
        try DesktopDistributionConfiguration.validate(bundle: b, version: "0.2.53", build: 56,
            source: String(repeating: "a", count: 40))
        guard case .configured = DesktopAppUpdateConfigurationState.load(bundle: b,
            environment: ["HERMES_GO_APP_UPDATE_ENABLED": "0"]) else { return XCTFail() }
    }
    func testMissingDisabledOrCrossOriginReleaseConfigurationRejected() throws {
        for (key, value) in [
            ("HermesGoBuildPurpose", "development" as Any),
            ("HermesGoSourceCommit", "unknown"),
            ("LSMinimumSystemVersion", "future"),
            ("HermesGoDesktopAppUpdateEnabled", false),
            ("HermesGoComponentPreflightEnabled", false),
            ("HermesGoDesktopComponentManifestURL", "https://evil.test/desktop/components/index.json"),
            ("HermesGoDesktopReleaseSigningPublicKey", "invalid"),
            ("HermesGoDesktopHermesRuntimeContract", "future")
        ] {
            var bad = values; bad[key] = value
            XCTAssertThrowsError(try DesktopDistributionConfiguration.validate(bundle: bundle(bad)), key)
        }
    }
    func testWrongBuildOrSourceRejected() throws {
        let b = try bundle(values)
        XCTAssertThrowsError(try DesktopDistributionConfiguration.validate(bundle: b, build: 55))
        XCTAssertThrowsError(try DesktopDistributionConfiguration.validate(bundle: b, source: String(repeating: "b", count: 40)))
    }
    func testRecoveryErrorIsLocalizedStableAndNotAutomaticallyRetried() {
        let issue = DesktopIssue.appUpdateInstall(DesktopAppUpdateInstallError.recoveryRequired)
        XCTAssertEqual(issue.code.rawValue, "HR-DESKUPDATE-006")
        XCTAssertFalse(issue.summaryChinese.isEmpty)
        XCTAssertFalse(issue.summaryEnglish.isEmpty)
        XCTAssertFalse(issue.retryable)
    }
}

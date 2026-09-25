import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopUpdateIndexTests: XCTestCase {
    private let indexURL = URL(string: "https://mrlgs.net/desktop/apps/index.json")!

    func testValidAppIndexResolvesEveryField() throws {
        let reference = try DesktopAppUpdateIndex.resolve(
            try index(appVersion: "0.2.29"),
            indexURL: indexURL,
            expectedChannel: "internal",
            expectedArchitecture: "arm64"
        )

        XCTAssertEqual(reference.appVersion, "0.2.29")
        XCTAssertEqual(reference.buildNumber, 32)
        XCTAssertEqual(reference.releaseNotes, ["修复菜单栏图标", "支持自动更新检查"])
        XCTAssertEqual(reference.sizeBytes, 2_690_779)
        XCTAssertEqual(
            reference.downloadURL.absoluteString,
            "https://mrlgs.net/desktop/apps/0.2.29/Hermes-Go-Desktop-0.2.29.dmg"
        )
    }

    func testUnknownFieldsAreRejected() throws {
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: try index(appVersion: "0.2.29")) as? [String: Any]
        )
        object["forceInstall"] = true
        let data = try JSONSerialization.data(withJSONObject: object)

        XCTAssertThrowsError(try resolve(data)) { error in
            XCTAssertEqual(error as? DesktopAppUpdateIndexError, .invalidIndex)
        }
    }

    func testOffOriginDownloadURLIsRejected() throws {
        XCTAssertThrowsError(try resolve(try index(
            appVersion: "0.2.29",
            downloadURL: "https://evil.example/desktop/apps/0.2.29/Hermes-Go-Desktop-0.2.29.dmg"
        ))) { error in
            XCTAssertEqual(error as? DesktopAppUpdateIndexError, .unsafeDownloadURL)
        }
    }

    func testVersionMismatchBetweenIndexAndFileNameIsRejected() throws {
        XCTAssertThrowsError(try resolve(try index(
            appVersion: "0.2.29",
            downloadURL: "https://mrlgs.net/desktop/apps/0.2.29/Hermes-Go-Desktop-0.2.28.dmg"
        )))
    }

    func testBadReleaseNotesAndChannelAreRejected() throws {
        XCTAssertThrowsError(try resolve(try index(
            appVersion: "0.2.29",
            notes: Array(repeating: "note", count: 21)
        )))
        XCTAssertThrowsError(try resolve(try index(appVersion: "0.2.29"), channel: "beta"))
    }

    func testSemanticComparison() {
        XCTAssertTrue(DesktopSemanticVersion.isNewer("0.2.29", than: "0.2.28"))
        XCTAssertTrue(DesktopSemanticVersion.isNewer("0.3.0", than: "0.2.28"))
        XCTAssertFalse(DesktopSemanticVersion.isNewer("0.2.28", than: "0.2.28"))
        XCTAssertFalse(DesktopSemanticVersion.isNewer("0.2.27", than: "0.2.28"))
        XCTAssertFalse(DesktopSemanticVersion.isNewer("0.2.28", than: "not-a-version"))
    }

    // MARK: - Fixtures

    private func resolve(
        _ data: Data,
        channel: String = "internal"
    ) throws -> DesktopAppUpdateReference {
        try DesktopAppUpdateIndex.resolve(
            data,
            indexURL: indexURL,
            expectedChannel: channel,
            expectedArchitecture: "arm64"
        )
    }

    private func index(
        appVersion: String,
        downloadURL: String? = nil,
        notes: [String] = ["修复菜单栏图标", "支持自动更新检查"]
    ) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1,
            "channel": "internal",
            "architecture": "arm64",
            "appVersion": appVersion,
            "buildNumber": 32,
            "minimumMacOS": "14.0",
            "downloadURL": downloadURL
                ?? "https://mrlgs.net/desktop/apps/\(appVersion)/Hermes-Go-Desktop-\(appVersion).dmg",
            "sizeBytes": 2_690_779,
            "sha256": String(repeating: "a", count: 64),
            "releaseNotes": notes,
            "sourceCommit": String(repeating: "b", count: 40),
            "updatedAt": "2026-09-25T00:00:00Z",
        ], options: [.sortedKeys])
    }
}

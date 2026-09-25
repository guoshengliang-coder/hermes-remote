import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopUpdateCheckerTests: XCTestCase {
    private let appIndexURL = URL(string: "https://mrlgs.net/desktop/apps/index.json")!
    private let managedIndexURL = URL(string: "https://mrlgs.net/desktop/releases/index.json")!

    override func tearDown() {
        UpdateURLProtocol.handler = nil
        super.tearDown()
    }

    func testNewerAppAndManagedReleasesAreReportedWithNotes() async throws {
        stubIndexes(appVersion: "0.2.29", managedVersion: "0.4.4")

        let report = try await checker().check(
            sources: sources(),
            installedAppVersion: "0.2.28",
            installedManagedVersion: "0.4.3"
        )

        XCTAssertEqual(report.appUpdate?.version, "0.2.29")
        XCTAssertEqual(report.appUpdate?.releaseNotes, ["支持自动更新检查"])
        XCTAssertEqual(report.managedUpdate?.version, "0.4.4")
        XCTAssertTrue(report.hasUpdates)
    }

    func testEqualVersionsProduceNoUpdate() async throws {
        stubIndexes(appVersion: "0.2.28", managedVersion: "0.4.3")

        let report = try await checker().check(
            sources: sources(),
            installedAppVersion: "0.2.28",
            installedManagedVersion: "0.4.3"
        )

        XCTAssertNil(report.appUpdate)
        XCTAssertNil(report.managedUpdate)
        XCTAssertFalse(report.hasUpdates)
    }

    func testManagedUpdateIsNotReportedWhenNothingIsInstalled() async throws {
        stubIndexes(appVersion: "0.2.28", managedVersion: "0.4.4")

        let report = try await checker().check(
            sources: sources(),
            installedAppVersion: "0.2.28",
            installedManagedVersion: nil
        )

        XCTAssertNil(report.managedUpdate)
    }

    func testMalformedIndexFailsClosed() async throws {
        UpdateURLProtocol.handler = { _ in (200, [:], Data("not json".utf8)) }

        do {
            _ = try await checker().check(
                sources: sources(),
                installedAppVersion: "0.2.28",
                installedManagedVersion: "0.4.3"
            )
            XCTFail("Expected index failure")
        } catch {
            XCTAssertEqual(error as? DesktopUpdateCheckError, .indexUnavailable)
        }
    }

    func testNoConfiguredSourceReportsNotConfigured() async throws {
        do {
            _ = try await checker().check(
                sources: DesktopUpdateSources(
                    appIndexURL: nil, appChannel: nil, appArchitecture: nil,
                    managedIndexURL: nil, managedChannel: nil, managedArchitecture: nil
                ),
                installedAppVersion: "0.2.28",
                installedManagedVersion: nil
            )
            XCTFail("Expected not-configured")
        } catch {
            XCTAssertEqual(error as? DesktopUpdateCheckError, .notConfigured)
        }
    }

    // MARK: - Fixtures

    private func stubIndexes(appVersion: String, managedVersion: String) {
        // Deliberately built from locals before the closure: capturing `self` inside a handler that
        // switches over an optional URL used to crash the Swift 6.1.2 SILGen pass on CI.
        let appData = appIndex(version: appVersion)
        let managedData = managedIndex(version: managedVersion)
        let appURL = appIndexURL
        let managedURL = managedIndexURL
        UpdateURLProtocol.handler = { request in
            if request.url == appURL { return (200, [:], appData) }
            if request.url == managedURL { return (200, [:], managedData) }
            return (404, [:], Data())
        }
    }

    private func checker() -> DesktopUpdateChecker {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UpdateURLProtocol.self]
        return DesktopUpdateChecker(downloader: DesktopReleaseDownloader(session: URLSession(configuration: configuration)))
    }

    private func sources() -> DesktopUpdateSources {
        DesktopUpdateSources(
            appIndexURL: appIndexURL,
            appChannel: "internal",
            appArchitecture: "arm64",
            managedIndexURL: managedIndexURL,
            managedChannel: "internal",
            managedArchitecture: "arm64"
        )
    }

    private func appIndex(version: String) -> Data {
        try! JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1,
            "channel": "internal",
            "architecture": "arm64",
            "appVersion": version,
            "buildNumber": 32,
            "minimumMacOS": "14.0",
            "downloadURL": "https://mrlgs.net/desktop/apps/\(version)/Hermes-Go-Desktop-\(version).dmg",
            "sizeBytes": 2_690_779,
            "sha256": String(repeating: "a", count: 64),
            "releaseNotes": ["支持自动更新检查"],
            "sourceCommit": String(repeating: "b", count: 40),
            "updatedAt": "2026-09-25T00:00:00Z",
        ], options: [.sortedKeys])
    }

    private func managedIndex(version: String) -> Data {
        try! JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1,
            "channel": "internal",
            "architecture": "arm64",
            "releaseVersion": version,
            "manifestURL": "https://mrlgs.net/desktop/releases/\(version)/Hermes-Desktop-\(version)-arm64.manifest.json",
            "manifestSizeBytes": 1_024,
            "manifestSHA256": String(repeating: "c", count: 64),
            "releaseNotes": ["连接稳定性改进"],
            "updatedAt": "2026-09-25T00:00:00Z",
        ], options: [.sortedKeys])
    }
}

private final class UpdateURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, [String: String], Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        let result = handler(request)
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: result.0,
            httpVersion: "HTTP/1.1",
            headerFields: result.1
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: result.2)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

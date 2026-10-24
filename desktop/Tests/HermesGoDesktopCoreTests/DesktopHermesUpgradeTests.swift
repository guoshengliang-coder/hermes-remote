import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeTests: XCTestCase {
    func testDecodedTargetCannotBypassValidation() throws {
        let invalid = "{\"version\":\"0.21.6\",\"commit\":\"main\",\"channel\":\"stable\"}"
        XCTAssertThrowsError(try JSONDecoder().decode(DesktopHermesUpgradeTarget.self, from: Data(invalid.utf8)))
        let target = try DesktopHermesUpgradeTarget(version: "0.21.6", commit: String(repeating: "a", count: 40))
        XCTAssertEqual(try JSONDecoder().decode(DesktopHermesUpgradeTarget.self, from: JSONEncoder().encode(target)), target)
        XCTAssertThrowsError(try DesktopHermesUpgradeTarget(version: "0.21.6/../main", commit: target.commit))
        XCTAssertThrowsError(try DesktopHermesUpgradeTarget(version: "0.21.6", commit: target.commit, channel: "main"))
    }

    func testLatestIsNeverAutomaticallyRecommended() async throws {
        let target = try DesktopHermesUpgradeTarget(version: "0.21.6", commit: String(repeating: "a", count: 40))
        let checker = DesktopHermesUpgradeChecker(fetcher: ReleaseFixture(target: target))
        let report = try await checker.check(installation: .absent(hermesDataPresent: false))
        XCTAssertEqual(report.latestStable, target)
        XCTAssertNil(report.verifiedRecommendation)
        XCTAssertFalse(report.latestStableIsVerified)
        let verified = try await DesktopHermesUpgradeChecker(fetcher: ReleaseFixture(target: target), verifiedTargets: [target])
            .check(installation: .absent(hermesDataPresent: false))
        XCTAssertTrue(verified.latestStableIsVerified)
        let other = try DesktopHermesUpgradeTarget(version: target.version, commit: String(repeating: "b", count: 40))
        let replaced = try await DesktopHermesUpgradeChecker(fetcher: ReleaseFixture(target: other), verifiedTargets: [target])
            .check(installation: .absent(hermesDataPresent: false))
        XCTAssertFalse(replaced.latestStableIsVerified)
    }

    func testOfficialReleaseResolvesAnnotatedTagInsteadOfMovingMain() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HermesReleaseURLProtocol.self]
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel(); HermesReleaseURLProtocol.handler = nil }
        let commit = String(repeating: "a", count: 40)
        let tag = String(repeating: "b", count: 40)
        HermesReleaseURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "api.github.com")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            switch request.url!.lastPathComponent {
            case "latest": return ["draft": false, "prerelease": false, "tag_name": "v0.21.6", "target_commitish": "main"]
            case "v0.21.6": return ["object": ["type": "tag", "sha": tag]]
            case tag: return ["object": ["type": "commit", "sha": commit]]
            default: throw DesktopHermesUpgradeError.invalidRelease
            }
        }
        let target = try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable()
        XCTAssertEqual(target.commit, commit)
        XCTAssertEqual(target.version, "0.21.6")
    }

    func testUpgradeFailuresHaveBilingualStableCodesAndNoRawCause() throws {
        for error in [DesktopHermesUpgradeError.checkFailed, .preflightBlocked, .recoveryRequired] {
            let issue = error.issue
            XCTAssertTrue(issue.code.rawValue.hasPrefix("HR-HERMESUPDATE-"))
            XCTAssertFalse(issue.summaryChinese.isEmpty)
            XCTAssertFalse(issue.summaryEnglish.isEmpty)
            XCTAssertEqual(issue.retryable, error == .checkFailed)
            XCTAssertFalse(issue.summaryChinese.contains("hermes_upgrade="))
        }
    }
}
private struct ReleaseFixture: DesktopHermesReleaseFetching {
    let target: DesktopHermesUpgradeTarget
    func latestStable() async throws -> DesktopHermesUpgradeTarget { target }
}
private final class HermesReleaseURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> [String: Any])?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let data = try JSONSerialization.data(withJSONObject: Self.handler!(request))
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

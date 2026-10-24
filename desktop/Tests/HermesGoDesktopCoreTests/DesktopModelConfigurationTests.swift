import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopModelConfigurationTests: XCTestCase {
    func testSetupCommandUsesTheManagedProfileAndQuotesTheExecutable() {
        XCTAssertEqual(DesktopModelConfiguration.setupCommand(executable: "/Users/test name/hermes"),
                       "'/Users/test name/hermes' --profile default setup")
        XCTAssertEqual(DesktopModelConfiguration.setupCommand(executable: "/test'path/hermes"),
                       "'/test'\"'\"'path/hermes' --profile default setup")
    }

    func testFreshSetupIgnoresRetiredBundledChoiceButExistingRuntimeDoesNotChange() {
        XCTAssertTrue(DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: true, configuredEnabled: false))
        XCTAssertFalse(DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: false, configuredEnabled: false))
        XCTAssertTrue(DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: false, configuredEnabled: true))
    }

    func testMissingAndUnknownAreDifferentAndNeitherPromisesUsability() {
        XCTAssertEqual(DesktopModelConfiguration.parse(Data(#"{"model":""}"#.utf8)), .missing)
        XCTAssertEqual(DesktopModelConfiguration.parse(Data(#"{"provider":"x"}"#.utf8)), .unknown)
        XCTAssertEqual(DesktopModelConfiguration.parse(Data("invalid".utf8)), .unknown)
        let selected = DesktopModelConfiguration.parse(Data(#"{"model":"test-model","api_key":"do-not-surface"}"#.utf8))
        XCTAssertEqual(selected, .selected("test-model"))
        XCTAssertTrue(selected.detail.contains("尚未验证"))
        XCTAssertFalse(selected.detail.contains("do-not-surface"))
    }
    func testUnknownContractAndUnboundedLabelsAreIgnored() {
        XCTAssertEqual(DesktopModelConfiguration.parse(Data(#"{"model":{}}"#.utf8)), .unknown)
        let data = try! JSONSerialization.data(withJSONObject: ["model": String(repeating: "a", count: 257)])
        XCTAssertEqual(DesktopModelConfiguration.parse(data), .unknown)
    }
}

final class DesktopModelProbeTests: XCTestCase {
    func testUsesFixedAuthenticatedLoopbackAndTreatsOptionalFailureAsUnknown() async {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ModelMetadataProtocol.self]
        let prober = HTTPHealthProber(session: URLSession(configuration: configuration))
        let selected = await prober.probeModelConfiguration(sessionToken: "fixture-token")
        XCTAssertEqual(selected, .selected("fixture-model"))
        let unavailable = await prober.probeModelConfiguration(sessionToken: "fixture-unavailable")
        XCTAssertEqual(unavailable, .unknown)
    }
}

private final class ModelMetadataProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        XCTAssertEqual(request.url?.absoluteString, "http://127.0.0.1:9119/api/model/info")
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalCacheData)
        let token = request.value(forHTTPHeaderField: "X-Hermes-Session-Token")
        XCTAssertTrue(["fixture-token", "fixture-unavailable"].contains(token))
        let response = HTTPURLResponse(url: request.url!, statusCode: token == "fixture-token" ? 200 : 404,
                                       httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"model":"fixture-model","api_key":"not-for-display"}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

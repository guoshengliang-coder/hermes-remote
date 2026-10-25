import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class HermesCompatibilityRecommendationTests: XCTestCase {
    private let key = Curve25519.Signing.PrivateKey()
    private let now = ISO8601DateFormatter().date(from: "2026-10-25T00:00:00Z")!
    private let origin = "https://relay.example"
    private var components: [[String:Any]] {
        ["desktop","connector","gateway","android","web"].map { ["name":$0,"version":"1.0.0","artifactSha256":String(repeating:"f",count:64)] }
    }
    private var environment: [String:Any] { ["os":"macos","arch":"arm64","osVersion":"26.0.0","installation":"legacy-venv-to-pm"] }
    private func entry(version: String = "0.22.5") -> [String:Any] {
        ["schemaVersion":1,"id":UUID().uuidString.lowercased(),"runId":UUID().uuidString.lowercased(),"reportSha256":String(repeating:"0",count:64),
         "identity":["schemaVersion":1,"candidateId":UUID().uuidString.lowercased(),"version":version,
                     "sourceCommit":String(repeating:"a",count:40),"targetCommit":String(repeating:"b",count:40),"goCommit":String(repeating:"c",count:40),
                     "suiteSha256":String(repeating:"d",count:64),"installerSha256":String(repeating:"e",count:64),"environment":environment,"components":components]]
    }
    private func payload() -> [String:Any] {
        ["schemaVersion":1,"purpose":"hermes-go.compatibility.recommendations.v1","origin":origin,"revision":7,
         "issuedAt":"2026-10-25T00:00:00Z","expiresAt":"2026-10-26T00:00:00Z","entries":[entry()]]
    }
    private func envelope(_ value: [String:Any]) throws -> Data {
        let data = try JSONSerialization.data(withJSONObject:value,options:.sortedKeys)
        func encode(_ bytes: Data) -> String { bytes.base64EncodedString().replacingOccurrences(of:"+",with:"-").replacingOccurrences(of:"/",with:"_").replacingOccurrences(of:"=",with:"") }
        return try JSONSerialization.data(withJSONObject:["payload":encode(data),"keyId":"test","algorithm":"Ed25519","signature":encode(key.signature(for:data))])
    }
    private func verifier() throws -> HermesCompatibilityRecommendationVerifier {
        try HermesCompatibilityRecommendationVerifier(origin:origin,keys:["test":key.publicKey.rawRepresentation])
    }

    func testSharedTypeScriptSignedFixtureVerifiesInSwift() throws {
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data=try Data(contentsOf:root.appendingPathComponent("scripts/fixtures/hermes-compatibility/signed-recommendation.json"))
        let fixture=try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
        let publicKey=try XCTUnwrap(fixture["publicKey"] as? String)
        let raw=try XCTUnwrap(Data(base64Encoded:publicKey.replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/")+"="))
        let verifier=try HermesCompatibilityRecommendationVerifier(origin:"https://fixture.invalid",keys:["synthetic-contract-test":raw])
        let result=try verifier.verify(JSONSerialization.data(withJSONObject:fixture["envelope"]!),minimumRevision:7,now:now)
        XCTAssertEqual(result.revision,7)
        XCTAssertEqual(result.entries.first?.identity.version,"0.22.5")
    }

    func testVerifiedRecommendationMatchesExactEnvironmentSourceAndAllReleasedComponents() throws {
        var value = payload(); value["entries"] = [entry(),entry(version:"0.23.0")]
        let recommendation = try verifier().verify(envelope(value),minimumRevision:7,now:now)
        let environment = HermesCompatibilityEnvironment(arch:"arm64",osVersion:"26.0.0",installation:"legacy-venv-to-pm")
        let components = self.components.map { HermesCompatibilityComponent(name:$0["name"] as! String,version:"1.0.0",artifactSha256:String(repeating:"f",count:64)) }
        func choose(source:String=String(repeating:"a",count:40),version:String="0.21.3",env:HermesCompatibilityEnvironment?=nil,parts:[HermesCompatibilityComponent]?=nil) -> VerifiedHermesRecommendation.Entry? {
            recommendation.newestUpgrade(sourceCommit:source,currentVersion:version,environment:env ?? environment,components:parts ?? components,now:now)
        }
        XCTAssertEqual(choose()?.identity.version,"0.23.0")
        XCTAssertNil(choose(source:String(repeating:"e",count:40)))
        XCTAssertNil(choose(version:"0.24.0"))
        XCTAssertNil(choose(version:"0.23.0"))
        XCTAssertNil(choose(env:HermesCompatibilityEnvironment(arch:"arm64",osVersion:"26.0.1",installation:"legacy-venv-to-pm")))
        XCTAssertNil(choose(parts:Array(components.dropLast())))
        var changed=components; changed[0]=HermesCompatibilityComponent(name:"desktop",version:"1.0.1",artifactSha256:String(repeating:"f",count:64))
        XCTAssertNil(choose(parts:changed))
        XCTAssertNil(recommendation.newestUpgrade(sourceCommit:String(repeating:"a",count:40),currentVersion:"0.21.3",environment:environment,components:components,now:now.addingTimeInterval(86_400)))
    }

    func testPurposeOriginExpiryReplayAndUnknownFieldsAreRejectedEvenWithValidSignature() throws {
        for (field,value) in [("purpose","hermes-go.compatibility.released-components.v1"),("origin","https://other.example"),
                              ("expiresAt","2026-10-25T00:00:00Z"),("expiresAt","2026-10-27T00:00:00Z"),("issuedAt","2026-10-25T01:00:00Z"),("unexpected","value")] {
            var body=payload();body[field]=value
            XCTAssertThrowsError(try verifier().verify(envelope(body),minimumRevision:0,now:now),field)
        }
        XCTAssertThrowsError(try verifier().verify(envelope(payload()),minimumRevision:8,now:now))
        var body=payload(); var item=entry(); item["command"]="do not execute";body["entries"]=[item]
        XCTAssertThrowsError(try verifier().verify(envelope(body),minimumRevision:0,now:now))
        let duplicate=entry();body=payload();body["entries"]=[duplicate,duplicate]
        XCTAssertThrowsError(try verifier().verify(envelope(body),minimumRevision:0,now:now))
    }

    func testSignatureAndTrustConfigurationCannotBeReplacedByManifest() throws {
        let other=Curve25519.Signing.PrivateKey()
        let verifier=try HermesCompatibilityRecommendationVerifier(origin:origin,keys:["test":other.publicKey.rawRepresentation])
        XCTAssertThrowsError(try verifier.verify(envelope(payload()),minimumRevision:0,now:now))
        XCTAssertThrowsError(try HermesCompatibilityRecommendationVerifier(origin:"http://relay.example",keys:["test":key.publicKey.rawRepresentation]))
        XCTAssertThrowsError(try HermesCompatibilityRecommendationVerifier(origin:origin,keys:[:]))
        XCTAssertThrowsError(try self.verifier().verify(Data(repeating:0,count:512*1024+1),minimumRevision:0,now:now))
    }

    func testSignedDuplicateJSONKeysCannotChooseTheAcceptedRevision() throws {
        let original=try JSONSerialization.data(withJSONObject:payload(),options:.sortedKeys)
        let text=try XCTUnwrap(String(data:original,encoding:.utf8))
        let ambiguous=Data(text.replacingOccurrences(of:"\"revision\":7",with:"\"revision\":7,\"revision\":1").utf8)
        XCTAssertNotEqual(original,ambiguous)
        func encode(_ data:Data)->String { data.base64EncodedString().replacingOccurrences(of:"+",with:"-").replacingOccurrences(of:"/",with:"_").replacingOccurrences(of:"=",with:"") }
        let envelope=try JSONSerialization.data(withJSONObject:["payload":encode(ambiguous),"keyId":"test","algorithm":"Ed25519","signature":encode(key.signature(for:ambiguous))])
        XCTAssertThrowsError(try verifier().verify(envelope,minimumRevision:7,now:now))
    }

    func testVerificationFailureHasLocalizedStableErrorAndNoRawData() throws {
        XCTAssertThrowsError(try verifier().verify(Data("secret invalid payload".utf8),minimumRevision:0,now:now)) { error in
            guard let issue=error as? DesktopIssue else { return XCTFail("structured issue required") }
            XCTAssertEqual(issue.code.rawValue,"HR-HERMESCOMPAT-008")
            XCTAssertTrue(issue.retryable)
            XCTAssertFalse(issue.displayChinese.contains("secret"))
            XCTAssertFalse(issue.detailEnglish.isEmpty)
            XCTAssertEqual(issue.recoveryAction,.retry)
        }
    }

    func testOnlineRefreshPersistsRevisionAcrossClientsAndRejectsEquivocation() async throws {
        let suite = "compatibility-test-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = HermesRecommendationRevisionStore(defaults: defaults)
        let source = RecommendationFixtureFetcher(data: try envelope(payload()))
        let instant = now
        func client() throws -> HermesCompatibilityRecommendationClient {
            try HermesCompatibilityRecommendationClient(origin: origin, keys: ["test": key.publicKey.rawRepresentation],
                fetcher: source, revisions: store, now: { instant })
        }
        let first = try await client().refresh()
        XCTAssertEqual(first.revision, 7)
        var changed = payload(); changed["revision"] = 6
        await source.set(try envelope(changed))
        do { _ = try await client().refresh(); XCTFail("restart must not reset revision") } catch {}
        changed = payload(); changed["entries"] = []
        await source.set(try envelope(changed))
        do { _ = try await client().refresh(); XCTFail("same revision with different content") } catch {}
        changed["revision"] = 8
        let revoked = try envelope(changed)
        await source.set(revoked)
        let second = try await client().refresh()
        XCTAssertEqual(second.revision, 8)
        XCTAssertTrue(second.entries.isEmpty)
        let repeated = try await client().refresh()
        XCTAssertEqual(repeated.revision, 8)
    }

    func testOfflineOrPersistenceFailureNeverReturnsPriorRecommendation() async throws {
        let source = RecommendationFixtureFetcher(data: try envelope(payload()))
        let instant = now
        let client = try HermesCompatibilityRecommendationClient(origin: origin, keys: ["test": key.publicKey.rawRepresentation],
            fetcher: source, revisions: RecommendationFixtureStore(fails: false), now: { instant })
        _ = try await client.refresh()
        await source.set(nil)
        do { _ = try await client.refresh(); XCTFail("offline must not authorize from cache") }
        catch { XCTAssertEqual((error as? DesktopIssue)?.code, .hermesCompatibilityRecommendationInvalid) }
        await source.set(try envelope(payload()))
        let broken = try HermesCompatibilityRecommendationClient(origin: origin, keys: ["test": key.publicKey.rawRepresentation],
            fetcher: source, revisions: RecommendationFixtureStore(fails: true), now: { instant })
        do { _ = try await broken.refresh(); XCTFail("persistence failure") }
        catch { XCTAssertEqual((error as? DesktopIssue)?.code, .hermesCompatibilityRecommendationInvalid) }
        let calls = await source.calls
        XCTAssertEqual(calls, 3)
    }

    func testPublicHTTPTransportHasNoAccountCredentialsAndBoundsResponses() async throws {
        defer { RecommendationURLProtocol.handler = nil }
        let fetcher = try HermesRecommendationHTTPFetcher(origin: origin, protocolClasses: [RecommendationURLProtocol.self])
        RecommendationURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.absoluteString, "https://relay.example/v2/hermes-compatibility/recommendations")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-cache, no-store")
            XCTAssertFalse(request.httpShouldHandleCookies)
            return (200, Data("{}".utf8), [:])
        }
        let data = try await fetcher.fetch()
        XCTAssertEqual(data, Data("{}".utf8))
        for (status, bytes, headers) in [(503, Data(), [String:String]()),
                                         (200, Data(repeating: 0, count: 512 * 1024 + 1), [:]),
                                         (200, Data(), ["Content-Length": "524289"])] {
            RecommendationURLProtocol.handler = { _ in (status, bytes, headers) }
            do { _ = try await fetcher.fetch(); XCTFail("invalid response accepted") }
            catch { XCTAssertEqual((error as? DesktopIssue)?.code, .hermesCompatibilityRecommendationInvalid) }
        }
    }
}

private actor RecommendationFixtureFetcher: HermesRecommendationFetching {
    private var data: Data?
    private(set) var calls = 0
    init(data: Data) { self.data = data }
    func set(_ data: Data?) { self.data = data }
    func fetch() async throws -> Data {
        calls += 1
        guard let data else { throw URLError(.notConnectedToInternet) }
        return data
    }
}
private struct RecommendationFixtureStore: HermesRecommendationRevisionStoring {
    let fails: Bool
    func accept(origin: String, revision: Int, payloadSha256: String) throws {
        if fails { throw URLError(.cannotWriteToFile) }
    }
}
private final class RecommendationURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, Data, [String:String]))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data, headers) = try Self.handler!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: headers)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

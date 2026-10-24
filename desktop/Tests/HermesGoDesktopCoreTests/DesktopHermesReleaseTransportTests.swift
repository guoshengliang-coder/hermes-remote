import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesReleaseTransportTests: XCTestCase {
    override func tearDown() {
        ReleaseProtocol.state.reset()
        super.tearDown()
    }

    func testAmbientSessionCredentialsAndCookiesAreNeverSent() async throws {
        let session = session(headers: ["Authorization": "Bearer fixture-private", "Cookie": "fixture=private",
                                        "X-Hermes-Session-Token": "fixture-local-secret"])
        defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in .json(Self.response(for: request)) }
        _ = try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable()
        let requests = ReleaseProtocol.state.requests
        XCTAssertEqual(requests.count, 2)
        for request in requests {
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
            XCTAssertNil(request.value(forHTTPHeaderField: "X-Hermes-Session-Token"))
        }
    }

    func testDifferentRepositoryResponseCannotSelectAnOfficialTarget() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in
            var plan = ReleasePlan.json(Self.response(for: request))
            plan.url = URL(string: "https://api.github.com/repos/other-owner/hermes-agent/releases/latest")!
            return plan
        }
        await expectCheckFailure(session)
    }

    func testAnExistingEphemeralCookieJarIsNotReused() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ReleaseProtocol.self]
        let cookie = try XCTUnwrap(HTTPCookie(properties: [.domain: "api.github.com", .path: "/",
                                                          .name: "fixture-only", .value: "must-not-send", .secure: "TRUE"]))
        let storage = try XCTUnwrap(configuration.httpCookieStorage)
        storage.setCookie(cookie) // This configuration's in-memory jar; never sharedCookieStorage.
        XCTAssertTrue(storage.cookies?.contains(where: { $0.name == cookie.name }) == true)
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in .json(Self.response(for: request)) }
        _ = try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable()
        for request in ReleaseProtocol.state.requests {
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        }
        XCTAssertTrue(storage.cookies?.contains(where: { $0.name == cookie.name }) == true)
    }

    func testDuplicateTagFieldsAreRejectedInsteadOfChoosingOne() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in
            guard request.url?.lastPathComponent == "latest" else { return .json(Self.response(for: request)) }
            let text = #"{"draft":false,"prerelease":false,"tag_name":"v0.21.6","tag_name":"v0.21.6"}"#
            return ReleasePlan(chunks: [Data(text.utf8)])
        }
        await expectCheckFailure(session)
    }

    func testNumericDraftOrPrereleaseFlagsDoNotCountAsStableReleaseBooleans() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        for field in ["draft", "prerelease"] {
            ReleaseProtocol.state.handler = { request in
                var object = Self.response(for: request)
                if request.url?.lastPathComponent == "latest" { object[field] = 0 }
                return .json(object)
            }
            do {
                _ = try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable()
                XCTFail("numeric \(field) was accepted as a Boolean")
            } catch { XCTAssertEqual(error as? DesktopHermesUpgradeError, .invalidRelease) }
        }
    }

    func testAnnouncedOversizeStopsWithoutBufferingTheWholeBody() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { _ in
            ReleasePlan(chunks: Array(repeating: Data(repeating: 32, count: 100_000), count: 6), contentLength: 600_000)
        }
        await expectCheckFailure(session)
        // CFNetwork can coalesce the headers with an initial body chunk before resuming bytes().
        // The application checks headers before consuming bytes and must stop the remaining stream.
        XCTAssertLessThan(ReleaseProtocol.state.deliveredChunks, 6)
    }

    func testUnknownLengthOversizeStopsBeforeTheLastChunk() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { _ in
            ReleasePlan(chunks: Array(repeating: Data(repeating: 32, count: 65_536), count: 12))
        }
        await expectCheckFailure(session)
        XCTAssertLessThan(ReleaseProtocol.state.deliveredChunks, 12)
    }

    func testExactBodyBudgetAndLightweightTagStillResolve() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in
            var plan = ReleasePlan.json(Self.response(for: request))
            if request.url?.lastPathComponent == "latest" {
                var data = plan.chunks[0]
                data.append(Data(repeating: 32, count: 512 * 1024 - data.count))
                plan.chunks = [data]
                plan.contentLength = data.count
            }
            return plan
        }
        let result = try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable()
        XCTAssertEqual(result.version, "0.21.6")
        XCTAssertEqual(result.commit, String(repeating: "a", count: 40))
    }

    func testCancellationStopsAnUnfinishedRequest() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in
            var plan = ReleasePlan.json(Self.response(for: request))
            plan.delay = 10
            return plan
        }
        let task = Task { try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable() }
        for _ in 0..<100 {
            if !ReleaseProtocol.state.requests.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(ReleaseProtocol.state.requests.isEmpty)
        task.cancel()
        do { _ = try await task.value; XCTFail("cancelled request succeeded") } catch {}
        for _ in 0..<100 {
            if ReleaseProtocol.state.stops > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertGreaterThan(ReleaseProtocol.state.stops, 0)
        XCTAssertEqual(ReleaseProtocol.state.deliveredChunks, 0)
    }

    func testOneDeadlineBoundsTheWholeReleaseAndTagLookup() async throws {
        let session = session(); defer { session.invalidateAndCancel() }
        ReleaseProtocol.state.handler = { request in .json(Self.response(for: request)) }
        let fetcher = DesktopHermesOfficialReleaseFetcher(session: session)
        do {
            _ = try await fetcher.latestStable(deadline: ContinuousClock.now.advanced(by: .milliseconds(300)))
            XCTFail("a tag lookup received a new full timeout budget")
        } catch {}
        XCTAssertLessThanOrEqual(ReleaseProtocol.state.requests.count, 2)
    }

    private func session(headers: [String: String] = [:]) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ReleaseProtocol.self]
        configuration.httpAdditionalHeaders = headers
        return URLSession(configuration: configuration)
    }
    private func expectCheckFailure(_ session: URLSession, file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await DesktopHermesOfficialReleaseFetcher(session: session).latestStable()
            XCTFail("unsafe response selected a release", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? DesktopHermesUpgradeError, .checkFailed, file: file, line: line)
        }
    }
    private static func response(for request: URLRequest) -> [String: Any] {
        request.url?.lastPathComponent == "latest"
            ? ["draft": false, "prerelease": false, "tag_name": "v0.21.6"]
            : ["object": ["type": "commit", "sha": String(repeating: "a", count: 40)]]
    }
}

private struct ReleasePlan {
    var chunks: [Data]
    var contentLength: Int? = nil
    var url: URL? = nil
    var delay: TimeInterval = 0.1
    static func json(_ object: [String: Any]) -> Self {
        .init(chunks: [try! JSONSerialization.data(withJSONObject: object)])
    }
}

private final class ReleaseProtocol: URLProtocol, @unchecked Sendable {
    static let state = State()
    private let lock = NSLock()
    private var stopped = false
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let plan = Self.state.begin(request)
        var headers = [String: String]()
        if let length = plan.contentLength { headers["Content-Length"] = String(length) }
        let response = HTTPURLResponse(url: plan.url ?? request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        send(plan, index: 0)
    }
    private func send(_ plan: ReleasePlan, index: Int) {
        DispatchQueue.global().asyncAfter(deadline: .now() + plan.delay) { [self] in
            lock.lock(); let active = !stopped; lock.unlock()
            guard active else { return }
            guard index < plan.chunks.count else { client?.urlProtocolDidFinishLoading(self); return }
            Self.state.delivered()
            client?.urlProtocol(self, didLoad: plan.chunks[index])
            send(plan, index: index + 1)
        }
    }
    override func stopLoading() {
        lock.lock(); stopped = true; lock.unlock()
        Self.state.stopped()
    }

    final class State: @unchecked Sendable {
        private let lock = NSLock()
        private var callback: ((URLRequest) -> ReleasePlan)?
        private var seen = [URLRequest]()
        private var chunks = 0, stopCount = 0
        var handler: ((URLRequest) -> ReleasePlan)? {
            get { lock.lock(); defer { lock.unlock() }; return callback }
            set { lock.lock(); callback = newValue; lock.unlock() }
        }
        var requests: [URLRequest] { lock.lock(); defer { lock.unlock() }; return seen }
        var deliveredChunks: Int { lock.lock(); defer { lock.unlock() }; return chunks }
        var stops: Int { lock.lock(); defer { lock.unlock() }; return stopCount }
        func begin(_ request: URLRequest) -> ReleasePlan {
            lock.lock(); seen.append(request); let callback = callback; lock.unlock()
            return callback!(request)
        }
        func delivered() { lock.lock(); chunks += 1; lock.unlock() }
        func stopped() { lock.lock(); stopCount += 1; lock.unlock() }
        func reset() { lock.lock(); callback = nil; seen = []; chunks = 0; stopCount = 0; lock.unlock() }
    }
}

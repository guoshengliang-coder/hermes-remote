import CryptoKit
import Foundation

public protocol HermesRecommendationFetching: Sendable {
    func fetch() async throws -> Data
}

/// Public transport, independent of the account session and its credentials.
public struct HermesRecommendationHTTPFetcher: HermesRecommendationFetching {
    private let url: URL
    private let configuration: URLSessionConfiguration
    public init(origin: String, protocolClasses: [AnyClass]? = nil) throws {
        // Reuse the verifier's strict origin grammar without introducing a second URL policy.
        _ = try HermesCompatibilityRecommendationVerifier(origin: origin, keys: ["transport": Data(repeating: 0, count: 32)])
        url = URL(string: origin + "/v2/hermes-compatibility/recommendations")!
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = protocolClasses
        configuration.httpAdditionalHeaders = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.waitsForConnectivity = false
        self.configuration = configuration
    }
    public func fetch() async throws -> Data {
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        do {
            var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 20)
            request.httpShouldHandleCookies = false
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
            let deadline = ContinuousClock.now.advanced(by: .seconds(30))
            let (bytes, response) = try await session.bytes(for: request, delegate: RecommendationRedirectGuard())
            guard let http = response as? HTTPURLResponse, http.statusCode == 200, http.url == url,
                  response.expectedContentLength <= 512 * 1024 else { throw Self.issue }
            var data = Data()
            for try await byte in bytes {
                try Task.checkCancellation()
                guard data.count < 512 * 1024, ContinuousClock.now < deadline else { throw Self.issue }
                data.append(byte)
            }
            return data
        } catch is CancellationError { throw CancellationError() }
        catch { throw Self.issue }
    }
    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
}

private final class RecommendationRedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

public protocol HermesRecommendationRevisionStoring: Sendable {
    /// Atomically rejects rollback and different content at an already observed revision.
    /// A persistence failure must fail the fetch, never silently reset to revision zero.
    func accept(origin: String, revision: Int, payloadSha256: String) throws
}

public final class HermesRecommendationRevisionStore: HermesRecommendationRevisionStoring, @unchecked Sendable {
    private static let lock = NSLock()
    private let defaults: UserDefaults
    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    public func accept(origin: String, revision: Int, payloadSha256: String) throws {
        try Self.lock.withLock {
            let issue = DesktopIssue(code: .hermesCompatibilityRecommendationInvalid)
            guard (1...9_007_199_254_740_991).contains(revision),
                  payloadSha256.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil else { throw issue }
            let originHash = SHA256.hash(data: Data(origin.utf8)).map { String(format: "%02x", $0) }.joined()
            let key = "hermes.compatibility.revision.v1." + originHash
            struct Record: Codable { let revision: Int; let digest: String }
            if let existing = defaults.object(forKey: key) {
                guard let data = existing as? Data, let previous = try? JSONDecoder().decode(Record.self, from: data),
                      (1...9_007_199_254_740_991).contains(previous.revision),
                      previous.digest.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil,
                      revision >= previous.revision,
                      revision != previous.revision || payloadSha256 == previous.digest else { throw issue }
            }
            defaults.set(try JSONEncoder().encode(Record(revision: revision, digest: payloadSha256)), forKey: key)
            guard defaults.synchronize() else { throw issue }
        }
    }
}

/// Each refresh contacts the public endpoint. Failed refreshes never return cached authorization.
/// The caller must refresh again immediately before starting a mutation, then recheck local identity.
public protocol HermesRecommendationRefreshing: Sendable {
    func refresh() async throws -> VerifiedHermesRecommendation
}

public actor HermesCompatibilityRecommendationClient: HermesRecommendationRefreshing {
    /// Public recommendation requests use the account service origin and existing pinned release
    /// keys, but never its session. A build without release trust keeps this feature unavailable.
    public static func desktop(gatewayURL: URL, release: DesktopManagedBootstrapConfigurationState) throws
        -> HermesCompatibilityRecommendationClient? {
        switch release {
        case .disabled: return nil
        case .invalid: throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid)
        case .configured(let configuration):
            guard var origin = URLComponents(url: gatewayURL, resolvingAgainstBaseURL: false),
                  origin.path.isEmpty || origin.path == "/" else {
                throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid)
            }
            origin.path = ""
            guard let value = origin.string else { throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
            return try HermesCompatibilityRecommendationClient(origin: value, keys: configuration.signingKeys)
        }
    }

    private let origin: String
    private let fetcher: any HermesRecommendationFetching
    private let verifier: HermesCompatibilityRecommendationVerifier
    private let revisions: any HermesRecommendationRevisionStoring
    private let now: @Sendable () -> Date
    public init(origin: String, keys: [String: Data], fetcher: (any HermesRecommendationFetching)? = nil,
                revisions: any HermesRecommendationRevisionStoring = HermesRecommendationRevisionStore(),
                now: @escaping @Sendable () -> Date = { Date() }) throws {
        self.origin = origin
        self.verifier = try HermesCompatibilityRecommendationVerifier(origin: origin, keys: keys)
        self.fetcher = try fetcher ?? HermesRecommendationHTTPFetcher(origin: origin)
        self.revisions = revisions
        self.now = now
    }
    public func refresh() async throws -> VerifiedHermesRecommendation {
        do {
            let data = try await fetcher.fetch()
            try Task.checkCancellation()
            // Persistence performs the revision comparison AFTER the await, including across clients.
            let verified = try verifier.verify(data, minimumRevision: 0, now: now())
            try revisions.accept(origin: origin, revision: verified.revision, payloadSha256: verified.payloadSha256)
            return verified
        } catch is CancellationError { throw CancellationError() }
        catch { throw DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
    }
}

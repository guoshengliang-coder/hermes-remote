import CFNetwork
import Foundation

public struct ProbeResult: Equatable, Sendable {
    public let level: HealthLevel
    public let detail: String
    public let latencyMilliseconds: Int?
    public let issue: DesktopIssue?

    public init(
        level: HealthLevel,
        detail: String,
        latencyMilliseconds: Int? = nil,
        issue: DesktopIssue? = nil
    ) {
        self.level = level
        self.detail = detail
        self.latencyMilliseconds = latencyMilliseconds
        self.issue = issue
    }
}

public struct HTTPHealthProber {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public static func loopbackDirect() -> HTTPHealthProber {
        HTTPHealthProber(session: URLSession(configuration: loopbackDirectConfiguration(),
                                             delegate: LoopbackRedirectGuard(), delegateQueue: nil))
    }

    static func loopbackDirectConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        // A system HTTP/PAC proxy may otherwise receive requests for 127.0.0.1. Candidate
        // readiness must prove the process on this Mac, never a proxy response or timeout.
        configuration.connectionProxyDictionary = [
            kCFNetworkProxiesHTTPEnable as String: false,
            kCFNetworkProxiesHTTPSEnable as String: false,
            kCFNetworkProxiesSOCKSEnable as String: false,
            kCFNetworkProxiesFTPEnable as String: false,
            kCFNetworkProxiesProxyAutoConfigEnable as String: false,
            kCFNetworkProxiesProxyAutoDiscoveryEnable as String: false,
        ]
        return configuration
    }

    public func probeRelay(_ url: URL) async -> ProbeResult {
        await probe(url, kind: .relay)
    }

    public func probeHermes(_ url: URL) async -> ProbeResult {
        await probe(url, kind: .hermes)
    }

    /// The public status endpoint alone cannot prove Connector's local authentication works.
    /// Never accept a caller-supplied destination for this installation credential.
    public func probeAuthenticatedHermes(sessionToken: String) async -> ProbeResult {
        for path in ["/api/profiles/active", "/api/sessions?limit=1"] {
            let url = URL(string: path, relativeTo: DesktopHermesRuntimeContract.serveV1.baseURL)!.absoluteURL
            var request = URLRequest(url: url)
            request.timeoutInterval = 5
            request.cachePolicy = .reloadIgnoringLocalCacheData
            request.setValue(sessionToken, forHTTPHeaderField: "X-Hermes-Session-Token")
            do {
                let (data, response) = try await session.data(for: request)
                guard let response = response as? HTTPURLResponse else { return localFailure("non-http") }
                if path == "/api/profiles/active", [404, 405].contains(response.statusCode) { continue }
                guard (200..<300).contains(response.statusCode) else { return localFailure("http-\(response.statusCode)") }
                guard data.count <= 2 * 1024 * 1024,
                      (try? JSONSerialization.jsonObject(with: data)) is [String: Any]
                else { return localFailure("invalid-json") }
                return ProbeResult(level: .healthy, detail: "本机 Hermes 认证接口可用")
            } catch { return localFailure("transport") }
        }
        return localFailure("missing-route")
    }

    public func probeModelConfiguration(sessionToken: String) async -> DesktopModelConfiguration {
        let url = URL(string: "/api/model/info", relativeTo: DesktopHermesRuntimeContract.serveV1.baseURL)!.absoluteURL
        var request = URLRequest(url: url)
        request.timeoutInterval = 5
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(sessionToken, forHTTPHeaderField: "X-Hermes-Session-Token")
        do {
            let (data, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse, response.statusCode == 200 else { return .unknown }
            return DesktopModelConfiguration.parse(data)
        } catch { return .unknown }
    }

    private func localFailure(_ reason: String) -> ProbeResult {
        let issue = DesktopIssue(code: .hermesUnavailable, technicalCause: "stage=local-hermes-auth result=\(reason)")
        return ProbeResult(level: .failed, detail: issue.displayChinese, issue: issue)
    }

    private enum Kind {
        case relay
        case hermes
    }

    private func probe(_ url: URL, kind: Kind) async -> ProbeResult {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let startedAt = ContinuousClock.now

        do {
            let (_, response) = try await session.data(for: request)
            let elapsed = startedAt.duration(to: .now)
            let latency = Int(elapsed.components.seconds * 1_000)
                + Int(elapsed.components.attoseconds / 1_000_000_000_000_000)
            guard let http = response as? HTTPURLResponse else {
                return ProbeResult(level: .failed, detail: "响应格式无效")
            }
            if (200..<300).contains(http.statusCode) {
                return ProbeResult(level: .healthy, detail: "响应正常", latencyMilliseconds: latency)
            }
            if kind == .hermes && (http.statusCode == 401 || http.statusCode == 403) {
                return ProbeResult(level: .degraded, detail: "服务可达，需要认证", latencyMilliseconds: latency)
            }
            return ProbeResult(
                level: .failed,
                detail: "HTTP \(http.statusCode)",
                latencyMilliseconds: latency
            )
        } catch {
            return ProbeResult(
                level: .failed,
                detail: "当前无法访问",
                latencyMilliseconds: nil
            )
        }
    }
}

/// Even a loopback response must not forward the token to another host or port.
final class LoopbackRedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

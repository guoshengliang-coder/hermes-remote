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

public extension HTTPHealthProber {
    func probeEndToEnd(_ profile: ConnectionProfile) async -> ProbeResult {
        guard let url = statusURL(for: profile.gatewayURL) else {
            let issue = DesktopIssue(code: .invalidRelayURL, technicalCause: "status URL construction failed")
            return ProbeResult(level: .failed, detail: issue.displayChinese, issue: issue)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 8
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(profile.appToken, forHTTPHeaderField: "X-Hermes-Session-Token")
        let startedAt = ContinuousClock.now

        do {
            let (data, response) = try await session.data(for: request)
            let latency = latencyMilliseconds(since: startedAt)
            guard let http = response as? HTTPURLResponse else {
                let issue = DesktopIssue(code: .connectionFailed, technicalCause: "non-HTTP response")
                return ProbeResult(level: .failed, detail: issue.displayChinese, latencyMilliseconds: latency, issue: issue)
            }
            if (200..<300).contains(http.statusCode) {
                return ProbeResult(level: .healthy, detail: "端到端连接正常", latencyMilliseconds: latency)
            }

            let errorCode = relayErrorCode(from: data)
            let issue: DesktopIssue
            switch http.statusCode {
            case 401, 403:
                issue = DesktopIssue(code: .appTokenRejected, technicalCause: "HTTP \(http.statusCode)")
            case 503 where errorCode == "device_offline":
                issue = DesktopIssue(code: .connectorOffline, technicalCause: "HTTP 503 error=device_offline")
            case 404:
                issue = DesktopIssue(code: .invalidRelayURL, technicalCause: "HTTP 404")
            default:
                issue = DesktopIssue(
                    code: .relayFailure,
                    technicalCause: "HTTP \(http.statusCode) error=\(errorCode ?? "unknown")"
                )
            }
            return ProbeResult(
                level: .failed,
                detail: issue.displayChinese,
                latencyMilliseconds: latency,
                issue: issue
            )
        } catch {
            let issue = DesktopIssue(
                code: .connectionFailed,
                technicalCause: String(describing: type(of: error))
            )
            return ProbeResult(level: .failed, detail: issue.displayChinese, issue: issue)
        }
    }

    private func statusURL(for baseURL: URL) -> URL? {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = components.path.isEmpty ? "/api/status" : "/\(components.path)/api/status"
        return components.url
    }

    private func relayErrorCode(from data: Data) -> String? {
        guard data.count <= 16 * 1_024,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let value = object["error"] as? String,
              value.count <= 128
        else { return nil }
        return value
    }

    private func latencyMilliseconds(since startedAt: ContinuousClock.Instant) -> Int {
        let elapsed = startedAt.duration(to: .now)
        return Int(elapsed.components.seconds * 1_000)
            + Int(elapsed.components.attoseconds / 1_000_000_000_000_000)
    }
}

public struct HTTPHealthProber {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func probeRelay(_ url: URL) async -> ProbeResult {
        await probe(url, kind: .relay)
    }

    public func probeHermes(_ url: URL) async -> ProbeResult {
        await probe(url, kind: .hermes)
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

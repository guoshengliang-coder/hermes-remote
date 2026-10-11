import Foundation

/// A release selected from upstream. The commit, rather than a moving channel head, is confirmed.
public struct DesktopHermesUpgradeTarget: Codable, Equatable, Sendable {
    public let version: String
    public let commit: String
    public let channel: String
    public init(version: String, commit: String, channel: String = "stable") throws {
        guard version.range(of: "^(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})$", options: .regularExpression) != nil,
              DesktopLocalHermesDetector.isObjectID(commit), channel == "stable"
        else { throw DesktopHermesUpgradeError.invalidRelease }
        self.version = version; self.commit = commit; self.channel = channel
    }
    private enum CodingKeys: String, CodingKey { case version, commit, channel }
    public init(from decoder: any Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        try self.init(version: values.decode(String.self, forKey: .version),
                      commit: values.decode(String.self, forKey: .commit),
                      channel: values.decode(String.self, forKey: .channel))
    }
}

public enum DesktopHermesUpgradeError: Error, Equatable, Sendable {
    case invalidRelease, checkFailed, preflightBlocked, recoveryRequired, verificationFailed
    public var issue: DesktopIssue {
        let code: DesktopIssueCode = switch self {
        case .invalidRelease, .checkFailed: .hermesUpdateCheckFailed
        case .preflightBlocked: .hermesUpdateBlocked
        case .recoveryRequired: .hermesUpdateRecoveryRequired
        case .verificationFailed: .hermesUpdateVerificationFailed
        }
        return DesktopIssue(code: code, technicalCause: "hermes_upgrade=\(self)")
    }
}

public struct DesktopHermesUpgradeReport: Equatable, Sendable {
    public let installation: DesktopLocalHermesDetection
    public let latestStable: DesktopHermesUpgradeTarget
    public let checkedAt: Date
    /// A target only becomes recommended after the GO contract matrix verifies that exact commit.
    public let verifiedRecommendation: DesktopHermesUpgradeTarget?
    public var hasNewerStable: Bool {
        guard let local = installation.installation else { return false }
        return DesktopLocalHermesDetector.version(local.version, isOlderThan: latestStable.version)
    }
    public var latestStableIsVerified: Bool { verifiedRecommendation == latestStable }
}

public protocol DesktopHermesReleaseFetching: Sendable {
    func latestStable() async throws -> DesktopHermesUpgradeTarget
}

public struct DesktopHermesOfficialReleaseFetcher: DesktopHermesReleaseFetching {
    private let session: URLSession
    public init(session: URLSession = .shared) { self.session = session }
    public func latestStable() async throws -> DesktopHermesUpgradeTarget {
        let release = try await json("releases/latest")
        guard release["draft"] as? Bool == false, release["prerelease"] as? Bool == false,
              let tag = release["tag_name"] as? String, tag.hasPrefix("v")
        else { throw DesktopHermesUpgradeError.invalidRelease }
        // Validate before embedding a tag in a URL. target_commitish may be "main"; never use it.
        _ = try DesktopHermesUpgradeTarget(version: String(tag.dropFirst()), commit: String(repeating: "a", count: 40))
        var reference = try await json("git/ref/tags/\(tag)")
        for _ in 0..<4 {
            guard let object = reference["object"] as? [String: Any],
                  let sha = object["sha"] as? String, DesktopLocalHermesDetector.isObjectID(sha),
                  let type = object["type"] as? String
            else { throw DesktopHermesUpgradeError.invalidRelease }
            if type == "commit" { return try DesktopHermesUpgradeTarget(version: String(tag.dropFirst()), commit: sha) }
            guard type == "tag" else { throw DesktopHermesUpgradeError.invalidRelease }
            reference = try await json("git/tags/\(sha)")
        }
        throw DesktopHermesUpgradeError.invalidRelease
    }
    private func json(_ path: String) async throws -> [String: Any] {
        let url = URL(string: "https://api.github.com/repos/NousResearch/hermes-agent/" + path)!
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Hermes-GO-Desktop", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              http.url?.scheme == "https", http.url?.host == "api.github.com",
              data.count <= 512 * 1024,
              let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw DesktopHermesUpgradeError.checkFailed }
        return value
    }
}

public struct DesktopHermesUpgradeChecker: Sendable {
    private let fetcher: any DesktopHermesReleaseFetching
    private let verifiedTargets: [DesktopHermesUpgradeTarget]
    public init(fetcher: any DesktopHermesReleaseFetching = DesktopHermesOfficialReleaseFetcher(),
                verifiedTargets: [DesktopHermesUpgradeTarget] = []) {
        self.fetcher = fetcher; self.verifiedTargets = verifiedTargets
    }
    public func check(installation: DesktopLocalHermesDetection, now: Date = Date()) async throws -> DesktopHermesUpgradeReport {
        let latest = try await fetcher.latestStable()
        let recommendation = verifiedTargets.first { $0 == latest }
        return DesktopHermesUpgradeReport(installation: installation, latestStable: latest,
                                          checkedAt: now, verifiedRecommendation: recommendation)
    }
}

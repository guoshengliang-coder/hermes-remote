import Foundation

/// A presentation assessment of a fresh signed snapshot. It never authorizes a mutation.
public struct HermesRecommendationAssessment: Sendable {
    public enum Status: Equatable, Sendable {
        case unchecked, unconfigured, checking, failed, installationUnknown, installationUnsupported, noPath, requirements, matched
    }
    public let status: Status
    public let entry: VerifiedHermesRecommendation.Entry?
    public let unknownComponents: [String]
    public let mismatchedComponents: [String]
    public let revision: Int?
    public let expiresAt: Date?
    public var issue: DesktopIssue? {
        status == .failed ? DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) : nil
    }
    private init(_ status: Status, entry: VerifiedHermesRecommendation.Entry? = nil,
                 unknown: [String] = [], mismatched: [String] = [], snapshot: VerifiedHermesRecommendation? = nil) {
        self.status = status; self.entry = entry; unknownComponents = unknown; mismatchedComponents = mismatched
        revision = snapshot?.revision; expiresAt = snapshot?.expiresAt
    }
    public static let unchecked = Self(.unchecked)
    public static let unconfigured = Self(.unconfigured)
    public static let checking = Self(.checking)
    public static let failed = Self(.failed)

    public func current(at now: Date = Date()) -> Self {
        if let expiresAt, now >= expiresAt { return .failed }
        return self
    }

    public static func evaluate(_ snapshot: VerifiedHermesRecommendation,
                                installation: DesktopLocalHermesDetection,
                                environment: HermesCompatibilityEnvironment?,
                                components: [HermesCompatibilityComponent], now: Date = Date()) -> Self {
        guard now < snapshot.expiresAt else { return .failed }
        guard let local = installation.installation,
              HermesCompatibilitySnapshot.version(local.version) else { return Self(.installationUnknown, snapshot: snapshot) }
        guard let environment else { return Self(.installationUnsupported, snapshot: snapshot) }
        let names = ["desktop", "connector", "gateway", "android", "web"]
        guard Set(components.map(\.name)).count == components.count,
              components.allSatisfy({ names.contains($0.name) }) else { return .failed }
        let observed = Dictionary(uniqueKeysWithValues: components.map { ($0.name, $0) })
        let candidates = snapshot.entries.filter {
            $0.identity.sourceCommit == local.commit && $0.identity.environment == environment
                && DesktopSemanticVersion.isNewer($0.identity.version, than: local.version)
        }.sorted { DesktopSemanticVersion.isNewer($0.identity.version, than: $1.identity.version) }
        let canonical = names.compactMap { observed[$0] }
        if let matched = snapshot.newestUpgrade(sourceCommit: local.commit, currentVersion: local.version,
                                                environment: environment, components: canonical, now: now) {
            return Self(.matched, entry: matched, snapshot: snapshot)
        }
        // Prefer a candidate whose known components already match, while keeping unknown clients
        // explicit. If none fits, describe the highest candidate's unmet requirements.
        guard let candidate = candidates.first(where: { entry in
            entry.identity.components.allSatisfy { observed[$0.name] == nil || observed[$0.name] == $0 }
        }) ?? candidates.first else { return Self(.noPath, snapshot: snapshot) }
        let unknown = names.filter { observed[$0] == nil }
        let mismatched = candidate.identity.components.filter { observed[$0.name] != nil && observed[$0.name] != $0 }.map(\.name)
        return Self(.requirements, entry: candidate, unknown: unknown, mismatched: mismatched, snapshot: snapshot)
    }

    /// Select a digest to verify, not an identity to trust. Conflicting archive identities for the
    /// same installed version stay unknown; an unsigned bundle version never satisfies a record.
    public static func desktopRequirement(_ snapshot: VerifiedHermesRecommendation,
                                          installation: DesktopLocalHermesDetection,
                                          environment: HermesCompatibilityEnvironment?,
                                          installedVersion: String, now: Date = Date()) -> HermesCompatibilityComponent? {
        guard now < snapshot.expiresAt, let local = installation.installation, let environment else { return nil }
        let requirements = snapshot.entries.filter {
            $0.identity.sourceCommit == local.commit && $0.identity.environment == environment
                && DesktopSemanticVersion.isNewer($0.identity.version, than: local.version)
        }.compactMap { $0.identity.components.first { $0.name == "desktop" && $0.version == installedVersion } }
        guard let first = requirements.first, requirements.allSatisfy({ $0 == first }) else { return nil }
        return first
    }

    public var summaryChinese: String {
        switch status {
        case .unchecked: "尚未查询"
        case .unconfigured: "当前版本尚未启用推荐查询"
        case .checking: "正在查询…"
        case .failed: "无法确认"
        case .installationUnknown: "本机安装身份尚未确认"
        case .installationUnsupported: "本机安装条件尚未满足"
        case .noPath: "暂无匹配本机的升级路径"
        case .requirements: "\(entry!.identity.version)（条件待确认）"
        case .matched: "\(entry!.identity.version)（组件已匹配）"
        }
    }
    public var detailChinese: String {
        switch status {
        case .requirements:
            let unknown = unknownComponents.map(Self.componentLabel).joined(separator: "、")
            let mismatched = mismatchedComponents.map(Self.componentLabel).joined(separator: "、")
            return [unknown.isEmpty ? nil : "尚未核验：\(unknown)。",
                    mismatched.isEmpty ? nil : "不满足组件要求：\(mismatched)。",
                    "条件未满足前不能作为本机升级推荐。"].compactMap { $0 }.joined()
        case .matched: return "本机条件已匹配已发布的验证记录；一键升级尚未开放。"
        case .noPath: return "已读取推荐清单，未找到符合当前来源版本、系统和安装类型的更高版本；不会自动降级。"
        case .installationUnknown: return "先确认本机 Hermes 的安装类型和版本，再匹配升级路径。"
        case .installationUnsupported: return "当前安装布局或依赖状态尚不满足已验证的升级路径。"
        case .failed: return "请重新检查版本信息；此前的推荐判断不再使用。"
        default: return "兼容推荐按本机条件匹配，与上游最新发布分开查询。"
        }
    }
    public static func componentLabel(_ name: String) -> String {
        switch name {
        case "desktop": "Desktop"
        case "connector": "Connector"
        case "gateway": "云端服务"
        case "android": "Android"
        case "web": "Web"
        default: "未知组件"
        }
    }
}

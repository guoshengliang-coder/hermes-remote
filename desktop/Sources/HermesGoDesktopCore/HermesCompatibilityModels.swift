import Foundation

public protocol HermesCompatibilityAPIRequesting: Sendable {
    func hermesCompatibilityAccess(accessToken: String) async throws -> HermesCompatibilityAccess
    func hermesCompatibilitySnapshot(accessToken: String) async throws -> HermesCompatibilitySnapshot
    func syncHermesCompatibility(accessToken: String, operationID: String, expectedRevision: Int) async throws
    func requestHermesCompatibilityRun(accessToken: String, operationID: String, expectedRevision: Int, candidateID: String, recipeID: String) async throws
    func hermesCompatibilityReport(accessToken: String, runID: String) async throws -> HermesCompatibilityRunDetail
    func manageHermesCompatibility(accessToken: String, operationID: String, expectedRevision: Int, action: HermesCompatibilityManagementAction) async throws
}

public enum HermesCompatibilityManagementAction: Equatable, Sendable {
    case approve(runID: String, reportSha256: String)
    case publish
    case revoke(decisionID: String)
    var path: String { switch self { case .approve: "approve"; case .publish: "publish"; case .revoke: "revoke" } }
    var operationName: String {
        switch self {
        case let .approve(runID, hash): "approve:\(runID):\(hash)"
        case .publish: "publish"
        case let .revoke(id): "revoke:\(id)"
        }
    }
}

public struct HermesCompatibilityAccess: Decodable, Equatable, Sendable {
    public let schemaVersion: Int
    public let canManage: Bool
    public let revision: Int
}
public struct HermesCompatibilitySnapshot: Decodable, Equatable, Sendable {
    public struct Publication: Decodable, Equatable, Sendable, Identifiable {
        public let id: String
        public let revision: String
        public let state: String
        public let workflowRunId: String?
        public let signed: Bool
        public var stateTitle: String {
            switch state {
            case "published": "已发布"
            case "failed": "发布失败 · 请刷新后重试"
            case "pending": signed ? "等待公开回读" : workflowRunId == nil ? "等待发布任务或审批" : "正在签名发布"
            default: "未知状态 · 请刷新"
            }
        }
    }
    public struct Recipe: Decodable, Equatable, Sendable, Identifiable {
        public struct Identity: Decodable, Equatable, Sendable {
            public struct Environment: Decodable, Equatable, Sendable {
                public let os: String
                public let arch: String
                public let osVersion: String
                public let installation: String
            }
            public let sourceCommit: String
            public let goCommit: String
            public let environment: Environment
        }
        public let id: String
        public let identity: Identity
        public var title: String { "macOS \(identity.environment.osVersion) · \(identity.environment.arch) · 起点 \(identity.sourceCommit.prefix(8))" }
    }
    public struct Candidate: Decodable, Equatable, Sendable, Identifiable {
        public let id: String
        public let version: String
        public let tag: String
        public let targetCommit: String
        public let identityConflict: Bool
    }
    public struct Identity: Decodable, Equatable, Sendable {
        public let version: String
        public let sourceCommit: String
        public let targetCommit: String
        public let goCommit: String
    }
    public struct Run: Decodable, Equatable, Sendable, Identifiable {
        public let id: String
        public let candidateId: String
        public let identity: Identity
        public let state: String
        public let reportSha256: String?
        public var stateTitle: String { Self.title(state) }
        static func title(_ state: String) -> String {
            switch state {
            case "queued": "等待验证"
            case "running": "正在验证"
            case "passed": "验证通过 · 尚需审批"
            case "failed": "验证未通过"
            case "infrastructure_failed": "验证环境异常"
            case "cancelled": "已取消"
            default: "未知状态 · 请刷新"
            }
        }
    }
    public struct Decision: Decodable, Equatable, Sendable, Identifiable {
        public let id: String
        public let runId: String
        public let state: String
        public var stateTitle: String {
            switch state {
            case "approved": "已批准 · 等待发布"
            case "publishing": "正在发布"
            case "published": "已发布"
            case "publication_failed": "发布未完成"
            case "revoked": "已撤回"
            default: "未知状态 · 请刷新"
            }
        }
    }
    public struct Audit: Decodable, Equatable, Sendable, Identifiable {
        public let id: String
        public let revision: String
        public let action: String
        public let occurredAt: String
        public var title: String {
            switch action {
            case "admin_granted": "授予管理员"
            case "admin_revoked": "撤销管理员"
            case "candidate_discovered": "同步上游候选"
            case "candidate_conflict": "发现版本身份冲突"
            case "run_requested": "请求验证"
            case "run_dispatched": "派发验证"
            case "run_started": "开始验证"
            case "run_completed": "记录验证结果"
            case "run_cancelled": "取消验证"
            case "decision_approved": "批准推荐"
            case "decision_revoked": "撤回推荐"
            case "publication_started": "开始发布"
            case "publication_dispatched": "派发发布任务"
            case "publication_completed": "完成发布"
            case "publication_failed": "发布失败"
            default: "未知操作"
            }
        }
    }
    public let schemaVersion: Int
    public let revision: Int
    public let candidates: [Candidate]
    public let runs: [Run]
    public let decisions: [Decision]
    public let audit: [Audit]
    public let recipes: [Recipe]?
    public let publications: [Publication]?

    public func validated() throws -> Self {
        guard schemaVersion == 1, (0...9_007_199_254_740_991).contains(revision), candidates.count <= 25, runs.count <= 25,
              decisions.count <= 25, audit.count <= 50, (recipes?.count ?? 0) <= 10,
              (publications?.count ?? 0) <= 10,
              (publications ?? []).allSatisfy({ UUID(uuidString: $0.id) != nil && Int($0.revision).map { $0 > 0 && $0 <= 9_007_199_254_740_991 } == true
                  && ($0.workflowRunId == nil || $0.workflowRunId?.range(of: #"\A[1-9][0-9]{0,19}\z"#, options: .regularExpression) != nil) }),
              (recipes ?? []).allSatisfy({ $0.id.range(of: #"\A[a-z0-9][a-z0-9-]{0,63}\z"#, options: .regularExpression) != nil
                  && Self.commit($0.identity.sourceCommit) && Self.commit($0.identity.goCommit)
                  && Self.version($0.identity.environment.osVersion) && $0.identity.environment.os == "macos"
                  && $0.identity.environment.arch == "arm64" && $0.identity.environment.installation == "legacy-venv-to-pm" }),
              Set((recipes ?? []).map(\.id)).count == (recipes?.count ?? 0),
              candidates.allSatisfy({ UUID(uuidString: $0.id) != nil && Self.commit($0.targetCommit) && Self.version($0.version) }),
              runs.allSatisfy({ UUID(uuidString: $0.id) != nil && Self.commit($0.identity.sourceCommit)
                  && Self.commit($0.identity.targetCommit) && Self.commit($0.identity.goCommit) && Self.version($0.identity.version)
                  && ($0.reportSha256 == nil || $0.reportSha256?.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil) }),
              Set(candidates.map(\.id)).count == candidates.count,
              Set(runs.map(\.id)).count == runs.count,
              Set(decisions.map(\.id)).count == decisions.count,
              decisions.allSatisfy({ UUID(uuidString: $0.id) != nil && UUID(uuidString: $0.runId) != nil })
        else { throw AccountClientError.invalidResponse }
        return self
    }
    static func commit(_ value: String) -> Bool {
        value.range(of: #"\A[a-f0-9]{40}\z"#, options: .regularExpression) != nil
    }
    static func version(_ value: String) -> Bool {
        value.utf8.count <= 29 && value.range(of: #"\A(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\z"#, options: .regularExpression) != nil
    }
}

public struct HermesCompatibilityRunDetail: Decodable, Equatable, Sendable {
    public struct Report: Decodable, Equatable, Sendable {
        public struct Check: Decodable, Equatable, Sendable, Identifiable {
            public let name: String
            public let status: String
            public let evidenceSha256: String?
            public var id: String { name }
            public var title: String { Self.titles[name] ?? "未知检查项" }
            static let titles = ["installation":"安装", "authentication":"认证", "rest":"REST 接口", "rpc":"RPC 接口",
                "history":"历史会话", "configuration":"配置", "connector":"Connector", "serviceRecovery":"服务恢复",
                "upgrade":"升级", "recovery":"回退", "concurrentUpdate":"并发升级", "processInterruption":"进程中断",
                "desktopLifecycle":"Desktop 生命周期", "clientBusiness":"客户端业务", "newDataProtection":"新增数据保护"]
            public var stateTitle: String {
                switch status { case "passed": "通过"; case "failed": "未通过"; case "infrastructure_failed": "环境异常";
                case "not_run": "未执行"; default: "未知状态" }
            }
        }
        public let schemaVersion: Int
        public let runId: String
        public let identity: HermesCompatibilitySnapshot.Identity
        public let startedAt: String
        public let finishedAt: String
        public let outcome: String
        public let checks: [Check]
        public let evidenceBundleSha256: String
    }
    public let id: String
    public let state: String
    public let identity: HermesCompatibilitySnapshot.Identity
    public let report: Report?
    public let reportSha256: String?
    public var mayRequestApproval: Bool {
        state == "passed" && reportSha256 != nil && report?.checks.count == 15
            && report?.checks.allSatisfy({ $0.status == "passed" }) == true
    }
    public func validated(runID: String) throws -> Self {
        let hash: (String) -> Bool = { $0.range(of: #"\A[a-f0-9]{64}\z"#, options: .regularExpression) != nil }
        guard id == runID.lowercased(), UUID(uuidString: id) != nil,
              HermesCompatibilitySnapshot.commit(identity.sourceCommit), HermesCompatibilitySnapshot.commit(identity.targetCommit),
              HermesCompatibilitySnapshot.commit(identity.goCommit), HermesCompatibilitySnapshot.version(identity.version),
              reportSha256.map(hash) ?? true else { throw AccountClientError.invalidResponse }
        if let report {
            guard report.schemaVersion == 1, report.runId == id, report.identity == identity, report.outcome == state,
                  report.checks.count <= 15, Set(report.checks.map(\.name)).count == report.checks.count,
                  hash(report.evidenceBundleSha256), reportSha256 != nil,
                  report.checks.allSatisfy({ Report.Check.titles[$0.name] != nil
                      && ["passed", "failed", "infrastructure_failed", "not_run"].contains($0.status)
                      && ($0.evidenceSha256.map(hash) ?? ($0.status != "passed")) })
            else { throw AccountClientError.invalidResponse }
        }
        return self
    }
}

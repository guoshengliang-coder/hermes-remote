import HermesGoDesktopCore
import SwiftUI

struct HermesCompatibilityView: View {
    @EnvironmentObject private var model: DesktopViewModel
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Hermes 兼容版本管理").font(.title2.bold())
                        Text("验证固定版本，审阅证据后再推荐给用户。")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if model.isHermesCompatibilityBusy { ProgressView().controlSize(.small) }
                }
                if let issue = model.hermesCompatibilityIssue {
                    Label(issue.displayChinese, systemImage: "exclamationmark.circle")
                        .foregroundStyle(.secondary).textSelection(.enabled)
                }
                if model.canManageHermesCompatibility {
                    HStack {
                        Button("同步上游版本") { Task { await model.refreshHermesCompatibility(syncUpstream: true) } }
                            .disabled(model.hermesCompatibilitySnapshot == nil)
                        Button("刷新") { Task { await model.refreshHermesCompatibility() } }
                        Button("发布推荐清单") { Task { await model.manageHermesCompatibility(.publish) } }
                            .disabled(model.hermesCompatibilitySnapshot?.decisions.isEmpty != false)
                    }.disabled(model.isHermesCompatibilityBusy)
                    if let snapshot = model.hermesCompatibilitySnapshot {
                        GroupBox("上游候选 · 最近 25 条") {
                            VStack(alignment: .leading, spacing: 12) {
                                if snapshot.candidates.isEmpty { Text("尚未同步候选版本。").foregroundStyle(.secondary) }
                                ForEach(snapshot.candidates) { candidate in
                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack {
                                            Text(candidate.version).font(.headline)
                                            Spacer()
                                            Text(candidate.identityConflict ? "版本身份冲突" : "候选 · 以验证报告为准")
                                                .font(.caption).foregroundStyle(.secondary)
                                        }
                                        Text(candidate.targetCommit).font(.caption.monospaced()).textSelection(.enabled)
                                        if let recipes = snapshot.recipes, !recipes.isEmpty {
                                            Menu("开始隔离验证") {
                                                ForEach(recipes) { recipe in
                                                    Button(recipe.title) {
                                                        Task { await model.requestHermesCompatibilityRun(candidateID: candidate.id, recipeID: recipe.id) }
                                                    }
                                                }
                                            }.disabled(candidate.identityConflict || model.isHermesCompatibilityBusy)
                                        } else {
                                            Text("验证环境尚未配置").font(.caption).foregroundStyle(.secondary)
                                        }
                                    }
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(8)
                        }
                        GroupBox("验证任务 · 最近 25 条") {
                            VStack(alignment: .leading, spacing: 12) {
                                if snapshot.runs.isEmpty {
                                    Text("尚无验证任务。验证环境就绪后可开始验证。")
                                        .foregroundStyle(.secondary)
                                }
                                ForEach(snapshot.runs) { run in
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text("Hermes \(run.identity.version) · \(run.stateTitle)")
                                        Text("目标 \(run.identity.targetCommit.prefix(12)) · GO \(run.identity.goCommit.prefix(12))")
                                            .font(.caption.monospaced()).foregroundStyle(.secondary)
                                        if let hash = run.reportSha256 {
                                            Button("查看验证报告") { Task { await model.loadHermesCompatibilityReport(runID: run.id) } }
                                                .disabled(model.isHermesCompatibilityBusy)
                                            DisclosureGroup("报告指纹") { Text(hash).font(.caption.monospaced()).textSelection(.enabled) }
                                        } else { Text("报告尚未生成").font(.caption).foregroundStyle(.secondary) }
                                        if let detail = model.hermesCompatibilityReport, detail.id == run.id, let report = detail.report {
                                            VStack(alignment: .leading, spacing: 8) {
                                                Text("\(report.checks.filter { $0.status == "passed" }.count) / 15 项通过")
                                                    .font(.callout.bold())
                                                Text("\(report.startedAt) — \(report.finishedAt)")
                                                    .font(.caption).foregroundStyle(.secondary)
                                                ForEach(report.checks) { check in
                                                    HStack { Text(check.title); Spacer(); Text(check.stateTitle).foregroundStyle(.secondary) }
                                                        .font(.callout)
                                                }
                                                if report.checks.count < 15 {
                                                    Text("部分检查尚未返回，不能批准推荐。").font(.caption).foregroundStyle(.secondary)
                                                }
                                                if detail.mayRequestApproval, let hash = detail.reportSha256,
                                                   !snapshot.decisions.contains(where: { $0.runId == run.id }) {
                                                    Button("批准推荐") { Task { await model.manageHermesCompatibility(.approve(runID: run.id, reportSha256: hash)) } }
                                                        .disabled(model.isHermesCompatibilityBusy)
                                                    Text("批准后仍需发布推荐清单；服务端会复核组件发布证据。")
                                                        .font(.caption).foregroundStyle(.secondary)
                                                }
                                            }.padding(12).background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 8))
                                        }
                                    }
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(8)
                        }
                        GroupBox("推荐决策 · 最近 25 条") {
                            VStack(alignment: .leading, spacing: 8) {
                                if snapshot.decisions.isEmpty { Text("尚无推荐决策。测试通过后仍需审批与发布。").foregroundStyle(.secondary) }
                                ForEach(snapshot.decisions) { decision in
                                    HStack {
                                        VStack(alignment: .leading) {
                                            Text(decision.stateTitle)
                                            Text("验证任务 \(decision.runId.prefix(8))").font(.caption.monospaced()).foregroundStyle(.secondary)
                                        }
                                        Spacer()
                                        if decision.state != "revoked" {
                                            Button("撤回推荐") { Task { await model.manageHermesCompatibility(.revoke(decisionID: decision.id)) } }
                                                .disabled(model.isHermesCompatibilityBusy)
                                        }
                                    }
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(8)
                        }
                        if let publications = snapshot.publications, !publications.isEmpty {
                            GroupBox("发布任务 · 最近 10 条") {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text("发布沿用现有受保护环境，可能需要在 GitHub 完成审批。")
                                        .font(.caption).foregroundStyle(.secondary)
                                    ForEach(publications) { publication in
                                        HStack {
                                            Text("修订 \(publication.revision) · \(publication.stateTitle)")
                                            Spacer()
                                            if let id = publication.workflowRunId,
                                               let url = URL(string: "https://github.com/guoshengliang-coder/hermes-remote/actions/runs/\(id)") {
                                                Link("查看任务", destination: url)
                                            }
                                        }
                                    }
                                }.frame(maxWidth: .infinity, alignment: .leading).padding(8)
                            }
                        }
                        DisclosureGroup("操作记录 · 最近 50 条") {
                            VStack(alignment: .leading, spacing: 8) {
                                ForEach(snapshot.audit) { event in
                                    HStack { Text(event.title); Spacer(); Text(event.occurredAt).font(.caption).foregroundStyle(.secondary) }
                                }
                            }.padding(.top, 8)
                        }
                    }
                } else {
                    Text("当前账号的管理权限尚未确认，请刷新账号状态。")
                        .foregroundStyle(.secondary)
                    Button("刷新账号状态") { Task { await model.refreshAccount() } }
                }
            }.padding(28).frame(maxWidth: 900, alignment: .leading)
        }.task { await model.refreshHermesCompatibility() }
    }
}

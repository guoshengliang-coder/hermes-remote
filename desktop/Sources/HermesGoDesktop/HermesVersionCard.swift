import HermesGoDesktopCore
import SwiftUI

/// Read-only upstream checks belong to this Mac's Hermes page, separate from Desktop updates.
struct HermesVersionCard: View {
    let report: DesktopHermesUpgradeReport?
    let readiness: DesktopHermesUpgradeReadiness?
    let issue: DesktopIssue?
    let isChecking: Bool
    let isBusy: Bool
    let checkVersions: () -> Void
    let checkConditions: () -> Void
    var record: DesktopHermesUpgradeRecord = .unchecked
    var isRefreshingRecord = false
    var refreshRecord: () -> Void = {}
    var recommendation = HermesRecommendationAssessment.unchecked

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("版本与升级")
                .font(.system(size: 16, weight: .bold))
            if let report {
                if let installation = report.installation.installation {
                    versionRow("安装版本", installation.version)
                    versionRow("代码提交", String(installation.commit.prefix(12)))
                } else {
                    Text("尚未识别出可管理的本机 Hermes")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                }
                versionRow("上游稳定版本", report.latestStable.version)
                versionRow("检查时间", report.checkedAt.formatted(date: .abbreviated, time: .shortened))
            } else {
                Text("检查本机 Hermes 与官方稳定发布。")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
            TimelineView(.periodic(from: .now, by: 30)) { context in
                let assessment = recommendation.current(at: context.date)
                VStack(alignment: .leading, spacing: 8) {
                    versionRow("GO 验证推荐", assessment.summaryChinese)
                    Text(assessment.detailChinese)
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let entry = assessment.entry {
                        Text(entry.identity.maintenancePolicy.summaryChinese)
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        DisclosureGroup("查看验证记录与组件要求") {
                            VStack(alignment: .leading, spacing: 5) {
                                Text("Hermes \(entry.identity.version) · \(entry.identity.targetCommit.prefix(12))")
                                ForEach(entry.identity.components, id: \.name) { component in
                                    Text("\(HermesRecommendationAssessment.componentLabel(component.name))：\(component.version)")
                                }
                                if let expires = assessment.expiresAt {
                                    Text("清单有效至 \(expires.formatted(date: .abbreviated, time: .shortened))")
                                }
                            }.font(.system(size: 12)).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }.font(.system(size: 12))
                    }
                    if let issue = assessment.issue { GateIssueBanner(issue: issue) }
                }
            }
            Button(isChecking ? "正在检查…" : "检查 Hermes 版本") {
                checkVersions()
            }
            .buttonStyle(.bordered).controlSize(.small)
            .disabled(isChecking || isBusy)
            if report != nil {
                Button("检查升级条件") { checkConditions() }
                    .buttonStyle(.bordered).controlSize(.small)
                    .disabled(isChecking || isBusy)
            }
            if let readiness {
                versionRow("本地修改", readiness.workingTreeSummaryChinese)
                versionRow("升级恢复", readiness.recoveryRequired ? "有未完成的操作，自动启动已暂停" : "无待恢复操作")
                versionRow("其他更新", readiness.officialUpdateInProgress ? "正在进行，请稍后再试" : "未检测到进行中的更新")
                Text("条件检查不会停止服务或修改数据；执行升级还需完成任务空闲、备份和服务恢复验证。")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
            Divider().padding(.vertical, 4)
            Text(record.titleChinese).font(.system(size: 13, weight: .semibold))
            if case .recorded(let target, let phase, let updatedAt) = record {
                versionRow("记录目标", "\(target.version) · \(target.commit.prefix(12))")
                versionRow("最后记录阶段", phase.recordedLabelChinese)
                versionRow("记录时间", updatedAt.formatted(date: .abbreviated, time: .shortened))
            }
            Text(record.explanationChinese).font(.system(size: 12)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(isRefreshingRecord ? "正在读取…" : "刷新升级记录") { refreshRecord() }
                .buttonStyle(.bordered).controlSize(.small)
                .disabled(isRefreshingRecord || isChecking || isBusy)
            if let recordIssue = record.issue {
                GateIssueBanner(issue: recordIssue)
            }
            if let issue, issue.code != record.issue?.code { GateIssueBanner(issue: issue) }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func versionRow(_ label: String, _ value: String) -> some View {
        HStack(spacing: 20) {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value).lineLimit(1).truncationMode(.middle)
        }
        .font(.system(size: 13))
        .frame(height: 39)
    }
}

import HermesGoDesktopCore
import SwiftUI

struct HermesVersionCard: View {
    @ObservedObject var manager: DesktopHermesVersionManager
    var currentInstallation: DesktopLocalHermesDetection?
    let isBusy: Bool
    let upgrade: () -> Void
    let recheck: () -> Void
    let showDiagnostics: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("版本与升级").font(.system(size: 16, weight: .bold))
            versionRow("本机版本", (manager.installation ?? currentInstallation)?.installation?.version ?? "尚未识别")
            if let report = manager.report {
                versionRow("最新稳定版", report.latestStable.version)
                Text(manager.comparison).font(.system(size: 13)).foregroundStyle(.secondary)
                if !report.latestStable.releaseHighlights.isEmpty {
                    Text(report.latestStable.highlightsChinese == nil ? "主要更新（官方原文）" : "主要更新")
                        .font(.system(size: 13, weight: .semibold))
                    ForEach(Array(report.latestStable.releaseHighlights.enumerated()), id: \.offset) { _, line in
                        Text("• " + line).font(.system(size: 13)).fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    Text("暂未提供更新摘要，可查看官方发布说明。").font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Link("查看完整官方更新说明", destination: report.latestStable.releaseURL).font(.system(size: 12))
                Text("检查于 \(report.checkedAt.formatted(date: .abbreviated, time: .shortened))")
                    .font(.system(size: 11)).foregroundStyle(.secondary)
            }
            HStack {
                Button(manager.isChecking ? "正在检查…" : "检查更新") { Task { await manager.check() } }
                    .buttonStyle(.bordered).disabled(isBusy || manager.isChecking || manager.isRunning)
                    .accessibilityIdentifier("hermes-check-updates")
                if let target = manager.target, manager.canUpgrade {
                    Button(manager.phase == .failed ? "重试升级到 \(target.version)" : "升级到 \(target.version)", action: upgrade)
                        .buttonStyle(.borderedProminent).disabled(isBusy)
                        .accessibilityIdentifier("hermes-upgrade-start")
                }
            }
            if manager.isRunning {
                upgradeProgress
            }
            if manager.phase == .completed || manager.phase == .failed {
                Text(manager.resultSummary).font(.system(size: 14, weight: .semibold))
            }
            if let issue = manager.issue { GateIssueBanner(issue: issue) }
            if !manager.checks.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(manager.checks) { check in
                        HStack(alignment: .top) {
                            Text(check.title)
                            Spacer()
                            Text(check.result).foregroundStyle(check.needsAttention ? Color.orange : Color.secondary)
                        }
                        if check.health.level != .healthy {
                            Text(check.health.detail).font(.system(size: 11)).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }.font(.system(size: 12))
            }
            if manager.phase == .completed {
                HStack {
                    Button("重新检测", action: recheck).disabled(isBusy)
                    Button("查看诊断", action: showDiagnostics)
                }.buttonStyle(.bordered)
            }
            if !manager.output.isEmpty {
                DisclosureGroup("查看升级执行输出") {
                    ScrollView {
                        Text(manager.output.joined(separator: "\n"))
                            .font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }.frame(height: 150)
                }.font(.system(size: 12))
            }
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
        .task { if manager.report == nil && !manager.isChecking && !isBusy { await manager.check() } }
    }
    private var upgradeProgress: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let total = DesktopHermesUpgradeStep.allCases.count
            let start = manager.startedAt ?? context.date
            let stageStart = manager.stepStartedAt ?? start
            let lastActivity = max(manager.lastOutputAt ?? stageStart, stageStart)
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("第 \(manager.step.index + 1) / \(total) 步 · \(manager.progress)")
                        .font(.system(size: 13, weight: .semibold))
                        .fixedSize(horizontal: false, vertical: true)
                }
                ProgressView(value: Double(manager.completedStepCount), total: Double(total))
                    .progressViewStyle(.linear)
                    .accessibilityLabel("升级步骤进度")
                    .accessibilityValue("已完成 \(manager.completedStepCount) / \(total) 步")
                Text("已完成 \(manager.completedStepCount) / \(total) 步；各步骤耗时不同。")
                Text("总耗时 \(DesktopHermesUpgradeStep.elapsed(since: start, at: context.date)) · 本步骤 \(DesktopHermesUpgradeStep.elapsed(since: stageStart, at: context.date))")
                    .monospacedDigit()
                if manager.phase == .installing {
                    if let lastOutput = manager.lastOutputAt, lastOutput >= stageStart {
                        Text("最近收到输出：\(DesktopHermesUpgradeStep.elapsed(since: lastOutput, at: context.date))前")
                            .monospacedDigit()
                    }
                    if context.date.timeIntervalSince(lastActivity) >= 45 {
                        Text("正在等待本步骤的新输出，可展开下方执行输出查看详情。")
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Text("升级期间请保持 Desktop 运行。")
            }
            .font(.system(size: 12)).foregroundStyle(.secondary)
            .accessibilityIdentifier("hermes-upgrade-progress")
        }
    }
    private func versionRow(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(.secondary); Spacer(); Text(value) }.font(.system(size: 13))
    }
}

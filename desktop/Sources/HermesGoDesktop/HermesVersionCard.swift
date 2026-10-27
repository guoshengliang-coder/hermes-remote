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
                HStack { ProgressView().controlSize(.small); Text(manager.progress).font(.system(size: 13)) }
                Text("升级期间请保持 Desktop 运行。").font(.system(size: 12)).foregroundStyle(.secondary)
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
    private func versionRow(_ label: String, _ value: String) -> some View {
        HStack { Text(label).foregroundStyle(.secondary); Spacer(); Text(value) }.font(.system(size: 13))
    }
}

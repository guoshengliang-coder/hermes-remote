import HermesGoDesktopCore
import SwiftUI

struct DiagnosticsView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Binding var selection: DesktopSection

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    title: "诊断与修复",
                    subtitle: diagnosticSummary
                ) {
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        Label(model.isRefreshing ? "正在诊断" : "重新诊断", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.isRefreshing)
                }

                HStack(alignment: .top, spacing: 18) {
                    checksCard
                    VStack(spacing: 18) {
                        issueCard
                        logPreview
                    }
                }

                Label(
                    "Hermes 内部状态无法直接观察；以上结论来自本机网络与现有接口。",
                    systemImage: "info.circle"
                )
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            }
            .padding(34)
        }
    }

    private var diagnosticSummary: String {
        let problems = model.health.components.filter {
            $0.level == .failed || $0.level == .degraded || ($0.component.isRequired && $0.level == .unavailable)
        }.count
        return problems == 0
            ? "关键连接检查均已通过"
            : "已完成 \(model.health.components.count) 项检查，发现 \(problems) 个需要确认的问题"
    }

    private var checksCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("连接检查")
                .font(.system(size: 17, weight: .bold))
                .padding(.bottom, 14)
            ForEach(model.health.components) { item in
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: statusSymbol(item.level))
                        .foregroundStyle(statusColor(item.level))
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(item.component.title)
                            .font(.system(size: 13, weight: .semibold))
                        Text(item.detail)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.vertical, 10)
                if item.id != model.health.components.last?.id { Divider() }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var issueCard: some View {
        let issue = model.health.components.first {
            $0.level == .failed || $0.level == .degraded || ($0.component.isRequired && $0.level == .unavailable)
        }
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 7) {
                StatusDot(level: issue?.level ?? .healthy)
                Text(issue == nil ? "无需处理" : "需要确认")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(issue == nil ? Color.primary : Color.orange)
            }
            Text(issue?.component.title ?? "连接链路正常")
                .font(.system(size: 19, weight: .bold))
            Text(issue?.detail ?? "当前没有发现需要处理的问题。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            if issue != nil {
                HStack {
                    Button("再次尝试") { Task { await model.refresh() } }
                        .buttonStyle(PrimaryButtonStyle())
                    Button("账号与设备") { selection = .pairing }
                        .buttonStyle(.bordered)
                    if let structuredIssue = issue?.issue {
                        Button("复制诊断") { model.copyDiagnostics(structuredIssue) }
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var logPreview: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("最近日志")
                    .font(.system(size: 15, weight: .bold))
                Spacer()
                Label(logBadge, systemImage: model.legacy?.recentLogSummary.warningCount == 0 ? "lock" : "exclamationmark.triangle")
                    .font(.system(size: 11))
                    .foregroundStyle(model.legacy?.recentLogSummary.warningCount == 0 ? Color.secondary : Color.orange)
            }
            Text(model.legacy?.recentLogs.suffix(5).joined(separator: "\n") ?? "暂无可显示的日志")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, minHeight: 95, alignment: .topLeading)
                .padding(12)
                .background(.primary.opacity(0.035))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            Button("打开日志目录") { model.openLegacyLogDirectory() }
                .buttonStyle(.link)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var logBadge: String {
        let count = model.legacy?.recentLogSummary.warningCount ?? 0
        return count == 0 ? "已脱敏" : "已脱敏 · 近期疑似异常 \(count) 条"
    }

    private func statusSymbol(_ level: HealthLevel) -> String {
        switch level {
        case .healthy: "checkmark.circle.fill"
        case .degraded: "exclamationmark.triangle.fill"
        case .failed: "xmark.circle.fill"
        case .checking: "ellipsis.circle"
        case .unavailable: "minus.circle"
        }
    }

    private func statusColor(_ level: HealthLevel) -> Color {
        switch level {
        case .healthy: .green
        case .degraded: .orange
        case .failed: .red
        case .checking: .hermesBlue
        case .unavailable: .secondary
        }
    }
}

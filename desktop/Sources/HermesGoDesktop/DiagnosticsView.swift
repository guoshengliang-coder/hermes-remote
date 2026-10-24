import HermesGoDesktopCore
import SwiftUI

struct DiagnosticsView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Binding var selection: DesktopSection
    @State private var showsAllLogs = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    title: "诊断与修复",
                    subtitle: diagnosticSummary
                ) {
                    Button {
                        Task { await model.refreshDiagnostics() }
                    } label: {
                        Label(model.isDiagnosing ? "正在诊断" : "重新诊断", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.isDiagnosing || model.isRefreshing || model.isManagedBootstrapAccountLocked)
                }

                if let issue = model.diagnosticFocusIssue {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(issue.summaryChinese).font(.system(size: 17, weight: .bold))
                        Text(issue.sanitizedDiagnostic).font(.system(size: 12, design: .monospaced))
                            .textSelection(.enabled)
                        Button("复制此问题") { model.copyDiagnostics(issue) }
                    }.padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
                }
                componentDetails

                HStack(alignment: .top, spacing: 18) {
                    checksCard
                    VStack(spacing: 18) {
                        issueCard
                        if let local = model.localManagedHermesHealth { localCheckCard(local) }

                    }
                }

                logPreview

                Label(
                    "诊断来自本机检查与账号设备上报；检查时间可能不同。",
                    systemImage: "info.circle"
                )
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            }
            .padding(34)
        }
    }

    private var componentDetails: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("组件与版本").font(.system(size: 17, weight: .bold))
                Spacer()
                Button("复制诊断报告") { model.copyDiagnosticReport() }.buttonStyle(.bordered)
            }
            LabeledContent("Desktop", value: model.appVersion)
            LabeledContent("后台运行包（Hermes Server / Connector）", value: model.installedManagedVersion ?? "未确认受管安装")
            LabeledContent("后台 Agent", value: model.agentPresentation.health.detail)
            LabeledContent("本机 Hermes", value: model.localHermesPresentation.version)
            if case .unsupported(let reason, let detail) = model.localHermesDetection {
                Text(SecretRedactor.redact("\(reason.rawValue): \(detail)"))
                    .font(.system(size: 12)).textSelection(.enabled)
            }
            if let presentation = model.componentPreflightPresentation {
                ForEach(presentation.rows) { row in
                    LabeledContent(row.titleChinese, value: row.detailChinese)
                }
            }
            ForEach(Array([model.localHermesIssue, model.managedBootstrapIssue,
                           model.componentBootstrapIssue, model.appUpdateIssue].compactMap { $0 }.enumerated()), id: \.offset) { _, issue in
                GateIssueBanner(issue: issue)
            }
        }
        .font(.system(size: 13)).padding(20).hermesCard()
    }

    private var diagnosticSummary: String {
        if model.presentedHealth.overall == .checking { return "正在检查，结果尚未齐全" }
        let problems = model.presentedHealth.components.filter {
            $0.level == .failed || $0.level == .degraded || ($0.component.isRequired && $0.level == .unavailable)
        }.count
        return problems == 0
            ? "关键连接检查均已通过"
            : "已完成 \(model.presentedHealth.components.count) 项检查，发现 \(problems) 个需要确认的问题"
    }

    private var checksCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(model.selectedAccountDevice == nil ? "连接检查" : "账号设备上报")
                .font(.system(size: 17, weight: .bold))
                .padding(.bottom, 14)
            ForEach(model.presentedHealth.components) { item in
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: statusSymbol(item.level))
                        .foregroundStyle(statusColor(item.level))
                        .font(.system(size: 17, weight: .semibold))
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(componentTitle(item.component))
                            .font(.system(size: 13, weight: .semibold))
                        Text(item.detail)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                        Text(checkTime(item.checkedAt))
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.vertical, 10)
                if item.id != model.presentedHealth.components.last?.id { Divider() }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var issueCard: some View {
        let issue = model.presentedHealth.components.first {
            $0.level == .failed || $0.level == .degraded || ($0.component.isRequired && $0.level == .unavailable)
        }
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 7) {
                StatusDot(level: issue?.level ?? .healthy)
                Text(issue == nil ? "无需处理" : "需要确认")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(issue == nil ? Color.primary : Color.orange)
            }
            Text(issue.map { componentTitle($0.component) } ?? "连接链路正常")
                .font(.system(size: 19, weight: .bold))
            Text(issue?.detail ?? "当前没有发现需要处理的问题。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            if issue != nil {
                HStack {
                    Button("再次尝试") { Task { await model.refreshDiagnostics() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(model.isDiagnosing || model.isRefreshing || model.isManagedBootstrapAccountLocked)
                    Button("设备") { selection = .pairing }
                        .buttonStyle(.bordered)
                    if let structuredIssue = issue?.issue {
                        Button("复制诊断") { model.copyDiagnostics(structuredIssue) }
                            .buttonStyle(.bordered)
                    }
                }
            }
            if model.serviceRepairNeeded {
                Button("检查并修复 Desktop") { Task { await model.prepareServiceRepair() } }
                    .buttonStyle(.bordered).disabled(model.isManagedBootstrapAccountLocked)
            }
            if model.canReconnectManagedConnector {
                Button("重新连接这台 Mac") { Task { await model.prepareConnectorReconnect() } }
                    .buttonStyle(.bordered)
            }
            if let accountIssue = model.accountIssue { GateIssueBanner(issue: accountIssue) }
            if let repairIssue = model.serviceRepairIssue { GateIssueBanner(issue: repairIssue) }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func localCheckCard(_ local: ComponentHealth) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("这台 Mac 的 Hermes", systemImage: statusSymbol(local.level))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(statusColor(local.level))
            Text(local.detail).font(.system(size: 12)).foregroundStyle(.secondary)
            Text(checkTime(local.checkedAt)).font(.system(size: 10)).foregroundStyle(.secondary)
            Text("本机认证接口检查；账号设备的连接状态以上方上报为准。")
                .font(.system(size: 11)).foregroundStyle(.secondary)
            if let issue = local.issue {
                Button("复制诊断") { model.copyDiagnostics(issue) }.buttonStyle(.bordered)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func checkTime(_ date: Date?) -> String {
        guard let date, date != .distantPast else { return "检查时间未知" }
        return "检查于 \(date.formatted(date: .abbreviated, time: .standard))"
    }

    private var logPreview: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("最近日志")
                    .font(.system(size: 15, weight: .bold))
                Spacer()
                Label(logBadge, systemImage: model.recentLogWarningCount == 0 ? "lock" : "exclamationmark.triangle")
                    .font(.system(size: 11))
                    .foregroundStyle(model.recentLogWarningCount == 0 ? Color.secondary : Color.orange)
            }
            Text(model.recentLogLines.isEmpty ? "暂无可显示的日志" : (showsAllLogs ? model.recentLogLines : Array(model.recentLogLines.suffix(5))).joined(separator: "\n"))
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, minHeight: 95, alignment: .topLeading)
                .padding(12)
                .background(.primary.opacity(0.035))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
            HStack {
                Button(showsAllLogs ? "收起日志" : "展开运行日志") { showsAllLogs.toggle() }
                Button("复制诊断报告") { model.copyDiagnosticReport() }
                Button("打开日志目录") { model.openLogDirectory() }
            }.buttonStyle(.link)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func componentTitle(_ component: HealthComponent) -> String {
        guard model.selectedAccountDevice != nil else { return component.title }
        return switch component {
        case .hermes: "账号设备的 Hermes"
        case .endToEnd: "账号端到端"
        default: component.title
        }
    }

    private var logBadge: String {
        let count = model.recentLogWarningCount
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

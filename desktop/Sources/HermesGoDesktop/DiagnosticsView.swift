import HermesGoDesktopCore
import SwiftUI

struct DiagnosticsView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection
    @State private var showsTechnicalDetails = false
    private var results: DesktopDiagnosticsPresentation { model.diagnosticPresentation }
    private var busy: Bool { model.isDiagnosing || model.isRefreshing || model.isManagedBootstrapAccountLocked }

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(title: "诊断", subtitle: model.isDiagnosing ? "正在更新检查结果…" : results.summary) {
                    HStack {
                        Button("查看日志") { withAnimation { proxy.scrollTo("logs", anchor: .top) } }.buttonStyle(.bordered)
                        Button("复制诊断报告") { model.copyDiagnosticReport() }.buttonStyle(.bordered)
                        Button { Task { await model.refreshDiagnostics() } } label: {
                            Label(model.isDiagnosing ? "正在诊断" : "重新诊断", systemImage: "arrow.clockwise")
                        }.buttonStyle(PrimaryButtonStyle()).disabled(busy)
                    }
                }
                HStack {
                    Button("重新连接这台 Mac") { Task { await model.prepareConnectorReconnect() } }
                        .buttonStyle(.bordered)
                        .disabled(busy || !model.canReconnectManagedConnector)
                    if let message = model.connectorRecoveryMessage {
                        Text(message).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
                checks
                DiagnosticLogsView().id("logs")
                technicalDetails
                if let issue = model.diagnosticFocusIssue {
                    DisclosureGroup("从其他页面带入的问题 · \(issue.code.rawValue)") {
                        Text("这是进入诊断时的问题记录，当前状态以上方检查结果为准。")
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                        Text(issue.displayChinese).font(.system(size: 13))
                        Text(issue.sanitizedDiagnostic).font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                        HStack {
                            Button("复制此问题") { model.copyDiagnostics(issue) }
                            Button("清除此记录") { model.clearDiagnosticFocus() }
                        }.buttonStyle(.bordered)
                    }.padding(18).hermesCard()
                }
            }.padding(34)
        }
        }
    }

    private var checks: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("检查结果").font(.system(size: 17, weight: .bold)).padding(.bottom, 6)
            Text("逐项核对本机状态与远程连接。每项保留自己的检查时间，旧结果不会视为当前正常。")
                .font(.system(size: 12)).foregroundStyle(.secondary).padding(.bottom, 10)
            ForEach(results.checks) { check in
                VStack(alignment: .leading, spacing: 7) {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: symbol(check.health.level))
                            .foregroundStyle(Color.status(check.health.level, scheme: colorScheme)).frame(width: 20)
                        Text(check.title).font(.system(size: 14, weight: .semibold))
                        Spacer(minLength: 12)
                        Text(check.result).font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color.status(check.health.level, scheme: colorScheme))
                    }
                    Text(check.health.detail).font(.system(size: 12)).textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(alignment: .top) {
                        Text(check.source)
                        Spacer()
                        Text(checkTime(check.health.checkedAt))
                    }.font(.system(size: 11)).foregroundStyle(.secondary)
                    rowActions(check)
                    if check.id == .background && model.serviceRepairNeeded { repairControls }
                }.padding(.vertical, 13)
                if check.id != results.checks.last?.id { Divider() }
            }
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
    }

    @ViewBuilder private func rowActions(_ check: DesktopDiagnosticCheck) -> some View {
        if check.needsAttention {
            HStack {
                if check.id == .localHermes {
                    Button("查看本机 Hermes") { selection = .localHermes }
                }
                if let issue = check.health.issue {
                    if !check.health.detail.contains(issue.code.rawValue) {
                        Text(issue.code.rawValue).font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                    Button("复制此项问题") { model.copyDiagnostics(issue) }
                }
            }.buttonStyle(.bordered)
        }
    }

    private var repairControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let issue = model.serviceRepairIssue, issue.code != .managedServicesNeedRepair {
                Text(issue.displayChinese).font(.system(size: 12)).foregroundStyle(.secondary)
            }
            if let guidance = model.serviceRepairGuidance {
                Text(guidance).font(.system(size: 12)).fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                if model.isServiceRepairPreparing || model.isServiceRepairRunning {
                    ProgressView().controlSize(.small)
                    Text(model.isServiceRepairRunning ? "正在修复并验证连接…" : "正在检查修复条件…")
                        .font(.system(size: 12))
                }
                Button(model.serviceRepairGuidance == nil ? "检查并修复 Desktop" : "重新检查修复条件") {
                    Task { await model.prepareServiceRepair() }
                }.buttonStyle(.borderedProminent)
                    .disabled(model.isManagedBootstrapAccountLocked || model.isAccountOperationInProgress)
            }
        }.padding(.top, 4)
    }

    private var technicalDetails: some View {
        DisclosureGroup(isExpanded: $showsTechnicalDetails) {
            VStack(alignment: .leading, spacing: 12) {
                LabeledContent("Desktop 应用", value: model.appVersion)
                LabeledContent("已安装后台运行包", value: model.installedManagedVersion ?? "尚未确认")
                LabeledContent("后台 Agent", value: model.agentPresentation.health.detail)
                LabeledContent("本机 Hermes", value: model.localHermesPresentation.version)
                if case .unsupported(let reason, let detail) = model.localHermesDetection {
                    Text(SecretRedactor.redact("\(reason.rawValue): \(detail)")).textSelection(.enabled)
                }
                if let preflight = model.componentPreflightPresentation {
                    Divider()
                    Text("安装预检参考").fontWeight(.semibold)
                    Text("以下是上次安装预检的复用／下载计划，不代表当前安装版本或运行故障。")
                        .foregroundStyle(.secondary)
                    ForEach(preflight.rows) { row in
                        LabeledContent(row.titleChinese, value: row.detailChinese)
                    }
                }
                ForEach(Array(operationIssues.enumerated()), id: \.offset) { _, issue in
                    Divider()
                    Text(issue.displayChinese).textSelection(.enabled)
                    Button("复制操作记录") { model.copyDiagnostics(issue) }.buttonStyle(.bordered)
                }
            }.font(.system(size: 12)).padding(.top, 12)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text("技术详情").font(.system(size: 15, weight: .semibold))
                Text("查看已安装版本、安装预检和操作记录")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
    }
    private var operationIssues: [DesktopIssue] {
        [model.localHermesIssue, model.managedBootstrapIssue, model.componentBootstrapIssue,
         model.serviceRepairNeeded ? nil : model.serviceRepairIssue, model.appUpdateIssue].compactMap { $0 }
    }
    private func checkTime(_ date: Date?) -> String {
        guard let date, date != .distantPast else { return "尚未检查" }
        return date.formatted(date: .abbreviated, time: .standard)
    }
    private func symbol(_ level: HealthLevel) -> String {
        switch level {
        case .healthy: "checkmark.circle.fill"
        case .failed: "xmark.circle.fill"
        case .degraded: "exclamationmark.triangle.fill"
        case .checking: "clock"
        case .unavailable: "minus.circle"
        }
    }
}

struct DiagnosticLogsView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var source = "all"
    @State private var query = ""
    @State private var warningsOnly = false
    @State private var refreshing = false
    @State private var copied = false
    private var lines: [String] {
        RecentLogAnalyzer.filter(model.recentLogLines, source: source == "all" ? nil : source,
                                 query: query, warningsOnly: warningsOnly)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("运行日志").font(.system(size: 17, weight: .bold))
                Spacer()
                Button(refreshing ? "读取中…" : "刷新日志") {
                    refreshing = true
                    Task { await model.refreshLogs(); refreshing = false }
                }.disabled(refreshing || model.isRefreshing || model.isDiagnosing)
                Button(copied ? "已复制" : "复制筛选结果") { model.copyLogLines(lines); copied = true }
                    .disabled(lines.isEmpty)
                Button("打开日志目录") { model.openLogDirectory() }
            }.buttonStyle(.bordered)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { sourcePicker; search; warningToggle }
                VStack(alignment: .leading, spacing: 10) {
                    HStack { sourcePicker; warningToggle }
                    search
                }
            }
            GeometryReader { viewport in
                ScrollView([.horizontal, .vertical]) {
                    Text(lines.isEmpty ? (model.recentLogLines.isEmpty ? "暂无日志。点击“刷新日志”读取本机运行记录。" : "没有符合当前筛选条件的记录。") : lines.joined(separator: "\n"))
                        .font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                        .fixedSize(horizontal: true, vertical: true).padding(12)
                        .frame(minWidth: viewport.size.width, minHeight: viewport.size.height, alignment: .topLeading)
                }
            }.frame(height: 210).background(.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 8))
            Text("已脱敏 · 显示 \(lines.count) / \(model.recentLogLines.count) 条 · 每个来源最多读取最近 20 条。记录可能来自较早时间，不代表当前故障。")
                .font(.system(size: 11)).foregroundStyle(.secondary)
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
            .onChange(of: source) { copied = false }
            .onChange(of: query) { copied = false }
            .onChange(of: warningsOnly) { copied = false }
            .onChange(of: model.recentLogLines) { copied = false }
    }
    private var sourcePicker: some View {
        Picker("来源", selection: $source) {
            Text("全部来源").tag("all")
            Text("Desktop 操作").tag("desktop-runtime.log")
            Text("Connector 运行").tag("connector.log")
            Text("Connector 错误输出").tag("connector.error.log")
        }.frame(width: 220)
    }
    private var search: some View {
        TextField("搜索日志内容", text: $query).textFieldStyle(.roundedBorder)
            .frame(minWidth: 150).accessibilityLabel("搜索日志内容")
    }
    private var warningToggle: some View { Toggle("仅警告／错误关键词", isOn: $warningsOnly).toggleStyle(.checkbox).fixedSize() }
}

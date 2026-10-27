import HermesGoDesktopCore
import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection
    @State private var isPhoneSheetPresented = false
    @State private var showsConnectionSetup = false
    @State private var expandedDetails: Set<String> = []
    private var state: DesktopOverviewPresentation { model.overviewPresentation }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Spacer()
                    Button { Task { await model.refreshDiagnostics() } } label: {
                        Label(model.isRefreshing || model.isDiagnosing ? "检查中" : "刷新状态", systemImage: "arrow.clockwise")
                    }.buttonStyle(.bordered).disabled(refreshDisabled)
                }
                headline.padding(.bottom, 6)
                cloudSection
                desktopSection
                if showsConnectionSetup {
                    DesktopConnectionSetupView { showsConnectionSetup = false }
                }
                hermesSection
            }.padding(28)
        }
        .refreshable { await model.refreshDiagnostics() }
        .sheet(isPresented: $isPhoneSheetPresented) {
            VStack(alignment: .leading, spacing: 16) {
                Text("连上手机").font(.system(size: 22, weight: .bold))
                PhoneConnectPanel(showsHeader: false)
                HStack { Spacer(); Button("完成") { isPhoneSheetPresented = false } }
            }.padding(26).frame(width: 860)
        }
    }

    private var headline: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: OverviewStatusLabel.symbol(state.level))
                .font(.system(size: 24, weight: .medium))
                .foregroundStyle(Color.status(state.level, scheme: colorScheme))
                .frame(width: 46, height: 46)
                .background(Color.status(state.level, scheme: colorScheme).opacity(0.09), in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(state.title).font(.system(size: 24, weight: .bold))
                Text(state.level == .healthy ? "手机可以访问这台 Mac 的 Hermes" : state.detail)
                    .font(.system(size: 13)).foregroundStyle(.secondary)
            }.fixedSize(horizontal: false, vertical: true)
        }
    }

    private var cloudSection: some View {
        section("Hermes GO 云端", subtitle: model.accountGatewayURL.host ?? "未配置服务地址",
                symbol: "cloud", status: state.cloud) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 32) { latency; accountService }
                VStack(alignment: .leading, spacing: 16) { latency; accountService }
            }
            if state.cloud.level == .failed {
                issueLink(DesktopIssue(code: .connectionFailed))
            } else if let issue = model.accountIssue, !state.needsLogin {
                issueLink(issue)
            }
            footer("cloud", title: "检查详情") {
                Button("重新检查") { Task { await model.refreshDiagnostics() } }.disabled(refreshDisabled)
                if state.needsLogin { Button("登录账号") { model.requestAccountSignIn() } }
            } details: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.cloud.detail)
                    Text(checkTime("云端检查", state.cloud.checkedAt))
                    Text(checkTime("账号检查", model.accountCheckedAt))
                }.detailStyle()
            }
        }
    }

    private var latency: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(state.cloudLatency).font(.system(size: 28, weight: .medium, design: .rounded))
                .monospacedDigit().textSelection(.enabled)
            Text("本机到云端延时").font(.system(size: 11)).foregroundStyle(.secondary)
        }
    }
    private var accountService: some View {
        indicator("账号服务", value: state.accountService, level: state.accountServiceLevel)
    }

    private var desktopSection: some View {
        section("\(model.localDeviceName) 的 Desktop", subtitle: "版本 \(model.appVersion) · 本机",
                symbol: "laptopcomputer", status: state.desktop) {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 155), alignment: .leading)], alignment: .leading, spacing: 16) {
                indicator("后台运行", value: state.background, level: state.backgroundLevel)
                indicator("云端连接", value: state.remoteConnection, level: state.remoteConnectionLevel)
                indicator("手机访问", value: state.remoteAccess, level: state.remoteAccessLevel)
            }
            if let issue = model.serviceRepairIssue ?? model.managedBootstrapIssue ?? model.agentPresentation.health.issue {
                issueLink(issue)
            } else if state.desktop.level == .failed {
                issueLink(DesktopIssue(code: model.agentPresentation.mode == .attentionRequired
                    ? .migrationConnectorMismatch
                    : state.remoteAccess == "未通过" && state.remoteConnection == "已连接云端"
                        ? .relayFailure : .connectorOffline))
            }
            if let guidance = model.serviceRepairGuidance {
                Text(guidance).font(.system(size: 13)).fixedSize(horizontal: false, vertical: true)
            }
            footer("desktop", title: "连接详情") {
                if state.desktop.level != .healthy { desktopAction }
                if model.entryRoute == .main {
                    Button("检查更新") { Task { await model.checkForUpdates(manual: true) } }
                        .disabled(model.isManagedBootstrapAccountLocked || model.isDesktopUpdateRunning)
                    Button("查看诊断") { selection = .diagnostics }
                }
            } details: {
                VStack(alignment: .leading, spacing: 8) {
                    Text(state.desktop.detail)
                    Text(checkTime("后台检查", state.desktop.checkedAt))
                    Text(checkTime("云端连接检查", state.connectionCheckedAt))
                    Text(checkTime("手机访问验证", state.remoteAccessCheckedAt))
                    if state.desktop.level == .healthy { desktopAction.buttonStyle(.bordered) }
                }.detailStyle()
            }
            if showsNoPhoneHint {
                ViewThatFits(in: .horizontal) {
                    HStack {
                        phoneHint
                        Spacer()
                        phoneButton
                    }
                    VStack(alignment: .leading, spacing: 8) { phoneHint; phoneButton }
                }
            }
        }
    }

    private var hermesSection: some View {
        section("本机 Hermes", subtitle: nil, symbol: "terminal", status: state.hermes) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 32) { hermesVersion; hermesVerification; HermesModelFact() }
                VStack(alignment: .leading, spacing: 16) { hermesVersion; hermesVerification; HermesModelFact() }
            }
            if let detail = model.localHermesPresentation.managementDetail {
                Label(detail, systemImage: "exclamationmark.circle").font(.system(size: 13)).foregroundStyle(.secondary)
                if let issue = model.localHermesIssue {
                    DisclosureGroup("查看检测原因") { issueLink(issue) }
                }
            } else if let issue = model.localHermesIssue ?? model.localManagedHermesHealth?.issue {
                issueLink(issue)
            }
            if model.localHermesDetection?.installation != nil {
                ModelConfigurationPrompt()
            }
            footer("hermes", title: "检查详情") {
                if !model.isManagedBootstrapAccountLocked {
                    if model.localHermesPresentation.canOpen {
                        Button("打开 Hermes") { model.openHermes() }.buttonStyle(.bordered)
                    }
                    Button("查看本机 Hermes") { selection = .localHermes }
                    if !model.localHermesPresentation.canOpen {
                        Button("诊断与修复") { selection = .diagnostics }
                    }
                }
            } details: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(state.hermes.detail)
                    if model.localHermesDetection?.installation != nil {
                        Text(model.currentModelConfiguration.title)
                        Text(model.currentModelConfiguration.detail)
                    }
                    Text(checkTime("本机验证", state.hermes.checkedAt))
                }.detailStyle()
            }
        }
    }
    private var hermesVersion: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(model.localHermesPresentation.version).font(.system(size: 22, weight: .medium))
                .textSelection(.enabled)
            Text("Hermes 版本").font(.system(size: 11)).foregroundStyle(.secondary)
            if let label = model.localHermesPresentation.managementLabel { Text(label).font(.system(size: 11)).foregroundStyle(.secondary) }
        }
    }
    private var hermesVerification: some View {
        indicator("本机验证", value: model.localHermesPresentation.canOpen ? "已通过" : state.hermes.title,
                  level: model.localHermesPresentation.canOpen ? .healthy : state.hermes.level)
    }

    private func section<Content: View>(_ title: String, subtitle: String?, symbol: String,
                                        status: DesktopOverviewStatus, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 16) {
                    sectionHeading(title, subtitle: subtitle, symbol: symbol)
                    Spacer(minLength: 8)
                    OverviewStatusLabel(status: status)
                }
                VStack(alignment: .leading, spacing: 10) {
                    sectionHeading(title, subtitle: subtitle, symbol: symbol)
                    OverviewStatusLabel(status: status)
                }
            }
            if status.level != .healthy {
                Text(status.detail).font(.system(size: 13)).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            content()
        }.padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
    }

    private func sectionHeading(_ title: String, subtitle: String?, symbol: String) -> some View {
        HStack(spacing: 12) {
            Group {
                if symbol == "terminal" { HermesBrandIcon().frame(width: 23, height: 23) }
                else { Image(systemName: symbol).font(.system(size: 21)) }
            }.frame(width: 40, height: 40)
                .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 16, weight: .semibold))
                if let subtitle { Text(subtitle).font(.system(size: 11)).foregroundStyle(.secondary) }
            }.fixedSize(horizontal: false, vertical: true)
        }
    }

    private func indicator(_ title: String, value: String, level: HealthLevel) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: OverviewStatusLabel.symbol(level))
                .font(.system(size: 16)).foregroundStyle(Color.status(level, scheme: colorScheme))
                .padding(.top, 2).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 13, weight: .medium))
                Text(value).font(.system(size: 12)).foregroundStyle(.secondary)
            }.fixedSize(horizontal: false, vertical: true)
        }.accessibilityElement(children: .combine)
    }

    private func footer<Actions: View, Details: View>(_ id: String, title: String,
        @ViewBuilder actions: () -> Actions, @ViewBuilder details: () -> Details) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 12) {
                    actionRow(content: actions)
                    Spacer(minLength: 12)
                    detailsButton(id, title: title)
                }
                VStack(alignment: .leading, spacing: 10) {
                    actionRow(content: actions)
                    detailsButton(id, title: title)
                }
            }
            if expandedDetails.contains(id) { details() }
        }
    }
    private func detailsButton(_ id: String, title: String) -> some View {
        Button {
            if expandedDetails.contains(id) { expandedDetails.remove(id) }
            else { expandedDetails.insert(id) }
        } label: {
            Label(title, systemImage: expandedDetails.contains(id) ? "chevron.up" : "chevron.down")
                .font(.system(size: 12)).foregroundStyle(.secondary)
        }.buttonStyle(.plain).accessibilityValue(expandedDetails.contains(id) ? "已展开" : "已收起")
    }

    private func actionRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8, content: content)
            VStack(alignment: .leading, spacing: 8, content: content)
        }.buttonStyle(.bordered)
    }
    private func checkTime(_ label: String, _ date: Date?) -> String {
        guard let date, date != .distantPast else { return "\(label)：尚未检查" }
        return "\(label)：\(date.formatted(date: .abbreviated, time: .standard))"
    }
    private func issueLink(_ issue: DesktopIssue) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(issue.displayChinese).font(.system(size: 12)).fixedSize(horizontal: false, vertical: true)
            if model.entryRoute == .main {
                Button("查看原因") { model.showDiagnostics(issue) }.buttonStyle(.bordered)
            } else { CopyDiagnosticsButton(issue: issue) }
        }
    }

    @ViewBuilder private var desktopAction: some View {
        if state.needsLogin {
            Button("登录并继续") { model.requestAccountSignIn() }
        } else if model.serviceRepairNeeded {
            Button(model.serviceRepairGuidance == nil ? "修复 Desktop" : "重新检查修复条件") { Task { await model.prepareServiceRepair() } }
                .disabled(model.isManagedBootstrapAccountLocked)
        } else if model.isManageOnly {
            Button("连接这台 Mac") { Task { await model.chooseNewMacUse(.connect) } }
                .disabled(model.isManagedBootstrapAccountLocked)
        } else if model.canReconnectManagedConnector {
            Button("重新连接") { Task { await model.prepareConnectorReconnect() } }
        } else {
            Button("连接设置") { showsConnectionSetup = true }.disabled(model.entryRoute != .main)
        }
    }
    private var phoneHint: some View {
        Text("手机登录同一账号后，即可访问这台 Mac。")
            .font(.system(size: 12)).foregroundStyle(.secondary)
    }
    private var phoneButton: some View {
        Button("连上手机") { isPhoneSheetPresented = true }.buttonStyle(.bordered)
    }
    private var refreshDisabled: Bool {
        model.isRefreshing || model.isDiagnosing || model.isManagedBootstrapAccountLocked
    }
    private var showsNoPhoneHint: Bool {
        guard let dashboard = model.currentDashboard,
              state.desktop.level == .healthy else { return false }
        return DesktopRemoteClients.active(dashboard.installations).isEmpty
    }
}

private extension View {
    func detailStyle() -> some View {
        font(.system(size: 12)).foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 6)
            .textSelection(.enabled)
    }
}

struct OverviewStatusLabel: View {
    @Environment(\.colorScheme) private var colorScheme
    let status: DesktopOverviewStatus
    var body: some View {
        Label(status.title, systemImage: Self.symbol(status.level))
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(Color.status(status.level, scheme: colorScheme))
            .fixedSize(horizontal: true, vertical: false)
    }
    static func symbol(_ level: HealthLevel) -> String {
        switch level {
        case .healthy: "checkmark.circle.fill"
        case .failed, .degraded: "exclamationmark.circle.fill"
        case .checking: "clock"
        case .unavailable: "minus.circle"
        }
    }
}

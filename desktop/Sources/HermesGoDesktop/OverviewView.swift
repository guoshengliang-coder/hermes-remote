import HermesGoDesktopCore
import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Binding var selection: DesktopSection
    @State private var isPhoneSheetPresented = false
    @State private var showsConnectionSetup = false
    private var state: DesktopOverviewPresentation { model.overviewPresentation }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack {
                        Text("这台 Mac · \(model.localDeviceName)")
                            .font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary)
                        Spacer()
                        Button { Task { await model.refreshDiagnostics() } } label: {
                            Label(model.isRefreshing || model.isDiagnosing ? "检查中" : "刷新状态", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.bordered).disabled(refreshDisabled)
                    }
                    VStack(alignment: .leading, spacing: 14) {
                        ViewThatFits(in: .horizontal) {
                            HStack(alignment: .top, spacing: 22) {
                                headline
                                Spacer(minLength: 8)
                                statusLinks(proxy)
                            }
                            VStack(alignment: .leading, spacing: 14) {
                                headline
                                statusLinks(proxy)
                            }
                        }
                        Text(state.detail).font(.system(size: 13)).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 8)

                    section("Hermes GO 云端", symbol: "cloud", status: state.cloud) {
                        HStack(spacing: 24) {
                            fact("服务地址", model.accountGatewayURL.host ?? "未配置")
                            fact("账号服务", state.accountService)
                        }
                        if state.cloud.level == .failed {
                            issueLink(DesktopIssue(code: .connectionFailed))
                        } else if let issue = model.accountIssue, !state.needsLogin {
                            issueLink(issue)
                        }
                        HStack {
                            Button("重新检查") { Task { await model.refreshDiagnostics() } }
                                .disabled(refreshDisabled)
                            if state.needsLogin { Button("登录账号") { model.requestAccountSignIn() } }
                        }.buttonStyle(.bordered)
                    }.id("cloud")

                    section("这台 Mac 的 Desktop", symbol: "laptopcomputer", status: state.desktop) {
                        HStack(alignment: .top, spacing: 24) {
                            fact("后台运行", state.background)
                            fact("远程连接", state.remoteConnection)
                            fact("应用版本", model.appVersion)
                        }
                        HStack {
                            Text("手机访问验证：\(state.remoteAccess)")
                            Spacer()
                            Text(checkTime("验证时间", state.remoteAccessCheckedAt))
                        }.font(.system(size: 11)).foregroundStyle(.secondary)
                        Text(checkTime("云端连接检查", state.connectionCheckedAt))
                            .font(.system(size: 11)).foregroundStyle(.secondary)
                        if model.bootstrapPlan.readiness == .existingServiceNeedsAttention {
                            Text(model.bootstrapPlan.detailChinese)
                                .font(.system(size: 12)).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
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
                        HStack {
                            desktopAction
                            if model.entryRoute == .main {
                                Button("检查更新") { Task { await model.checkForUpdates(manual: true) } }
                                    .disabled(model.isManagedBootstrapAccountLocked || model.isDesktopUpdateRunning)
                                Button("查看诊断") { selection = .diagnostics }
                            }
                        }.buttonStyle(.bordered)
                        if showsNoPhoneHint {
                            Divider()
                            HStack {
                                Text("手机登录同一账号后，即可访问这台 Mac。")
                                    .font(.system(size: 12)).foregroundStyle(.secondary)
                                Spacer()
                                Button("连上手机") { isPhoneSheetPresented = true }.buttonStyle(.bordered)
                            }
                        }
                    }.id("desktop")
                    if showsConnectionSetup {
                        DesktopConnectionSetupView { showsConnectionSetup = false }
                    }

                    section("本机 Hermes", symbol: "terminal", status: state.hermes) {
                        fact("Hermes 版本", model.localHermesPresentation.version)
                        if let issue = model.localHermesIssue ?? model.localManagedHermesHealth?.issue {
                            issueLink(issue)
                        }
                        if model.entryRoute == .main {
                            HStack {
                                if state.hermes.level == .healthy {
                                    Button("打开 Hermes") { model.openHermes() }
                                }
                                Button("查看本机 Hermes") { selection = .localHermes }
                                if state.hermes.level != .healthy {
                                    Button("诊断与修复") { selection = .diagnostics }
                                }
                            }.buttonStyle(.bordered)
                        }
                    }.id("hermes")
                }
                .padding(28)
            }
            .refreshable { await model.refreshDiagnostics() }
        }
        .sheet(isPresented: $isPhoneSheetPresented) {
            VStack(alignment: .leading, spacing: 16) {
                Text("连上手机").font(.system(size: 22, weight: .bold))
                PhoneConnectPanel(showsHeader: false)
                HStack { Spacer(); Button("完成") { isPhoneSheetPresented = false } }
            }.padding(26).frame(width: 860)
        }
    }

    private var headline: some View {
        Text(state.title).font(.system(size: 24, weight: .bold))
            .fixedSize(horizontal: false, vertical: true)
    }

    private func statusLinks(_ proxy: ScrollViewProxy) -> some View {
        HStack(spacing: 10) {
            statusLink("云端", state.cloud, "cloud", proxy)
            statusLink("Desktop", state.desktop, "desktop", proxy)
            statusLink("Hermes", state.hermes, "hermes", proxy)
        }
    }

    private func statusLink(_ label: String, _ status: DesktopOverviewStatus, _ id: String,
                            _ proxy: ScrollViewProxy) -> some View {
        Button { withAnimation { proxy.scrollTo(id, anchor: .top) } } label: {
            VStack(alignment: .leading, spacing: 5) {
                Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
                OverviewStatusLabel(status: status)
            }.padding(10).background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
        }.buttonStyle(.plain).accessibilityLabel("\(label)，\(status.title)，查看详情")
    }

    private func section<Content: View>(_ title: String, symbol: String, status: DesktopOverviewStatus,
                                        @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(title, systemImage: symbol).font(.system(size: 16, weight: .semibold))
                Spacer()
                OverviewStatusLabel(status: status)
            }
            Text(status.detail).font(.system(size: 13)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            content()
            Text(checkTime("检查时间", status.checkedAt)).font(.system(size: 11)).foregroundStyle(.secondary)
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading).hermesCard()
    }

    private func fact(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 11)).foregroundStyle(.secondary)
            Text(value).font(.system(size: 13)).textSelection(.enabled)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private func checkTime(_ label: String, _ date: Date?) -> String {
        guard let date, date != .distantPast else { return "\(label)：尚未检查" }
        return "\(label)：\(date.formatted(date: .abbreviated, time: .standard))"
    }

    private func issueLink(_ issue: DesktopIssue) -> some View {
        HStack {
            Text(issue.displayChinese).font(.system(size: 12)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
            if model.entryRoute == .main {
                Button("查看原因") { model.showDiagnostics(issue) }.buttonStyle(.bordered)
            } else { CopyDiagnosticsButton(issue: issue) }
        }
    }

    @ViewBuilder private var desktopAction: some View {
        if state.needsLogin {
            Button("登录并继续") { model.requestAccountSignIn() }
        } else if model.serviceRepairNeeded {
            repairButton
        } else if model.isManageOnly {
            Button("连接这台 Mac") { Task { await model.chooseNewMacUse(.connect) } }
                .disabled(model.isManagedBootstrapAccountLocked)
        } else if model.canReconnectManagedConnector {
            Button("重新连接") { Task { await model.prepareConnectorReconnect() } }
        } else {
            Button("连接设置") { showsConnectionSetup = true }.disabled(model.entryRoute != .main)
        }
    }
    private var repairButton: some View {
        Button(model.serviceRepairGuidance == nil ? "修复 Desktop" : "重新检查修复条件") { Task { await model.prepareServiceRepair() } }
            .disabled(model.isManagedBootstrapAccountLocked)
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

struct OverviewStatusLabel: View {
    @Environment(\.colorScheme) private var colorScheme
    let status: DesktopOverviewStatus
    var body: some View {
        Label(status.title, systemImage: symbol)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(Color.status(status.level, scheme: colorScheme))
            .fixedSize(horizontal: true, vertical: false)
    }
    private var symbol: String {
        switch status.level {
        case .healthy: "checkmark.circle.fill"
        case .failed, .degraded: "exclamationmark.circle.fill"
        case .checking: "clock"
        case .unavailable: "minus.circle"
        }
    }
}

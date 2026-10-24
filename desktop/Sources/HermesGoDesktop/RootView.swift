import HermesGoDesktopCore
import SwiftUI

enum DesktopSection: String, CaseIterable, Identifiable {
    case overview
    case localHermes
    case pairing
    case diagnostics
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: "概览"
        case .diagnostics: "诊断"
        case .localHermes: "本机 Hermes"
        case .pairing: "设备"
        case .settings: "设置"
        }
    }

    var symbol: String {
        switch self {
        case .overview: "square.grid.2x2"
        case .diagnostics: "waveform.path.ecg"
        case .localHermes: "desktopcomputer"
        case .pairing: "iphone"
        case .settings: "gearshape"
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        MainShellView()
        .modifier(SetupConfirmationSheets())
        .onChange(of: model.entryRoute, initial: true) { _, route in
            model.entryRouteDidChange(route)
        }
    }
}

/// The account entry remains global; content still follows account/setup gates.
private struct MainShellView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @SceneStorage("desktop.selectedSection") private var selection: DesktopSection = .overview

    @State private var showsSignIn = false
    @State private var showsAccount = false

    var body: some View {
        HStack(spacing: 0) {
            SidebarView(selection: $selection, showSignIn: { showsSignIn = true },
                        showOverview: { showsSignIn = false }, showAccount: { showsAccount = true })
                .frame(width: 210)

            Rectangle()
                .fill(Color.hermesHairline(colorScheme))
                .frame(width: 1)

            Group {
                switch model.entryRoute {
                case .launching: LaunchGateView()
                case .launchFailed: LaunchFailedGateView()
                case .signIn(let reason):
                    if showsSignIn { SignInGateView(expiredReason: reason) }
                    else { OverviewView(selection: $selection) }
                case .serviceUnavailable: ServiceUnavailableGateView()
                case .accountDeletionSubmitted: AccountDeletionSubmittedGateView()
                case .newMacChoice: NewMacChoiceGateView()
                case .onboarding(let step): OnboardingGateView(step: step)
                case .main:
                    switch selection {
                    case .overview: OverviewView(selection: $selection)
                    case .diagnostics: DiagnosticsView(selection: $selection)
                    case .localHermes: LocalHermesView(selection: $selection)
                    case .pairing: AccountDevicesView()
                    case .settings: SettingsView()
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.hermesCanvas(colorScheme))
        .onChange(of: model.diagnosticRequestID) { _, _ in selection = .diagnostics }
        .onChange(of: model.accountSignInRequestID) { _, _ in showsSignIn = true }
        .onChange(of: model.entryRoute) { _, route in
            if case .signIn = route { showsAccount = false }
            else { showsSignIn = false }
            if route == .accountDeletionSubmitted { showsAccount = false }
        }
        .sheet(isPresented: $showsAccount) {
            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    Button("完成") { showsAccount = false }.keyboardShortcut(.cancelAction)
                        .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
                }.padding(16)
                AccountDevicesView(accountOnly: true)
            }.frame(width: 620, height: 620)
                .interactiveDismissDisabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
        }
    }
}

private struct SidebarView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection
    let showSignIn: () -> Void
    let showOverview: () -> Void
    let showAccount: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                AppLogoView(size: 42)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Hermes GO")
                        .font(.system(size: 17, weight: .bold))
                    Text("Desktop")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 24)
            .padding(.bottom, 26)

            ForEach(DesktopSection.allCases) { item in
                Button {
                    selection = item
                    if item == .overview { showOverview() }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: item.symbol)
                            .font(.system(size: 16, weight: .regular))
                            .frame(width: 22)
                        Text(item.title)
                            .font(.system(size: 14, weight: selection == item ? .semibold : .regular))
                        Spacer()
                    }
                    .foregroundStyle(selection == item ? Color.hermesBlue : .primary)
                    .padding(.horizontal, 13)
                    .frame(height: 42)
                    .background(selection == item ? Color.hermesBlue.opacity(0.09) : .clear)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .accessibilityLabel(item.title)
                .disabled(model.entryRoute != .main && !(item == .overview && isSignInRoute))
            }

            Spacer()

            Divider().padding(.horizontal, 18).padding(.bottom, 12)
            accountEntry.padding(.horizontal, 14).padding(.bottom, 12)
            Text(versionLabel)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 18)
                .padding(.bottom, 18)
        }
        .background(Color.hermesCard(colorScheme).opacity(0.8))
    }

    private var isSignInRoute: Bool {
        if case .signIn = model.entryRoute { return true }
        return false
    }

    @ViewBuilder private var accountEntry: some View {
        if case .signedIn(let dashboard) = model.accountState {
            HStack(spacing: 4) {
                Button(action: showAccount) { accountLabel }.buttonStyle(.plain)
                Menu {
                    Text(dashboard.session.account.email ?? "Hermes GO 账号")
                    Button("管理账号", action: showAccount)
                    Button("设备管理") { selection = .pairing }
                        .disabled(model.entryRoute != .main)
                    Divider()
                    Button("退出这台 Desktop 的账号") { Task { await model.signOutAccount() } }
                        .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
                } label: { Image(systemName: "ellipsis.circle").accessibilityLabel("账号操作") }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
            }
                .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
        } else {
            Button {
                if isSignInRoute { showSignIn() }
                else { Task { await model.refreshAccount(bootstrap: true) } }
            } label: { accountLabel }
                .buttonStyle(.plain)
                .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked
                          || model.accountState == .checking || model.accountState == .accountDeletionSubmitted)
        }
    }

    private var accountLabel: some View {
        HStack(spacing: 9) {
            Image(systemName: "person.crop.circle.fill")
                .font(.system(size: 28)).foregroundStyle(Color.hermesBlue)
            VStack(alignment: .leading, spacing: 4) {
                Text(accountTitle).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                Text(accountSubtitle).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            .help("\(accountTitle) · \(accountSubtitle)")
            .accessibilityElement(children: .combine)
    }

    private var accountTitle: String {
        if case .signedIn(let dashboard) = model.accountState {
            return dashboard.session.account.displayName ?? dashboard.session.account.email ?? "我的账号"
        }
        if case .needsSignIn = model.accountState { return model.lastAccountIdentity?.displayName ?? "重新登录" }
        return switch model.accountState {
        case .checking: "正在检查账号"
        case .signingIn: "正在登录"
        case .unavailable: "账号服务待确认"
        case .accountDeletionSubmitted: "账号删除已提交"
        default: "登录账号"
        }
    }

    private var accountSubtitle: String {
        switch model.accountState {
        case .signedIn(let dashboard): dashboard.session.account.email ?? "管理账号"
        case .needsSignIn: "登录已失效，点击重新登录"
        case .signingIn, .checking: "请稍候"
        case .unavailable: "点击重新检查"
        case .accountDeletionSubmitted: "本机 Hermes 数据保留"
        default: "登录后管理远程连接"
        }
    }

    private var versionLabel: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return "版本 \(version ?? "开发版")-dev"
    }
}

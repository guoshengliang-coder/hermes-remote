import HermesGoDesktopCore
import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection
    @State private var isPhoneSheetPresented = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Text(model.overviewDeviceName.uppercased())
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .tracking(0.8)
                    Spacer()
                    Label(accountSummary, systemImage: "person.crop.circle")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }

                PageHeader(title: model.statusTitle, subtitle: model.statusDetail) {
                    HStack(spacing: 10) {
                        Button("运行诊断") { selection = .diagnostics }
                            .buttonStyle(.bordered)
                            .controlSize(.large)
                        Button("账号与设备") { selection = .pairing }
                            .buttonStyle(.bordered)
                            .controlSize(.large)
                    }
                }

                if showsSetupProblem {
                    setupProblemCard
                } else if model.isManageOnly {
                    manageOnlyBanner
                } else if showsNoPhoneHint {
                    noPhoneBanner
                }
                agentStatusBanner

                HStack(alignment: .top, spacing: 18) {
                    informationCard
                    activityCard
                }

                Text("上次完整检查：\(model.presentedHealth.checkedAt.formatted(date: .omitted, time: .shortened))")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
            }
            .padding(34)
        }
        .refreshable { await model.refresh() }
    }

    /// §8: a half-installed or inconsistent Mac stays on main, so the reason belongs at the top of
    /// the overview instead of behind the sidebar.
    private var showsSetupProblem: Bool {
        model.bootstrapPlan.readiness == .existingServiceNeedsAttention
    }

    private var setupProblemCard: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 18))
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 3) {
                Text(model.bootstrapPlan.titleChinese)
                    .font(.system(size: 13, weight: .semibold))
                Text(model.bootstrapPlan.detailChinese)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 12)
            VStack(alignment: .trailing, spacing: 6) {
                if model.serviceRepairNeeded {
                    Button("修复 Desktop") { Task { await model.prepareServiceRepair() } }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.isManagedBootstrapAccountLocked)
                }
                Button("查看详情") { selection = .pairing }
                    .buttonStyle(.bordered)
                CopyDiagnosticsButton(
                    issue: model.serviceRepairIssue ?? model.managedBootstrapIssue ?? model.componentBootstrapIssue
                )
            }
        }
        .padding(14)
        .background(Color.orange.opacity(colorScheme == .dark ? 0.16 : 0.08))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    /// Chosen "只在这台 Mac 上管理" (§7.1): the way back into onboarding stays one click away.
    private var manageOnlyBanner: some View {
        HStack(spacing: 14) {
            Image(systemName: "laptopcomputer")
                .font(.system(size: 20))
                .foregroundStyle(Color.hermesBlue)
            VStack(alignment: .leading, spacing: 2) {
                Text("这台 Mac 只用于管理")
                    .font(.system(size: 13, weight: .semibold))
                Text("本机没有安装后台服务。想让手机也能使用这台 Mac 上的 Hermes，可以把它连上。")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("把这台 Mac 也连上") { Task { await model.chooseNewMacUse(.connect) } }
                .buttonStyle(.bordered)
        }
        .padding(14)
        .background(Color.hermesBlue.opacity(colorScheme == .dark ? 0.16 : 0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    /// After "稍后再说" in onboarding step 4, the reminder lives here rather than in a second prompt.
    private var showsNoPhoneHint: Bool {
        guard let dashboard = model.currentDashboard,
              model.bootstrapPlan.readiness == .managedInstallActive
                || model.bootstrapPlan.readiness == .managedUpgradeAvailable
        else { return false }
        return DesktopRemoteClients.active(dashboard.installations).isEmpty
    }

    private var noPhoneBanner: some View {
        HStack(spacing: 14) {
            Image(systemName: "iphone")
                .font(.system(size: 20))
                .foregroundStyle(Color.hermesBlue)
            VStack(alignment: .leading, spacing: 2) {
                Text("还没有手机连接")
                    .font(.system(size: 13, weight: .semibold))
                Text("在 Android 手机上安装 App，或在 iPhone / iPad 上把网页版添加到主屏幕，用同一个邮箱登录。")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("连上手机") { isPhoneSheetPresented = true }
                .buttonStyle(.bordered)
        }
        .padding(14)
        .background(Color.hermesBlue.opacity(colorScheme == .dark ? 0.16 : 0.06))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .sheet(isPresented: $isPhoneSheetPresented) {
            VStack(alignment: .leading, spacing: 16) {
                Text("连上手机")
                    .font(.system(size: 22, weight: .bold))
                PhoneConnectPanel(showsHeader: false)
                HStack {
                    Spacer()
                    Button("完成") { isPhoneSheetPresented = false }
                        .keyboardShortcut(.defaultAction)
                }
            }
            .padding(26)
            .frame(width: 860)
        }
    }

    private var accountSummary: String {
        switch model.accountState {
        case .signedIn(let dashboard):
            dashboard.session.account.displayName ?? "账号已登录"
        case .needsSignIn:
            "账号需要重新登录"
        case .checking, .signingIn:
            model.accountIssue == nil ? "正在检查账号" : "账号状态需确认"
        case .unavailable:
            "账号模式未开放"
        case .signedOut:
            "未登录账号"
        case .accountDeletionSubmitted:
            "云端账号删除已提交"
        }
    }

    private var agentStatusBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "eye")
                .foregroundStyle(Color.hermesBlue)
            VStack(alignment: .leading, spacing: 2) {
                Text(agentBannerTitle)
                    .font(.system(size: 13, weight: .semibold))
                Text(agentStatusDetail)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(14)
        .background(Color.hermesBlue.opacity(colorScheme == .dark ? 0.16 : 0.07))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var agentStatusDetail: String {
        if let device = model.selectedAccountDevice {
            return device.connector.online
                ? "\(device.desktopDisplayName) 已通过账号安全连接；状态来自该设备最近一次健康上报。"
                : "\(device.desktopDisplayName) 当前离线；现有本机服务不会因查看其他设备而改变。"
        }
        switch model.agentPresentation.mode {
        case .managedVerified:
            return "Desktop 后台连接正在运行；关闭窗口后仍可从手机使用。"
        case .managedUnverified:
            return "后台服务已保留；若刚完成迁移助理，请重新登录以核验这台 Mac，现有账号绑定会保留。"
        case .attentionRequired:
            return "Desktop 连接暂时无法确认，远程访问可能受影响；请打开诊断查看恢复状态。"
        case .checking:
            return "正在检查 Desktop 后台连接，不会在检查期间修改后台服务。"
        case .unavailable:
            return "请通过账号完成这台 Mac 的设置。"
        }
    }

    private var agentBannerTitle: String {
        if let device = model.selectedAccountDevice {
            return device.connector.online ? "Desktop 连接正常" : "Desktop 当前离线"
        }
        return switch model.agentPresentation.mode {
        case .managedVerified:
            "Desktop 后台连接正在运行"
        case .managedUnverified:
            "检测到Desktop 后台连接"
        case .attentionRequired:
            "后台连接状态需要核验"
        case .checking:
            "正在检查后台连接"
        case .unavailable:
            "Desktop 尚未完成设置"
        }
    }

    private var informationCard: some View {
        infoCard(title: "连接信息", rows: [
            ("设备", model.overviewDeviceName),
            ("账号连接", model.overviewGatewaySummary),
            ("Hermes", model.presentedHealth.component(.hermes).level == .healthy ? "可以使用" : "待确认"),
        ])
    }

    private var activityCard: some View {
        infoCard(title: "运行状态", rows: [
            ("后台模式", backgroundModeSummary),
            ("Desktop", model.appVersion),
            ("问题排查", "请打开诊断"),
        ])
    }

    private var backgroundModeSummary: String {
        if model.selectedAccountDevice != nil { return "账号连接" }
        return switch model.agentPresentation.mode {
        case .managedVerified: "正在运行"
        case .managedUnverified: "待核验"
        case .attentionRequired: "需要核验"
        case .checking: "检查中"
        case .unavailable: "未检测"
        }
    }

    private func infoCard(title: String, rows: [(String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .padding(.bottom, 13)
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider() }
                HStack {
                    Text(row.0).foregroundStyle(.secondary)
                    Spacer()
                    Text(row.1).lineLimit(1)
                }
                .font(.system(size: 13))
                .frame(height: 36)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }
}

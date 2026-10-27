import HermesGoDesktopCore
import SwiftUI

struct LocalHermesView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection
    @State private var showsConnectionSetup = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(title: "本机 Hermes", subtitle: "查看这台 Mac 的 Hermes 安装与可用状态") {
                    Button("刷新状态") { Task { await model.refreshDiagnostics() } }
                        .buttonStyle(.bordered)
                        .disabled(model.isRefreshing || model.isDiagnosing || model.isManagedBootstrapAccountLocked)
                }
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 12) {
                        HermesBrandIcon().frame(width: 30, height: 30)
                        Label(model.localHermesPresentation.title,
                              systemImage: model.localHermesPresentation.canOpen ? "checkmark.circle.fill" : "info.circle")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(Color.status(model.localHermesPresentation.level, scheme: colorScheme))
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 180), alignment: .topLeading)], alignment: .leading, spacing: 18) {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(model.localHermesPresentation.version).font(.system(size: 24, weight: .medium))
                            Text("Hermes 版本").font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                        HermesFact(title: "本机验证", value: model.localHermesPresentation.canOpen ? "已通过" : (model.localHermesPresentation.level == .failed ? "未通过" : "待验证"),
                                   level: model.localHermesPresentation.level)
                        HermesModelFact(showsName: true)
                    }
                    if !model.localHermesPresentation.canOpen {
                        Text(model.localHermesPresentation.detail).font(.system(size: 13)).foregroundStyle(.secondary)
                    }
                    if model.localHermesDetection?.installation != nil { ModelConfigurationPrompt() }
                    if let issue = model.localHermesIssue { GateIssueBanner(issue: issue) }
                    Divider()
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 10) { actions }
                        VStack(alignment: .leading, spacing: 10) { actions }
                    }.buttonStyle(.bordered)
                    DisclosureGroup("检查详情") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(model.localHermesPresentation.detail)
                            if model.localHermesDetection?.installation != nil {
                                Text("使用这台 Mac 已有的 Hermes，保留原有配置与数据。")
                                Text(model.currentModelConfiguration.detail)
                            }
                            if let checkedAt = model.localManagedHermesHealth?.checkedAt {
                                Text("本机检查于 \(checkedAt.formatted(date: .abbreviated, time: .shortened))")
                            }
                        }.font(.system(size: 12)).foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
                    }
                }
                .padding(22).frame(maxWidth: .infinity, alignment: .leading).hermesCard()

                HermesVersionCard(
                    report: model.hermesUpgradeReport,
                    readiness: model.hermesUpgradeReadiness,
                    issue: model.hermesUpgradeIssue,
                    isChecking: model.isCheckingHermesUpgrade,
                    isBusy: model.isDesktopUpdateRunning || model.isManagedBootstrapAccountLocked,
                    checkVersions: { Task { await model.checkHermesUpdates() } },
                    checkConditions: { Task { await model.checkHermesUpgradeConditions() } },
                    record: model.hermesUpgradeRecord,
                    isRefreshingRecord: model.isRefreshingHermesUpgradeRecord,
                    refreshRecord: { Task { await model.refreshHermesUpgradeRecord() } },
                    recommendation: model.hermesRecommendation
                )

                if showsConnectionSetup, model.currentDashboard != nil {
                    DesktopConnectionSetupView { showsConnectionSetup = false }
                } else if model.hermesInstallPhase != .hidden, model.currentDashboard != nil { HermesInstallCard() }

            }
            .padding(34)
        }
    }
    @ViewBuilder private var actions: some View {
        Button("打开 Hermes") { model.openHermes() }
            .disabled(!model.localHermesPresentation.canOpen || model.isManagedBootstrapAccountLocked)
        Button(model.localHermesPresentation.canOpen ? "查看诊断" : "诊断与修复") { selection = .diagnostics }
        if model.currentDashboard == nil {
            Button("登录后连接本机") { model.requestAccountSignIn() }
        } else if model.isManageOnly {
            Button("连接这台 Mac") { Task { await model.chooseNewMacUse(.connect) } }
        } else if !model.localHermesPresentation.canOpen {
            Button("连接设置") { showsConnectionSetup = true }
        }
    }

}

/// An actionable setup hint only when the authenticated model metadata explicitly reports no model.
struct ModelConfigurationPrompt: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        if model.currentModelConfiguration == .missing {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { message; Spacer(minLength: 8); configureButton }
                VStack(alignment: .leading, spacing: 10) { message; configureButton }
            }
            .padding(12)
            .background(Color.accentColor.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
        }
    }

    private var message: some View {
        Label("尚未配置模型，请先配置模型再开始 AI 任务。", systemImage: "info.circle")
            .font(.system(size: 13))
            .fixedSize(horizontal: false, vertical: true)
    }

    private var configureButton: some View {
        Button("配置模型") { model.showsModelSetup = true }
            .buttonStyle(.bordered)
            .disabled(model.isManagedBootstrapAccountLocked)
    }
}

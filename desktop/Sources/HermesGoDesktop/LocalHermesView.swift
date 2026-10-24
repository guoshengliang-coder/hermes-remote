import HermesGoDesktopCore
import SwiftUI

struct LocalHermesView: View {
    @EnvironmentObject private var model: DesktopViewModel
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
                VStack(alignment: .leading, spacing: 14) {
                    Label(model.localHermesPresentation.title,
                          systemImage: model.localHermesPresentation.canOpen ? "checkmark.circle.fill" : "desktopcomputer")
                        .font(.system(size: 20, weight: .bold))
                    Text(model.localHermesPresentation.detail).foregroundStyle(.secondary)
                    LabeledContent("Hermes 版本", value: model.localHermesPresentation.version)
                    if model.localHermesDetection?.installation != nil {
                        Text("使用这台 Mac 已有的 Hermes，保留原有配置与数据。")
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    if let checkedAt = model.localManagedHermesHealth?.checkedAt {
                        Text("本机检查于 \(checkedAt.formatted(date: .abbreviated, time: .shortened))")
                            .font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    HStack {
                        Button("打开 Hermes") { model.openHermes() }
                            .buttonStyle(.borderedProminent)
                            .disabled(!model.localHermesPresentation.canOpen)
                        Button("诊断与修复") { selection = .diagnostics }.buttonStyle(.bordered)
                        Button("配置模型") { model.showsModelSetup = true }
                            .disabled(model.localHermesDetection?.installation == nil)
                        if model.currentDashboard == nil {
                            Button("登录后连接本机") { model.requestAccountSignIn() }
                        } else if model.isManageOnly {
                            Button("连接这台 Mac") { Task { await model.chooseNewMacUse(.connect) } }
                                .buttonStyle(.bordered)
                        } else if !model.localHermesPresentation.canOpen {
                            Button("连接设置") { showsConnectionSetup = true }.buttonStyle(.bordered)
                        }
                    }
                    if model.localHermesDetection?.installation != nil {
                        LabeledContent("模型配置", value: model.modelConfiguration.title)
                        Text(model.modelConfiguration.detail).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    if let issue = model.localHermesIssue { GateIssueBanner(issue: issue) }
                }
                .padding(22).frame(maxWidth: .infinity, alignment: .leading).hermesCard()

                if showsConnectionSetup, model.currentDashboard != nil {
                    DesktopConnectionSetupView { showsConnectionSetup = false }
                } else if model.hermesInstallPhase != .hidden, model.currentDashboard != nil { HermesInstallCard() }

            }
            .padding(34)
        }
    }
}

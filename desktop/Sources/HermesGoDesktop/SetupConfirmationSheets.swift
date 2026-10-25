import HermesGoDesktopCore
import SwiftUI

/// The two machine-changing confirmation sheets of the managed (schema-v1) and component
/// (schema-v2) installers. Attached once at the window root so they present wherever the setup was
/// started — onboarding step 3 or "账号与设备" — and never twice.
struct SetupConfirmationSheets: ViewModifier {
    @EnvironmentObject private var model: DesktopViewModel

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $model.showsModelSetup) { ModelSetupSheet() }
            .sheet(item: Binding(
                get: { model.serviceRepairPreparation },
                set: { value in
                    if value == nil { Task { await model.cancelServiceRepair() } }
                }
            )) { plan in
                ManagedServiceRepairConfirmation(
                    plan: plan, running: model.isServiceRepairRunning,
                    cancel: { Task { await model.cancelServiceRepair() } },
                    confirm: { Task { await model.confirmServiceRepair() } }
                )
            }
            .sheet(item: Binding(
                get: {
                    model.managedBootstrapOperation.presentsConfirmation
                        ? model.managedBootstrapPreparation : nil
                },
                set: { value in
                    if value == nil, model.managedBootstrapOperation == .awaitingConfirmation {
                        Task { await model.cancelManagedBootstrapConfirmation() }
                    }
                }
            )) { preparation in
                managedBootstrapConfirmationSheet(preparation)
            }
            .sheet(item: Binding(
                get: {
                    model.componentBootstrapOperation.presentsConfirmation
                        ? model.componentBootstrapPreparation : nil
                },
                set: { value in
                    if value == nil, model.componentBootstrapOperation == .awaitingConfirmation {
                        Task { await model.cancelComponentBootstrapConfirmation() }
                    }
                }
            )) { preparation in
                componentBootstrapConfirmationSheet(preparation)
            }
            .sheet(isPresented: $model.isUpdateSheetPresented) {
                updateAvailableSheet()
            }
    }

    private func updateAvailableSheet() -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Desktop 更新", systemImage: "arrow.down.circle.fill")
                .font(.system(size: 19, weight: .bold)).foregroundStyle(Color.hermesBlue)
            Text(model.updateStatusText).font(.system(size: 15, weight: .semibold))
            if let report = model.updateCheckState.report, report.hasUpdates {
                let notes = (report.appUpdate?.releaseNotes ?? []) + (report.managedUpdate?.releaseNotes ?? [])
                ScrollView {
                    VStack(alignment: .leading, spacing: 8) {
                        ForEach(Array(notes.enumerated()), id: \.offset) { _, note in
                            Text(note).font(.system(size: 13)).foregroundStyle(.secondary)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }.frame(maxHeight: 200)
                Text(report.managedUpdate != nil
                    ? "更新会短暂中断手机连接，也可能中断正在运行的任务。请等任务完成后继续。后台更新验证成功后，Desktop 会按需退出并自动重启。"
                    : "更新会退出并自动重启 Desktop。已经设置的后台连接会继续运行；请勿在安装或修复过程中退出。")
                    .font(.system(size: 12)).foregroundStyle(.secondary)
            }
            if let issue = model.appUpdateIssue { GateIssueBanner(issue: issue) }
            if let message = model.appUpdateStatusMessage {
                Text(message).font(.system(size: 12)).foregroundStyle(.secondary)
            }
            if model.isDesktopUpdateRunning { ProgressView("正在更新 Desktop…") }
            HStack {
                Button(model.updateCheckState.report?.hasUpdates == true ? "稍后" : "确定") { model.isUpdateSheetPresented = false }
                    .disabled(model.isDesktopUpdateRunning).keyboardShortcut(.cancelAction)
                Spacer()
                if model.updateCheckState.report?.hasUpdates == true {
                    Button("更新 Desktop") { Task { await model.installDesktopUpdate() } }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.isDesktopUpdateRunning || model.isManagedBootstrapAccountLocked)
                }
            }
        }
        .padding(26).frame(width: 560)
        .interactiveDismissDisabled(model.isDesktopUpdateRunning)
    }

    /// The adopt path installs no Hermes — it takes over the copy already on this Mac — so it must
    /// not promise an install. The plan is what knows that, the same way it knows a run is an
    /// upgrade (`SecondaryViews` reads `readiness` for `ComponentPreflightView`).
    private func managedBootstrapConfirmationDetail(
        _ preparation: DesktopManagedBootstrapPreparation
    ) -> String {
        if preparation.intent.isUpgrade {
            return "继续后会更新 Desktop 的后台运行能力，保留账号、设备绑定和本机数据。手机连接会短暂中断，运行中的任务也可能中断，请等任务完成后继续。健康检查未通过时会尝试恢复旧版本，结果可在诊断中查看。"
        }
        if model.bootstrapPlan.readiness == .readyToAdoptLocalHermes {
            return "继续后会接管这台 Mac 上已有的 Hermes：不安装第二份代码，也不改动它的 .env 与数据，为 Desktop 启用登录后自动运行和账号连接。现有本机服务会短暂重启，请等任务完成后继续；如有冲突，请按诊断指引处理。"
        }
        return "继续后会完成 Desktop 后台设置，启用登录后自动运行并连接当前账号。模型服务凭据和 Hermes 数据仍只保存在这台 Mac。"
    }

    private func managedBootstrapConfirmLabel(
        _ preparation: DesktopManagedBootstrapPreparation
    ) -> String {
        if preparation.intent.isUpgrade { return "升级并重连" }
        return model.bootstrapPlan.readiness == .readyToAdoptLocalHermes ? "接管并连接" : "安装并连接"
    }

    private func managedBootstrapConfirmationSheet(
        _ preparation: DesktopManagedBootstrapPreparation
    ) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Label("安装包签名已验证", systemImage: "checkmark.shield.fill")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("Desktop 安装内容已验证")
                .font(.system(size: 15, weight: .semibold))
            Text(managedBootstrapConfirmationDetail(preparation))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if model.managedBootstrapOperation == .committing {
                SetupExecutionProgress(progress: model.managedSetupProgress)
            } else {
                HStack {
                    Button("取消") {
                        Task { await model.cancelManagedBootstrapConfirmation() }
                    }
                    .keyboardShortcut(.cancelAction)
                    Spacer()
                    Button(managedBootstrapConfirmLabel(preparation)) {
                        Task { await model.confirmManagedBootstrap() }
                    }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(26)
        .frame(width: 540)
        .interactiveDismissDisabled(model.managedBootstrapOperation == .committing)
    }

    private func componentBootstrapConfirmationSheet(
        _ preparation: DesktopComponentBootstrapPreparation
    ) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Label("Desktop 更新内容已验证", systemImage: "checkmark.shield.fill")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("Desktop 安装内容已验证")
                .font(.system(size: 15, weight: .semibold))
            Text(preparation.migratesBundledLayout
                ? "继续后会将 Desktop 的后台组件迁移到新版管理方式，保留本机 Hermes、模型配置、会话和账号绑定。不会下载或安装另一份 Hermes。手机连接和运行中的任务会短暂中断，请等任务完成后继续。健康检查失败时会恢复原来的连接。"
                : preparation.intent.isUpgrade
                ? "继续后会更新 Desktop 的后台运行能力，保留账号、设备绑定和本机数据。手机连接会短暂中断，任务也可能中断，请等任务完成后继续。健康检查未通过时会尝试恢复旧版本，结果可在诊断中查看。"
                : "继续后会完成 Desktop 设置，启用登录后自动运行并连接当前账号。手机连接会短暂中断，请等任务完成后继续。模型凭据和 Hermes 数据保留在这台 Mac。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            if model.componentBootstrapOperation == .committing {
                SetupExecutionProgress(progress: model.componentSetupProgress)
            } else {
                HStack {
                    Button("取消") {
                        Task { await model.cancelComponentBootstrapConfirmation() }
                    }
                    .keyboardShortcut(.cancelAction)
                    Spacer()
                    Button(preparation.migratesBundledLayout ? "迁移并重连"
                        : preparation.intent.isUpgrade ? "升级并重连" : "安装并连接") {
                        Task { await model.confirmComponentBootstrap() }
                    }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(26)
        .frame(width: 540)
        .interactiveDismissDisabled(model.componentBootstrapOperation == .committing)
    }
}

struct ModelSetupSheet: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var copied = false
    private var command: String {
        let path = model.localHermesDetection?.installation?.executable.path ?? "hermes"
        return DesktopModelConfiguration.setupCommand(executable: path)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("配置 Hermes 模型").font(.system(size: 20, weight: .bold))
            Text(model.modelConfiguration.detail).foregroundStyle(.secondary)
            Text("打开 Hermes，选择模型服务并完成登录或填写 API Key。也可以复制下面的命令，在终端运行官方配置向导。")
                .font(.system(size: 13))
            Text(command).font(.system(size: 12, design: .monospaced)).textSelection(.enabled)
            Text("配置保存在本机。完成后回到 Hermes 发起一次任务，确认模型服务可用。")
                .font(.system(size: 12)).foregroundStyle(.secondary)
            HStack {
                Button(copied ? "已复制" : "复制配置命令") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(command, forType: .string); copied = true
                }
                if model.localHermesPresentation.canOpen { Button("打开 Hermes") { model.openHermes() } }
                Spacer()
                Button("完成") { model.showsModelSetup = false; Task { await model.refresh() } }
            }
        }.padding(26).frame(width: 560)
    }
}

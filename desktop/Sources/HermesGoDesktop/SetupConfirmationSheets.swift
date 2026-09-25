import HermesGoDesktopCore
import SwiftUI

/// The two machine-changing confirmation sheets of the managed (schema-v1) and component
/// (schema-v2) installers. Attached once at the window root so they present wherever the setup was
/// started — onboarding step 3 or "账号与设备" — and never twice.
struct SetupConfirmationSheets: ViewModifier {
    @EnvironmentObject private var model: DesktopViewModel

    func body(content: Content) -> some View {
        content
            .sheet(item: Binding(
                get: { model.managedBootstrapPreparation },
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
                    switch model.componentBootstrapOperation {
                    case .awaitingConfirmation, .committing:
                        model.componentBootstrapPreparation
                    default:
                        nil
                    }
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
            Label("发现新版本", systemImage: "arrow.down.circle.fill")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)

            if let report = model.updateCheckState.report {
                if let app = report.appUpdate {
                    updateSection(title: "应用更新", update: app, installsApp: true)
                }
                if let managed = report.managedUpdate {
                    updateSection(title: "组件更新", update: managed, installsApp: false)
                }
            }

            if let issue = model.appUpdateIssue {
                GateIssueBanner(issue: issue)
            }
            if let message = model.appUpdateStatusMessage {
                Text(message)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }

            HStack {
                Spacer()
                Button("稍后") { model.isUpdateSheetPresented = false }
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(26)
        .frame(width: 560)
        .interactiveDismissDisabled(model.isAppUpdateInstalling)
    }

    private func updateSection(
        title: String,
        update: DesktopUpdateAvailability,
        installsApp: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                Spacer()
                Text("v\(update.version)")
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            if update.releaseNotes.isEmpty {
                Text("本次更新未提供说明。")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(update.releaseNotes.enumerated()), id: \.offset) { _, note in
                        HStack(alignment: .top, spacing: 7) {
                            Text("•")
                            Text(note)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    }
                }
            }
            HStack {
                Spacer()
                if installsApp {
                    Button(model.isAppUpdateInstalling ? "正在准备…" : "立即更新") {
                        Task { await model.installAppUpdate() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.isAppUpdateInstalling)
                } else {
                    Button("立即更新") { model.installManagedUpdate() }
                        .buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(16)
        .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func managedBootstrapConfirmationSheet(
        _ preparation: DesktopManagedBootstrapPreparation
    ) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Label("安装包签名已验证", systemImage: "checkmark.shield.fill")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("Hermes Go \(preparation.releaseVersion)")
                .font(.system(size: 15, weight: .semibold))
            Text(preparation.intent.isUpgrade
                ? "继续后会保留当前账号、设备绑定和本机数据，更新两个用户级自动启动项，并短暂重启 Hermes Server 与 Connector。新版本未通过健康检查时会自动恢复旧版本。"
                : "继续后会安装受管 Hermes Server 与 Connector、写入两个用户级自动启动项、绑定当前账号，并短暂重启这两个服务。模型服务凭据和 Hermes 数据仍只保存在这台 Mac。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(preparation.confirmationText)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .textSelection(.enabled)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 10))

            if model.managedBootstrapOperation == .committing {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("正在执行并验证，失败时会在提交前自动恢复。")
                        .font(.system(size: 12))
                }
            } else {
                HStack {
                    Button("取消") {
                        Task { await model.cancelManagedBootstrapConfirmation() }
                    }
                    .keyboardShortcut(.cancelAction)
                    Spacer()
                    Button(preparation.intent.isUpgrade ? "升级并重连" : "安装并连接") {
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
            Label("组件签名与内容已验证", systemImage: "checkmark.shield.fill")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("Hermes Go \(preparation.releaseVersion)")
                .font(.system(size: 15, weight: .semibold))
            Text(preparation.intent.isUpgrade
                ? "继续后会保留当前账号、设备绑定和本机数据，提交已验证的基础组件，并短暂重启 Hermes Server 与 Connector。新版本未通过健康检查时会自动恢复旧版本。不会修改 Homebrew。"
                : "继续后会把已验证的基础组件提交到受管目录，写入两个用户级自动启动项、绑定当前账号，并短暂启动或切换 Hermes Server 与 Connector。不会修改 Homebrew；模型服务凭据和 Hermes 数据仍只保存在这台 Mac。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(preparation.confirmationText)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .textSelection(.enabled)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 10))

            if model.componentBootstrapOperation == .committing {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("正在提交并验证，失败时会按迁移日志自动恢复。")
                        .font(.system(size: 12))
                }
            } else {
                HStack {
                    Button("取消") {
                        Task { await model.cancelComponentBootstrapConfirmation() }
                    }
                    .keyboardShortcut(.cancelAction)
                    Spacer()
                    Button(preparation.intent.isUpgrade ? "升级并重连" : "安装并连接") {
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

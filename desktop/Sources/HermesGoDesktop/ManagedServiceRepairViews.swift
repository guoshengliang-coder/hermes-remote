import HermesGoDesktopCore
import SwiftUI

struct ManagedServiceRepairCard: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("修复这台 Mac 的受管服务", systemImage: "wrench.and.screwdriver")
                .font(.system(size: 16, weight: .bold))
            Text("启动项缺失或尚未载入。先检查原安装和账号，确认后恢复连接；设备绑定和 Hermes 数据会保留。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let issue = model.serviceRepairIssue { GateIssueBanner(issue: issue) }
            if let guidance = model.serviceRepairGuidance {
                Text(guidance).font(.system(size: 12)).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                if model.isServiceRepairPreparing || model.isServiceRepairRunning {
                    ProgressView().controlSize(.small)
                    Text(model.isServiceRepairRunning ? "正在修复并验证连接…" : "正在检查，尚未修改服务…")
                        .font(.system(size: 12))
                }
                Spacer()
                Button("检查并修复受管服务") { Task { await model.prepareServiceRepair() } }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.isManagedBootstrapAccountLocked || model.isAccountOperationInProgress)
            }
        }
        .padding(20)
        .hermesCard()
    }
}

struct ManagedServiceRepairConfirmation: View {
    let plan: DesktopManagedServiceRepairPreparation
    let running: Bool
    let cancel: () -> Void
    let confirm: () -> Void

    var body: some View {
        ServiceRepairConfirmationContent(
            releaseVersion: plan.releaseVersion, filePaths: plan.filePaths,
            usesLocalHermes: plan.usesLocalHermes, recovering: plan.resumesInterruptedRepair,
            running: running, cancel: cancel, confirm: confirm
        )
        .interactiveDismissDisabled(running)
    }
}

/// Presentation is separate from the live model, allowing isolated visual checks without Keychain,
/// network, launchd, or the user's actual installation.
struct ServiceRepairConfirmationContent: View {
    let releaseVersion: String
    let filePaths: [String]
    let usesLocalHermes: Bool
    let recovering: Bool
    let running: Bool
    let cancel: () -> Void
    let confirm: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label(recovering ? "恢复上次未完成的修复" : "修复受管服务", systemImage: "wrench.and.screwdriver")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("原受管安装 · \(releaseVersion)").font(.system(size: 14, weight: .semibold))
            Text(recovering
                 ? "会恢复上次修复前的启动项和服务状态。完成后请重新检查，再确认新的修复。"
                 : "会恢复缺失的启动项、载入未启动的服务，并在必要时短暂重启 Connector，验证这台 Mac 的连接。")
                .font(.system(size: 12)).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(usesLocalHermes ? "Hermes 使用这台 Mac 已有的标准安装。" : "Hermes 使用原受管安装中的运行程序。")
                .font(.system(size: 12))
            Text("当前账号、设备绑定、机器凭据和 Hermes 数据会保留。")
                .font(.system(size: 12, weight: .semibold))
            DisclosureGroup("启动项与文件范围") {
                ForEach(filePaths, id: \.self) { path in
                    Text(path).font(.system(size: 10, design: .monospaced))
                        .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }.font(.system(size: 12))
            Text("验证失败时恢复本次修改；恢复未完成时保留私有快照并显示处理步骤。")
                .font(.system(size: 11)).foregroundStyle(.secondary)
            if running {
                HStack { ProgressView().controlSize(.small); Text("正在修复并验证，请保持 Desktop 打开…") }
                    .font(.system(size: 12))
            } else {
                HStack {
                    Button("取消", action: cancel).keyboardShortcut(.cancelAction)
                    Spacer()
                    Button(recovering ? "恢复上次操作" : "修复并连接", action: confirm)
                        .buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(26)
        .frame(width: 540)
    }
}

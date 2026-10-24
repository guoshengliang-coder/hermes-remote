import HermesGoDesktopCore
import SwiftUI

/// Contextual setup surface; device management never performs local installation preflight.
struct DesktopConnectionSetupView: View {
    @EnvironmentObject private var model: DesktopViewModel
    let close: () -> Void
    @State private var isBundledHermesChoicePresented = false

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("连接这台 Mac").font(.system(size: 21, weight: .bold))
                Spacer()
                Button("收起") { close() }.disabled(model.isManagedBootstrapAccountLocked)
            }
            if model.hermesInstallPhase != .hidden {
                HermesInstallCard()
            }
            if let presentation = model.componentPreflightPresentation {
                ComponentPreflightCard(
                    presentation: presentation,
                    canBegin: model.componentBootstrapCanBegin && !model.isHermesInstallDecisionPending,
                    isUpgrade: model.bootstrapPlan.readiness == .managedUpgradeAvailable,
                    operation: model.componentBootstrapOperation,
                    cleanupRetryAvailable: model.componentCleanupRetryAvailable,
                    prepare: { Task {
                        if model.bootstrapPlan.readiness == .managedUpgradeAvailable { await model.checkForUpdates(manual: true) }
                        else { await model.prepareComponentBootstrap() }
                    } },
                    retryCleanup: { Task { await model.retryComponentBootstrapCleanup() } }
                )
            } else if model.componentCleanupRetryAvailable,
                      model.componentBootstrapOperation == .failed {
                componentCleanupRecoveryCard
            }
            if let issue = model.componentBootstrapIssue {
                GateIssueBanner(issue: issue)
            }
            if model.serviceRepairNeeded {
                ManagedServiceRepairCard()
            } else {
                bootstrapPlanCard(model.bootstrapPlan)
            }
            if let issue = model.managedBootstrapIssue {
                GateIssueBanner(issue: issue)
            }
            if model.isFreshInstallBlockedByDesktopsOwnCheckout, model.hermesInstallPhase == .hidden {
                bundledHermesChoiceCard
            } else if model.isFreshInstallBlockedByOwnersHermes {
                ownersHermesGuidanceCard
            }
            if let issue = model.localHermesIssue {
                GateIssueBanner(issue: issue)
            }
        }.padding(20).hermesCard()
        .task { await model.refreshComponentPreflight() }
    }

    private func bootstrapPlanCard(_ plan: DesktopBootstrapPlan) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack {
                Label("本机安装预检", systemImage: bootstrapSymbol(plan.readiness))
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Text(plan.canBegin ? "可开始" : "只读预检")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(plan.canBegin ? Color.hermesBlue : .secondary)
            }
            Text(plan.titleChinese)
                .font(.system(size: 15, weight: .semibold))
            Text(plan.detailChinese)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !plan.steps.isEmpty {
                Divider()
                ForEach(plan.steps) { step in
                    HStack(spacing: 9) {
                        Image(systemName: step.changesMachine ? "circle" : "checkmark.circle")
                            .foregroundStyle(step.changesMachine ? .secondary : Color.hermesBlue)
                            .frame(width: 16)
                        Text(step.titleChinese)
                            .font(.system(size: 11))
                        Spacer()
                        if step.changesMachine {
                            Text("执行前确认")
                                .font(.system(size: 10))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            managedBootstrapAction(plan)
        }
        .padding(20)
        .hermesCard()
    }

    @ViewBuilder
    private func managedBootstrapAction(_ plan: DesktopBootstrapPlan) -> some View {
        switch model.managedBootstrapOperation {
        case .preparing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在下载并验证签名安装包；尚未修改安装或服务")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .awaitingConfirmation:
            Divider()
            Label("安装包已验证，等待你的明确确认", systemImage: "checkmark.shield")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.hermesBlue)
        case .committing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在安装、启动并验证；请保持 Desktop 打开")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .recovering:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在恢复上次中断的操作，不会开始第二次安装")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .completed(_, let cleanupPending):
            Divider()
            HStack {
                Label("Desktop 已连接", systemImage: "checkmark.circle.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color.hermesBlue)
                Spacer()
                if cleanupPending {
                    Button("重试清理") {
                        Task { await model.retryManagedBootstrapCleanup() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        case .idle, .failed:
            if plan.canBegin, !model.isComponentBootstrapPathSelected, model.isHermesInstallDecisionPending {
                Divider()
                Text("先在上方决定如何获取 Hermes：安装官方 Hermes，或改用内置 Hermes。")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            } else if plan.canBegin, !model.isComponentBootstrapPathSelected {
                Divider()
                HStack {
                    Text("第一步只写入私有临时缓存，不会停止或启动任何服务。")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(plan.readiness == .managedUpgradeAvailable
                        ? "查看 Desktop 更新"
                        : "下载并验证安装包") {
                        Task {
                            if plan.readiness == .managedUpgradeAvailable { await model.checkForUpdates(manual: true) }
                            else { await model.prepareManagedBootstrap() }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(model.isManagedBootstrapAccountLocked)
                }
            }
        }
    }

    /// Shown with `HR-MIGRATE-008` on a fresh setup only when the blocking checkout is the one
    /// Hermes GO's own install left behind — never for a Hermes the owner installed.
    private var bundledHermesChoiceCard: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("这份 Hermes 是 Hermes GO 之前的安装留下的")
                    .font(.system(size: 13, weight: .semibold))
                Text("它没有安装完整，Hermes GO 无法直接使用。可以改用 Hermes GO 内置的 Hermes 完成设置；留下的文件保持不动，删除 ~/.hermes/hermes-agent 后可重新安装官方 Hermes。")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Button("改用内置 Hermes") { isBundledHermesChoicePresented = true }
                .buttonStyle(.bordered)
        }
        .padding(18)
        .hermesCard()
        .confirmationDialog(
            "这台 Mac 改用 Hermes GO 内置的 Hermes？",
            isPresented: $isBundledHermesChoicePresented
        ) {
            Button("改用内置 Hermes") { model.useBundledHermes() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("之后的设置会安装并运行 Hermes GO 自带的 Hermes。Hermes GO 之前留下的文件不会被修改。")
        }
    }

    /// Shown with `HR-MIGRATE-008` when the owner's own Hermes blocks a fresh setup. No alternative
    /// is offered: the owner's rule is never a second copy next to a Hermes they installed.
    private var ownersHermesGuidanceCard: some View {
        Label(
            "这台 Mac 上的 Hermes 是你自己安装的，Hermes GO 不会再另装一份。请按“复制诊断”里的原因整理它（例如改为默认的 ~/.hermes、升级到 \(DesktopLocalHermesDetector.minimumVersion) 或更新），修复后重新检查。已有 profiles 和会话无需删除。",
            systemImage: "info.circle"
        )
        .font(.system(size: 11))
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var componentCleanupRecoveryCard: some View {
        HStack(spacing: 14) {
            Label("Desktop 更新临时文件待清理", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                .font(.system(size: 13, weight: .semibold))
            Spacer()
            Button("重试清理") {
                Task { await model.retryComponentBootstrapCleanup() }
            }
            .buttonStyle(.bordered)
        }
        .padding(18)
        .hermesCard()
    }

    private func bootstrapSymbol(_ readiness: DesktopBootstrapReadiness) -> String {
        switch readiness {
        case .checking: "ellipsis.circle"
        case .existingServiceNeedsAttention: "exclamationmark.shield"
        case .waitingForSignedRelease: "signature"
        case .readyForManagedInstall: "shippingbox.and.arrow.backward"
        case .readyToAdoptLocalHermes: "desktopcomputer"
        case .managedUpgradeAvailable: "arrow.triangle.2.circlepath.circle"
        case .managedInstallActive: "checkmark.circle.fill"
        }
    }

}

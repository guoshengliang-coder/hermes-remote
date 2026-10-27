import Foundation
import HermesGoDesktopCore

extension DesktopViewModel {
    /// Active work wins over retained terminal results from previous, unrelated operations.
    var activeOperationKind: DesktopOperationStatus.Kind? {
        if hermesVersionManager.isRunning { return .hermesUpgrade }
        if isDesktopUpdateRunning || isAppUpdateInstalling { return .desktopUpdate }
        if hermesInstallPhase.isRunning { return .hermesInstall }
        if isServiceRepairPreparing || isServiceRepairRunning || serviceRepairPreparation != nil { return .serviceRepair }
        switch componentBootstrapOperation {
        case .preparing, .awaitingConfirmation, .committing, .completed(_, cleanupPending: true): return .componentSetup
        case .failed where componentCleanupRetryAvailable: return .componentSetup
        default: break
        }
        switch managedBootstrapOperation {
        case .preparing, .awaitingConfirmation, .committing, .recovering, .completed(_, cleanupPending: true): return .managedSetup
        default: return nil
        }
    }

    func operationStatus(for kind: DesktopOperationStatus.Kind, now: Date = Date()) -> DesktopOperationStatus {
        typealias Status = DesktopOperationStatus
        switch kind {
        case .hermesUpgrade:
            let manager = hermesVersionManager
            let state: Status.State = manager.isRunning ? .running : manager.phase == .failed ? .failed
                : manager.phase == .completed ? (manager.issue == nil ? .succeeded : .attention) : .cancelled
            var measurements = [manager.networkSummary, manager.substep].filter { !$0.isEmpty }
            if let transfer = manager.transfer {
                measurements.append(transfer.summary)
                if let at = manager.transferAt, now.timeIntervalSince(at) < 5, let speed = transfer.speedSummary {
                    measurements.append("最近报告速度：" + speed)
                }
            }
            measurements.append("已完成 \(manager.completedStepCount) / \(DesktopHermesUpgradeStep.allCases.count) 步；各步骤耗时不同。")
            return .init(kind: kind, state: state,
                stage: manager.isRunning ? (manager.progress.isEmpty ? "正在准备升级" : manager.progress)
                    : manager.phase == .failed ? "升级未完成：" + manager.step.titleChinese : manager.resultSummary,
                detail: "升级期间请保持 Desktop 运行。收起窗口不会停止升级。",
                issue: manager.issue, measurements: measurements, output: manager.output,
                activity: "\(manager.step)-\(manager.lastOutputAt?.timeIntervalSince1970 ?? 0)-\(manager.transferAt?.timeIntervalSince1970 ?? 0)",
                retryTitle: manager.phase == .failed && manager.canUpgrade ? manager.retryTitle : nil)
        case .desktopUpdate:
            if isAppUpdateHandoff {
                return .init(kind: kind, state: .handoff, stage: "更新包已交给安装程序", detail: "Desktop 即将退出并自动重启；重启后再确认版本。")
            }
            if desktopUpdateSequence.stage == .stopped {
                let background = setupOperationStatus(desktopUpdateBackgroundKind)
                if background.state == .attention {
                    return .init(kind: kind, state: .attention, stage: background.stage, issue: background.issue ?? appUpdateIssue,
                                 retryTitle: background.retryTitle)
                }
            }
            if let issue = appUpdateIssue { return .init(kind: kind, state: .failed, stage: "Desktop 更新未完成", issue: issue, retryTitle: "重新检查 Desktop 更新") }
            if isAppUpdateInstalling || desktopUpdateSequence.stage == .app {
                return .init(kind: kind, state: .running, stage: "正在下载、校验 Desktop 更新包", detail: "安装程序尚未提供下载字节数；完成验证后会自动重启 Desktop。")
            }
            if desktopUpdateSequence.stage == .completed { return .init(kind: kind, state: .succeeded, stage: "Desktop 更新已完成") }
            let backgroundKind = desktopUpdateBackgroundKind
            var result = setupOperationStatus(backgroundKind)
            result = .init(kind: kind, state: result.state, stage: result.stage, detail: result.detail,
                           issue: result.issue, measurements: result.measurements, activity: result.activity,
                           retryTitle: result.state == .failed ? "重新检查 Desktop 更新" : result.retryTitle)
            return result
        case .managedSetup, .componentSetup:
            return setupOperationStatus(kind)
        case .serviceRepair:
            if isServiceRepairPreparing { return .init(kind: kind, state: .running, stage: "正在检查原安装和修复条件") }
            if isServiceRepairRunning { return .init(kind: kind, state: .running, stage: "正在恢复服务并验证连接", detail: "修复执行器暂未提供更细的阶段进度。") }
            if serviceRepairPreparation != nil { return .init(kind: kind, state: .waitingForConfirmation, stage: "修复条件已检查，等待确认") }
            if let issue = serviceRepairIssue { return .init(kind: kind, state: .failed, stage: "连接修复未完成", detail: serviceRepairGuidance ?? "", issue: issue, retryTitle: "重新检查修复条件") }
            return .init(kind: kind, state: serviceRepairCompleted ? .succeeded : .cancelled,
                         stage: serviceRepairCompleted ? "连接修复已完成" : "修复已取消")
        case .hermesInstall:
            switch hermesInstallPhase {
            case .running(_, let run, let cancelling):
                return .init(kind: kind, state: .running,
                    stage: cancelling ? "正在结束安装" : run.verifying ? "正在验证 Hermes 安装" : run.current?.stage.titleChinese ?? "正在下载安装程序",
                    detail: run.retryMessage ?? "请保持 Desktop 运行。", measurements: ["已完成 \(run.completedCount) 个安装阶段"],
                    activity: String(describing: run))
            case .failed(_, _, let issue): return .init(kind: kind, state: .failed, stage: "Hermes 安装未完成", issue: issue, retryTitle: "重试安装 Hermes")
            case .succeeded(let version): return .init(kind: kind, state: .succeeded, stage: "Hermes \(version) 已安装")
            default: return .init(kind: kind, state: .cancelled, stage: "Hermes 安装已取消")
            }
        }
    }

    private func setupOperationStatus(_ kind: DesktopOperationStatus.Kind) -> DesktopOperationStatus {
        let component = kind == .componentSetup
        let state = component ? componentBootstrapOperation.uiState : managedBootstrapOperation.uiState
        let progress = (component ? componentSetupProgress : managedSetupProgress)?.snapshot
        let issue = component ? componentBootstrapIssue : managedBootstrapIssue
        let cleanup = component ? componentCleanupRetryAvailable : false
        switch state {
        case .preparing: return .init(kind: kind, state: .running, stage: "正在检查、下载并验证后台组件", detail: "此准备阶段尚未提供下载字节数。")
        case .awaitingConfirmation: return .init(kind: kind, state: .waitingForConfirmation, stage: "组件已准备，等待确认服务切换")
        case .committing, .recovering:
            return .init(kind: kind, state: .running, stage: progress?.stage.titleChinese ?? (state == .recovering ? "正在恢复上次连接操作" : "正在准备服务切换"),
                detail: "服务切换期间请保持 Desktop 运行。收起窗口不会停止操作。", activity: progress?.stage.rawValue ?? state.rawValue)
        case .completed: return .init(kind: kind, state: .succeeded, stage: "连接设置已完成")
        case .cleanupPending: return .init(kind: kind, state: .attention, stage: "连接已生效，临时文件待清理", issue: issue, retryTitle: "重试清理临时文件")
        case .failed: return .init(kind: kind, state: cleanup ? .attention : .failed, stage: "连接设置未完成", issue: issue,
                                   retryTitle: cleanup ? "重试清理临时文件" : "重新检查连接设置")
        case .idle: return .init(kind: kind, state: .cancelled, stage: "连接设置已取消")
        }
    }

    func retryOperation(_ kind: DesktopOperationStatus.Kind) async {
        switch kind {
        case .hermesUpgrade: await upgradeLocalHermes()
        case .desktopUpdate:
            if componentCleanupRetryAvailable { await retryComponentBootstrapCleanup() }
            else if case .completed(_, true) = managedBootstrapOperation { await retryManagedBootstrapCleanup() }
            else { await checkForUpdates(manual: true) }
        case .serviceRepair: await prepareServiceRepair()
        case .hermesInstall: startHermesInstall()
        case .componentSetup:
            if componentCleanupRetryAvailable { await retryComponentBootstrapCleanup() }
            else { await prepareComponentBootstrap() }
        case .managedSetup:
            if case .completed(_, true) = managedBootstrapOperation { await retryManagedBootstrapCleanup() }
            else { await prepareManagedBootstrap() }
        }
    }
}

// Isolated native UI and adapter checks. See docs/DESKTOP_TEST_PLAN.md.
import AppKit
import SwiftUI
@testable import HermesGoDesktopCore

enum DesktopHermesInstallPhase {
    case hidden, running(Int, DesktopHermesInstallRun, cancelling: Bool), failed(Int, DesktopHermesInstallRun, DesktopIssue), succeeded(version: String)
    var isRunning: Bool { if case .running = self { true } else { false } }
}
@MainActor final class DesktopViewModel: ObservableObject {
    let hermesVersionManager = DesktopHermesVersionManager(detect: { .absent(hermesDataPresent: false) }, execute: { _,_,_ in })
    var desktopUpdateSequence = DesktopUpdateSequence()
    var isDesktopUpdateRunning: Bool { desktopUpdateSequence.isRunning }
    var isAppUpdateInstalling = false, isAppUpdateHandoff = false
    var isAutomaticConnectorRecovery = false, isUpdateSheetPresented = false
    var appUpdateIssue: DesktopIssue?
    var desktopUpdateBackgroundKind = DesktopOperationStatus.Kind.managedSetup
    var hermesInstallPhase = DesktopHermesInstallPhase.hidden
    var isServiceRepairPreparing = false, isServiceRepairRunning = false, serviceRepairCompleted = false
    var serviceRepairPreparation: Int?
    var serviceRepairIssue: DesktopIssue?
    var serviceRepairGuidance: String?
    var componentBootstrapOperation = DesktopComponentBootstrapOperation.idle
    var managedBootstrapOperation = DesktopManagedBootstrapOperation.idle
    var componentSetupProgress: DesktopSetupProgress?
    var managedSetupProgress: DesktopSetupProgress?
    var componentBootstrapIssue: DesktopIssue?
    var managedBootstrapIssue: DesktopIssue?
    var componentCleanupRetryAvailable = false
    var managedRecentLogs: [String] = []
    var actions: [String] = []
    func upgradeLocalHermes() async { actions.append("upgrade") }
    func checkForUpdates(manual: Bool) async { actions.append("check") }
    func prepareServiceRepair() async { actions.append("repair") }
    func startHermesInstall() { actions.append("install") }
    func retryComponentBootstrapCleanup() async { actions.append("component-cleanup") }
    func prepareComponentBootstrap() async { actions.append("component-prepare") }
    func retryManagedBootstrapCleanup() async { actions.append("managed-cleanup") }
    func prepareManagedBootstrap() async { actions.append("managed-prepare") }
    func refreshLogs() async {}
    func requestOperationDiagnostics() {}
}
@main struct Fixture {
    @MainActor static func main() async throws {
        try FileManager.default.createDirectory(atPath: "/private/tmp/operation-ui", withIntermediateDirectories: true)
        _ = NSApplication.shared
        let model = DesktopViewModel()
        precondition(model.activeOperationKind == nil)
        model.managedBootstrapOperation = .preparing
        precondition(model.activeOperationKind == .managedSetup)
        precondition(model.operationStatus(for: .managedSetup).state == .running)
        model.managedBootstrapOperation = .awaitingConfirmation
        precondition(model.operationStatus(for: .managedSetup).state == .waitingForConfirmation)
        model.managedBootstrapOperation = .completed(releaseVersion: "fixture", cleanupPending: true)
        precondition(model.operationStatus(for: .managedSetup).state == .attention)
        await model.retryOperation(.managedSetup)
        precondition(model.actions.last == "managed-cleanup")
        model.managedBootstrapOperation = .idle
        model.componentBootstrapOperation = .failed
        model.componentCleanupRetryAvailable = true
        precondition(model.operationStatus(for: .componentSetup).state == .attention)
        await model.retryOperation(.componentSetup)
        precondition(model.actions.last == "component-cleanup")
        model.desktopUpdateBackgroundKind = .componentSetup
        model.desktopUpdateSequence.begin(background: true, app: true)
        model.componentBootstrapOperation = .idle
        precondition(model.operationStatus(for: .desktopUpdate).state == .running)
        model.componentBootstrapOperation = .awaitingConfirmation
        precondition(model.activeOperationKind == .desktopUpdate)
        precondition(model.operationStatus(for: .desktopUpdate).state == .waitingForConfirmation)
        model.desktopUpdateSequence.backgroundFinished(succeeded: true, cleanupPending: true)
        model.componentBootstrapOperation = .completed(releaseVersion: "fixture", cleanupPending: true)
        model.appUpdateIssue = .init(code: .migrationCleanupPending)
        precondition(model.operationStatus(for: .desktopUpdate).state == .attention)
        await model.retryOperation(.desktopUpdate)
        precondition(model.actions.last == "component-cleanup")
        model.appUpdateIssue = nil
        model.desktopUpdateSequence.begin(background: true, app: true)
        model.desktopUpdateSequence.backgroundFinished(succeeded: true, cleanupPending: false)
        precondition(model.operationStatus(for: .desktopUpdate).stage.contains("更新包"))
        model.isAppUpdateHandoff = true
        precondition(model.operationStatus(for: .desktopUpdate).state == .handoff)
        model.isServiceRepairRunning = true
        precondition(model.operationStatus(for: .serviceRepair).state == .running)
        model.isServiceRepairRunning = false
        model.serviceRepairCompleted = true
        precondition(model.operationStatus(for: .serviceRepair).state == .succeeded)

        let confirmationMonitor = DesktopOperationWindow()
        confirmationMonitor.sample(.init(kind: .desktopUpdate, state: .running, stage: "准备"))
        precondition(NSApp.windows.contains { $0.title == "操作进度" && $0.isVisible })
        confirmationMonitor.sample(.init(kind: .desktopUpdate, state: .waitingForConfirmation, stage: "确认"))
        precondition(!NSApp.windows.contains { $0.title == "操作进度" && $0.isVisible })
        confirmationMonitor.sample(.init(kind: .desktopUpdate, state: .running, stage: "执行"))
        precondition(NSApp.windows.contains { $0.title == "操作进度" && $0.isVisible })
        confirmationMonitor.close()

        let scenarios: [(String, DesktopOperationStatus)] = [
            ("download", .init(kind: .hermesUpgrade, state: .running, stage: "第 2 / 9 步 · 下载 Hermes", measurements: ["已下载 26 MiB / 84 MiB", "最近报告速度：2 MiB/s"], output: ["正在接收上游代码……"])),
            ("waiting", .init(kind: .desktopUpdate, state: .waitingForConfirmation, stage: "组件已准备，等待确认服务切换")),
            ("silent", .init(kind: .desktopUpdate, state: .running, stage: "正在等待云端确认连接", detail: "服务切换期间请保持 Desktop 运行。收起窗口不会停止操作。")),
            ("failed", .init(kind: .hermesUpgrade, state: .failed, stage: "升级未完成", issue: .init(code: .hermesUpdateExecutionFailed), retryTitle: "重试：准备 Hermes 应用与工具")),
            ("cleanup", .init(kind: .componentSetup, state: .attention, stage: "连接已生效，临时文件待清理", issue: .init(code: .migrationCleanupPending), retryTitle: "重试清理临时文件")),
            ("success", .init(kind: .serviceRepair, state: .succeeded, stage: "连接修复已完成"))
        ]
        for (name, status) in scenarios {
            for (theme, width) in [("light", 620.0), ("dark", 620.0), ("compact", 500.0)] {
                let monitor = DesktopOperationWindow()
                monitor.sample(.init(kind: status.kind, state: .running, stage: "准备"))
                monitor.sample(status)
                if name == "silent" { monitor.now += 62 }
                monitor.show()
                let panel = NSApp.windows.first { $0.title == "操作进度" && $0.isVisible }!
                panel.setContentSize(NSSize(width: width, height: 440))
                panel.appearance = NSAppearance(named: theme == "dark" ? .darkAqua : .aqua)
                try await Task.sleep(for: .milliseconds(400))
                let view = panel.contentView!
                view.layoutSubtreeIfNeeded()
                let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds)!
                view.cacheDisplay(in: view.bounds, to: rep)
                try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: "/private/tmp/operation-ui/\(name)-\(theme).png"))
                let generation = monitor.tracker.generation
                panel.performClose(nil)
                precondition(monitor.tracker.status?.state == status.state)
                monitor.show()
                precondition(panel.isVisible && monitor.tracker.generation == generation)
                monitor.close()
            }
        }
        print("Adapter state/retry checks and 18 production NSPanel fixtures passed; close/reopen preserved each operation.")
    }
}

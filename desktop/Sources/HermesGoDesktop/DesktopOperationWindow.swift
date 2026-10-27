import AppKit
import Combine
import HermesGoDesktopCore
import SwiftUI

@MainActor final class DesktopOperationWindow: ObservableObject {
    @Published private(set) var tracker = DesktopOperationTracker()
    @Published var now = ProcessInfo.processInfo.systemUptime
    private var window: NSWindow?
    var retry: (DesktopOperationStatus.Kind) -> Void = { _ in }
    var diagnose: () -> Void = {}

    func sample(_ status: DesktopOperationStatus?) {
        now = ProcessInfo.processInfo.systemUptime
        let old = tracker.status?.state
        let begins = tracker.update(status, now: now)
        // Confirmation already has its own sheet; don't cover it with a second window.
        if begins && status?.state == .running { show() }
        if old?.isActive == true, status?.state.isActive == false, status?.state != .cancelled { show() }
    }
    func show() {
        guard tracker.status != nil else { return }
        if window == nil {
            let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 590, height: 500),
                                styleMask: [.titled, .closable, .resizable, .utilityWindow], backing: .buffered, defer: false)
            panel.title = "操作进度"
            panel.isReleasedWhenClosed = false
            panel.minSize = NSSize(width: 500, height: 360)
            panel.contentView = NSHostingView(rootView: DesktopOperationProgressView(monitor: self))
            panel.center()
            window = panel
        }
        window?.makeKeyAndOrderFront(nil)
    }
    func hide() { window?.orderOut(nil) }
    func close() { window?.close(); window = nil }
}

struct DesktopOperationProgressView: View {
    @ObservedObject var monitor: DesktopOperationWindow
    var body: some View {
        if let status = monitor.tracker.status {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    if status.state == .running { ProgressView().controlSize(.small) }
                    Text(status.kind.title).font(.system(size: 20, weight: .bold))
                    Spacer()
                    Text(status.state.title).foregroundStyle(status.state == .failed ? .red : .secondary)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(status.stage).font(.system(size: 15, weight: .semibold))
                            .fixedSize(horizontal: false, vertical: true)
                        if !status.detail.isEmpty { Text(status.detail).foregroundStyle(.secondary) }
                        Text("总耗时 \(duration(monitor.tracker.elapsed(at: monitor.now))) · 本步骤 \(duration(monitor.tracker.stageElapsed(at: monitor.now)))")
                            .monospacedDigit()
                        if status.state == .running {
                            Text("最近进展：\(duration(monitor.tracker.silentSeconds(at: monitor.now)))前")
                                .monospacedDigit().foregroundStyle(.secondary)
                        }
                        ForEach(Array(status.measurements.enumerated()), id: \.offset) { _, line in Text(line) }
                        if monitor.tracker.showsSilence(at: monitor.now) {
                            Label("暂未收到新进展，不能据此判断卡住。可查看执行记录和诊断；请勿重复启动操作。", systemImage: "clock")
                                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                        if let issue = status.issue {
                            Text(issue.displayChinese).foregroundStyle(.red)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        DisclosureGroup("查看执行记录") {
                            Text("近期后台记录可能包含此前操作，请结合当前步骤查看。").foregroundStyle(.secondary)
                            Text(status.output.isEmpty ? "当前执行器未提供文本输出。阶段变化和耗时显示在上方，更多信息可查看诊断。" : status.output.joined(separator: "\n"))
                                .font(.system(size: 11, design: .monospaced)).textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }.font(.system(size: 13)).frame(maxWidth: .infinity, alignment: .leading)
                }
                Divider()
                HStack {
                    Button("查看诊断") { monitor.hide(); monitor.diagnose() }
                    if let title = status.retryTitle, !status.state.isActive {
                        Button(title) { monitor.hide(); monitor.retry(status.kind) }
                    }
                    Spacer()
                    Button(status.state == .waitingForConfirmation ? "返回确认" : status.state.isActive ? "收起" : "关闭") { monitor.hide() }
                        .keyboardShortcut(.cancelAction)
                }
                if status.state.isActive {
                    Text(status.state == .waitingForConfirmation ? "请在主窗口确认；确认前不会切换服务。" : "收起后操作继续，退出 Desktop 会中断操作。")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
            }.padding(24).frame(minWidth: 450, minHeight: 320)
                .background(Color(nsColor: .windowBackgroundColor))
        }
    }
    private func duration(_ seconds: Int) -> String { "\(seconds / 60) 分 \(seconds % 60) 秒" }
}

/// This status window is separate from confirmation sheets so closing it never cancels work.
struct DesktopOperationWindowHost: ViewModifier {
    @EnvironmentObject private var model: DesktopViewModel
    @StateObject private var monitor = DesktopOperationWindow()
    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if let status = monitor.tracker.status {
                    HStack {
                        Text("\(status.kind.title) · \(status.state.title)").font(.system(size: 12, weight: .semibold))
                        Text(status.stage).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                        Spacer()
                        Button(status.state.isActive ? "查看进度" : "查看操作结果") { monitor.show() }
                    }.padding(12).background(.regularMaterial)
                }
            }
            .task {
                monitor.retry = { kind in Task { await model.retryOperation(kind) } }
                monitor.diagnose = { model.requestOperationDiagnostics() }
                var ticks = 0
                while !Task.isCancelled {
                    if ticks % 5 == 0, let kind = model.activeOperationKind, model.operationStatus(for: kind).state == .running {
                        await model.refreshLogs()
                    }
                    ticks += 1
                    sample()
                    do { try await Task.sleep(for: .seconds(1)) } catch { break }
                }
            }
            .onReceive(model.objectWillChange) { _ in Task { @MainActor in await Task.yield(); sample() } }
            .onReceive(model.hermesVersionManager.objectWillChange) { _ in Task { @MainActor in await Task.yield(); sample() } }
            .onDisappear { monitor.close() }
    }
    private func sample() {
        let kind = model.activeOperationKind ?? monitor.tracker.status?.kind
        var status = kind.map { model.operationStatus(for: $0) }
        if let kind, kind != .hermesUpgrade && kind != .hermesInstall {
            status?.output = model.managedRecentLogs.filter { $0.hasPrefix("[desktop-runtime.log]") }
        }
        monitor.sample(status)
    }
}

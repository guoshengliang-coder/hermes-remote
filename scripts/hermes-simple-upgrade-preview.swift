// Test-only host for the production card/controller. Never instantiates the daily Desktop model.
import AppKit
import SwiftUI
@testable import HermesGoDesktopCore

final class DesktopViewModel: ObservableObject { let currentModelConfiguration = DesktopModelConfiguration.unknown }
struct GateIssueBanner: View {
    let issue: DesktopIssue
    var body: some View { Text(issue.displayChinese).font(.system(size: 12)).foregroundStyle(.orange) }
}
private actor PreviewInstallation {
    var version = "0.21.3"
    func update() { version = "0.22.5" }
    func detection() -> DesktopLocalHermesDetection {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("hermes-preview-only")
        return .usable(.init(executable: root.appendingPathComponent("hermes"), checkoutRoot: root,
                            hermesHome: root, commit: String(repeating: version == "0.21.3" ? "a" : "b", count: 40),
                            version: version, identityChangedAt: Date()))
    }
}
private struct PreviewRelease: DesktopHermesReleaseFetching {
    func latestStable() async throws -> DesktopHermesUpgradeTarget {
        try .init(version: "0.22.5", commit: String(repeating: "b", count: 40),
                  releaseNotes: "# Preview fixture\n- Example release change\n- Example fix")
    }
}
@MainActor private final class PreviewModel: ObservableObject {
    let manager: DesktopHermesVersionManager
    @Published var unhealthy = false
    init() {
        let installation = PreviewInstallation()
        manager = .init(checker: .init(fetcher: PreviewRelease()), detect: { await installation.detection() }, execute: { _, progress, output in
            for stage in ["下载官方升级程序", "下载 Hermes", "安装 Python 依赖", "准备 Hermes 应用与工具"] {
                progress(stage); output("fixture: \(stage)")
                try await Task.sleep(for: .milliseconds(600))
            }
            await installation.update()
        })
    }
    func diagnose() async -> [DesktopDiagnosticCheck] {
        try? await Task.sleep(for: .seconds(2))
        return [.init(id: .localHermes, title: "本机 Hermes", source: "fixture", health: .init(component: .hermes,
            level: unhealthy ? .failed : .healthy, detail: unhealthy ? "模拟本机接口暂不可达" : "模拟接口通过", checkedAt: Date()))]
    }
}
@main struct SimpleUpgradePreview: App {
    @StateObject private var model = PreviewModel()
    var body: some Scene {
        WindowGroup("Hermes 首阶段升级 · 隔离交互验收") {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("隔离交互验收 · 使用模拟安装与检测数据").font(.headline)
                    Toggle("模拟升级后检测异常", isOn: $model.unhealthy)
                    HermesVersionCard(manager: model.manager, isBusy: false,
                        upgrade: { Task { await model.manager.upgrade { await model.diagnose() } } },
                        recheck: { Task { await model.manager.recheckRuntime { await model.diagnose() } } }, showDiagnostics: {})
                }.padding(24)
            }.frame(minWidth: 650, minHeight: 600)
        }.defaultSize(width: 720, height: 780)
    }
}

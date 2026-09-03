import AppKit
import HermesGoDesktopCore
import SwiftUI

struct MenuBarContentView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                AppLogoView(size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Hermes Go Desktop")
                        .font(.system(size: 14, weight: .bold))
                    HStack(spacing: 6) {
                        StatusDot(level: model.overallLevel, size: 8)
                        Text(model.statusTitle)
                            .font(.system(size: 12))
                    }
                }
            }
            .padding(14)

            Divider()

            accountStatusRow
            statusRow(.gateway)
            statusRow(.hermes)

            Divider()

            Button {
                openWindow(id: "main")
                NSApplication.shared.activate(ignoringOtherApps: true)
            } label: {
                Label("打开主窗口", systemImage: "macwindow")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 14)
            .frame(height: 38)

            Button {
                Task { await model.refresh() }
            } label: {
                Label("刷新状态", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 14)
            .frame(height: 38)

            Divider()

            Text("账号客户端 · 不影响旧 Connector")
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
                .padding(14)
        }
        .frame(width: 285)
    }

    private var accountStatusRow: some View {
        return HStack {
            Text("Hermes GO 账号")
            Spacer()
            StatusDot(level: model.accountPresentation.level, size: 8)
            Text(model.accountPresentation.menuLabel)
                .foregroundStyle(.secondary)
        }
        .font(.system(size: 12))
        .padding(.horizontal, 14)
        .frame(height: 34)
    }

    private func statusRow(_ component: HealthComponent) -> some View {
        let item = model.health.component(component)
        return HStack {
            Text(component.title)
            Spacer()
            StatusDot(level: item.level, size: 8)
            Text(shortStatus(item.level))
                .foregroundStyle(.secondary)
        }
        .font(.system(size: 12))
        .padding(.horizontal, 14)
        .frame(height: 34)
    }

    private func shortStatus(_ level: HealthLevel) -> String {
        switch level {
        case .checking: "检查中"
        case .healthy: "正常"
        case .degraded: "受限"
        case .failed: "异常"
        case .unavailable: "未知"
        }
    }
}

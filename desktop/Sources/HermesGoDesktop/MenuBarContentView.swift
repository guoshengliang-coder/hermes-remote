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
                        StatusDot(level: headline.level, size: 8)
                        Text(headline.title)
                            .font(.system(size: 12))
                    }
                }
            }
            .padding(14)

            Divider()

            accountStatusRow
            statusRows
            if let gateAction {
                gateActionRow(gateAction)
            }

            Divider()

            Button {
                openMainWindow()
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

            Button {
                openMainWindow()
                Task { await model.checkForUpdates(manual: true) }
            } label: {
                Label("检查更新", systemImage: "arrow.down.circle")
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
        let presentation: (HealthLevel, String)
        if model.accountIssue != nil, model.accountState == .checking {
            presentation = (.degraded, "需确认")
        } else {
            presentation = switch model.accountState {
            case .signedIn: (.healthy, "已登录")
            case .needsSignIn: (.failed, "需登录")
            case .checking, .signingIn: (.checking, "检查中")
            case .unavailable: (.unavailable, "未开放")
            case .signedOut: (.unavailable, "未登录")
            case .accountDeletionSubmitted: (.unavailable, "删除已提交")
            }
        }
        return HStack {
            Text("Hermes GO 账号")
            Spacer()
            StatusDot(level: presentation.0, size: 8)
            Text(presentation.1)
                .foregroundStyle(.secondary)
        }
        .font(.system(size: 12))
        .padding(.horizontal, 14)
        .frame(height: 34)
    }

    /// §9, and the reason the requirements document starts with it: a Mac that has simply not been
    /// set up yet must not read as a failure. "需要处理" with a red dot is the symptom the gate exists
    /// to remove, so before setup the headline counts the remaining steps.
    private var headline: (level: HealthLevel, title: String) {
        switch model.entryRoute {
        case .onboarding(let step):
            (.degraded, "还差 \(5 - step.rawValue) 步完成设置")
        case .newMacChoice:
            (.degraded, "还没有设置这台 Mac")
        default:
            (model.overallLevel, model.menuBarStatusTitle)
        }
    }

    /// §9: the menu bar keeps working while the window is gated. Signed out it offers sign-in, and a
    /// signed-in Mac with nothing installed offers to finish setup. Both only open the main window —
    /// `RootView` decides the page — so the menu bar can never bypass the gate.
    private var gateAction: (title: String, symbol: String)? {
        switch model.entryRoute {
        case .signIn: ("登录 Hermes GO", "person.crop.circle")
        case .onboarding: ("完成设置", "checklist")
        default: nil
        }
    }

    private func gateActionRow(_ action: (title: String, symbol: String)) -> some View {
        Button {
            openMainWindow()
        } label: {
            HStack {
                Label(action.title, systemImage: action.symbol)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .font(.system(size: 12, weight: .medium))
        .foregroundStyle(Color.hermesBlue)
        .padding(.horizontal, 14)
        .frame(height: 34)
    }

    /// §9: the rows under the account line describe whatever the owner can act on. In manage-only
    /// mode that is the Mac being managed, not this machine; on a Mac that has nothing installed yet
    /// it is that fact alone — local Gateway/Hermes rows would read as two failures caused by not
    /// having run setup, the symptom the requirements document opens with.
    @ViewBuilder private var statusRows: some View {
        if model.isManageOnly, let device = model.selectedAccountDevice {
            valueRow(
                title: "正在管理",
                value: device.desktopDisplayName,
                level: .healthy,
                showsStatusDot: false
            )
            valueRow(
                title: "Connector",
                value: device.connector.online ? "在线" : "离线",
                level: device.connector.online ? .healthy : .unavailable
            )
            valueRow(
                title: "Hermes",
                value: model.overviewHermesSummary,
                level: device.hermes.reachable == true ? .healthy : .unavailable
            )
        } else if isNotSetUp {
            valueRow(title: "这台 Mac", value: "未连接", level: .unavailable)
        } else {
            statusRow(.gateway)
            statusRow(.hermes)
        }
    }

    private var isNotSetUp: Bool {
        switch model.entryRoute {
        case .onboarding, .newMacChoice: true
        default: false
        }
    }

    private func valueRow(
        title: String,
        value: String,
        level: HealthLevel,
        showsStatusDot: Bool = true
    ) -> some View {
        HStack {
            Text(title)
            Spacer()
            if showsStatusDot {
                StatusDot(level: level, size: 8)
            }
            Text(value)
                .foregroundStyle(.secondary)
        }
        .font(.system(size: 12))
        .padding(.horizontal, 14)
        .frame(height: 34)
    }

    private func openMainWindow() {
        openWindow(id: "main")
        NSApplication.shared.activate(ignoringOtherApps: true)
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

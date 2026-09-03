import HermesGoDesktopCore
import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection

    private let topology: [HealthComponent] = [.desktopAgent, .gateway, .hermes, .endToEnd]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    Text((model.legacy?.config.deviceID ?? "MAC MINI").uppercased())
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .tracking(0.8)
                    Spacer()
                    Label(accountSummary, systemImage: "person.crop.circle")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }

                PageHeader(title: model.statusTitle, subtitle: model.statusDetail) {
                    HStack(spacing: 10) {
                        Button("运行诊断") { selection = .diagnostics }
                            .buttonStyle(PrimaryButtonStyle())
                        Button("账号与设备") { selection = .pairing }
                            .buttonStyle(.bordered)
                            .controlSize(.large)
                    }
                }

                compatibilityBanner
                topologyCard

                HStack(alignment: .top, spacing: 18) {
                    informationCard
                    activityCard
                }

                Text("上次完整检查：\(model.health.checkedAt.formatted(date: .omitted, time: .shortened))")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
            }
            .padding(34)
        }
        .refreshable { await model.refresh() }
    }

    private var accountSummary: String {
        switch model.accountState {
        case .signedIn(let dashboard):
            dashboard.session.account.displayName ?? "账号已登录"
        case .needsSignIn:
            "账号需要重新登录"
        case .checking, .signingIn:
            model.accountIssue == nil ? "正在检查账号" : "账号状态需确认"
        case .unavailable:
            "账号模式未开放"
        case .signedOut:
            "未登录账号"
        }
    }

    private var compatibilityBanner: some View {
        HStack(spacing: 12) {
            Image(systemName: "eye")
                .foregroundStyle(Color.hermesBlue)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.legacy?.isInstalled == true ? "正在观察现有 Connector" : "兼容观察模式已启用")
                    .font(.system(size: 13, weight: .semibold))
                Text(compatibilityDetail)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(14)
        .background(Color.hermesBlue.opacity(colorScheme == .dark ? 0.16 : 0.07))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var compatibilityDetail: String {
        if model.legacy?.isInstalled == true {
            return "Desktop 尚未接管后台连接，不会中断现有手机和 Hermes 会话。"
        }
        return "未检测到旧 Connector；Desktop 不会自动启动第二个后台实例。"
    }

    private var topologyCard: some View {
        HStack(spacing: 0) {
            ForEach(Array(topology.enumerated()), id: \.element) { index, component in
                topologyNode(component)
                if index < topology.count - 1 {
                    Rectangle()
                        .fill(Color.hermesHairline(colorScheme))
                        .frame(height: 1)
                        .frame(maxWidth: 66)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 22)
        .padding(.vertical, 25)
        .hermesCard()
    }

    private func topologyNode(_ component: HealthComponent) -> some View {
        let item = model.health.component(component)
        return VStack(spacing: 8) {
            ZStack {
                Circle()
                    .stroke(Color.hermesBlue, lineWidth: 1.4)
                    .frame(width: 48, height: 48)
                Image(systemName: topologySymbol(component))
                    .font(.system(size: 19, weight: .regular))
                    .foregroundStyle(Color.hermesBlue)
            }
            Text(component.title)
                .font(.system(size: 13, weight: .semibold))
            HStack(spacing: 6) {
                StatusDot(level: item.level, size: 8)
                Text(item.detail)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func topologySymbol(_ component: HealthComponent) -> String {
        switch component {
        case .desktopAgent: "shippingbox"
        case .gateway: "globe.asia.australia"
        case .hermes: "desktopcomputer"
        case .observer: "eye"
        case .endToEnd: "checkmark.shield"
        }
    }

    private var informationCard: some View {
        infoCard(title: "连接信息", rows: [
            ("设备", model.legacy?.config.deviceID ?? "—"),
            ("Gateway", model.legacy?.config.gatewayURL?.host ?? "未配置"),
            ("Hermes", model.legacy?.config.hermesBaseURL.host ?? "127.0.0.1"),
        ])
    }

    private var activityCard: some View {
        infoCard(title: "观察状态", rows: [
            ("旧 Connector", model.legacy?.isRunning == true ? "运行中" : "未运行"),
            ("只读观察", observerSummary),
            ("近期异常", recentWarningSummary),
            ("日志数量", "\(model.legacy?.recentLogs.count ?? 0) 条"),
        ])
    }

    private var recentWarningSummary: String {
        let count = model.legacy?.recentLogSummary.warningCount ?? 0
        return count == 0 ? "未发现疑似异常" : "\(count) 条历史记录"
    }

    private var observerSummary: String {
        guard model.legacy?.isInstalled == true else { return "未检测" }
        return model.legacy?.config.observerEnabled == true ? "已配置" : "已关闭"
    }

    private func infoCard(title: String, rows: [(String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .padding(.bottom, 13)
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider() }
                HStack {
                    Text(row.0).foregroundStyle(.secondary)
                    Spacer()
                    Text(row.1).lineLimit(1)
                }
                .font(.system(size: 13))
                .frame(height: 36)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }
}

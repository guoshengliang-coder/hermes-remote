import HermesGoDesktopCore
import SwiftUI

struct AccountLoginDevicesCard: View {
    let dashboard: AccountDashboard
    let isBusy: Bool
    let revoke: (ManagedAccountInstallation) -> Void

    var body: some View {
        let installations = dashboard.loginDevices
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("登录设备")
                    .font(.system(size: 17, weight: .bold))
                Spacer()
                Text("\(installations.count) 个")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .padding(.bottom, 12)
            if installations.isEmpty {
                Text("还没有手机 App 或 WebApp 使用这个账号登录。")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 14)
            } else {
                ForEach(Array(installations.enumerated()), id: \.element.id) { index, installation in
                    if index > 0 { Divider() }
                    HStack(spacing: 12) {
                        Image(systemName: installation.kind == "browser" ? "globe" : "iphone")
                            .foregroundStyle(Color.hermesBlue)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(installation.displayName)
                                .font(.system(size: 13, weight: .semibold))
                            Text("\(installation.kind == "browser" ? "WebApp · 浏览器" : "手机 App") · \(installation.status == "active" ? "登录有效" : "已撤销") · 最近活动 \(formattedActivity(installation.lastSeenAt))")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            if installation.kind == "browser" && !dashboard.browserInstallationRevocation {
                                Text("服务器更新后可在此撤销登录")
                                    .font(.system(size: 11)).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Button("撤销登录", role: .destructive) {
                            revoke(installation)
                        }
                            .buttonStyle(.bordered)
                            .fixedSize()
                            .disabled(!dashboard.canRevokeLoginDevice(installation) || isBusy)
                            .help(installation.kind == "browser" && !dashboard.browserInstallationRevocation
                                ? "服务器更新后可在此撤销 WebApp 登录" : "撤销此登录设备的账号访问")
                    }
                    .padding(.vertical, 6)
                    .frame(minHeight: 54)
                }
            }
        }
        .padding(20)
        .hermesCard()
    }

    private func formattedActivity(_ value: String) -> String {
        let format = ISO8601DateFormatter()
        format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = format.date(from: value) ?? ISO8601DateFormatter().date(from: value)
        return date?.formatted(date: .abbreviated, time: .shortened) ?? "未知"
    }

}

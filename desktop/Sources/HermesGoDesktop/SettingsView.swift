import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    title: "设置",
                    subtitle: "账号管理与兼容观察；不写入 Hermes"
                ) {
                    EmptyView()
                }

                VStack(alignment: .leading, spacing: 0) {
                    Text("兼容观察")
                        .font(.system(size: 16, weight: .bold))
                        .padding(.bottom, 12)
                    settingRow("旧 Connector", model.legacy?.isRunning == true ? "运行中" : "未运行")
                    Divider()
                    settingRow("设备 ID", model.legacy?.config.deviceID ?? "mac-mini")
                    Divider()
                    settingRow("Gateway", model.legacy?.config.gatewayURL?.absoluteString ?? "未检测到")
                    Divider()
                    settingRow("Hermes", model.legacy?.config.hermesBaseURL.absoluteString ?? "http://127.0.0.1:9119")
                    Divider()
                    settingRow("旧版手机连接", model.connectionProfile == nil ? "未配置" : "App Token 已存入 Keychain")
                    if let profile = model.connectionProfile {
                        Divider()
                        settingRow("手机 Gateway", profile.gatewayURL.absoluteString)
                    }
                }
                .padding(20)
                .hermesCard()

                VStack(alignment: .leading, spacing: 0) {
                    Text("Hermes GO 账号")
                        .font(.system(size: 16, weight: .bold))
                        .padding(.bottom, 12)
                    settingRow("账号状态", accountStatus)
                    if case .signedIn(let dashboard) = model.accountState {
                        Divider()
                        settingRow("账号", dashboard.session.account.email ?? dashboard.session.account.displayName ?? "已登录")
                        Divider()
                        settingRow("Desktop", dashboard.session.installation.displayName)
                    }
                    Text("账号与远程设备管理集中在侧边栏“账号与设备”；设置页不重复展示连接拓扑。")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .padding(.top, 12)
                }
                .padding(20)
                .hermesCard()

                VStack(alignment: .leading, spacing: 10) {
                    Text("安全边界")
                        .font(.system(size: 16, weight: .bold))
                    Text("Desktop 当前不会停止旧 Connector、修改 Hermes、导入明文凭据或开放本机端口。")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Button("打开本机 Hermes") { model.openHermes() }
                        .buttonStyle(.bordered)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .hermesCard()
            }
            .padding(34)
        }
    }

    private var accountStatus: String {
        switch model.accountState {
        case .signedIn: "已登录"
        case .signedOut: "未登录"
        case .needsSignIn: "需要重新登录"
        case .checking, .signingIn: "正在检查"
        case .unavailable: "账号模式未开放"
        }
    }

    private func settingRow(_ label: String, _ value: String) -> some View {
        HStack(spacing: 20) {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .font(.system(size: 13))
        .frame(height: 39)
    }
}

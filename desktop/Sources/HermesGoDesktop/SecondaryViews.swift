import HermesGoDesktopCore
import SwiftUI

struct LogsView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            PageHeader(
                title: "日志",
                subtitle: "仅显示 Desktop 与 Connector 的脱敏运行信息"
            ) {
                Button("打开日志目录") { model.openLegacyLogDirectory() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
            }

            ScrollView {
                Text(logText)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(18)
            }
            .hermesCard()

            Label("Token、密码、Cookie 和签名参数会在显示前自动隐藏。", systemImage: "lock")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
        .padding(34)
    }

    private var logText: String {
        let lines = model.legacy?.recentLogs ?? []
        return lines.isEmpty ? "暂无可显示的 Connector 日志。" : lines.joined(separator: "\n")
    }
}

struct AccountDevicesView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var legacyExpanded = false
    @State private var phoneToRemove: ManagedAccountInstallation?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    title: "账号与设备",
                    subtitle: "同一 Google 账号下的手机会连接到这一台 Desktop 和它所服务的 Hermes。"
                ) {
                    Button {
                        Task { await model.refreshAccount() }
                    } label: {
                        Label("刷新", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                    .disabled(model.isAccountOperationInProgress)
                }

                accountContent

                if let issue = model.accountIssue {
                    accountIssueCard(issue)
                }

                DisclosureGroup(isExpanded: $legacyExpanded) {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack(alignment: .top, spacing: 18) {
                            pairingCodeCard
                            configurationCard
                        }
                        Label(
                            "旧版连接继续兼容：Desktop 只保存 App Token，不读取 Connector Token 或 Hermes 凭据。",
                            systemImage: "lock.shield"
                        )
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    }
                    .padding(.top, 16)
                } label: {
                    Label("高级：旧版 URL、Token 与二维码连接", systemImage: "wrench.and.screwdriver")
                        .font(.system(size: 14, weight: .semibold))
                }
                .padding(18)
                .hermesCard()
            }
            .padding(34)
        }
        .confirmationDialog(
            "移除这台手机？",
            isPresented: Binding(
                get: { phoneToRemove != nil },
                set: { if !$0 { phoneToRemove = nil } }
            ),
            presenting: phoneToRemove
        ) { phone in
            Button("移除 \(phone.displayName)", role: .destructive) {
                Task {
                    await model.revokePhone(phone.id)
                    phoneToRemove = nil
                }
            }
            Button("取消", role: .cancel) { phoneToRemove = nil }
        } message: { phone in
            Text("只会撤销 \(phone.displayName) 这一个安装；其他手机、Desktop、Connector 和 Hermes 不受影响。")
        }
    }

    @ViewBuilder
    private var accountContent: some View {
        switch model.accountState {
        case .checking:
            statusCard(
                symbol: model.accountIssue == nil ? "ellipsis.circle" : "wifi.exclamationmark",
                title: model.accountIssue == nil ? "正在检查账号功能" : "暂时无法确认账号功能",
                detail: model.accountIssue == nil
                    ? "正在读取 Relay 能力和这台 Mac 的安全会话。"
                    : "现有 Connector 和旧版手机连接保持原样；可刷新重试或继续使用旧版连接。"
            )
        case .unavailable:
            statusCard(
                symbol: "lock.shield",
                title: "账号模式尚未开放",
                detail: "现有 Connector 和手机连接保持原样；可继续使用下方旧版连接。"
            )
        case .signedOut, .needsSignIn:
            signedOutCard
        case .signingIn:
            statusCard(
                symbol: "person.crop.circle.badge.clock",
                title: "正在等待 Google 授权",
                detail: "请在系统默认浏览器中选择账号，然后返回 Hermes Go Desktop。"
            )
        case .signedIn(let dashboard):
            signedInContent(dashboard)
        }
    }

    private var signedOutCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            AppLogoView(size: 52)
            Text("使用 Hermes GO 账号连接")
                .font(.system(size: 22, weight: .bold))
            Text("登录后，这台 Mac 可以查看账号绑定和已授权手机。Google 会在默认浏览器中显示账号选择器；Hermes GO 不读取浏览器资料。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 9) {
                accountBenefit("同账号的多台手机可连接同一个 Hermes", symbol: "iphone.gen3")
                accountBenefit("本机 Hermes 凭据不会上传", symbol: "lock.shield")
                accountBenefit("本阶段不会停止或替换旧 Connector", symbol: "arrow.triangle.2.circlepath")
            }
            Button("使用 Google 账号继续") {
                Task { await model.signInAccount() }
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.isAccountOperationInProgress)
            .accessibilityHint("将在系统默认浏览器中打开 Google 账号选择器")
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    @ViewBuilder
    private func signedInContent(_ dashboard: AccountDashboard) -> some View {
        HStack(alignment: .top, spacing: 18) {
            accountCard(dashboard)
            bindingCard(dashboard.binding)
        }
        phonesCard(dashboard.phones)
    }

    private func accountCard(_ dashboard: AccountDashboard) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Hermes GO 账号", systemImage: "person.crop.circle.fill")
                .font(.system(size: 16, weight: .bold))
            Text(dashboard.session.account.displayName ?? "Google 账号")
                .font(.system(size: 20, weight: .bold))
            if let email = dashboard.session.account.email {
                Text(email)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Divider()
            settingRow("当前 Desktop", value: dashboard.session.installation.displayName)
            settingRow("登录状态", value: "已验证")
            Button("仅退出这台 Desktop") {
                Task { await model.signOutAccount() }
            }
            .buttonStyle(.bordered)
            .disabled(model.isAccountOperationInProgress)
            Text("退出账号管理不会解绑、停止或重新配置 Connector。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func bindingCard(_ binding: AccountBindingSnapshot) -> some View {
        let presentation = bindingPresentation(binding)
        return VStack(alignment: .leading, spacing: 14) {
            Label("Desktop 与 Hermes", systemImage: presentation.symbol)
                .font(.system(size: 16, weight: .bold))
            Text(presentation.title)
                .font(.system(size: 20, weight: .bold))
            Text(presentation.detail)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let active = binding.binding ?? binding.previousBinding {
                Divider()
                settingRow("Mac", value: active.desktopDisplayName)
                settingRow("Connector", value: active.connector.online ? "在线" : "离线")
                settingRow("Hermes", value: active.hermes.reachable == true ? "可访问" : "未确认")
            }
            if binding.state == "no_binding" {
                Label("I3 只做账号管理与只读预检；Connector 接管会在带回滚的迁移阶段单独确认。", systemImage: "info.circle")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func phonesCard(_ phones: [ManagedAccountInstallation]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("已授权手机")
                    .font(.system(size: 17, weight: .bold))
                Spacer()
                Text("\(phones.count) 台")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .padding(.bottom, 12)
            if phones.isEmpty {
                Text("还没有手机使用这个账号登录。")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 14)
            } else {
                ForEach(Array(phones.enumerated()), id: \.element.id) { index, phone in
                    if index > 0 { Divider() }
                    HStack(spacing: 12) {
                        Image(systemName: "iphone")
                            .foregroundStyle(Color.hermesBlue)
                            .frame(width: 24)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(phone.displayName)
                                .font(.system(size: 13, weight: .semibold))
                            Text("\(phone.status == "active" ? "已授权" : "已撤销") · 最近活动 \(phone.lastSeenAt)")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button("移除", role: .destructive) { phoneToRemove = phone }
                            .buttonStyle(.bordered)
                            .disabled(phone.status != "active" || model.isAccountOperationInProgress)
                    }
                    .frame(minHeight: 54)
                }
            }
        }
        .padding(20)
        .hermesCard()
    }

    private func statusCard(symbol: String, title: String, detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 26))
                .foregroundStyle(Color.hermesBlue)
                .frame(width: 34)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.system(size: 17, weight: .bold))
                Text(detail).font(.system(size: 13)).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(20)
        .hermesCard()
    }

    private func accountIssueCard(_ issue: DesktopIssue) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 4) {
                Text(issue.summaryChinese)
                    .font(.system(size: 13, weight: .semibold))
                Text(issue.displayChinese)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("复制诊断") { model.copyDiagnostics(issue) }
                .buttonStyle(.bordered)
        }
        .padding(16)
        .hermesCard()
    }

    private func accountBenefit(_ text: String, symbol: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.system(size: 12))
            .foregroundStyle(.secondary)
    }

    private func bindingPresentation(_ binding: AccountBindingSnapshot) -> (symbol: String, title: String, detail: String) {
        switch binding.state {
        case "bound":
            let online = binding.binding?.connector.online == true
            return (
                online ? "checkmark.circle.fill" : "exclamationmark.triangle.fill",
                online ? "连接正常" : "Connector 当前离线",
                online ? "账号已绑定这台 Desktop，手机可以共享访问同一个 Hermes。" : "账号绑定仍保留；打开对应 Mac 上的 Connector 即可恢复。"
            )
        case "binding_pending":
            return ("clock", "正在等待 Connector 验证", "候选绑定尚未完成密钥与健康检查，现有连接不会被替换。")
        case "replacement_pending":
            return ("arrow.triangle.2.circlepath", "等待确认更换 Mac", "原来的 Desktop 在更换提交前仍保持工作。")
        case "revoked":
            return ("xmark.shield", "这台 Mac 的绑定已撤销", "需要重新验证账号后才能发起新的绑定或替换。")
        default:
            return ("desktopcomputer", "尚未建立账号绑定", "当前旧 Connector 不受影响；账号绑定将在安全迁移阶段完成。")
        }
    }

    private var pairingCodeCard: some View {
        VStack(spacing: 16) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(.white)
                    .frame(width: 258, height: 258)

                if model.isPairingCodeRevealed,
                   let payload = model.connectionProfile?.pairingPayloadData {
                    PairingQRCodeView(payload: payload)
                        .padding(18)
                        .frame(width: 258, height: 258)
                } else {
                    VStack(spacing: 13) {
                        Image(systemName: model.connectionProfile == nil ? "qrcode" : "lock")
                            .font(.system(size: 58, weight: .ultraLight))
                            .foregroundStyle(.secondary)
                        Text(model.connectionProfile == nil ? "先保存连接配置" : "配对码已隐藏")
                            .font(.system(size: 14, weight: .semibold))
                        Text(model.connectionProfile == nil ? "需要 Relay 地址和 App Token" : "显示时，附近的人可能扫描")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Button(model.isPairingCodeRevealed ? "隐藏配对码" : "显示配对码") {
                model.isPairingCodeRevealed.toggle()
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.connectionProfile == nil)

            if let profile = model.connectionProfile {
                VStack(spacing: 5) {
                    Text(profile.name)
                        .font(.system(size: 13, weight: .semibold))
                    Text(profile.gatewayURL.host ?? profile.gatewayURL.absoluteString)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
            }

            Text("二维码包含长期 App Token，请只在可信环境中显示。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .frame(maxWidth: .infinity, minHeight: 430, alignment: .top)
        .hermesCard()
    }

    private var configurationCard: some View {
        VStack(alignment: .leading, spacing: 15) {
            Text("连接配置")
                .font(.system(size: 17, weight: .bold))

            fieldLabel("配置名称（仅保存在此 Mac）")
            TextField("例如：客厅 Mac mini", text: $model.profileName)
                .textFieldStyle(.roundedBorder)

            fieldLabel("Relay 地址")
            TextField("https://mrlgs.net", text: $model.gatewayAddress)
                .textFieldStyle(.roundedBorder)

            fieldLabel("App Token")
            SecureField("输入手机使用的 App Token", text: $model.appToken)
                .textFieldStyle(.roundedBorder)

            if let issue = model.configurationIssue {
                VStack(alignment: .leading, spacing: 4) {
                    Text(issue.summaryChinese)
                        .font(.system(size: 12, weight: .semibold))
                    Text(issue.displayChinese)
                        .font(.system(size: 11))
                }
                .foregroundStyle(.red)
                .accessibilityElement(children: .combine)
            } else if let message = model.configurationMessage {
                Label(message, systemImage: "checkmark.circle.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(.green)
            }

            Button("保存到 Keychain 并测试") {
                Task { await model.saveConnectionProfile() }
            }
            .buttonStyle(PrimaryButtonStyle())

            Divider()

            Text("手机手动配置")
                .font(.system(size: 14, weight: .semibold))
            settingRow("名称", value: model.profileName.isEmpty ? "自定义" : model.profileName)
            settingRow("地址", value: model.gatewayAddress.isEmpty ? "—" : model.gatewayAddress)
            settingRow("Token", value: model.appToken.isEmpty ? "未填写" : "••••••••••••")
            settingRow("连接模式", value: "旧版 App Token")
        }
        .padding(22)
        .frame(maxWidth: .infinity, minHeight: 430, alignment: .topLeading)
        .hermesCard()
    }

    private func fieldLabel(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.secondary)
    }

    private func settingRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(value)
        }
        .font(.system(size: 13))
        .frame(height: 40)
    }
}

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

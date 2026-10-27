import HermesGoDesktopCore
import SwiftUI

struct AccountDevicesView: View {
    var accountOnly = false
    @EnvironmentObject private var model: DesktopViewModel
    @State private var loginDeviceToRevoke: ManagedAccountInstallation?
    @State private var loginDeviceVerificationCode = ""
    @State private var loginDeviceVerificationChallenge: DesktopEmailVerificationChallenge?
    @State private var accountEmail = ""
    @State private var accountCode = ""
    @State private var accountEmailChallenge: DesktopEmailVerificationChallenge?
    @State private var shareDevice: AccountDevice?
    @State private var shareEmail = ""
    @State private var shareAcknowledged = false
    @State private var shareVerificationCode = ""
    @State private var shareVerificationChallenge: DesktopEmailVerificationChallenge?
    @State private var showsAcceptInvitation = false
    @State private var invitationInput = ""
    @State private var invitationAcknowledged = false
    @State private var grantToRevoke: DeviceAccessGrant?
    @State private var sharedDeviceToLeave: AccountDevice?
    @State private var macToRemove: AccountDevice?
    @State private var isAccountDeletionPresented = false
    @State private var accountDeletionConfirmation = ""
    @State private var accountDeletionAcknowledged = false
    @State private var accountDeletionCode = ""
    @State private var accountDeletionChallenge: DesktopEmailVerificationChallenge?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    title: accountOnly ? "我的账号" : "我的设备",
                    subtitle: accountOnly ? "管理当前账号与登录设备" : "管理自己的 Mac 和他人共享的 Mac。自己的手机使用同一账号登录即可。"
                ) {
                    if !accountOnly, model.currentDashboard?.supportsDeviceSharing == true {
                        Button("接受邀请") { showsAcceptInvitation = true }
                            .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
                    }
                    Button {
                        Task {
                            await model.refreshAccount()
                        }
                    } label: {
                        Label("刷新", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                    .disabled(
                        model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked
                    )
                }

                accountContent

                if let issue = model.accountIssue {
                    accountIssueCard(issue)
                }

            }
            .padding(34)
        }
        .sheet(item: $loginDeviceToRevoke, onDismiss: {
            loginDeviceVerificationChallenge = nil
            loginDeviceVerificationCode = ""
        }) { phone in
            loginDeviceRevocationSheet(phone)
        }
        .sheet(isPresented: $showsAcceptInvitation) { acceptInvitationSheet }
        .sheet(item: $shareDevice) { device in
            shareInvitationSheet(device)
        }
        .sheet(item: $macToRemove) { device in
            RemoveMacSheet(device: device)
        }
        .sheet(isPresented: $isAccountDeletionPresented, onDismiss: resetAccountDeletion) {
            accountDeletionSheet
        }
        .confirmationDialog(
            "撤销整台设备的共享权限？",
            isPresented: Binding(
                get: { grantToRevoke != nil },
                set: { if !$0 { grantToRevoke = nil } }
            ),
            presenting: grantToRevoke
        ) { grant in
            Button("立即撤销 \(grant.granteeEmailHint)", role: .destructive) {
                Task {
                    await model.revokeDeviceShare(deviceID: grant.deviceId, grantID: grant.id)
                    grantToRevoke = nil
                }
            }
            Button("取消", role: .cancel) { grantToRevoke = nil }
        } message: { _ in
            Text("该账号正在使用的此设备会话将失效；不会删除 Mac 上的 Hermes 数据。")
        }
        .confirmationDialog(
            "退出这台共享 Mac？",
            isPresented: Binding(
                get: { sharedDeviceToLeave != nil },
                set: { if !$0 { sharedDeviceToLeave = nil } }
            ),
            presenting: sharedDeviceToLeave
        ) { device in
            Button("退出 \(device.desktopDisplayName)", role: .destructive) {
                Task {
                    await model.leaveSharedDevice(deviceID: device.deviceId)
                    sharedDeviceToLeave = nil
                }
            }
            Button("取消", role: .cancel) { sharedDeviceToLeave = nil }
        } message: { _ in
            Text("退出后当前账号将无法再访问这台 Hermes；设备所有者和 Mac 本身不受影响。")
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
                    : "暂时无法读取账号服务，请刷新重试。"
            )
        case .unavailable:
            statusCard(
                symbol: "lock.shield",
                title: "账号模式尚未开放",
                detail: "当前服务尚未开放账号连接，请检查服务配置后重试。"
            )
        case .signedOut, .needsSignIn:
            // Device management remains reachable; cloud actions require signing in here.
            SignInForm()
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
                .hermesCard()
        case .accountDeletionSubmitted:
            accountDeletionSubmittedCard
        case .signingIn:
            statusCard(
                symbol: "person.crop.circle.badge.clock",
                title: "正在验证账号",
                detail: "正在安全验证邮箱验证码，请稍候。"
            )
        case .signedIn(let dashboard):
            signedInContent(dashboard)
        }
    }

    private var accountDeletionSubmittedCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("云端账号删除已提交", systemImage: "checkmark.shield")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("云端访问已立即停止，个人数据将在 30 天期限后清理。Mac 上的 Hermes 数据仍保留在本机。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button("使用其他邮箱账号") {
                Task { await model.refreshAccount() }
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    @ViewBuilder
    private func signedInContent(_ dashboard: AccountDashboard) -> some View {
        if accountOnly {
            accountCard(dashboard)
            if let issue = dashboard.sectionIssues[.installations] { accountIssueCard(issue) }
            else {
                AccountLoginDevicesCard(dashboard: dashboard, isBusy: model.isAccountOperationInProgress) { installation in
                    loginDeviceVerificationChallenge = nil
                    loginDeviceVerificationCode = ""
                    loginDeviceToRevoke = installation
                }
            }
        } else {
            if let issue = dashboard.sectionIssues[.devices] {
                statusCard(symbol: "wifi.exclamationmark", title: "设备列表暂时无法加载", detail: "请刷新重试。账号和本机功能仍可使用。")
                accountIssueCard(issue)
            } else { devicesCard(dashboard) }
        }
    }

    private func devicesCard(_ dashboard: AccountDashboard) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("我的 Mac · \(dashboard.ownedDevices.count) / \(dashboard.maxOwnedDevices) 台")
                    .font(.system(size: 17, weight: .bold))
                Spacer()
                if dashboard.localDevice == nil {
                    Button("连接这台 Mac") { Task { await model.chooseNewMacUse(.connect) } }
                        .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
                }
            }
            if dashboard.ownedDevices.isEmpty {
                Text("还没有自己的 Mac。可以连接本机，或通过右上角接受他人的邀请。")
                    .font(.system(size: 13)).foregroundStyle(.secondary)
            }
            ForEach(dashboard.ownedDevices) { device in
                deviceRow(device, dashboard: dashboard)
                if dashboard.supportsDeviceSharing {
                    DisclosureGroup("邀请与访问权限") { deviceSharingDetails(device, dashboard: dashboard) }
                        .font(.system(size: 12)).padding(.leading, 37)
                }
                Divider()
            }
            if !dashboard.sharedDevices.isEmpty {
                Text("他人共享 · \(dashboard.sharedDevices.count) 台").font(.system(size: 15, weight: .semibold))
                ForEach(dashboard.sharedDevices) { device in
                    deviceRow(device, dashboard: dashboard)
                    Divider()
                }
            }
            Text("切换仅影响接下来打开的新内容；已有会话仍使用原来的 Mac。“账号默认”会同步给同账号的新会话。")
                .font(.system(size: 11)).foregroundStyle(.secondary)
        }.padding(20).hermesCard()
    }

    private func deviceRow(_ device: AccountDevice, dashboard: AccountDashboard) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: device.deviceId == dashboard.localDeviceID ? "desktopcomputer.and.macbook" : "desktopcomputer")
                    .foregroundStyle(Color.hermesBlue).frame(width: 25)
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(device.desktopDisplayName).font(.system(size: 14, weight: .semibold))
                        if device.deviceId == dashboard.localDeviceID { deviceBadge("本机") }
                        if device.isDefault { deviceBadge("账号默认") }
                    }
                    Text(deviceStatus(device)).font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Spacer()
                if dashboard.selectedDeviceID == device.deviceId {
                    Label("当前使用", systemImage: "checkmark.circle.fill").font(.system(size: 12)).foregroundStyle(Color.hermesBlue)
                }
            }
            HStack(spacing: 12) {
                if dashboard.selectedDeviceID != device.deviceId {
                    Button("切换使用") { Task { await model.selectDevice(device.deviceId) } }
                }
                if device.access == "owner", dashboard.supportsDeviceSharing {
                    Button("邀请他人") {
                        shareEmail = ""; shareAcknowledged = false
                        shareVerificationCode = ""; shareVerificationChallenge = nil; shareDevice = device
                    }
                }
                if !device.isDefault || device.access == "operator" || (device.deviceId != dashboard.localDeviceID && dashboard.maxOwnedDevices > 1) {
                Menu("更多") {
                    if !device.isDefault { Button("设为默认") { Task { await model.selectDefaultDevice(device.deviceId) } } }
                    if device.access == "operator" {
                        Button("退出共享", role: .destructive) { sharedDeviceToLeave = device }
                    } else if device.deviceId != dashboard.localDeviceID, dashboard.maxOwnedDevices > 1 {
                        Button("移除设备", role: .destructive) { macToRemove = device }
                    }
                }.menuStyle(.borderlessButton).fixedSize()
                }
                Spacer()
            }.buttonStyle(.bordered).padding(.leading, 37)
                .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
        }.padding(.vertical, 8)
    }

    @ViewBuilder private func deviceSharingDetails(_ device: AccountDevice, dashboard: AccountDashboard) -> some View {
        if let issue = dashboard.shareIssues[device.deviceId] {
            Text("访问权限暂时无法加载，请刷新重试。").foregroundStyle(.secondary)
            GateIssueBanner(issue: issue)
        } else if let sharing = dashboard.deviceShares[device.deviceId] {
            VStack(alignment: .leading, spacing: 10) {
                Text("已邀请 \(sharing.grants.count) 个账号 · 最多 \(sharing.maxGranteesPerDevice) 个")
                    .foregroundStyle(.secondary)
                ForEach(sharing.invitations) { invitation in
                    HStack {
                        Label("等待 \(invitation.targetEmailHint) 接受", systemImage: "envelope")
                        Spacer()
                        Button("取消邀请", role: .destructive) {
                            Task { await model.cancelShareInvitation(deviceID: device.deviceId, invitationID: invitation.id) }
                        }
                    }
                }
                ForEach(sharing.grants) { grant in
                    HStack {
                        Text(grant.granteeEmailHint)
                        Spacer()
                        Button("撤销访问", role: .destructive) { grantToRevoke = grant }
                    }
                }
                if sharing.invitations.isEmpty && sharing.grants.isEmpty { Text("尚未邀请其他账号。") }
            }.padding(.top, 8).disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
        } else { Text("权限信息待刷新。").foregroundStyle(.secondary) }
    }

    private var acceptInvitationSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("接受设备邀请").font(.system(size: 20, weight: .bold))
            Text("接受后，这台 Mac 会出现在“我的设备”的“他人共享”中，无需在本机安装 Hermes。")
                .foregroundStyle(.secondary)
            TextField("粘贴邮件中的完整邀请链接或邀请码", text: $invitationInput).textFieldStyle(.roundedBorder)
            Toggle("我理解此账号将能访问该 Hermes 暴露的会话、文件和模型配置", isOn: $invitationAcknowledged)
                .font(.system(size: 12))
            if let issue = model.accountIssue { GateIssueBanner(issue: issue) }
            HStack {
                Button("取消") { showsAcceptInvitation = false }.disabled(model.isAccountOperationInProgress)
                Spacer()
                Button("接受邀请") {
                    Task {
                        if await model.acceptShareInvitation(invitationInput, acknowledged: invitationAcknowledged) {
                            invitationInput = ""; invitationAcknowledged = false; showsAcceptInvitation = false
                            await model.chooseNewMacUse(.manageOnly)
                        }
                    }
                }.buttonStyle(.borderedProminent)
                    .disabled(invitationInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !invitationAcknowledged || model.isAccountOperationInProgress)
            }
        }.padding(26).frame(width: 540).interactiveDismissDisabled(model.isAccountOperationInProgress)
    }

    private func shareInvitationSheet(_ device: AccountDevice) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("共享 \(device.desktopDisplayName)")
                .font(.system(size: 20, weight: .bold))
            Text("输入对方账号的已验证邮箱。发送邀请前，我们会向你当前账号的邮箱发送六位验证码，以确认是你本人操作。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
            TextField("name@example.com", text: $shareEmail)
                .textFieldStyle(.roundedBorder)
                .disabled(shareVerificationChallenge != nil)
            VStack(alignment: .leading, spacing: 7) {
                Label("对方可能看到现有会话和消息", systemImage: "text.bubble")
                Label("对方可能访问 Hermes 暴露的文件", systemImage: "folder")
                Label("对方可能看到模型与服务配置元数据", systemImage: "gearshape.2")
            }
            .font(.system(size: 11))
            .foregroundStyle(.secondary)
            Toggle(
                "我确认这是整台 Hermes 设备的访问权限",
                isOn: $shareAcknowledged
            )
            .font(.system(size: 12, weight: .semibold))
            .disabled(shareVerificationChallenge != nil)
            if let verification = shareVerificationChallenge {
                VStack(alignment: .leading, spacing: 8) {
                    Text("身份验证码已发送至 \(verification.email)")
                        .font(.system(size: 12, weight: .semibold))
                    TextField("六位验证码", text: $shareVerificationCode)
                        .textFieldStyle(.roundedBorder)
                    Button("重新发送身份验证码") {
                        Task {
                            if let next = await model.requestShareInvitationVerification(
                                deviceID: device.deviceId
                            ) {
                                shareVerificationChallenge = next
                                shareVerificationCode = ""
                            }
                        }
                    }
                    .buttonStyle(.borderless)
                    .disabled(model.isAccountOperationInProgress)
                }
            }
            HStack {
                Button("取消") {
                    shareDevice = nil
                    shareVerificationChallenge = nil
                    shareVerificationCode = ""
                }
                    .buttonStyle(.bordered)
                Spacer()
                if let verification = shareVerificationChallenge {
                    Button("验证并发送邀请") {
                        let email = shareEmail
                        Task {
                            if await model.createShareInvitation(
                                deviceID: device.deviceId,
                                email: email,
                                acknowledged: shareAcknowledged,
                                verification: verification,
                                verificationCode: shareVerificationCode
                            ) {
                                shareDevice = nil
                                shareEmail = ""
                                shareAcknowledged = false
                                shareVerificationCode = ""
                                shareVerificationChallenge = nil
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        !isSixDigitCode(shareVerificationCode)
                            || model.isAccountOperationInProgress
                    )
                } else {
                    Button("发送身份验证码") {
                        Task {
                            shareVerificationChallenge = await model.requestShareInvitationVerification(
                                deviceID: device.deviceId
                            )
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        shareEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || !shareAcknowledged
                            || model.isAccountOperationInProgress
                    )
                }
            }
        }
        .padding(24)
        .frame(width: 470)
    }

    private func loginDeviceRevocationSheet(_ installation: ManagedAccountInstallation) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("撤销 \(installation.displayName) 的登录？")
                .font(.system(size: 20, weight: .bold))
            Text("为确认是你本人，我们会向当前账号邮箱发送六位验证码。验证成功后，只撤销这个手机 App 或浏览器的账号登录；其他登录设备和 Hermes 不受影响。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let verification = loginDeviceVerificationChallenge {
                Text("身份验证码已发送至 \(verification.email)")
                    .font(.system(size: 12, weight: .semibold))
                TextField("六位验证码", text: $loginDeviceVerificationCode)
                    .textFieldStyle(.roundedBorder)
                Button("重新发送身份验证码") {
                    Task {
                        if let next = await model.requestLoginDeviceRevocationVerification(id: installation.id) {
                            loginDeviceVerificationChallenge = next
                            loginDeviceVerificationCode = ""
                        }
                    }
                }
                .buttonStyle(.borderless)
                .disabled(model.isAccountOperationInProgress)
            }
            HStack {
                Button("取消") {
                    loginDeviceToRevoke = nil
                    loginDeviceVerificationChallenge = nil
                    loginDeviceVerificationCode = ""
                }
                .buttonStyle(.bordered)
                Spacer()
                if let verification = loginDeviceVerificationChallenge {
                    Button("验证并撤销登录", role: .destructive) {
                        Task {
                            if await model.revokeLoginDevice(
                                installation.id,
                                verification: verification,
                                verificationCode: loginDeviceVerificationCode
                            ) {
                                loginDeviceToRevoke = nil
                                loginDeviceVerificationChallenge = nil
                                loginDeviceVerificationCode = ""
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!isSixDigitCode(loginDeviceVerificationCode) || model.isAccountOperationInProgress)
                } else {
                    Button("发送身份验证码") {
                        Task {
                            loginDeviceVerificationChallenge = await model.requestLoginDeviceRevocationVerification(
                                id: installation.id
                            )
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(model.isAccountOperationInProgress)
                }
            }
        }
        .padding(24)
        .frame(width: 470)
    }

    private func deviceBadge(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 9, weight: .semibold))
            .foregroundStyle(Color.hermesBlue)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.hermesBlue.opacity(0.09))
            .clipShape(Capsule())
    }

    private func deviceStatus(_ device: AccountDevice) -> String {
        AccountDeviceHealth.deviceSummary(device, checkedAt: model.accountCheckedAt)
    }

    private func accountCard(_ dashboard: AccountDashboard) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Hermes GO 账号", systemImage: "person.crop.circle.fill")
                .font(.system(size: 16, weight: .bold))
            Text(dashboard.session.account.displayName ?? dashboard.session.account.email ?? "Hermes GO 账号")
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
            .disabled(model.isAccountOperationInProgress || model.isManagedBootstrapAccountLocked)
            Text("退出账号管理后，已设置的 Desktop 后台连接继续运行。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
            if dashboard.accountDeletionEnabled {
                Divider()
                Text("危险操作")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.red)
                Text("永久删除会立即撤销所有登录、Desktop 绑定与共享权限，并在 30 天后清理云端个人信息；不会删除 Mac 上的本地 Hermes 数据。")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button("永久删除云端账号", role: .destructive) {
                    isAccountDeletionPresented = true
                }
                .buttonStyle(.bordered)
                .disabled(model.isAccountOperationInProgress)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var accountDeletionSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("永久删除云端账号", systemImage: "exclamationmark.triangle.fill")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.red)
            Text("提交后将立即退出所有设备、撤销 Desktop 连接与共享权限。30 天后删除云端身份和账号资料；Mac 上的 Hermes 数据仍保留在本机。原账号无法恢复。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            TextField("输入 DELETE", text: $accountDeletionConfirmation)
                .textFieldStyle(.roundedBorder)
            Toggle("我理解这是永久操作，无法撤销", isOn: $accountDeletionAcknowledged)
                .toggleStyle(.checkbox)
            if let challenge = accountDeletionChallenge {
                Text("最终验证码已发送至 \(challenge.email)")
                    .font(.system(size: 12, weight: .semibold))
                TextField("六位验证码", text: $accountDeletionCode)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Button("取消", role: .cancel) { isAccountDeletionPresented = false }
                        .buttonStyle(.bordered)
                    Button("永久删除", role: .destructive) {
                        Task {
                            if await model.deleteAccount(
                                verification: challenge,
                                verificationCode: accountDeletionCode,
                                acknowledgedPermanentCloudDeletion: accountDeletionAcknowledged
                            ) {
                                isAccountDeletionPresented = false
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        accountDeletionConfirmation != "DELETE"
                            || !accountDeletionAcknowledged
                            || !isSixDigitCode(accountDeletionCode)
                            || model.isAccountOperationInProgress
                    )
                }
            } else {
                HStack {
                    Button("取消", role: .cancel) { isAccountDeletionPresented = false }
                        .buttonStyle(.bordered)
                    Button("发送最终验证码", role: .destructive) {
                        Task {
                            accountDeletionChallenge = await model.requestAccountDeletionVerification()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        accountDeletionConfirmation != "DELETE"
                            || !accountDeletionAcknowledged
                            || model.isAccountOperationInProgress
                    )
                }
            }
        }
        .padding(24)
        .frame(width: 480)
    }

    private func resetAccountDeletion() {
        accountDeletionConfirmation = ""
        accountDeletionAcknowledged = false
        accountDeletionCode = ""
        accountDeletionChallenge = nil
    }

    private func isSixDigitCode(_ value: String) -> Bool {
        value.count == 6 && value.allSatisfy { $0.isASCII && $0.isNumber }
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
                    subtitle: "管理 Desktop 偏好与更新"
                ) {
                    EmptyView()
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text("Desktop 更新")
                        .font(.system(size: 16, weight: .bold))
                    ViewThatFits(in: .horizontal) {
                        HStack {
                            Text("当前版本").foregroundStyle(.secondary)
                            Spacer()
                            versionAndStatus
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("当前版本").foregroundStyle(.secondary)
                            versionAndStatus
                        }
                    }.font(.system(size: 13)).padding(.vertical, 10)
                    Toggle(
                        "自动检查更新（每 12 小时）",
                        isOn: Binding(
                            get: { model.isAutomaticUpdateEnabled },
                            set: { model.setAutomaticUpdateChecksEnabled($0) }
                        )
                    )
                    .font(.system(size: 13))
                    .toggleStyle(.switch)
                    HStack(spacing: 12) {
                        Spacer()
                        Button("检查更新") {
                            Task { await model.checkForUpdates(manual: true) }
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                        .disabled(isCheckingUpdates || model.isDesktopUpdateRunning || model.isManagedBootstrapAccountLocked)
                    }
                    if let issue = model.appUpdateIssue {
                        GateIssueBanner(issue: issue)
                    }
                    if let message = model.appUpdateStatusMessage, message != model.updateStatusText, !isUpToDate {
                        Text(message)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .hermesCard()


            }
            .padding(34)
        }
    }

    private var versionAndStatus: some View {
        HStack(spacing: 10) {
            Text(model.appVersion).textSelection(.enabled)
            Label(updateBadge, systemImage: updateSymbol)
                .font(.system(size: 12)).foregroundStyle(.secondary)
        }
    }
    private var isUpToDate: Bool {
        if case .upToDate = model.updateCheckState { return true }
        return false
    }
    private var updateBadge: String {
        switch model.updateCheckState {
        case .idle: model.isUpdateConfigured ? "尚未检查" : "未配置更新"
        case .checking: "检查中"
        case .upToDate: "已是最新"
        case .available: "有新版本"
        case .failed: "检查失败"
        }
    }
    private var updateSymbol: String {
        switch model.updateCheckState {
        case .upToDate: "checkmark.circle"
        case .available: "arrow.down.circle"
        case .failed: "exclamationmark.circle"
        default: "arrow.clockwise"
        }
    }

    private var isCheckingUpdates: Bool {
        if case .checking = model.updateCheckState { return true }
        return false
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

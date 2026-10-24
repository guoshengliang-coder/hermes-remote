import HermesGoDesktopCore
import SwiftUI

struct AccountDevicesView: View {
    var accountOnly = false
    @EnvironmentObject private var model: DesktopViewModel
    @State private var phoneToRemove: ManagedAccountInstallation?
    @State private var phoneVerificationCode = ""
    @State private var phoneVerificationChallenge: DesktopEmailVerificationChallenge?
    @State private var accountEmail = ""
    @State private var accountCode = ""
    @State private var accountEmailChallenge: DesktopEmailVerificationChallenge?
    @State private var shareDevice: AccountDevice?
    @State private var shareEmail = ""
    @State private var shareAcknowledged = false
    @State private var shareVerificationCode = ""
    @State private var shareVerificationChallenge: DesktopEmailVerificationChallenge?
    @State private var invitationInput = ""
    @State private var invitationAcknowledged = false
    @State private var grantToRevoke: DeviceAccessGrant?
    @State private var sharedDeviceToLeave: AccountDevice?
    @State private var macToRemove: AccountDevice?
    @State private var isBundledHermesChoicePresented = false
    @State private var isAccountDeletionPresented = false
    @State private var accountDeletionConfirmation = ""
    @State private var accountDeletionAcknowledged = false
    @State private var accountDeletionCode = ""
    @State private var accountDeletionChallenge: DesktopEmailVerificationChallenge?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                PageHeader(
                    title: accountOnly ? "我的账号" : "设备",
                    subtitle: accountOnly ? "管理当前账号与登录" : "一个账号可管理多台 Mac；当前选择只影响接下来打开的新内容。"
                ) {
                    Button {
                        Task {
                            await model.refreshAccount()
                            await model.refreshComponentPreflight()
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
        .sheet(item: $phoneToRemove, onDismiss: {
            phoneVerificationChallenge = nil
            phoneVerificationCode = ""
        }) { phone in
            phoneRevocationSheet(phone)
        }
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
            // Unreachable behind the sign-in gate; kept so the page never renders empty.
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
        } else {
            bindingCard(dashboard.binding)
            if model.hermesInstallPhase != .hidden {
                HermesInstallCard()
            }
            if let presentation = model.componentPreflightPresentation {
                ComponentPreflightCard(
                    presentation: presentation,
                    canBegin: model.componentBootstrapCanBegin && !model.isHermesInstallDecisionPending,
                    isUpgrade: model.bootstrapPlan.readiness == .managedUpgradeAvailable,
                    operation: model.componentBootstrapOperation,
                    cleanupRetryAvailable: model.componentCleanupRetryAvailable,
                    prepare: { Task {
                        if model.bootstrapPlan.readiness == .managedUpgradeAvailable { await model.checkForUpdates(manual: true) }
                        else { await model.prepareComponentBootstrap() }
                    } },
                    retryCleanup: { Task { await model.retryComponentBootstrapCleanup() } }
                )
            } else if model.componentCleanupRetryAvailable,
                      model.componentBootstrapOperation == .failed {
                componentCleanupRecoveryCard
            }
            if let issue = model.componentBootstrapIssue {
                accountIssueCard(issue)
            }
            if model.serviceRepairNeeded {
                ManagedServiceRepairCard()
            } else {
                bootstrapPlanCard(model.bootstrapPlan)
            }
            if let issue = model.managedBootstrapIssue {
                accountIssueCard(issue)
            }
            if model.isFreshInstallBlockedByDesktopsOwnCheckout, model.hermesInstallPhase == .hidden {
                bundledHermesChoiceCard
            } else if model.isFreshInstallBlockedByOwnersHermes {
                ownersHermesGuidanceCard
            }
            if let issue = model.localHermesIssue {
                accountIssueCard(issue)
            }
            if !dashboard.devices.isEmpty {
                devicesCard(dashboard)
            }
            if dashboard.supportsDeviceSharing {
                sharingCard(dashboard)
            }
            phonesCard(dashboard.phones)
        }
    }

    private func bootstrapPlanCard(_ plan: DesktopBootstrapPlan) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack {
                Label("本机安装预检", systemImage: bootstrapSymbol(plan.readiness))
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Text(plan.canBegin ? "可开始" : "只读预检")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(plan.canBegin ? Color.hermesBlue : .secondary)
            }
            Text(plan.titleChinese)
                .font(.system(size: 15, weight: .semibold))
            Text(plan.detailChinese)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !plan.steps.isEmpty {
                Divider()
                ForEach(plan.steps) { step in
                    HStack(spacing: 9) {
                        Image(systemName: step.changesMachine ? "circle" : "checkmark.circle")
                            .foregroundStyle(step.changesMachine ? .secondary : Color.hermesBlue)
                            .frame(width: 16)
                        Text(step.titleChinese)
                            .font(.system(size: 11))
                        Spacer()
                        if step.changesMachine {
                            Text("执行前确认")
                                .font(.system(size: 10))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            managedBootstrapAction(plan)
        }
        .padding(20)
        .hermesCard()
    }

    @ViewBuilder
    private func managedBootstrapAction(_ plan: DesktopBootstrapPlan) -> some View {
        switch model.managedBootstrapOperation {
        case .preparing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在下载并验证签名安装包；尚未修改安装或服务")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .awaitingConfirmation:
            Divider()
            Label("安装包已验证，等待你的明确确认", systemImage: "checkmark.shield")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.hermesBlue)
        case .committing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在安装、启动并验证；请保持 Desktop 打开")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .recovering:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在恢复上次中断的操作，不会开始第二次安装")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .completed(_, let cleanupPending):
            Divider()
            HStack {
                Label("Desktop 已连接", systemImage: "checkmark.circle.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color.hermesBlue)
                Spacer()
                if cleanupPending {
                    Button("重试清理") {
                        Task { await model.retryManagedBootstrapCleanup() }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        case .idle, .failed:
            if plan.canBegin, !model.isComponentBootstrapPathSelected, model.isHermesInstallDecisionPending {
                Divider()
                Text("先在上方决定如何获取 Hermes：安装官方 Hermes，或改用内置 Hermes。")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            } else if plan.canBegin, !model.isComponentBootstrapPathSelected {
                Divider()
                HStack {
                    Text("第一步只写入私有临时缓存，不会停止或启动任何服务。")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(plan.readiness == .managedUpgradeAvailable
                        ? "查看 Desktop 更新"
                        : "下载并验证安装包") {
                        Task {
                            if plan.readiness == .managedUpgradeAvailable { await model.checkForUpdates(manual: true) }
                            else { await model.prepareManagedBootstrap() }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(model.isManagedBootstrapAccountLocked)
                }
            }
        }
    }

    /// Shown with `HR-MIGRATE-008` on a fresh setup only when the blocking checkout is the one
    /// Hermes GO's own install left behind — never for a Hermes the owner installed.
    private var bundledHermesChoiceCard: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("这份 Hermes 是 Hermes GO 之前的安装留下的")
                    .font(.system(size: 13, weight: .semibold))
                Text("它没有安装完整，Hermes GO 无法直接使用。可以改用 Hermes GO 内置的 Hermes 完成设置；留下的文件保持不动，删除 ~/.hermes/hermes-agent 后可重新安装官方 Hermes。")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Button("改用内置 Hermes") { isBundledHermesChoicePresented = true }
                .buttonStyle(.bordered)
        }
        .padding(18)
        .hermesCard()
        .confirmationDialog(
            "这台 Mac 改用 Hermes GO 内置的 Hermes？",
            isPresented: $isBundledHermesChoicePresented
        ) {
            Button("改用内置 Hermes") { model.useBundledHermes() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("之后的设置会安装并运行 Hermes GO 自带的 Hermes。Hermes GO 之前留下的文件不会被修改。")
        }
    }

    /// Shown with `HR-MIGRATE-008` when the owner's own Hermes blocks a fresh setup. No alternative
    /// is offered: the owner's rule is never a second copy next to a Hermes they installed.
    private var ownersHermesGuidanceCard: some View {
        Label(
            "这台 Mac 上的 Hermes 是你自己安装的，Hermes GO 不会再另装一份。请按“复制诊断”里的原因整理它（例如改为默认的 ~/.hermes、单一 profile、升级到 \(DesktopLocalHermesDetector.minimumVersion) 或更新），或将其移除后刷新。",
            systemImage: "info.circle"
        )
        .font(.system(size: 11))
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var componentCleanupRecoveryCard: some View {
        HStack(spacing: 14) {
            Label("Desktop 更新临时文件待清理", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                .font(.system(size: 13, weight: .semibold))
            Spacer()
            Button("重试清理") {
                Task { await model.retryComponentBootstrapCleanup() }
            }
            .buttonStyle(.bordered)
        }
        .padding(18)
        .hermesCard()
    }

    private func bootstrapSymbol(_ readiness: DesktopBootstrapReadiness) -> String {
        switch readiness {
        case .checking: "ellipsis.circle"
        case .existingServiceNeedsAttention: "exclamationmark.shield"
        case .waitingForSignedRelease: "signature"
        case .readyForManagedInstall: "shippingbox.and.arrow.backward"
        case .readyToAdoptLocalHermes: "desktopcomputer"
        case .managedUpgradeAvailable: "arrow.triangle.2.circlepath.circle"
        case .managedInstallActive: "checkmark.circle.fill"
        }
    }

    private func devicesCard(_ dashboard: AccountDashboard) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("可使用的 Mac")
                        .font(.system(size: 17, weight: .bold))
                    Text(dashboard.supportsDeviceSharing
                        ? "自有 \(dashboard.ownedDevices.count) / \(dashboard.maxOwnedDevices) 台 · 他人共享 \(dashboard.sharedDevices.count) / \(dashboard.maxSharedDevices) 台"
                        : "已连接 \(dashboard.ownedDevices.count) / \(dashboard.maxOwnedDevices) 台")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let selected = dashboard.selectedDevice {
                    Text("当前使用：\(selected.desktopDisplayName)")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.hermesBlue)
                }
            }
            .padding(.bottom, 12)

            ForEach(Array(dashboard.devices.enumerated()), id: \.element.id) { index, device in
                if index > 0 { Divider() }
                HStack(spacing: 12) {
                    Image(systemName: device.deviceId == dashboard.localDeviceID
                        ? "desktopcomputer.and.macbook"
                        : "desktopcomputer")
                        .foregroundStyle(Color.hermesBlue)
                        .frame(width: 25)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 7) {
                            Text(device.desktopDisplayName)
                                .font(.system(size: 13, weight: .semibold))
                            if device.deviceId == dashboard.localDeviceID {
                                deviceBadge("本机")
                            }
                            if device.access == "operator" {
                                deviceBadge("他人共享")
                            }
                            if device.isDefault {
                                deviceBadge("账号默认")
                            }
                        }
                        Text(deviceStatus(device))
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if dashboard.selectedDeviceID == device.deviceId {
                        Label("正在使用", systemImage: "checkmark.circle.fill")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Color.hermesBlue)
                    } else {
                        Button("切换") {
                            Task { await model.selectDevice(device.deviceId) }
                        }
                        .buttonStyle(.bordered)
                        .disabled(model.isAccountOperationInProgress)
                    }
                    if !device.isDefault {
                        Button("设为默认") {
                            Task { await model.selectDefaultDevice(device.deviceId) }
                        }
                        .buttonStyle(.borderless)
                        .disabled(model.isAccountOperationInProgress)
                    }
                    if device.access == "owner", dashboard.supportsDeviceSharing {
                        Button("共享") {
                            shareEmail = ""
                            shareAcknowledged = false
                            shareVerificationCode = ""
                            shareVerificationChallenge = nil
                            shareDevice = device
                        }
                        .buttonStyle(.borderless)
                        .disabled(model.isAccountOperationInProgress)
                    } else if device.access == "operator" {
                        Button("退出共享", role: .destructive) { sharedDeviceToLeave = device }
                            .buttonStyle(.borderless)
                            .disabled(model.isAccountOperationInProgress)
                    }
                    if device.access == "owner",
                       device.deviceId != dashboard.localDeviceID,
                       dashboard.maxOwnedDevices > 1 {
                        Button("移除", role: .destructive) { macToRemove = device }
                            .buttonStyle(.borderless)
                            .disabled(model.isAccountOperationInProgress)
                    }
                }
                .frame(minHeight: 62)
            }

            Text("切换只保存在这台 Desktop；“账号默认”会同步给同账号的其他新会话。已有会话仍固定使用创建时的 Mac。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .padding(.top, 12)
        }
        .padding(20)
        .hermesCard()
    }

    private func sharingCard(_ dashboard: AccountDashboard) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Label("整台设备共享", systemImage: "person.2.badge.gearshape")
                    .font(.system(size: 17, weight: .bold))
                Spacer()
                Text("每台最多 5 个账号")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }

            Label(
                "被邀请者可访问这台 Hermes 暴露的现有会话、文件和模型配置。当前不是项目或文件夹级权限。",
                systemImage: "exclamationmark.shield"
            )
            .font(.system(size: 12))
            .foregroundStyle(.orange)
            .fixedSize(horizontal: false, vertical: true)

            ForEach(dashboard.ownedDevices) { device in
                let sharing = dashboard.deviceShares[device.deviceId]
                VStack(alignment: .leading, spacing: 9) {
                    HStack {
                        Text(device.desktopDisplayName)
                            .font(.system(size: 13, weight: .semibold))
                        Spacer()
                        Button("邀请账号") {
                            shareEmail = ""
                            shareAcknowledged = false
                            shareVerificationCode = ""
                            shareVerificationChallenge = nil
                            shareDevice = device
                        }
                        .buttonStyle(.bordered)
                        .disabled(model.isAccountOperationInProgress)
                    }
                    if let sharing {
                        ForEach(sharing.invitations) { invitation in
                            HStack {
                                Label("等待 \(invitation.targetEmailHint) 接受", systemImage: "envelope.badge")
                                    .font(.system(size: 11))
                                Spacer()
                                Button("取消邀请", role: .destructive) {
                                    Task {
                                        await model.cancelShareInvitation(
                                            deviceID: device.deviceId,
                                            invitationID: invitation.id
                                        )
                                    }
                                }
                                .buttonStyle(.borderless)
                                .disabled(model.isAccountOperationInProgress)
                            }
                        }
                        ForEach(sharing.grants) { grant in
                            HStack {
                                Label("已共享给 \(grant.granteeEmailHint)", systemImage: "person.crop.circle.badge.checkmark")
                                    .font(.system(size: 11))
                                Spacer()
                                Button("撤销", role: .destructive) { grantToRevoke = grant }
                                    .buttonStyle(.borderless)
                                    .disabled(model.isAccountOperationInProgress)
                            }
                        }
                        if sharing.invitations.isEmpty && sharing.grants.isEmpty {
                            Text("尚未共享给其他账号。")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(12)
                .background(Color.primary.opacity(0.035))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }

            Divider()
            Text("接受别人发来的邀请")
                .font(.system(size: 13, weight: .semibold))
            TextField("粘贴邮件中的完整邀请链接或 hsi_ 邀请码", text: $invitationInput)
                .textFieldStyle(.roundedBorder)
            Toggle(
                "我理解此账号将能访问该 Hermes 暴露的整台设备内容",
                isOn: $invitationAcknowledged
            )
            .font(.system(size: 11))
            Button("接受邀请") {
                let input = invitationInput
                Task {
                    if await model.acceptShareInvitation(input, acknowledged: invitationAcknowledged) {
                        invitationInput = ""
                        invitationAcknowledged = false
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(
                invitationInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || !invitationAcknowledged
                    || model.isAccountOperationInProgress
            )
        }
        .padding(20)
        .hermesCard()
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

    private func phoneRevocationSheet(_ phone: ManagedAccountInstallation) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("移除 \(phone.displayName)？")
                .font(.system(size: 20, weight: .bold))
            Text("为确认是你本人，我们会向当前账号邮箱发送六位验证码。验证成功后，只撤销这台手机的账号登录；其他手机、Desktop 和 Hermes 不受影响。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if let verification = phoneVerificationChallenge {
                Text("身份验证码已发送至 \(verification.email)")
                    .font(.system(size: 12, weight: .semibold))
                TextField("六位验证码", text: $phoneVerificationCode)
                    .textFieldStyle(.roundedBorder)
                Button("重新发送身份验证码") {
                    Task {
                        if let next = await model.requestPhoneRevocationVerification(id: phone.id) {
                            phoneVerificationChallenge = next
                            phoneVerificationCode = ""
                        }
                    }
                }
                .buttonStyle(.borderless)
                .disabled(model.isAccountOperationInProgress)
            }
            HStack {
                Button("取消") {
                    phoneToRemove = nil
                    phoneVerificationChallenge = nil
                    phoneVerificationCode = ""
                }
                .buttonStyle(.bordered)
                Spacer()
                if let verification = phoneVerificationChallenge {
                    Button("验证并移除", role: .destructive) {
                        Task {
                            if await model.revokePhone(
                                phone.id,
                                verification: verification,
                                verificationCode: phoneVerificationCode
                            ) {
                                phoneToRemove = nil
                                phoneVerificationChallenge = nil
                                phoneVerificationCode = ""
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!isSixDigitCode(phoneVerificationCode) || model.isAccountOperationInProgress)
                } else {
                    Button("发送身份验证码") {
                        Task {
                            phoneVerificationChallenge = await model.requestPhoneRevocationVerification(
                                id: phone.id
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
        let connector = device.connector.online ? "Desktop 在线" : "Desktop 离线"
        let hermes = switch device.hermes.reachable {
        case .some(true): "Hermes 可访问"
        case .some(false): "Hermes 不可访问"
        case .none: "Hermes 未确认"
        }
        return "\(connector) · \(hermes)"
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
                settingRow("Desktop 连接", value: active.connector.online ? "在线" : "离线")
                settingRow("Hermes", value: active.hermes.reachable == true ? "可访问" : "未确认")
            }
            if binding.state == "no_binding" {
                Label("先检查这台 Mac 的连接条件；需要修改安装时会显示影响并请你确认。", systemImage: "info.circle")
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
                        Button("移除", role: .destructive) {
                            phoneVerificationChallenge = nil
                            phoneVerificationCode = ""
                            phoneToRemove = phone
                        }
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
                online ? "连接正常" : "Desktop 当前离线",
                online ? "账号已绑定这台 Desktop，手机可以共享访问同一个 Hermes。" : "账号绑定仍保留；请在对应 Mac 上打开 Desktop，检查连接状态。"
            )
        case "binding_pending":
            return ("clock", "正在验证 Desktop 连接", "候选绑定尚未完成密钥与健康检查，现有连接不会被替换。")
        case "replacement_pending":
            return ("arrow.triangle.2.circlepath", "等待确认更换 Mac", "原来的 Desktop 在更换提交前仍保持工作。")
        case "revoked":
            return ("xmark.shield", "这台 Mac 的绑定已撤销", "若上次迁移已安全回滚，可重新下载并确认；其他撤销需要重新验证账号。")
        default:
            return ("desktopcomputer", "尚未建立账号绑定", "完成本机设置后，即可使用账号连接这台 Mac。")
        }
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
                    settingRow("当前版本", model.appVersion)
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
                        Text(model.updateStatusText)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
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
                    if let message = model.appUpdateStatusMessage {
                        Text(message)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .hermesCard()

                VStack(alignment: .leading, spacing: 10) {
                    Text("后台运行").font(.system(size: 16, weight: .bold)).foregroundStyle(.primary)
                    Text("完成连接设置后，Desktop 的后台运行能力会在登录这台 Mac 时自动启动。")
                    Text("关闭窗口：Desktop 和后台连接继续运行。退出 Desktop：关闭界面与自动检查，已设置的后台连接继续运行。")
                    Text("安装、更新或修复期间请保持 Desktop 打开。退出可能中断当前操作；重新打开后会检查恢复状态。Mac 睡眠、关机或退出系统账号时，远程访问和任务可能中断。")
                    Text("任务进度、结果和历史请在 Hermes 的任务页面查看；运行日志集中在“诊断”。")
                }
                .font(.system(size: 13)).foregroundStyle(.secondary)
                .padding(20).frame(maxWidth: .infinity, alignment: .leading).hermesCard()

                VStack(alignment: .leading, spacing: 10) {
                    Text("安全边界")
                        .font(.system(size: 16, weight: .bold))
                    Text("Desktop 通过账号建立安全连接，Hermes 凭据保留在这台 Mac。")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .hermesCard()
            }
            .padding(34)
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

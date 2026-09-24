import AppKit
import HermesGoDesktopCore
import SwiftUI

/// First-run onboarding after sign-in (`docs/DESKTOP_ONBOARDING_REQUIREMENTS.md` §6). The step is
/// derived from the real state every time; this view only adds the one in-memory acknowledgement
/// of step 2 when this Mac already has a Hermes to use.
struct OnboardingGateView: View {
    @EnvironmentObject private var model: DesktopViewModel
    let step: DesktopOnboardingStep
    @State private var hermesAcknowledged = false

    private var shownStep: DesktopOnboardingStep {
        step == .connectMac && !hermesAcknowledged && !model.isManagedBootstrapAccountLocked
            ? .prepareHermes
            : step
    }

    var body: some View {
        switch shownStep {
        case .signIn, .prepareHermes:
            PrepareHermesStep(onContinue: { hermesAcknowledged = true })
        case .connectMac:
            ConnectMacStep()
        case .connectPhone:
            ConnectPhoneStep()
        }
    }
}

// MARK: - Step 2

private struct PrepareHermesStep: View {
    @EnvironmentObject private var model: DesktopViewModel
    let onContinue: () -> Void
    @State private var source: DesktopViewModel.OnboardingHermesSource?

    var body: some View {
        GateShell(step: .prepareHermes) {
            VStack(alignment: .leading, spacing: 0) {
                if model.isHermesInstallDecisionPending {
                    Text(installTitle)
                        .font(.system(size: 26, weight: .bold))
                    Text("Hermes GO 需要这台 Mac 上有一个 Hermes。推荐安装官方 Hermes，它会装在 ~/.hermes，与你手动安装完全一样。")
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                    HermesInstallCard()
                        .padding(.top, 20)
                } else {
                    readyContent
                }
            }
            .frame(width: 560, alignment: .leading)
        } footer: {
            CopyDiagnosticsButton(issue: model.localHermesIssue)
            Spacer()
            if !model.isHermesInstallDecisionPending {
                Button("继续", action: onContinue)
                    .buttonStyle(PrimaryButtonStyle())
                    .keyboardShortcut(.defaultAction)
            }
        }
        .task(id: model.isHermesInstallDecisionPending) {
            if !model.isHermesInstallDecisionPending {
                source = await model.onboardingHermesSource()
            }
        }
    }

    private var installTitle: String {
        if case .running = model.hermesInstallPhase { return "正在安装 Hermes" }
        return "这台 Mac 还没有 Hermes"
    }

    @ViewBuilder
    private var readyContent: some View {
        switch source {
        case .local(let installation):
            Text("已找到这台 Mac 上的 Hermes")
                .font(.system(size: 26, weight: .bold))
            Text("Hermes GO 会直接使用它和你现有的对话，不会再安装第二份。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .padding(.top, 6)
            facts([
                ("folder", "位置", abbreviated(installation.hermesHome)),
                ("tag", "版本", installation.version),
                ("clock.arrow.circlepath", "更新", "Hermes 的版本由你自己决定，Hermes GO 不会替你升级"),
            ])
        case .bundled:
            Text("将使用 Hermes GO 内置的 Hermes")
                .font(.system(size: 26, weight: .bold))
            Text("你之前选择了内置 Hermes。下一步会把它和后台连接一起安装。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .padding(.top, 6)
        case .unknown:
            Text("准备连接这台 Mac")
                .font(.system(size: 26, weight: .bold))
            Text("下一步会检查这台 Mac 上的 Hermes，并在需要时说明原因。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .padding(.top, 6)
        case nil:
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在检查这台 Mac 上的 Hermes…").foregroundStyle(.secondary)
            }
        }
    }

    private func facts(_ rows: [(String, String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider() }
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: row.0)
                        .foregroundStyle(.secondary)
                        .frame(width: 18)
                    Text(row.1)
                        .foregroundStyle(.secondary)
                        .frame(width: 44, alignment: .leading)
                    Text(row.2)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .font(.system(size: 13))
                .padding(.vertical, 10)
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
        .hermesCard()
        .padding(.top, 22)
    }

    private func abbreviated(_ url: URL) -> String {
        (url.path as NSString).abbreviatingWithTildeInPath
    }
}

// MARK: - Step 3

private struct ConnectMacStep: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var isBundledChoicePresented = false

    private enum RowState { case pending, running, done }

    var body: some View {
        GateShell(step: .connectMac) {
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(.system(size: 26, weight: .bold))
                Text(subtitle)
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
                progressCard
                    .padding(.top, 22)
                ForEach(issues, id: \.code) { issue in
                    GateIssueBanner(issue: issue)
                        .padding(.top, 12)
                }
                guidance
                Label("关闭窗口不会中断；如果退出 Hermes GO，下次打开会从这里继续。", systemImage: "info.circle")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .padding(.top, 16)
            }
            .frame(width: 540, alignment: .leading)
        } footer: {
            CopyDiagnosticsButton(issue: issues.first)
            Spacer()
            footerAction
        }
        .confirmationDialog(
            "这台 Mac 改用 Hermes GO 内置的 Hermes？",
            isPresented: $isBundledChoicePresented
        ) {
            Button("改用内置 Hermes") { model.useBundledHermes() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("之后的设置会安装并运行 Hermes GO 自带的 Hermes。Hermes GO 之前留下的文件不会被修改。")
        }
    }

    private var phase: (download: RowState, install: RowState, verify: RowState) {
        let operation: String = {
            if model.isComponentBootstrapPathSelected {
                switch model.componentBootstrapOperation {
                case .preparing: return "preparing"
                case .awaitingConfirmation: return "confirm"
                case .committing: return "committing"
                case .completed: return "done"
                case .idle, .failed: return "idle"
                }
            }
            switch model.managedBootstrapOperation {
            case .preparing: return "preparing"
            case .awaitingConfirmation: return "confirm"
            case .committing, .recovering: return "committing"
            case .completed: return "done"
            case .idle, .failed: return "idle"
            }
        }()
        return switch operation {
        case "preparing": (.running, .pending, .pending)
        case "confirm": (.done, .pending, .pending)
        case "committing": (.done, .running, .pending)
        case "done": (.done, .done, .done)
        default: (.pending, .pending, .pending)
        }
    }

    private var isWaitingForRelease: Bool {
        model.bootstrapPlan.readiness == .waitingForSignedRelease && !model.isManagedBootstrapAccountLocked
    }

    private var canBegin: Bool {
        (model.isComponentBootstrapPathSelected ? model.componentBootstrapCanBegin : model.bootstrapPlan.canBegin)
            && !model.isHermesInstallDecisionPending
            && !model.isManagedBootstrapAccountLocked
    }

    private var title: String {
        if isWaitingForRelease { return "暂时无法连接这台 Mac" }
        return phase.download == .pending ? "连接这台 Mac" : "正在连接这台 Mac"
    }

    private var subtitle: String {
        if isWaitingForRelease { return model.bootstrapPlan.detailChinese }
        return "完成后，这台 Mac 会在后台保持连接，手机随时可以访问。会改动这台 Mac 的操作，开始前都会先请你确认。"
    }

    private var progressCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            row("下载并验证安装包", state: phase.download, note: phase.download == .done ? "签名已校验" : nil)
            row("安装后台服务、绑定账号并设为开机启动", state: phase.install, note: nil)
            row("验证可以从手机访问", state: phase.verify, note: nil)
            if phase.download == .done, phase.install == .pending {
                Label("安装包已验证，请在弹出的窗口中确认", systemImage: "checkmark.shield")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.hermesBlue)
                    .padding(.top, 10)
            }
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private func row(_ title: String, state: RowState, note: String?) -> some View {
        HStack(spacing: 12) {
            Group {
                switch state {
                case .done:
                    Image(systemName: "checkmark").foregroundStyle(Color.hermesBlue)
                case .running:
                    ProgressView().controlSize(.small)
                case .pending:
                    Circle().stroke(Color.secondary.opacity(0.35), lineWidth: 1.5).frame(width: 15, height: 15)
                }
            }
            .frame(width: 20, height: 20)
            Text(title)
                .foregroundStyle(state == .pending ? .secondary : .primary)
            Spacer()
            if let note {
                Text(note).font(.system(size: 12)).foregroundStyle(.secondary)
            }
        }
        .font(.system(size: 13.5))
        .padding(.vertical, 8)
    }

    private var issues: [DesktopIssue] {
        [model.componentBootstrapIssue, model.managedBootstrapIssue, model.localHermesIssue].compactMap { $0 }
    }

    @ViewBuilder
    private var guidance: some View {
        if model.isFreshInstallBlockedByDesktopsOwnCheckout, model.hermesInstallPhase == .hidden {
            HStack(alignment: .top, spacing: 12) {
                Text("这份 Hermes 是 Hermes GO 之前的安装留下的，没有安装完整。可以改用内置的 Hermes 完成设置；留下的文件保持不动。")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer()
                Button("改用内置 Hermes") { isBundledChoicePresented = true }
                    .buttonStyle(.bordered)
            }
            .padding(14)
            .hermesCard()
            .padding(.top, 12)
        } else if model.isFreshInstallBlockedByOwnersHermes {
            Label(
                "这台 Mac 上的 Hermes 是你自己安装的，Hermes GO 不会再另装一份。请按“复制诊断”里的原因整理它，或将其移除后重试。",
                systemImage: "info.circle"
            )
            .font(.system(size: 12))
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 12)
        }
    }

    @ViewBuilder
    private var footerAction: some View {
        if case .completed(_, cleanupPending: true) = model.managedBootstrapOperation {
            Button("重试清理") { Task { await model.retryManagedBootstrapCleanup() } }
                .buttonStyle(PrimaryButtonStyle())
        } else if model.componentCleanupRetryAvailable, model.componentBootstrapOperation == .failed {
            Button("重试清理") { Task { await model.retryComponentBootstrapCleanup() } }
                .buttonStyle(PrimaryButtonStyle())
        } else if model.isManagedBootstrapAccountLocked {
            Button("正在连接…") {}
                .buttonStyle(PrimaryButtonStyle())
                .disabled(true)
                .opacity(0.5)
        } else if isWaitingForRelease || !canBegin {
            Button("重试") {
                Task {
                    await model.refresh()
                    await model.refreshComponentPreflight()
                }
            }
            .buttonStyle(PrimaryButtonStyle())
        } else {
            Button("开始连接") { Task { await model.startSetup() } }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
        }
    }
}

// MARK: - Step 4

private struct ConnectPhoneStep: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        GateShell(step: .connectPhone) {
            PhoneConnectPanel()
                .frame(width: 800, alignment: .leading)
        } footer: {
            Button("稍后再说") { model.finishPhoneStep() }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            Spacer()
            Button("进入 Hermes GO") { model.finishPhoneStep() }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(model.newlyConnectedClients.isEmpty)
                .opacity(model.newlyConnectedClients.isEmpty ? 0.5 : 1)
                .keyboardShortcut(.defaultAction)
        }
    }
}

/// The phone instructions, shared by onboarding step 4 and the overview's "连上手机" sheet.
struct PhoneConnectPanel: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var platform: DesktopPhonePlatform = .android
    var showsHeader = true

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .bottom) {
                if showsHeader {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("最后一步：连上你的手机")
                            .font(.system(size: 26, weight: .bold))
                        Text("这台 Mac 已经准备好了。选择你的手机类型，按步骤用同一个邮箱登录即可。")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Picker("手机类型", selection: $platform) {
                    Label("Android", systemImage: "smartphone").tag(DesktopPhonePlatform.android)
                    Label("iPhone / iPad", systemImage: "iphone").tag(DesktopPhonePlatform.apple)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 260)
            }
            HStack(alignment: .top, spacing: 20) {
                qrCard
                instructions
            }
            .padding(.top, 22)
        }
        .task {
            // Sign-ins on the phone show up through the account refresh; poll faster while waiting.
            while !Task.isCancelled, model.newlyConnectedClients.isEmpty {
                try? await Task.sleep(for: .seconds(5))
                await model.refreshAccount()
            }
        }
    }

    private var targetURL: URL? { model.phoneTargetURL(platform) }

    private var qrCard: some View {
        VStack(spacing: 12) {
            if let targetURL {
                LinkQRCodeView(url: targetURL)
                    .frame(width: 188, height: 188)
                    .padding(8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
                Text(platform == .android ? "用手机相机扫码下载" : "用相机扫码，在 Safari 打开")
                    .font(.system(size: 14, weight: .semibold))
                HStack {
                    Text(targetURL.absoluteString)
                        .font(.system(size: 12.5))
                        .textSelection(.enabled)
                        .lineLimit(1)
                    Spacer()
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(targetURL.absoluteString, forType: .string)
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.hermesBlue)
                    .help("复制地址")
                }
                .padding(.horizontal, 10)
                .frame(height: 30)
                .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8))
                Text(platform == .android ? "下载 Android App" : "网页版 · 无需从 App Store 安装")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(22)
        .frame(width: 290)
        .hermesCard()
    }

    private var instructions: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(alignment: .top, spacing: 12) {
                    Text("\(index + 1)")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Color.hermesBlue)
                        .frame(width: 24, height: 24)
                        .background(Color.hermesBlue.opacity(0.1), in: Circle())
                    VStack(alignment: .leading, spacing: 3) {
                        Text(step.0).font(.system(size: 13.5, weight: .semibold))
                        if let detail = step.1 {
                            Text(detail).font(.system(size: 12)).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            if platform == .apple {
                Label("也可以直接在 Safari 标签页里用，但会显示浏览器的地址栏和工具栏。", systemImage: "info.circle")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            status
                .padding(.top, 6)
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hermesCard()
    }

    private var steps: [(String, String?)] {
        let email = model.currentDashboard?.session.account.email ?? "同一个邮箱"
        switch platform {
        case .android:
            return [
                ("扫码下载并安装 Hermes GO", "手机可能会提示「允许安装未知来源应用」"),
                ("用 \(email) 登录", "与这台 Mac 使用同一个邮箱"),
                ("选择「\(Host.current().localizedName ?? "这台 Mac")」", "之后就能在手机上继续这台 Mac 的对话"),
            ]
        case .apple:
            return [
                ("用相机扫码，在 Safari 中打开", nil),
                ("点「分享」", "iPhone 在屏幕底部，iPad 在地址栏右侧"),
                ("选「添加到主屏幕」，再点「添加」", nil),
                ("从主屏幕的 Hermes GO 图标打开", "用 \(email) 登录，选择「\(Host.current().localizedName ?? "这台 Mac")」"),
            ]
        }
    }

    @ViewBuilder
    private var status: some View {
        if let client = model.newlyConnectedClients.first {
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                Text("已连接：\(DesktopRemoteClients.displayName(client))")
            }
            .font(.system(size: 13, weight: .semibold))
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.green.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        } else {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text(platform == .android ? "正在等待手机登录…" : "正在等待 Web App 登录…")
            }
            .font(.system(size: 13))
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.hermesBlue.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
        }
    }
}

struct LinkQRCodeView: View {
    let url: URL

    var body: some View {
        if let image = rendered {
            Image(nsImage: image)
                .resizable()
                .interpolation(.none)
                .scaledToFit()
                .accessibilityLabel("指向 \(url.absoluteString) 的二维码")
        } else {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.orange)
                .accessibilityLabel("二维码生成失败")
        }
    }

    private var rendered: NSImage? {
        guard let ciImage = PairingQRCodeGenerator.image(for: Data(url.absoluteString.utf8)) else { return nil }
        let representation = NSCIImageRep(ciImage: ciImage)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}

// MARK: - Existing account, new Mac (§7)

struct NewMacChoiceGateView: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var choice: DesktopNewMacChoice?
    @State private var deviceToRemove: AccountDevice?

    var body: some View {
        let dashboard = model.currentDashboard
        let others = dashboard.map(DesktopEntryRouter.otherOwnedDevices) ?? []
        let limit = dashboard?.maxOwnedDevices ?? 1
        let full = others.count >= limit
        let effectiveChoice = choice ?? (full ? .manageOnly : .connect)
        GateShell(step: nil) {
            VStack(alignment: .leading, spacing: 0) {
                Text("你的账号已连接 \(others.count) 台 Mac")
                    .font(.system(size: 26, weight: .bold))
                Text("这台\(Host.current().localizedName.map { " \($0) " } ?? " Mac ")还没有连接。你想怎么使用它？")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .padding(.top, 6)
                if full {
                    GateIssueBanner(issue: DesktopIssue(code: .ownedDeviceLimitReached))
                        .padding(.top, 16)
                }
                VStack(spacing: 0) {
                    ForEach(Array(others.enumerated()), id: \.element.id) { index, device in
                        if index > 0 { Divider() }
                        HStack(spacing: 12) {
                            Image(systemName: "desktopcomputer")
                                .foregroundStyle(Color.hermesBlue)
                                .frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(device.desktopDisplayName)
                                HStack(spacing: 6) {
                                    StatusDot(level: device.connector.online ? .healthy : .unavailable, size: 7)
                                    Text((device.connector.online ? "在线" : "离线") + (device.isDefault ? " · 默认" : ""))
                                        .font(.system(size: 12))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            if full {
                                Button("移除") { deviceToRemove = device }
                                    .buttonStyle(.bordered)
                                    .foregroundStyle(.red)
                            }
                        }
                        .font(.system(size: 13.5))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                    }
                }
                .hermesCard()
                .padding(.top, 16)
                HStack(alignment: .top, spacing: 16) {
                    option(
                        .connect,
                        selected: effectiveChoice == .connect,
                        enabled: !full,
                        symbol: "plus.rectangle.on.rectangle",
                        title: "把这台 Mac 也连上",
                        detail: full
                            ? "需要先移除一台 Mac，名额空出后即可选择。"
                            : "在这台 Mac 上准备 Hermes 并连接账号。手机可以在几台 Mac 之间切换。",
                        foot: "已用 \(others.count) / \(limit) 台"
                    )
                    option(
                        .manageOnly,
                        selected: effectiveChoice == .manageOnly,
                        enabled: true,
                        symbol: "eye",
                        title: "只在这台 Mac 上管理",
                        detail: "不在本机安装任何东西，用这台 Mac 查看和管理已连接的 Mac。以后可以随时改为连接。",
                        foot: "本机不做任何改动"
                    )
                }
                .padding(.top, 16)
                if let issue = model.accountIssue {
                    GateIssueBanner(issue: issue).padding(.top, 12)
                }
            }
            .frame(width: 760, alignment: .leading)
        } footer: {
            CopyDiagnosticsButton(issue: model.accountIssue)
            Button("退出登录") { Task { await model.signOutAccount() } }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            Spacer()
            Button("继续") { Task { await model.chooseNewMacUse(effectiveChoice) } }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(effectiveChoice == .connect && full)
                .keyboardShortcut(.defaultAction)
        }
        .sheet(item: $deviceToRemove) { device in
            RemoveMacSheet(device: device)
        }
    }

    private func option(
        _ value: DesktopNewMacChoice,
        selected: Bool,
        enabled: Bool,
        symbol: String,
        title: String,
        detail: String,
        foot: String
    ) -> some View {
        Button {
            choice = value
        } label: {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    Image(systemName: symbol)
                        .font(.system(size: 19))
                        .foregroundStyle(Color.hermesBlue)
                        .frame(width: 40, height: 40)
                        .background(Color.hermesBlue.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
                    Spacer()
                    Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                        .font(.system(size: 17))
                        .foregroundStyle(selected ? Color.hermesBlue : .secondary)
                }
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .padding(.top, 14)
                Text(detail)
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 6)
                Spacer(minLength: 12)
                Text(foot)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .padding(20)
            .frame(maxWidth: .infinity, minHeight: 190, alignment: .topLeading)
            .contentShape(Rectangle())
            .hermesCard()
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(selected ? Color.hermesBlue : .clear, lineWidth: 1.5)
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.55)
    }
}

/// Removing one of the account's own Macs (§7.3): states the consequences, then requires a fresh
/// six-digit code sent to the account email before the destructive action is enabled.
struct RemoveMacSheet: View {
    @EnvironmentObject private var model: DesktopViewModel
    @Environment(\.dismiss) private var dismiss
    let device: AccountDevice
    @State private var challenge: DesktopEmailVerificationChallenge?
    @State private var code = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("移除「\(device.desktopDisplayName)」？")
                .font(.system(size: 20, weight: .bold))
            Text("它会从你的 Hermes GO 账号中移除。")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 8) {
                consequence("xmark", .red, "\(device.desktopDisplayName) 会立即断开，手机将无法再访问它。")
                consequence("checkmark", .green, "那台 Mac 上的 Hermes 对话、文件和配置不会被删除。")
                consequence("arrow.counterclockwise", .secondary, "之后可以在那台 Mac 上重新连接。")
            }
            if let challenge {
                Text("验证码已发送至 \(challenge.email)")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .padding(.top, 6)
                TextField("六位验证码", text: $code)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 20, weight: .semibold, design: .monospaced))
                    .frame(maxWidth: 240)
                    .onChange(of: code) { _, value in
                        let digits = String(value.filter(\.isNumber).prefix(6))
                        if digits != value { code = digits }
                    }
            }
            if let issue = model.accountIssue {
                GateIssueBanner(issue: issue)
            }
            HStack {
                if challenge != nil {
                    Button("重新发送") { Task { await requestCode() } }
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.hermesBlue)
                        .disabled(model.isAccountOperationInProgress)
                }
                Spacer()
                Button("取消") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                if let challenge {
                    Button("验证并移除", role: .destructive) {
                        Task {
                            if await model.removeOwnedDevice(
                                device.deviceId,
                                verification: challenge,
                                verificationCode: code
                            ) {
                                dismiss()
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .disabled(code.count != 6 || model.isAccountOperationInProgress)
                } else {
                    Button("发送验证码") { Task { await requestCode() } }
                        .buttonStyle(.borderedProminent)
                        .disabled(model.isAccountOperationInProgress)
                }
            }
            .padding(.top, 6)
        }
        .padding(26)
        .frame(width: 540)
    }

    private func consequence(_ symbol: String, _ color: Color, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol)
                .foregroundStyle(color)
                .frame(width: 16)
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.system(size: 13))
    }

    private func requestCode() async {
        if let next = await model.requestDeviceRemovalVerification(deviceID: device.deviceId) {
            challenge = next
            code = ""
        }
    }
}

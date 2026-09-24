import HermesGoDesktopCore
import SwiftUI

/// Full-window pages shown instead of the sidebar while the account or this Mac is not ready
/// (`docs/DESKTOP_ONBOARDING_REQUIREMENTS.md` §5). None of them offers a way around sign-in.
struct GateShell<Content: View, Footer: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    var step: DesktopOnboardingStep?
    @ViewBuilder let content: Content
    @ViewBuilder let footer: Footer

    var body: some View {
        VStack(spacing: 0) {
            if let step {
                OnboardingStepper(current: step)
                    .padding(.top, 18)
                    .padding(.bottom, 8)
            }
            ScrollView {
                content
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 40)
                    .padding(.vertical, 28)
            }
            .defaultScrollAnchor(.center)
            Rectangle()
                .fill(Color.hermesHairline(colorScheme))
                .frame(height: 1)
            HStack(spacing: 18) { footer }
                .padding(.horizontal, 28)
                .frame(height: 64)
                .background(Color.hermesCard(colorScheme).opacity(0.7))
        }
        .background(Color.hermesCanvas(colorScheme))
    }
}

struct OnboardingStepper: View {
    let current: DesktopOnboardingStep

    var body: some View {
        HStack(spacing: 0) {
            ForEach(DesktopOnboardingStep.allCases, id: \.self) { step in
                if step != .signIn {
                    Rectangle()
                        .fill(step <= current ? Color.hermesBlue : Color.secondary.opacity(0.25))
                        .frame(width: 56, height: 1.5)
                        .padding(.horizontal, 12)
                }
                HStack(spacing: 8) {
                    ZStack {
                        if step < current {
                            Circle().fill(Color.hermesBlue)
                            Image(systemName: "checkmark")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.white)
                        } else {
                            Circle()
                                .stroke(step == current ? Color.hermesBlue : Color.secondary.opacity(0.35), lineWidth: 1.5)
                            Text("\(step.rawValue)")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(step == current ? Color.hermesBlue : .secondary)
                        }
                    }
                    .frame(width: 24, height: 24)
                    Text(step.titleChinese)
                        .font(.system(size: 13, weight: step == current ? .semibold : .regular))
                        .foregroundStyle(step == current ? .primary : .secondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("第 \(step.rawValue) 步，\(step.titleChinese)\(step < current ? "，已完成" : step == current ? "，当前" : "")")
            }
        }
    }
}

struct CopyDiagnosticsButton: View {
    @EnvironmentObject private var model: DesktopViewModel
    let issue: DesktopIssue?

    var body: some View {
        if let issue {
            Button {
                model.copyDiagnostics(issue)
            } label: {
                Label("复制诊断", systemImage: "doc.on.doc")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
        }
    }
}

/// An inline error with its registered code; the technical cause stays behind "复制诊断".
struct GateIssueBanner: View {
    @EnvironmentObject private var model: DesktopViewModel
    let issue: DesktopIssue
    var tone: Color = .orange

    var body: some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(tone)
            VStack(alignment: .leading, spacing: 3) {
                Text(issue.summaryChinese)
                    .font(.system(size: 13, weight: .semibold))
                Text(issue.displayChinese)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Button("复制诊断") { model.copyDiagnostics(issue) }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
        .padding(14)
        .background(tone.opacity(0.09), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(tone.opacity(0.25), lineWidth: 1)
        }
    }
}

// MARK: - Launch

struct LaunchGateView: View {
    var body: some View {
        VStack(spacing: 22) {
            AppLogoView(size: 64)
            ProgressView().controlSize(.regular)
            Text("正在检查账号与这台 Mac…")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct LaunchFailedGateView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        VStack(spacing: 16) {
            AppLogoView(size: 64)
            Text("暂时无法连接 Hermes GO")
                .font(.system(size: 26, weight: .bold))
            Text("登录状态需要联网确认。这台 Mac 上已在运行的服务不受影响。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
            if let issue = model.accountIssue {
                GateIssueBanner(issue: issue, tone: .red)
                    .frame(width: 480)
                    .padding(.top, 8)
            }
            HStack(spacing: 12) {
                Button("重试") { Task { await model.refreshAccount(bootstrap: true) } }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(model.isAccountOperationInProgress)
            }
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ServiceUnavailableGateView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        VStack(spacing: 16) {
            AppLogoView(size: 64)
            Text("Hermes GO 服务暂不可用")
                .font(.system(size: 26, weight: .bold))
            Text("这个服务器暂时没有开放账号登录。这台 Mac 上已在运行的服务不受影响。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
            if let issue = model.accountIssue {
                GateIssueBanner(issue: issue)
                    .frame(width: 480)
                    .padding(.top, 8)
            }
            Button("重试") { Task { await model.refreshAccount(bootstrap: true) } }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct AccountDeletionSubmittedGateView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.shield")
                .font(.system(size: 44))
                .foregroundStyle(Color.hermesBlue)
            Text("云端账号删除已提交")
                .font(.system(size: 26, weight: .bold))
            Text("云端访问已立即停止，个人数据将在 30 天期限后清理。Mac 上的 Hermes 数据仍保留在本机。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(width: 460)
            Button("使用其他邮箱账号") { Task { await model.refreshAccount() } }
                .buttonStyle(PrimaryButtonStyle())
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Sign in

struct SignInGateView: View {
    @EnvironmentObject private var model: DesktopViewModel
    /// Present only after an unexpected expiry; a deliberate sign-out shows no reason (§5.3).
    let expiredReason: DesktopIssueCode?

    var body: some View {
        GateShell(step: expiredReason == nil ? .signIn : nil) {
            VStack(alignment: .leading, spacing: 0) {
                AppLogoView(size: 64)
                Text(expiredReason == nil ? "登录 Hermes GO" : "重新登录")
                    .font(.system(size: 26, weight: .bold))
                    .padding(.top, 18)
                if let expiredReason {
                    GateIssueBanner(issue: DesktopIssue(code: expiredReason))
                        .padding(.top, 14)
                    Text("这台 Mac 上的 Hermes 和后台连接不会因此停止。")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .padding(.top, 8)
                }
                SignInForm()
                    .padding(.top, expiredReason == nil ? 6 : 18)
            }
            .frame(width: 420, alignment: .leading)
        } footer: {
            CopyDiagnosticsButton(issue: model.accountIssue)
            Spacer()
            Text(versionLabel)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
    }

    private var versionLabel: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return "版本 \(version ?? "开发版")"
    }
}

/// Email plus six-digit code — the only sign-in the first release accepts (DESKTOP_DESIGN.md).
struct SignInForm: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var email = ""
    @State private var code = ""
    @State private var challenge: DesktopEmailVerificationChallenge?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let challenge {
                Text("六位验证码已发送至 \(challenge.email)。")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                TextField("六位验证码", text: $code)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 22, weight: .semibold, design: .monospaced))
                    .focused($focused)
                    .onChange(of: code) { _, value in
                        let digits = String(value.filter(\.isNumber).prefix(6))
                        if digits != value { code = digits }
                    }
                    .onSubmit(verify)
                    .padding(.top, 20)
                Button("验证并登录", action: verify)
                    .buttonStyle(WideButtonStyle())
                    .disabled(code.count != 6 || model.isAccountOperationInProgress)
                    .keyboardShortcut(.defaultAction)
                    .padding(.top, 14)
                HStack {
                    Button("更换邮箱") {
                        self.challenge = nil
                        code = ""
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.hermesBlue)
                    Spacer()
                    Button("重新发送") {
                        Task {
                            if let next = await model.requestEmailSignInCode(email: challenge.email) {
                                self.challenge = next
                                code = ""
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .disabled(model.isAccountOperationInProgress)
                }
                .font(.system(size: 13))
                .padding(.top, 14)
            } else {
                Text("输入邮箱，我们会发送一封包含六位验证码的邮件。首次验证会自动创建账号。")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text("邮箱")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .padding(.top, 22)
                TextField("name@example.com", text: $email)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 14))
                    .focused($focused)
                    .onSubmit(sendCode)
                    .padding(.top, 7)
                Button("发送验证码", action: sendCode)
                    .buttonStyle(WideButtonStyle())
                    .disabled(
                        email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || model.isAccountOperationInProgress
                    )
                    .keyboardShortcut(.defaultAction)
                    .padding(.top, 14)
                Label("这台 Mac 上的 Hermes 凭据不会上传", systemImage: "lock")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .padding(.top, 18)
            }
            if model.isAccountOperationInProgress {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(challenge == nil ? "正在发送…" : "正在验证…")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 12)
            }
            if let issue = model.accountIssue, !isExpiryReason(issue) {
                GateIssueBanner(issue: issue)
                    .padding(.top, 16)
            }
        }
        .onAppear { focused = true }
    }

    private func sendCode() {
        let value = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty, !model.isAccountOperationInProgress else { return }
        Task {
            if let next = await model.requestEmailSignInCode(email: value) {
                challenge = next
                email = next.email
                focused = true
            }
        }
    }

    private func verify() {
        guard let challenge, code.count == 6, !model.isAccountOperationInProgress else { return }
        Task {
            if await model.completeEmailSignIn(challenge: challenge, code: code) {
                self.challenge = nil
                code = ""
                email = ""
            }
        }
    }

    /// The expiry reason is already shown in the page banner; do not repeat it under the form.
    private func isExpiryReason(_ issue: DesktopIssue) -> Bool {
        if case .needsSignIn(let code) = model.accountState { return issue.code == code }
        return false
    }
}

/// Full-width prominent action used on gate and onboarding pages.
struct WideButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(
                Color.hermesBlue.opacity(isEnabled ? (configuration.isPressed ? 0.82 : 1) : 0.4),
                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
            )
    }
}

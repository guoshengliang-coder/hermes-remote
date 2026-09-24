import SwiftUI

enum DesktopSection: String, CaseIterable, Identifiable {
    case overview
    case diagnostics
    case logs
    case pairing
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: "概览"
        case .diagnostics: "诊断"
        case .logs: "日志"
        case .pairing: "账号与设备"
        case .settings: "设置"
        }
    }

    var symbol: String {
        switch self {
        case .overview: "square.grid.2x2"
        case .diagnostics: "waveform.path.ecg"
        case .logs: "doc.text"
        case .pairing: "iphone"
        case .settings: "gearshape"
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        Group {
            switch model.entryRoute {
            case .launching:
                LaunchGateView()
            case .launchFailed:
                LaunchFailedGateView()
            case .signIn(let expiredReason):
                SignInGateView(expiredReason: expiredReason)
            case .serviceUnavailable:
                ServiceUnavailableGateView()
            case .accountDeletionSubmitted:
                AccountDeletionSubmittedGateView()
            case .newMacChoice:
                NewMacChoiceGateView()
            case .onboarding(let step):
                OnboardingGateView(step: step)
            case .main:
                MainShellView()
            }
        }
        .modifier(SetupConfirmationSheets())
        .onChange(of: model.entryRoute, initial: true) { _, route in
            model.entryRouteDidChange(route)
        }
    }
}

/// The signed-in, set-up app: sidebar plus sections. The section survives a re-sign-in, so an
/// unexpected expiry returns to where the owner was (§4.3).
private struct MainShellView: View {
    @Environment(\.colorScheme) private var colorScheme
    @SceneStorage("desktop.selectedSection") private var selection: DesktopSection = .overview

    var body: some View {
        HStack(spacing: 0) {
            SidebarView(selection: $selection)
                .frame(width: 210)

            Rectangle()
                .fill(Color.hermesHairline(colorScheme))
                .frame(width: 1)

            Group {
                switch selection {
                case .overview: OverviewView(selection: $selection)
                case .diagnostics: DiagnosticsView(selection: $selection)
                case .logs: LogsView()
                case .pairing: AccountDevicesView()
                case .settings: SettingsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.hermesCanvas(colorScheme))
    }
}

private struct SidebarView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Binding var selection: DesktopSection

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                AppLogoView(size: 42)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Hermes GO")
                        .font(.system(size: 17, weight: .bold))
                    Text("Desktop")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 18)
            .padding(.top, 24)
            .padding(.bottom, 26)

            ForEach(DesktopSection.allCases) { item in
                Button {
                    selection = item
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: item.symbol)
                            .font(.system(size: 16, weight: .regular))
                            .frame(width: 22)
                        Text(item.title)
                            .font(.system(size: 14, weight: selection == item ? .semibold : .regular))
                        Spacer()
                    }
                    .foregroundStyle(selection == item ? Color.hermesBlue : .primary)
                    .padding(.horizontal, 13)
                    .frame(height: 42)
                    .background(selection == item ? Color.hermesBlue.opacity(0.09) : .clear)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .accessibilityLabel(item.title)
            }

            Spacer()

            Text("账号客户端 · 兼容观察")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 18)
                .padding(.bottom, 5)
            Text(versionLabel)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 18)
                .padding(.bottom, 18)
        }
        .background(Color.hermesCard(colorScheme).opacity(0.8))
    }

    private var versionLabel: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        return "版本 \(version ?? "开发版")-dev"
    }
}

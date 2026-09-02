import AppKit
import HermesGoDesktopCore
import SwiftUI

extension Color {
    static let hermesBlue = Color(hex: 0x0B5FD0)

    static func hermesCanvas(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x111820) : Color(hex: 0xF7F9FD)
    }

    static func hermesCard(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x1A212A) : Color(hex: 0xFAFBFD)
    }

    static func hermesHairline(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0x333A44) : Color(hex: 0xEBEDF2)
    }

    static func status(_ level: HealthLevel, scheme: ColorScheme) -> Color {
        switch level {
        case .healthy: scheme == .dark ? Color(hex: 0x70D69B) : Color(hex: 0x228A52)
        case .degraded: scheme == .dark ? Color(hex: 0xFFC45B) : Color(hex: 0xC77900)
        case .failed: scheme == .dark ? Color(hex: 0xFF8A80) : Color(hex: 0xC83B32)
        case .checking: scheme == .dark ? Color(hex: 0xA9C7FF) : .hermesBlue
        case .unavailable: scheme == .dark ? Color(hex: 0xAEB8C4) : Color(hex: 0x7A8491)
        }
    }

    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

struct HermesCardModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .background(Color.hermesCard(colorScheme))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .stroke(Color.hermesHairline(colorScheme), lineWidth: 1)
            }
            .shadow(
                color: colorScheme == .dark ? .clear : .black.opacity(0.035),
                radius: 4,
                y: 1
            )
    }
}

extension View {
    func hermesCard() -> some View {
        modifier(HermesCardModifier())
    }
}

struct StatusDot: View {
    @Environment(\.colorScheme) private var colorScheme
    let level: HealthLevel
    var size: CGFloat = 9

    var body: some View {
        Circle()
            .fill(Color.status(level, scheme: colorScheme))
            .frame(width: size, height: size)
            .accessibilityLabel(accessibilityLabel)
    }

    private var accessibilityLabel: String {
        switch level {
        case .checking: "正在检查"
        case .healthy: "正常"
        case .degraded: "功能受限"
        case .failed: "检查失败"
        case .unavailable: "不可用"
        }
    }
}

struct AppLogoView: View {
    let size: CGFloat

    var body: some View {
        Image(nsImage: NSApplication.shared.applicationIconImage)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .frame(width: size, height: size)
            .accessibilityLabel("Hermes GO")
    }
}

struct PageHeader<Actions: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let actions: Actions

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.system(size: 30, weight: .bold))
                Text(subtitle)
                    .font(.system(size: 15))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 16)
            actions
        }
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 17)
            .frame(minHeight: 36)
            .background(Color.hermesBlue.opacity(configuration.isPressed ? 0.82 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
    }
}

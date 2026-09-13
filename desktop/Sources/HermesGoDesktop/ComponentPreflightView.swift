import HermesGoDesktopCore
import SwiftUI

struct ComponentPreflightCard: View {
    let presentation: DesktopComponentPreflightPresentation

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Label("环境与组件预检", systemImage: "checkmark.shield")
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Text("Hermes Go \(presentation.releaseVersion)")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                summaryPill(
                    title: "直接复用 \(presentation.reusedComponentCount) 项",
                    systemImage: "checkmark.circle.fill",
                    emphasized: true
                )
                summaryPill(
                    title: "首次下载 \(presentation.bootstrapDownloadText)",
                    systemImage: "arrow.down.circle"
                )
                if presentation.deferredDownloadBytes > 0 {
                    summaryPill(
                        title: "按需下载 \(presentation.deferredDownloadText)",
                        systemImage: "clock.badge.checkmark"
                    )
                }
            }

            Divider()
            VStack(spacing: 0) {
                ForEach(Array(presentation.rows.enumerated()), id: \.element.id) { index, row in
                    componentRow(row)
                    if index < presentation.rows.count - 1 {
                        Divider().padding(.leading, 31)
                    }
                }
            }

            Label("只读取本机状态；不会修改 Homebrew、用户环境或正在运行的服务", systemImage: "lock.shield")
                .font(.system(size: 10))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(20)
        .hermesCard()
    }

    private func summaryPill(
        title: String,
        systemImage: String,
        emphasized: Bool = false
    ) -> some View {
        Label(title, systemImage: systemImage)
            .font(.system(size: 10, weight: .semibold))
            .foregroundStyle(emphasized ? Color.hermesBlue : Color.secondary)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(
                (emphasized ? Color.hermesBlue : Color.secondary).opacity(0.09),
                in: Capsule()
            )
    }

    private func componentRow(_ row: DesktopComponentPreflightPresentationRow) -> some View {
        HStack(spacing: 11) {
            Image(systemName: symbol(row.action))
                .foregroundStyle(color(row.action))
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 3) {
                Text(row.titleChinese)
                    .font(.system(size: 12, weight: .semibold))
                Text(row.detailChinese)
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            Text(actionLabel(row.action))
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(color(row.action))
        }
        .padding(.vertical, 9)
    }

    private func symbol(_ action: DesktopComponentPreflightPresentationAction) -> String {
        switch action {
        case .reuseManaged: "checkmark.circle.fill"
        case .reuseExternal: "checkmark.seal.fill"
        case .downloadAtInstall: "arrow.down.circle.fill"
        case .downloadOnDemand: "clock.badge.checkmark"
        }
    }

    private func actionLabel(_ action: DesktopComponentPreflightPresentationAction) -> String {
        switch action {
        case .reuseManaged, .reuseExternal: "复用"
        case .downloadAtInstall: "安装时"
        case .downloadOnDemand: "按需"
        }
    }

    private func color(_ action: DesktopComponentPreflightPresentationAction) -> Color {
        switch action {
        case .reuseManaged, .reuseExternal: Color.hermesBlue
        case .downloadAtInstall: Color.primary
        case .downloadOnDemand: Color.secondary
        }
    }
}

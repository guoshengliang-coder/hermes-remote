import HermesGoDesktopCore
import SwiftUI

struct ComponentPreflightCard: View {
    let presentation: DesktopComponentPreflightPresentation
    let canBegin: Bool
    let isUpgrade: Bool
    let operation: DesktopComponentBootstrapOperation
    let cleanupRetryAvailable: Bool
    let prepare: () -> Void
    let retryCleanup: () -> Void

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

            componentAction
        }
        .padding(20)
        .hermesCard()
    }

    @ViewBuilder
    private var componentAction: some View {
        switch operation {
        case .preparing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在下载并验证缺失组件；尚未修改安装或服务")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .awaitingConfirmation:
            Divider()
            Label("所需组件已验证，等待你的明确确认", systemImage: "checkmark.shield")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.hermesBlue)
        case .committing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在提交组件、启动并验证服务；请保持 Desktop 打开")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .completed(let releaseVersion, let cleanupPending):
            Divider()
            HStack {
                Label("Hermes Go \(releaseVersion) 已连接", systemImage: "checkmark.circle.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color.hermesBlue)
                Spacer()
                if cleanupPending {
                    Button("重试清理", action: retryCleanup)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            }
        case .idle, .failed:
            if cleanupRetryAvailable {
                Divider()
                HStack {
                    Text("安装未继续，私有临时文件仍待清理。")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("重试清理", action: retryCleanup)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            } else if canBegin {
                Divider()
                HStack {
                    Text("只下载缺失的基础组件；提交安装前还会再次确认。")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(isUpgrade ? "下载并验证更新" : "下载缺失组件", action: prepare)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                }
            }
        }
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

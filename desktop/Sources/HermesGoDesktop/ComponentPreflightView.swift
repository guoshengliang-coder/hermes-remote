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
                Label("Desktop 安装检查", systemImage: "checkmark.shield")
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Text("已检查安装条件")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
            }

            Text("需要下载 \(presentation.bootstrapDownloadText)，已有的安装内容将复用。详细检查结果可在诊断中查看。")
                .font(.system(size: 12)).foregroundStyle(.secondary)

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
                Text("正在准备 Desktop 安装内容；尚未修改安装或服务")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
        case .awaitingConfirmation:
            Divider()
            Label("Desktop 安装内容已验证，等待你的明确确认", systemImage: "checkmark.shield")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.hermesBlue)
        case .committing:
            Divider()
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在安装并验证 Desktop；请保持 Desktop 打开")
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
                    Text("安装前会说明对连接和任务的影响。")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(isUpgrade ? "查看 Desktop 更新" : "准备安装 Desktop", action: prepare)
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                }
            }
        }
    }

}

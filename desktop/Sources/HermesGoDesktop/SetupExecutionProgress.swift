import HermesGoDesktopCore
import SwiftUI

/// Time updates independently of network callbacks; stage changes come only from real work.
struct SetupExecutionProgress: View {
    let progress: DesktopSetupProgress?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let snapshot = progress?.snapshot
            HStack(alignment: .top, spacing: 10) {
                ProgressView().controlSize(.small)
                    .accessibilityLabel("正在更新连接")
                VStack(alignment: .leading, spacing: 6) {
                    Text(snapshot?.stage.titleChinese ?? DesktopSetupStage.checking.titleChinese)
                        .font(.system(size: 12, weight: .semibold))
                    Text(snapshot?.elapsedChinese ?? "已用时 0 秒")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(.secondary)
                    Text(snapshot?.stage == .rollingBack
                        ? "正在恢复原来的连接，请保持 Desktop 打开。"
                        : "服务重启和连接验证可能需要一些时间；执行期间无法取消，请保持 Desktop 打开。")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

import SwiftUI

struct LogsView: View {
    @EnvironmentObject private var model: DesktopViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            PageHeader(
                title: "日志",
                subtitle: "仅显示 Desktop 与 Connector 的脱敏运行信息"
            ) {
                Button("打开日志目录") { model.openLegacyLogDirectory() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
            }

            ScrollView {
                Text(logText)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(18)
            }
            .hermesCard()

            Label("Token、密码、Cookie 和签名参数会在显示前自动隐藏。", systemImage: "lock")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
        }
        .padding(34)
    }

    private var logText: String {
        let lines = model.legacy?.recentLogs ?? []
        return lines.isEmpty ? "暂无可显示的 Connector 日志。" : lines.joined(separator: "\n")
    }
}

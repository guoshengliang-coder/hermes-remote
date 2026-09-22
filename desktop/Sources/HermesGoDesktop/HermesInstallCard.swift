import HermesGoDesktopCore
import SwiftUI

/// Install-when-missing (`docs/DESKTOP_DESIGN.md`, "Installing Hermes on a Mac that has none").
///
/// Shown above the setup-preflight card, only on a fresh Mac about to be set up that has no Hermes
/// at all. It says what will happen before anything runs, requires the confirmation sheet, shows
/// the installer's own stages while it runs, and always leaves the bundled copy as the way out.
struct HermesInstallCard: View {
    @EnvironmentObject private var model: DesktopViewModel
    @State private var isBundledChoicePresented = false

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack {
                Label(title, systemImage: symbol)
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Text("Hermes 官方安装程序")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
            content
        }
        .padding(20)
        .hermesCard()
        .sheet(isPresented: $model.isHermesInstallConfirmationPresented) {
            if let offer = model.hermesInstallPhase.offer {
                confirmationSheet(offer)
            }
        }
        .confirmationDialog(
            "这台 Mac 改用 Hermes GO 内置的 Hermes？",
            isPresented: $isBundledChoicePresented
        ) {
            Button("改用内置 Hermes") { model.useBundledHermes() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("之后的设置会像以前一样安装并运行 Hermes GO 自带的 Hermes，不会安装官方 Hermes。已经下载到 ~/.hermes 的内容保持不动。")
        }
    }

    private var title: String {
        switch model.hermesInstallPhase {
        case .running(_, _, let cancelling): cancelling ? "正在取消安装" : "正在安装 Hermes"
        case .succeeded: "Hermes 已安装"
        case .cancelled: "已取消安装 Hermes"
        case .failed: "Hermes 安装未完成"
        case .offered, .hidden: "这台 Mac 还没有 Hermes"
        }
    }

    private var symbol: String {
        switch model.hermesInstallPhase {
        case .running: "arrow.down.circle"
        case .succeeded: "checkmark.circle.fill"
        case .cancelled: "pause.circle"
        case .failed: "exclamationmark.triangle"
        case .offered, .hidden: "shippingbox"
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.hermesInstallPhase {
        case .hidden:
            EmptyView()
        case .offered(let offer):
            Text("推荐先安装 Hermes 官方版本：Hermes GO 会运行 Hermes 官方安装程序，把 Hermes 装到 ~/.hermes，和你自己在终端安装的完全一样；之后由 Hermes GO 负责启动它，手机连接的就是这一份 Hermes。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            facts(offer)
            Divider()
            HStack {
                Button("改用内置 Hermes") { isBundledChoicePresented = true }
                    .buttonStyle(.bordered)
                Spacer()
                Button("安装 Hermes…") { model.isHermesInstallConfirmationPresented = true }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
            }
        case .running(_, let run, let cancelling):
            stageList(run)
            Divider()
            HStack {
                Text("日志：\(model.hermesInstallLogPath)")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                Spacer()
                Button(cancelling ? "正在取消…" : "取消安装") { model.cancelHermesInstall() }
                    .buttonStyle(.bordered)
                    .disabled(cancelling)
            }
        case .succeeded(let version):
            Text("Hermes \(version) 已安装在 ~/.hermes。继续下方的安装步骤，Hermes GO 会直接使用这份 Hermes，不会再安装第二份。模型服务可稍后在终端运行 hermes setup 配置。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        case .cancelled(_, let run):
            stageList(run)
            Text("已停止安装。已完成的部分保留在 ~/.hermes，重试会从那里继续；也可以改用内置 Hermes。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            retryActions(retryable: true)
        case .failed(_, let run, let issue):
            stageList(run)
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                VStack(alignment: .leading, spacing: 4) {
                    Text(issue.summaryChinese)
                        .font(.system(size: 13, weight: .semibold))
                    Text(issue.displayChinese)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("日志：\(model.hermesInstallLogPath)")
                        .font(.system(size: 10))
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                Spacer()
                Button("复制诊断") { model.copyDiagnostics(issue) }
                    .buttonStyle(.bordered)
            }
            retryActions(retryable: issue.retryable)
        }
    }

    private func facts(_ offer: DesktopHermesInstallOffer) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            fact("来源：\(offer.scriptURL.host ?? "hermes-agent.nousresearch.com") 官方安装程序（HTTPS），分支 \(offer.branch)", symbol: "checkmark.seal")
            fact("安装到 ~/.hermes/hermes-agent；约占用 2–4 GB，网络良好时约 10–20 分钟", symbol: "internaldrive")
            fact("需要联网：\(offer.proxy.summaryChinese)", symbol: "network")
            fact("以当前用户身份运行，不使用管理员权限、不执行 sudo；可随时取消", symbol: "lock.shield")
            fact("模型服务与消息网关不在此时配置，可稍后运行 hermes setup", symbol: "slider.horizontal.3")
            if offer.resumesEarlierAttempt {
                fact("会从上次未完成的安装继续", symbol: "arrow.clockwise")
            }
        }
    }

    private func fact(_ text: String, symbol: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.system(size: 11))
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func stageList(_ run: DesktopHermesInstallRun) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            if run.downloading {
                stageRow(symbol: nil, title: "下载官方安装程序", note: nil)
            }
            ForEach(run.rows) { row in
                switch row.state {
                case .pending:
                    stageRow(symbol: "circle", title: row.stage.titleChinese, note: nil, dimmed: true)
                case .running:
                    stageRow(symbol: nil, title: row.stage.titleChinese, note: nil)
                case .succeeded:
                    stageRow(symbol: "checkmark.circle.fill", title: row.stage.titleChinese, note: nil)
                case .skipped:
                    stageRow(symbol: "minus.circle", title: row.stage.titleChinese, note: "需要交互，稍后配置", dimmed: true)
                case .failed:
                    stageRow(symbol: "xmark.circle.fill", title: row.stage.titleChinese, note: "失败")
                }
            }
            if run.verifying {
                stageRow(symbol: nil, title: "确认 Hermes 可以使用", note: nil)
            }
            if !run.rows.isEmpty {
                ProgressView(value: Double(run.completedCount), total: Double(max(run.rows.count, 1)))
                    .progressViewStyle(.linear)
            }
        }
    }

    private func stageRow(symbol: String?, title: String, note: String?, dimmed: Bool = false) -> some View {
        HStack(spacing: 9) {
            Group {
                if let symbol {
                    Image(systemName: symbol)
                        .foregroundStyle(symbol == "xmark.circle.fill" ? Color.red : (dimmed ? Color.secondary : Color.hermesBlue))
                } else {
                    ProgressView().controlSize(.mini)
                }
            }
            .frame(width: 16)
            Text(title)
                .font(.system(size: 11))
                .foregroundStyle(dimmed ? .secondary : .primary)
            Spacer()
            if let note {
                Text(note)
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func retryActions(retryable: Bool) -> some View {
        HStack {
            Button("改用内置 Hermes") { isBundledChoicePresented = true }
                .buttonStyle(.bordered)
            Spacer()
            if retryable {
                Button("重试") { model.startHermesInstall() }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    private func confirmationSheet(_ offer: DesktopHermesInstallOffer) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("安装 Hermes 官方版本", systemImage: "shippingbox.fill")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color.hermesBlue)
            Text("继续后，Hermes GO 会从 Hermes 官方网站下载安装程序，并以你的用户身份逐步运行它，把 Hermes 安装到 ~/.hermes。安装程序可能会下载 Python、Node.js 和浏览器组件；Homebrew 已安装时可能用它补充 ripgrep、ffmpeg 等工具；macOS 可能弹出安装命令行开发工具的系统窗口。")
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            facts(offer)
            Text("官方安装程序通过 HTTPS 从官方来源获取；Hermes 官方目前不提供安装程序的校验和或签名。")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button("取消") { model.isHermesInstallConfirmationPresented = false }
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("开始安装") { model.startHermesInstall() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding(26)
        .frame(width: 540)
    }
}

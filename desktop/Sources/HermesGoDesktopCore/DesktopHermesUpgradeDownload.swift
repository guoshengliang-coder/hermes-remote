import Foundation

/// Facts emitted by the downloader, never a guessed whole-upgrade percentage.
public enum DesktopHermesUpgradeActivity: Equatable, Sendable {
    case network(String)
    case transfer(DesktopHermesTransferProgress)
    case substep(String)
    case resumed(completedStages: Int)
}

public struct DesktopHermesTransferProgress: Equatable, Sendable {
    public let receivedBytes: Int
    public let totalBytes: Int?
    public let bytesPerSecond: Double?
    public init(receivedBytes: Int, totalBytes: Int? = nil, bytesPerSecond: Double? = nil) {
        self.receivedBytes = max(0, receivedBytes)
        self.totalBytes = totalBytes.flatMap { $0 >= receivedBytes && $0 > 0 ? $0 : nil }
        self.bytesPerSecond = bytesPerSecond.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
    }
    public var summary: String {
        let bytes = ByteCountFormatter.string(fromByteCount: Int64(receivedBytes), countStyle: .binary)
        let total = totalBytes.map { " / " + ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .binary) } ?? ""
        return "已下载 \(bytes)\(total)"
    }
    public var speedSummary: String? {
        bytesPerSecond.map { ByteCountFormatter.string(fromByteCount: Int64(min($0, Double(Int64.max / 2))), countStyle: .binary) + "/s" }
    }

    /// Git's byte counters are independent of its object-count percentage. Keep only byte facts.
    public static func parse(_ line: String) -> Self? {
        guard line.contains("Receiving objects:") else { return nil }
        let pattern = #",\s*([0-9]+(?:\.[0-9]+)?)\s*(bytes|KiB|MiB|GiB)\s*\|\s*([0-9]+(?:\.[0-9]+)?)\s*(bytes|KiB|MiB|GiB)/s"#
        guard let re = try? NSRegularExpression(pattern: pattern),
              let match = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
        func field(_ i: Int) -> String { String(line[Range(match.range(at: i), in: line)!]) }
        let scale = ["bytes": 1.0, "KiB": 1024, "MiB": 1048576, "GiB": 1073741824]
        guard let amount = Double(field(1)), let speed = Double(field(3)),
              let unit = scale[field(2)], let speedUnit = scale[field(4)], amount * unit < Double(Int.max / 2) else { return nil }
        return .init(receivedBytes: Int(amount * unit), bytesPerSecond: speed * speedUnit)
    }
}

public enum DesktopHermesUpgradeOutput {
    /// Only known operations become primary UI text; arbitrary logs remain in the redacted disclosure.
    public static func substep(_ line: String) -> String? {
        let titles = [("Preparing Node dependencies", "准备 Node 依赖"),
                      ("Installing Python dependencies", "安装 Python 依赖"),
                      ("Downloading Python", "下载 Python 运行环境"),
                      ("Building desktop packaged app", "构建 Hermes 客户端"),
                      ("Packaging the desktop app", "打包 Hermes 客户端"),
                      ("Building the TUI", "构建命令行界面"),
                      ("Building the web UI", "构建 Web 界面"),
                      ("electron-runtime: preparing", "下载并准备 Electron 运行环境")]
        return titles.first { line.contains($0.0) }?.1
    }
}

/// A scoped adapter for upstream children. No writes to Git or system proxy configuration.
struct DesktopHermesUpgradeNetwork {
    static func environment(proxy: DesktopSystemProxy, inherited: [String: String]) -> [String: String] {
        var env = proxy.environment(inheriting: inherited)
        for (lower, upper) in [("http_proxy", "HTTP_PROXY"), ("https_proxy", "HTTPS_PROXY"), ("all_proxy", "ALL_PROXY")] {
            if let value = env[lower] ?? env[upper] { env[lower] = value; env[upper] = value }
        }
        // Native fetch (Node >=22.21 /24); ignored by older Nodes rather than an unsupported flag.
        if env["HTTP_PROXY"] != nil || env["HTTPS_PROXY"] != nil {
            env["NODE_USE_ENV_PROXY"] = "1"
            env["ELECTRON_GET_USE_PROXY"] = "1"
            env["GLOBAL_AGENT_HTTP_PROXY"] = env["HTTP_PROXY"] ?? env["HTTPS_PROXY"]
            env["GLOBAL_AGENT_HTTPS_PROXY"] = env["HTTPS_PROXY"] ?? env["HTTP_PROXY"]
            env["GLOBAL_AGENT_NO_PROXY"] = env["NO_PROXY"]
        }
        let canonical = "https://github.com/NousResearch/hermes-agent.git"
        let sources = [canonical, String(canonical.dropLast(4)), "git@github.com:NousResearch/hermes-agent.git",
                       "git@github.com:NousResearch/hermes-agent", "ssh://git@github.com/NousResearch/hermes-agent.git"]
        var configs = sources.map { ("url.\(canonical).insteadOf", $0) }
        if let value = env["https_proxy"] ?? env["all_proxy"] { configs.append(("http.https://github.com/.proxy", value)) }
        env["GIT_CONFIG_COUNT"] = String(configs.count)
        for (i, entry) in configs.enumerated() { env["GIT_CONFIG_KEY_\(i)"] = entry.0; env["GIT_CONFIG_VALUE_\(i)"] = entry.1 }
        return env
    }
    static func summary(proxy: DesktopSystemProxy, environment: [String: String]) -> String {
        if environment["https_proxy"] != nil { return "下载网络：使用已配置的 HTTPS 代理" }
        if environment["all_proxy"] != nil { return "下载网络：SOCKS 代理；Node 下载需要 HTTP/HTTPS 代理" }
        if proxy.usesAutomaticConfiguration { return "下载网络：自动代理仅用于升级程序下载；依赖下载未配置手动代理" }
        return "下载网络：直接连接（HTTPS）"
    }
    static func urlSessionProxy(_ env: [String: String]) throws -> [AnyHashable: Any] {
        var result: [AnyHashable: Any] = ["HTTPEnable": 0, "HTTPSEnable": 0, "SOCKSEnable": 0]
        for (name, prefix) in [("http_proxy", "HTTP"), ("https_proxy", "HTTPS"), ("all_proxy", "SOCKS")] {
            guard let raw = env[name], !raw.isEmpty else { continue }
            guard let url = URLComponents(string: raw), let host = url.host, !host.isEmpty,
                  url.user == nil, url.password == nil,
                  prefix == "SOCKS" ? ["socks5", "socks5h"].contains(url.scheme ?? "") : url.scheme == "http" else {
                throw DesktopHermesInstallFailure.network(stage: "download", detail: "configured proxy cannot be used for installer download")
            }
            let port = url.port ?? (prefix == "SOCKS" ? 1080 : 80)
            guard (1...65535).contains(port) else {
                throw DesktopHermesInstallFailure.network(stage: "download", detail: "configured proxy port is invalid")
            }
            result[prefix + "Enable"] = 1; result[prefix + "Proxy"] = host; result[prefix + "Port"] = port
        }
        result["ExceptionsList"] = (env["NO_PROXY"] ?? "").split(separator: ",").map(String.init)
        return result
    }
    /// Git suppresses progress on pipes. Ask for its real counters without patching upstream.
    static let gitWrapper = #"""
    #!/bin/bash
    args=("$@")
    i=0
    while [ "$i" -lt "${#args[@]}" ]; do
      case "${args[$i]}" in
        -C|-c|--git-dir|--work-tree|--namespace|--config-env) i=$((i+2));;
        --*=*|-*) i=$((i+1));;
        fetch|clone)
          exec /usr/bin/git "${args[@]:0:$((i+1))}" --progress "${args[@]:$((i+1))}";;
        *) break;;
      esac
    done
    exec /usr/bin/git "$@"
    """#
}

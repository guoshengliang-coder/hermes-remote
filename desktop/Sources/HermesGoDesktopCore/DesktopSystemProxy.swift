import CFNetwork
import Foundation

/// The macOS system proxy, as the child processes of upstream's installer need to see it.
///
/// `URLSession` honours the system proxy (including PAC) by itself, so Desktop's own download of the
/// installer needs nothing from here. The installer's children do: `git`, `curl`, `uv` and `npm` read
/// only environment variables, and a Desktop launched from Finder has none. Owner decision
/// 2026-09-22: Hermes is installed from upstream directly, through the system proxy — so the manual
/// HTTP, HTTPS and SOCKS proxies configured in System Settings are exported to the installer's
/// environment, and only to it.
///
/// What is deliberately not exported:
/// - **PAC / auto-discovery.** A proxy chosen per URL by a script cannot be expressed as one
///   environment variable. It is reported (`usesAutomaticConfiguration`) so the log says why the
///   installer ran without a proxy; the owner can configure a manual proxy or set the variables.
/// - **Proxy credentials.** macOS keeps them in the keychain, not in these settings. A proxy that
///   needs them answers 407, which the installer driver reports as a network failure.
public struct DesktopSystemProxy: Equatable, Sendable {
    public struct Endpoint: Equatable, Sendable {
        public let host: String
        public let port: Int

        public init?(host: String, port: Int) {
            let trimmed = host.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, trimmed.utf8.count <= 253,
                  trimmed.range(of: #"^[A-Za-z0-9._:\[\]-]+$"#, options: .regularExpression) != nil,
                  (1...65535).contains(port)
            else { return nil }
            self.host = trimmed
            self.port = port
        }

        /// `host:port`, bracketing a bare IPv6 address so the result is a valid URL authority.
        public var authority: String {
            let needsBrackets = host.contains(":") && !host.hasPrefix("[")
            return (needsBrackets ? "[\(host)]" : host) + ":\(port)"
        }
    }

    public let http: Endpoint?
    public let https: Endpoint?
    public let socks: Endpoint?
    /// "Bypass proxy settings for these hosts & domains", already translated to `NO_PROXY` syntax.
    public let exceptions: [String]
    public let usesAutomaticConfiguration: Bool

    public init(
        http: Endpoint? = nil,
        https: Endpoint? = nil,
        socks: Endpoint? = nil,
        exceptions: [String] = [],
        usesAutomaticConfiguration: Bool = false
    ) {
        self.http = http
        self.https = https
        self.socks = socks
        self.exceptions = exceptions
        self.usesAutomaticConfiguration = usesAutomaticConfiguration
    }

    public static let none = DesktopSystemProxy()

    public var isConfigured: Bool { http != nil || https != nil || socks != nil }

    /// The current user's settings (`CFNetworkCopySystemProxySettings`).
    public static func current() -> DesktopSystemProxy {
        guard let settings = CFNetworkCopySystemProxySettings()?.takeRetainedValue() as? [String: Any]
        else { return .none }
        return parse(settings)
    }

    /// Parses the dictionary shape `CFNetworkCopySystemProxySettings` returns. Pure, for tests.
    public static func parse(_ settings: [String: Any]) -> DesktopSystemProxy {
        func flag(_ key: String) -> Bool { (settings[key] as? NSNumber)?.boolValue ?? false }
        func endpoint(_ prefix: String) -> Endpoint? {
            guard flag("\(prefix)Enable"),
                  let host = settings["\(prefix)Proxy"] as? String,
                  let port = (settings["\(prefix)Port"] as? NSNumber)?.intValue
            else { return nil }
            return Endpoint(host: host, port: port)
        }
        let exceptions = (settings["ExceptionsList"] as? [String] ?? []).compactMap(noProxyEntry)
        return DesktopSystemProxy(
            http: endpoint("HTTP"),
            https: endpoint("HTTPS"),
            socks: endpoint("SOCKS"),
            exceptions: exceptions,
            usesAutomaticConfiguration: flag("ProxyAutoConfigEnable") || flag("ProxyAutoDiscoveryEnable")
        )
    }

    /// Environment-variable names the installer's children read for a proxy, in both cases:
    /// curl honours only the lower-case `http_proxy`, most other tools either.
    public static let proxyVariableNames = [
        "http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY",
    ]
    public static let noProxyVariableNames = ["no_proxy", "NO_PROXY"]
    /// Loopback always bypasses any proxy: nothing the installer does should reach 127.0.0.1
    /// through a proxy (the same rule `HTTPHealthProber` applies to Desktop's own loopback probes).
    public static let loopbackExceptions = ["localhost", "127.0.0.1", "::1"]

    /// The proxy variables for the installer's environment.
    ///
    /// - Explicit proxy variables already in `inherited` win and are passed through unchanged: the
    ///   owner set them on purpose, and mixing them with System Settings would be a guess.
    /// - Otherwise a variable is exported only for a proxy that is actually configured: HTTP →
    ///   `http_proxy`, HTTPS ("Secure web proxy", an HTTP CONNECT proxy, hence the `http://` scheme)
    ///   → `https_proxy`, SOCKS → `all_proxy` with `socks5h://` so names resolve through the proxy.
    ///   Each macOS setting maps to exactly the traffic macOS itself sends through it.
    /// - `no_proxy` always carries loopback, the configured exceptions and anything inherited.
    public func environment(inheriting inherited: [String: String]) -> [String: String] {
        var result: [String: String] = [:]
        let explicit = Self.proxyVariableNames.filter { !(inherited[$0] ?? "").isEmpty }
        if !explicit.isEmpty {
            for name in explicit { result[name] = inherited[name] }
        } else {
            if let http {
                result["http_proxy"] = "http://\(http.authority)"
                result["HTTP_PROXY"] = "http://\(http.authority)"
            }
            if let https {
                result["https_proxy"] = "http://\(https.authority)"
                result["HTTPS_PROXY"] = "http://\(https.authority)"
            }
            if let socks {
                result["all_proxy"] = "socks5h://\(socks.authority)"
                result["ALL_PROXY"] = "socks5h://\(socks.authority)"
            }
        }
        var bypass: [String] = []
        for name in Self.noProxyVariableNames {
            for entry in (inherited[name] ?? "").split(separator: ",") {
                if let value = Self.noProxyEntry(String(entry)) { bypass.append(value) }
            }
        }
        bypass += Self.loopbackExceptions + exceptions
        var seen = Set<String>()
        let joined = bypass.filter { seen.insert($0.lowercased()).inserted }.joined(separator: ",")
        for name in Self.noProxyVariableNames { result[name] = joined }
        return result
    }

    /// One line for the confirmation sheet and the install log. Never carries credentials: none are
    /// read in the first place.
    public var summaryChinese: String {
        var parts: [String] = []
        if let https { parts.append("HTTPS \(https.authority)") }
        if let http { parts.append("HTTP \(http.authority)") }
        if let socks { parts.append("SOCKS \(socks.authority)") }
        if !parts.isEmpty { return "系统代理（\(parts.joined(separator: "，"))）" }
        if usesAutomaticConfiguration { return "系统使用自动代理配置（PAC），安装程序将直接联网" }
        return "未设置系统代理，将直接联网"
    }

    public var summaryForLog: String {
        var parts: [String] = []
        if let https { parts.append("https=\(https.authority)") }
        if let http { parts.append("http=\(http.authority)") }
        if let socks { parts.append("socks=\(socks.authority)") }
        if usesAutomaticConfiguration { parts.append("pac=not-exported") }
        return parts.isEmpty ? "proxy=none" : "proxy " + parts.joined(separator: " ")
    }

    /// A macOS bypass entry in `no_proxy` syntax, or nil when it cannot be expressed safely.
    /// `*.example.com` becomes `.example.com` (curl and git match suffixes, not globs).
    static func noProxyEntry(_ raw: String) -> String? {
        var value = raw.trimmingCharacters(in: .whitespaces)
        if value.hasPrefix("*.") { value = String(value.dropFirst(1)) }
        guard !value.isEmpty, value != "*", value.utf8.count <= 255,
              value.range(of: #"^[A-Za-z0-9._:/\[\]-]+$"#, options: .regularExpression) != nil
        else { return nil }
        return value
    }
}

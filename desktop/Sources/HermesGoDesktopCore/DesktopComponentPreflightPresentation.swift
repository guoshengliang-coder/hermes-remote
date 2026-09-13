import Foundation

public enum DesktopComponentPreflightPresentationAction: String, Equatable, Sendable {
    case reuseManaged = "reuse_managed"
    case reuseExternal = "reuse_external"
    case downloadAtInstall = "download_at_install"
    case downloadOnDemand = "download_on_demand"
}

public struct DesktopComponentPreflightPresentationRow: Identifiable, Equatable, Sendable {
    public let id: DesktopManagedComponentKind
    public let titleChinese: String
    public let titleEnglish: String
    public let detailChinese: String
    public let detailEnglish: String
    public let action: DesktopComponentPreflightPresentationAction
    public let downloadBytes: Int64

    public init(
        id: DesktopManagedComponentKind,
        titleChinese: String,
        titleEnglish: String,
        detailChinese: String,
        detailEnglish: String,
        action: DesktopComponentPreflightPresentationAction,
        downloadBytes: Int64
    ) {
        self.id = id
        self.titleChinese = titleChinese
        self.titleEnglish = titleEnglish
        self.detailChinese = detailChinese
        self.detailEnglish = detailEnglish
        self.action = action
        self.downloadBytes = downloadBytes
    }
}

public struct DesktopComponentPreflightPresentation: Equatable, Sendable {
    public let releaseVersion: String
    public let rows: [DesktopComponentPreflightPresentationRow]
    public let bootstrapDownloadBytes: Int64
    public let deferredDownloadBytes: Int64

    public init(result: DesktopComponentReleasePreflightResult) {
        releaseVersion = result.manifest.releaseVersion
        rows = result.plan.decisions.map(Self.row)
        bootstrapDownloadBytes = result.plan.bootstrapDownloadBytes
        deferredDownloadBytes = result.plan.deferredDownloadBytes
    }

    public var reusedComponentCount: Int {
        rows.filter { $0.action == .reuseManaged || $0.action == .reuseExternal }.count
    }

    public var bootstrapDownloadText: String {
        Self.formatBytes(bootstrapDownloadBytes)
    }

    public var deferredDownloadText: String {
        Self.formatBytes(deferredDownloadBytes)
    }

    public static func formatBytes(_ bytes: Int64) -> String {
        guard bytes > 0 else { return "0 B" }
        let units: [(threshold: Int64, divisor: Double, suffix: String)] = [
            (1_073_741_824, 1_073_741_824, "GiB"),
            (1_048_576, 1_048_576, "MiB"),
            (1_024, 1_024, "KiB"),
        ]
        guard let unit = units.first(where: { bytes >= $0.threshold }) else {
            return "\(bytes) B"
        }
        return String(
            format: "%.1f %@",
            locale: Locale(identifier: "en_US_POSIX"),
            Double(bytes) / unit.divisor,
            unit.suffix
        )
    }

    private static func row(
        _ decision: DesktopManagedComponentDecision
    ) -> DesktopComponentPreflightPresentationRow {
        let title = titles(decision.requirement.kind)
        let action: DesktopComponentPreflightPresentationAction
        let detailChinese: String
        let detailEnglish: String
        let downloadBytes: Int64
        switch decision.action {
        case .reuse(let candidate):
            downloadBytes = 0
            if candidate.source == .managedStore {
                action = .reuseManaged
                detailChinese = "已验证本机受管组件，将直接复用"
                detailEnglish = "Verified managed component; no download needed"
            } else {
                action = .reuseExternal
                detailChinese = "已验证兼容的系统组件，将直接复用"
                detailEnglish = "Verified compatible system component; no download needed"
            }
        case .download:
            action = .downloadAtInstall
            downloadBytes = decision.requirement.downloadBytes
            detailChinese = "安装前下载并校验 · \(formatBytes(downloadBytes))"
            detailEnglish = "Download and verify before install · \(formatBytes(downloadBytes))"
        case .deferUntilNeeded:
            action = .downloadOnDemand
            downloadBytes = decision.requirement.downloadBytes
            detailChinese = "首次使用时下载并校验 · \(formatBytes(downloadBytes))"
            detailEnglish = "Download and verify on first use · \(formatBytes(downloadBytes))"
        }
        return DesktopComponentPreflightPresentationRow(
            id: decision.requirement.kind,
            titleChinese: title.chinese,
            titleEnglish: title.english,
            detailChinese: detailChinese,
            detailEnglish: detailEnglish,
            action: action,
            downloadBytes: downloadBytes
        )
    }

    private static func titles(
        _ kind: DesktopManagedComponentKind
    ) -> (chinese: String, english: String) {
        switch kind {
        case .pythonRuntime: ("Python 运行环境", "Python runtime")
        case .nodeRuntime: ("Node.js 运行环境", "Node.js runtime")
        case .hermesCore: ("Hermes 核心", "Hermes core")
        case .connector: ("Connector", "Connector")
        case .browserAutomation: ("浏览器自动化", "Browser automation")
        case .speechRuntime: ("语音能力", "Speech capability")
        case .documentTools: ("文档工具", "Document tools")
        }
    }
}

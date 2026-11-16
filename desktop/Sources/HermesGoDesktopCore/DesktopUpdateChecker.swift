import Foundation

public enum DesktopUpdateKind: String, Equatable, Sendable {
    case app
    case managed
}

public enum DesktopUpdateCheckError: Error, Equatable, Sendable {
    /// No update source is configured in this build. This is a build fact, not a network failure.
    case notConfigured
    /// An index could not be fetched, decoded, or validated. Retryable.
    case indexUnavailable
}

public struct DesktopUpdateAvailability: Equatable, Sendable {
    public let isRepair: Bool
    public let kind: DesktopUpdateKind
    public let version: String
    public let releaseNotes: [String]
    public let appReference: DesktopAppUpdateReference?
    public let managedReference: DesktopReleaseIndexReference?
    /// Frozen with discovery so capability refreshes cannot change the executor mid-update.
    public let managedLayout: DesktopManagedReleaseLayoutKind?

    public init(app reference: DesktopAppUpdateReference, repair: Bool = false) {
        isRepair = repair
        kind = .app
        version = reference.appVersion
        releaseNotes = reference.releaseNotes
        appReference = reference
        managedReference = nil
        managedLayout = nil
    }

    /// A configured manifest already inspected by the setup preflight. Installation still verifies
    /// that manifest through the existing executor; this is only presentation/discovery metadata.
    public init(managedVersion: String, layout: DesktopManagedReleaseLayoutKind = .bundledRelease) {
        isRepair = false
        kind = .managed
        version = managedVersion
        releaseNotes = []
        appReference = nil
        managedReference = nil
        managedLayout = layout
    }

    public init(managed reference: DesktopReleaseIndexReference, layout: DesktopManagedReleaseLayoutKind = .bundledRelease) {
        isRepair = false
        kind = .managed
        version = reference.releaseVersion
        releaseNotes = reference.releaseNotes
        appReference = nil
        managedReference = reference
        managedLayout = layout
    }
}

public enum DesktopUpdateSourceState: String, Equatable, Sendable {
    case notConfigured, notChecked, upToDate, available, failed, installationUnknown, repairAvailable

    public var summaryChinese: String {
        switch self {
        case .repairAvailable: "安装身份需要确认修复"
        case .notConfigured: "未配置更新源"
        case .notChecked: "尚未检查"
        case .upToDate: "已是最新版本"
        case .available: "有可用更新"
        case .failed: "HR-DESKUPDATE-001 无法检查，请重试"
        case .installationUnknown: "已检查更新源，尚未确认本机安装"
        }
    }
}

public struct DesktopUpdateReport: Equatable, Sendable {
    public let checkedAt: Date
    public let appUpdate: DesktopUpdateAvailability?
    public let managedUpdate: DesktopUpdateAvailability?
    public let latestManagedReference: DesktopReleaseIndexReference?
    public let appRepairReference: DesktopAppUpdateReference?
    public let appState: DesktopUpdateSourceState
    public let managedState: DesktopUpdateSourceState

    public init(checkedAt: Date, appUpdate: DesktopUpdateAvailability?,
                managedUpdate: DesktopUpdateAvailability?,
                appState: DesktopUpdateSourceState? = nil,
                managedState: DesktopUpdateSourceState? = nil,
                appRepairReference: DesktopAppUpdateReference? = nil,
                latestManagedReference: DesktopReleaseIndexReference? = nil) {
        self.latestManagedReference = latestManagedReference
        self.appRepairReference = appRepairReference
        self.checkedAt = checkedAt
        self.appUpdate = appUpdate
        self.managedUpdate = managedUpdate
        self.appState = appState ?? (appUpdate == nil ? .upToDate : .available)
        self.managedState = managedState ?? (managedUpdate == nil ? .upToDate : .available)
    }

    public func includingPreparedManagedVersion(
        _ target: String?, installed: String?,
        layout: DesktopManagedReleaseLayoutKind = .bundledRelease
    ) -> Self {
        guard managedUpdate == nil, let target, let installed,
              DesktopSemanticVersion.isNewer(target, than: installed) else { return self }
        return Self(checkedAt: checkedAt, appUpdate: appUpdate,
                    managedUpdate: DesktopUpdateAvailability(managedVersion: target, layout: layout),
                    appState: appState, managedState: .available, appRepairReference: appRepairReference, latestManagedReference: latestManagedReference)
    }

    public func afterManagedUpdate() -> Self {
        Self(checkedAt: checkedAt, appUpdate: appUpdate, managedUpdate: nil, appState: appState,
             managedState: .upToDate, appRepairReference: appRepairReference, latestManagedReference: latestManagedReference)
    }

    public func markingManagedIdentity(_ state: DesktopUpdateSourceState) -> Self {
        Self(checkedAt: checkedAt, appUpdate: appUpdate, managedUpdate: managedUpdate,
             appState: appState, managedState: state, appRepairReference: appRepairReference,
             latestManagedReference: latestManagedReference)
    }

    /// Selection is explicit; ordinary discovery never schedules a same-version replacement.
    public func selectingAppRepair() -> Self? {
        guard let appRepairReference else { return nil }
        return Self(checkedAt: checkedAt, appUpdate: DesktopUpdateAvailability(app: appRepairReference, repair: true),
                    managedUpdate: nil, appState: .available, managedState: managedState)
    }

    public var hasUpdates: Bool { appUpdate != nil || managedUpdate != nil }
    public var hasFailures: Bool { appState == .failed || managedState == .failed }
    public var isFullyChecked: Bool {
        [appState, managedState].allSatisfy { $0 == .upToDate || $0 == .available }
    }
}

public struct DesktopUpdateSources: Equatable, Sendable {
    public let appIndexURL: URL?
    public let appChannel: String?
    public let appArchitecture: String?
    public let managedIndexURL: URL?
    public let managedChannel: String?
    public let managedArchitecture: String?
    public let managedLayout: DesktopManagedReleaseLayoutKind

    public init(
        appIndexURL: URL?,
        appChannel: String?,
        appArchitecture: String?,
        managedIndexURL: URL?,
        managedChannel: String?,
        managedArchitecture: String?,
        managedLayout: DesktopManagedReleaseLayoutKind = .bundledRelease
    ) {
        self.appIndexURL = appIndexURL
        self.appChannel = appChannel
        self.appArchitecture = appArchitecture
        self.managedIndexURL = managedIndexURL
        self.managedChannel = managedChannel
        self.managedArchitecture = managedArchitecture
        self.managedLayout = managedLayout
    }
}

/// Compares the stable update indexes with what this Mac has. It only reports what exists; installing
/// an app update is a separate, user-confirmed step, and a managed update still flows through the
/// signed manifest and the existing migration path.
public final class DesktopUpdateChecker: @unchecked Sendable {
    private let downloader: DesktopReleaseDownloader
    private let now: @Sendable () -> Date

    public init(
        downloader: DesktopReleaseDownloader = DesktopReleaseDownloader(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.downloader = downloader
        self.now = now
    }

    public func check(
        sources: DesktopUpdateSources,
        installedAppVersion: String,
        installedAppBuild: Int? = nil,
        appIdentityNeedsRepair: Bool = false,
        installedAppSource: String? = nil,
        installedManagedVersion: String?
    ) async throws -> DesktopUpdateReport {
        guard sources.appIndexURL != nil || sources.managedIndexURL != nil else {
            throw DesktopUpdateCheckError.notConfigured
        }
        // Each request has its own failure boundary; cancellation still cancels the whole check.
        async let app = checkApp(sources, version: installedAppVersion, build: installedAppBuild, needsRepair: appIdentityNeedsRepair, source: installedAppSource)
        async let managed = checkManaged(sources, version: installedManagedVersion)
        let (a, m) = await (app, managed)
        try Task.checkCancellation()
        return DesktopUpdateReport(checkedAt: now(), appUpdate: a.0, managedUpdate: m.0,
                                   appState: a.1, managedState: m.1, appRepairReference: a.2, latestManagedReference: m.2)
    }

    private func checkApp(_ sources: DesktopUpdateSources, version: String, build: Int?, needsRepair: Bool, source: String?) async
        -> (DesktopUpdateAvailability?, DesktopUpdateSourceState, DesktopAppUpdateReference?) {
        guard let url = sources.appIndexURL else { return (nil, .notConfigured, nil) }
        guard let channel = sources.appChannel, let architecture = sources.appArchitecture else {
            return (nil, .failed, nil)
        }
        do {
            let data = try await downloader.fetchManifest(from: url)
            let reference = try DesktopAppUpdateIndex.resolve(data, indexURL: url,
                expectedChannel: channel, expectedArchitecture: architecture)
            let newer = DesktopSemanticVersion.isNewer(reference.appVersion, than: version)
                || (reference.appVersion == version && build.map { reference.buildNumber > $0 } == true)
            let repair = (needsRepair || (source != nil && source != reference.sourceCommit)) && reference.appVersion == version && build == reference.buildNumber ? reference : nil
            return newer ? (DesktopUpdateAvailability(app: reference), .available, nil) : (nil, repair == nil ? .upToDate : .repairAvailable, repair)
        } catch { return (nil, .failed, nil) }
    }

    private func checkManaged(_ sources: DesktopUpdateSources, version: String?) async
        -> (DesktopUpdateAvailability?, DesktopUpdateSourceState, DesktopReleaseIndexReference?) {
        guard let url = sources.managedIndexURL else { return (nil, .notConfigured, nil) }
        guard let channel = sources.managedChannel, let architecture = sources.managedArchitecture else {
            return (nil, .failed, nil)
        }
        guard url.lastPathComponent == "index.json" else { return (nil, .notChecked, nil) }
        do {
            let data = try await downloader.fetchManifest(from: url)
            let reference = try DesktopReleaseIndex.resolve(data, indexURL: url,
                expectedChannel: channel, expectedArchitecture: architecture)
            guard let version else { return (nil, .installationUnknown, reference) }
            return DesktopSemanticVersion.isNewer(reference.releaseVersion, than: version)
                ? (DesktopUpdateAvailability(managed: reference, layout: sources.managedLayout), .available, reference)
                : (nil, .upToDate, reference)
        } catch { return (nil, .failed, nil) }
    }
}

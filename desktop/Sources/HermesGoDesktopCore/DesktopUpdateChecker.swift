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
    public let kind: DesktopUpdateKind
    public let version: String
    public let releaseNotes: [String]
    public let appReference: DesktopAppUpdateReference?
    public let managedReference: DesktopReleaseIndexReference?

    public init(app reference: DesktopAppUpdateReference) {
        kind = .app
        version = reference.appVersion
        releaseNotes = reference.releaseNotes
        appReference = reference
        managedReference = nil
    }

    public init(managed reference: DesktopReleaseIndexReference) {
        kind = .managed
        version = reference.releaseVersion
        releaseNotes = reference.releaseNotes
        appReference = nil
        managedReference = reference
    }
}

public struct DesktopUpdateReport: Equatable, Sendable {
    public let checkedAt: Date
    public let appUpdate: DesktopUpdateAvailability?
    public let managedUpdate: DesktopUpdateAvailability?

    public init(
        checkedAt: Date,
        appUpdate: DesktopUpdateAvailability?,
        managedUpdate: DesktopUpdateAvailability?
    ) {
        self.checkedAt = checkedAt
        self.appUpdate = appUpdate
        self.managedUpdate = managedUpdate
    }

    public var hasUpdates: Bool { appUpdate != nil || managedUpdate != nil }
}

public struct DesktopUpdateSources: Equatable, Sendable {
    public let appIndexURL: URL?
    public let appChannel: String?
    public let appArchitecture: String?
    public let managedIndexURL: URL?
    public let managedChannel: String?
    public let managedArchitecture: String?

    public init(
        appIndexURL: URL?,
        appChannel: String?,
        appArchitecture: String?,
        managedIndexURL: URL?,
        managedChannel: String?,
        managedArchitecture: String?
    ) {
        self.appIndexURL = appIndexURL
        self.appChannel = appChannel
        self.appArchitecture = appArchitecture
        self.managedIndexURL = managedIndexURL
        self.managedChannel = managedChannel
        self.managedArchitecture = managedArchitecture
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
        installedManagedVersion: String?
    ) async throws -> DesktopUpdateReport {
        guard sources.appIndexURL != nil || sources.managedIndexURL != nil else {
            throw DesktopUpdateCheckError.notConfigured
        }

        var appUpdate: DesktopUpdateAvailability?
        if let url = sources.appIndexURL,
           let channel = sources.appChannel,
           let architecture = sources.appArchitecture {
            let data = try await fetchIndex(url)
            let reference: DesktopAppUpdateReference
            do {
                reference = try DesktopAppUpdateIndex.resolve(
                    data,
                    indexURL: url,
                    expectedChannel: channel,
                    expectedArchitecture: architecture
                )
            } catch {
                throw DesktopUpdateCheckError.indexUnavailable
            }
            if DesktopSemanticVersion.isNewer(reference.appVersion, than: installedAppVersion) {
                appUpdate = DesktopUpdateAvailability(app: reference)
            }
        }

        var managedUpdate: DesktopUpdateAvailability?
        if let url = sources.managedIndexURL,
           let channel = sources.managedChannel,
           let architecture = sources.managedArchitecture,
           url.lastPathComponent == "index.json" {
            let data = try await fetchIndex(url)
            let reference: DesktopReleaseIndexReference
            do {
                reference = try DesktopReleaseIndex.resolve(
                    data,
                    indexURL: url,
                    expectedChannel: channel,
                    expectedArchitecture: architecture
                )
            } catch {
                throw DesktopUpdateCheckError.indexUnavailable
            }
            if let installedManagedVersion,
               DesktopSemanticVersion.isNewer(reference.releaseVersion, than: installedManagedVersion) {
                managedUpdate = DesktopUpdateAvailability(managed: reference)
            }
        }

        return DesktopUpdateReport(
            checkedAt: now(),
            appUpdate: appUpdate,
            managedUpdate: managedUpdate
        )
    }

    private func fetchIndex(_ url: URL) async throws -> Data {
        do {
            return try await downloader.fetchManifest(from: url)
        } catch {
            throw DesktopUpdateCheckError.indexUnavailable
        }
    }
}

import CryptoKit
import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopOnDemandComponentInstallerTests: XCTestCase {
    func testFirstUseDownloadsDependencyClosureAndRecordsReferences() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        let downloader = OnDemandDownloader()
        let scanner = OnDemandScanner()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: scanner
        )
        let runID = "11000000-0000-4000-8000-000000000011"

        let result = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: fixture.executableProbe
        )

        let requested = await downloader.requestedKinds()

        XCTAssertEqual(requested, [
            .documentTools, .browserAutomation,
        ])
        XCTAssertEqual(result.components.map(\.kind), [
            .documentTools, .browserAutomation,
        ])
        XCTAssertEqual(result.referenceURLs.count, 2)
        XCTAssertTrue(result.components.allSatisfy {
            if case .managed = $0.location { return true }
            return false
        })
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        let plan = try DesktopManagedComponentGarbageCollectionPlanner(
            root: fixture.store,
            currentUserID: Darwin.getuid()
        ).plan(protectedReleaseVersions: [fixture.manifest.releaseVersion])
        XCTAssertEqual(Set(plan.retained.map(\.kind)), Set([
            .pythonRuntime, .nodeRuntime, .hermesCore, .connector,
            .documentTools, .browserAutomation,
        ]))
    }

    func testSecondUseReusesManagedContentWithoutNetworkOrWorkspace() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        let first = try fixture.installer(
            downloader: OnDemandDownloader(),
            scanner: OnDemandScanner()
        )
        _ = try await first.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "12000000-0000-4000-8000-000000000012",
            healthProbe: fixture.executableProbe
        )
        let downloader = OnDemandDownloader()
        let second = try fixture.installer(
            downloader: downloader,
            scanner: OnDemandScanner()
        )
        let unusedWorkspace = fixture.base.appendingPathComponent(
            "must-not-be-created", isDirectory: true
        )

        let result = try await second.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: unusedWorkspace,
            runID: "13000000-0000-4000-8000-000000000013",
            healthProbe: fixture.executableProbe
        )

        let requested = await downloader.requestedKinds()

        XCTAssertEqual(requested, [])
        XCTAssertEqual(result.referenceURLs.count, 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: unusedWorkspace.path))
    }

    func testCompatibleSystemBrowserIsReusedAfterPathRevalidation() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        try await fixture.installBase()
        let executable = fixture.base.appendingPathComponent("system-chromium")
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: executable)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: executable.path
        )
        let candidate = DesktopManagedComponentCandidate(
            kind: .browserAutomation,
            version: "126.0.6478",
            architecture: "arm64",
            source: .external,
            compatibilityIdentifier: "playwright-system-chromium-v1",
            healthProbePassed: true
        )
        let scanner = OnDemandScanner(result: DesktopExternalEnvironmentScan(
            observations: [DesktopExternalEnvironmentObservation(
                kind: .browserAutomation,
                executableURL: executable,
                version: candidate.version,
                architecture: candidate.architecture,
                status: .reusable,
                candidate: candidate
            )]
        ))
        let downloader = OnDemandDownloader()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: scanner
        )

        let result = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "14000000-0000-4000-8000-000000000014",
            healthProbe: fixture.executableProbe
        )

        let requested = await downloader.requestedKinds()

        XCTAssertEqual(requested, [])
        XCTAssertEqual(result.referenceURLs, [])
        XCTAssertEqual(
            result.components,
            [DesktopResolvedOnDemandComponent(
                kind: .browserAutomation,
                location: .external(executable: executable)
            )]
        )
    }

    func testExternalBrowserSymlinkIsRejectedBeforeNetworkOrWorkspace() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        try await fixture.installBase()
        let executable = fixture.base.appendingPathComponent("real-chromium")
        let symlink = fixture.base.appendingPathComponent("linked-chromium")
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: executable)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: executable.path
        )
        try FileManager.default.createSymbolicLink(
            at: symlink, withDestinationURL: executable
        )
        let candidate = DesktopManagedComponentCandidate(
            kind: .browserAutomation,
            version: "126.0.6478",
            architecture: "arm64",
            source: .external,
            compatibilityIdentifier: "playwright-system-chromium-v1",
            healthProbePassed: true
        )
        let scanner = OnDemandScanner(result: DesktopExternalEnvironmentScan(
            observations: [DesktopExternalEnvironmentObservation(
                kind: .browserAutomation,
                executableURL: symlink,
                version: candidate.version,
                architecture: candidate.architecture,
                status: .reusable,
                candidate: candidate
            )]
        ))
        let downloader = OnDemandDownloader()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: scanner
        )

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "14500000-0000-4000-8000-000000000014",
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual(
                $0 as? DesktopOnDemandComponentInstallError,
                .invalidExternalCandidate
            )
        }
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [])
    }

    func testMissingBaseReferenceStopsBeforeScanWorkspaceOrNetwork() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        try FileManager.default.removeItem(at: fixture.baseReference)
        let downloader = OnDemandDownloader()
        let scanner = OnDemandScanner()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: scanner
        )

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "15000000-0000-4000-8000-000000000015",
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual(
                $0 as? DesktopOnDemandComponentInstallError,
                .missingBaseReleaseReference
            )
        }
        XCTAssertEqual(scanner.scanCount, 0)
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [])
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: fixture.workspace.path),
            []
        )
    }

    func testUnhealthyBaseStopsBeforeScanWorkspaceOrNetwork() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        let downloader = OnDemandDownloader()
        let scanner = OnDemandScanner()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: scanner
        )

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "16000000-0000-4000-8000-000000000016",
            healthProbe: { kind, _, _ in kind != .nodeRuntime }
        )) {
            XCTAssertEqual(
                $0 as? DesktopOnDemandComponentInstallError,
                .componentHealthProbeFailed(.nodeRuntime)
            )
        }
        XCTAssertEqual(scanner.scanCount, 0)
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [])
        XCTAssertEqual(
            try FileManager.default.contentsOfDirectory(atPath: fixture.workspace.path),
            []
        )
    }

    func testInterruptedDownloadIsBoundToManifestTriggerAndCanResume() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        let downloader = OnDemandDownloader(failOnceFor: .documentTools)
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: OnDemandScanner()
        )
        let runID = "17000000-0000-4000-8000-000000000017"
        let runRoot = fixture.workspace.appendingPathComponent(runID)

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual($0 as? DesktopComponentDownloadError, .transportFailed)
        }
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: runRoot.appendingPathComponent("capability-install.json").path
        ))

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "document.use",
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual(
                $0 as? DesktopOnDemandComponentInstallError,
                .workspaceAlreadyExists
            )
        }

        let result = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: fixture.executableProbe
        )
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [
            .documentTools, .documentTools, .browserAutomation,
        ])
        XCTAssertEqual(result.referenceURLs.count, 2)
        XCTAssertFalse(FileManager.default.fileExists(atPath: runRoot.path))
    }

    func testExtractionFailureCleansWorkspaceAndPublishesNoCapabilityReference() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        let downloader = OnDemandDownloader()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: OnDemandScanner(),
            failExtractionFor: .documentTools
        )
        let runID = "17500000-0000-4000-8000-000000000017"

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual($0 as? OnDemandFixtureError, .extractionFailed)
        }
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.workspace.appendingPathComponent(runID).path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: fixture.store.appendingPathComponent("capability-references").path
        ))
    }

    func testInterruptedWorkspaceCanBeDiscardedOnlyWithAValidMarker() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        try await fixture.installBase()
        let installer = try fixture.installer(
            downloader: OnDemandDownloader(failOnceFor: .documentTools),
            scanner: OnDemandScanner()
        )
        let runID = "17700000-0000-4000-8000-000000000017"
        let runRoot = fixture.workspace.appendingPathComponent(runID)
        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: runID,
            healthProbe: fixture.executableProbe
        )) { _ in }
        let marker = runRoot.appendingPathComponent("capability-install.json")
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o644], ofItemAtPath: marker.path
        )
        XCTAssertThrowsError(try installer.discardInterruptedInstall(
            workspaceRoot: fixture.workspace,
            runID: runID
        )) {
            XCTAssertEqual(
                $0 as? DesktopOnDemandComponentInstallError,
                .invalidWorkspace
            )
        }
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: marker.path
        )

        try installer.discardInterruptedInstall(
            workspaceRoot: fixture.workspace,
            runID: runID
        )

        XCTAssertFalse(FileManager.default.fileExists(atPath: runRoot.path))
        XCTAssertNoThrow(try installer.discardInterruptedInstall(
            workspaceRoot: fixture.workspace,
            runID: runID
        ))
    }

    func testUnknownTriggerIsInert() async throws {
        let fixture = try OnDemandFixture()
        defer { fixture.remove() }
        let downloader = OnDemandDownloader()
        let scanner = OnDemandScanner()
        let installer = try fixture.installer(
            downloader: downloader,
            scanner: scanner
        )

        await assertAsyncError(try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "unknown.use",
            workspaceRoot: fixture.workspace,
            runID: "18000000-0000-4000-8000-000000000018",
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual(
                $0 as? DesktopOnDemandComponentInstallError,
                .unknownTrigger
            )
        }
        XCTAssertEqual(scanner.scanCount, 0)
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.workspace.path))
    }

    func testActiveResolverKeepsReferencedDocumentWhenBrowserIsInstalledLater() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        try await fixture.installBase()
        let installer = try fixture.installer(
            downloader: OnDemandDownloader(), scanner: OnDemandScanner()
        )
        _ = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "document.use",
            workspaceRoot: fixture.workspace,
            runID: "19000000-0000-4000-8000-000000000019",
            healthProbe: fixture.executableProbe
        )
        let browser = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "19100000-0000-4000-8000-000000000019",
            healthProbe: fixture.executableProbe
        )
        let resolver = try DesktopActiveOnDemandComponentResolver(
            verifiedManifest: fixture.verifiedManifest,
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid(),
            externalScanner: OnDemandScanner()
        )

        let resolved = try resolver.resolve(
            installed: browser,
            hermesLaunchAgentURL: fixture.base.appendingPathComponent("unused.plist"),
            healthProbe: fixture.executableProbe
        )

        XCTAssertEqual(resolved.map(\.kind), [.browserAutomation, .documentTools])
    }

    func testActiveResolverRetainsOnlyFreshlyRevalidatedExternalBrowser() async throws {
        let fixture = try OnDemandFixture(browserDependsOnDocument: false)
        defer { fixture.remove() }
        try await fixture.installBase()
        let browser = fixture.base.appendingPathComponent("system-chromium")
        try Data("#!/bin/sh\nexit 0\n".utf8).write(to: browser)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: browser.path
        )
        let candidate = DesktopManagedComponentCandidate(
            kind: .browserAutomation,
            version: "126.0.6478",
            architecture: "arm64",
            source: .external,
            compatibilityIdentifier: "playwright-system-chromium-v1",
            healthProbePassed: true
        )
        let scan = DesktopExternalEnvironmentScan(observations: [
            DesktopExternalEnvironmentObservation(
                kind: .browserAutomation,
                executableURL: browser,
                version: candidate.version,
                architecture: candidate.architecture,
                status: .reusable,
                candidate: candidate
            ),
        ])
        let installer = try fixture.installer(
            downloader: OnDemandDownloader(), scanner: OnDemandScanner(result: scan)
        )
        _ = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "browser.use",
            workspaceRoot: fixture.workspace,
            runID: "19200000-0000-4000-8000-000000000019",
            healthProbe: fixture.executableProbe
        )
        let document = try await installer.install(
            verifiedManifest: fixture.verifiedManifest,
            trigger: "document.use",
            workspaceRoot: fixture.workspace,
            runID: "19300000-0000-4000-8000-000000000019",
            healthProbe: fixture.executableProbe
        )
        let launchAgent = fixture.base.appendingPathComponent("hermes.plist")
        let plist = try PropertyListSerialization.data(
            fromPropertyList: [
                "Label": "com.hermes.test",
                "EnvironmentVariables": [
                    "AGENT_BROWSER_EXECUTABLE_PATH": browser.path,
                ],
            ],
            format: .xml,
            options: 0
        )
        try plist.write(to: launchAgent)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: launchAgent.path
        )
        let resolver = try DesktopActiveOnDemandComponentResolver(
            verifiedManifest: fixture.verifiedManifest,
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid(),
            externalScanner: OnDemandScanner(result: scan)
        )

        let resolved = try resolver.resolve(
            installed: document,
            hermesLaunchAgentURL: launchAgent,
            healthProbe: fixture.executableProbe
        )

        XCTAssertEqual(resolved, [
            DesktopResolvedOnDemandComponent(
                kind: .browserAutomation,
                location: .external(executable: browser)
            ),
            document.components[0],
        ])

        let staleResolver = try DesktopActiveOnDemandComponentResolver(
            verifiedManifest: fixture.verifiedManifest,
            storeRoot: fixture.store,
            currentUserID: Darwin.getuid(),
            externalScanner: OnDemandScanner()
        )
        XCTAssertThrowsError(try staleResolver.resolve(
            installed: document,
            hermesLaunchAgentURL: launchAgent,
            healthProbe: fixture.executableProbe
        )) {
            XCTAssertEqual(
                $0 as? DesktopActiveOnDemandComponentResolutionError,
                .invalidExternalCandidate
            )
        }
    }
}

private func assertAsyncError<T>(
    _ expression: @autoclosure () async throws -> T,
    _ errorHandler: (Error) -> Void
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        errorHandler(error)
    }
}

private actor OnDemandDownloader: DesktopComponentReleaseDownloading {
    private var requested: [DesktopManagedComponentKind] = []
    private let failOnceKind: DesktopManagedComponentKind?
    private var didFail = false

    init(failOnceFor kind: DesktopManagedComponentKind? = nil) {
        failOnceKind = kind
    }

    func download(
        _ component: DesktopComponentReleaseArtifactV2,
        into downloadRoot: URL
    ) async throws -> URL {
        requested.append(component.kind)
        if component.kind == failOnceKind, !didFail {
            didFail = true
            let partial = downloadRoot.appendingPathComponent(
                ".\(component.fileName).partial"
            )
            try Data("partial".utf8).write(to: partial)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o600], ofItemAtPath: partial.path
            )
            throw DesktopComponentDownloadError.transportFailed
        }
        let destination = downloadRoot.appendingPathComponent(component.fileName)
        try Data(component.kind.rawValue.utf8).write(to: destination)
        return destination
    }

    func requestedKinds() -> [DesktopManagedComponentKind] {
        requested
    }
}

private enum OnDemandFixtureError: Error, Equatable {
    case extractionFailed
}

private final class OnDemandExtractor: DesktopComponentArchiveExtracting {
    private let sources: [DesktopManagedComponentKind: URL]
    private let failedKind: DesktopManagedComponentKind?

    init(
        sources: [DesktopManagedComponentKind: URL],
        failedKind: DesktopManagedComponentKind? = nil
    ) {
        self.sources = sources
        self.failedKind = failedKind
    }

    func extractComponent(
        archive: URL,
        metadata: DesktopComponentReleaseArtifactV2,
        into destinationRoot: URL,
        runID: String
    ) throws -> URL {
        guard metadata.kind != failedKind else {
            throw OnDemandFixtureError.extractionFailed
        }
        let destination = destinationRoot.appendingPathComponent(
            "\(runID.lowercased())-\(metadata.kind.rawValue)",
            isDirectory: true
        )
        try FileManager.default.copyItem(at: sources[metadata.kind]!, to: destination)
        return destination
    }
}

private final class OnDemandScanner: DesktopExternalEnvironmentScanning {
    private let result: DesktopExternalEnvironmentScan
    private(set) var scanCount = 0

    init(result: DesktopExternalEnvironmentScan = .init(observations: [])) {
        self.result = result
    }

    func scan(
        requirements: [DesktopManagedComponentRequirement]
    ) -> DesktopExternalEnvironmentScan {
        scanCount += 1
        return result
    }
}

private final class OnDemandFixture {
    let base: URL
    let workspace: URL
    let store: URL
    let manifest: DesktopComponentReleaseManifestV2
    let verifiedManifest: VerifiedDesktopComponentReleaseManifestV2
    let sources: [DesktopManagedComponentKind: URL]

    var baseReference: URL {
        store.appendingPathComponent("references/\(manifest.releaseVersion).json")
    }

    var executableProbe: DesktopOnDemandComponentInstaller.HealthProbe {
        { _, root, entrypoint in
            FileManager.default.fileExists(atPath: root.path)
                && FileManager.default.isExecutableFile(atPath: entrypoint.path)
        }
    }

    init(browserDependsOnDocument: Bool = true) throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(
            "hermes-on-demand-\(UUID().uuidString)", isDirectory: true
        )
        workspace = base.appendingPathComponent("workspace", isDirectory: true)
        store = base.appendingPathComponent("managed", isDirectory: true)
        try FileManager.default.createDirectory(
            at: base, withIntermediateDirectories: false
        )
        let entrypoints: [DesktopManagedComponentKind: String] = [
            .pythonRuntime: "bin/python3",
            .nodeRuntime: "bin/node",
            .hermesCore: "bin/hermes",
            .connector: "bin/hermes-connector",
            .documentTools: "bin/document-tools",
            .browserAutomation: "bin/chromium",
        ]
        var builtSources: [DesktopManagedComponentKind: URL] = [:]
        var hashes: [DesktopManagedComponentKind: String] = [:]
        for (kind, relative) in entrypoints {
            let source = base.appendingPathComponent(
                "source-\(kind.rawValue)", isDirectory: true
            )
            let entrypoint = source.appendingPathComponent(relative)
            try FileManager.default.createDirectory(
                at: entrypoint.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try Data("#!/bin/sh\nexit 0\n".utf8).write(to: entrypoint)
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o700], ofItemAtPath: entrypoint.path
            )
            builtSources[kind] = source
            hashes[kind] = try DesktopManagedComponentContentHasher()
                .identify(directory: source).sha256
        }
        sources = builtSources

        let python = Self.artifact(
            kind: .pythonRuntime,
            entrypoint: entrypoints[.pythonRuntime]!,
            contentSHA256: hashes[.pythonRuntime]!,
            dependencies: []
        )
        let node = Self.artifact(
            kind: .nodeRuntime,
            entrypoint: entrypoints[.nodeRuntime]!,
            contentSHA256: hashes[.nodeRuntime]!,
            dependencies: []
        )
        let hermes = Self.artifact(
            kind: .hermesCore,
            entrypoint: entrypoints[.hermesCore]!,
            contentSHA256: hashes[.hermesCore]!,
            dependencies: [.init(
                kind: .pythonRuntime,
                contentSHA256: hashes[.pythonRuntime]!
            )]
        )
        let connector = Self.artifact(
            kind: .connector,
            entrypoint: entrypoints[.connector]!,
            contentSHA256: hashes[.connector]!,
            dependencies: [
                .init(
                    kind: .hermesCore,
                    contentSHA256: hashes[.hermesCore]!
                ),
                .init(
                    kind: .nodeRuntime,
                    contentSHA256: hashes[.nodeRuntime]!
                ),
            ]
        )
        let document = Self.artifact(
            kind: .documentTools,
            entrypoint: entrypoints[.documentTools]!,
            contentSHA256: hashes[.documentTools]!,
            installPhase: .onDemand,
            trigger: "document.use",
            dependencies: [.init(
                kind: .pythonRuntime,
                contentSHA256: hashes[.pythonRuntime]!
            )]
        )
        let browserDependencies: [DesktopComponentReleaseDependency]
        if browserDependsOnDocument {
            browserDependencies = [.init(
                kind: .documentTools,
                contentSHA256: hashes[.documentTools]!
            )]
        } else {
            browserDependencies = [.init(
                kind: .nodeRuntime,
                contentSHA256: hashes[.nodeRuntime]!
            )]
        }
        let browser = Self.artifact(
            kind: .browserAutomation,
            entrypoint: entrypoints[.browserAutomation]!,
            contentSHA256: hashes[.browserAutomation]!,
            installPhase: .onDemand,
            trigger: "browser.use",
            reuseContract: .verifiedCompatibility,
            compatibilityIdentifier: "playwright-system-chromium-v1",
            dependencies: browserDependencies
        )
        let builtManifest = DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.1",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: [browser, connector, document, hermes, node, python]
        )
        manifest = builtManifest
        verifiedManifest = try Self.verify(builtManifest)
    }

    func installBase() async throws {
        let downloader = OnDemandDownloader()
        let installer = try DesktopComponentReleaseInstaller(
            storeRoot: store,
            currentUserID: Darwin.getuid(),
            downloader: downloader,
            extractor: OnDemandExtractor(sources: sources)
        )
        let installed = try await installer.install(
            verifiedManifest: verifiedManifest,
            workspaceRoot: workspace,
            runID: UUID().uuidString,
            healthProbe: executableProbe
        )
        try installer.discard(installed)
        let requested = await downloader.requestedKinds()
        XCTAssertEqual(requested, [
            .pythonRuntime, .nodeRuntime, .hermesCore, .connector,
        ])
    }

    func installer(
        downloader: OnDemandDownloader,
        scanner: OnDemandScanner,
        failExtractionFor failedKind: DesktopManagedComponentKind? = nil
    ) throws -> DesktopOnDemandComponentInstaller {
        try DesktopOnDemandComponentInstaller(
            storeRoot: store,
            currentUserID: Darwin.getuid(),
            downloader: downloader,
            extractor: OnDemandExtractor(
                sources: sources,
                failedKind: failedKind
            ),
            externalScanner: scanner
        )
    }

    func remove() {
        try? FileManager.default.removeItem(at: base)
    }

    private static func artifact(
        kind: DesktopManagedComponentKind,
        entrypoint: String,
        contentSHA256: String,
        installPhase: DesktopManagedComponentInstallPhase = .bootstrap,
        trigger: String? = nil,
        reuseContract: DesktopComponentReuseContract = .exactContent,
        compatibilityIdentifier: String? = nil,
        dependencies: [DesktopComponentReleaseDependency]
    ) -> DesktopComponentReleaseArtifactV2 {
        let fileName = "Hermes-Component-\(kind.rawValue)-1.2.3-arm64.tar.gz"
        return DesktopComponentReleaseArtifactV2(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            installPhase: installPhase,
            requiredForBootstrap: installPhase == .bootstrap,
            onDemandTrigger: trigger,
            reuseContract: reuseContract,
            compatibilityIdentifier: compatibilityIdentifier,
            fileName: fileName,
            entrypoint: entrypoint,
            downloadURL: "https://downloads.example/desktop/components/\(fileName)",
            sizeBytes: 1,
            sha256: SHA256.hash(data: Data(kind.rawValue.utf8))
                .map { String(format: "%02x", $0) }.joined(),
            contentSHA256: contentSHA256,
            dependencies: dependencies
        )
    }

    private static func verify(
        _ manifest: DesktopComponentReleaseManifestV2
    ) throws -> VerifiedDesktopComponentReleaseManifestV2 {
        let signer = Curve25519.Signing.PrivateKey()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let payload = try encoder.encode(manifest)
        let signature = try signer.signature(for: payload)
        let envelope = try JSONSerialization.data(withJSONObject: [
            "algorithm": "Ed25519",
            "keyId": "test-key",
            "payload": base64URL(payload),
            "signature": base64URL(signature),
        ], options: [.sortedKeys])
        let now = ISO8601DateFormatter().date(
            from: "2026-09-10T00:00:00Z"
        )!
        let verifier = try DesktopComponentReleaseManifestV2Verifier(
            expectedOrigin: URL(string: "https://downloads.example")!,
            expectedChannel: "internal",
            expectedArchitecture: "arm64",
            currentMacOS: OperatingSystemVersion(
                majorVersion: 14,
                minorVersion: 8,
                patchVersion: 0
            ),
            signingKeys: ["test-key": signer.publicKey.rawRepresentation],
            now: { now }
        )
        return try verifier.verifyForInstallation(envelope)
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

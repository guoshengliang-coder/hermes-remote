import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedBootstrapRuntimeTests: XCTestCase {
    func testCurrentUserPathsAreDeterministicAndSeparatedByPurpose() throws {
        let paths = try DesktopManagedBootstrapPaths(
            homeDirectory: URL(fileURLWithPath: "/Users/tester")
        )

        XCTAssertEqual(
            paths.managedRoot.path,
            "/Users/tester/Library/Application Support/Hermes Go/Managed"
        )
        XCTAssertEqual(
            paths.workspaceRoot.path,
            "/Users/tester/Library/Caches/com.hermesgo.desktop-managed-bootstrap"
        )
        XCTAssertEqual(paths.migrationJournalRoot.path, paths.managedRoot.appendingPathComponent("state").path)
        XCTAssertEqual(paths.launchAgentsRoot.path, "/Users/tester/Library/LaunchAgents")
        XCTAssertEqual(paths.hermesHome.path, "/Users/tester/.hermes")
        XCTAssertThrowsError(try DesktopManagedBootstrapPaths(homeDirectory: URL(fileURLWithPath: "/")))
    }

    func testRuntimeCompositionDoesNotCreateManagedFiles() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: root) }
        let paths = try DesktopManagedBootstrapPaths(homeDirectory: root)
        let configuration = try DesktopManagedBootstrapConfiguration(
            manifestURL: URL(string: "https://downloads.example/desktop/manifest.json")!,
            artifactOrigin: URL(string: "https://downloads.example")!,
            channel: "internal",
            architecture: "arm64",
            signingKeyID: "desktop-2026",
            signingPublicKey: Data(repeating: 7, count: 32),
            runtimeContract: .serveV1
        )

        let runtime = try DesktopManagedBootstrapRuntime(
            releaseConfiguration: configuration,
            accountGatewayURL: URL(string: "https://gateway.example")!,
            account: RuntimeAccountFake(),
            paths: paths,
            userID: 501
        )
        _ = try DesktopManagedRecoveryRuntime(
            account: RuntimeAccountFake(),
            paths: paths,
            userID: 501
        )

        XCTAssertEqual(runtime.manifestURL, configuration.manifestURL)
        XCTAssertEqual(runtime.workspaceRoot, paths.workspaceRoot)
        XCTAssertEqual(
            runtime.commitConfiguration.gatewayWebSocketURL.absoluteString,
            "wss://gateway.example/v2/connect"
        )
        XCTAssertFalse(FileManager.default.fileExists(atPath: paths.managedRoot.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: paths.workspaceRoot.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: paths.launchAgentsRoot.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: paths.hermesHome.path))
    }

    func testInstallationStatusRequiresJournalAndBothExactManagedServices() {
        let active = journal(state: .accountActive)
        XCTAssertEqual(
            DesktopManagedBootstrapInstallationStatus.reduce(
                journal: active,
                services: DesktopLaunchAgentServiceState(
                    legacyLoaded: false,
                    accountLoaded: true,
                    hermesLoaded: true
                )
            ),
            .active(
                releaseVersion: "1.2.3",
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 1
            )
        )
        XCTAssertEqual(
            DesktopManagedBootstrapInstallationStatus.reduce(
                journal: active,
                services: DesktopLaunchAgentServiceState(
                    legacyLoaded: false,
                    accountLoaded: true,
                    hermesLoaded: false
                )
            ),
            .inconsistent
        )
        XCTAssertEqual(
            DesktopManagedBootstrapInstallationStatus.reduce(
                journal: nil,
                services: DesktopLaunchAgentServiceState(
                    legacyLoaded: false,
                    accountLoaded: false,
                    hermesLoaded: true
                )
            ),
            .inconsistent
        )
    }

    func testIntermediateJournalRequiresRecoveryBeforeAnotherInstall() {
        XCTAssertEqual(
            DesktopManagedBootstrapInstallationStatus.reduce(
                journal: journal(state: .commitPending),
                services: DesktopLaunchAgentServiceState(
                    legacyLoaded: false,
                    accountLoaded: true,
                    hermesLoaded: true
                )
            ),
            .interrupted(
                runID: "60000000-0000-4000-8000-000000000006",
                state: .commitPending
            )
        )
    }

    func testActiveInstallationMustMatchTheCurrentAccountsBindingGeneration() {
        let active = DesktopManagedBootstrapInstallationStatus.active(
            releaseVersion: "1.2.3",
            bindingID: "70000000-0000-4000-8000-000000000007",
            bindingGeneration: 2
        )

        XCTAssertEqual(
            active.scopedToCurrentAccount(
                bindingID: "70000000-0000-4000-8000-000000000007",
                bindingGeneration: 2
            ),
            active
        )
        XCTAssertEqual(
            active.scopedToCurrentAccount(
                bindingID: "80000000-0000-4000-8000-000000000008",
                bindingGeneration: 1
            ),
            .inconsistent
        )
        XCTAssertEqual(
            active.scopedToCurrentAccount(bindingID: nil, bindingGeneration: nil),
            .inconsistent
        )
    }

    private func journal(state: DesktopMigrationState) -> DesktopMigrationJournal {
        DesktopMigrationJournal(
            runID: "60000000-0000-4000-8000-000000000006",
            state: state,
            lastKnownGoodMode: .none,
            releaseVersion: "1.2.3",
            bindingID: "70000000-0000-4000-8000-000000000007",
            bindingGeneration: 1,
            updatedAt: "2026-09-08T00:00:00.000Z"
        )
    }
}

private struct RuntimeAccountFake: DesktopBindingCoordinating {
    func beginBinding(
        retryingTerminalBindingID: String?,
        retryingTerminalGeneration: Int?
    ) async throws -> DesktopBindingPreparation {
        fatalError("must stay inert")
    }
    func refresh() async throws -> DesktopAccountState { fatalError("must stay inert") }
    func confirmBinding() async throws -> DesktopAccountState { fatalError("must stay inert") }
}

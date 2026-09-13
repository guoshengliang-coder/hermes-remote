import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentPreflightTests: XCTestCase {
    private let pythonDigest = String(repeating: "a", count: 64)

    func testReusesExactManagedRuntimeAndDownloadsOnlyMissingBootstrapComponents() throws {
        let python = requirement(
            .pythonRuntime,
            bytes: 60_000_000,
            reuse: .exactContent(sha256: pythonDigest)
        )
        let server = requirement(
            .hermesCore,
            bytes: 8_000_000,
            reuse: .exactContent(sha256: String(repeating: "b", count: 64))
        )
        let candidate = DesktopManagedComponentCandidate(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            source: .managedStore,
            contentSHA256: pythonDigest,
            healthProbePassed: true
        )

        let plan = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [python, server],
            candidates: [candidate]
        )

        XCTAssertEqual(plan.decisions.map(\.action), [.reuse(candidate), .download])
        XCTAssertEqual(plan.bootstrapDownloadBytes, 8_000_000)
        XCTAssertEqual(plan.deferredDownloadBytes, 0)
    }

    func testVersionStringWithoutExactIdentityNeverReusesMutablePythonEnvironment() throws {
        let python = requirement(
            .pythonRuntime,
            bytes: 60_000_000,
            reuse: .exactContent(sha256: pythonDigest)
        )
        let homebrewPython = DesktopManagedComponentCandidate(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            source: .external,
            healthProbePassed: true
        )

        let plan = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [python],
            candidates: [homebrewPython]
        )

        XCTAssertEqual(plan.decisions.map(\.action), [.download])
    }

    func testCompatibleHealthySystemBrowserCanBeReused() throws {
        let browser = requirement(
            .browserAutomation,
            bytes: 130_000_000,
            phase: .onDemand,
            reuse: .verifiedCompatibility(identifier: "playwright-chromium-cdp-v1")
        )
        let chrome = DesktopManagedComponentCandidate(
            kind: .browserAutomation,
            version: "123.0.0",
            architecture: "universal",
            source: .external,
            compatibilityIdentifier: "playwright-chromium-cdp-v1",
            healthProbePassed: true
        )

        let plan = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [browser],
            candidates: [chrome]
        )

        XCTAssertEqual(plan.decisions.map(\.action), [.reuse(chrome)])
        XCTAssertEqual(plan.bootstrapDownloadBytes, 0)
        XCTAssertEqual(plan.deferredDownloadBytes, 0)
    }

    func testMissingOptionalCapabilitiesAreDeferred() throws {
        let browser = requirement(
            .browserAutomation,
            bytes: 130_000_000,
            phase: .onDemand,
            reuse: .verifiedCompatibility(identifier: "playwright-chromium-cdp-v1")
        )

        let plan = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [browser],
            candidates: []
        )

        XCTAssertEqual(plan.decisions.map(\.action), [.deferUntilNeeded])
        XCTAssertEqual(plan.bootstrapDownloadBytes, 0)
        XCTAssertEqual(plan.deferredDownloadBytes, 130_000_000)
    }

    func testWrongArchitectureFailedProbeAndDigestMismatchCannotBeReused() throws {
        let python = requirement(
            .pythonRuntime,
            bytes: 60_000_000,
            reuse: .exactContent(sha256: pythonDigest)
        )
        let candidates = [
            DesktopManagedComponentCandidate(
                kind: .pythonRuntime,
                version: "3.11.15",
                architecture: "x86_64",
                source: .managedStore,
                contentSHA256: pythonDigest,
                healthProbePassed: true
            ),
            DesktopManagedComponentCandidate(
                kind: .pythonRuntime,
                version: "3.11.15",
                architecture: "arm64",
                source: .managedStore,
                contentSHA256: pythonDigest,
                healthProbePassed: false
            ),
            DesktopManagedComponentCandidate(
                kind: .pythonRuntime,
                version: "3.11.15",
                architecture: "arm64",
                source: .managedStore,
                contentSHA256: String(repeating: "c", count: 64),
                healthProbePassed: true
            ),
        ]

        let plan = try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [python],
            candidates: candidates
        )

        XCTAssertEqual(plan.decisions.map(\.action), [.download])
    }

    func testDuplicateAndMalformedRequirementsFailBeforePlanning() {
        let valid = requirement(
            .connector,
            bytes: 5_000_000,
            reuse: .exactContent(sha256: String(repeating: "d", count: 64))
        )
        XCTAssertThrowsError(try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [valid, valid],
            candidates: []
        )) { error in
            XCTAssertEqual(error as? DesktopManagedComponentPreflightError, .duplicateRequirement)
        }

        let malformed = DesktopManagedComponentRequirement(
            kind: .connector,
            version: "latest",
            architecture: "arm64",
            downloadBytes: 0,
            installPhase: .bootstrap,
            reusePolicy: .exactContent(sha256: "bad")
        )
        XCTAssertThrowsError(try DesktopManagedComponentPreflightPlanner.plan(
            requirements: [malformed],
            candidates: []
        )) { error in
            XCTAssertEqual(error as? DesktopManagedComponentPreflightError, .invalidRequirement)
        }
    }

    private func requirement(
        _ kind: DesktopManagedComponentKind,
        bytes: Int64,
        phase: DesktopManagedComponentInstallPhase = .bootstrap,
        reuse: DesktopManagedComponentReusePolicy
    ) -> DesktopManagedComponentRequirement {
        DesktopManagedComponentRequirement(
            kind: kind,
            version: kind == .pythonRuntime ? "3.11.15" : "1.2.3",
            architecture: "arm64",
            downloadBytes: bytes,
            installPhase: phase,
            reusePolicy: reuse
        )
    }
}

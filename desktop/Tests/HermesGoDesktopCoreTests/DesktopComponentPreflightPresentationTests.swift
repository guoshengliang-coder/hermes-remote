import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopComponentPreflightPresentationTests: XCTestCase {
    func testMapsReuseBootstrapAndDeferredDecisionsToBilingualRows() {
        let managed = candidate(.pythonRuntime, source: .managedStore)
        let external = candidate(.browserAutomation, source: .external)
        let decisions = [
            decision(.pythonRuntime, bytes: 45_000_000, action: .reuse(managed)),
            decision(.nodeRuntime, bytes: 36_000_000, action: .download),
            decision(.browserAutomation, bytes: 130_000_000, action: .reuse(external)),
            decision(.speechRuntime, bytes: 80_000_000, phase: .onDemand, action: .deferUntilNeeded),
        ]
        let result = DesktopComponentReleasePreflightResult(
            manifest: manifest(),
            plan: DesktopManagedComponentPreflightPlan(decisions: decisions),
            externalEnvironment: .init(observations: [])
        )

        let presentation = DesktopComponentPreflightPresentation(result: result)

        XCTAssertEqual(presentation.releaseVersion, "0.4.3")
        XCTAssertEqual(presentation.reusedComponentCount, 2)
        XCTAssertEqual(presentation.bootstrapDownloadBytes, 36_000_000)
        XCTAssertEqual(presentation.deferredDownloadBytes, 80_000_000)
        XCTAssertEqual(presentation.bootstrapDownloadText, "34.3 MiB")
        XCTAssertEqual(presentation.deferredDownloadText, "76.3 MiB")
        XCTAssertEqual(presentation.rows.map(\.action), [
            .reuseManaged, .downloadAtInstall, .reuseExternal, .downloadOnDemand,
        ])
        XCTAssertEqual(presentation.rows.map(\.titleChinese), [
            "Python 运行环境", "Node.js 运行环境", "浏览器自动化", "语音能力",
        ])
        XCTAssertEqual(presentation.rows.map(\.titleEnglish), [
            "Python runtime", "Node.js runtime", "Browser automation", "Speech capability",
        ])
        XCTAssertTrue(presentation.rows[0].detailChinese.contains("受管组件"))
        XCTAssertTrue(presentation.rows[1].detailEnglish.contains("34.3 MiB"))
        XCTAssertTrue(presentation.rows[2].detailChinese.contains("系统组件"))
        XCTAssertTrue(presentation.rows[3].detailEnglish.contains("76.3 MiB"))
    }

    func testNamesEverySupportedComponentAndFormatsByteBoundaries() {
        let decisions = DesktopManagedComponentKind.allCases.map {
            decision($0, bytes: 1, action: .download)
        }
        let presentation = DesktopComponentPreflightPresentation(result: .init(
            manifest: manifest(),
            plan: .init(decisions: decisions),
            externalEnvironment: .init(observations: [])
        ))

        XCTAssertEqual(Set(presentation.rows.map(\.titleChinese)), Set([
            "Python 运行环境", "Node.js 运行环境", "Hermes 核心", "Connector",
            "浏览器自动化", "语音能力", "文档工具",
        ]))
        XCTAssertEqual(DesktopComponentPreflightPresentation.formatBytes(0), "0 B")
        XCTAssertEqual(DesktopComponentPreflightPresentation.formatBytes(1_023), "1023 B")
        XCTAssertEqual(DesktopComponentPreflightPresentation.formatBytes(1_024), "1.0 KiB")
        XCTAssertEqual(DesktopComponentPreflightPresentation.formatBytes(1_048_576), "1.0 MiB")
        XCTAssertEqual(
            DesktopComponentPreflightPresentation.formatBytes(1_073_741_824), "1.0 GiB"
        )
    }

    private func decision(
        _ kind: DesktopManagedComponentKind,
        bytes: Int64,
        phase: DesktopManagedComponentInstallPhase = .bootstrap,
        action: DesktopManagedComponentAction
    ) -> DesktopManagedComponentDecision {
        DesktopManagedComponentDecision(
            requirement: DesktopManagedComponentRequirement(
                kind: kind,
                version: "1.2.3",
                architecture: "arm64",
                downloadBytes: bytes,
                installPhase: phase,
                reusePolicy: .exactContent(sha256: String(repeating: "a", count: 64))
            ),
            action: action
        )
    }

    private func candidate(
        _ kind: DesktopManagedComponentKind,
        source: DesktopManagedComponentCandidateSource
    ) -> DesktopManagedComponentCandidate {
        DesktopManagedComponentCandidate(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            source: source,
            contentSHA256: source == .managedStore ? String(repeating: "a", count: 64) : nil,
            compatibilityIdentifier: source == .external ? "system-contract-v1" : nil,
            healthProbePassed: true
        )
    }

    private func manifest() -> DesktopComponentReleaseManifestV2 {
        DesktopComponentReleaseManifestV2(
            releaseVersion: "0.4.3",
            channel: "internal",
            architecture: "arm64",
            minimumMacOS: "14.0",
            createdAt: "2026-09-01T00:00:00Z",
            expiresAt: "2026-09-20T00:00:00Z",
            components: []
        )
    }
}

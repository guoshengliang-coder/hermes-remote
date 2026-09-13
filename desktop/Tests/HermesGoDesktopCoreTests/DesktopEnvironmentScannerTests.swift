import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopEnvironmentScannerTests: XCTestCase {
    func testPythonAndNodeAreReportedButNeverReusedFromVersionAlone() throws {
        let fixture = try ScannerFixture()
        defer { fixture.remove() }
        let python = try fixture.executable("python3")
        let node = try fixture.executable("node")
        let runner = EnvironmentFixtureRunner(outputs: [
            python.path: "Python 3.11.15\n",
            node.path: "v22.23.2\n",
        ])
        let scan = DesktopExternalEnvironmentScanner(
            paths: [
                .init(kind: .pythonRuntime, executableURL: python),
                .init(kind: .nodeRuntime, executableURL: node),
            ],
            runner: runner,
            currentUserID: Darwin.getuid()
        ).scan(requirements: [
            requirement(.pythonRuntime, version: "3.11.15", reuse: .exactContent(
                sha256: String(repeating: "a", count: 64)
            )),
            requirement(.nodeRuntime, version: "22.23.2", reuse: .exactContent(
                sha256: String(repeating: "b", count: 64)
            )),
        ])

        XCTAssertEqual(scan.observations.map(\.status), [.detected, .detected])
        XCTAssertEqual(scan.observations.map(\.version), ["3.11.15", "22.23.2"])
        XCTAssertEqual(scan.reusableCandidates, [])
    }

    func testHealthySafeBrowserCreatesCandidateOnlyForSupportedSignedContract() throws {
        let fixture = try ScannerFixture()
        defer { fixture.remove() }
        let chrome = try fixture.executable("chrome")
        let runner = EnvironmentFixtureRunner(outputs: [chrome.path: "Google Chrome 126.0.6478\n"])
        let scanner = DesktopExternalEnvironmentScanner(
            paths: [.init(kind: .browserAutomation, executableURL: chrome)],
            runner: runner,
            currentUserID: Darwin.getuid(),
            browserCompatibilityProbe: { executable, version, identifier in
                executable == chrome
                    && version == "126.0.6478"
                    && identifier == "playwright-system-chromium-v1"
            }
        )

        let accepted = scanner.scan(requirements: [requirement(
            .browserAutomation,
            version: "1.2.3",
            reuse: .verifiedCompatibility(
                contentSHA256: String(repeating: "c", count: 64),
                identifier: "playwright-system-chromium-v1"
            )
        )])
        XCTAssertEqual(accepted.observations.map(\.status), [.reusable])
        XCTAssertEqual(
            accepted.reusableCandidates.first?.compatibilityIdentifier,
            "playwright-system-chromium-v1"
        )

        let unknown = scanner.scan(requirements: [requirement(
            .browserAutomation,
            version: "1.2.3",
            reuse: .verifiedCompatibility(
                contentSHA256: String(repeating: "c", count: 64),
                identifier: "future-browser-contract"
            )
        )])
        XCTAssertEqual(unknown.observations.map(\.status), [.detected])
        XCTAssertEqual(unknown.reusableCandidates, [])
    }

    func testArchitectureMismatchAndFailedOrOversizedProbeCannotBeReused() throws {
        let fixture = try ScannerFixture()
        defer { fixture.remove() }
        let wrongArchitecture = try fixture.executable("wrong-arch")
        let failed = try fixture.executable("failed")
        let oversized = try fixture.executable("oversized")
        let runner = EnvironmentFixtureRunner(
            outputs: [
                wrongArchitecture.path: "Google Chrome 126.0.6478\n",
                failed.path: "Google Chrome 126.0.6478\n",
                oversized.path: "Google Chrome 126.0.6478\n",
            ],
            architectures: [wrongArchitecture.path: "x86_64"],
            failed: [failed.path],
            oversized: [oversized.path]
        )
        let scan = DesktopExternalEnvironmentScanner(
            paths: [
                .init(kind: .browserAutomation, executableURL: wrongArchitecture),
                .init(kind: .browserAutomation, executableURL: failed),
                .init(kind: .browserAutomation, executableURL: oversized),
            ],
            runner: runner,
            currentUserID: Darwin.getuid()
        ).scan(requirements: [requirement(
            .browserAutomation,
            version: "1.2.3",
            reuse: .verifiedCompatibility(
                contentSHA256: String(repeating: "c", count: 64),
                identifier: "playwright-system-chromium-v1"
            )
        )])

        XCTAssertEqual(scan.observations.map(\.status), [.incompatible, .detected, .detected])
        XCTAssertEqual(scan.reusableCandidates, [])
    }

    func testSymlinkAndGroupWritableExecutableAreUnsafeAndNeverRun() throws {
        let fixture = try ScannerFixture()
        defer { fixture.remove() }
        let target = try fixture.executable("target")
        let link = fixture.root.appendingPathComponent("link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: target)
        let writable = try fixture.executable("writable")
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o775], ofItemAtPath: writable.path
        )
        let runner = EnvironmentFixtureRunner(outputs: [:])

        let scan = DesktopExternalEnvironmentScanner(
            paths: [
                .init(kind: .browserAutomation, executableURL: link),
                .init(kind: .browserAutomation, executableURL: writable),
            ],
            runner: runner,
            currentUserID: Darwin.getuid()
        ).scan(requirements: [])

        XCTAssertEqual(scan.observations.map(\.status), [.unsafe, .unsafe])
        XCTAssertEqual(runner.invocationCount, 0)
    }

    func testHomebrewStyleRuntimeSymlinkIsResolvedForReportingOnly() throws {
        let fixture = try ScannerFixture()
        defer { fixture.remove() }
        let target = try fixture.executable("python-target")
        let link = fixture.root.appendingPathComponent("python3")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: target)
        let runner = EnvironmentFixtureRunner(outputs: [target.path: "Python 3.11.15\n"])

        let scan = DesktopExternalEnvironmentScanner(
            paths: [.init(kind: .pythonRuntime, executableURL: link)],
            runner: runner,
            currentUserID: Darwin.getuid()
        ).scan(requirements: [requirement(
            .pythonRuntime,
            version: "3.11.15",
            reuse: .exactContent(sha256: String(repeating: "a", count: 64))
        )])

        XCTAssertEqual(scan.observations.map(\.status), [.detected])
        XCTAssertEqual(scan.observations.map(\.version), ["3.11.15"])
        XCTAssertEqual(scan.reusableCandidates, [])
    }

    private func requirement(
        _ kind: DesktopManagedComponentKind,
        version: String,
        reuse: DesktopManagedComponentReusePolicy
    ) -> DesktopManagedComponentRequirement {
        DesktopManagedComponentRequirement(
            kind: kind,
            version: version,
            architecture: "arm64",
            downloadBytes: 1,
            installPhase: kind == .browserAutomation ? .onDemand : .bootstrap,
            reusePolicy: reuse
        )
    }
}

private final class EnvironmentFixtureRunner: OutputCommandRunning {
    private let outputs: [String: String]
    private let architectures: [String: String]
    private let failed: Set<String>
    private let oversized: Set<String>
    private(set) var invocationCount = 0

    init(
        outputs: [String: String],
        architectures: [String: String] = [:],
        failed: Set<String> = [],
        oversized: Set<String> = []
    ) {
        self.outputs = outputs
        self.architectures = architectures
        self.failed = failed
        self.oversized = oversized
    }

    func run(
        executable: URL,
        arguments: [String],
        maximumOutputBytes: Int
    ) -> DesktopCommandOutput {
        invocationCount += 1
        if executable.path == "/usr/bin/file", let target = arguments.last {
            return DesktopCommandOutput(
                status: 0,
                stdout: Data((architectures[target] ?? "Mach-O 64-bit executable arm64").utf8),
                outputLimitExceeded: false
            )
        }
        return DesktopCommandOutput(
            status: failed.contains(executable.path) ? 1 : 0,
            stdout: Data((outputs[executable.path] ?? "").utf8),
            outputLimitExceeded: oversized.contains(executable.path)
        )
    }
}

private final class ScannerFixture {
    let root: URL

    init() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-environment-scan-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
    }

    func executable(_ name: String) throws -> URL {
        let url = root.appendingPathComponent(name)
        try Data("fixture".utf8).write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
        return url
    }

    func remove() { try? FileManager.default.removeItem(at: root) }
}

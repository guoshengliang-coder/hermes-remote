import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeReadinessTests: XCTestCase {
    func testRealGitInspectorSeesTrackedAndUntrackedChangesWithoutWritingIndex() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try git(["init", "-q", root.path])
        let file = root.appendingPathComponent("fixture.txt")
        try Data("before".utf8).write(to: file)
        let inspector = DesktopHermesUpgradeGitInspector()
        XCTAssertEqual(inspector.workingTree(checkout: root), .dirty)
        try git(["-C", root.path, "add", "fixture.txt"])
        try git(["-C", root.path, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"])
        let index = root.appendingPathComponent(".git/index")
        let before = try FileManager.default.attributesOfItem(atPath: index.path)[.modificationDate] as? Date
        XCTAssertEqual(inspector.workingTree(checkout: root), .clean)
        XCTAssertEqual(try FileManager.default.attributesOfItem(atPath: index.path)[.modificationDate] as? Date, before)
        try Data("after".utf8).write(to: file)
        XCTAssertEqual(inspector.workingTree(checkout: root), .dirty)
        XCTAssertEqual(inspector.workingTree(checkout: root.appendingPathComponent("missing")), .unknown)
    }
    func testGitConditionCheckRefusesLocalAndIncludedFiltersWithoutRunningThem() throws {
        for included in [false, true] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: root) }
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try git(["init", "-q", root.path])
            let file = root.appendingPathComponent("fixture.txt")
            let marker = root.appendingPathComponent("filter-ran")
            let script = root.appendingPathComponent("filter.sh")
            try Data("before\n".utf8).write(to: file)
            try Data("*.txt filter=hg199\n".utf8).write(to: root.appendingPathComponent(".gitattributes"))
            try git(["-C", root.path, "add", ".gitattributes", "fixture.txt"])
            try git(["-C", root.path, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"])
            try Data("#!/bin/sh\n: > \"$1\"\ncat\n".utf8).write(to: script)
            let command = "/bin/sh '\(script.path)' '\(marker.path)'"
            if included {
                let config = root.appendingPathComponent("extra-config")
                try Data("[filter \"hg199\"]\n clean = \(command)\n".utf8).write(to: config)
                try git(["-C", root.path, "config", "include.path", config.path])
            } else {
                try git(["-C", root.path, "config", "filter.hg199.clean", command])
            }
            try Data("change\n".utf8).write(to: file)
            try FileManager.default.setAttributes([.modificationDate: Date().addingTimeInterval(3)], ofItemAtPath: file.path)
            XCTAssertEqual(DesktopHermesUpgradeGitInspector().workingTree(checkout: root), .unknown)
            XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path), "A read-only condition check must not run local filters")
            try git(["-C", root.path, "config", "filter.hg199.required", "true"])
            let bypassedPreflight = DesktopHermesUpgradeGitInspector.readOnlyOutput(checkout: root,
                arguments: ["status", "--porcelain=v1"], deadline: ProcessInfo.processInfo.systemUptime + 3)
            XCTAssertNil(bypassedPreflight, "The OS boundary must also reject a filter introduced after preflight")
            XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))
        }
    }
    func testGitConditionCheckRejectsUnsafeConfigurationClassesAndOversizeOutput() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try git(["init", "-q", root.path])
        let config = root.appendingPathComponent(".git/config")
        let original = try Data(contentsOf: config)
        let entries = [("filter.fixture.process", "/bin/false"), ("include.path", root.appendingPathComponent("missing").path),
            ("includeIf.gitdir:/tmp/.path", "/tmp/missing"), ("url.fixture.insteadOf", "https://example.invalid"),
            ("protocol.file.allow", "always"), ("alias.fixture", "!false"), ("submodule.fixture.url", "/tmp/missing"),
            ("core.attributesFile", "/tmp/missing"), ("core.sshCommand", "/bin/false"),
            ("core.gitProxy", "/bin/false"), ("extensions.worktreeConfig", "true"), ("remote.fixture.vcs", "ext")]
        for (key, value) in entries {
            try original.write(to: config)
            try git(["-C", root.path, "config", key, value])
            XCTAssertEqual(DesktopHermesUpgradeGitInspector().workingTree(checkout: root), .unknown, key)
        }
        try (original + Data(("\n[extra]\n value = " + String(repeating: "x", count: 70_000) + "\n").utf8)).write(to: config)
        XCTAssertEqual(DesktopHermesUpgradeGitInspector().workingTree(checkout: root), .unknown)
        try original.write(to: config)
        XCTAssertEqual(DesktopHermesUpgradeGitInspector().workingTree(checkout: root), .clean)
    }
    func testGitConditionCheckRefusesAnAncestorRepositoryAndExpiredBudget() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let child = root.appendingPathComponent("child")
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        try git(["init", "-q", root.path])
        XCTAssertEqual(DesktopHermesUpgradeGitInspector().workingTree(checkout: child), .unknown)
        XCTAssertNil(DesktopHermesUpgradeGitInspector.readOnlyOutput(checkout: root, arguments: ["status"],
            deadline: ProcessInfo.processInfo.systemUptime - 1))
    }
    private func git(_ arguments: [String]) throws {
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
        process.arguments = ["-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false"] + arguments
        process.environment = ["PATH": "/usr/bin:/bin", "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"]
        process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        try process.run(); process.waitUntilExit()
        XCTAssertEqual(process.terminationStatus, 0)
    }
}

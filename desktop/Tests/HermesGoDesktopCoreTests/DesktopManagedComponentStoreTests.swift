import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedComponentStoreTests: XCTestCase {
    func testContentIdentityIsStableAndChangesWithBytesPathOrExecutableBit() throws {
        let root = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let bin = root.appendingPathComponent("bin", isDirectory: true)
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        let executable = bin.appendingPathComponent("python")
        try Data("runtime".utf8).write(to: executable)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: executable.path)
        let hasher = DesktopManagedComponentContentHasher()

        let first = try hasher.identify(directory: root)
        XCTAssertEqual(try hasher.identify(directory: root), first)
        XCTAssertEqual(first.sha256, "c3a207f2656841667491852cbf5e699077bdda037473f789ee8fad5a4c545415")

        try FileManager.default.setAttributes([.posixPermissions: 0o400], ofItemAtPath: executable.path)
        XCTAssertNotEqual(try hasher.identify(directory: root).sha256, first.sha256)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: executable.path)
        try Data("changed".utf8).write(to: executable)
        XCTAssertNotEqual(try hasher.identify(directory: root).sha256, first.sha256)
    }

    func testContentIdentityRejectsSymbolicLinks() throws {
        let root = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createSymbolicLink(
            at: root.appendingPathComponent("escape"),
            withDestinationURL: URL(fileURLWithPath: "/tmp")
        )

        XCTAssertThrowsError(try DesktopManagedComponentContentHasher().identify(directory: root)) { caught in
            XCTAssertEqual(caught as? DesktopManagedComponentStoreError, .unsafeTree)
        }
    }

    func testInspectorReturnsOnlyARehashedHealthyExactComponent() throws {
        let store = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: store) }
        let source = store.appendingPathComponent("source", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        try Data("python".utf8).write(to: source.appendingPathComponent("runtime"))
        let identity = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
        let component = store.appendingPathComponent("components/python_runtime/\(identity)/content")
        try FileManager.default.createDirectory(
            at: component.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try FileManager.default.moveItem(at: source, to: component)
        let receiptURL = component.deletingLastPathComponent().appendingPathComponent("receipt.json")
        let receipt = DesktopManagedComponentReceipt(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            contentSHA256: identity
        )
        try JSONEncoder().encode(receipt).write(to: receiptURL)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: receiptURL.path)
        let requirement = DesktopManagedComponentRequirement(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            downloadBytes: 1,
            installPhase: .bootstrap,
            reusePolicy: .exactContent(sha256: identity)
        )
        let inspector = try DesktopManagedComponentStoreInspector(
            root: store,
            currentUserID: Darwin.getuid()
        )

        let candidate = try XCTUnwrap(inspector.candidate(for: requirement) { _ in true })
        XCTAssertEqual(candidate.source, .managedStore)
        XCTAssertEqual(candidate.contentSHA256, identity)
        XCTAssertTrue(candidate.healthProbePassed)
    }

    func testInspectorRejectsTamperedTreeAndUnsafeReceiptPermissions() throws {
        let fixture = try StoreFixture()
        defer { fixture.remove() }
        let inspector = try DesktopManagedComponentStoreInspector(
            root: fixture.root,
            currentUserID: Darwin.getuid()
        )
        try FileManager.default.setAttributes([.posixPermissions: 0o666], ofItemAtPath: fixture.receipt.path)
        XCTAssertThrowsError(try inspector.candidate(
            for: fixture.requirement,
            healthProbe: { _ in true }
        )) { caught in
            XCTAssertEqual(caught as? DesktopManagedComponentStoreError, .unsafeTree)
        }
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fixture.receipt.path)
        try Data("tampered".utf8).write(to: fixture.component.appendingPathComponent("runtime"))
        XCTAssertThrowsError(try inspector.candidate(
            for: fixture.requirement,
            healthProbe: { _ in true }
        )) { caught in
            XCTAssertEqual(caught as? DesktopManagedComponentStoreError, .identityMismatch)
        }
    }

    func testInspectorFindsManagedFallbackForCompatibilityRequirement() throws {
        let fixture = try StoreFixture()
        defer { fixture.remove() }
        guard case .exactContent(let identity) = fixture.requirement.reusePolicy else {
            return XCTFail("fixture must carry an exact identity")
        }
        let requirement = DesktopManagedComponentRequirement(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            downloadBytes: 1,
            installPhase: .bootstrap,
            reusePolicy: .verifiedCompatibility(
                contentSHA256: identity,
                identifier: "external-python-contract-for-test"
            )
        )
        let inspector = try DesktopManagedComponentStoreInspector(
            root: fixture.root, currentUserID: Darwin.getuid()
        )

        let candidate = try inspector.candidate(for: requirement) { _ in true }

        XCTAssertEqual(candidate?.source, .managedStore)
        XCTAssertEqual(candidate?.contentSHA256, identity)
    }

    func testWriterCommitsContentAndReceiptTogetherThenRecordsIdempotentReferences() throws {
        let base = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: base) }
        let source = base.appendingPathComponent("source", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: false)
        try Data("python".utf8).write(to: source.appendingPathComponent("runtime"))
        let identity = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
        let receipt = DesktopManagedComponentReceipt(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            contentSHA256: identity
        )
        let store = base.appendingPathComponent("managed", isDirectory: true)
        let writer = try DesktopManagedComponentStoreWriter(
            root: store, currentUserID: Darwin.getuid()
        )
        let runID = "10000000-0000-4000-8000-000000000001"

        let first = try writer.commit(
            sourceDirectory: source, receipt: receipt, runID: runID,
            healthProbe: { directory in
                FileManager.default.fileExists(
                    atPath: directory.appendingPathComponent("runtime").path
                )
            }
        )
        XCTAssertTrue(FileManager.default.fileExists(atPath: first.path))
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: first.deletingLastPathComponent().appendingPathComponent("receipt.json").path
        ))
        XCTAssertEqual(
            try writer.commit(
                sourceDirectory: source, receipt: receipt,
                runID: "20000000-0000-4000-8000-000000000002",
                healthProbe: { _ in true }
            ),
            first
        )

        let reference = try writer.recordReferences(
            releaseVersion: "1.2.3", receipts: [receipt],
            runID: "30000000-0000-4000-8000-000000000003"
        )
        let original = try Data(contentsOf: reference)
        XCTAssertEqual(
            try writer.recordReferences(
                releaseVersion: "1.2.3", receipts: [receipt],
                runID: "40000000-0000-4000-8000-000000000004"
            ),
            reference
        )
        XCTAssertEqual(try Data(contentsOf: reference), original)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o666], ofItemAtPath: reference.path
        )
        XCTAssertThrowsError(try writer.recordReferences(
            releaseVersion: "1.2.3", receipts: [receipt],
            runID: "60000000-0000-4000-8000-000000000006"
        )) { caught in
            XCTAssertEqual(caught as? DesktopManagedComponentStoreError, .unsafeTree)
        }
    }

    func testFailedHealthProbeRemovesOnlyCurrentWorkspace() throws {
        let base = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: base) }
        let source = base.appendingPathComponent("source", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: false)
        try Data("python".utf8).write(to: source.appendingPathComponent("runtime"))
        let identity = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
        let store = base.appendingPathComponent("managed", isDirectory: true)
        let writer = try DesktopManagedComponentStoreWriter(
            root: store, currentUserID: Darwin.getuid()
        )
        let receipt = DesktopManagedComponentReceipt(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            contentSHA256: identity
        )

        XCTAssertThrowsError(try writer.commit(
            sourceDirectory: source,
            receipt: receipt,
            runID: "50000000-0000-4000-8000-000000000005",
            healthProbe: { _ in false }
        )) { caught in
            XCTAssertEqual(caught as? DesktopManagedComponentStoreError, .healthProbeFailed)
        }
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: store.appendingPathComponent("components/python_runtime/\(identity)").path
        ))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: store.appendingPathComponent(
                ".staging/50000000-0000-4000-8000-000000000005"
            ).path
        ))
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    private func temporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-component-store-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        return url
    }
}

private final class StoreFixture {
    let root: URL
    let component: URL
    let receipt: URL
    let requirement: DesktopManagedComponentRequirement

    init() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-component-store-fixture-\(UUID().uuidString)", isDirectory: true)
        let source = root.appendingPathComponent("source", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        try Data("python".utf8).write(to: source.appendingPathComponent("runtime"))
        let identity = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
        component = root.appendingPathComponent("components/python_runtime/\(identity)/content")
        try FileManager.default.createDirectory(
            at: component.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try FileManager.default.moveItem(at: source, to: component)
        receipt = component.deletingLastPathComponent().appendingPathComponent("receipt.json")
        try JSONEncoder().encode(DesktopManagedComponentReceipt(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            contentSHA256: identity
        )).write(to: receipt)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: receipt.path)
        requirement = DesktopManagedComponentRequirement(
            kind: .pythonRuntime,
            version: "3.11.15",
            architecture: "arm64",
            downloadBytes: 1,
            installPhase: .bootstrap,
            reusePolicy: .exactContent(sha256: identity)
        )
    }

    func remove() { try? FileManager.default.removeItem(at: root) }
}

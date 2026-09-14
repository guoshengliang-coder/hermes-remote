import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedComponentGarbageCollectorTests: XCTestCase {
    func testPlanRetainsEveryReferenceAndReturnsOnlyOrphansInStableOrder() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let retained = try fixture.addComponent(kind: .pythonRuntime, contents: "python")
        let orphanNode = try fixture.addComponent(kind: .nodeRuntime, contents: "node")
        let orphanBrowser = try fixture.addComponent(
            kind: .browserAutomation, contents: "browser"
        )
        try fixture.addReference(version: "1.2.3", identities: [retained])

        let plan = try fixture.planner().plan(protectedReleaseVersions: ["1.2.3"])

        XCTAssertEqual(plan.retained, [retained])
        XCTAssertEqual(
            plan.candidates.map(\.identity),
            [orphanBrowser, orphanNode]
        )
        XCTAssertEqual(plan.reclaimableFileBytes, Int64("browser".utf8.count + "node".utf8.count))
        XCTAssertEqual(plan.storeSnapshotSHA256.count, 64)
        XCTAssertTrue(plan.candidates.allSatisfy {
            FileManager.default.fileExists(atPath: $0.containerURL.path)
        })
    }

    func testCapabilityReferenceRetainsOnDemandContentWithItsBaseRelease() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let python = try fixture.addComponent(kind: .pythonRuntime, contents: "python")
        let browser = try fixture.addComponent(
            kind: .browserAutomation, contents: "browser"
        )
        let orphan = try fixture.addComponent(kind: .nodeRuntime, contents: "node")
        try fixture.addReference(version: "1.2.3", identities: [python])
        try fixture.addCapabilityReference(version: "1.2.3", identity: browser)

        let plan = try fixture.planner().plan(protectedReleaseVersions: ["1.2.3"])

        XCTAssertEqual(Set(plan.retained), Set([python, browser]))
        XCTAssertEqual(plan.candidates.map(\.identity), [orphan])
    }

    func testCapabilityReferenceWithoutBaseReleaseFailsClosed() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let browser = try fixture.addComponent(
            kind: .browserAutomation, contents: "browser"
        )
        try fixture.addCapabilityReference(version: "1.2.3", identity: browser)

        XCTAssertThrowsError(try fixture.planner().plan(protectedReleaseVersions: [])) {
            XCTAssertEqual(
                $0 as? DesktopManagedComponentGarbageCollectionError,
                .invalidReference
            )
        }
    }

    func testCapabilityContentMayBeSharedAcrossBaseReleases() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let python = try fixture.addComponent(kind: .pythonRuntime, contents: "python")
        let browser = try fixture.addComponent(
            kind: .browserAutomation, contents: "browser"
        )
        try fixture.addReference(version: "1.2.3", identities: [python])
        try fixture.addReference(version: "1.2.4", identities: [python])
        try fixture.addCapabilityReference(version: "1.2.3", identity: browser)
        try fixture.addCapabilityReference(version: "1.2.4", identity: browser)

        let plan = try fixture.planner().plan(protectedReleaseVersions: ["1.2.4"])

        XCTAssertEqual(Set(plan.retained), Set([python, browser]))
        XCTAssertTrue(plan.candidates.isEmpty)
    }

    func testPlanIsReadOnlyAndEmptyStoreNeedsNoDirectories() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-gc-empty-\(UUID().uuidString)", isDirectory: true)
        let planner = try DesktopManagedComponentGarbageCollectionPlanner(
            root: root, currentUserID: Darwin.getuid()
        )

        let first = try planner.plan(protectedReleaseVersions: [])
        let second = try planner.plan(protectedReleaseVersions: [])

        XCTAssertEqual(first, second)
        XCTAssertEqual(first.candidates, [])
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    }

    func testMalformedOrUnsafeReferenceFailsClosed() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let component = try fixture.addComponent(kind: .pythonRuntime, contents: "python")
        let reference = try fixture.addReference(version: "1.2.3", identities: [component])
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o644], ofItemAtPath: reference.path
        )

        XCTAssertThrowsError(try fixture.planner().plan(
            protectedReleaseVersions: ["1.2.3"]
        )) { error in
            XCTAssertEqual(
                error as? DesktopManagedComponentGarbageCollectionError,
                .unsafeStore
            )
        }

        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: reference.path
        )
        try Data("{}".utf8).write(to: reference)
        XCTAssertThrowsError(try fixture.planner().plan(
            protectedReleaseVersions: ["1.2.3"]
        )) { error in
            XCTAssertEqual(
                error as? DesktopManagedComponentGarbageCollectionError,
                .invalidReference
            )
        }
    }

    func testMissingProtectedReferenceOrReferencedComponentFailsClosed() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let component = try fixture.addComponent(kind: .pythonRuntime, contents: "python")

        XCTAssertThrowsError(try fixture.planner().plan(
            protectedReleaseVersions: ["1.2.3"]
        )) { error in
            XCTAssertEqual(
                error as? DesktopManagedComponentGarbageCollectionError,
                .missingProtectedReference
            )
        }

        try fixture.addReference(version: "1.2.3", identities: [component])
        try FileManager.default.removeItem(at: fixture.container(for: component))
        XCTAssertThrowsError(try fixture.planner().plan(
            protectedReleaseVersions: ["1.2.3"]
        )) { error in
            XCTAssertEqual(
                error as? DesktopManagedComponentGarbageCollectionError,
                .missingReferencedComponent
            )
        }
    }

    func testTamperedComponentAndUnknownKindFailClosed() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let component = try fixture.addComponent(kind: .pythonRuntime, contents: "python")
        try Data("tampered".utf8).write(
            to: fixture.container(for: component).appendingPathComponent("content/runtime")
        )

        XCTAssertThrowsError(try fixture.planner().plan(protectedReleaseVersions: [])) {
            XCTAssertEqual(
                $0 as? DesktopManagedComponentGarbageCollectionError,
                .invalidComponent
            )
        }

        try FileManager.default.removeItem(at: fixture.container(for: component))
        let unknown = fixture.root.appendingPathComponent("components/future_component")
        try FileManager.default.createDirectory(at: unknown, withIntermediateDirectories: true)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: unknown.path
        )
        XCTAssertThrowsError(try fixture.planner().plan(protectedReleaseVersions: [])) {
            XCTAssertEqual(
                $0 as? DesktopManagedComponentGarbageCollectionError,
                .invalidComponent
            )
        }
    }

    func testComponentContainerSymlinkFailsClosed() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let outside = fixture.root.appendingPathComponent("outside", isDirectory: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: false)
        let kindRoot = fixture.root.appendingPathComponent("components/python_runtime")
        try FileManager.default.createDirectory(at: kindRoot, withIntermediateDirectories: true)
        let link = kindRoot.appendingPathComponent(String(repeating: "a", count: 64))
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)

        XCTAssertThrowsError(try fixture.planner().plan(protectedReleaseVersions: [])) {
            XCTAssertEqual(
                $0 as? DesktopManagedComponentGarbageCollectionError,
                .unsafeStore
            )
        }
    }

    func testSnapshotChangesWhenAValidReceiptChanges() throws {
        let fixture = try GarbageCollectionFixture()
        defer { fixture.remove() }
        let component = try fixture.addComponent(kind: .pythonRuntime, contents: "python")
        let first = try fixture.planner().plan(protectedReleaseVersions: [])
        let receiptURL = fixture.container(for: component).appendingPathComponent("receipt.json")
        let receipt = DesktopManagedComponentReceipt(
            kind: .pythonRuntime,
            version: "1.2.4",
            architecture: "arm64",
            contentSHA256: component.contentSHA256
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(receipt).write(to: receiptURL)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: receiptURL.path
        )

        let second = try fixture.planner().plan(protectedReleaseVersions: [])

        XCTAssertNotEqual(first.storeSnapshotSHA256, second.storeSnapshotSHA256)
        XCTAssertEqual(first.candidates.map(\.identity), second.candidates.map(\.identity))
    }
}

private final class GarbageCollectionFixture {
    let root: URL

    init() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("hermes-component-gc-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(
            at: root, withIntermediateDirectories: false,
            attributes: [.posixPermissions: 0o700]
        )
    }

    func planner() throws -> DesktopManagedComponentGarbageCollectionPlanner {
        try DesktopManagedComponentGarbageCollectionPlanner(
            root: root, currentUserID: Darwin.getuid()
        )
    }

    func addComponent(
        kind: DesktopManagedComponentKind,
        contents: String
    ) throws -> DesktopManagedComponentIdentity {
        let source = root.appendingPathComponent("source-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: false)
        try Data(contents.utf8).write(to: source.appendingPathComponent("runtime"))
        let hash = try DesktopManagedComponentContentHasher().identify(directory: source).sha256
        let receipt = DesktopManagedComponentReceipt(
            kind: kind,
            version: "1.2.3",
            architecture: "arm64",
            contentSHA256: hash
        )
        _ = try DesktopManagedComponentStoreWriter(
            root: root, currentUserID: Darwin.getuid()
        ).commit(
            sourceDirectory: source,
            receipt: receipt,
            runID: UUID().uuidString,
            healthProbe: { _ in true }
        )
        try FileManager.default.removeItem(at: source)
        return DesktopManagedComponentIdentity(kind: kind, contentSHA256: hash)
    }

    @discardableResult
    func addReference(
        version: String,
        identities: [DesktopManagedComponentIdentity]
    ) throws -> URL {
        let directory = root.appendingPathComponent("references", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: directory.path
        )
        let reference = DesktopManagedComponentReferenceSet(
            releaseVersion: version,
            components: identities.map {
                DesktopManagedComponentReference(
                    kind: $0.kind, contentSHA256: $0.contentSHA256
                )
            }
        )
        let destination = directory.appendingPathComponent("\(version).json")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(reference).write(to: destination)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: destination.path
        )
        return destination
    }

    @discardableResult
    func addCapabilityReference(
        version: String,
        identity: DesktopManagedComponentIdentity
    ) throws -> URL {
        let directory = root.appendingPathComponent(
            "capability-references/\(version)", isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: directory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700]
        )
        let ancestors = [directory.deletingLastPathComponent(), directory]
        for ancestor in ancestors {
            try FileManager.default.setAttributes(
                [.posixPermissions: 0o700], ofItemAtPath: ancestor.path
            )
        }
        let reference = DesktopManagedCapabilityReference(
            releaseVersion: version,
            component: DesktopManagedComponentReference(
                kind: identity.kind,
                contentSHA256: identity.contentSHA256
            )
        )
        let destination = directory.appendingPathComponent("\(identity.kind.rawValue).json")
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(reference).write(to: destination)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: destination.path
        )
        return destination
    }

    func container(for identity: DesktopManagedComponentIdentity) -> URL {
        root.appendingPathComponent(
            "components/\(identity.kind.rawValue)/\(identity.contentSHA256)",
            isDirectory: true
        )
    }

    func remove() { try? FileManager.default.removeItem(at: root) }
}

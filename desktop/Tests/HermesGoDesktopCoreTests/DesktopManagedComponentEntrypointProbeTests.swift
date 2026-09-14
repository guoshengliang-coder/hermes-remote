import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopManagedComponentEntrypointProbeTests: XCTestCase {
    private var base: URL!
    private var root: URL!
    private var entrypoint: URL!

    override func setUpWithError() throws {
        base = FileManager.default.temporaryDirectory.appendingPathComponent(
            "hermes-entrypoint-probe-\(UUID().uuidString)", isDirectory: true
        )
        root = base.appendingPathComponent("content", isDirectory: true)
        entrypoint = root.appendingPathComponent("bin/node")
        try FileManager.default.createDirectory(
            at: entrypoint.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try Data("binary".utf8).write(to: entrypoint)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: entrypoint.path
        )
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: base)
    }

    func testAcceptsOwnedNonWritableExecutableInsideComponentRoot() throws {
        XCTAssertTrue(try probe()(.nodeRuntime, root: root, entrypoint: entrypoint))
    }

    func testRejectsNonExecutableOrGroupWritableEntrypoint() throws {
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o600], ofItemAtPath: entrypoint.path
        )
        XCTAssertFalse(try probe()(.nodeRuntime, root: root, entrypoint: entrypoint))

        try FileManager.default.setAttributes(
            [.posixPermissions: 0o720], ofItemAtPath: entrypoint.path
        )
        XCTAssertFalse(try probe()(.nodeRuntime, root: root, entrypoint: entrypoint))
    }

    func testRejectsEntrypointOutsideRootOrThroughSymlink() throws {
        let outside = base.appendingPathComponent("outside")
        try Data("binary".utf8).write(to: outside)
        try FileManager.default.setAttributes(
            [.posixPermissions: 0o700], ofItemAtPath: outside.path
        )
        XCTAssertFalse(try probe()(.nodeRuntime, root: root, entrypoint: outside))

        let link = root.appendingPathComponent("bin/link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: entrypoint)
        XCTAssertFalse(try probe()(.nodeRuntime, root: root, entrypoint: link))
    }

    private func probe() -> DesktopManagedComponentEntrypointProbe {
        DesktopManagedComponentEntrypointProbe(currentUserID: getuid())
    }
}

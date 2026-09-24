import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class ManagedLogInspectorTests: XCTestCase {
    func testReadsManagedLogsWithoutFollowingSymlinksOrShowingSecrets() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try "2026-09-24 connector ready\n2026-09-24 ERROR password=hunter2\n".write(
            to: root.appendingPathComponent("connector.log"), atomically: true, encoding: .utf8
        )
        try "restart hermes result=ready\n".write(
            to: root.appendingPathComponent("desktop-runtime.log"), atomically: true, encoding: .utf8
        )
        let external = root.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: external) }
        try "sensitive".write(to: external, atomically: true, encoding: .utf8)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("connector.error.log"),
                                                   withDestinationURL: external)

        let lines = ManagedLogInspector(logsDirectory: root).inspect()
        XCTAssertTrue(lines.contains { $0.contains("connector ready") })
        XCTAssertTrue(lines.contains { $0.contains("restart hermes result=ready") })
        XCTAssertFalse(lines.joined().contains("hunter2"))
        XCTAssertFalse(lines.joined().contains("sensitive"))

        let linkedDirectory = root.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: linkedDirectory) }
        try FileManager.default.createSymbolicLink(at: linkedDirectory, withDestinationURL: root)
        XCTAssertTrue(ManagedLogInspector(logsDirectory: linkedDirectory).inspect().isEmpty)
    }
}

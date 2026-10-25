import Darwin
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopConnectorRuntimeIdentityTests: XCTestCase {
    func testKernelArgumentParserPreservesArgvAndDoesNotReturnEnvironmentValues() throws {
        let data = arguments(["/bundle/runtime/node", "/bundle/app/index.js"], environment: ["PRIVATE_TEST_ONLY=synthetic-value", "PATH=/usr/bin"])
        let parsed = try XCTUnwrap(ConnectorKernelIdentity.parseArguments(data))
        XCTAssertEqual(parsed.arguments, ["/bundle/runtime/node", "/bundle/app/index.js"])
        XCTAssertFalse(parsed.injected)
        XCTAssertFalse(String(describing: parsed).contains("synthetic-value"))
        for entry in ["NODE_OPTIONS=--import=example", "NODE_PATH=/elsewhere", "DYLD_INSERT_LIBRARIES=/elsewhere"] {
            XCTAssertTrue(try XCTUnwrap(ConnectorKernelIdentity.parseArguments(arguments(["node", "main.js"], environment: [entry]))).injected)
        }
        XCTAssertFalse(try XCTUnwrap(ConnectorKernelIdentity.parseArguments(arguments(["node", "main.js"], environment: ["NODE_OPTIONS="]))).injected)
    }

    func testKernelArgumentParserRejectsMalformedOrTruncatedData() {
        XCTAssertNil(ConnectorKernelIdentity.parseArguments(Data()))
        var negative: Int32 = -1
        var bytes = withUnsafeBytes(of: &negative) { Data($0) }; bytes.append(contentsOf: [0, 0])
        XCTAssertNil(ConnectorKernelIdentity.parseArguments(bytes))
        var missing = arguments(["node", "main.js"], environment: ["PRIVATE_TEST_ONLY=value"])
        missing.removeLast()
        XCTAssertNil(ConnectorKernelIdentity.parseArguments(missing))
        XCTAssertNil(ConnectorKernelIdentity.parseArguments(arguments(["node"], environment: ["malformed"])))
    }

    func testJobParserRequiresExactTopLevelPIDAndManagedLauncher() throws {
        let target = "gui/501/com.hermesgo.connector", launcher = "/managed/current/connector/bin/hermes-connector"
        let valid = "\(target) = {\n\tpid = 123\n\targuments = {\n\t\t\(launcher)\n\t}\n}\n"
        XCTAssertEqual(try DesktopConnectorRuntimeIdentityInspector.parseJob(valid, target: target, launcher: launcher), 123)
        for invalid in [valid.replacingOccurrences(of: "\tpid = 123", with: "\t\tpid = 123"),
                        valid.replacingOccurrences(of: "\tpid = 123", with: "\tpid = 123\n\tpid = 456"),
                        valid.replacingOccurrences(of: "\tpid = 123", with: "\tpid = -1"),
                        valid.replacingOccurrences(of: launcher, with: "/other/launcher"),
                        valid.replacingOccurrences(of: target, with: "gui/501/other")] {
            XCTAssertThrowsError(try DesktopConnectorRuntimeIdentityInspector.parseJob(invalid, target: target, launcher: launcher))
        }
    }

    func testRealKernelReaderTracksOwnChildAndRejectsExitedProcess() throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sleep")
        process.arguments = ["30"]
        process.environment = ["PATH": "/usr/bin:/bin", "PRIVATE_TEST_ONLY": "synthetic-value"]
        process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        try process.run()
        defer { if process.isRunning { process.terminate(); process.waitUntilExit() } }
        var observed: ConnectorKernelIdentity?
        for _ in 0..<100 {
            observed = ConnectorKernelIdentity.read(process.processIdentifier)
            if observed != nil { break }
            usleep(10_000)
        }
        let identity = try XCTUnwrap(observed)
        XCTAssertEqual(identity.pid, process.processIdentifier)
        XCTAssertEqual(identity.userID, getuid())
        XCTAssertEqual(identity.executable, "/bin/sleep")
        XCTAssertEqual(identity.arguments, ["/bin/sleep", "30"])
        XCTAssertFalse(identity.injectedEnvironment)
        XCTAssertFalse(String(describing: identity).contains("synthetic-value"))
        XCTAssertEqual(ConnectorKernelIdentity.read(identity.pid), identity)
        process.terminate(); process.waitUntilExit()
        XCTAssertNil(ConnectorKernelIdentity.read(identity.pid))
    }

    private func arguments(_ args: [String], environment: [String]) -> Data {
        var argc = Int32(args.count)
        var result = withUnsafeBytes(of: &argc) { Data($0) }
        result.append(Data("/kernel/exec\0\0\0".utf8))
        for value in args + environment { result.append(Data((value + "\0").utf8)) }
        return result
    }
}

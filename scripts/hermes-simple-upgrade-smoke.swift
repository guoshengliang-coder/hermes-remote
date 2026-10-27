// Explicitly invoked, disposable-home smoke test. It cannot write outside that home or its scratch.
import Darwin
import Foundation
@testable import HermesGoDesktopCore

private struct IsolatedRunner: DesktopHermesInstallerProcessRunning {
    let home: URL
    func run(executable: URL, arguments: [String], environment: [String: String], workingDirectory: URL,
             onLine: @escaping @Sendable (String) -> Void) async throws -> DesktopHermesInstallerProcessResult {
        let quoted = home.path.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let temporary = FileManager.default.temporaryDirectory.path
        let resolved = realpath(temporary, nil)!
        let temporaryPath = String(cString: resolved); free(resolved)
        let expression = "^" + NSRegularExpression.escapedPattern(for: temporaryPath.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
        let tempRule = "^/" + String(expression.dropFirst()) + "/(tmp[.][^/]+|xcrun_db-[^/]+)(/|$)"
        let escapedRule = tempRule.replacingOccurrences(of: "\\", with: "\\\\")
        let profile = "(version 1) (allow default) (deny file-write* (require-not (require-any (subpath \"\(quoted)\") (regex \"\(escapedRule)\") (literal \"/dev/null\"))))"
        var environment = environment
        environment["UV_CACHE_DIR"] = home.appendingPathComponent("tmp/uv-cache").path
        environment["XDG_CACHE_HOME"] = home.appendingPathComponent("tmp/cache").path
        environment["UV_PYTHON_INSTALL_DIR"] = home.appendingPathComponent(".local/share/uv/python").path
        return try await DesktopPosixProcessRunner().run(executable: URL(fileURLWithPath: "/usr/bin/sandbox-exec"),
            arguments: ["-p", profile, executable.path] + arguments, environment: environment,
            workingDirectory: workingDirectory, onLine: onLine)
    }
}
private final class PortCapture: @unchecked Sendable {
    let lock = NSLock()
    var stored: Int?
    var port: Int? { lock.withLock { stored } }
    func line(_ value: String) {
        let prefix = "HERMES_BACKEND_READY port="
        if value.hasPrefix(prefix), let port = Int(value.dropFirst(prefix.count)) { lock.withLock { stored = port } }
    }
}
@main struct StableUpgradeSmoke {
    static func main() async {
        setbuf(stdout, nil)
        do { try await exercise() }
        catch { print("SMOKE_FAILED=\(SecretRedactor.redact(String(describing: error)))"); exit(1) }
    }
    static func exercise() async throws {
        guard CommandLine.arguments.count == 2 || (CommandLine.arguments.count == 3 && CommandLine.arguments[2] == "--verify") else { fatalError("disposable home argument required") }
        guard let physical = realpath(CommandLine.arguments[1], nil) else { fatalError("missing disposable home") }
        let physicalPath = String(cString: physical); free(physical)
        let home = URL(fileURLWithPath: physicalPath)
        guard physicalPath.hasPrefix("/private/tmp/hermes-simple-smoke-"),
              FileManager.default.fileExists(atPath: home.appendingPathComponent("ISOLATED_TEST_HOME").path)
        else { fatalError("not an explicitly prepared disposable home") }
        let paths = try DesktopLocalHermesPaths(homeDirectory: home, systemEntrypoints: [])
        let detector = DesktopLocalHermesDetector(paths: paths, environment: [:])
        let source = detector.detect().installation?.version ?? "unrecognized"
        print("SOURCE=\(source)")
        let target = try await DesktopHermesOfficialReleaseFetcher().latestStable()
        print("TARGET=\(target.version)@\(target.commit)")
        let runner = IsolatedRunner(home: home)
        let installer = DesktopHermesStableInstaller(paths: paths, proxy: .current(), runner: runner,
            inheritedEnvironment: ["TMPDIR": home.appendingPathComponent("tmp").path])
        if CommandLine.arguments.count == 2 {
            try await installer.run(target: target, progress: { print("STAGE=\($0.titleChinese)") }, output: { print($0) })
        }
        if case .unsupported(let reason, let detail) = detector.detect() {
            print("DETECTION=\(reason.rawValue) \(SecretRedactor.redact(detail))")
        }
        guard let installed = detector.detect().installation, installed.commit == target.commit, installed.version == target.version
        else { throw DesktopHermesInstallFailure.notUsable(stage: "verify", detail: "installed target mismatch") }
        var environment = installer.environment()
        environment["HERMES_DESKTOP"] = "0"
        let token = UUID().uuidString + UUID().uuidString
        environment["HERMES_DASHBOARD_SESSION_TOKEN"] = token
        let output = PortCapture()
        let running = Task {
            try await runner.run(executable: installed.executable,
                arguments: ["--profile", "default", "serve", "--host", "127.0.0.1", "--port", "0"],
                environment: environment, workingDirectory: installed.checkoutRoot, onLine: { output.line($0) })
        }
        do {
            for _ in 0..<600 {
                if output.port != nil { break }
                try await Task.sleep(for: .milliseconds(200))
            }
            guard let port = output.port else { throw URLError(.timedOut) }
            let config = URLSessionConfiguration.ephemeral; config.connectionProxyDictionary = [:]
            let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
            for route in ["/api/status", "/api/sessions"] {
                var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(route)")!)
                request.timeoutInterval = 10; request.setValue(token, forHTTPHeaderField: "X-Hermes-Session-Token")
                let (bytes, response) = try await session.data(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                if route == "/api/status" {
                    let json = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
                    guard json?["version"] as? String == target.version else { throw URLError(.cannotParseResponse) }
                }
                print("HTTP_OK=\(route)")
            }
        } catch {
            running.cancel(); _ = try? await running.value; throw error
        }
        running.cancel(); _ = try? await running.value
        print("HERMES_STABLE_SMOKE_OK source=\(source) target=\(target.version) localRuntime=true")
    }
}

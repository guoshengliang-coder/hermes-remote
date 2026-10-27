import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopHermesUpgradeDownloadTests: XCTestCase {
    let target = try! DesktopHermesUpgradeTarget(version: "0.22.5", commit: String(repeating: "b", count: 40))
    func testAllDownloadersReceiveSameManualProxyAndLoopbackBypass() {
        let proxy = DesktopSystemProxy(http: .init(host: "127.0.0.1", port: 7897), https: .init(host: "127.0.0.1", port: 7897))
        let env = DesktopHermesUpgradeNetwork.environment(proxy: proxy, inherited: ["NODE_OPTIONS": "--bad", "OPENAI_API_KEY": "private"])
        for key in ["http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY", "GLOBAL_AGENT_HTTP_PROXY", "GLOBAL_AGENT_HTTPS_PROXY"] {
            XCTAssertEqual(env[key], "http://127.0.0.1:7897", key)
        }
        XCTAssertEqual(env["NODE_USE_ENV_PROXY"], "1")
        XCTAssertEqual(env["ELECTRON_GET_USE_PROXY"], "1")
        XCTAssertTrue(env["NO_PROXY"]!.contains("127.0.0.1"))
        XCTAssertNil(env["NODE_OPTIONS"])
        XCTAssertNil(env["OPENAI_API_KEY"])
        XCTAssertFalse(DesktopHermesUpgradeNetwork.summary(proxy: proxy, environment: env).contains("7897"))
    }
    func testExplicitProxyNormalizesCasesAndDoesNotMixSystemSettings() {
        let proxy = DesktopSystemProxy(https: .init(host: "system.invalid", port: 1234))
        let env = DesktopHermesUpgradeNetwork.environment(proxy: proxy, inherited: ["https_proxy": "http://chosen.invalid:8080", "HTTPS_PROXY": "http://other.invalid:8080"])
        XCTAssertEqual(env["HTTPS_PROXY"], "http://chosen.invalid:8080")
        XCTAssertEqual(env["GLOBAL_AGENT_HTTPS_PROXY"], env["HTTPS_PROXY"])
        XCTAssertNil(env["HTTP_PROXY"])
        XCTAssertFalse(env.values.joined().contains("system.invalid"))
    }
    func testDirectPACAndSOCKSAreNotMisrepresentedAsUnifiedHTTPProxy() {
        let direct = DesktopHermesUpgradeNetwork.environment(proxy: .none, inherited: [:])
        XCTAssertNil(direct["NODE_USE_ENV_PROXY"])
        XCTAssertTrue(DesktopHermesUpgradeNetwork.summary(proxy: .none, environment: direct).contains("直接连接"))
        let socks = DesktopSystemProxy(socks: .init(host: "127.0.0.1", port: 1080))
        let env = DesktopHermesUpgradeNetwork.environment(proxy: socks, inherited: [:])
        XCTAssertNil(env["NODE_USE_ENV_PROXY"])
        XCTAssertTrue(DesktopHermesUpgradeNetwork.summary(proxy: socks, environment: env).contains("需要 HTTP/HTTPS"))
    }
    func testScopedGitRewriteWinsWithoutChangingGlobalConfig() async throws {
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        let config = "[url \"git@github.com:\"]\n\tinsteadOf = https://github.com/\n"
        try Data(config.utf8).write(to: home.appendingPathComponent(".gitconfig"))
        let runner = DesktopPosixProcessRunner()
        var env = DesktopHermesUpgradeNetwork.environment(proxy: .none, inherited: [:])
        env["HOME"] = home.path; env["PATH"] = "/usr/bin:/bin"; env["GIT_CONFIG_NOSYSTEM"] = "1"
        let result = try await runner.run(executable: URL(fileURLWithPath: "/usr/bin/git"),
            arguments: ["ls-remote", "--get-url", "https://github.com/NousResearch/hermes-agent.git"],
            environment: env, workingDirectory: home, onLine: { _ in })
        XCTAssertEqual(result.exitStatus, 0)
        XCTAssertEqual(result.standardOutputTail.last, "https://github.com/NousResearch/hermes-agent.git")
        XCTAssertEqual(try String(contentsOf: home.appendingPathComponent(".gitconfig")), config)
    }
    func testGitWrapperPreservesArgumentsAndForcesRealProgressOnPipe() async throws {
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        let bin = home.appendingPathComponent("bin")
        try FileManager.default.createDirectory(at: bin, withIntermediateDirectories: true)
        let wrapper = bin.appendingPathComponent("git")
        try Data(DesktopHermesUpgradeNetwork.gitWrapper.utf8).write(to: wrapper)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: wrapper.path)
        var env = DesktopHermesUpgradeNetwork.environment(proxy: .none, inherited: [:])
        env["HOME"] = home.path; env["PATH"] = bin.path + ":/usr/bin:/bin"; env["GIT_CONFIG_NOSYSTEM"] = "1"
        let script = #"""
        set -e
        git init "$HOME/source with spaces"
        /usr/bin/openssl rand -out "$HOME/source with spaces/data" 131072
        git -C "$HOME/source with spaces" add data
        git -C "$HOME/source with spaces" -c user.name=Fixture -c user.email=fixture@example.invalid commit -m fixture
        git -C "$HOME" clone --no-local "$HOME/source with spaces" "$HOME/copy with spaces"
        cmp "$HOME/source with spaces/data" "$HOME/copy with spaces/data"
        """#
        let result = try await DesktopPosixProcessRunner().run(executable: URL(fileURLWithPath: "/bin/bash"), arguments: ["-c", script], environment: env, workingDirectory: home, onLine: { _ in })
        XCTAssertEqual(result.exitStatus, 0)
        XCTAssertTrue(result.combinedTail.contains { $0.contains("Receiving objects:") })
    }
    func testCarriageReturnProgressArrivesBeforeProcessExits() async throws {
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        let received = expectation(description: "live CR progress")
        let run = Task {
            try await DesktopPosixProcessRunner().run(executable: URL(fileURLWithPath: "/bin/bash"),
                arguments: ["-c", "printf 'Receiving objects: 24%% (24/100), 12.50 MiB | 1.25 MiB/s\\r'; sleep 2; printf 'done\\n'"],
                environment: ["PATH": "/usr/bin:/bin"], workingDirectory: home, onLine: { line in
                    if DesktopHermesTransferProgress.parse(line) != nil { received.fulfill() }
                })
        }
        await fulfillment(of: [received], timeout: 1.5)
        _ = try await run.value
    }
    func testGitByteProgressDoesNotTurnObjectPercentIntoBytePercent() {
        let value = DesktopHermesTransferProgress.parse("Receiving objects: 24% (24/100), 12.50 MiB | 1.25 MiB/s")
        XCTAssertEqual(value?.receivedBytes, 13_107_200)
        XCTAssertEqual(value?.bytesPerSecond, 1_310_720)
        XCTAssertNil(value?.totalBytes)
        XCTAssertNil(DesktopHermesTransferProgress.parse("Resolving deltas: 50% (5/10)"))
        XCTAssertNil(DesktopHermesTransferProgress.parse("Receiving objects: 50% (5/10)"))
        XCTAssertNil(DesktopHermesUpgradeOutput.substep("secret value from arbitrary log"))
        XCTAssertEqual(DesktopHermesUpgradeOutput.substep("→ Packaging the desktop app…"), "打包 Hermes 客户端")
    }
    func testRetryRunsOnlyFailedAndRemainingStagesAndUsesFreshProxy() async throws {
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        let proof = ProofBox()
        let session = DesktopHermesUpgradeSession(evidence: { step, _ in proof.value(step) })
        let runner = RetryStageRunner(failure: .products)
        let paths = try DesktopLocalHermesPaths(homeDirectory: home)
        let first = DesktopHermesStableInstaller(paths: paths, proxy: .none, runner: runner, fetcher: DownloadScript(), session: session)
        do { try await first.run(target: target, progress: { _ in }, output: { _ in }); XCTFail() } catch {}
        let retry = DesktopHermesStableInstaller(paths: paths, proxy: .init(https: .init(host: "127.0.0.1", port: 7897)), runner: runner, fetcher: DownloadScript(), session: session)
        try await retry.run(target: target, progress: { _ in }, output: { _ in })
        let stages = await runner.stages
        XCTAssertEqual(stages, ["repository", "venv", "python-deps", "config", "products", "products", "complete"])
        let lastEnv = await runner.lastEnvironment
        XCTAssertEqual(lastEnv["HTTPS_PROXY"], "http://127.0.0.1:7897")
    }
    func testChangedEvidenceRestartsAtEarliestInvalidStage() async throws {
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        let proof = ProofBox(); let session = DesktopHermesUpgradeSession(evidence: { step, _ in proof.value(step) })
        let runner = RetryStageRunner(failure: .products)
        let installer = DesktopHermesStableInstaller(paths: try .init(homeDirectory: home), proxy: .none, runner: runner, fetcher: DownloadScript(), session: session)
        do { try await installer.run(target: target, progress: { _ in }, output: { _ in }); XCTFail() } catch {}
        proof.change(.dependencies)
        try await installer.run(target: target, progress: { _ in }, output: { _ in })
        let stages = await runner.stages
        XCTAssertEqual(Array(stages.suffix(4)), ["python-deps", "config", "products", "complete"])
        XCTAssertEqual(stages.filter { $0 == "repository" }.count, 1)
    }
    func testChangedTargetOrScriptCannotReuseReceipts() async throws {
        for changeTarget in [true, false] {
            let proof = ProofBox(); let session = DesktopHermesUpgradeSession(evidence: { step, _ in proof.value(step) })
            _ = await session.resume(target: target, script: Data("first".utf8))
            await session.completed(.repository, target: target)
            let count = await session.resume(target: changeTarget ? try .init(version: "0.22.6", commit: String(repeating: "c", count: 40)) : target,
                                             script: Data((changeTarget ? "first" : "second").utf8))
            XCTAssertEqual(count, 0)
        }
    }

    func testScriptDownloaderUsesSameManualProxy() throws {
        let env = DesktopHermesUpgradeNetwork.environment(proxy: .init(https: .init(host: "127.0.0.1", port: 7897)), inherited: [:])
        let config = try DesktopHermesUpgradeNetwork.urlSessionProxy(env)
        XCTAssertEqual(config["HTTPSProxy"] as? String, "127.0.0.1")
        XCTAssertEqual(config["HTTPSPort"] as? Int, 7897)
        XCTAssertEqual(config["HTTPSEnable"] as? Int, 1)
        XCTAssertEqual(config["HTTPEnable"] as? Int, 0)
        XCTAssertTrue((config["ExceptionsList"] as? [String] ?? []).contains("127.0.0.1"))
        XCTAssertEqual(try DesktopHermesUpgradeNetwork.urlSessionProxy(["https_proxy": "http://proxy.invalid"])["HTTPSPort"] as? Int, 80)
        XCTAssertThrowsError(try DesktopHermesUpgradeNetwork.urlSessionProxy(["https_proxy": "https://proxy.invalid"]))
        XCTAssertThrowsError(try DesktopHermesUpgradeNetwork.urlSessionProxy(["https_proxy": "http://user:private@proxy.invalid:80"]))
    }
    func testNativeNodeFetchActuallyUsesProxy() async throws {
        let candidates = ["/opt/homebrew/bin/node", "/usr/local/bin/node", FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".local/bin/node").path]
        guard let node = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else { throw XCTSkip("Node unavailable") }
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        var env = DesktopHermesUpgradeNetwork.environment(proxy: .init(http: .init(host: "127.0.0.1", port: 7897), https: .init(host: "127.0.0.1", port: 7897)), inherited: [:])
        env["PATH"] = "/usr/bin:/bin"; env["HOME"] = home.path
        let script = #"""
        import os,sys,threading,http.server,subprocess
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                assert self.path in ['http://hermes-proxy-test.invalid/fixture','/fixture']
                self.send_response(200);self.end_headers();self.wfile.write(b'proxy-reached')
            def do_CONNECT(self):
                assert self.path == 'hermes-proxy-test.invalid:80'
                self.send_response(200);self.end_headers();self.handle_one_request()
            def log_message(self,*a):pass
        server=http.server.HTTPServer(('127.0.0.1',0),Handler)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        env=dict(os.environ)
        for key in ['http_proxy','HTTP_PROXY','https_proxy','HTTPS_PROXY','GLOBAL_AGENT_HTTP_PROXY','GLOBAL_AGENT_HTTPS_PROXY']:
            env[key]='http://127.0.0.1:'+str(server.server_port)
        try:
            r=subprocess.run([sys.argv[1],'-e',"fetch('http://hermes-proxy-test.invalid/fixture',{signal:AbortSignal.timeout(3000)}).then(r=>r.text()).then(x=>{if(x!=='proxy-reached')process.exit(2);console.log(x)}).catch(()=>process.exit(1))"],env=env,timeout=8,capture_output=True,text=True)
            print(r.stdout.strip());sys.exit(r.returncode)
        finally:server.shutdown()
        """#
        let result = try await DesktopPosixProcessRunner().run(executable: URL(fileURLWithPath: "/usr/bin/python3"), arguments: ["-c", script, node], environment: env, workingDirectory: home, onLine: { _ in })
        XCTAssertEqual(result.exitStatus, 0)
        XCTAssertEqual(result.standardOutputTail.last, "proxy-reached")
    }
    func testRealCheckpointRejectsRemovedDependencyEnvironmentAndChangedCheckout() async throws {
        let home = try fixtureHome(); defer { try? FileManager.default.removeItem(at: home) }
        let paths = try DesktopLocalHermesPaths(homeDirectory: home)
        let root = paths.checkoutRoot
        func write(_ url: URL, _ value: String, executable: Bool = false) throws {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data(value.utf8).write(to: url)
            if executable { try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.path) }
        }
        try write(root.appendingPathComponent(".git/HEAD"), target.commit)
        try write(root.appendingPathComponent("pm/lock.json"), #"{"packages":{"python":{"version":"3.14.0"}}}"#)
        try write(root.appendingPathComponent("uv.lock"), "fixture")
        try write(home.appendingPathComponent(".local/share/uv/python/cpython-3.14.0-macos-arm64/bin/python3"), "fixture", executable: true)
        let key = SHA256.hash(data: Data(root.path.utf8)).map { String(format: "%02x", $0) }.joined().prefix(16)
        let state = paths.hermesHome.appendingPathComponent("installs/\(key)")
        let venv = state.appendingPathComponent("environments/fixture/venv")
        try write(venv.appendingPathComponent("pyvenv.cfg"), "home=fixture")
        try write(venv.appendingPathComponent("bin/python"), "fixture", executable: true)
        try FileManager.default.createDirectory(at: venv.appendingPathComponent("lib"), withIntermediateDirectories: true)
        let facts = try JSONSerialization.data(withJSONObject: ["packages": ["venv": ["environment": venv.path]]])
        try facts.write(to: state.appendingPathComponent("facts.json"))
        try write(paths.hermesHome.appendingPathComponent(".env"), "fixture-secret-not-read")
        try FileManager.default.createDirectory(at: paths.hermesHome.appendingPathComponent("sessions"), withIntermediateDirectories: true)
        let session = DesktopHermesUpgradeSession(paths: paths)
        _ = await session.resume(target: target, script: Data())
        for step in [DesktopHermesUpgradeStep.repository, .venv, .dependencies, .config] { await session.completed(step, target: target) }
        let valid = await session.resume(target: target, script: Data())
        XCTAssertEqual(valid, 4)
        try FileManager.default.removeItem(at: venv)
        let missing = await session.resume(target: target, script: Data())
        XCTAssertEqual(missing, 2)
        try write(root.appendingPathComponent(".git/HEAD"), String(repeating: "c", count: 40))
        let changed = await session.resume(target: target, script: Data())
        XCTAssertEqual(changed, 0)
    }

    func fixtureHome() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
}
private final class ProofBox: @unchecked Sendable {
    let lock = NSLock(); var changed: Set<DesktopHermesUpgradeStep> = []
    func value(_ step: DesktopHermesUpgradeStep) -> String { lock.withLock { step.rawValue + (changed.contains(step) ? "new" : "old") } }
    func change(_ step: DesktopHermesUpgradeStep) { lock.withLock { _ = changed.insert(step) } }
}
private struct DownloadScript: DesktopHermesInstallerScriptFetching {
    func fetchInstallerScript(from url: URL) async throws -> Data { Data("fixture".utf8) }
}
private actor RetryStageRunner: DesktopHermesInstallerProcessRunning {
    let failure: DesktopHermesUpgradeStep
    var failed = false
    var stages: [String] = []
    var lastEnvironment: [String: String] = [:]
    init(failure: DesktopHermesUpgradeStep) { self.failure = failure }
    func run(executable: URL, arguments: [String], environment: [String: String], workingDirectory: URL, onLine: @escaping @Sendable (String) -> Void) async throws -> DesktopHermesInstallerProcessResult {
        lastEnvironment = environment
        if arguments.contains("--manifest") {
            let data = try JSONSerialization.data(withJSONObject: ["protocol_version": 1, "stages": DesktopHermesStableInstaller.stages.map { ["name": $0, "title": $0] }])
            return .init(exitStatus: 0, standardOutputTail: [String(decoding: data, as: UTF8.self)], combinedTail: [])
        }
        let stage = arguments[2]; stages.append(stage)
        let fail = stage == failure.rawValue && !failed
        if fail { failed = true }
        return .init(exitStatus: fail ? 1 : 0, standardOutputTail: ["{\"ok\":\(!fail),\"stage\":\"\(stage)\",\"skipped\":false}"], combinedTail: [])
    }
}

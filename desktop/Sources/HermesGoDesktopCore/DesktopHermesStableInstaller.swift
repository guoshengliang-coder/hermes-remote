import Darwin
import Foundation

/// Phase 1: drive upstream's installer at the selected stable commit in the existing checkout.
/// No compatibility registry, maintenance coordinator, backup or rollback subsystem.
public struct DesktopHermesStableInstaller: Sendable {
    let paths: DesktopLocalHermesPaths
    let runner: any DesktopHermesInstallerProcessRunning
    let fetcher: any DesktopHermesInstallerScriptFetching
    let inheritedEnvironment: [String: String]
    let proxy: DesktopSystemProxy

    public init(paths: DesktopLocalHermesPaths, proxy: DesktopSystemProxy,
                runner: any DesktopHermesInstallerProcessRunning = DesktopPosixProcessRunner(),
                fetcher: any DesktopHermesInstallerScriptFetching = DesktopHermesInstallerURLSessionFetcher(),
                inheritedEnvironment: [String: String] = ProcessInfo.processInfo.environment) {
        self.paths = paths; self.proxy = proxy; self.runner = runner; self.fetcher = fetcher
        self.inheritedEnvironment = inheritedEnvironment
    }

    public static let stages = ["repository", "venv", "python-deps", "config", "products", "complete"]

    public func run(target: DesktopHermesUpgradeTarget,
                    progress: @escaping @Sendable (String) -> Void,
                    output: @escaping @Sendable (String) -> Void) async throws {
        guard getuid() != 0 else { throw DesktopHermesInstallFailure.runningAsRoot }
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("hermes-stable-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: work) }
        progress("下载官方升级程序")
        let url = URL(string: "https://raw.githubusercontent.com/NousResearch/hermes-agent/\(target.commit)/scripts/install.sh")!
        let data = try await fetcher.fetchInstallerScript(from: url)
        let script = work.appendingPathComponent("install.sh")
        try data.write(to: script)
        let environment = environment()
        let manifestRun = try await runner.run(executable: URL(fileURLWithPath: "/bin/bash"),
            arguments: [script.path, "--manifest"], environment: environment,
            workingDirectory: paths.homeDirectory, onLine: { output(SecretRedactor.redact($0)) })
        guard manifestRun.exitStatus == 0 else { throw DesktopHermesInstallFailure.protocolMismatch(detail: "manifest failed") }
        let manifest = try DesktopHermesInstallerManifest.parse(manifestRun.standardOutputTail.joined(separator: "\n"))
        let common = ["--dir", paths.checkoutRoot.path, "--hermes-home", paths.hermesHome.path,
                      "--commit", target.commit, "--skip-browser", "--skip-computer-use"]
        // Require every required stage before running the first stage.
        guard Self.stages.allSatisfy({ name in manifest.stages.contains { $0.name == name && !$0.needsUserInput } })
        else { throw DesktopHermesInstallFailure.protocolMismatch(detail: "required upgrade stages missing") }
        for name in Self.stages {
            try Task.checkCancellation()
            let stage = manifest.stages.first { $0.name == name }!
            progress(stage.titleChinese)
            let result = try await runner.run(executable: URL(fileURLWithPath: "/bin/bash"),
                arguments: [script.path, "--stage", name, "--non-interactive", "--json"] + common,
                environment: environment, workingDirectory: paths.homeDirectory,
                onLine: { output(SecretRedactor.redact($0)) })
            guard result.exitStatus == 0,
                  let frame = DesktopHermesInstallerStageResult.parse(result.standardOutputTail),
                  frame.stage == name, frame.ok, !frame.skipped
            else { throw DesktopHermesInstallFailure.stageFailed(stage: name, detail: "official stage did not complete") }
        }
    }

    func environment() -> [String: String] {
        var env: [String: String] = [:]
        for key in ["USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR"] {
            env[key] = inheritedEnvironment[key]
        }
        env["HOME"] = paths.homeDirectory.path
        env["HERMES_HOME"] = paths.hermesHome.path
        env["PATH"] = [paths.homeDirectory.appendingPathComponent(".local/bin").path,
                       "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"].joined(separator: ":")
        env["SHELL"] = "/bin/bash"; env["LANG"] = env["LANG"] ?? "en_US.UTF-8"
        env["TERM"] = "dumb"; env["NO_COLOR"] = "1"; env["NONINTERACTIVE"] = "1"
        env["GIT_TERMINAL_PROMPT"] = "0"; env["SUDO_ASKPASS"] = "/usr/bin/false"
        for (key, value) in proxy.environment(inheriting: inheritedEnvironment) { env[key] = value }
        return env
    }
}

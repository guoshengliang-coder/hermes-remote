// Disposable acceptance helper. No production labels reach launchctl.
import Darwin
import Foundation
import HermesGoDesktopCore

private struct Refused: Error {}
private struct IsolatedLaunchctl: CommandRunning {
    let workspace: URL
    let label: String
    var domain: String { "gui/\(getuid())" }
    var plist: URL { workspace.appendingPathComponent("\(DesktopLaunchAgentController<SystemCommandRunner>.hermesLabel).plist") }
    func run(executable: URL, arguments: [String]) -> CommandResult {
        let original = "\(domain)/\(DesktopLaunchAgentController<SystemCommandRunner>.hermesLabel)"
        let isolated = "\(domain)/\(label)"
        let mapped: [String]
        guard executable.path == "/bin/launchctl" else { return CommandResult(status: 64) }
        if arguments == ["print", original] { mapped = ["print", isolated] }
        else if arguments == ["bootout", original] { mapped = ["bootout", isolated] }
        else if arguments == ["bootstrap", domain, plist.path] { mapped = arguments }
        else { return CommandResult(status: 64) }
        let child = Process()
        child.executableURL = executable; child.arguments = mapped
        child.standardInput = FileHandle.nullDevice
        child.standardOutput = FileHandle.nullDevice; child.standardError = FileHandle.nullDevice
        child.environment = ["PATH": "/usr/bin:/bin", "HOME": workspace.path]
        do { try child.run() } catch { return CommandResult(status: 70) }
        let deadline = Date().addingTimeInterval(10)
        while child.isRunning && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        if child.isRunning { child.terminate(); Thread.sleep(forTimeInterval: 0.1) }
        if child.isRunning { kill(child.processIdentifier, SIGKILL) }
        child.waitUntilExit()
        return CommandResult(status: child.terminationStatus)
    }
}

@main private struct AcceptanceHelper {
    static func main() {
        do { try run() }
        catch {
            // Fixed diagnostics only. Never print launchctl's environment or token-file contents.
            fputs("HR-HERMESCOMPAT-004：隔离服务验证未完成，请查看测试记录。 / Isolated service verification incomplete; review the test record.\n", stderr)
            exit(1)
        }
    }
    static func run() throws {
        let args = Array(CommandLine.arguments.dropFirst())
        guard args.count == 3, ["prepare", "start", "stop", "inspect"].contains(args[0]),
              ["0.21.3", "0.22.5"].contains(args[2]) else { throw Refused() }
        let workspace = URL(fileURLWithPath: args[1]).standardizedFileURL
        let root = workspace.deletingLastPathComponent().deletingLastPathComponent()
        let temporary = FileManager.default.temporaryDirectory.resolvingSymlinksInPath().standardizedFileURL
        // Foundation intentionally presents /private/var as /var. Check the caller's path with
        // POSIX realpath; URL equality below still fences the same resolved temporary directory.
        guard let canonical = realpath(args[1], nil) else { throw Refused() }
        defer { free(canonical) }
        guard String(cString: canonical) == args[1], workspace.resolvingSymlinksInPath() == workspace,
              root.deletingLastPathComponent() == temporary,
              root.lastPathComponent.hasPrefix("hermes-compat-lab-"),
              workspace.deletingLastPathComponent().lastPathComponent == "tmp",
              workspace.lastPathComponent.hasPrefix("launchd-"),
              UUID(uuidString: String(workspace.lastPathComponent.dropFirst(8))) != nil else { throw Refused() }
        for path in [root, workspace] {
            var info = stat()
            guard lstat(path.path, &info) == 0, info.st_mode & S_IFMT == S_IFDIR,
                  info.st_uid == getuid(), info.st_mode & 0o077 == 0 else { throw Refused() }
        }
        let label = "com.hermesgo.compat-lab." + workspace.lastPathComponent.dropFirst(8).lowercased()
        let runner = IsolatedLaunchctl(workspace: workspace, label: label)
        let controller = try DesktopLaunchAgentController(userID: getuid(), launchAgentsRoot: workspace,
            runner: runner, convergenceAttempts: 50, convergenceDelay: 0.1)
        if args[0] == "prepare" {
            let repo = root.appendingPathComponent("user/.hermes/hermes-agent")
            let executable = repo.appendingPathComponent(args[2] == "0.21.3" ? "venv/bin/hermes" : ".hermes/bin/hermes")
            let installation = DesktopLocalHermesInstallation(executable: executable, checkoutRoot: repo,
                hermesHome: root.appendingPathComponent("user/.hermes"), commit: String(repeating: "a", count: 40),
                version: args[2], identityChangedAt: Date())
            let launcher = workspace.appendingPathComponent("hermes-local-serve")
            let agent = DesktopLocalHermesLaunchAgent(launcher: launcher, installation: installation,
                sessionTokenFile: workspace.appendingPathComponent("session.token"),
                standardOutput: workspace.appendingPathComponent("service.private.log"),
                standardError: workspace.appendingPathComponent("service.private.log"))
            for (path, data, mode) in [(launcher, try DesktopLocalHermesLauncher.script(executable: executable), 0o700),
                                      (runner.plist, try agent.encodedPropertyList(), 0o600)] {
                guard !FileManager.default.fileExists(atPath: path.path) else { throw Refused() }
                try data.write(to: path, options: .withoutOverwriting)
                guard chmod(path.path, mode_t(mode)) == 0 else { throw Refused() }
            }
            print("prepared")
            return
        }
        let values = try PropertyListSerialization.propertyList(from: Data(contentsOf: runner.plist), format: nil) as? [String: Any]
        guard values?["Label"] as? String == label else { throw Refused() }
        if args[0] == "start" { try controller.startHermes(plistURL: runner.plist) }
        else if args[0] == "stop" { try controller.stopHermes() }
        else {
            // Only the isolated Hermes label is read; account/legacy daily services are never queried.
            print(runner.run(executable: URL(fileURLWithPath: "/bin/launchctl"),
                arguments: ["print", "gui/\(getuid())/\(DesktopLaunchAgentController<SystemCommandRunner>.hermesLabel)"]).status == 0 ? "loaded" : "unloaded")
            return
        }
        print("done")
    }
}

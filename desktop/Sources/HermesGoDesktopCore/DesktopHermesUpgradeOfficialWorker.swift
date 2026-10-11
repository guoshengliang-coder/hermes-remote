import Darwin
import Foundation

/// A process owns upstream's real marker + checkout lock. Its fixed-target takeover children
/// inherit that lock; dropping the UI/Swift handle never sends them a signal after commit.
public final class DesktopHermesUpgradeOfficialWorker: DesktopHermesUpgradeLockHolding, @unchecked Sendable {
    public struct Configuration: Sendable {
        public let runID: String
        public let upstreamUpdateID: String
        public let sourceRoot: URL
        public let checkout: URL
        public let hermesHome: URL
        public let runDirectory: URL
        public let journal: URL
        public let sourceCommit: String
        public let targetCommit: String
        public let targetVersion: String
        public let preUpdateVersion: String
        /// Captured official UpdatePlan, not a guessed empty fleet.
        public let plan: Data
        public init(runID: String, upstreamUpdateID: String, sourceRoot: URL, checkout: URL,
                    hermesHome: URL, runDirectory: URL, journal: URL, sourceCommit: String,
                    targetCommit: String, targetVersion: String, preUpdateVersion: String, plan: Data) {
            self.runID = runID; self.upstreamUpdateID = upstreamUpdateID; self.sourceRoot = sourceRoot
            self.checkout = checkout; self.hermesHome = hermesHome; self.runDirectory = runDirectory
            self.journal = journal; self.sourceCommit = sourceCommit; self.targetCommit = targetCommit
            self.targetVersion = targetVersion; self.preUpdateVersion = preUpdateVersion; self.plan = plan
        }
        fileprivate func json() throws -> Data {
            guard UUID(uuidString: runID) != nil, UUID(uuidString: upstreamUpdateID) != nil,
                  DesktopLocalHermesDetector.isObjectID(sourceCommit), DesktopLocalHermesDetector.isObjectID(targetCommit),
                  plan.count <= 32 * 1024, let object = try JSONSerialization.jsonObject(with: plan) as? [String: Any]
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            _ = try DesktopHermesUpgradeTarget(version: targetVersion, commit: targetCommit)
            return try JSONSerialization.data(withJSONObject: ["schema": 1, "runID": runID,
                "upstreamUpdateID": upstreamUpdateID, "sourceRoot": sourceRoot.path, "checkout": checkout.path,
                "hermesHome": hermesHome.path, "runDirectory": runDirectory.path, "journal": journal.path,
                "sourceCommit": sourceCommit, "targetCommit": targetCommit,
                "targetVersion": targetVersion,
                "preUpdateVersion": preUpdateVersion, "plan": object], options: [.sortedKeys])
        }
    }

    private struct Event: Decodable {
        let schema: Int
        let event: String
        let exitCode: Int32?
    }
    public let runID: String
    public let upstreamUpdateID: String?
    private let process: Process
    private let input: FileHandle
    private let output: FileHandle
    private let state = NSLock()
    private var busy = false
    private var executed = false
    private var closed = false
    private var buffer = Data()

    public static var bundledScript: URL? {
        if Bundle.main.bundleURL.pathExtension == "app" {
            guard let resources = Bundle.main.resourceURL,
                  let bundle = Bundle(url: resources.appendingPathComponent("HermesGoDesktop_HermesGoDesktopCore.bundle"))
            else { return nil }
            return bundle.url(forResource: "hermes-upgrade-worker", withExtension: "py")
        }
        return Bundle.module.url(forResource: "hermes-upgrade-worker", withExtension: "py")
    }

    /// Lock acquisition is inert with respect to Git/data/services. Source verification and
    /// upstream's concurrent-update refusal happen before the ready event is accepted.
    public static func acquire(configuration: Configuration, python: URL, userHome: URL,
                               path: String = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin",
                               proxyEnvironment: [String: String] = [:]) async throws -> DesktopHermesUpgradeOfficialWorker {
        guard let script = bundledScript else { throw DesktopHermesUpgradeError.preflightBlocked }
        return try await Task.detached {
            try launch(configuration: configuration, python: python, userHome: userHome,
                       path: path, proxyEnvironment: proxyEnvironment, script: script)
        }.value
    }

    // The test entry only substitutes the packaged bridge; no caller-supplied updater command is
    // part of the production wire protocol.
    static func launch(configuration: Configuration, python: URL, userHome: URL, path: String,
                       proxyEnvironment: [String: String], script: URL) throws -> DesktopHermesUpgradeOfficialWorker {
        do {
            let data = try configuration.json()
            guard data.count <= 64 * 1024 else { throw DesktopHermesUpgradeError.preflightBlocked }
            try checkDirectory(configuration.runDirectory)
            let config = configuration.runDirectory.appendingPathComponent("worker-configuration.json")
            // A resumed run uses the same frozen IDs and source. Never overwrite different custody.
            if FileManager.default.fileExists(atPath: config.path) {
                guard try privateData(config) == data else { throw DesktopHermesUpgradeError.recoveryRequired }
            } else {
                let fd = Darwin.open(config.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
                guard fd >= 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
                defer { Darwin.close(fd) }
                try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
                guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
                let directory = Darwin.open(configuration.runDirectory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
                guard directory >= 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
                defer { Darwin.close(directory) }
                guard fsync(directory) == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
            }
            let temporary = configuration.runDirectory.appendingPathComponent("tmp")
            if !FileManager.default.fileExists(atPath: temporary.path) {
                try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: false,
                                                        attributes: [.posixPermissions: 0o700])
            }
            try checkDirectory(temporary)
            var environment = ["HOME": userHome.path, "HERMES_HOME": configuration.hermesHome.path,
                "PATH": path, "LANG": "en_US.UTF-8", "LC_ALL": "C", "TERM": "dumb", "NO_COLOR": "1",
                "TMPDIR": temporary.path, "XDG_CACHE_HOME": temporary.appendingPathComponent("cache").path,
                "UV_CACHE_DIR": temporary.appendingPathComponent("uv").path,
                "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1", "GIT_TERMINAL_PROMPT": "0",
                "GIT_NO_REPLACE_OBJECTS": "1", "GIT_GRAFT_FILE": "/dev/null",
                "SUDO_ASKPASS": "/usr/bin/false", "NONINTERACTIVE": "1", "HERMES_UPDATE_UI_ACTIVE": "1",
                "HERMES_UPDATE_CORRELATION_ID": configuration.runID]
            let allowedProxyKeys = Set(["HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                                        "http_proxy", "https_proxy", "all_proxy", "no_proxy"])
            for (key, value) in proxyEnvironment where allowedProxyKeys.contains(key) { environment[key] = value }
            let process = Process(), pipeIn = Pipe(), pipeOut = Pipe()
            process.executableURL = python
            process.arguments = ["-I", "-S", "-B", script.path, config.path]
            process.currentDirectoryURL = configuration.runDirectory
            process.environment = environment
            process.standardInput = pipeIn; process.standardOutput = pipeOut
            process.standardError = FileHandle.nullDevice
            let worker = DesktopHermesUpgradeOfficialWorker(configuration: configuration, process: process,
                input: pipeIn.fileHandleForWriting, output: pipeOut.fileHandleForReading)
            try process.run()
            let ready: Event
            do { ready = try worker.nextEvent(timeout: 30) }
            catch { throw DesktopHermesUpgradeError.preflightBlocked }
            guard ready.event == "locked" else {
                throw DesktopHermesUpgradeError.preflightBlocked
            }
            return worker
        } catch let error as DesktopHermesUpgradeError { throw error }
        catch { throw DesktopHermesUpgradeError.preflightBlocked }
    }

    private init(configuration: Configuration, process: Process, input: FileHandle, output: FileHandle) {
        runID = configuration.runID; upstreamUpdateID = configuration.upstreamUpdateID
        self.process = process; self.input = input; self.output = output
    }
    deinit { try? input.close(); try? output.close() }

    /// Exactly one update command per lock handle. The durable GO journal is checked again by the
    /// child before mutation. The caller still owns stopped-writer/backup and completion checks.
    public func runOfficialUpdate() async throws -> Int32 {
        try state.withLock {
            guard !closed, !busy, !executed else { throw DesktopHermesUpgradeError.recoveryRequired }
            busy = true; executed = true
        }
        defer { state.withLock { busy = false } }
        return try await Task.detached { [self] in
            do {
                try send("update")
                while true {
                    let event = try nextEvent(timeout: nil)
                    switch event.event {
                    case "updating": continue
                    case "finished":
                        guard let code = event.exitCode else { throw DesktopHermesUpgradeError.recoveryRequired }
                        return code
                    default: throw DesktopHermesUpgradeError.recoveryRequired
                    }
                }
            } catch { throw DesktopHermesUpgradeError.recoveryRequired }
        }.value
    }

    public func release() throws {
        try state.withLock {
            guard !busy else { throw DesktopHermesUpgradeError.recoveryRequired }
            guard !closed else { return }
            closed = true
            try send("release")
            try input.close()
        }
    }

    private func send(_ command: String) throws {
        var data = try JSONSerialization.data(withJSONObject: ["command": command, "runID": runID])
        data.append(10)
        try input.write(contentsOf: data)
    }
    private func nextEvent(timeout: TimeInterval?) throws -> Event {
        let deadline = timeout.map { ProcessInfo.processInfo.systemUptime + $0 }
        while true {
            if let index = buffer.firstIndex(of: 10) {
                let data = buffer.prefix(upTo: index)
                buffer.removeSubrange(...index)
                guard data.count <= 4096, let event = try? JSONDecoder().decode(Event.self, from: data), event.schema == 1,
                      ["locked", "updating", "finished", "refused"].contains(event.event)
                else { throw DesktopHermesUpgradeError.recoveryRequired }
                return event
            }
            guard buffer.count <= 4096, deadline == nil || ProcessInfo.processInfo.systemUptime < deadline! else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            var descriptor = pollfd(fd: output.fileDescriptor, events: Int16(POLLIN | POLLHUP), revents: 0)
            let result = Darwin.poll(&descriptor, 1, 250)
            if result < 0 && errno == EINTR { continue }
            guard result >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            if result == 0 { continue }
            // FileHandle.read(upToCount:) can wait to fill its buffer on a pipe; the updater is
            // waiting for our command, so use one POSIX read of the currently available bytes.
            var bytes = [UInt8](repeating: 0, count: 4096)
            let count = Darwin.read(output.fileDescriptor, &bytes, bytes.count)
            if count < 0 && errno == EINTR { continue }
            guard count > 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            buffer.append(contentsOf: bytes.prefix(count))
        }
    }
    private static func checkDirectory(_ directory: URL) throws {
        var info = stat()
        guard directory.path != "/", directory.resolvingSymlinksInPath().path == directory.path,
              lstat(directory.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { throw DesktopHermesUpgradeError.preflightBlocked }
    }
    private static func privateData(_ url: URL) throws -> Data {
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_nlink == 1,
              info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 64 * 1024,
              let data = try FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: 64 * 1024 + 1),
              data.count <= 64 * 1024 else { throw DesktopHermesUpgradeError.recoveryRequired }
        return data
    }
}

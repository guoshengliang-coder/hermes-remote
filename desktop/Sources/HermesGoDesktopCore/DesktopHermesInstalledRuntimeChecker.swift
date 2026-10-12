import Foundation

public protocol DesktopHermesInstalledRuntimeChecking: Sendable {
    func verify(_ installation: DesktopLocalHermesInstallation, environment: [String: String]) async throws
}

/// File presence is only detection. Load the actual serve command through the selected published
/// launcher, without opening a port or receiving credentials. PM can select/sync its own runtime.
/// The runner cancels the entire process group on timeout; output is never copied into diagnostics.
public struct DesktopHermesInstalledRuntimeChecker: DesktopHermesInstalledRuntimeChecking {
    private let runner: any DesktopHermesInstallerProcessRunning
    private let timeoutNanoseconds: UInt64

    public init(runner: any DesktopHermesInstallerProcessRunning = DesktopPosixProcessRunner(),
                timeoutNanoseconds: UInt64 = 300_000_000_000) {
        self.runner = runner
        self.timeoutNanoseconds = timeoutNanoseconds
    }

    public func verify(_ installation: DesktopLocalHermesInstallation, environment: [String: String]) async throws {
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask {
                    let result = try await runner.run(
                        executable: installation.executable, arguments: ["serve", "--help"],
                        environment: environment, workingDirectory: installation.checkoutRoot, onLine: { _ in }
                    )
                    guard result.exitStatus == 0 else {
                        throw DesktopHermesInstallFailure.notUsable(stage: "verify", detail: "serve-help exit=\(result.exitStatus)")
                    }
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: timeoutNanoseconds)
                    throw DesktopHermesInstallFailure.notUsable(stage: "verify", detail: "serve-help timed-out")
                }
                defer { group.cancelAll() }
                _ = try await group.next()
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let failure as DesktopHermesInstallFailure {
            throw failure
        } catch {
            throw DesktopHermesInstallFailure.notUsable(stage: "verify", detail: "serve-help could not run")
        }
    }
}

import Foundation
import Network

public protocol DesktopHermesShutdownChecking: Sendable {
    func waitUntilStopped(
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool
}

/// Proves the old loopback listener has gone away before launchd starts the replacement. Waiting
/// only for the launchd label is insufficient: a terminating Hermes process can keep port 9119
/// open for several seconds after `bootout` succeeds.
public struct DesktopHermesShutdownChecker: DesktopHermesShutdownChecking {
    private let connectionTimeoutNanoseconds: UInt64

    public init(connectionTimeoutNanoseconds: UInt64 = 500_000_000) {
        self.connectionTimeoutNanoseconds = min(
            max(connectionTimeoutNanoseconds, 1),
            5_000_000_000
        )
    }

    public func waitUntilStopped(
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool {
        guard (1...300).contains(maximumAttempts),
              contract.baseURL.host == DesktopHermesRuntimeContract.loopbackHost,
              contract.baseURL.port == DesktopHermesRuntimeContract.loopbackPort
        else { return false }
        for attempt in 0..<maximumAttempts {
            if await listenerAcceptsConnections() == false { return true }
            if attempt + 1 < maximumAttempts, delayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: delayNanoseconds)
            }
        }
        return false
    }

    private func listenerAcceptsConnections() async -> Bool {
        await withCheckedContinuation { continuation in
            let queue = DispatchQueue(label: "com.hermesgo.desktop.hermes-shutdown-probe")
            let connection = NWConnection(
                host: NWEndpoint.Host(DesktopHermesRuntimeContract.loopbackHost),
                port: NWEndpoint.Port(rawValue: UInt16(DesktopHermesRuntimeContract.loopbackPort))!,
                using: .tcp
            )
            let probe = DesktopLoopbackConnectionProbe(
                connection: connection,
                continuation: continuation
            )
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    probe.finish(true)
                case .failed, .cancelled:
                    probe.finish(false)
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(
                deadline: .now() + .nanoseconds(Int(connectionTimeoutNanoseconds))
            ) {
                // An ambiguous timeout must not authorize the replacement process to start.
                // Only an explicit connection failure proves the old listener is gone.
                probe.finish(true)
            }
        }
    }
}

private final class DesktopLoopbackConnectionProbe: @unchecked Sendable {
    private let lock = NSLock()
    private let connection: NWConnection
    private var continuation: CheckedContinuation<Bool, Never>?

    init(connection: NWConnection, continuation: CheckedContinuation<Bool, Never>) {
        self.connection = connection
        self.continuation = continuation
    }

    func finish(_ result: Bool) {
        let pending = lock.withLock { () -> CheckedContinuation<Bool, Never>? in
            defer { continuation = nil }
            return continuation
        }
        guard let pending else { return }
        connection.cancel()
        pending.resume(returning: result)
    }
}

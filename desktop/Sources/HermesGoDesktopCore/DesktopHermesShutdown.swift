import Foundation
import Network

public protocol DesktopHermesShutdownChecking: Sendable {
    func waitUntilStopped(
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool
}

/// What one loopback connection attempt proves about the port.
public enum DesktopLoopbackProbeDecision: Equatable, Sendable {
    /// A listener accepted the connection.
    case listening
    /// Nothing is listening: the kernel refused the connection.
    case notListening
    /// Not decided yet (setting up, preparing, or waiting for a reason that proves nothing).
    case undecided

    /// Maps one `NWConnection` state onto a decision.
    ///
    /// On macOS a TCP connection to a loopback port with no listener does **not** end in `.failed`:
    /// Network.framework reports `.waiting(POSIXErrorCode.ECONNREFUSED)` within milliseconds and then
    /// stays there, waiting for "better conditions" that never come. Treating only `.failed` as
    /// proof of a free port therefore made every shutdown wait time out once the old Hermes had gone
    /// (the 2026-09-21 incident, `docs/DESKTOP_E4_TEST_RECORD.md`). A refusal is the kernel's own
    /// statement that no socket is listening, so it decides. Any other waiting reason stays
    /// undecided, and the caller's timeout keeps treating an undecided probe as "still listening".
    /// The same holds for `.failed`: a local resource failure (for example `EMFILE`) is not proof
    /// that the old Hermes has released the port.
    public static func decide(_ state: NWConnection.State) -> DesktopLoopbackProbeDecision {
        switch state {
        case .ready:
            return .listening
        case .waiting(let error), .failed(let error):
            return provesNoListener(error) ? .notListening : .undecided
        case .setup, .preparing, .cancelled:
            // `.cancelled` only follows our own `cancel()`, which proves nothing about the port.
            return .undecided
        @unknown default:
            return .undecided
        }
    }

    /// Only a refusal proves the absence of a listener. A reset, a timeout or an unreachable network
    /// says something about a path, not about the port, so they stay undecided.
    public static func provesNoListener(_ error: NWError) -> Bool {
        if case .posix(let code) = error { return code == .ECONNREFUSED }
        return false
    }
}

/// Proves the old loopback listener has gone away before launchd starts the replacement. Waiting
/// only for the launchd label is insufficient: a terminating Hermes process can keep port 9119
/// open for several seconds after `bootout` succeeds.
public struct DesktopHermesShutdownChecker: DesktopHermesShutdownChecking {
    private let connectionTimeoutNanoseconds: UInt64
    private let probePort: UInt16
    private let log: DesktopServiceOperationLog?

    public init(
        connectionTimeoutNanoseconds: UInt64 = 500_000_000,
        log: DesktopServiceOperationLog? = nil
    ) {
        self.init(
            connectionTimeoutNanoseconds: connectionTimeoutNanoseconds,
            probePort: UInt16(DesktopHermesRuntimeContract.loopbackPort),
            log: log
        )
    }

    /// Tests probe an ephemeral loopback port; production always probes the contract's port.
    init(connectionTimeoutNanoseconds: UInt64, probePort: UInt16, log: DesktopServiceOperationLog? = nil) {
        self.connectionTimeoutNanoseconds = min(
            max(connectionTimeoutNanoseconds, 1),
            5_000_000_000
        )
        self.probePort = probePort
        self.log = log
    }

    public func waitUntilStopped(
        contract: DesktopHermesRuntimeContract,
        maximumAttempts: Int,
        delayNanoseconds: UInt64
    ) async throws -> Bool {
        guard (1...300).contains(maximumAttempts),
              contract.baseURL.host == DesktopHermesRuntimeContract.loopbackHost,
              contract.baseURL.port == DesktopHermesRuntimeContract.loopbackPort
        else {
            log?.record("wait-stopped result=invalid-input")
            return false
        }
        let started = Date()
        var lastDecision = DesktopLoopbackProbeDecision.undecided
        for attempt in 0..<maximumAttempts {
            lastDecision = await probe()
            if lastDecision == .notListening {
                log?.record(String(
                    format: "wait-stopped result=stopped attempts=%d elapsed=%.1fs",
                    attempt + 1, Date().timeIntervalSince(started)
                ))
                return true
            }
            if attempt + 1 < maximumAttempts, delayNanoseconds > 0 {
                try await Task.sleep(nanoseconds: delayNanoseconds)
            }
        }
        log?.record(String(
            format: "wait-stopped result=timed-out attempts=%d elapsed=%.1fs last=%@",
            maximumAttempts, Date().timeIntervalSince(started), String(describing: lastDecision)
        ))
        return false
    }

    /// One connection attempt. `.undecided` when the timeout expires first, which the loop treats
    /// like `.listening`: an ambiguous probe must not authorize the replacement process to start.
    private func probe() async -> DesktopLoopbackProbeDecision {
        await withCheckedContinuation { continuation in
            let queue = DispatchQueue(label: "com.hermesgo.desktop.hermes-shutdown-probe")
            let connection = NWConnection(
                host: NWEndpoint.Host(DesktopHermesRuntimeContract.loopbackHost),
                port: NWEndpoint.Port(rawValue: probePort)!,
                using: .tcp
            )
            let probe = DesktopLoopbackConnectionProbe(
                connection: connection,
                continuation: continuation
            )
            connection.stateUpdateHandler = { state in
                let decision = DesktopLoopbackProbeDecision.decide(state)
                if decision != .undecided { probe.finish(decision) }
            }
            connection.start(queue: queue)
            queue.asyncAfter(
                deadline: .now() + .nanoseconds(Int(connectionTimeoutNanoseconds))
            ) {
                probe.finish(.undecided)
            }
        }
    }
}

private final class DesktopLoopbackConnectionProbe: @unchecked Sendable {
    private let lock = NSLock()
    private let connection: NWConnection
    private var continuation: CheckedContinuation<DesktopLoopbackProbeDecision, Never>?

    init(
        connection: NWConnection,
        continuation: CheckedContinuation<DesktopLoopbackProbeDecision, Never>
    ) {
        self.connection = connection
        self.continuation = continuation
    }

    func finish(_ result: DesktopLoopbackProbeDecision) {
        let pending = lock.withLock { () -> CheckedContinuation<DesktopLoopbackProbeDecision, Never>? in
            defer { continuation = nil }
            return continuation
        }
        guard let pending else { return }
        // Cancelling reports `.cancelled` to the handler, which finds no continuation and returns.
        connection.cancel()
        pending.resume(returning: result)
    }
}

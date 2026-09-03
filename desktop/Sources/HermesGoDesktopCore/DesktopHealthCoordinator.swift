import Foundation

public struct DesktopHealthRefresh: Equatable, Sendable {
    public let legacy: LegacyConnectorSnapshot
    public let health: DesktopHealthSnapshot

    public init(legacy: LegacyConnectorSnapshot, health: DesktopHealthSnapshot) {
        self.legacy = legacy
        self.health = health
    }
}

public actor DesktopHealthCoordinator {
    private let inspector: LegacyConnectorInspector<SystemCommandRunner>
    private let prober: HTTPHealthProber

    public init() {
        inspector = LegacyConnectorInspector(runner: SystemCommandRunner())
        prober = HTTPHealthProber()
    }

    public func refresh(connectionProfile: ConnectionProfile?) async -> DesktopHealthRefresh {
        let observation = inspector.inspect()
        async let relay = relayResult(for: observation)
        async let hermes = hermesResult(for: observation)
        async let endToEnd = endToEndResult(for: connectionProfile)
        let results = await (relay, hermes, endToEnd)
        return DesktopHealthRefresh(
            legacy: observation,
            health: DesktopHealthAssembler.assemble(
                legacy: observation,
                relay: results.0,
                hermes: results.1,
                endToEnd: results.2
            )
        )
    }

    private func relayResult(for observation: LegacyConnectorSnapshot) async -> ProbeResult {
        guard let relayURL = observation.config.relayHealthURL else {
            return ProbeResult(level: .unavailable, detail: "缺少可观察的 Gateway 地址")
        }
        return await prober.probeRelay(relayURL)
    }

    private func hermesResult(for observation: LegacyConnectorSnapshot) async -> ProbeResult {
        guard let hermesURL = observation.config.hermesStatusURL else {
            return ProbeResult(level: .unavailable, detail: "Hermes 地址无效")
        }
        return await prober.probeHermes(hermesURL)
    }

    private func endToEndResult(for profile: ConnectionProfile?) async -> ProbeResult {
        guard let profile else {
            return ProbeResult(level: .unavailable, detail: "尚未保存 App Token，未执行")
        }
        return await prober.probeEndToEnd(profile)
    }
}

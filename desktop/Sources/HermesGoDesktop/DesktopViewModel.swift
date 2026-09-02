import AppKit
import Foundation
import HermesGoDesktopCore

@MainActor
final class DesktopViewModel: ObservableObject {
    @Published private(set) var health: DesktopHealthSnapshot = .checking
    @Published private(set) var legacy: LegacyConnectorSnapshot?
    @Published private(set) var isRefreshing = false
    @Published private(set) var connectionProfile: ConnectionProfile?
    @Published var profileName = "Mac mini"
    @Published var gatewayAddress = "https://mrlgs.net"
    @Published var appToken = ""
    @Published var isPairingCodeRevealed = false
    @Published private(set) var configurationIssue: DesktopIssue?
    @Published private(set) var configurationMessage: String?
    @Published private(set) var accountState: DesktopAccountState = .checking
    @Published private(set) var accountIssue: DesktopIssue?
    @Published private(set) var isAccountOperationInProgress = false

    private let inspector = LegacyConnectorInspector(runner: SystemCommandRunner())
    private let prober = HTTPHealthProber()
    private let profileStore: any ConnectionProfileStoring
    private let accountController: DesktopAccountController
    private var monitorTask: Task<Void, Never>?

    init(profileStore: any ConnectionProfileStoring = KeychainConnectionProfileStore()) {
        self.profileStore = profileStore
        let configuration = DesktopAccountConfiguration.load()
        let oauth = configuration.googleClientID.map { GoogleOAuthFlow(clientID: $0) }
        accountController = DesktopAccountController(
            api: AccountAPIClient(gatewayURL: configuration.gatewayURL),
            sessionStore: KeychainAccountSessionStore(),
            machineIdentityStore: KeychainConnectorMachineIdentityStore(),
            oauth: oauth,
            displayName: Host.current().localizedName ?? "Mac",
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                ?? "development"
        )
        do {
            if let profile = try profileStore.load() {
                connectionProfile = profile
                profileName = profile.name
                gatewayAddress = profile.gatewayURL.absoluteString
                appToken = profile.appToken
            }
        } catch {
            configurationIssue = DesktopIssue(
                code: .configurationLoadFailed,
                technicalCause: String(describing: error)
            )
        }
    }

    var statusTitle: String {
        switch health.overall {
        case .checking: "正在检查"
        case .healthy: "工作正常"
        case .degraded: "部分功能受限"
        case .needsAttention: "需要处理"
        }
    }

    var statusDetail: String {
        switch health.overall {
        case .checking: "正在确认旧 Connector 与连接链路"
        case .healthy: "这台 Mac 正在安全连接 Hermes GO"
        case .degraded: "主链路可用，但有一项能力需要确认"
        case .needsAttention: "连接链路中有一项关键检查未通过"
        }
    }

    var overallLevel: HealthLevel {
        switch health.overall {
        case .checking: .checking
        case .healthy: .healthy
        case .degraded: .degraded
        case .needsAttention: .failed
        }
    }

    func startMonitoring() {
        guard monitorTask == nil else { return }
        monitorTask = Task { [weak self] in
            await self?.refreshAccount(bootstrap: true)
            var cycle = 0
            while !Task.isCancelled {
                await self?.refresh()
                cycle += 1
                if cycle.isMultiple(of: 4) {
                    await self?.refreshAccount()
                }
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    func refreshAccount(bootstrap: Bool = false) async {
        guard !isAccountOperationInProgress else { return }
        if bootstrap { accountState = .checking }
        do {
            let state: DesktopAccountState
            if bootstrap {
                state = try await accountController.bootstrap()
            } else {
                state = try await accountController.refresh()
            }
            applyAccountState(state)
        } catch let issue as AccountClientError {
            accountIssue = DesktopIssue.account(issue)
        } catch {
            accountIssue = DesktopIssue(
                code: .configurationLoadFailed,
                technicalCause: String(describing: error)
            )
            if bootstrap { accountState = .signedOut }
        }
    }

    func signInAccount() async {
        guard !isAccountOperationInProgress else { return }
        isAccountOperationInProgress = true
        accountIssue = nil
        accountState = .signingIn
        defer { isAccountOperationInProgress = false }
        do {
            applyAccountState(try await accountController.signIn())
        } catch let error as GoogleOAuthError {
            accountState = .signedOut
            accountIssue = DesktopIssue.oauth(error)
        } catch let error as AccountClientError {
            accountState = .signedOut
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountState = .signedOut
            accountIssue = DesktopIssue(
                code: .configurationSaveFailed,
                technicalCause: String(describing: error)
            )
        }
    }

    func revokePhone(_ id: String) async {
        guard !isAccountOperationInProgress else { return }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            applyAccountState(try await accountController.revokePhone(id: id))
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
    }

    func signOutAccount() async {
        guard !isAccountOperationInProgress else { return }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            applyAccountState(try await accountController.signOut())
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .configurationSaveFailed,
                technicalCause: String(describing: error)
            )
        }
    }

    private func applyAccountState(_ state: DesktopAccountState) {
        accountState = state
        switch state {
        case .unavailable:
            accountIssue = DesktopIssue(code: .accountFeatureDisabled)
        case .needsSignIn(let issueCode):
            accountIssue = DesktopIssue(code: issueCode)
        default:
            accountIssue = nil
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        let inspector = self.inspector
        let observation = await Task.detached(priority: .utility) {
            inspector.inspect()
        }.value
        legacy = observation

        let checkedAt = Date()
        let agentHealth = ComponentHealth(
            component: .desktopAgent,
            level: observation.isRunning ? .healthy : (observation.isInstalled ? .failed : .unavailable),
            detail: observation.isRunning
                ? "旧 Connector 正在运行（兼容观察模式）"
                : (observation.isInstalled ? "已安装但当前未运行" : "未检测到旧 Connector"),
            checkedAt: checkedAt
        )

        let relayResult: ProbeResult
        if let relayURL = observation.config.relayHealthURL {
            relayResult = await prober.probeRelay(relayURL)
        } else {
            relayResult = ProbeResult(level: .unavailable, detail: "缺少可观察的 Gateway 地址")
        }

        let hermesResult: ProbeResult
        if let hermesURL = observation.config.hermesStatusURL {
            hermesResult = await prober.probeHermes(hermesURL)
        } else {
            hermesResult = ProbeResult(level: .unavailable, detail: "Hermes 地址无效")
        }

        let endToEndResult: ProbeResult
        if let connectionProfile {
            endToEndResult = await prober.probeEndToEnd(connectionProfile)
        } else {
            endToEndResult = ProbeResult(
                level: .unavailable,
                detail: "尚未保存 App Token，未执行"
            )
        }

        let relayDetail = relayResult.latencyMilliseconds.map {
            "\(relayResult.detail) · \($0) ms"
        } ?? relayResult.detail
        let hermesDetail = hermesResult.latencyMilliseconds.map {
            "\(hermesResult.detail) · \($0) ms"
        } ?? hermesResult.detail

        health = DesktopHealthSnapshot(
            components: [
                agentHealth,
                ComponentHealth(
                    component: .gateway,
                    level: relayResult.level,
                    detail: relayDetail,
                    checkedAt: checkedAt
                ),
                ComponentHealth(
                    component: .hermes,
                    level: hermesResult.level,
                    detail: hermesDetail,
                    checkedAt: checkedAt
                ),
                observerHealth(observation, checkedAt: checkedAt),
                ComponentHealth(
                    component: .endToEnd,
                    level: endToEndResult.level,
                    detail: endToEndResult.latencyMilliseconds.map {
                        "\(endToEndResult.detail) · \($0) ms"
                    } ?? endToEndResult.detail,
                    checkedAt: checkedAt,
                    issue: endToEndResult.issue
                ),
            ],
            checkedAt: checkedAt
        )
    }

    func saveConnectionProfile() async {
        configurationIssue = nil
        configurationMessage = nil
        let profile: ConnectionProfile
        do {
            profile = try ConnectionProfile.validated(
                name: profileName,
                gatewayAddress: gatewayAddress,
                appToken: appToken
            )
        } catch ConnectionProfileValidationError.invalidGatewayURL {
            configurationIssue = DesktopIssue(code: .invalidRelayURL)
            return
        } catch ConnectionProfileValidationError.pairingPayloadTooLarge {
            configurationIssue = DesktopIssue(code: .pairingPayloadTooLarge)
            return
        } catch {
            configurationIssue = DesktopIssue(code: .incompletePairingConfiguration)
            return
        }

        do {
            try profileStore.save(profile)
            connectionProfile = profile
            profileName = profile.name
            gatewayAddress = profile.gatewayURL.absoluteString
            appToken = profile.appToken
            isPairingCodeRevealed = false
            configurationMessage = "已安全保存到这台 Mac 的 Keychain，正在执行端到端检查。"
            await refresh()
            let endToEnd = health.component(.endToEnd)
            if let issue = endToEnd.issue {
                configurationMessage = nil
                configurationIssue = issue
            } else if endToEnd.level == .healthy {
                configurationMessage = "已保存到 Keychain，端到端连接检查通过。"
            }
        } catch {
            configurationIssue = DesktopIssue(
                code: .configurationSaveFailed,
                technicalCause: String(describing: error)
            )
        }
    }

    func copyDiagnostics(_ issue: DesktopIssue) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(issue.sanitizedDiagnostic, forType: .string)
    }

    private func observerHealth(
        _ observation: LegacyConnectorSnapshot,
        checkedAt: Date
    ) -> ComponentHealth {
        guard observation.isInstalled else {
            return ComponentHealth(
                component: .observer,
                level: .unavailable,
                detail: "未检测到旧 Connector",
                checkedAt: checkedAt
            )
        }
        return ComponentHealth(
            component: .observer,
            level: observation.config.observerEnabled ? .checking : .unavailable,
            detail: observation.config.observerEnabled
                ? "按现有配置启用，阶段 0 尚未独立上报"
                : "现有配置已关闭",
            checkedAt: checkedAt
        )
    }

    func openHermes() {
        guard let url = legacy?.config.hermesBaseURL else { return }
        NSWorkspace.shared.open(url)
    }

    func openLegacyLogDirectory() {
        guard let directory = legacy?.installDirectory else { return }
        NSWorkspace.shared.open(directory)
    }
}

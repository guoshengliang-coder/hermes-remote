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
    @Published private(set) var accountOperationMessage: String?
    @Published private(set) var isAccountOperationInProgress = false

    private let healthCoordinator = DesktopHealthCoordinator()
    private let profileStore: any ConnectionProfileStoring
    private let accountController: DesktopAccountController
    private var monitorTask: Task<Void, Never>?

    init(profileStore: (any ConnectionProfileStoring)? = nil) {
        let configuration = DesktopAccountConfiguration.load()
        self.profileStore = profileStore ?? KeychainConnectionProfileStore(
            service: configuration.keychainService(
                base: "com.hermesgo.desktop.connection-profile"
            )
        )
        let oauth = configuration.googleClientID.map { GoogleOAuthFlow(clientID: $0) }
        accountController = DesktopAccountController(
            api: AccountAPIClient(gatewayURL: configuration.gatewayURL),
            sessionStore: KeychainAccountSessionStore(
                service: configuration.keychainService(
                    base: "com.hermesgo.desktop.account-session"
                )
            ),
            machineIdentityStore: KeychainConnectorMachineIdentityStore(
                service: configuration.keychainService(
                    base: "com.hermesgo.desktop.connector-machine-key"
                )
            ),
            oauth: oauth,
            displayName: Host.current().localizedName ?? "Mac",
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                ?? "development"
        )
        do {
            if let profile = try self.profileStore.load() {
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
        health.presentation.title
    }

    var statusDetail: String {
        health.presentation.detail
    }

    var overallLevel: HealthLevel {
        health.presentation.level
    }

    var accountPresentation: DesktopAccountPresentation {
        accountState.presentation(hasIssue: accountIssue != nil)
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
        accountOperationMessage = nil
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
        accountOperationMessage = nil
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
        accountOperationMessage = nil
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

    func performBindingAction(_ action: DesktopBindingAction) async {
        if action == .refresh {
            await refreshAccount()
            return
        }
        guard !isAccountOperationInProgress else { return }
        isAccountOperationInProgress = true
        accountIssue = nil
        accountOperationMessage = nil
        defer { isAccountOperationInProgress = false }

        do {
            let message: String
            switch action {
            case .createFirst:
                _ = try await accountController.createFirstBinding()
                message = "候选 Desktop 已登记；旧 Connector 保持运行，正在等待密钥与健康预检。"
            case .confirmFirst(let id, let generation):
                _ = try await accountController.confirmFirstBinding(id: id, generation: generation)
                message = "Desktop 账号绑定已确认。"
            case .createReplacement:
                _ = try await accountController.createReplacement()
                message = "新 Mac 候选已登记；原来的 Desktop 会继续工作到最终确认。"
            case .confirmReplacement(let requestID):
                _ = try await accountController.confirmReplacement(requestID: requestID)
                message = "新的 Desktop 绑定已启用。"
            case .unbind:
                try await accountController.unbind()
                message = "Connector 远程绑定已解除；账号登录和 Hermes 保持不变。"
            case .refresh:
                return
            }
            applyAccountState(try await accountController.refresh())
            accountOperationMessage = message
        } catch let error as GoogleOAuthError {
            accountIssue = DesktopIssue.oauth(error)
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
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

        let refreshed = await healthCoordinator.refresh(connectionProfile: connectionProfile)
        legacy = refreshed.legacy
        health = refreshed.health
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

    func openHermes() {
        guard let url = legacy?.config.hermesBaseURL else { return }
        NSWorkspace.shared.open(url)
    }

    func openLegacyLogDirectory() {
        guard let directory = legacy?.installDirectory else { return }
        NSWorkspace.shared.open(directory)
    }
}

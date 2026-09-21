import AppKit
import Darwin
import Foundation
import HermesGoDesktopCore

enum DesktopManagedBootstrapOperation: Equatable {
    case idle
    case preparing
    case awaitingConfirmation
    case committing
    case recovering
    case completed(releaseVersion: String, cleanupPending: Bool)
    case failed
}

enum DesktopComponentBootstrapOperation: Equatable {
    case idle
    case preparing
    case awaitingConfirmation
    case committing
    case completed(releaseVersion: String, cleanupPending: Bool)
    case failed
}

/// Install-when-missing (`DesktopHermesInstaller`): where the owner is in deciding how a Mac with no
/// Hermes gets one. Nothing runs before `.running`, which only `startHermesInstall()` enters — from
/// the confirmation sheet or an explicit retry.
enum DesktopHermesInstallPhase: Equatable {
    case hidden
    case offered(DesktopHermesInstallOffer)
    case running(DesktopHermesInstallOffer, DesktopHermesInstallRun, cancelling: Bool)
    case succeeded(version: String)
    case cancelled(DesktopHermesInstallOffer, DesktopHermesInstallRun)
    case failed(DesktopHermesInstallOffer, DesktopHermesInstallRun, DesktopIssue)

    var offer: DesktopHermesInstallOffer? {
        switch self {
        case .offered(let offer), .running(let offer, _, _), .cancelled(let offer, _), .failed(let offer, _, _): offer
        case .hidden, .succeeded: nil
        }
    }

    var isRunning: Bool {
        if case .running = self { return true }
        return false
    }
}

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
    @Published private(set) var bootstrapPlan: DesktopBootstrapPlan = .checking
    @Published private(set) var agentPresentation: DesktopAgentPresentation = .checking
    @Published private(set) var managedBootstrapOperation: DesktopManagedBootstrapOperation = .idle
    @Published private(set) var managedBootstrapPreparation: DesktopManagedBootstrapPreparation?
    @Published private(set) var managedBootstrapIssue: DesktopIssue?
    /// Kept apart from `managedBootstrapIssue` on purpose: drift is orthogonal to the bootstrap
    /// state machine, and that machine clears its issue on almost every transition. On a Mac whose
    /// managed installation reads `inconsistent` — which is the permanent state of any Mac that
    /// also runs its own hermes-agent — sharing one slot would mean the drift is never the thing
    /// shown.
    @Published private(set) var managedSchemaIssue: DesktopIssue?
    /// Which Hermes the managed service runs is reconciled on every refresh (local runtime mode,
    /// `docs/DESKTOP_PHASE0.md`). Its own slot for the same reason as `managedSchemaIssue`: it is
    /// orthogonal to the bootstrap state machine, which clears its issue on most transitions.
    @Published private(set) var localHermesIssue: DesktopIssue?
    @Published private(set) var hermesInstallPhase: DesktopHermesInstallPhase = .hidden
    @Published var isHermesInstallConfirmationPresented = false
    /// A fresh setup was refused with `HR-MIGRATE-008`, and the checkout that caused it is the one
    /// Desktop's own install created (`DesktopHermesInstallResume.desktopOwnsCheckout`). Only then
    /// is "改用内置 Hermes" offered beside the code; for the owner's own Hermes, never — the owner's
    /// rule is no second copy next to a Hermes they installed.
    @Published private(set) var isFreshInstallBlockedByDesktopsOwnCheckout = false
    /// A fresh setup was refused with `HR-MIGRATE-008` because of the owner's own Hermes: shown with
    /// guidance to fix or remove that install, and no alternative.
    @Published private(set) var isFreshInstallBlockedByOwnersHermes = false
    @Published private(set) var componentPreflightPresentation:
        DesktopComponentPreflightPresentation?
    @Published private(set) var isComponentPreflightRefreshing = false
    @Published private(set) var componentBootstrapCanBegin = false
    @Published private(set) var componentBootstrapOperation: DesktopComponentBootstrapOperation = .idle
    @Published private(set) var componentBootstrapPreparation: DesktopComponentBootstrapPreparation?
    @Published private(set) var componentBootstrapIssue: DesktopIssue?

    private let inspector = LegacyConnectorInspector(runner: SystemCommandRunner())
    private let managedSchemaInspector: DesktopManagedSchemaInspector?
    private let prober = HTTPHealthProber()
    private let profileStore: any ConnectionProfileStoring
    private let accountController: DesktopAccountController
    private let managedBootstrapConfiguration: DesktopManagedBootstrapConfigurationState
    private let componentPreflightConfiguration: DesktopComponentPreflightConfigurationState
    private let managedBootstrapRuntime: DesktopManagedBootstrapRuntime?
    private let managedRecoveryRuntime: DesktopManagedRecoveryRuntime?
    private let componentBootstrapRuntime: DesktopComponentBootstrapRuntime?
    private let componentEntrypointProbe: DesktopManagedComponentEntrypointProbe
    private var trustedComponentPreflight: DesktopTrustedComponentPreflight?
    private var componentInterruptedRunID: String?
    private var monitorTask: Task<Void, Never>?
    private let localHermesDetector: DesktopLocalHermesDetector?
    private let managedPaths: DesktopManagedBootstrapPaths?
    private var hermesInstallTask: Task<Void, Never>?
    /// Desktop's own unfinished install, tied to the exact checkout its stages created.
    private let hermesInstallAttempts = DesktopUserDefaultsInstallAttemptStore()
    /// After a failed switch or restart, wait before trying again rather than restarting Hermes on
    /// every 15-second refresh while the owner's install is broken.
    private var hermesRuntimeRetryAfter: Date?
    private static let hermesRuntimeRetryInterval: TimeInterval = 5 * 60
    /// With the setting off, launchd's running arguments are compared with the agent file once per
    /// launch, to finish a rollback that was interrupted between writing the file and restarting.
    private var hasCheckedRunningAgentWhileDisabled = false

    init(profileStore: any ConnectionProfileStoring = KeychainConnectionProfileStore()) {
        self.profileStore = profileStore
        let configuration = DesktopAccountConfiguration.load()
        let bootstrapConfiguration = DesktopManagedBootstrapConfigurationState.load()
        let componentConfiguration = DesktopComponentPreflightConfigurationState.load()
        managedBootstrapConfiguration = bootstrapConfiguration
        componentPreflightConfiguration = componentConfiguration
        componentEntrypointProbe = DesktopManagedComponentEntrypointProbe(
            currentUserID: getuid()
        )
        let oauth = configuration.googleClientID.map { GoogleOAuthFlow(clientID: $0) }
        let controller = DesktopAccountController(
            api: AccountAPIClient(gatewayURL: configuration.gatewayURL),
            sessionStore: KeychainAccountSessionStore(),
            machineIdentityStore: KeychainConnectorMachineIdentityStore(),
            oauth: oauth,
            displayName: Host.current().localizedName ?? "Mac",
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
                ?? "development"
        )
        accountController = controller
        let managedPaths = try? DesktopManagedBootstrapPaths.currentUser()
        self.managedPaths = managedPaths
        localHermesDetector = (try? DesktopLocalHermesPaths.currentUser())
            .map { DesktopLocalHermesDetector(paths: $0) }
        managedSchemaInspector = managedPaths.map {
            DesktopManagedSchemaInspector(managedPaths: $0)
        }
        if let managedPaths {
            managedRecoveryRuntime = try? DesktopManagedRecoveryRuntime(
                account: controller,
                paths: managedPaths
            )
        } else {
            managedRecoveryRuntime = nil
        }
        if case .configured(let releaseConfiguration) = bootstrapConfiguration,
           let managedPaths {
            managedBootstrapRuntime = try? DesktopManagedBootstrapRuntime(
                releaseConfiguration: releaseConfiguration,
                accountGatewayURL: configuration.gatewayURL,
                account: controller,
                paths: managedPaths
            )
        } else {
            managedBootstrapRuntime = nil
        }
        if case .configured(let releaseConfiguration) = componentConfiguration,
           let managedPaths {
            componentBootstrapRuntime = try? DesktopComponentBootstrapRuntime(
                releaseConfiguration: releaseConfiguration,
                accountGatewayURL: configuration.gatewayURL,
                runtimeContract: .serveV1,
                account: controller,
                paths: managedPaths
            )
        } else {
            componentBootstrapRuntime = nil
        }
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
        switch presentedHealth.overall {
        case .checking: "正在检查"
        case .healthy: "工作正常"
        case .degraded: "部分功能受限"
        case .needsAttention: "需要处理"
        }
    }

    var statusDetail: String {
        switch presentedHealth.overall {
        case .checking: "正在确认旧 Connector 与连接链路"
        case .healthy: "这台 Mac 正在安全连接 Hermes GO"
        case .degraded: "主链路可用，但有一项能力需要确认"
        case .needsAttention: "连接链路中有一项关键检查未通过"
        }
    }

    var overallLevel: HealthLevel {
        switch presentedHealth.overall {
        case .checking: .checking
        case .healthy: .healthy
        case .degraded: .degraded
        case .needsAttention: .failed
        }
    }

    var presentedHealth: DesktopHealthSnapshot {
        if let device = selectedAccountDevice {
            return AccountDeviceHealth.snapshot(for: device, checkedAt: health.checkedAt)
        }
        return health.presented(accountModeActive: isAccountModeActive)
    }

    var selectedAccountDevice: AccountDevice? {
        guard case .signedIn(let dashboard) = accountState else { return nil }
        return dashboard.selectedDevice
    }

    var overviewDeviceName: String {
        selectedAccountDevice?.desktopDisplayName
            ?? legacy?.config.deviceID
            ?? "Mac"
    }

    var overviewGatewaySummary: String {
        guard let device = selectedAccountDevice else {
            return legacy?.config.gatewayURL?.host ?? "未配置"
        }
        return device.gateway.latencyMs.map { "账号连接 · \($0) ms" } ?? "账号连接"
    }

    var overviewHermesSummary: String {
        guard let device = selectedAccountDevice else {
            return legacy?.config.hermesBaseURL.host ?? "127.0.0.1"
        }
        return device.hermes.version
            ?? (device.hermes.reachable == true ? "可访问" : "不可访问")
    }

    private var isAccountModeActive: Bool {
        if case .signedIn = accountState { return true }
        return false
    }

    func startMonitoring() {
        guard monitorTask == nil else { return }
        monitorTask = Task { [weak self] in
            await self?.refreshAccount(bootstrap: true)
            await self?.recoverManagedBootstrapAfterRestart()
            await self?.refreshComponentPreflight()
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

    func refreshComponentPreflight() async {
        guard !isManagedBootstrapAccountLocked,
              !isComponentPreflightRefreshing
        else { return }
        guard componentBootstrapAvailability == .ready,
              let runtime = componentBootstrapRuntime
        else {
            clearComponentPreflight()
            if componentBootstrapOperation == .idle {
                componentBootstrapIssue = nil
            }
            return
        }
        isComponentPreflightRefreshing = true
        defer { isComponentPreflightRefreshing = false }
        do {
            let probe = componentEntrypointProbe
            let trusted = try await runtime.preflight.loadTrusted { kind, root, entrypoint in
                try probe(kind, root: root, entrypoint: entrypoint)
            }
            guard componentBootstrapAvailability == .ready else {
                clearComponentPreflight()
                return
            }
            trustedComponentPreflight = trusted
            componentPreflightPresentation = DesktopComponentPreflightPresentation(
                result: trusted.result
            )
            componentBootstrapCanBegin = await componentMachinePreflight().plan.canBegin
            if componentBootstrapOperation == .idle {
                componentBootstrapIssue = nil
            }
        } catch {
            clearComponentPreflight()
            componentBootstrapIssue = DesktopIssue(
                code: .migrationPreflightFailed,
                technicalCause: String(describing: error)
            )
        }
    }

    func refreshAccount(bootstrap: Bool = false) async {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return }
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
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return }
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

    func requestEmailSignInCode(
        email: String
    ) async -> DesktopEmailVerificationChallenge? {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return nil }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            return try await accountController.requestEmailSignInCode(email: email)
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
        return nil
    }

    @discardableResult
    func completeEmailSignIn(
        challenge: DesktopEmailVerificationChallenge,
        code: String
    ) async -> Bool {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return false }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            applyAccountState(try await accountController.completeEmailSignIn(
                challenge: challenge,
                code: code
            ))
            return true
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
        return false
    }

    func requestPhoneRevocationVerification(
        id: String
    ) async -> DesktopEmailVerificationChallenge? {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return nil }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            return try await accountController.requestPhoneRevocationVerification(id: id)
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
        return nil
    }

    @discardableResult
    func revokePhone(
        _ id: String,
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String
    ) async -> Bool {
        await performAccountOperation {
            try await self.accountController.revokePhone(
                id: id,
                verification: verification,
                verificationCode: verificationCode
            )
        }
    }

    func selectDevice(_ id: String) async {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return }
        accountIssue = nil
        do {
            applyAccountState(try await accountController.selectDevice(id: id))
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
    }

    func selectDefaultDevice(_ id: String) async {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            applyAccountState(try await accountController.selectDefaultDevice(id: id))
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
    }

    @discardableResult
    func createShareInvitation(
        deviceID: String,
        email: String,
        acknowledged: Bool,
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String
    ) async -> Bool {
        await performAccountOperation {
            try await self.accountController.createShareInvitation(
                deviceID: deviceID,
                email: email,
                acknowledgedWholeDeviceAccess: acknowledged,
                verification: verification,
                verificationCode: verificationCode
            )
        }
    }

    func requestShareInvitationVerification(
        deviceID: String
    ) async -> DesktopEmailVerificationChallenge? {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return nil }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            return try await accountController.requestShareInvitationVerification(deviceID: deviceID)
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
        return nil
    }

    func cancelShareInvitation(deviceID: String, invitationID: String) async {
        _ = await performAccountOperation {
            try await self.accountController.cancelShareInvitation(
                deviceID: deviceID,
                invitationID: invitationID
            )
        }
    }

    func revokeDeviceShare(deviceID: String, grantID: String) async {
        _ = await performAccountOperation {
            try await self.accountController.revokeDeviceShare(deviceID: deviceID, grantID: grantID)
        }
    }

    func leaveSharedDevice(deviceID: String) async {
        _ = await performAccountOperation {
            try await self.accountController.leaveSharedDevice(deviceID: deviceID)
        }
    }

    @discardableResult
    func acceptShareInvitation(_ input: String, acknowledged: Bool) async -> Bool {
        await performAccountOperation {
            try await self.accountController.acceptShareInvitation(
                token: input,
                acknowledgedWholeDeviceAccess: acknowledged
            )
        }
    }

    func signOutAccount() async {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return }
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

    func requestAccountDeletionVerification() async -> DesktopEmailVerificationChallenge? {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return nil }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            return try await accountController.requestAccountDeletionVerification()
        } catch let error as AccountClientError {
            accountIssue = DesktopIssue.account(error)
        } catch {
            accountIssue = DesktopIssue(
                code: .accountServiceUnavailable,
                technicalCause: String(describing: error)
            )
        }
        return nil
    }

    @discardableResult
    func deleteAccount(
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String,
        acknowledgedPermanentCloudDeletion: Bool
    ) async -> Bool {
        await performAccountOperation {
            try await self.accountController.deleteAccount(
                verification: verification,
                verificationCode: verificationCode,
                acknowledgedPermanentCloudDeletion: acknowledgedPermanentCloudDeletion
            )
        }
    }

    private func applyAccountState(_ state: DesktopAccountState) {
        let previousAccountID = currentAccountID(accountState)
        let nextAccountID = currentAccountID(state)
        accountState = state
        switch state {
        case .unavailable:
            accountIssue = DesktopIssue(code: .accountFeatureDisabled)
        case .needsSignIn(let issueCode):
            accountIssue = DesktopIssue(code: issueCode)
        default:
            accountIssue = nil
        }
        if previousAccountID != nextAccountID {
            bootstrapPlan = .checking
            if managedBootstrapPreparation == nil, !isManagedBootstrapBusy {
                managedBootstrapOperation = .idle
                managedBootstrapIssue = nil
            }
            Task { [weak self] in
                _ = await self?.refreshManagedBootstrapPreflight()
            }
        }
        if componentBootstrapAvailability != .ready {
            clearComponentPreflight()
        }
    }

    private func currentAccountID(_ state: DesktopAccountState) -> String? {
        if case .signedIn(let dashboard) = state { return dashboard.session.account.id }
        return nil
    }

    private func performAccountOperation(
        _ operation: () async throws -> DesktopAccountState
    ) async -> Bool {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return false }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            applyAccountState(try await operation())
            return true
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
        return false
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
        let rawManagedInstallation = await inspectRawManagedBootstrapInstallation()
        let accountVerification = managedAccountVerification(for: rawManagedInstallation)
        let scopedManagedInstallation = scopeManagedBootstrapInstallation(rawManagedInstallation)
        agentPresentation = DesktopAgentPresentation.reduce(
            legacy: observation,
            managed: rawManagedInstallation,
            accountVerification: accountVerification,
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
                agentPresentation.health,
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
        let serverRuntimeContract: String? = switch accountState {
        case .signedIn(let dashboard): dashboard.desktopBootstrapRuntimeContract
        default: nil
        }
        bootstrapPlan = DesktopBootstrapPlanner.plan(
            legacy: observation,
            hermesReachable: hermesResult.level == .healthy || hermesResult.level == .degraded,
            managedInstallAvailability: DesktopManagedBootstrapAvailability.evaluate(
                configuration: effectiveManagedBootstrapConfiguration,
                serverRuntimeContract: serverRuntimeContract
            ),
            managedInstallation: scopedManagedInstallation,
            targetReleaseVersion: selectedTargetReleaseVersion
        )
        applyManagedBootstrapInstallation(scopedManagedInstallation)
        await refreshHermesInstallOffer(scopedManagedInstallation)
        await refreshManagedSchemaIssue()
        await refreshHermesRuntime()
    }

    /// Runs on every refresh: keeps the managed Hermes service on this Mac's own Hermes when it has a
    /// usable one, restarts it when that code changed underneath it (`hermes update`), and returns
    /// it to the bundled copy only if the owner's Hermes is removed. Inert without a committed
    /// managed installation, and skipped while a bootstrap is changing the same services.
    private func refreshHermesRuntime() async {
        guard let runtime = managedRecoveryRuntime, let detector = localHermesDetector else {
            localHermesIssue = nil
            return
        }
        guard !isManagedServiceOperationInProgress else { return }
        if let retryAfter = hermesRuntimeRetryAfter, Date() < retryAfter { return }
        let enabled = DesktopLocalHermesRuntimeSetting.isEnabled()
        // Setting off: no detection and no errors — unless this Mac is actually in local mode, which
        // is the rollback case. One `launchctl print` still runs, so a Hermes job left unloaded is
        // loaded again rather than left down (2026-09-21 incident).
        guard enabled || runtime.hermesAgentLooksLocal else {
            let checkRunningAgent = !hasCheckedRunningAgentWhileDisabled
            hasCheckedRunningAgentWhileDisabled = true
            do {
                _ = try await Task.detached(priority: .utility) {
                    try await runtime.reconcileWhileDisabled(detector: detector, checkRunningAgent: checkRunningAgent)
                }.value
                hermesRuntimeRetryAfter = nil
                localHermesIssue = nil
            } catch {
                hermesRuntimeRetryAfter = Date().addingTimeInterval(Self.hermesRuntimeRetryInterval)
                localHermesIssue = DesktopIssue.hermesRuntimeFailure(error)
            }
            return
        }
        do {
            let result = try await Task.detached(priority: .utility) {
                try await runtime.reconcileHermesRuntime(detector: detector, enabled: enabled)
            }.value
            hermesRuntimeRetryAfter = nil
            if case .unchanged(.wait) = result { return }
            localHermesIssue = DesktopIssue.hermesRuntime(result)
        } catch {
            hermesRuntimeRetryAfter = Date().addingTimeInterval(Self.hermesRuntimeRetryInterval)
            localHermesIssue = DesktopIssue.hermesRuntimeFailure(error)
        }
    }

    private var isManagedServiceOperationInProgress: Bool {
        switch managedBootstrapOperation {
        case .preparing, .committing, .recovering: return true
        default: break
        }
        switch componentBootstrapOperation {
        case .preparing, .committing: return true
        default: return false
        }
    }

    // MARK: Install-when-missing

    /// While the owner has not yet decided how this Mac gets its Hermes — or an install is running,
    /// failed or was cancelled — the managed setup waits: starting it would pick the bundled copy
    /// behind the owner's back.
    var isHermesInstallDecisionPending: Bool {
        switch hermesInstallPhase {
        case .offered, .running, .cancelled, .failed: true
        case .hidden, .succeeded: false
        }
    }

    var hermesInstallLogPath: String {
        "~/Library/Application Support/Hermes Go/Managed/logs/\(DesktopHermesInstaller.logFileName)"
    }

    /// Runs on every refresh. Offers upstream's installer only on a fresh Mac about to be set up,
    /// with the local-runtime setting on and no Hermes at all (`DesktopHermesInstallOffer.evaluate`);
    /// any other shape keeps today's behaviour, including `HR-MIGRATE-008` for unusual installs.
    private func refreshHermesInstallOffer(_ installation: DesktopManagedBootstrapInstallationStatus) async {
        guard let detector = localHermesDetector else {
            hermesInstallPhase = .hidden
            return
        }
        if hermesInstallPhase.isRunning { return }
        let fresh = installation == .absent && !(managedRecoveryRuntime?.hasManagedHermesLaunchAgent ?? false)
        if case .succeeded = hermesInstallPhase {
            if !fresh { hermesInstallPhase = .hidden }
            return
        }
        let setupAvailable = bootstrapPlan.canBegin || componentBootstrapCanBegin
        let enabled = DesktopLocalHermesRuntimeSetting.isEnabled()
        // Most refreshes end here: an installed Mac, or the setting off, needs no detection.
        guard fresh, enabled else {
            hermesInstallPhase = .hidden
            return
        }
        let attempts = hermesInstallAttempts
        let (detection, proxy, unfinished) = await Task.detached(priority: .utility) {
            (
                detector.detect(),
                DesktopSystemProxy.current(),
                DesktopHermesInstallResume.reconcile(attempts, paths: detector.paths)
            )
        }.value
        DesktopHermesInstallResume.forgetIfFinished(attempts, paths: detector.paths, detection: detection)
        let offer = setupAvailable || unfinished != nil
            ? DesktopHermesInstallOffer.evaluate(
                detection: detection,
                paths: detector.paths,
                localRuntimeEnabled: enabled,
                freshInstall: fresh,
                unfinishedCheckout: unfinished,
                proxy: proxy
            )
            : nil
        if hermesInstallPhase.isRunning { return }
        switch hermesInstallPhase {
        case .failed(_, let run, let issue):
            // Keep the outcome on screen while Desktop's own install is unfinished (the offer then
            // resumes it, even if detection already reads the half-installed Hermes as usable);
            // drop it once the Mac has Hermes some other way or the setting was turned off.
            hermesInstallPhase = offer.map { .failed($0, run, issue) } ?? .hidden
        case .cancelled(_, let run):
            hermesInstallPhase = offer.map { .cancelled($0, run) } ?? .hidden
        default:
            hermesInstallPhase = offer.map { .offered($0) } ?? .hidden
        }
    }

    /// The owner's explicit "install" — from the confirmation sheet, or "重试" after a failure or
    /// cancellation. The only path into `DesktopHermesInstaller.install`.
    func startHermesInstall() {
        isHermesInstallConfirmationPresented = false
        guard !hermesInstallPhase.isRunning,
              let shownOffer = hermesInstallPhase.offer,
              let detector = localHermesDetector,
              let managedPaths
        else { return }
        // Re-read the Mac before anything runs, from what is recorded now — nothing is written
        // here. It must still be the offer the owner saw: the same fresh install, or a resume of
        // the same checkout. Anything else is shown again rather than run.
        let reevaluated = DesktopHermesInstallOffer.evaluate(
            detection: detector.detect(),
            paths: detector.paths,
            localRuntimeEnabled: DesktopLocalHermesRuntimeSetting.isEnabled(),
            freshInstall: true,
            unfinishedCheckout: DesktopHermesInstallResume.reconcile(hermesInstallAttempts, paths: detector.paths),
            proxy: DesktopSystemProxy.current()
        )
        guard let offer = reevaluated, offer.resumeCheckout == shownOffer.resumeCheckout else {
            hermesInstallPhase = reevaluated.map { .offered($0) } ?? .hidden
            return
        }
        let installer = DesktopHermesInstaller(
            paths: detector.paths,
            workRoot: managedPaths.workspaceRoot.deletingLastPathComponent()
                .appendingPathComponent("com.hermesgo.desktop-hermes-install", isDirectory: true),
            log: DesktopServiceOperationLog(
                url: managedPaths.managedRoot
                    .appendingPathComponent("logs", isDirectory: true)
                    .appendingPathComponent(DesktopHermesInstaller.logFileName),
                maximumBytes: 1024 * 1024
            ),
            attempts: hermesInstallAttempts
        )
        let confirmation = offer.confirm()
        hermesInstallPhase = .running(offer, DesktopHermesInstallRun(), cancelling: false)
        hermesInstallTask = Task { [weak self] in
            do {
                let installation = try await installer.install(confirmation) { progress in
                    // The main queue is FIFO, so stages arrive in the order the driver sent them.
                    DispatchQueue.main.async { self?.applyHermesInstallProgress(progress) }
                }
                await self?.finishHermesInstall(.success(installation))
            } catch let failure as DesktopHermesInstallFailure {
                await self?.finishHermesInstall(.failure(failure))
            } catch {
                await self?.finishHermesInstall(.failure(.stageFailed(stage: "spawn", detail: String(describing: error))))
            }
        }
    }

    func cancelHermesInstall() {
        guard case .running(let offer, let run, cancelling: false) = hermesInstallPhase else { return }
        hermesInstallPhase = .running(offer, run, cancelling: true)
        hermesInstallTask?.cancel()
    }

    /// "改用内置 Hermes": the owner's persisted choice for this Mac. Turning the local-runtime
    /// setting off makes the managed setup — and every later refresh — behave exactly as before
    /// local runtime mode existed. Whatever an unfinished install left in `~/.hermes` is not touched.
    func useBundledHermes() {
        guard !hermesInstallPhase.isRunning else { return }
        DesktopLocalHermesRuntimeSetting.chooseBundled()
        hermesInstallAttempts.clear()
        hermesInstallPhase = .hidden
        if isFreshInstallBlockedByDesktopsOwnCheckout {
            isFreshInstallBlockedByDesktopsOwnCheckout = false
            if managedBootstrapIssue?.code == .localHermesUnsupported { managedBootstrapIssue = nil }
            if componentBootstrapIssue?.code == .localHermesUnsupported { componentBootstrapIssue = nil }
        }
    }

    private func applyHermesInstallProgress(_ progress: DesktopHermesInstallProgress) {
        guard case .running(let offer, var run, let cancelling) = hermesInstallPhase else { return }
        run.apply(progress)
        hermesInstallPhase = .running(offer, run, cancelling: cancelling)
    }

    private func finishHermesInstall(_ result: Result<DesktopLocalHermesInstallation, DesktopHermesInstallFailure>) async {
        hermesInstallTask = nil
        guard case .running(let shownOffer, var run, _) = hermesInstallPhase else { return }
        // Re-read the Mac before Retry becomes available, so the offer behind it already names the
        // checkout this attempt created and the first Retry click resumes instead of bouncing back.
        var offer = shownOffer
        if case .failure = result, let detector = localHermesDetector {
            let attempts = hermesInstallAttempts
            let refreshed = await Task.detached(priority: .userInitiated) {
                DesktopHermesInstallOffer.evaluate(
                    detection: detector.detect(),
                    paths: detector.paths,
                    localRuntimeEnabled: DesktopLocalHermesRuntimeSetting.isEnabled(),
                    freshInstall: true,
                    unfinishedCheckout: DesktopHermesInstallResume.reconcile(attempts, paths: detector.paths),
                    proxy: shownOffer.proxy
                )
            }.value
            if let refreshed { offer = refreshed }
        }
        switch result {
        case .success(let installation):
            hermesInstallPhase = .succeeded(version: installation.version)
        case .failure(.cancelled):
            run.stop()
            hermesInstallPhase = .cancelled(offer, run)
        case .failure(let failure):
            run.fail(stage: failure.stage)
            let issue = DesktopIssue.hermesInstall(failure)
                ?? DesktopIssue(code: .hermesInstallStageFailed, technicalCause: "stage=\(failure.stage)")
            hermesInstallPhase = .failed(offer, run, issue)
        }
        await refresh()
    }

    private func noteFreshInstallBlock() {
        let owned = localHermesDetector.map {
            DesktopHermesInstallResume.desktopOwnsCheckout(hermesInstallAttempts, paths: $0.paths)
        } ?? false
        isFreshInstallBlockedByDesktopsOwnCheckout = owned
        isFreshInstallBlockedByOwnersHermes = !owned
    }

    private func clearFreshInstallBlock() {
        isFreshInstallBlockedByDesktopsOwnCheckout = false
        isFreshInstallBlockedByOwnersHermes = false
    }

    /// A fresh install on a Mac that already has Hermes in a shape Hermes GO cannot run would put a
    /// second copy of Hermes beside it. Stop and say why instead.
    private func localHermesInstallBlock(
        for installation: DesktopManagedBootstrapInstallationStatus
    ) -> DesktopIssue? {
        guard let detector = localHermesDetector else { return nil }
        let fresh = installation == .absent && !(managedRecoveryRuntime?.hasManagedHermesLaunchAgent ?? false)
        return DesktopIssue.localHermesInstallBlock(
            detector.detect(),
            freshInstall: fresh,
            localRuntimeEnabled: DesktopLocalHermesRuntimeSetting.isEnabled()
        )
    }

    /// Runs on every refresh and is not gated on the installation status.
    ///
    /// The inspector is silent when there is no managed release or no readable database, so the
    /// gate is the installation's own existence. Gating on `.active` instead would have hidden the
    /// finding on exactly the Mac that has it. Measured cost: ~12 ms per pass, off the main actor.
    private func refreshManagedSchemaIssue() async {
        guard let managedSchemaInspector else {
            managedSchemaIssue = nil
            return
        }
        let drift = await Task.detached(priority: .utility) {
            managedSchemaInspector.inspect()
        }.value
        managedSchemaIssue = DesktopIssue.managedSchemaDrift(drift)
    }

    func prepareManagedBootstrap() async {
        guard !isManagedBootstrapAccountLocked, !isHermesInstallDecisionPending else { return }
        managedBootstrapIssue = nil
        clearFreshInstallBlock()
        managedBootstrapOperation = .preparing
        guard let runtime = managedBootstrapRuntime else {
            failManagedBootstrap(DesktopManagedBootstrapExecutorError.notPrepared)
            return
        }

        let preflight = await refreshManagedBootstrapPreflight()
        guard preflight.plan.canBegin else {
            failManagedBootstrap(DesktopMigrationCoordinatorError.invalidStartingState)
            return
        }
        do {
            let installation = await inspectScopedManagedBootstrapInstallation()
            if let block = localHermesInstallBlock(for: installation) {
                managedBootstrapOperation = .failed
                managedBootstrapIssue = block
                noteFreshInstallBlock()
                return
            }
            managedBootstrapPreparation = try await runtime.executor.prepare(
                manifestURL: runtime.manifestURL,
                workspaceRoot: runtime.workspaceRoot,
                runID: UUID().uuidString.lowercased(),
                installation: installation
            )
            managedBootstrapOperation = .awaitingConfirmation
        } catch {
            failManagedBootstrap(error)
        }
    }

    func cancelManagedBootstrapConfirmation() async {
        guard managedBootstrapOperation == .awaitingConfirmation,
              let preparation = managedBootstrapPreparation,
              let runtime = managedBootstrapRuntime
        else { return }
        do {
            try await runtime.executor.cancel(preparation)
            managedBootstrapPreparation = nil
            managedBootstrapIssue = nil
            managedBootstrapOperation = .idle
        } catch {
            failManagedBootstrap(error)
        }
    }

    func confirmManagedBootstrap() async {
        guard managedBootstrapOperation == .awaitingConfirmation,
              let preparation = managedBootstrapPreparation,
              let runtime = managedBootstrapRuntime
        else { return }

        let preflight = await refreshManagedBootstrapPreflight()
        guard preflight.plan.canBegin else {
            do { try await runtime.executor.cancel(preparation) }
            catch { failManagedBootstrap(error); return }
            managedBootstrapPreparation = nil
            failManagedBootstrap(DesktopMigrationCoordinatorError.invalidStartingState)
            return
        }

        managedBootstrapIssue = nil
        managedBootstrapOperation = .committing
        do {
            let outcome = try await runtime.executor.commit(
                preparation,
                configuration: runtime.commitConfiguration,
                legacy: preflight.legacy,
                confirmation: preparation.confirmationText
            )
            managedBootstrapPreparation = outcome.cleanupRetry
            managedBootstrapOperation = .completed(
                releaseVersion: outcome.migration.releaseVersion,
                cleanupPending: !outcome.temporaryWorkspaceRemoved
            )
            if !outcome.temporaryWorkspaceRemoved {
                managedBootstrapIssue = DesktopIssue(code: .migrationCleanupPending)
            }
            await refreshAccount()
            await refresh()
        } catch {
            let terminalState = try? managedRecoveryRuntime?.journal.load()?.state
            managedBootstrapPreparation = nil
            managedBootstrapOperation = .failed
            managedBootstrapIssue = DesktopIssue.migration(error, terminalState: terminalState)
        }
    }

    func retryManagedBootstrapCleanup() async {
        guard case .completed(let releaseVersion, cleanupPending: true) = managedBootstrapOperation,
              let preparation = managedBootstrapPreparation,
              let runtime = managedBootstrapRuntime
        else { return }
        do {
            try await runtime.executor.retryCleanup(preparation)
            managedBootstrapPreparation = nil
            managedBootstrapIssue = nil
            managedBootstrapOperation = .completed(
                releaseVersion: releaseVersion,
                cleanupPending: false
            )
        } catch {
            managedBootstrapIssue = DesktopIssue(
                code: .migrationCleanupPending,
                technicalCause: String(describing: error)
            )
        }
    }

    func prepareComponentBootstrap() async {
        guard !isManagedBootstrapAccountLocked,
              !isHermesInstallDecisionPending,
              componentBootstrapOperation == .idle || componentBootstrapOperation == .failed,
              let runtime = componentBootstrapRuntime,
              let trustedPreflight = trustedComponentPreflight
        else { return }

        componentBootstrapIssue = nil
        clearFreshInstallBlock()
        componentBootstrapOperation = .preparing
        do {
            applyAccountState(try await accountController.refresh())
            guard componentBootstrapAvailability == .ready else {
                throw DesktopComponentBootstrapExecutorError.invalidPreflight
            }
            let machine = await componentMachinePreflight()
            componentBootstrapCanBegin = machine.plan.canBegin
            guard machine.plan.canBegin else {
                throw DesktopMigrationCoordinatorError.invalidStartingState
            }
            if let block = localHermesInstallBlock(for: machine.installation) {
                componentBootstrapOperation = .failed
                componentBootstrapIssue = block
                noteFreshInstallBlock()
                return
            }
            let probe = componentEntrypointProbe
            let runID = UUID().uuidString.lowercased()
            do {
                let preparation = try await runtime.executor.prepare(
                    trustedPreflight: trustedPreflight,
                    workspaceRoot: runtime.workspaceRoot,
                    runID: runID,
                    installation: machine.installation
                ) { kind, root, entrypoint in
                    try probe(kind, root: root, entrypoint: entrypoint)
                }
                componentBootstrapOperation = .awaitingConfirmation
                componentBootstrapPreparation = preparation
            } catch {
                if componentPreparationMayNeedCleanup(error) {
                    do {
                        try await runtime.executor.discardInterruptedPreparation(
                            workspaceRoot: runtime.workspaceRoot,
                            runID: runID
                        )
                    } catch {
                        componentInterruptedRunID = runID
                        throw DesktopComponentBootstrapExecutorError.cleanupFailed
                    }
                }
                throw error
            }
            componentInterruptedRunID = nil
        } catch {
            failComponentBootstrap(error)
        }
    }

    func cancelComponentBootstrapConfirmation() async {
        guard componentBootstrapOperation == .awaitingConfirmation,
              let preparation = componentBootstrapPreparation,
              let runtime = componentBootstrapRuntime
        else { return }
        do {
            try await runtime.executor.cancel(preparation)
            componentBootstrapPreparation = nil
            componentBootstrapIssue = nil
            componentBootstrapOperation = .idle
        } catch {
            componentBootstrapOperation = .failed
            componentBootstrapIssue = DesktopIssue.migration(error, terminalState: nil)
        }
    }

    func confirmComponentBootstrap() async {
        guard componentBootstrapOperation == .awaitingConfirmation,
              let preparation = componentBootstrapPreparation,
              let runtime = componentBootstrapRuntime
        else { return }

        let machine: (
            legacy: LegacyConnectorSnapshot,
            plan: DesktopBootstrapPlan,
            installation: DesktopManagedBootstrapInstallationStatus
        )
        do {
            applyAccountState(try await accountController.refresh())
            guard componentBootstrapAvailability == .ready else {
                throw DesktopComponentBootstrapExecutorError.invalidPreflight
            }
            machine = await componentMachinePreflight()
            componentBootstrapCanBegin = machine.plan.canBegin
            guard machine.plan.canBegin else {
                throw DesktopMigrationCoordinatorError.invalidStartingState
            }
        } catch {
            do {
                try await runtime.executor.cancel(preparation)
                componentBootstrapPreparation = nil
                failComponentBootstrap(error)
            } catch {
                componentBootstrapOperation = .failed
                componentBootstrapIssue = DesktopIssue.migration(
                    DesktopComponentBootstrapExecutorError.cleanupFailed,
                    terminalState: nil
                )
            }
            return
        }

        do {
            componentBootstrapIssue = nil
            componentBootstrapOperation = .committing
            let outcome = try await runtime.executor.commit(
                preparation,
                configuration: runtime.commitConfiguration,
                legacy: machine.legacy,
                confirmation: preparation.confirmationText
            )
            componentBootstrapPreparation = outcome.cleanupRetry
            componentBootstrapOperation = .completed(
                releaseVersion: outcome.migration.releaseVersion,
                cleanupPending: !outcome.temporaryWorkspaceRemoved
            )
            if !outcome.temporaryWorkspaceRemoved {
                componentBootstrapIssue = DesktopIssue(code: .migrationCleanupPending)
            }
            await refreshAccount()
            await refresh()
            await refreshComponentPreflight()
        } catch {
            let terminalState = try? runtime.journal.load()?.state
            if error as? DesktopComponentBootstrapExecutorError != .cleanupFailed {
                componentBootstrapPreparation = nil
            }
            componentBootstrapOperation = .failed
            componentBootstrapIssue = DesktopIssue.migration(error, terminalState: terminalState)
        }
    }

    func retryComponentBootstrapCleanup() async {
        guard let runtime = componentBootstrapRuntime else { return }
        if let runID = componentInterruptedRunID {
            do {
                try await runtime.executor.discardInterruptedPreparation(
                    workspaceRoot: runtime.workspaceRoot,
                    runID: runID
                )
                componentInterruptedRunID = nil
                componentBootstrapIssue = nil
                componentBootstrapOperation = .idle
            } catch {
                componentBootstrapIssue = DesktopIssue.migration(error, terminalState: nil)
            }
            return
        }
        guard let preparation = componentBootstrapPreparation else { return }
        let completedRelease: String?
        switch componentBootstrapOperation {
        case .completed(let releaseVersion, cleanupPending: true):
            completedRelease = releaseVersion
        case .failed:
            completedRelease = nil
        default:
            return
        }
        do {
            try await runtime.executor.retryCleanup(preparation)
            componentBootstrapPreparation = nil
            componentBootstrapIssue = nil
            componentBootstrapOperation = completedRelease.map {
                .completed(releaseVersion: $0, cleanupPending: false)
            } ?? .idle
        } catch {
            componentBootstrapIssue = DesktopIssue.migration(error, terminalState: nil)
        }
    }

    var isManagedBootstrapBusy: Bool {
        switch managedBootstrapOperation {
        case .preparing, .committing, .recovering: true
        default: false
        }
    }

    var isManagedBootstrapAccountLocked: Bool {
        let managedLocked = switch managedBootstrapOperation {
        case .preparing, .awaitingConfirmation, .committing, .recovering: true
        case .completed(_, cleanupPending: true): true
        default: false
        }
        return managedLocked || isComponentBootstrapAccountLocked
    }

    private var isComponentBootstrapAccountLocked: Bool {
        switch componentBootstrapOperation {
        case .preparing, .awaitingConfirmation, .committing: true
        case .completed(_, cleanupPending: true): true
        case .failed: componentCleanupRetryAvailable
        case .idle, .completed: false
        }
    }

    var componentCleanupRetryAvailable: Bool {
        componentBootstrapPreparation != nil || componentInterruptedRunID != nil
    }

    var isComponentBootstrapPathSelected: Bool {
        guard case .configured = componentPreflightConfiguration,
              case .signedIn(let dashboard) = accountState
        else { return false }
        return dashboard.desktopComponentManifestSchemaVersion == 2
            && dashboard.desktopBootstrapRuntimeContract
                == DesktopHermesRuntimeContract.serveV1.rawValue
    }

    private var effectiveManagedBootstrapConfiguration: DesktopManagedBootstrapConfigurationState {
        if case .configured = managedBootstrapConfiguration, managedBootstrapRuntime == nil {
            return .invalid
        }
        return managedBootstrapConfiguration
    }

    private var managedTargetReleaseVersion: String? {
        guard case .configured(let configuration) = effectiveManagedBootstrapConfiguration else {
            return nil
        }
        return configuration.pinnedReleaseVersion
    }

    private var selectedTargetReleaseVersion: String? {
        if isComponentBootstrapPathSelected {
            return trustedComponentPreflight?.result.manifest.releaseVersion
        }
        return managedTargetReleaseVersion
    }

    private func refreshManagedBootstrapPreflight() async -> (
        legacy: LegacyConnectorSnapshot,
        plan: DesktopBootstrapPlan
    ) {
        let inspector = self.inspector
        let observation = await Task.detached(priority: .userInitiated) {
            inspector.inspect()
        }.value
        let statusURL = DesktopBootstrapPlanner.hermesStatusURL(for: observation)
        let hermes = await prober.probeHermes(statusURL)
        let managedInstallation = await inspectScopedManagedBootstrapInstallation()
        let plan = DesktopBootstrapPlanner.plan(
            legacy: observation,
            hermesReachable: hermes.level == .healthy || hermes.level == .degraded,
            managedInstallAvailability: DesktopManagedBootstrapAvailability.evaluate(
                configuration: effectiveManagedBootstrapConfiguration,
                serverRuntimeContract: currentDesktopBootstrapRuntimeContract
            ),
            managedInstallation: managedInstallation,
            targetReleaseVersion: managedTargetReleaseVersion
        )
        legacy = observation
        bootstrapPlan = plan
        applyManagedBootstrapInstallation(managedInstallation)
        return (observation, plan)
    }

    private var currentDesktopBootstrapRuntimeContract: String? {
        if case .signedIn(let dashboard) = accountState {
            return dashboard.desktopBootstrapRuntimeContract
        }
        return nil
    }

    private var componentBootstrapAvailability: DesktopComponentBootstrapAvailability {
        let dashboard: AccountDashboard? = switch accountState {
        case .signedIn(let dashboard): dashboard
        default: nil
        }
        let configuration: DesktopComponentPreflightConfigurationState
        if case .configured = componentPreflightConfiguration,
           componentBootstrapRuntime == nil {
            configuration = .invalid
        } else {
            configuration = componentPreflightConfiguration
        }
        return DesktopComponentBootstrapAvailability.evaluate(
            configuration: configuration,
            serverManifestSchemaVersion: dashboard?.desktopComponentManifestSchemaVersion,
            serverRuntimeContract: dashboard?.desktopBootstrapRuntimeContract
        )
    }

    private func componentMachinePreflight() async -> (
        legacy: LegacyConnectorSnapshot,
        plan: DesktopBootstrapPlan,
        installation: DesktopManagedBootstrapInstallationStatus
    ) {
        let inspector = self.inspector
        let observation = await Task.detached(priority: .userInitiated) {
            inspector.inspect()
        }.value
        let statusURL = DesktopBootstrapPlanner.hermesStatusURL(for: observation)
        let hermes = await prober.probeHermes(statusURL)
        let installation = await inspectScopedManagedBootstrapInstallation()
        let plan = DesktopBootstrapPlanner.plan(
            legacy: observation,
            hermesReachable: hermes.level == .healthy || hermes.level == .degraded,
            managedInstallAvailability: .ready,
            managedInstallation: installation,
            targetReleaseVersion: trustedComponentPreflight?.result.manifest.releaseVersion
        )
        return (observation, plan, installation)
    }

    private func clearComponentPreflight() {
        trustedComponentPreflight = nil
        componentPreflightPresentation = nil
        componentBootstrapCanBegin = false
    }

    private func failComponentBootstrap(_ error: Error) {
        componentBootstrapOperation = .failed
        componentBootstrapIssue = DesktopIssue.migration(error, terminalState: nil)
    }

    private func componentPreparationMayNeedCleanup(_ error: Error) -> Bool {
        if error as? DesktopComponentDownloadError == .transportFailed {
            return true
        }
        if error as? DesktopComponentReleaseInstallError == .cleanupFailed {
            return true
        }
        return error as? DesktopComponentBootstrapExecutorError == .cleanupFailed
    }

    private func inspectRawManagedBootstrapInstallation() async
        -> DesktopManagedBootstrapInstallationStatus {
        guard let runtime = managedRecoveryRuntime else { return .absent }
        return await Task.detached(priority: .utility) {
            (try? runtime.inspectInstallation()) ?? .inconsistent
        }.value
    }

    private func inspectScopedManagedBootstrapInstallation() async
        -> DesktopManagedBootstrapInstallationStatus {
        scopeManagedBootstrapInstallation(await inspectRawManagedBootstrapInstallation())
    }

    private func scopeManagedBootstrapInstallation(
        _ installation: DesktopManagedBootstrapInstallationStatus
    ) -> DesktopManagedBootstrapInstallationStatus {
        guard case .signedIn(let dashboard) = accountState else { return installation }
        return installation.scopedToCurrentAccount(
            bindingID: dashboard.binding.binding?.id,
            bindingGeneration: dashboard.binding.binding?.generation
        )
    }

    private func managedAccountVerification(
        for installation: DesktopManagedBootstrapInstallationStatus
    ) -> DesktopManagedAccountVerification {
        guard case .active(_, let bindingID, let bindingGeneration) = installation,
              case .signedIn(let dashboard) = accountState
        else { return .unavailable }
        return dashboard.binding.binding?.id == bindingID
            && dashboard.binding.binding?.generation == bindingGeneration
            ? .verified
            : .mismatched
    }

    private func applyManagedBootstrapInstallation(
        _ installation: DesktopManagedBootstrapInstallationStatus
    ) {
        guard managedBootstrapPreparation == nil else { return }
        switch installation {
        case .active(let releaseVersion, _, _):
            if bootstrapPlan.canBegin {
                if case .completed = managedBootstrapOperation {
                    managedBootstrapOperation = .idle
                }
            } else {
                managedBootstrapOperation = .completed(
                    releaseVersion: releaseVersion,
                    cleanupPending: false
                )
            }
            managedBootstrapIssue = nil
        case .attentionRequired, .inconsistent:
            if managedBootstrapOperation != .recovering {
                managedBootstrapOperation = .failed
                if managedBootstrapIssue == nil {
                    managedBootstrapIssue = DesktopIssue(
                        code: installation == .attentionRequired
                            ? .migrationRollbackFailed
                            : .migrationConnectorMismatch
                    )
                }
            }
        case .absent:
            if case .completed = managedBootstrapOperation {
                managedBootstrapOperation = .failed
                managedBootstrapIssue = DesktopIssue(code: .migrationConnectorMismatch)
            }
        case .interrupted:
            break
        }
    }

    private func recoverManagedBootstrapAfterRestart() async {
        guard let runtime = managedRecoveryRuntime else { return }
        var reconciliationIssue: DesktopIssue?
        var reconciliationBlocksUpgrade = false
        do {
            _ = try await Task.detached(priority: .utility) {
                try runtime.reconcileTransferredAccountActive()
            }.value
        } catch {
            let stage = DesktopManagedStartupRepairStage.transferredAccountConnector
            reconciliationIssue = stage.issue(error)
            reconciliationBlocksUpgrade = stage.blocksManagedUpgrade
        }
        if !reconciliationBlocksUpgrade {
            do {
                _ = try await Task.detached(priority: .utility) {
                    try await runtime.reconcileCommittedHermesSessionTokenStorage()
                }.value
            } catch {
                reconciliationIssue = DesktopManagedStartupRepairStage.sessionTokenStorage.issue(error)
            }
            do {
                // HG-58: an installation migrated before the managed agent carried a PATH keeps
                // running Hermes with launchd's bare four directories, where nothing the user
                // installed is visible. Failure is advisory: it must not hide a safe upgrade.
                _ = try await Task.detached(priority: .utility) {
                    try await runtime.reconcileCommittedHermesSearchPath()
                }.value
            } catch {
                if reconciliationIssue == nil {
                    reconciliationIssue = DesktopManagedStartupRepairStage.searchPath.issue(error)
                }
            }
        }
        let inspector = self.inspector
        let observation = await Task.detached(priority: .utility) {
            inspector.inspect()
        }.value
        let installation = await inspectScopedManagedBootstrapInstallation()
        guard case .interrupted(let runID, _) = installation else {
            applyManagedBootstrapInstallation(installation)
            if let reconciliationIssue {
                if reconciliationBlocksUpgrade {
                    managedBootstrapOperation = .failed
                }
                managedBootstrapIssue = reconciliationIssue
            }
            return
        }
        managedBootstrapOperation = .recovering
        managedBootstrapIssue = nil
        do {
            let recovered = try await runtime.recoverInterrupted(
                legacy: observation,
                runID: runID
            )
            let refreshed = await inspectScopedManagedBootstrapInstallation()
            applyManagedBootstrapInstallation(refreshed)
            if recovered == .legacyActive || recovered == .cleanUninstalled {
                managedBootstrapOperation = .idle
            } else if refreshed == .attentionRequired || refreshed == .inconsistent {
                managedBootstrapOperation = .failed
                managedBootstrapIssue = DesktopIssue(
                    code: refreshed == .attentionRequired
                        ? .migrationRollbackFailed
                        : .migrationConnectorMismatch
                )
            }
        } catch {
            let terminalState = try? runtime.journal.load()?.state
            managedBootstrapOperation = .failed
            managedBootstrapIssue = DesktopIssue.migration(error, terminalState: terminalState)
        }
    }

    private func failManagedBootstrap(_ error: Error) {
        managedBootstrapOperation = .failed
        managedBootstrapIssue = DesktopIssue.migration(error, terminalState: nil)
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

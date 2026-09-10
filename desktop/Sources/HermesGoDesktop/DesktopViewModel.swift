import AppKit
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

    private let inspector = LegacyConnectorInspector(runner: SystemCommandRunner())
    private let prober = HTTPHealthProber()
    private let profileStore: any ConnectionProfileStoring
    private let accountController: DesktopAccountController
    private let managedBootstrapConfiguration: DesktopManagedBootstrapConfigurationState
    private let managedBootstrapRuntime: DesktopManagedBootstrapRuntime?
    private let managedRecoveryRuntime: DesktopManagedRecoveryRuntime?
    private var monitorTask: Task<Void, Never>?

    init(profileStore: any ConnectionProfileStoring = KeychainConnectionProfileStore()) {
        self.profileStore = profileStore
        let configuration = DesktopAccountConfiguration.load()
        let bootstrapConfiguration = DesktopManagedBootstrapConfigurationState.load()
        managedBootstrapConfiguration = bootstrapConfiguration
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
        health.presented(accountModeActive: isAccountModeActive)
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
            managedInstallation: scopedManagedInstallation
        )
        applyManagedBootstrapInstallation(scopedManagedInstallation)
    }

    func prepareManagedBootstrap() async {
        guard !isManagedBootstrapBusy else { return }
        managedBootstrapIssue = nil
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
            managedBootstrapPreparation = try await runtime.executor.prepare(
                manifestURL: runtime.manifestURL,
                workspaceRoot: runtime.workspaceRoot,
                runID: UUID().uuidString.lowercased()
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

    var isManagedBootstrapBusy: Bool {
        switch managedBootstrapOperation {
        case .preparing, .committing, .recovering: true
        default: false
        }
    }

    var isManagedBootstrapAccountLocked: Bool {
        switch managedBootstrapOperation {
        case .preparing, .awaitingConfirmation, .committing, .recovering: true
        case .completed(_, cleanupPending: true): true
        default: false
        }
    }

    private var effectiveManagedBootstrapConfiguration: DesktopManagedBootstrapConfigurationState {
        if case .configured = managedBootstrapConfiguration, managedBootstrapRuntime == nil {
            return .invalid
        }
        return managedBootstrapConfiguration
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
            managedInstallation: managedInstallation
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
            managedBootstrapOperation = .completed(
                releaseVersion: releaseVersion,
                cleanupPending: false
            )
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
        let inspector = self.inspector
        let observation = await Task.detached(priority: .utility) {
            inspector.inspect()
        }.value
        let installation = await inspectScopedManagedBootstrapInstallation()
        guard case .interrupted(let runID, _) = installation else {
            applyManagedBootstrapInstallation(installation)
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

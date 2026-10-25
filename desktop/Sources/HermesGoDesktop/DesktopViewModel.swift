import AppKit
import Darwin
import Foundation
import HermesGoDesktopCore

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

/// Where the owner is in checking for a newer Desktop app or managed release.
enum DesktopUpdateCheckState: Equatable {
    case idle
    case checking
    case upToDate(DesktopUpdateReport)
    case available(DesktopUpdateReport)
    case failed(DesktopIssue)

    var report: DesktopUpdateReport? {
        switch self {
        case .upToDate(let report), .available(let report): report
        case .idle, .checking, .failed: nil
        }
    }
}

@MainActor
final class DesktopViewModel: ObservableObject {    @Published private(set) var health: DesktopHealthSnapshot = .checking
    @Published private(set) var managedRecentLogs: [String] = []
    var recentLogLines: [String] {
        managedRecentLogs
    }
    var recentLogWarningCount: Int { RecentLogAnalyzer.summarize(recentLogLines).warningCount }
    @Published private(set) var isRefreshing = false
    @Published private(set) var accountState: DesktopAccountState = .checking
    @Published private(set) var accountIssue: DesktopIssue?
    @Published private(set) var accountCheckedAt: Date?
    @Published private(set) var canManageHermesCompatibility = false
    @Published private(set) var hermesCompatibilitySnapshot: HermesCompatibilitySnapshot?
    @Published private(set) var hermesCompatibilityReport: HermesCompatibilityRunDetail?
    @Published private(set) var hermesCompatibilityIssue: DesktopIssue?
    @Published private(set) var isHermesCompatibilityBusy = false
    private var hermesCompatibilityEpoch = 0
    @Published private(set) var isDiagnosing = false
    @Published private(set) var setupRequestID = 0
    @Published private(set) var modelConfiguration: DesktopModelConfiguration = .unknown
    @Published var showsModelSetup = false
    @Published private(set) var localManagedHermesHealth: ComponentHealth?
    private var isAccountRefreshInProgress = false
    @Published private(set) var isAccountOperationInProgress = false
    @Published private(set) var bootstrapPlan: DesktopBootstrapPlan = .checking
    @Published private(set) var agentPresentation: DesktopAgentPresentation = .checking
    @Published private(set) var managedBootstrapOperation: DesktopManagedBootstrapOperation = .idle {
        didSet {
            if oldValue != managedBootstrapOperation {
                managedBootstrapRuntime?.progress.recordUI(managedBootstrapOperation.uiState)
            }
        }
    }
    @Published private(set) var managedBootstrapPreparation: DesktopManagedBootstrapPreparation?
    @Published private(set) var managedBootstrapIssue: DesktopIssue?
    @Published private(set) var serviceRepairNeeded = false
    @Published private(set) var serviceRepairPreparation: DesktopManagedServiceRepairPreparation?
    @Published private(set) var serviceRepairIssue: DesktopIssue?
    @Published private(set) var serviceRepairGuidance: String?
    @Published private(set) var isServiceRepairPreparing = false
    @Published private(set) var isServiceRepairRunning = false
    /// Kept apart from `managedBootstrapIssue` on purpose: drift is orthogonal to the bootstrap
    /// Which Hermes the managed service runs is reconciled on every refresh (local runtime mode,
    /// `docs/DESKTOP_PHASE0.md`). Its own slot because it is orthogonal to the bootstrap state
    /// machine, which clears its issue on most transitions.
    @Published private(set) var localHermesDetection: DesktopLocalHermesDetection?
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
    @Published private(set) var componentBootstrapOperation: DesktopComponentBootstrapOperation = .idle {
        didSet {
            if oldValue != componentBootstrapOperation {
                componentBootstrapRuntime?.progress.recordUI(componentBootstrapOperation.uiState)
            }
        }
    }
    @Published private(set) var componentBootstrapPreparation: DesktopComponentBootstrapPreparation?
    @Published private(set) var componentBootstrapIssue: DesktopIssue?
    /// Per-account onboarding facts (`DesktopOnboardingRecord`); reloaded when the account changes.
    @Published private(set) var onboardingRecord = DesktopOnboardingRecord()
    @Published private(set) var hermesUpgradeReport: DesktopHermesUpgradeReport?
    @Published private(set) var hermesUpgradeIssue: DesktopIssue?
    @Published private(set) var hermesUpgradeReadiness: DesktopHermesUpgradeReadiness?
    @Published private(set) var hermesUpgradeRecord: DesktopHermesUpgradeRecord = .unchecked
    @Published private(set) var isRefreshingHermesUpgradeRecord = false
    @Published private(set) var isCheckingHermesUpgrade = false
    private let hermesUpgradeChecker = DesktopHermesUpgradeChecker()
    @Published private(set) var hermesRecommendation = HermesRecommendationAssessment.unchecked
    private let hermesRecommendationClient: Result<(any HermesRecommendationRefreshing)?, DesktopIssue>

    @Published private(set) var updateCheckState: DesktopUpdateCheckState = .idle
    @Published var isUpdateSheetPresented = false
    @Published private(set) var diagnosticFocusIssue: DesktopIssue?
    @Published private(set) var diagnosticRequestID: UUID?
    @Published private(set) var isAppUpdateInstalling = false
    @Published private(set) var desktopUpdateSequence = DesktopUpdateSequence()
    private var desktopUpdateReport: DesktopUpdateReport?
    @Published private(set) var appUpdateIssue: DesktopIssue?
    @Published private(set) var appUpdateStatusMessage: String?
    @Published private(set) var isAutomaticUpdateEnabled = DesktopAutomaticUpdateSetting.isEnabled()

    private let inspector = RetiredConnectorInspector(runner: SystemCommandRunner())
    private let prober = HTTPHealthProber()
    private let accountController: DesktopAccountController
    private let managedBootstrapConfiguration: DesktopManagedBootstrapConfigurationState
    private let componentPreflightConfiguration: DesktopComponentPreflightConfigurationState
    private let managedBootstrapRuntime: DesktopManagedBootstrapRuntime?
    private let serviceRepair: DesktopManagedServiceRepair<SystemCommandRunner>?
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
    private let onboardingStore: any DesktopOnboardingStoring
    private let appUpdateConfiguration: DesktopAppUpdateConfigurationState
    private let updateChecker = DesktopUpdateChecker()
    private let appUpdateInstaller = DesktopAppUpdateInstaller()
    let appVersion: String
    private var lastUpdateCheckAt: Date?
    private static let automaticUpdateInterval: TimeInterval = 12 * 60 * 60
    private var currentManagedInstallation: DesktopManagedBootstrapInstallationStatus = .absent
    /// The account Gateway's origin; the phone step's QR targets are derived from it.
    let accountGatewayURL: URL

    init(
        onboardingStore: any DesktopOnboardingStoring = UserDefaultsDesktopOnboardingStore()
    ) {
        self.onboardingStore = onboardingStore
        let configuration = DesktopAccountConfiguration.load()
        accountGatewayURL = configuration.gatewayURL
        appUpdateConfiguration = DesktopAppUpdateConfigurationState.load()
        appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? "development"
        let bootstrapConfiguration = DesktopManagedBootstrapConfigurationState.load()
        let componentConfiguration = DesktopComponentPreflightConfigurationState.load()
        managedBootstrapConfiguration = bootstrapConfiguration
        do {
            hermesRecommendationClient = .success(try HermesCompatibilityRecommendationClient.desktop(
                gatewayURL: configuration.gatewayURL, release: bootstrapConfiguration))
        } catch {
            hermesRecommendationClient = .failure(DesktopIssue(code: .hermesCompatibilityRecommendationInvalid))
        }
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
            appVersion: appVersion
        )
        accountController = controller
        let managedPaths = try? DesktopManagedBootstrapPaths.currentUser()
        self.managedPaths = managedPaths
        localHermesDetector = (try? DesktopLocalHermesPaths.currentUser())
            .map { DesktopLocalHermesDetector(paths: $0) }
        if let managedPaths {
            serviceRepair = try? DesktopManagedServiceRepair.system(
                account: controller, paths: managedPaths, gatewayURL: configuration.gatewayURL
            )
            managedRecoveryRuntime = try? DesktopManagedRecoveryRuntime(
                account: controller,
                paths: managedPaths
            )
        } else {
            serviceRepair = nil
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
    }

    @Published var accountSignInRequestID = UUID()
    @Published private(set) var lastAccountIdentity: HermesAccount?

    func requestAccountSignIn() { accountSignInRequestID = UUID() }

    var localDeviceName: String {
        currentDashboard?.session.installation.displayName ?? Host.current().localizedName ?? "这台 Mac"
    }

    var overviewPresentation: DesktopOverviewPresentation {
        DesktopOverviewPresentation(gateway: health.component(.gateway), agent: agentPresentation,
            localHermes: localHermesPresentation, localHealth: localManagedHermesHealth,
            accountState: accountState, accountCheckedAt: accountCheckedAt,
            hasAccountIssue: accountIssue != nil, manageOnly: isManageOnly,
            operationInProgress: isManagedBootstrapAccountLocked)
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
        case .checking: "正在确认 Desktop 与账号连接状态"
        case .healthy: "当前设备可以通过 Desktop 使用 Hermes"
        case .degraded: "部分功能可能受影响，请打开诊断查看原因与恢复操作"
        case .needsAttention:
            if presentedHealth.component(.hermes).level == .failed {
                "Hermes 暂时无法使用，任务可能无法开始或继续。请打开诊断。"
            } else {
                "远程访问暂时不可用；本机 Hermes 的状态可在独立版块查看。请打开诊断恢复连接。"
            }
        }
    }

    /// §9: in manage-only mode the menu bar names the Mac it reports on, because this Mac's own
    /// Gateway and Hermes rows no longer describe anything the owner cares about.
    var menuBarStatusTitle: String {
        guard isManageOnly, let device = selectedAccountDevice else { return statusTitle }
        return "\(device.desktopDisplayName) · \(statusTitle)"
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
        if let device = currentDashboard?.localDevice {
            return AccountDeviceHealth.snapshot(for: device, checkedAt: accountCheckedAt ?? .distantPast)
        }
        return health
    }

    var selectedAccountDevice: AccountDevice? {
        guard case .signedIn(let dashboard) = accountState else { return nil }
        return dashboard.selectedDevice
    }

    var overviewDeviceName: String {
        selectedAccountDevice?.desktopDisplayName
            ?? Host.current().localizedName
            ?? "Mac"
    }

    var overviewGatewaySummary: String {
        guard let device = selectedAccountDevice else {
            return accountGatewayURL.host ?? "未配置"
        }
        return device.gateway.latencyMs.map { "账号连接 · \($0) ms" } ?? "账号连接"
    }

    var overviewHermesSummary: String {
        guard let device = selectedAccountDevice else {
            return DesktopHermesRuntimeContract.serveV1.baseURL.host ?? "127.0.0.1"
        }
        return device.hermes.version
            ?? (device.hermes.reachable == true ? "可访问" : "不可访问")
    }

    func startMonitoring() {
        guard monitorTask == nil else { return }
        monitorTask = Task { [weak self] in
            await self?.refreshAccount(bootstrap: true)
            await self?.recoverManagedBootstrapAfterRestart()
            await self?.refreshComponentPreflight()
            await self?.runAutomaticUpdateCheckIfDue()
            var cycle = 0
            while !Task.isCancelled {
                await self?.refresh()
                cycle += 1
                if cycle.isMultiple(of: 4) {
                    await self?.refreshAccount()
                    await self?.runAutomaticUpdateCheckIfDue()
                }
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    // MARK: - Updates

    var installedManagedVersion: String? {
        if case .active(let releaseVersion, _, _, _) = currentManagedInstallation {
            return releaseVersion
        }
        return nil
    }

    private var managedUpdateSource: DesktopManagedUpdateSource? {
        let bundled: DesktopManagedBootstrapConfiguration? =
            if case .configured(let value) = effectiveManagedBootstrapConfiguration { value } else { nil }
        let components: DesktopComponentPreflightConfiguration? =
            if componentBootstrapAvailability == .ready,
               case .configured(let value) = componentPreflightConfiguration { value } else { nil }
        return DesktopManagedUpdateSource.resolve(
            installation: currentManagedInstallation, bundled: bundled, components: components,
            allowBundledComponentMigration: managedRecoveryRuntime?.hermesAgentLooksLocal == true
                && localHermesDetection?.installation != nil
        )
    }

    var updateSources: DesktopUpdateSources {
        let app: DesktopAppUpdateConfiguration? =
            if case .configured(let value) = appUpdateConfiguration { value } else { nil }
        let managed = managedUpdateSource
        return DesktopUpdateSources(
            appIndexURL: app?.indexURL,
            appChannel: app?.channel,
            appArchitecture: app?.architecture,
            managedIndexURL: managed?.manifestURL,
            managedChannel: managed?.channel,
            managedArchitecture: managed?.architecture,
            managedLayout: managed?.layout ?? .bundledRelease
        )
    }

    var isUpdateConfigured: Bool {
        let sources = updateSources
        return sources.appIndexURL != nil
            || sources.managedIndexURL?.lastPathComponent == "index.json"
    }

    var currentModelConfiguration: DesktopModelConfiguration {
        modelConfiguration.current(health: localManagedHermesHealth)
    }

    var updateStatusText: String {
        switch updateCheckState {
        case .idle: isUpdateConfigured ? "尚未检查" : "此版本未配置更新检查"
        case .checking: "正在检查更新…"
        case .upToDate: "已是最新版本（\(appVersion)）"
        case .available: "Desktop 有可用更新"
        case .failed(let issue): issue.summaryChinese
        }
    }

    func setAutomaticUpdateChecksEnabled(_ enabled: Bool) {
        DesktopAutomaticUpdateSetting.setEnabled(enabled)
        isAutomaticUpdateEnabled = enabled
        if enabled {
            Task { await checkForUpdates(manual: false) }
        }
    }

    func checkForUpdates(manual: Bool) async {
        guard !desktopUpdateSequence.isRunning, !isManagedBootstrapAccountLocked, !isAppUpdateInstalling else { return }
        if case .checking = updateCheckState { return }
        // Resolve both discovery and execution from the actual installation, not the monitor's
        // startup cache or the server's capability for fresh installations.
        updateCheckState = .checking
        currentManagedInstallation = await inspectScopedManagedBootstrapInstallation()
        let source = managedUpdateSource
        let sources = updateSources
        // Only a direct manifest needs a prepared hint. An index is authoritative for discovery;
        // a stale startup preflight must not resurrect a release that index no longer offers.
        let preparedTarget: String?
        if let source, source.manifestURL.lastPathComponent != "index.json" {
            preparedTarget = source.layout == .componentStore
                ? trustedComponentPreflight?.result.manifest.releaseVersion : managedTargetReleaseVersion
        } else {
            preparedTarget = nil
        }
        guard isUpdateConfigured else {
            let report = DesktopUpdateReport(checkedAt: Date(), appUpdate: nil, managedUpdate: nil)
                .includingPreparedManagedVersion(preparedTarget, installed: installedManagedVersion,
                                                 layout: sources.managedLayout)
            if report.hasUpdates {
                updateCheckState = .available(report)
                isUpdateSheetPresented = true
                return
            }
            let issue = DesktopIssue(code: .updateNotConfigured)
            updateCheckState = .failed(issue)
            if manual { appUpdateIssue = issue }
            return
        }
        let checker = updateChecker
        let installedManagedVersion = self.installedManagedVersion
        appUpdateStatusMessage = nil
        if manual { appUpdateIssue = nil }
        do {
            let report = try await checker.check(
                sources: sources,
                installedAppVersion: appVersion,
                installedManagedVersion: installedManagedVersion
            ).includingPreparedManagedVersion(preparedTarget, installed: installedManagedVersion,
                                                 layout: sources.managedLayout)
            lastUpdateCheckAt = report.checkedAt
            updateCheckState = report.hasUpdates ? .available(report) : .upToDate(report)
            // The Account & Devices card reads this discovered version as a preflight hint.
            // Refresh it now instead of waiting for the next monitor cycle.
            _ = await refreshManagedBootstrapPreflight()
            if report.hasUpdates {
                isUpdateSheetPresented = true
            } else if manual {
                appUpdateStatusMessage = nil
            }
        } catch {
            let issue = DesktopIssue.updateCheck(error)
            updateCheckState = .failed(issue)
            if manual { appUpdateIssue = issue }
        }
    }

    func checkHermesUpdates() async {
        guard !isCheckingHermesUpgrade, !isDesktopUpdateRunning, !isManagedBootstrapAccountLocked,
              let detector = localHermesDetector else { return }
        isCheckingHermesUpgrade = true
        hermesUpgradeIssue = nil
        hermesUpgradeReadiness = nil
        hermesRecommendation = .checking
        defer { isCheckingHermesUpgrade = false }
        let detection = await Task.detached(priority: .userInitiated) { detector.detect() }.value
        let sources = await hermesUpgradeChecker.checkSources(installation: detection, recommendation: hermesRecommendationClient)
        switch sources.upstream {
        case .success(let report): hermesUpgradeReport = report
        case .failure(let issue):
            // A failed check cannot keep advertising an earlier successful verdict.
            hermesUpgradeReport = nil
            hermesUpgradeIssue = issue
        }
        // The two public sources are independent: upstream failure must not suppress the signed
        // recommendation result, and a failed recommendation must not hide the upstream version.
        switch sources.recommendation {
        case .failure: hermesRecommendation = .failed
        case .success(nil): hermesRecommendation = .unconfigured
        case .success(let snapshot?):
            let current = await Task.detached(priority: .userInitiated) { detector.detect() }.value
            let os = ProcessInfo.processInfo.operatingSystemVersion
            #if arch(arm64)
            let architecture = "arm64"
            #else
            let architecture = "x86_64"
            #endif
            let environment = current.installation.flatMap { local -> HermesCompatibilityEnvironment? in
                guard !local.usesPackageManager, local.dependenciesConsistent else { return nil }
                return HermesCompatibilityEnvironment(arch: architecture,
                    osVersion: "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)", installation: "legacy-venv-to-pm")
            }
            let initial = HermesRecommendationAssessment.evaluate(snapshot, installation: current,
                environment: environment, components: [])
            var components: [HermesCompatibilityComponent] = []
            // Avoid downloading an original archive when no source/environment path exists.
            if initial.status == .requirements,
               case .configured(let configuration) = managedBootstrapConfiguration,
               let paths = managedPaths,
               let layout = try? DesktopManagedInstallLayout(root: paths.managedRoot, launchAgentsRoot: paths.launchAgentsRoot),
               let identity = try? await DesktopConnectorIdentityCollector().collect(configuration: configuration, layout: layout) {
                components = [identity.component]
            }
            if initial.status == .requirements,
               case .configured(let configuration) = appUpdateConfiguration,
               let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
               let expected = HermesRecommendationAssessment.desktopRequirement(snapshot, installation: current,
                   environment: environment, installedVersion: version),
               let identity = try? await DesktopAppIdentityCollector().collect(expected: expected,
                   configuration: configuration, installedApp: Bundle.main.bundleURL) {
                components.append(identity.component)
            }
            // A download can outlast a local Hermes change. Re-detect before using the result;
            // absent/failed component observations remain unknown, never reused from a prior query.
            let finalDetection = await Task.detached(priority: .userInitiated) { detector.detect() }.value
            hermesRecommendation = .evaluate(snapshot, installation: finalDetection,
                environment: finalDetection == current ? environment : nil, components: components)
        }
        await refreshHermesUpgradeRecord()
        if let issue = hermesUpgradeRecord.issue { hermesUpgradeIssue = issue }
    }

    /// Independent of upstream availability; no journal creation, recovery or service calls.
    func refreshHermesUpgradeRecord() async {
        guard !isRefreshingHermesUpgradeRecord else { return }
        isRefreshingHermesUpgradeRecord = true
        defer { isRefreshingHermesUpgradeRecord = false }
        guard let paths = managedPaths else { hermesUpgradeRecord = .unreadable; return }
        hermesUpgradeRecord = await Task.detached(priority: .utility) {
            DesktopHermesUpgradeRecord.read(from: .init(root: paths.migrationJournalRoot))
        }.value
    }

    func checkHermesUpgradeConditions() async {
        guard !isCheckingHermesUpgrade, !isDesktopUpdateRunning, !isManagedBootstrapAccountLocked,
              let detector = localHermesDetector,
              let target = hermesUpgradeReport?.latestStable, let paths = managedPaths else { return }
        isCheckingHermesUpgrade = true
        defer { isCheckingHermesUpgrade = false }
        let result = await Task.detached(priority: .userInitiated) {
            let checker = DesktopHermesUpgradeReadinessChecker()
            let detection = detector.detect()
            let journal = DesktopHermesUpgradeJournalStore(root: paths.migrationJournalRoot)
            let report = checker.check(detection: detection, target: target, journal: journal,
                officialUpdateInProgress: detector.updateInProgress())
            return report
        }.value
        hermesUpgradeReadiness = result
        hermesUpgradeIssue = result.issue
        await refreshHermesUpgradeRecord()
        if let issue = hermesUpgradeRecord.issue { hermesUpgradeIssue = issue }
    }

    /// Runs from the monitoring loop; a no-op until the 12-hour interval has passed. A build with no
    /// update source, or an owner who switched automatic checks off, never reaches the network.
    func runAutomaticUpdateCheckIfDue() async {
        guard isUpdateConfigured, DesktopAutomaticUpdateSetting.isEnabled() else { return }
        if let lastUpdateCheckAt,
           Date().timeIntervalSince(lastUpdateCheckAt) < Self.automaticUpdateInterval {
            return
        }
        await checkForUpdates(manual: false)
    }

    private func installAppUpdate() async {
        guard let reference = desktopUpdateReport?.appUpdate?.appReference
        else { return }
        guard !isAppUpdateInstalling else { return }
        isAppUpdateInstalling = true
        appUpdateIssue = nil
        defer { isAppUpdateInstalling = false }
        do {
            let prepared = try await appUpdateInstaller.prepare(
                reference: reference,
                destinationAppURL: Bundle.main.bundleURL,
                workspaceRoot: Self.updateWorkspaceRoot()
            )
            try appUpdateInstaller.commit(prepared)
            appUpdateStatusMessage = "已安排安装 \(reference.appVersion)，应用即将退出并自动重启。"
            isUpdateSheetPresented = false
            try? await Task.sleep(for: .seconds(1))
            NSApplication.shared.terminate(nil)
        } catch {
            desktopUpdateSequence.stop()
            appUpdateIssue = DesktopIssue.appUpdateInstall(error)
        }
    }

    var isDesktopUpdateRunning: Bool { desktopUpdateSequence.isRunning }

    var localHermesPresentation: DesktopLocalHermesPresentation {
        DesktopLocalHermesPresentation(detection: localHermesDetection,
            health: localManagedHermesHealth, hasManagedInstallation: installedManagedVersion != nil)
    }

    /// Freeze the discovered release for this run; automatic checks cannot replace it mid-install.
    func installDesktopUpdate() async {
        guard !isDesktopUpdateRunning, !isManagedBootstrapAccountLocked,
              case .available(let report) = updateCheckState else { return }
        desktopUpdateReport = report
        appUpdateIssue = nil
        desktopUpdateSequence.begin(background: report.managedUpdate != nil, app: report.appUpdate != nil)
        if desktopUpdateSequence.stage == .background {
            appUpdateStatusMessage = "正在准备 Desktop 更新，验证后将确认重启影响。"
            isUpdateSheetPresented = false
            if report.managedUpdate?.managedLayout == .componentStore {
                await prepareComponentBootstrap(forDesktopUpdate: true)
                if componentBootstrapOperation != .awaitingConfirmation {
                    desktopUpdateSequence.stop()
                    appUpdateIssue = componentBootstrapIssue ?? DesktopIssue(code: .migrationPreflightFailed)
                    appUpdateStatusMessage = "Desktop 更新准备失败，尚未重启。请重新检查更新；仍失败可打开诊断并复制报告。"
                    isUpdateSheetPresented = true
                }
            } else {
                await prepareManagedBootstrap(forDesktopUpdate: true)
                if managedBootstrapOperation != .awaitingConfirmation {
                    desktopUpdateSequence.stop()
                    appUpdateIssue = managedBootstrapIssue ?? DesktopIssue(code: .migrationPreflightFailed)
                    appUpdateStatusMessage = "Desktop 更新准备失败，尚未重启。请重新检查更新；仍失败可打开诊断并复制报告。"
                    isUpdateSheetPresented = true
                }
            }
        } else {
            await installAppUpdate()
        }
    }

    private func finishDesktopBackgroundUpdate(succeeded: Bool, cleanupPending: Bool, issue: DesktopIssue?) async {
        guard desktopUpdateSequence.stage == .background else { return }
        desktopUpdateSequence.backgroundFinished(succeeded: succeeded, cleanupPending: cleanupPending)
        if succeeded, let report = desktopUpdateReport {
            let remaining = DesktopUpdateReport(checkedAt: report.checkedAt, appUpdate: report.appUpdate, managedUpdate: nil)
            desktopUpdateReport = remaining
            updateCheckState = remaining.hasUpdates ? .available(remaining) : .upToDate(remaining)
        }
        if desktopUpdateSequence.stage == .app {
            // The service confirmation has closed before presenting the final app stage.
            isUpdateSheetPresented = true
            appUpdateStatusMessage = "Desktop 后台更新已完成，正在准备应用重启。"
            await installAppUpdate()
        } else {
            appUpdateIssue = issue
            appUpdateStatusMessage = desktopUpdateSequence.stage == .completed
                ? "Desktop 更新已完成。" : "Desktop 更新未全部完成；请先处理诊断中的问题，再重试更新。"
            isUpdateSheetPresented = true
            if desktopUpdateSequence.stage == .completed {
                updateCheckState = .upToDate(DesktopUpdateReport(checkedAt: Date(), appUpdate: nil, managedUpdate: nil))
            }
        }
    }

    private static func updateWorkspaceRoot() -> URL {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Application Support", isDirectory: true)
        return base
            .appendingPathComponent("Hermes Go/Updates", isDirectory: true)
            .resolvingSymlinksInPath()
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
        guard !isAccountRefreshInProgress, !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return }
        isAccountRefreshInProgress = true
        defer { isAccountRefreshInProgress = false }
        if bootstrap { accountState = .checking }
        do {
            let state: DesktopAccountState
            if bootstrap {
                state = try await accountController.bootstrap()
            } else {
                state = try await accountController.refresh()
            }
            applyAccountState(state)
            accountCheckedAt = Date()
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

    func refreshHermesCompatibilityAccess() async {
        guard let accountID = currentAccountID(accountState) else { return }
        let epoch = hermesCompatibilityEpoch
        do {
            let access = try await accountController.hermesCompatibilityAccess()
            guard epoch == hermesCompatibilityEpoch, accountID == currentAccountID(accountState) else { return }
            canManageHermesCompatibility = access.canManage
            if !access.canManage { hermesCompatibilitySnapshot = nil; hermesCompatibilityReport = nil }
        } catch {
            guard epoch == hermesCompatibilityEpoch, accountID == currentAccountID(accountState) else { return }
            canManageHermesCompatibility = false
            hermesCompatibilitySnapshot = nil
            hermesCompatibilityReport = nil
        }
    }

    func requestHermesCompatibilityRun(candidateID: String, recipeID: String) async {
        await refreshHermesCompatibility(requestRun: (candidateID, recipeID))
    }

    func manageHermesCompatibility(_ action: HermesCompatibilityManagementAction) async {
        await refreshHermesCompatibility(managementAction: action)
    }

    func loadHermesCompatibilityReport(runID: String) async {
        guard canManageHermesCompatibility, !isHermesCompatibilityBusy,
              let accountID = currentAccountID(accountState) else { return }
        let epoch = hermesCompatibilityEpoch
        isHermesCompatibilityBusy = true
        hermesCompatibilityIssue = nil
        hermesCompatibilityReport = nil
        defer { if epoch == hermesCompatibilityEpoch { isHermesCompatibilityBusy = false } }
        do {
            let report = try await accountController.hermesCompatibilityReport(runID: runID)
            guard epoch == hermesCompatibilityEpoch, accountID == currentAccountID(accountState) else { return }
            hermesCompatibilityReport = report
        } catch {
            guard epoch == hermesCompatibilityEpoch, accountID == currentAccountID(accountState) else { return }
            hermesCompatibilityIssue = (error as? AccountClientError).map(DesktopIssue.account)
                ?? DesktopIssue(code: .hermesCompatibilityUnavailable)
            if let code = hermesCompatibilityIssue?.code,
               [.hermesCompatibilityForbidden, .accountSessionRevoked, .accountSessionExpired].contains(code) {
                canManageHermesCompatibility = false
                hermesCompatibilitySnapshot = nil
            }
        }
    }

    func refreshHermesCompatibility(syncUpstream: Bool = false, requestRun: (candidateID: String, recipeID: String)? = nil,
                                   managementAction: HermesCompatibilityManagementAction? = nil) async {
        guard canManageHermesCompatibility, !isHermesCompatibilityBusy, !isAccountOperationInProgress, !isManagedBootstrapAccountLocked,
              let accountID = currentAccountID(accountState) else { return }
        let epoch = hermesCompatibilityEpoch
        isHermesCompatibilityBusy = true
        let mutating = syncUpstream || requestRun != nil || managementAction != nil
        if mutating { isAccountOperationInProgress = true }
        hermesCompatibilityIssue = nil
        defer {
            if mutating { isAccountOperationInProgress = false }
            if epoch == hermesCompatibilityEpoch { isHermesCompatibilityBusy = false }
        }
        do {
            if syncUpstream {
                guard let revision = hermesCompatibilitySnapshot?.revision else { return }
                try await accountController.syncHermesCompatibility(expectedRevision: revision)
            }
            if let requestRun {
                guard let snapshot = hermesCompatibilitySnapshot,
                      snapshot.candidates.contains(where: { $0.id == requestRun.candidateID && !$0.identityConflict }),
                      (snapshot.recipes ?? []).contains(where: { $0.id == requestRun.recipeID }) else { return }
                try await accountController.requestHermesCompatibilityRun(expectedRevision: snapshot.revision,
                    candidateID: requestRun.candidateID, recipeID: requestRun.recipeID)
            }
            if let managementAction {
                guard let snapshot = hermesCompatibilitySnapshot else { return }
                try await accountController.manageHermesCompatibility(expectedRevision: snapshot.revision, action: managementAction)
            }
            let snapshot = try await accountController.hermesCompatibilitySnapshot()
            guard epoch == hermesCompatibilityEpoch, accountID == currentAccountID(accountState) else { return }
            hermesCompatibilitySnapshot = snapshot
        } catch {
            guard epoch == hermesCompatibilityEpoch, accountID == currentAccountID(accountState) else { return }
            let issue = (error as? AccountClientError).map(DesktopIssue.account)
                ?? DesktopIssue(code: .hermesCompatibilityUnavailable)
            hermesCompatibilityIssue = issue
            if issue.code == .hermesCompatibilityForbidden || issue.code == .accountSessionRevoked
                || issue.code == .accountSessionExpired {
                canManageHermesCompatibility = false
                hermesCompatibilitySnapshot = nil
                hermesCompatibilityReport = nil
            }
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
        if previousAccountID != nextAccountID || nextAccountID == nil {
            hermesCompatibilityEpoch += 1
            canManageHermesCompatibility = false
            hermesCompatibilitySnapshot = nil
            hermesCompatibilityReport = nil
            hermesCompatibilityIssue = nil
            isHermesCompatibilityBusy = false
        }
        if nextAccountID != nil { Task { await self.refreshHermesCompatibilityAccess() } }
        switch state {
        case .signedIn(let dashboard): lastAccountIdentity = dashboard.session.account
        case .signedOut, .accountDeletionSubmitted: lastAccountIdentity = nil
        default: break
        }
        switch state {
        case .unavailable:
            accountIssue = DesktopIssue(code: .accountFeatureDisabled)
        case .needsSignIn(let issueCode):
            accountIssue = DesktopIssue(code: issueCode)
        default:
            accountIssue = nil
        }
        if previousAccountID != nextAccountID {
            accountCheckedAt = nil
            if case .signedIn(let dashboard) = state {
                let record = onboardingStore.load(accountID: dashboard.session.account.id)
                saveOnboardingRecord(record.resuming(on: dashboard))
            } else {
                onboardingRecord = DesktopOnboardingRecord()
            }
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

    // MARK: Entry route and onboarding (docs/DESKTOP_ONBOARDING_REQUIREMENTS.md)

    /// What the main window shows. Only presentation: the monitoring loop and every background
    /// service keep running whatever this returns (§4.2).
    var entryRoute: DesktopEntryRoute {
        DesktopEntryRouter.route(DesktopEntryInputs(
            accountState: accountState,
            hasAccountIssue: accountIssue != nil,
            readiness: bootstrapPlan.readiness,
            setupInProgress: isManagedBootstrapAccountLocked,
            hermesDecisionPending: isHermesInstallDecisionPending,
            record: onboardingRecord
        ))
    }

    var currentDashboard: AccountDashboard? {
        if case .signedIn(let dashboard) = accountState { return dashboard }
        return nil
    }

    /// Persist first-setup transitions without re-arming them during an existing-binding upgrade.
    func entryRouteDidChange(_ route: DesktopEntryRoute) {
        guard let dashboard = currentDashboard else { return }
        var record = onboardingRecord
        record.observe(route: route, dashboard: dashboard)
        if record != onboardingRecord { saveOnboardingRecord(record) }
    }

    /// Active account records count even when the phone is offline or signed in before setup.
    var connectedRemoteClients: [ManagedAccountInstallation] {
        guard let dashboard = currentDashboard else { return [] }
        return DesktopRemoteClients.active(dashboard.installations)
    }

    /// Step 4 finished — a phone signed in, or the owner chose "稍后再说".
    func finishPhoneStep() {
        var record = onboardingRecord
        record.finishPhoneStep()
        saveOnboardingRecord(record)
    }

    func chooseNewMacUse(_ choice: DesktopNewMacChoice) async {
        var record = onboardingRecord
        record.newMacChoice = choice
        saveOnboardingRecord(record)
        if choice == .connect { enableOfficialHermesForFreshSetup(); setupRequestID += 1; await refresh() }
        guard choice == .manageOnly, let dashboard = currentDashboard,
              dashboard.selectedDevice == nil
        else { return }
        let others = dashboard.devices.filter { $0.deviceId != dashboard.localDeviceID }
        if let target = others.first(where: \.isDefault) ?? others.first {
            await selectDevice(target.deviceId)
        }
    }

    var isManageOnly: Bool {
        onboardingRecord.newMacChoice == .manageOnly
            && bootstrapPlan.readiness != .managedInstallActive
            && bootstrapPlan.readiness != .managedUpgradeAvailable
    }

    /// Starts step 3 on whichever installer path Gateway selected; both stop at their own
    /// confirmation sheet before changing the machine.
    func startSetup() async {
        if isComponentBootstrapPathSelected {
            await prepareComponentBootstrap()
        } else {
            await prepareManagedBootstrap()
        }
    }

    /// Which Hermes step 3 will run, for the step 2 confirmation screen. Read-only detection.
    enum OnboardingHermesSource: Equatable {
        case local(DesktopLocalHermesInstallation)
        case bundled
        case unknown
    }

    func onboardingHermesSource() async -> OnboardingHermesSource {
        guard DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: currentManagedInstallation == .absent, configuredEnabled: DesktopLocalHermesRuntimeSetting.isEnabled()) else { return .bundled }
        guard let detector = localHermesDetector else { return .unknown }
        let detection = await Task.detached(priority: .userInitiated) { detector.detect() }.value
        return detection.installation.map { .local($0) } ?? .unknown
    }

    func phoneTargetURL(_ platform: DesktopPhonePlatform) -> URL? {
        platform.targetURL(gatewayURL: accountGatewayURL)
    }

    func requestDeviceRemovalVerification(
        deviceID: String
    ) async -> DesktopEmailVerificationChallenge? {
        guard !isAccountOperationInProgress, !isManagedBootstrapAccountLocked else { return nil }
        isAccountOperationInProgress = true
        accountIssue = nil
        defer { isAccountOperationInProgress = false }
        do {
            return try await accountController.requestDeviceRemovalVerification(deviceID: deviceID)
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
    func removeOwnedDevice(
        _ deviceID: String,
        verification: DesktopEmailVerificationChallenge,
        verificationCode: String
    ) async -> Bool {
        await performAccountOperation {
            try await self.accountController.removeOwnedDevice(
                deviceID: deviceID,
                verification: verification,
                verificationCode: verificationCode
            )
        }
    }

    private func saveOnboardingRecord(_ record: DesktopOnboardingRecord) {
        onboardingRecord = record
        if let dashboard = currentDashboard {
            onboardingStore.save(record, accountID: dashboard.session.account.id)
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        let inspector = self.inspector
        let managedLogDirectory = managedPaths?.managedRoot.appendingPathComponent("logs", isDirectory: true)
        let (observation, managedLines) = await Task.detached(priority: .utility) {
            (inspector.inspect(), managedLogDirectory.map { ManagedLogInspector(logsDirectory: $0).inspect() } ?? [])
        }.value
        managedRecentLogs = managedLines
        if let detector = localHermesDetector {
            localHermesDetection = await Task.detached(priority: .utility) { detector.detect() }.value
        }

        let checkedAt = Date()
        let rawManagedInstallation = await inspectRawManagedBootstrapInstallation()
        let accountVerification = managedAccountVerification(for: rawManagedInstallation)
        let scopedManagedInstallation = scopeManagedBootstrapInstallation(rawManagedInstallation)
        if case .active = scopedManagedInstallation, let managedPaths,
           let layout = try? DesktopManagedInstallLayout(root: managedPaths.managedRoot, launchAgentsRoot: managedPaths.launchAgentsRoot) {
            let result = await DesktopManagedInstaller(layout: layout).probeManagedHermes()
            localManagedHermesHealth = ComponentHealth(component: .hermes, level: result.level,
                detail: result.detail, checkedAt: Date(), issue: result.issue)
            modelConfiguration = result.level == .healthy
                ? await DesktopManagedInstaller(layout: layout).probeModelConfiguration() : .unknown
        } else { localManagedHermesHealth = nil; modelConfiguration = .unknown }
        agentPresentation = DesktopAgentPresentation.reduce(
            legacy: observation,
            managed: rawManagedInstallation,
            accountVerification: accountVerification,
            checkedAt: checkedAt
        )

        let relayResult = await prober.probeRelay(
            URL(string: "/health", relativeTo: accountGatewayURL)!.absoluteURL
        )
        let relayCheckedAt = Date()
        let hermesResult = await HTTPHealthProber.loopbackDirect().probeHermes(
            DesktopBootstrapPlanner.hermesStatusURL
        )

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
                    checkedAt: relayCheckedAt,
                    latencyMilliseconds: relayResult.latencyMilliseconds
                ),
                ComponentHealth(
                    component: .hermes,
                    level: hermesResult.level,
                    detail: hermesDetail,
                    checkedAt: checkedAt
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
            targetReleaseVersion: selectedTargetReleaseVersion,
            localHermes: await detectLocalHermesInstallation()
        )
        applyManagedBootstrapInstallation(scopedManagedInstallation)
        await refreshServiceRepairOffer()
        await refreshHermesInstallOffer(scopedManagedInstallation)
        await refreshHermesRuntime()
        await refreshHermesUpgradeRecord()
    }

    /// One user action refreshes both evidence sources. Reading Cloud does not make its older
    /// Connector report new; AccountDeviceHealth retains that report's own timestamp.
    func refreshDiagnostics() async {
        guard !isDiagnosing, !isRefreshing, !isManagedBootstrapAccountLocked else { return }
        isDiagnosing = true
        defer { isDiagnosing = false }
        await refresh()
        await refreshAccount()
    }

    var canReconnectManagedConnector: Bool {
        guard !isManagedBootstrapAccountLocked, !isAccountOperationInProgress, serviceRepair != nil,
              !serviceRepairNeeded, localManagedHermesHealth?.level == .healthy
        else { return false }
        return DesktopConnectorReconnectEligibility.matches(currentManagedInstallation, device: selectedAccountDevice)
    }

    func prepareConnectorReconnect() async {
        guard canReconnectManagedConnector, let repair = serviceRepair else { return }
        isServiceRepairPreparing = true
        serviceRepairIssue = nil
        serviceRepairGuidance = nil
        do { serviceRepairPreparation = try await repair.prepare(reconnectOnly: true) }
        catch { showServiceRepairFailure(error, duringCommit: false) }
        isServiceRepairPreparing = false
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
        guard !isManagedServiceOperationInProgress, !serviceRepairNeeded else { return }
        if let retryAfter = hermesRuntimeRetryAfter, Date() < retryAfter { return }
        let enabled = DesktopLocalHermesRuntimeSetting.isEnabled()
        // Setting off: no detection and no errors — unless this Mac is actually in local mode, which
        // is the rollback case. One `launchctl print` still runs, so a Hermes job left unloaded is
        // loaded again rather than left down (2026-09-21 incident).
        guard enabled || runtime.hermesAgentLooksLocal else {
            let checkRunningAgent = !hasCheckedRunningAgentWhileDisabled
            hasCheckedRunningAgentWhileDisabled = true
            do {
                let result = try await Task.detached(priority: .utility) {
                    try await runtime.reconcileWhileDisabled(detector: detector, checkRunningAgent: checkRunningAgent)
                }.value
                hermesRuntimeRetryAfter = nil
                localHermesIssue = result.flatMap(DesktopIssue.hermesRuntime)
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
        let enabled = DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: fresh, configuredEnabled: DesktopLocalHermesRuntimeSetting.isEnabled())
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
        guard currentDashboard != nil, !isManagedBootstrapAccountLocked, !hermesInstallPhase.isRunning,
              let shownOffer = hermesInstallPhase.offer,
              let detector = localHermesDetector,
              let managedPaths
        else { return }
        let installLease: DesktopMigrationOperationLease
        do {
            installLease = try DesktopMigrationJournalStore(root: managedPaths.migrationJournalRoot).acquireOperationLease()
        } catch {
            hermesUpgradeIssue = (error as? DesktopHermesUpgradeError == .recoveryRequired
                ? DesktopHermesUpgradeError.recoveryRequired : .preflightBlocked).issue
            managedBootstrapIssue = hermesUpgradeIssue
            return
        }
        // Re-read the Mac before anything runs, from what is recorded now — nothing is written
        // here. It must still be the offer the owner saw: the same fresh install, or a resume of
        // the same checkout. Anything else is shown again rather than run.
        let reevaluated = DesktopHermesInstallOffer.evaluate(
            detection: detector.detect(),
            paths: detector.paths,
            localRuntimeEnabled: DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: currentManagedInstallation == .absent, configuredEnabled: DesktopLocalHermesRuntimeSetting.isEnabled()),
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
            defer { withExtendedLifetime(installLease) {} }
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

    private func enableOfficialHermesForFreshSetup() {
        guard currentManagedInstallation == .absent else { return }
        UserDefaults.standard.set(true, forKey: DesktopLocalHermesRuntimeSetting.defaultsKey)
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
                    localRuntimeEnabled: true,
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

    func prepareManagedBootstrap(forDesktopUpdate: Bool = false) async {
        guard !isServiceMutationLocked, (!isDesktopUpdateRunning || forDesktopUpdate), !isHermesInstallDecisionPending else { return }
        guard currentDashboard?.sectionIssues[.devices] == nil else { return }
        enableOfficialHermesForFreshSetup()
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
        if desktopUpdateSequence.stage == .background {
            desktopUpdateSequence.stop()
            appUpdateStatusMessage = "已取消 Desktop 更新。"
        }
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

    var managedSetupProgress: DesktopSetupProgress? { managedBootstrapRuntime?.progress }
    var componentSetupProgress: DesktopSetupProgress? { componentBootstrapRuntime?.progress }

    func confirmManagedBootstrap() async {
        guard managedBootstrapOperation == .awaitingConfirmation,
              let preparation = managedBootstrapPreparation,
              let runtime = managedBootstrapRuntime
        else { return }

        defer {
            let operation = managedBootstrapOperation
            let issue = managedBootstrapIssue
            Task {
                if case .completed(_, let pending) = operation {
                    await finishDesktopBackgroundUpdate(succeeded: true, cleanupPending: pending, issue: issue)
                } else {
                    await finishDesktopBackgroundUpdate(succeeded: false, cleanupPending: false, issue: issue)
                }
            }
        }
        runtime.progress.begin(runID: preparation.runID)
        managedBootstrapIssue = nil
        managedBootstrapOperation = .committing
        defer {
            if managedBootstrapOperation == .failed {
                runtime.progress.advance(.failed, runID: preparation.runID)
            }
        }
        let preflight = await refreshManagedBootstrapPreflight()
        guard preflight.plan.canBegin else {
            do { try await runtime.executor.cancel(preparation) }
            catch { failManagedBootstrap(error); return }
            managedBootstrapPreparation = nil
            failManagedBootstrap(DesktopMigrationCoordinatorError.invalidStartingState)
            return
        }

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
            runtime.progress.advance(
                outcome.temporaryWorkspaceRemoved ? .completed : .cleanupPending,
                runID: preparation.runID
            )
            await refreshAccount()
            await refresh()
        } catch {
            let terminalState = try? managedRecoveryRuntime?.journal.load()?.state
            managedBootstrapPreparation = nil
            managedBootstrapOperation = .failed
            managedBootstrapIssue = migrationIssue(error, terminalState: terminalState)
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

    func prepareComponentBootstrap(forDesktopUpdate: Bool = false) async {
        guard !isServiceMutationLocked, (!isDesktopUpdateRunning || forDesktopUpdate),
              !isHermesInstallDecisionPending,
              let runtime = componentBootstrapRuntime
        else { return }

        guard currentDashboard?.sectionIssues[.devices] == nil else { return }
        enableOfficialHermesForFreshSetup()
        componentBootstrapIssue = nil
        clearFreshInstallBlock()
        componentBootstrapOperation = .preparing
        do {
            applyAccountState(try await accountController.refresh())
            guard componentBootstrapAvailability == .ready else {
                throw DesktopComponentBootstrapExecutorError.invalidPreflight
            }
            // A cached setup preflight may predate the release discovered by the update check.
            // Reload and verify the selected component source before preparing its confirmation.
            let probe = componentEntrypointProbe
            if forDesktopUpdate {
                trustedComponentPreflight = try await runtime.preflight.loadTrusted { kind, root, entrypoint in
                    try probe(kind, root: root, entrypoint: entrypoint)
                }
            }
            guard let trustedPreflight = trustedComponentPreflight else {
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
            let runID = UUID().uuidString.lowercased()
            do {
                let preparation = try await runtime.executor.prepare(
                    trustedPreflight: trustedPreflight,
                    workspaceRoot: runtime.workspaceRoot,
                    runID: runID,
                    installation: machine.installation,
                    migrateBundledLayout: forDesktopUpdate
                        && machine.installation.requiresComponentLayoutMigration
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
        if desktopUpdateSequence.stage == .background {
            desktopUpdateSequence.stop()
            appUpdateStatusMessage = "已取消 Desktop 更新。"
        }
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
            componentBootstrapIssue = migrationIssue(error, terminalState: nil)
        }
    }

    func confirmComponentBootstrap() async {
        guard componentBootstrapOperation == .awaitingConfirmation,
              let preparation = componentBootstrapPreparation,
              let runtime = componentBootstrapRuntime
        else { return }

        defer {
            let operation = componentBootstrapOperation
            let issue = componentBootstrapIssue
            Task {
                if case .completed(_, let pending) = operation {
                    await finishDesktopBackgroundUpdate(succeeded: true, cleanupPending: pending, issue: issue)
                } else {
                    await finishDesktopBackgroundUpdate(succeeded: false, cleanupPending: false, issue: issue)
                }
            }
        }
        runtime.progress.begin(runID: preparation.runID)
        componentBootstrapIssue = nil
        componentBootstrapOperation = .committing
        defer {
            if componentBootstrapOperation == .failed {
                runtime.progress.advance(.failed, runID: preparation.runID)
            }
        }
        let machine: (
            legacy: RetiredConnectorSnapshot,
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
                componentBootstrapIssue = migrationIssue(
                    DesktopComponentBootstrapExecutorError.cleanupFailed,
                    terminalState: nil
                )
            }
            return
        }

        do {
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
            runtime.progress.advance(
                outcome.temporaryWorkspaceRemoved ? .completed : .cleanupPending,
                runID: preparation.runID
            )
            await refreshAccount()
            await refresh()
            await refreshComponentPreflight()
        } catch {
            let terminalState = try? runtime.journal.load()?.state
            if error as? DesktopComponentBootstrapExecutorError != .cleanupFailed {
                componentBootstrapPreparation = nil
            }
            componentBootstrapOperation = .failed
            componentBootstrapIssue = migrationIssue(error, terminalState: terminalState)
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
                componentBootstrapIssue = migrationIssue(error, terminalState: nil)
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
            componentBootstrapIssue = migrationIssue(error, terminalState: nil)
        }
    }

    var isManagedBootstrapBusy: Bool {
        switch managedBootstrapOperation {
        case .preparing, .committing, .recovering: true
        default: false
        }
    }

    var isManagedBootstrapAccountLocked: Bool { isDesktopUpdateRunning || isServiceMutationLocked }

    private var isServiceMutationLocked: Bool {
        let managedLocked = switch managedBootstrapOperation {
        case .preparing, .awaitingConfirmation, .committing, .recovering: true
        case .completed(_, cleanupPending: true): true
        default: false
        }
        return isAppUpdateInstalling || managedLocked || isComponentBootstrapAccountLocked
            || isServiceRepairPreparing || isServiceRepairRunning || serviceRepairPreparation != nil
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
        if case .active(_, let layout, _, _) = currentManagedInstallation {
            return layout == .componentStore
        }
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
        return configuration.targetReleaseVersion(
            discoveredVersion: updateCheckState.report?.managedUpdate?.version
        )
    }

    private var selectedTargetReleaseVersion: String? {
        if isComponentBootstrapPathSelected {
            return trustedComponentPreflight?.result.manifest.releaseVersion
        }
        return managedTargetReleaseVersion
    }

    /// The owner's own Hermes, when Desktop is allowed to reuse it, for the planner's adoption
    /// branch. Read-only filesystem work, so it runs off the main actor.
    private func detectLocalHermesInstallation() async -> DesktopLocalHermesInstallation? {
        guard DesktopLocalHermesRuntimeSetting.enabledForSetup(freshInstall: currentManagedInstallation == .absent, configuredEnabled: DesktopLocalHermesRuntimeSetting.isEnabled()), let detector = localHermesDetector else {
            return nil
        }
        return await Task.detached(priority: .userInitiated) { detector.detect().installation }.value
    }

    /// Every failed setup step becomes a `DesktopIssue`. The port conflict is the one failure that
    /// can name its cause, and the name is what makes it actionable, so it is the one classification
    /// that pays for a look at `~/Library/LaunchAgents`.
    private func migrationIssue(_ error: Error, terminalState: DesktopMigrationState?) -> DesktopIssue {
        DesktopIssue.migration(
            error,
            terminalState: terminalState,
            occupantLabel: DesktopServiceRecoveryFailure.classification(of: error) == .hermesPortInUse
                ? localHermesDetector?.loopbackPortOccupantLabel()
                : nil
        )
    }

    private func refreshManagedBootstrapPreflight() async -> (
        legacy: RetiredConnectorSnapshot,
        plan: DesktopBootstrapPlan
    ) {
        let inspector = self.inspector
        let observation = await Task.detached(priority: .userInitiated) {
            inspector.inspect()
        }.value
        let statusURL = DesktopBootstrapPlanner.hermesStatusURL
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
            targetReleaseVersion: managedTargetReleaseVersion,
            localHermes: await detectLocalHermesInstallation()
        )
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
        legacy: RetiredConnectorSnapshot,
        plan: DesktopBootstrapPlan,
        installation: DesktopManagedBootstrapInstallationStatus
    ) {
        let inspector = self.inspector
        let observation = await Task.detached(priority: .userInitiated) {
            inspector.inspect()
        }.value
        let statusURL = DesktopBootstrapPlanner.hermesStatusURL
        let hermes = await prober.probeHermes(statusURL)
        let installation = await inspectScopedManagedBootstrapInstallation()
        let plan = DesktopBootstrapPlanner.plan(
            legacy: observation,
            hermesReachable: hermes.level == .healthy || hermes.level == .degraded,
            managedInstallAvailability: .ready,
            managedInstallation: installation,
            targetReleaseVersion: trustedComponentPreflight?.result.manifest.releaseVersion,
            localHermes: await detectLocalHermesInstallation()
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
        componentBootstrapIssue = migrationIssue(error, terminalState: nil)
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
        guard case .active(_, _, let bindingID, let bindingGeneration) = installation,
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
        currentManagedInstallation = installation
        guard managedBootstrapPreparation == nil else { return }
        switch installation {
        case .active(let releaseVersion, _, _, _):
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
        do { try runtime.journal.hermesUpgradeJournal.requireNoPendingUpgrade() }
        catch {
            hermesUpgradeIssue = DesktopHermesUpgradeError.recoveryRequired.issue
            managedBootstrapIssue = hermesUpgradeIssue
            return
        }
        await refreshServiceRepairOffer()
        if serviceRepairNeeded { return }
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
                runID: runID
            )
            let refreshed = await inspectScopedManagedBootstrapInstallation()
            applyManagedBootstrapInstallation(refreshed)
            if recovered == .cleanUninstalled {
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
            managedBootstrapIssue = migrationIssue(error, terminalState: terminalState)
        }
    }

    private func refreshServiceRepairOffer() async {
        guard !isManagedBootstrapAccountLocked, let repair = serviceRepair else { return }
        do {
            serviceRepairNeeded = try await repair.needsRepair()
            if serviceRepairNeeded {
                if serviceRepairIssue == nil { serviceRepairIssue = DesktopIssue(code: .managedServicesNeedRepair) }
                // A repairable committed installation has a dedicated recovery surface; keep the
                // generic ownership alarm out of its primary copy.
                if managedBootstrapIssue?.code == .migrationConnectorMismatch { managedBootstrapIssue = nil }
            } else {
                serviceRepairIssue = nil
                serviceRepairGuidance = nil
            }
        } catch {
            serviceRepairNeeded = true
            showServiceRepairFailure(error, duringCommit: false)
        }
    }

    func prepareServiceRepair() async {
        guard !isManagedBootstrapAccountLocked, !isAccountOperationInProgress, let repair = serviceRepair else { return }
        isServiceRepairPreparing = true
        serviceRepairIssue = nil
        serviceRepairGuidance = nil
        do { serviceRepairPreparation = try await repair.prepare() }
        catch { showServiceRepairFailure(error, duringCommit: false) }
        isServiceRepairPreparing = false
    }

    func cancelServiceRepair() async {
        guard !isServiceRepairRunning, let plan = serviceRepairPreparation, let repair = serviceRepair else { return }
        await repair.cancel(plan)
        serviceRepairPreparation = nil
    }

    func confirmServiceRepair() async {
        guard !isServiceRepairRunning, let plan = serviceRepairPreparation, let repair = serviceRepair else { return }
        isServiceRepairRunning = true
        serviceRepairIssue = nil
        serviceRepairGuidance = nil
        do { try await repair.commit(plan, confirmed: true) }
        catch { showServiceRepairFailure(error, duringCommit: true) }
        serviceRepairPreparation = nil
        isServiceRepairRunning = false
        await refreshAccount()
        await refresh()
    }

    private func showServiceRepairFailure(_ error: Error, duringCommit: Bool) {
        serviceRepairIssue = DesktopIssue(
            code: duringCommit ? .managedServiceRepairFailed : .managedServiceRepairBlocked,
            technicalCause: "stage=managed-service-repair \(String(describing: error))"
        )
        let reason: DesktopManagedServiceRepairError? = error is DesktopManagedInstallError
            ? .unsafeInstallation : error as? DesktopManagedServiceRepairError
        switch reason {
        case .accountOrMachineMismatch:
            serviceRepairGuidance = "请登录原安装所用的账号，并选择这台 Mac。原机器凭据不匹配或已丢失时，请先恢复原凭据备份；不要删除或替换现有绑定。"
        case .portOccupied:
            serviceRepairGuidance = "端口 9119 已被其他进程占用或无法确认空闲。请关闭占用该端口的程序后重新检查；Hermes GO 不会停止未知进程。"
        case .unknownService:
            serviceRepairGuidance = "启动任务的身份无法确认或存在旧 Connector。请在诊断中检查启动方式，解决重复或未知任务后重新检查。"
        case .localHermesUnavailable(let reason):
            serviceRepairGuidance = reason.repairGuidanceChinese
        case .localHermesDependenciesIncomplete:
            serviceRepairGuidance = "已找到本机 Hermes，但运行依赖与代码版本不一致。请先完成 Hermes 自身的更新或修复，再重新检查；无需删除配置、profiles 或会话。"
        case .installationRecordMissing:
            serviceRepairGuidance = "找不到这台 Mac 原来的 Desktop 安装记录。请恢复原安装记录后重新检查；不会重新绑定设备，也无需删除 Hermes 数据。"
        case .unsafeInstallation:
            serviceRepairGuidance = "Desktop 安装文件的完整性、所属用户或权限未通过检查。请在诊断中查看失败阶段，检查原安装目录；重新安装 Hermes 或删除 profiles 无法解决此问题。"
        case .restorationFailed:
            serviceRepairGuidance = "上次修复的恢复快照已保留。请恢复原账号及安装资料，再点修复入口检查并恢复上次操作；恢复完成后才能再次修复。"
        case .stalePreparation:
            serviceRepairGuidance = "预检后的服务或安装状态发生了变化。请重新检查并确认修复范围。"
        default:
            serviceRepairGuidance = "请复制诊断并检查服务状态，恢复网络或排除启动问题后重新检查。"
        }
    }

    private func failManagedBootstrap(_ error: Error) {
        managedBootstrapOperation = .failed
        managedBootstrapIssue = migrationIssue(error, terminalState: nil)
    }

    func showDiagnostics(_ issue: DesktopIssue) {
        diagnosticFocusIssue = issue
        diagnosticRequestID = UUID()
    }

    func clearDiagnosticFocus() { diagnosticFocusIssue = nil }

    var diagnosticPresentation: DesktopDiagnosticsPresentation {
        DesktopDiagnosticsPresentation(gateway: health.component(.gateway), agent: agentPresentation,
            localHermes: localHermesPresentation, localHealth: localManagedHermesHealth,
            accountState: accountState, accountCheckedAt: accountCheckedAt, accountIssue: accountIssue,
            repairNeeded: serviceRepairNeeded)
    }

    func refreshLogs() async {
        let directory = managedPaths?.managedRoot.appendingPathComponent("logs", isDirectory: true)
        managedRecentLogs = await Task.detached(priority: .utility) {
            directory.map { ManagedLogInspector(logsDirectory: $0).inspect() } ?? []
        }.value
    }

    func copyLogLines(_ lines: [String]) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(SecretRedactor.redact(lines.joined(separator: "\n")), forType: .string)
    }

    func copyDiagnosticReport() {
        var lines = ["Desktop \(appVersion)", "Managed: \(installedManagedVersion ?? "unknown")",
                     "Local Hermes: \(localHermesPresentation.version)"]
        for check in diagnosticPresentation.checks {
            let item = check.health
            lines.append("\(check.title) [\(check.source)]: \(item.level.rawValue) — \(item.detail) — \(String(describing: item.checkedAt))")
        }
        if case .unsupported(let reason, let detail) = localHermesDetection { lines.append("\(reason.rawValue): \(detail)") }
        for issue in [localHermesIssue, managedBootstrapIssue, componentBootstrapIssue, serviceRepairIssue, appUpdateIssue, accountIssue].compactMap({ $0 }) {
            lines.append(issue.sanitizedDiagnostic)
        }
        lines.append(contentsOf: recentLogLines)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(SecretRedactor.redact(lines.joined(separator: "\n")), forType: .string)
    }

    func copyDiagnostics(_ issue: DesktopIssue) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(issue.sanitizedDiagnostic, forType: .string)
    }

    func openHermes() {
        NSWorkspace.shared.open(DesktopHermesRuntimeContract.serveV1.baseURL)
    }

    func openLogDirectory() {
        if let directory = managedPaths?.managedRoot.appendingPathComponent("logs", isDirectory: true),
           FileManager.default.fileExists(atPath: directory.path) {
            NSWorkspace.shared.open(directory)
        }
    }
}

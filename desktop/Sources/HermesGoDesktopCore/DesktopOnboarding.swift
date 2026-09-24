import Foundation

/// Where the main window goes. Decided from account state, this Mac's own installation, and the
/// account's other Macs — never from one merged flag (`docs/DESKTOP_ONBOARDING_REQUIREMENTS.md` §3–§4).
/// The route only chooses what is shown; it never starts, stops, or skips a background service.
public enum DesktopEntryRoute: Equatable, Sendable {
    /// Account or local installation still being read. Never rendered as the sign-in page.
    case launching
    /// Account state could not be confirmed (`.checking` with an issue): retry and diagnostics.
    case launchFailed
    /// Full-window sign-in. A reason is present only when the session expired unexpectedly.
    case signIn(expiredReason: DesktopIssueCode?)
    /// The Gateway does not offer accounts; there is no legacy path to fall back to.
    case serviceUnavailable
    case accountDeletionSubmitted
    /// Signed in, this Mac has nothing installed, and the account already owns another Mac.
    case newMacChoice
    case onboarding(DesktopOnboardingStep)
    case main
}

public enum DesktopOnboardingStep: Int, CaseIterable, Comparable, Sendable {
    case signIn = 1
    case prepareHermes
    case connectMac
    case connectPhone

    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }

    public var titleChinese: String {
        switch self {
        case .signIn: "登录"
        case .prepareHermes: "准备 Hermes"
        case .connectMac: "连接这台 Mac"
        case .connectPhone: "连上手机"
        }
    }
}

public enum DesktopNewMacChoice: String, Codable, Sendable {
    case connect
    case manageOnly
}

/// The only onboarding facts Desktop remembers, per account. Everything else is re-derived from the
/// real state on every launch, so onboarding resumes where the Mac actually is.
public struct DesktopOnboardingRecord: Codable, Equatable, Sendable {
    public var newMacChoice: DesktopNewMacChoice?
    /// Set when this Mac entered onboarding. Only such a Mac is shown the phone step after its
    /// install, so a Mac that was already set up before this flow existed goes straight to main.
    public var phoneStepPending: Bool
    /// Installation IDs that already existed when the phone step began; only others count as new.
    public var phoneBaseline: [String]?

    public init(
        newMacChoice: DesktopNewMacChoice? = nil,
        phoneStepPending: Bool = false,
        phoneBaseline: [String]? = nil
    ) {
        self.newMacChoice = newMacChoice
        self.phoneStepPending = phoneStepPending
        self.phoneBaseline = phoneBaseline
    }
}

public struct DesktopEntryInputs: Sendable {
    public var accountState: DesktopAccountState
    public var hasAccountIssue: Bool
    public var readiness: DesktopBootstrapReadiness
    /// A managed or component setup is preparing, awaiting confirmation, committing, recovering,
    /// or waiting for its cleanup to be retried.
    public var setupInProgress: Bool
    public var hermesDecisionPending: Bool
    public var record: DesktopOnboardingRecord

    public init(
        accountState: DesktopAccountState,
        hasAccountIssue: Bool,
        readiness: DesktopBootstrapReadiness,
        setupInProgress: Bool,
        hermesDecisionPending: Bool,
        record: DesktopOnboardingRecord
    ) {
        self.accountState = accountState
        self.hasAccountIssue = hasAccountIssue
        self.readiness = readiness
        self.setupInProgress = setupInProgress
        self.hermesDecisionPending = hermesDecisionPending
        self.record = record
    }
}

public enum DesktopEntryRouter {
    public static func route(_ inputs: DesktopEntryInputs) -> DesktopEntryRoute {
        switch inputs.accountState {
        case .checking:
            return inputs.hasAccountIssue ? .launchFailed : .launching
        case .signedOut, .signingIn:
            return .signIn(expiredReason: nil)
        case .needsSignIn(let code):
            return .signIn(expiredReason: code)
        case .unavailable:
            return .serviceUnavailable
        case .accountDeletionSubmitted:
            return .accountDeletionSubmitted
        case .signedIn(let dashboard):
            return signedInRoute(dashboard, inputs: inputs)
        }
    }

    private static func signedInRoute(
        _ dashboard: AccountDashboard,
        inputs: DesktopEntryInputs
    ) -> DesktopEntryRoute {
        let installed = inputs.readiness == .managedInstallActive || inputs.readiness == .managedUpgradeAvailable
        // A fresh install keeps onboarding on screen through every intermediate readiness. An
        // upgrade of an installed Mac is started from main and stays there.
        if inputs.setupInProgress, !installed { return .onboarding(.connectMac) }
        switch inputs.readiness {
        case .checking:
            return .launching
        case .managedInstallActive, .managedUpgradeAvailable:
            return inputs.record.phoneStepPending ? .onboarding(.connectPhone) : .main
        case .existingServiceNeedsAttention, .existingServicePreserved:
            // Half-installed or inconsistent: the planner already forbids a second install, so
            // onboarding must not offer one. Main shows the problem instead.
            return .main
        case .readyForManagedInstall, .waitingForSignedRelease:
            switch inputs.record.newMacChoice {
            case .manageOnly:
                return .main
            case .connect:
                break
            case nil:
                if !otherOwnedDevices(dashboard).isEmpty { return .newMacChoice }
            }
            return .onboarding(inputs.hermesDecisionPending ? .prepareHermes : .connectMac)
        }
    }

    /// The account's own Macs other than this one.
    public static func otherOwnedDevices(_ dashboard: AccountDashboard) -> [AccountDevice] {
        let local = dashboard.localDeviceID
        return dashboard.ownedDevices.filter { $0.deviceId != local }
    }
}

/// Phones and Web App sign-ins that can reach this Mac. The Android app signs in as `phone`; the
/// Web App added to an iPhone or iPad home screen signs in as `browser`/`web` (§6.4).
public enum DesktopRemoteClients {
    public static func active(_ installations: [ManagedAccountInstallation]) -> [ManagedAccountInstallation] {
        installations.filter {
            ($0.kind == "phone" || $0.kind == "browser") && $0.status == "active" && !$0.current
        }
    }

    public static func baseline(_ installations: [ManagedAccountInstallation]) -> [String] {
        active(installations).map(\.id)
    }

    public static func newlyConnected(
        _ installations: [ManagedAccountInstallation],
        baseline: [String]
    ) -> [ManagedAccountInstallation] {
        let known = Set(baseline)
        return active(installations).filter { !known.contains($0.id) }
    }

    /// The Web App reports no device name (Gateway records "Web browser"), so it cannot be told
    /// apart from a desktop browser; it is never labelled as an iPhone.
    public static func displayName(_ installation: ManagedAccountInstallation) -> String {
        installation.kind == "browser" ? "网页版 Hermes GO" : installation.displayName
    }
}

public protocol DesktopOnboardingStoring: Sendable {
    func load(accountID: String) -> DesktopOnboardingRecord
    func save(_ record: DesktopOnboardingRecord, accountID: String)
}

public struct UserDefaultsDesktopOnboardingStore: DesktopOnboardingStoring, @unchecked Sendable {
    private let defaults: UserDefaults
    private let keyPrefix: String

    public init(
        defaults: UserDefaults = .standard,
        keyPrefix: String = "com.hermesgo.desktop.onboarding"
    ) {
        self.defaults = defaults
        self.keyPrefix = keyPrefix
    }

    public func load(accountID: String) -> DesktopOnboardingRecord {
        guard let key = key(accountID),
              let data = defaults.data(forKey: key),
              let record = try? JSONDecoder().decode(DesktopOnboardingRecord.self, from: data)
        else { return DesktopOnboardingRecord() }
        return record
    }

    public func save(_ record: DesktopOnboardingRecord, accountID: String) {
        guard let key = key(accountID) else { return }
        guard record != DesktopOnboardingRecord() else {
            defaults.removeObject(forKey: key)
            return
        }
        guard let data = try? JSONEncoder().encode(record) else { return }
        defaults.set(data, forKey: key)
    }

    private func key(_ accountID: String) -> String? {
        UUID(uuidString: accountID).map { "\(keyPrefix).\($0.uuidString.lowercased())" }
    }
}

/// The two phone paths of onboarding step 4 (§6.4). Both targets are public, unauthenticated
/// pages on the account Gateway's origin — the release server answers `/` with a redirect to the
/// newest APK and the Gateway hosts the Web App under `/app/` — and are rendered as QR codes locally.
public enum DesktopPhonePlatform: String, CaseIterable, Sendable {
    case android
    case apple

    public func targetURL(gatewayURL: URL) -> URL? {
        guard var components = URLComponents(url: gatewayURL, resolvingAgainstBaseURL: false),
              components.scheme == "https",
              components.host?.isEmpty == false
        else { return nil }
        components.path = self == .android ? "/" : "/app/"
        components.query = nil
        components.fragment = nil
        components.user = nil
        components.password = nil
        return components.url
    }
}

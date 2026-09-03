import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopAccountControllerTests: XCTestCase {
    func testDisabledGatewayKeepsAccountClientUnavailableWithoutStartingOAuth() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures, accountEnabled: false)
        let oauth = StaticOAuth(proof: GoogleIdentityProof(idToken: "unused", nonce: "unused"))
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(),
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: oauth,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        let bootstrapState = try await controller.bootstrap()
        let signInState = try await controller.signIn()
        let signInCount = await oauth.signInCount()
        XCTAssertEqual(bootstrapState, .unavailable)
        XCTAssertEqual(signInState, .unavailable)
        XCTAssertEqual(signInCount, 0)
    }

    func testSignInPersistsManagementSessionSeparatelyAndLoadsDashboard() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures)
        let sessions = MemoryAccountSessionStore()
        let machines = MemoryMachineIdentityStore()
        let oauth = StaticOAuth(proof: GoogleIdentityProof(idToken: "provider-proof", nonce: "client-nonce"))
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: machines,
            oauth: oauth,
            displayName: "Living-room Mac mini",
            appVersion: "0.3.0"
        )

        let state = try await controller.signIn()
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected signed-in dashboard")
        }
        let recordedExchange = await api.exchangeInput()
        let exchange = try XCTUnwrap(recordedExchange)
        let stored = try XCTUnwrap(try sessions.load())
        let machine = try machines.loadOrCreate()

        XCTAssertEqual(exchange.proof, GoogleIdentityProof(idToken: "provider-proof", nonce: "client-nonce"))
        XCTAssertEqual(exchange.clientInstallationID, machine.clientInstallationID)
        XCTAssertEqual(exchange.displayName, "Living-room Mac mini")
        XCTAssertEqual(stored, fixtures.record)
        XCTAssertEqual(dashboard.phones.map { $0.displayName }, ["Phone A", "Phone B"])
        XCTAssertEqual(dashboard.binding.state, "no_binding")
        XCTAssertFalse(machine.connectorPublicKey.isEmpty)
        XCTAssertEqual(machine.connectorPublicKey.count, 43)
    }

    func testExpiredAccessRefreshUsesStableClientInstallationIDAndRotatesKeychainRecord() async throws {
        let fixtures = AccountFixtures(expiredAccess: true)
        let api = RecordingAccountAPI(fixtures: fixtures)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let machines = MemoryMachineIdentityStore()
        let machine = try machines.loadOrCreate()
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: machines,
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        let state = try await controller.bootstrap()
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected refreshed dashboard")
        }
        let recordedRefresh = await api.refreshInput()
        let refresh = try XCTUnwrap(recordedRefresh)

        XCTAssertEqual(refresh.refreshToken, fixtures.record.session.refreshToken)
        XCTAssertEqual(refresh.clientInstallationID, machine.clientInstallationID)
        XCTAssertEqual(dashboard.session.session, fixtures.freshTokens)
        XCTAssertEqual(try sessions.load()?.session, fixtures.freshTokens)
    }

    func testPhoneRemovalAndDesktopSignOutDoNotDeleteMachineIdentity() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let machines = MemoryMachineIdentityStore()
        let machineBefore = try machines.loadOrCreate()
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: machines,
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        _ = try await controller.revokePhone(id: fixtures.phoneA.id)
        let state = try await controller.signOut()

        let revokedPhoneID = await api.revokedPhoneID()
        let didSignOut = await api.didSignOut()
        XCTAssertEqual(revokedPhoneID, fixtures.phoneA.id)
        XCTAssertTrue(didSignOut)
        XCTAssertNil(try sessions.load())
        XCTAssertEqual(try machines.loadOrCreate(), machineBefore)
        XCTAssertEqual(state, .signedOut)
    }

    func testLostRefreshResponseReusesPersistedIdempotencyKey() async throws {
        let fixtures = AccountFixtures(expiredAccess: true)
        let api = RecordingAccountAPI(fixtures: fixtures, refreshFailuresRemaining: 1)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        await XCTAssertThrowsErrorAsync(try await controller.bootstrap()) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        let pendingKey = try XCTUnwrap(try sessions.load()?.pendingRefreshIdempotencyKey)
        _ = try await controller.bootstrap()
        let attempts = await api.refreshAttempts()

        XCTAssertEqual(attempts.map { $0.idempotencyKey }, [pendingKey, pendingKey])
        XCTAssertNil(try sessions.load()?.pendingRefreshIdempotencyKey)
        XCTAssertEqual(try sessions.load()?.session, fixtures.freshTokens)
    }

    func testLostFirstBindingResponseReusesPersistedKeyAndMachineIdentity() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures, bindingFailuresRemaining: 1)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let machines = MemoryMachineIdentityStore()
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: machines,
            oauth: nil,
            displayName: "Living-room Mac mini",
            appVersion: "0.3.0"
        )

        await XCTAssertThrowsErrorAsync(try await controller.createFirstBinding()) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        let pendingKey = try XCTUnwrap(
            try sessions.load()?.pendingOperationIdempotencyKeys?["connector.binding.create"]
        )
        let candidate = try await controller.createFirstBinding()
        let attempts = await api.bindingAttempts()

        XCTAssertEqual(candidate, fixtures.candidate)
        XCTAssertEqual(attempts.map(\.idempotencyKey), [pendingKey, pendingKey])
        XCTAssertEqual(attempts.map(\.input.connectorPublicKey).uniqued().count, 1)
        XCTAssertEqual(attempts.first?.input.desktopInstallationID, fixtures.desktop.id)
        XCTAssertEqual(attempts.first?.input.displayName, "Living-room Mac mini")
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }

    func testReplacementReauthAndMutationReuseBothKeysAfterLostResponse() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures, replacementFailuresRemaining: 1)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let oauth = StaticOAuth(proof: GoogleIdentityProof(idToken: "fresh-proof", nonce: "fresh-nonce"))
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: oauth,
            displayName: "Replacement Mac",
            appVersion: "0.3.0"
        )

        await XCTAssertThrowsErrorAsync(try await controller.createReplacement()) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        let pending = try XCTUnwrap(try sessions.load()?.pendingOperationIdempotencyKeys)
        let request = try await controller.createReplacement()
        let reauthenticationAttempts = await api.reauthenticationAttempts()
        let replacementAttempts = await api.replacementAttempts()

        XCTAssertEqual(request, fixtures.replacement)
        XCTAssertEqual(reauthenticationAttempts.map(\.scope), [.connectorReplace, .connectorReplace])
        XCTAssertEqual(
            reauthenticationAttempts.map(\.idempotencyKey),
            [pending["auth.reauthenticate:connector.replace"], pending["auth.reauthenticate:connector.replace"]]
        )
        XCTAssertEqual(
            replacementAttempts.map(\.idempotencyKey),
            [pending["connector.binding.replace"], pending["connector.binding.replace"]]
        )
        XCTAssertEqual(replacementAttempts.map(\.grant), [fixtures.reauthentication.grant, fixtures.reauthentication.grant])
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }

    func testConfirmAndUnbindUseExactTargetsAndScopedReauthentication() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let oauth = StaticOAuth(proof: GoogleIdentityProof(idToken: "fresh-proof", nonce: "fresh-nonce"))
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: oauth,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        let confirmedFirst = try await controller.confirmFirstBinding(
            id: fixtures.candidate.id,
            generation: fixtures.candidate.generation
        )
        let confirmedReplacement = try await controller.confirmReplacement(
            requestID: fixtures.replacement.id
        )
        try await controller.unbind()
        let recordedConfirmation = await api.bindingConfirmation()
        let recordedReplacementConfirmation = await api.replacementConfirmation()
        let recordedUnbindAttempt = await api.unbindAttempt()
        let confirmation = try XCTUnwrap(recordedConfirmation)
        let replacementConfirmation = try XCTUnwrap(recordedReplacementConfirmation)
        let unbindAttempt = try XCTUnwrap(recordedUnbindAttempt)
        let reauthenticationAttempts = await api.reauthenticationAttempts()

        XCTAssertEqual(confirmedFirst, fixtures.activeBinding)
        XCTAssertEqual(confirmedReplacement, fixtures.activeBinding)
        XCTAssertEqual(confirmation.id, fixtures.candidate.id)
        XCTAssertEqual(confirmation.generation, fixtures.candidate.generation)
        XCTAssertEqual(replacementConfirmation.requestID, fixtures.replacement.id)
        XCTAssertEqual(reauthenticationAttempts.last?.scope, .connectorUnbind)
        XCTAssertEqual(unbindAttempt.grant, fixtures.reauthentication.grant)
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }

    func testDisabledBindingFailsClosedBeforeOAuthOrMutation() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures, bindingEnabled: false)
        let oauth = StaticOAuth(proof: GoogleIdentityProof(idToken: "unused", nonce: "unused"))
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: oauth,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        await XCTAssertThrowsErrorAsync(try await controller.createReplacement()) { error in
            XCTAssertEqual(DesktopIssue.account(error as! AccountClientError).code, .bindingFeatureDisabled)
        }
        let signInCount = await oauth.signInCount()
        let reauthenticationAttempts = await api.reauthenticationAttempts()
        let replacementAttempts = await api.replacementAttempts()
        XCTAssertEqual(signInCount, 0)
        XCTAssertTrue(reauthenticationAttempts.isEmpty)
        XCTAssertTrue(replacementAttempts.isEmpty)
    }

    func testCancelledReplacementDoesNotPersistOrTransmitOperation() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures)
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: ThrowingOAuth(error: .cancelled),
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        await XCTAssertThrowsErrorAsync(try await controller.createReplacement()) { error in
            XCTAssertEqual(error as? GoogleOAuthError, .cancelled)
        }
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
        let reauthenticationAttempts = await api.reauthenticationAttempts()
        let replacementAttempts = await api.replacementAttempts()
        XCTAssertTrue(reauthenticationAttempts.isEmpty)
        XCTAssertTrue(replacementAttempts.isEmpty)
    }

    func testLostConfirmationAndUnbindResponsesReusePersistedKeys() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            confirmationFailuresRemaining: 1,
            replacementConfirmationFailuresRemaining: 1,
            unbindFailuresRemaining: 1
        )
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: StaticOAuth(proof: GoogleIdentityProof(idToken: "proof", nonce: "nonce")),
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        await XCTAssertThrowsErrorAsync(
            try await controller.confirmFirstBinding(
                id: fixtures.candidate.id,
                generation: fixtures.candidate.generation
            )
        )
        _ = try await controller.confirmFirstBinding(
            id: fixtures.candidate.id,
            generation: fixtures.candidate.generation
        )
        await XCTAssertThrowsErrorAsync(
            try await controller.confirmReplacement(requestID: fixtures.replacement.id)
        )
        _ = try await controller.confirmReplacement(requestID: fixtures.replacement.id)
        await XCTAssertThrowsErrorAsync(try await controller.unbind())
        try await controller.unbind()

        let bindingConfirmations = await api.bindingConfirmations()
        let replacementConfirmations = await api.replacementConfirmations()
        let unbindAttempts = await api.unbindAttempts()
        let reauthenticationAttempts = await api.reauthenticationAttempts()
        XCTAssertEqual(bindingConfirmations.map(\.idempotencyKey).uniqued().count, 1)
        XCTAssertEqual(replacementConfirmations.map(\.idempotencyKey).uniqued().count, 1)
        XCTAssertEqual(unbindAttempts.map(\.idempotencyKey).uniqued().count, 1)
        XCTAssertEqual(reauthenticationAttempts.map(\.idempotencyKey).uniqued().count, 1)
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }
}

private struct AccountFixtures {
    let account = HermesAccount(
        id: "10000000-0000-4000-8000-000000000001",
        displayName: "Liang",
        email: "liang@example.invalid",
        avatarUrl: nil
    )
    let desktop = AccountInstallation(
        id: "20000000-0000-4000-8000-000000000001",
        kind: "desktop",
        platform: "macos",
        displayName: "Mac mini"
    )
    let phoneA = ManagedAccountInstallation(
        id: "30000000-0000-4000-8000-000000000001",
        kind: "phone",
        platform: "android",
        displayName: "Phone A",
        lastSeenAt: "2026-09-02T00:00:00Z",
        status: "active",
        current: false
    )
    let phoneB = ManagedAccountInstallation(
        id: "30000000-0000-4000-8000-000000000002",
        kind: "phone",
        platform: "android",
        displayName: "Phone B",
        lastSeenAt: "2026-09-02T00:01:00Z",
        status: "active",
        current: false
    )
    let freshTokens = AccountSessionTokens(
        accessToken: "hga_fresh",
        accessExpiresAt: "2099-09-02T01:00:00Z",
        refreshToken: "hgr_fresh",
        refreshExpiresAt: "2099-10-02T00:00:00Z"
    )
    let record: AccountSessionRecord

    init(expiredAccess: Bool = false) {
        let tokens = expiredAccess
            ? AccountSessionTokens(
                accessToken: "hga_expired",
                accessExpiresAt: "2020-09-02T01:00:00Z",
                refreshToken: "hgr_original",
                refreshExpiresAt: "2099-10-02T00:00:00Z"
            )
            : freshTokens
        record = AccountSessionRecord(account: account, installation: desktop, session: tokens)
    }

    var capabilities: AccountCapabilities {
        AccountCapabilities(
            version: 1,
            accountAuth: .init(enabled: true, providers: ["google"], android: true, macos: true),
            binding: .init(enabled: true, replacement: true, maxActiveConnectorsPerAccount: 1),
            legacy: .init(appTokenAccepted: true, connectorTokenAccepted: true)
        )
    }

    var accountSnapshot: AccountSnapshot {
        AccountSnapshot(
            account: account,
            installation: desktop,
            session: .init(authenticated: true, recentReauthentication: false)
        )
    }

    var binding: AccountBindingSnapshot {
        AccountBindingSnapshot(
            state: "no_binding",
            id: nil,
            generation: nil,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: nil,
            previousBinding: nil
        )
    }

    var candidate: AccountBindingCandidate {
        AccountBindingCandidate(
            id: "40000000-0000-4000-8000-000000000001",
            generation: 1,
            deviceId: "hermes-40000000-0000-4000-8000-000000000001",
            displayName: "Mac mini",
            publicKeyFingerprint: String(repeating: "a", count: 64),
            state: "binding_pending",
            expiresAt: "2026-09-03T01:10:00.000Z",
            keyProved: false,
            healthVerified: false
        )
    }

    var activeBinding: ActiveAccountBinding {
        ActiveAccountBinding(
            id: candidate.id,
            generation: candidate.generation,
            deviceId: candidate.deviceId,
            desktopDisplayName: candidate.displayName,
            publicKeyFingerprint: candidate.publicKeyFingerprint,
            connector: .init(online: true, lastSeenAt: "2026-09-03T01:00:00.000Z"),
            hermes: .init(reachable: true, version: "0.0.0-test"),
            gateway: .init(latencyMs: 12),
            endToEnd: .init(healthy: true, checkedAt: "2026-09-03T01:00:00.000Z")
        )
    }

    var replacement: AccountReplacementRequest {
        AccountReplacementRequest(
            id: "50000000-0000-4000-8000-000000000001",
            state: "replacement_pending",
            expiresAt: "2026-09-03T01:10:00.000Z",
            previousBinding: activeBinding,
            candidate: AccountBindingCandidate(
                id: "40000000-0000-4000-8000-000000000002",
                generation: 2,
                deviceId: "hermes-40000000-0000-4000-8000-000000000002",
                displayName: "Replacement Mac",
                publicKeyFingerprint: String(repeating: "b", count: 64),
                state: "binding_pending",
                expiresAt: "2026-09-03T01:10:00.000Z",
                keyProved: false,
                healthVerified: false
            )
        )
    }

    var reauthentication: AccountReauthenticationGrant {
        AccountReauthenticationGrant(
            grant: "hgg_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ",
            scope: .connectorReplace,
            expiresAt: "2026-09-03T01:10:00.000Z"
        )
    }
}

private actor RecordingAccountAPI: AccountAPIRequesting {
    struct ExchangeInput {
        let proof: GoogleIdentityProof
        let clientInstallationID: String
        let displayName: String
        let appVersion: String
    }

    struct RefreshInput {
        let refreshToken: String
        let clientInstallationID: String
        let idempotencyKey: String
    }

    struct ReauthenticationInput {
        let proof: GoogleIdentityProof
        let scope: AccountReauthenticationScope
        let idempotencyKey: String
    }

    struct BindingAttempt {
        let input: ConnectorBindingInput
        let idempotencyKey: String
    }

    struct ReplacementAttempt {
        let input: ConnectorBindingInput
        let grant: String
        let idempotencyKey: String
    }

    struct BindingConfirmation {
        let id: String
        let generation: Int
        let idempotencyKey: String
    }

    struct ReplacementConfirmation {
        let requestID: String
        let idempotencyKey: String
    }

    struct UnbindAttempt {
        let grant: String
        let idempotencyKey: String
    }

    private let fixtures: AccountFixtures
    private var exchanged: ExchangeInput?
    private var refreshed: RefreshInput?
    private var refreshHistory: [RefreshInput] = []
    private var refreshFailuresRemaining: Int
    private let accountEnabled: Bool
    private let bindingEnabled: Bool
    private var bindingFailuresRemaining: Int
    private var replacementFailuresRemaining: Int
    private var revoked: String?
    private var signedOut = false
    private var recordedReauthentications: [ReauthenticationInput] = []
    private var recordedBindings: [BindingAttempt] = []
    private var recordedReplacements: [ReplacementAttempt] = []
    private var recordedBindingConfirmations: [BindingConfirmation] = []
    private var recordedReplacementConfirmations: [ReplacementConfirmation] = []
    private var recordedUnbinds: [UnbindAttempt] = []
    private var confirmationFailuresRemaining: Int
    private var replacementConfirmationFailuresRemaining: Int
    private var unbindFailuresRemaining: Int

    init(
        fixtures: AccountFixtures,
        refreshFailuresRemaining: Int = 0,
        accountEnabled: Bool = true,
        bindingEnabled: Bool = true,
        bindingFailuresRemaining: Int = 0,
        replacementFailuresRemaining: Int = 0,
        confirmationFailuresRemaining: Int = 0,
        replacementConfirmationFailuresRemaining: Int = 0,
        unbindFailuresRemaining: Int = 0
    ) {
        self.fixtures = fixtures
        self.refreshFailuresRemaining = refreshFailuresRemaining
        self.accountEnabled = accountEnabled
        self.bindingEnabled = bindingEnabled
        self.bindingFailuresRemaining = bindingFailuresRemaining
        self.replacementFailuresRemaining = replacementFailuresRemaining
        self.confirmationFailuresRemaining = confirmationFailuresRemaining
        self.replacementConfirmationFailuresRemaining = replacementConfirmationFailuresRemaining
        self.unbindFailuresRemaining = unbindFailuresRemaining
    }

    func capabilities() async throws -> AccountCapabilities {
        if accountEnabled {
            return AccountCapabilities(
                version: 1,
                accountAuth: .init(enabled: true, providers: ["google"], android: true, macos: true),
                binding: .init(
                    enabled: bindingEnabled,
                    replacement: bindingEnabled,
                    maxActiveConnectorsPerAccount: 1
                ),
                legacy: .init(appTokenAccepted: true, connectorTokenAccepted: true)
            )
        }
        return AccountCapabilities(
            version: 1,
            accountAuth: .init(enabled: false, providers: ["google"], android: true, macos: true),
            binding: .init(enabled: false, replacement: false, maxActiveConnectorsPerAccount: 1),
            legacy: .init(appTokenAccepted: true, connectorTokenAccepted: true)
        )
    }

    func reauthenticateGoogle(
        _ proof: GoogleIdentityProof,
        scope: AccountReauthenticationScope,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant {
        recordedReauthentications.append(.init(
            proof: proof,
            scope: scope,
            idempotencyKey: idempotencyKey
        ))
        return AccountReauthenticationGrant(
            grant: fixtures.reauthentication.grant,
            scope: scope,
            expiresAt: fixtures.reauthentication.expiresAt
        )
    }

    func createBinding(
        _ input: ConnectorBindingInput,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingCandidate {
        recordedBindings.append(.init(input: input, idempotencyKey: idempotencyKey))
        if bindingFailuresRemaining > 0 {
            bindingFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return fixtures.candidate
    }

    func confirmBinding(
        id: String,
        generation: Int,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> ActiveAccountBinding {
        recordedBindingConfirmations.append(.init(
            id: id,
            generation: generation,
            idempotencyKey: idempotencyKey
        ))
        if confirmationFailuresRemaining > 0 {
            confirmationFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return fixtures.activeBinding
    }

    func createReplacement(
        _ input: ConnectorBindingInput,
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReplacementRequest {
        recordedReplacements.append(.init(
            input: input,
            grant: grant,
            idempotencyKey: idempotencyKey
        ))
        if replacementFailuresRemaining > 0 {
            replacementFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return fixtures.replacement
    }

    func confirmReplacement(
        requestID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> ActiveAccountBinding {
        recordedReplacementConfirmations.append(.init(
            requestID: requestID,
            idempotencyKey: idempotencyKey
        ))
        if replacementConfirmationFailuresRemaining > 0 {
            replacementConfirmationFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return fixtures.activeBinding
    }

    func unbind(grant: String, accessToken: String, idempotencyKey: String) async throws {
        recordedUnbinds.append(.init(grant: grant, idempotencyKey: idempotencyKey))
        if unbindFailuresRemaining > 0 {
            unbindFailuresRemaining -= 1
            throw AccountClientError.transport
        }
    }

    func exchangeGoogleProof(
        _ proof: GoogleIdentityProof,
        clientInstallationID: String,
        displayName: String,
        appVersion: String
    ) async throws -> AccountSessionRecord {
        exchanged = ExchangeInput(
            proof: proof,
            clientInstallationID: clientInstallationID,
            displayName: displayName,
            appVersion: appVersion
        )
        return fixtures.record
    }

    func refresh(
        refreshToken: String,
        clientInstallationID: String,
        idempotencyKey: String
    ) async throws -> AccountSessionTokens {
        refreshed = RefreshInput(
            refreshToken: refreshToken,
            clientInstallationID: clientInstallationID,
            idempotencyKey: idempotencyKey
        )
        refreshHistory.append(refreshed!)
        if refreshFailuresRemaining > 0 {
            refreshFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return fixtures.freshTokens
    }

    func account(accessToken: String) async throws -> AccountSnapshot { fixtures.accountSnapshot }
    func installations(accessToken: String) async throws -> [ManagedAccountInstallation] {
        [fixtures.phoneA, fixtures.phoneB]
    }
    func binding(accessToken: String) async throws -> AccountBindingSnapshot { fixtures.binding }

    func revokePhone(id: String, accessToken: String, idempotencyKey: String) async throws { revoked = id }
    func signOut(accessToken: String, idempotencyKey: String) async throws { signedOut = true }

    func exchangeInput() -> ExchangeInput? { exchanged }
    func refreshInput() -> RefreshInput? { refreshed }
    func refreshAttempts() -> [RefreshInput] { refreshHistory }
    func revokedPhoneID() -> String? { revoked }
    func didSignOut() -> Bool { signedOut }
    func bindingAttempts() -> [BindingAttempt] { recordedBindings }
    func reauthenticationAttempts() -> [ReauthenticationInput] { recordedReauthentications }
    func replacementAttempts() -> [ReplacementAttempt] { recordedReplacements }
    func bindingConfirmation() -> BindingConfirmation? { recordedBindingConfirmations.last }
    func replacementConfirmation() -> ReplacementConfirmation? { recordedReplacementConfirmations.last }
    func unbindAttempt() -> UnbindAttempt? { recordedUnbinds.last }
    func bindingConfirmations() -> [BindingConfirmation] { recordedBindingConfirmations }
    func replacementConfirmations() -> [ReplacementConfirmation] { recordedReplacementConfirmations }
    func unbindAttempts() -> [UnbindAttempt] { recordedUnbinds }
}

private actor StaticOAuth: GoogleOAuthPerforming {
    let proof: GoogleIdentityProof
    private var count = 0
    init(proof: GoogleIdentityProof) { self.proof = proof }
    func signIn() async throws -> GoogleIdentityProof {
        count += 1
        return proof
    }
    func signInCount() -> Int { count }
}

private actor ThrowingOAuth: GoogleOAuthPerforming {
    let error: GoogleOAuthError
    init(error: GoogleOAuthError) { self.error = error }
    func signIn() async throws -> GoogleIdentityProof { throw error }
}

private final class MemoryAccountSessionStore: AccountSessionStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var record: AccountSessionRecord?

    init(record: AccountSessionRecord? = nil) { self.record = record }

    func load() throws -> AccountSessionRecord? {
        lock.withLock { record }
    }

    func save(_ record: AccountSessionRecord) throws {
        lock.withLock { self.record = record }
    }

    func delete() throws {
        lock.withLock { record = nil }
    }
}

private final class MemoryMachineIdentityStore: ConnectorMachineIdentityStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var identity: ConnectorMachineIdentity?

    func loadOrCreate() throws -> ConnectorMachineIdentity {
        lock.withLock {
            if let identity { return identity }
            let created = ConnectorMachineIdentity()
            identity = created
            return created
        }
    }

    func delete() throws {
        lock.withLock { identity = nil }
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    _ handler: (Error) -> Void = { _ in }
) async {
    do {
        _ = try await expression()
        XCTFail("Expected expression to throw")
    } catch {
        handler(error)
    }
}

private extension Sequence where Element: Hashable {
    func uniqued() -> [Element] {
        Array(Set(self))
    }
}

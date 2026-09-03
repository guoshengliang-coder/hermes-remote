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

    private let fixtures: AccountFixtures
    private var exchanged: ExchangeInput?
    private var refreshed: RefreshInput?
    private var refreshHistory: [RefreshInput] = []
    private var refreshFailuresRemaining: Int
    private let accountEnabled: Bool
    private var revoked: String?
    private var signedOut = false

    init(
        fixtures: AccountFixtures,
        refreshFailuresRemaining: Int = 0,
        accountEnabled: Bool = true
    ) {
        self.fixtures = fixtures
        self.refreshFailuresRemaining = refreshFailuresRemaining
        self.accountEnabled = accountEnabled
    }

    func capabilities() async throws -> AccountCapabilities {
        guard !accountEnabled else { return fixtures.capabilities }
        return AccountCapabilities(
            version: 1,
            accountAuth: .init(enabled: false, providers: ["google"], android: true, macos: true),
            binding: .init(enabled: false, replacement: false, maxActiveConnectorsPerAccount: 1),
            legacy: .init(appTokenAccepted: true, connectorTokenAccepted: true)
        )
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

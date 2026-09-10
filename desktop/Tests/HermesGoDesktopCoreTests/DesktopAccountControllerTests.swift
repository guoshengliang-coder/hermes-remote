import CryptoKit
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

    func testEmailCodeSignInNormalizesEmailAndReusesChallengeExchangeKey() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures)
        let sessions = MemoryAccountSessionStore()
        let machines = MemoryMachineIdentityStore()
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: machines,
            oauth: nil,
            displayName: "Living-room Mac mini",
            appVersion: "0.4.0"
        )

        let challenge = try await controller.requestEmailSignInCode(email: " Liang@Example.invalid ")
        let state = try await controller.completeEmailSignIn(challenge: challenge, code: "012345")
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected signed-in dashboard")
        }
        let requestedValue = await api.emailChallengeInput()
        let exchangedValue = await api.emailExchangeInput()
        let requested = try XCTUnwrap(requestedValue)
        let exchanged = try XCTUnwrap(exchangedValue)
        let machine = try machines.loadOrCreate()

        XCTAssertEqual(challenge.email, "liang@example.invalid")
        XCTAssertEqual(requested.email, challenge.email)
        XCTAssertEqual(requested.clientInstallationID, machine.clientInstallationID)
        XCTAssertEqual(exchanged.challengeID, challenge.challenge.challengeId)
        XCTAssertEqual(exchanged.code, "012345")
        XCTAssertEqual(exchanged.idempotencyKey, challenge.idempotencyKey)
        XCTAssertEqual(dashboard.session.account.email, challenge.email)
        XCTAssertEqual(try sessions.load(), fixtures.record)
    }

    func testEmailOnlyGrayRolloutSkipsDisabledManagementRoutesAfterSignIn() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            bindingEnabled: false,
            identityManagementEnabled: false
        )
        let sessions = MemoryAccountSessionStore()
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.2.0"
        )

        let challenge = try await controller.requestEmailSignInCode(email: "liang@example.invalid")
        let state = try await controller.completeEmailSignIn(challenge: challenge, code: "012345")

        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected an email-only signed-in dashboard")
        }
        XCTAssertEqual(dashboard.session.account.email, "liang@example.invalid")
        XCTAssertEqual(dashboard.binding.state, "no_binding")
        XCTAssertTrue(dashboard.installations.isEmpty)
        XCTAssertEqual(try sessions.load(), fixtures.record)
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
        let verification = try await controller.requestPhoneRevocationVerification(
            id: fixtures.phoneA.id
        )
        _ = try await controller.revokePhone(
            id: fixtures.phoneA.id,
            verification: verification,
            verificationCode: "123456"
        )
        let state = try await controller.signOut()

        let revokedPhoneID = await api.revokedPhoneID()
        let didSignOut = await api.didSignOut()
        XCTAssertEqual(revokedPhoneID, fixtures.phoneA.id)
        XCTAssertTrue(didSignOut)
        XCTAssertNil(try sessions.load())
        XCTAssertEqual(try machines.loadOrCreate(), machineBefore)
        XCTAssertEqual(state, .signedOut)
    }

    func testPhoneRevocationRequiresEmailGrantAndReusesItAfterLostResponse() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            phoneRevocationFailuresRemaining: 1
        )
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.4.0"
        )

        _ = try await controller.bootstrap()
        let verification = try await controller.requestPhoneRevocationVerification(
            id: fixtures.phoneA.id
        )
        await XCTAssertThrowsErrorAsync(
            try await controller.revokePhone(
                id: fixtures.phoneA.id,
                verification: verification,
                verificationCode: "123456"
            )
        ) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        XCTAssertEqual(try sessions.load()?.pendingReauthenticationGrants?.count, 1)
        XCTAssertEqual(try sessions.load()?.pendingOperationIdempotencyKeys?.count, 1)

        _ = try await controller.revokePhone(
            id: fixtures.phoneA.id,
            verification: verification,
            verificationCode: "123456"
        )
        let reauthenticationAttempts = await api.emailReauthenticationAttempts()
        let revokeAttempts = await api.phoneRevocationAttempts()

        XCTAssertEqual(reauthenticationAttempts.count, 1)
        XCTAssertEqual(reauthenticationAttempts[0].scope, "account.installation.revoke")
        XCTAssertEqual(reauthenticationAttempts[0].email, fixtures.account.email)
        XCTAssertEqual(reauthenticationAttempts[0].code, "123456")
        XCTAssertEqual(revokeAttempts.count, 2)
        XCTAssertEqual(revokeAttempts.map(\.id), [fixtures.phoneA.id, fixtures.phoneA.id])
        XCTAssertEqual(revokeAttempts.map(\.grant), [revokeAttempts[0].grant, revokeAttempts[0].grant])
        XCTAssertEqual(
            revokeAttempts.map(\.idempotencyKey),
            [revokeAttempts[0].idempotencyKey, revokeAttempts[0].idempotencyKey]
        )
        XCTAssertNil(try sessions.load()?.pendingReauthenticationGrants)
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }

    func testPhoneRevocationFailsClosedWhenIdentityManagementIsNotAdvertised() async throws {
        let fixtures = AccountFixtures()
        let controller = DesktopAccountController(
            api: RecordingAccountAPI(
                fixtures: fixtures,
                identityManagementEnabled: false
            ),
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.4.0"
        )

        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(
            try await controller.requestPhoneRevocationVerification(id: fixtures.phoneA.id)
        ) { error in
            guard case .remote(let remote)? = error as? AccountClientError else {
                return XCTFail("Expected a structured account error")
            }
            XCTAssertEqual(remote.code, "HR-ACCOUNT-009")
            XCTAssertFalse(remote.retryable)
        }
    }

    func testAccountDeletionRequiresCapabilityAndReusesGrantAndMutationKeyAfterLostResponse() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let machines = MemoryMachineIdentityStore()
        let machineBefore = try machines.loadOrCreate()
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            accountDeletionEnabled: true,
            accountDeletionFailuresRemaining: 1
        )
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: machines,
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.4.0"
        )

        let bootstrapped = try await controller.bootstrap()
        guard case .signedIn(let dashboard) = bootstrapped else {
            return XCTFail("Expected signed-in dashboard")
        }
        XCTAssertTrue(dashboard.accountDeletionEnabled)
        let verification = try await controller.requestAccountDeletionVerification()
        await XCTAssertThrowsErrorAsync(
            try await controller.deleteAccount(
                verification: verification,
                verificationCode: "123456",
                acknowledgedPermanentCloudDeletion: true
            )
        ) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        XCTAssertNotNil(try sessions.load()?.pendingReauthenticationGrants?["account.delete"])
        XCTAssertNotNil(try sessions.load()?.pendingOperationIdempotencyKeys?["account.delete:commit"])

        let completed = try await controller.deleteAccount(
            verification: verification,
            verificationCode: "different-code-is-not-reused",
            acknowledgedPermanentCloudDeletion: true
        )
        let reauthenticationAttempts = await api.emailReauthenticationAttempts()
        let deletionAttempts = await api.accountDeletionAttempts()
        XCTAssertEqual(reauthenticationAttempts.count, 1)
        XCTAssertEqual(reauthenticationAttempts[0].scope, "account.delete")
        XCTAssertEqual(deletionAttempts.count, 2)
        XCTAssertEqual(deletionAttempts.map(\.grant), [deletionAttempts[0].grant, deletionAttempts[0].grant])
        XCTAssertEqual(
            deletionAttempts.map(\.idempotencyKey),
            [deletionAttempts[0].idempotencyKey, deletionAttempts[0].idempotencyKey]
        )
        XCTAssertTrue(deletionAttempts.allSatisfy(\.acknowledged))
        XCTAssertEqual(completed, .accountDeletionSubmitted)
        XCTAssertNil(try sessions.load())
        let emptySessionState = try await controller.refresh()
        XCTAssertEqual(emptySessionState, .signedOut)
        XCTAssertEqual(try machines.loadOrCreate(), machineBefore)
    }

    func testAccountDeletionIsUnavailableWhenCapabilityIsNotAdvertised() async throws {
        let fixtures = AccountFixtures()
        let controller = DesktopAccountController(
            api: RecordingAccountAPI(fixtures: fixtures),
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.4.0"
        )
        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(
            try await controller.requestAccountDeletionVerification()
        ) { error in
            guard case .remote(let remote)? = error as? AccountClientError else {
                return XCTFail("Expected a structured account error")
            }
            XCTAssertEqual(remote.code, "HR-ACCOUNT-003")
        }
    }

    func testBootstrapRetriesPersistedAccountDeletionWithoutReusingTheEmailCode() async throws {
        let fixtures = AccountFixtures()
        let deletionKey = "90000000-0000-4000-8000-000000000009"
        let pending = AccountSessionRecord(
            account: fixtures.record.account,
            installation: fixtures.record.installation,
            session: fixtures.record.session,
            pendingOperationIdempotencyKeys: ["account.delete:commit": deletionKey],
            pendingReauthenticationGrants: ["account.delete": "hgg_pending"]
        )
        let sessions = MemoryAccountSessionStore(record: pending)
        let api = RecordingAccountAPI(fixtures: fixtures, accountDeletionEnabled: true)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.4.0"
        )

        let bootstrapState = try await controller.bootstrap()
        XCTAssertEqual(bootstrapState, .accountDeletionSubmitted)
        let deletionAttempts = await api.accountDeletionAttempts()
        XCTAssertEqual(deletionAttempts.count, 1)
        XCTAssertEqual(deletionAttempts[0].grant, "hgg_pending")
        XCTAssertEqual(deletionAttempts[0].idempotencyKey, deletionKey)
        XCTAssertTrue(deletionAttempts[0].acknowledged)
        let reauthenticationAttempts = await api.emailReauthenticationAttempts()
        XCTAssertTrue(reauthenticationAttempts.isEmpty)
        XCTAssertNil(try sessions.load())
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

    func testMultiDeviceDashboardRestoresLocalSelectionAndFallsBackToAccountDefault() async throws {
        let fixtures = AccountFixtures()
        let selections = MemoryDeviceSelectionStore(value: "missing-device")
        let controller = DesktopAccountController(
            api: RecordingAccountAPI(fixtures: fixtures, multiDeviceEnabled: true),
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: selections,
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        let state = try await controller.bootstrap()
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected signed-in dashboard")
        }

        XCTAssertEqual(dashboard.devices.count, 2)
        XCTAssertEqual(dashboard.maxOwnedDevices, 3)
        XCTAssertEqual(dashboard.selectedDeviceID, fixtures.deviceB.deviceId)
        XCTAssertEqual(selections.value, fixtures.deviceB.deviceId)
    }

    func testLegacyCapabilityDoesNotEraseRememberedMultiDeviceSelection() async throws {
        let fixtures = AccountFixtures()
        let selections = MemoryDeviceSelectionStore(value: fixtures.deviceA.deviceId)
        let controller = DesktopAccountController(
            api: RecordingAccountAPI(fixtures: fixtures, multiDeviceEnabled: false),
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: selections,
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        let state = try await controller.bootstrap()
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected signed-in dashboard")
        }

        XCTAssertTrue(dashboard.devices.isEmpty)
        XCTAssertNil(dashboard.selectedDeviceID)
        XCTAssertEqual(selections.value, fixtures.deviceA.deviceId)
    }

    func testSharingDashboardSeparatesOwnedAndOperatorDevicesAndLoadsOwnerManagementOnly() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            multiDeviceEnabled: true,
            sharingEnabled: true
        )
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        let state = try await controller.bootstrap()
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected signed-in dashboard")
        }

        XCTAssertTrue(dashboard.supportsDeviceSharing)
        XCTAssertEqual(dashboard.maxSharedDevices, 10)
        XCTAssertEqual(dashboard.ownedDevices.map(\.deviceId), [fixtures.deviceA.deviceId, fixtures.deviceB.deviceId])
        XCTAssertEqual(dashboard.sharedDevices.map(\.deviceId), [fixtures.sharedDevice.deviceId])
        XCTAssertEqual(Set(dashboard.deviceShares.keys), Set([fixtures.deviceA.deviceId, fixtures.deviceB.deviceId]))
        let lookups = await api.shareManagementLookups()
        XCTAssertEqual(lookups, [fixtures.deviceA.deviceId, fixtures.deviceB.deviceId])
    }

    func testShareInvitationRequiresDisclosureAndReusesKeychainGrantAfterLostResponse() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            multiDeviceEnabled: true,
            sharingEnabled: true,
            shareCreateFailuresRemaining: 1
        )
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        let verification = try await controller.requestShareInvitationVerification(
            deviceID: fixtures.deviceA.deviceId
        )
        await XCTAssertThrowsErrorAsync(
            try await controller.createShareInvitation(
                deviceID: fixtures.deviceA.deviceId,
                email: "guest@example.invalid",
                acknowledgedWholeDeviceAccess: false,
                verification: verification,
                verificationCode: "123456"
            )
        ) { error in
            guard let clientError = error as? AccountClientError,
                  case .remote(let remote) = clientError
            else {
                return XCTFail("Expected disclosure error")
            }
            XCTAssertEqual(remote.code, "HR-SHARE-006")
        }
        await XCTAssertThrowsErrorAsync(
            try await controller.createShareInvitation(
                deviceID: fixtures.deviceA.deviceId,
                email: "Guest@Example.invalid ",
                acknowledgedWholeDeviceAccess: true,
                verification: verification,
                verificationCode: "123456"
            )
        ) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        let pending = try XCTUnwrap(try sessions.load())
        XCTAssertEqual(pending.pendingReauthenticationGrants?.count, 1)
        XCTAssertEqual(pending.pendingOperationIdempotencyKeys?.count, 1)

        _ = try await controller.createShareInvitation(
            deviceID: fixtures.deviceA.deviceId,
            email: "guest@example.invalid",
            acknowledgedWholeDeviceAccess: true,
            verification: verification,
            verificationCode: "123456"
        )
        let attempts = await api.shareCreateAttempts()
        let reauthenticationAttempts = await api.emailReauthenticationAttempts()
        XCTAssertEqual(reauthenticationAttempts.count, 1)
        XCTAssertEqual(reauthenticationAttempts[0].email, fixtures.account.email)
        XCTAssertEqual(reauthenticationAttempts[0].code, "123456")
        XCTAssertEqual(reauthenticationAttempts[0].idempotencyKey, verification.idempotencyKey)
        XCTAssertEqual(attempts.count, 2)
        XCTAssertEqual(attempts.map(\.grant), [attempts[0].grant, attempts[0].grant])
        XCTAssertEqual(attempts.map(\.idempotencyKey), [attempts[0].idempotencyKey, attempts[0].idempotencyKey])
        XCTAssertEqual(attempts.map(\.email), ["guest@example.invalid", "guest@example.invalid"])
        XCTAssertNil(try sessions.load()?.pendingReauthenticationGrants)
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }

    func testLostEmailReauthenticationResponseReusesPersistedIdempotencyKey() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            multiDeviceEnabled: true,
            sharingEnabled: true,
            emailReauthenticationFailuresRemaining: 1
        )
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.4.0"
        )

        _ = try await controller.bootstrap()
        let verification = try await controller.requestShareInvitationVerification(
            deviceID: fixtures.deviceA.deviceId
        )
        await XCTAssertThrowsErrorAsync(
            try await controller.createShareInvitation(
                deviceID: fixtures.deviceA.deviceId,
                email: "guest@example.invalid",
                acknowledgedWholeDeviceAccess: true,
                verification: verification,
                verificationCode: "123456"
            )
        ) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        _ = try await controller.createShareInvitation(
            deviceID: fixtures.deviceA.deviceId,
            email: "guest@example.invalid",
            acknowledgedWholeDeviceAccess: true,
            verification: verification,
            verificationCode: "123456"
        )
        let attempts = await api.emailReauthenticationAttempts()

        XCTAssertEqual(attempts.count, 2)
        XCTAssertEqual(attempts.map(\.challengeID), [
            verification.challenge.challengeId,
            verification.challenge.challengeId,
        ])
        XCTAssertEqual(attempts.map(\.idempotencyKey), [
            verification.idempotencyKey,
            verification.idempotencyKey,
        ])
        XCTAssertNil(try sessions.load()?.pendingReauthenticationGrants)
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys)
    }

    func testShareInvitationParserAcceptsOnlyExactTokenOrAccountFragment() {
        let token = "hsi_" + String(repeating: "a", count: 43)
        XCTAssertEqual(DesktopAccountController.shareInvitationToken(from: token), token)
        XCTAssertEqual(
            DesktopAccountController.shareInvitationToken(
                from: "https://relay.example/account#share-invitation=\(token)"
            ),
            token
        )
        XCTAssertNil(DesktopAccountController.shareInvitationToken(from: "https://evil.invalid/#token=\(token)"))
        XCTAssertNil(DesktopAccountController.shareInvitationToken(from: "hsi_short"))
    }

    func testLocalDeviceSelectionDoesNotChangeCloudDefault() async throws {
        let fixtures = AccountFixtures()
        let api = RecordingAccountAPI(fixtures: fixtures, multiDeviceEnabled: true)
        let selections = MemoryDeviceSelectionStore()
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: selections,
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        let state = try await controller.selectDevice(id: fixtures.deviceA.deviceId)
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected signed-in dashboard")
        }

        XCTAssertEqual(dashboard.selectedDeviceID, fixtures.deviceA.deviceId)
        XCTAssertEqual(selections.value, fixtures.deviceA.deviceId)
        let cloudSelection = await api.selectedDefaultInput()
        XCTAssertNil(cloudSelection)
    }

    func testDefaultSelectionUsesStablePersistedIdempotencyKeyAfterLostResponse() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let api = RecordingAccountAPI(
            fixtures: fixtures,
            multiDeviceEnabled: true,
            defaultSelectionFailuresRemaining: 1
        )
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Mac mini",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(
            try await controller.selectDefaultDevice(id: fixtures.deviceA.deviceId)
        ) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        let operation = "device.select-default:\(fixtures.deviceA.deviceId)"
        let pendingKey = try XCTUnwrap(
            try sessions.load()?.pendingOperationIdempotencyKeys?[operation]
        )
        _ = try await controller.selectDefaultDevice(id: fixtures.deviceA.deviceId)
        let attempts = await api.defaultSelectionAttempts()

        XCTAssertEqual(attempts.map(\.idempotencyKey), [pendingKey, pendingKey])
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys?[operation])
    }

    func testBindingPreparationRegistersTheKeyAndProducesConnectorOnlyCredential() async throws {
        let fixtures = AccountFixtures()
        let machines = MemoryMachineIdentityStore()
        let api = RecordingAccountAPI(fixtures: fixtures)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: machines,
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        let preparation = try await controller.beginBinding()
        let requestValue = await api.lastBindingCreateInput()
        let request = try XCTUnwrap(requestValue)
        let credential = try XCTUnwrap(
            JSONSerialization.jsonObject(with: preparation.credential.data) as? [String: Any]
        )
        let machine = try machines.loadOrCreate()

        XCTAssertEqual(request.desktopInstallationID, fixtures.desktop.id)
        XCTAssertEqual(request.displayName, "Office Mac")
        XCTAssertEqual(request.connectorPublicKey, machine.connectorPublicKey)
        XCTAssertEqual(credential["bindingId"] as? String, "50000000-0000-4000-8000-000000000001")
        XCTAssertEqual(credential["generation"] as? Int, 1)
        XCTAssertEqual(credential["publicKeyFingerprint"] as? String, machine.connectorPublicKeyFingerprint)
        XCTAssertNotNil(credential["privateKey"] as? String)
        XCTAssertFalse(String(describing: preparation.credential).contains(credential["privateKey"] as! String))
    }

    func testRevokedBindingCanBeRecreatedOnlyForTheRecordedRollbackGeneration() async throws {
        let fixtures = AccountFixtures()
        let revoked = AccountBindingSnapshot(
            state: "revoked",
            id: nil,
            generation: 1,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: nil,
            previousBinding: nil
        )
        let api = RecordingAccountAPI(fixtures: fixtures, bindingSnapshot: revoked)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        let preparation = try await controller.beginBinding(
            retryingTerminalBindingID: "50000000-0000-4000-8000-000000000001",
            retryingTerminalGeneration: 1
        )

        guard case .signedIn(let dashboard) = preparation.state else {
            return XCTFail("Expected pending binding dashboard")
        }
        XCTAssertEqual(dashboard.binding.state, "binding_pending")
        let createAttempts = await api.bindingCreateAttempts()
        XCTAssertEqual(createAttempts.count, 1)
    }

    func testRevokedBindingWithoutMatchingRollbackGenerationFailsClosed() async throws {
        let fixtures = AccountFixtures()
        let revoked = AccountBindingSnapshot(
            state: "revoked",
            id: nil,
            generation: 1,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: nil,
            previousBinding: nil
        )
        let api = RecordingAccountAPI(fixtures: fixtures, bindingSnapshot: revoked)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(
            try await controller.beginBinding(
                retryingTerminalBindingID: "50000000-0000-4000-8000-000000000001",
                retryingTerminalGeneration: 2
            )
        ) { error in
            guard case .remote(let remote)? = error as? AccountClientError else {
                return XCTFail("Expected a structured remote binding error")
            }
            XCTAssertEqual(remote.code, "HR-BIND-006")
        }
        let createAttempts = await api.bindingCreateAttempts()
        XCTAssertTrue(createAttempts.isEmpty)
    }

    func testMatchingBoundRollbackCanResumeWithoutCreatingAReplacement() async throws {
        let fixtures = AccountFixtures()
        let machines = MemoryMachineIdentityStore()
        let machine = try machines.loadOrCreate()
        let bindingID = "50000000-0000-4000-8000-000000000001"
        let bound = AccountBindingSnapshot(
            state: "bound",
            id: nil,
            generation: nil,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: ActiveAccountBinding(
                id: bindingID,
                generation: 7,
                deviceId: "hermes-bound",
                desktopDisplayName: "Office Mac",
                publicKeyFingerprint: machine.connectorPublicKeyFingerprint,
                connector: .init(online: false, lastSeenAt: nil),
                hermes: .init(reachable: true, version: "1.0.0"),
                gateway: .init(latencyMs: nil),
                endToEnd: .init(healthy: false, checkedAt: nil)
            ),
            previousBinding: nil
        )
        let api = RecordingAccountAPI(fixtures: fixtures, bindingSnapshot: bound)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: machines,
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        let preparation = try await controller.beginBinding(
            retryingTerminalBindingID: bindingID,
            retryingTerminalGeneration: 7
        )
        let credential = try XCTUnwrap(
            JSONSerialization.jsonObject(with: preparation.credential.data) as? [String: Any]
        )

        XCTAssertEqual(credential["bindingId"] as? String, bindingID.lowercased())
        XCTAssertEqual(credential["generation"] as? Int, 7)
        let createAttempts = await api.bindingCreateAttempts()
        XCTAssertTrue(createAttempts.isEmpty)
    }

    func testBoundRollbackResumeFailsClosedWhenJournalBindingDoesNotMatch() async throws {
        let fixtures = AccountFixtures()
        let machines = MemoryMachineIdentityStore()
        let machine = try machines.loadOrCreate()
        let bound = AccountBindingSnapshot(
            state: "bound",
            id: nil,
            generation: nil,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: ActiveAccountBinding(
                id: "50000000-0000-4000-8000-000000000001",
                generation: 7,
                deviceId: "hermes-bound",
                desktopDisplayName: "Office Mac",
                publicKeyFingerprint: machine.connectorPublicKeyFingerprint,
                connector: .init(online: false, lastSeenAt: nil),
                hermes: .init(reachable: true, version: "1.0.0"),
                gateway: .init(latencyMs: nil),
                endToEnd: .init(healthy: false, checkedAt: nil)
            ),
            previousBinding: nil
        )
        let api = RecordingAccountAPI(fixtures: fixtures, bindingSnapshot: bound)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: machines,
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(try await controller.beginBinding(
            retryingTerminalBindingID: "50000000-0000-4000-8000-000000000099",
            retryingTerminalGeneration: 7
        )) { error in
            guard case .remote(let remote)? = error as? AccountClientError else {
                return XCTFail("Expected a structured binding conflict")
            }
            XCTAssertEqual(remote.code, "HR-BIND-002")
        }
        let createAttempts = await api.bindingCreateAttempts()
        XCTAssertTrue(createAttempts.isEmpty)
    }

    func testBoundRollbackResumeFailsClosedWhenMachineKeyDoesNotMatch() async throws {
        let fixtures = AccountFixtures()
        let bindingID = "50000000-0000-4000-8000-000000000001"
        let bound = AccountBindingSnapshot(
            state: "bound",
            id: nil,
            generation: nil,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: ActiveAccountBinding(
                id: bindingID,
                generation: 7,
                deviceId: "hermes-bound",
                desktopDisplayName: "Office Mac",
                publicKeyFingerprint: String(repeating: "f", count: 64),
                connector: .init(online: false, lastSeenAt: nil),
                hermes: .init(reachable: true, version: "1.0.0"),
                gateway: .init(latencyMs: nil),
                endToEnd: .init(healthy: false, checkedAt: nil)
            ),
            previousBinding: nil
        )
        let api = RecordingAccountAPI(fixtures: fixtures, bindingSnapshot: bound)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: MemoryAccountSessionStore(record: fixtures.record),
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(try await controller.beginBinding(
            retryingTerminalBindingID: bindingID,
            retryingTerminalGeneration: 7
        )) { error in
            XCTAssertEqual(error as? AccountSecretStoreError, .invalidMachineIdentity)
        }
        let createAttempts = await api.bindingCreateAttempts()
        XCTAssertTrue(createAttempts.isEmpty)
    }

    func testLostBindingCreateResponseReusesPersistedIdempotencyKey() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let api = RecordingAccountAPI(fixtures: fixtures, bindingCreateFailuresRemaining: 1)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        await XCTAssertThrowsErrorAsync(try await controller.beginBinding())
        let pendingKey = try XCTUnwrap(
            try sessions.load()?.pendingOperationIdempotencyKeys?["binding.create"]
        )
        _ = try await controller.beginBinding()
        let attempts = await api.bindingCreateAttempts()

        XCTAssertEqual(attempts.map(\.idempotencyKey), [pendingKey, pendingKey])
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys?["binding.create"])
    }

    func testLostBindingConfirmationResponseReusesPersistedIdempotencyKey() async throws {
        let fixtures = AccountFixtures()
        let sessions = MemoryAccountSessionStore(record: fixtures.record)
        let api = RecordingAccountAPI(fixtures: fixtures, bindingConfirmFailuresRemaining: 1)
        let controller = DesktopAccountController(
            api: api,
            sessionStore: sessions,
            machineIdentityStore: MemoryMachineIdentityStore(),
            deviceSelectionStore: MemoryDeviceSelectionStore(),
            oauth: nil,
            displayName: "Office Mac",
            appVersion: "0.3.0"
        )

        _ = try await controller.bootstrap()
        let preparation = try await controller.beginBinding()
        guard case .signedIn(let pendingDashboard) = preparation.state else {
            return XCTFail("Expected pending binding dashboard")
        }
        await api.markPendingBindingHealthy()
        _ = try await controller.refresh()
        await XCTAssertThrowsErrorAsync(try await controller.confirmBinding()) { error in
            XCTAssertEqual(error as? AccountClientError, .transport)
        }
        let operation = "binding.confirm:\(pendingDashboard.binding.id!):\(pendingDashboard.binding.generation!)"
        let pendingKey = try XCTUnwrap(
            try sessions.load()?.pendingOperationIdempotencyKeys?[operation]
        )

        let state = try await controller.confirmBinding()
        guard case .signedIn(let dashboard) = state else {
            return XCTFail("Expected confirmed dashboard")
        }
        let attempts = await api.bindingConfirmAttempts()
        XCTAssertEqual(attempts, [pendingKey, pendingKey])
        XCTAssertEqual(dashboard.binding.state, "bound")
        XCTAssertNil(try sessions.load()?.pendingOperationIdempotencyKeys?[operation])
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
            accountAuth: .init(
                enabled: true,
                providers: ["email_otp"],
                android: true,
                macos: true,
                identityManagement: true
            ),
            binding: .init(enabled: true, replacement: true, maxActiveConnectorsPerAccount: 1),
            legacy: .init(appTokenAccepted: true, connectorTokenAccepted: true)
        )
    }

    func capabilities(
        multiDeviceEnabled: Bool,
        sharingEnabled: Bool = false,
        bindingEnabled: Bool = true,
        identityManagementEnabled: Bool = true,
        accountDeletionEnabled: Bool = false
    ) -> AccountCapabilities {
        AccountCapabilities(
            version: 1,
            accountAuth: .init(
                enabled: true,
                providers: ["email_otp"],
                android: true,
                macos: true,
                identityManagement: identityManagementEnabled,
                accountDeletion: accountDeletionEnabled
            ),
            binding: .init(
                enabled: bindingEnabled,
                replacement: bindingEnabled,
                maxActiveConnectorsPerAccount: multiDeviceEnabled ? 3 : 1,
                supportsDeviceSelection: bindingEnabled && multiDeviceEnabled ? true : nil,
                supportsDeviceSharing: bindingEnabled && sharingEnabled ? true : nil,
                maxSharedDevices: sharingEnabled ? 10 : nil,
                maxGranteesPerDevice: sharingEnabled ? 5 : nil
            ),
            legacy: .init(appTokenAccepted: true, connectorTokenAccepted: true)
        )
    }

    var deviceA: AccountDevice { device(id: "40000000-0000-4000-8000-000000000001", suffix: "a", name: "Office Mac", isDefault: false) }
    var deviceB: AccountDevice { device(id: "40000000-0000-4000-8000-000000000002", suffix: "b", name: "Home Mac", isDefault: true) }
    var sharedDevice: AccountDevice {
        device(
            id: "40000000-0000-4000-8000-000000000003",
            suffix: "c",
            name: "Shared Mac",
            access: "operator",
            isDefault: false
        )
    }

    private func device(
        id: String,
        suffix: String,
        name: String,
        access: String = "owner",
        isDefault: Bool
    ) -> AccountDevice {
        AccountDevice(
            id: id,
            generation: 1,
            deviceId: "hermes-\(suffix)",
            desktopDisplayName: name,
            publicKeyFingerprint: String(repeating: suffix, count: 64),
            connector: .init(online: true, lastSeenAt: "2026-09-07T00:00:00Z"),
            hermes: .init(reachable: true, version: "1.0.0"),
            gateway: .init(latencyMs: 12),
            endToEnd: .init(healthy: true, checkedAt: "2026-09-07T00:00:00Z"),
            access: access,
            isDefault: isDefault
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

    struct EmailChallengeInput {
        let email: String
        let clientInstallationID: String
    }

    struct EmailExchangeInput {
        let challengeID: String
        let email: String
        let code: String
        let clientInstallationID: String
        let displayName: String
        let appVersion: String
        let idempotencyKey: String
    }

    struct EmailReauthenticationInput {
        let challengeID: String
        let email: String
        let code: String
        let scope: String
        let idempotencyKey: String
    }

    struct DefaultSelectionInput {
        let id: String
        let idempotencyKey: String
    }

    struct BindingCreateInput {
        let desktopInstallationID: String
        let displayName: String
        let connectorPublicKey: String
        let idempotencyKey: String
    }

    struct ShareCreateInput {
        let deviceID: String
        let email: String
        let grant: String
        let idempotencyKey: String
    }

    struct PhoneRevocationInput {
        let id: String
        let grant: String
        let idempotencyKey: String
    }

    struct AccountDeletionInput {
        let grant: String
        let acknowledged: Bool
        let idempotencyKey: String
    }

    private let fixtures: AccountFixtures
    private var exchanged: ExchangeInput?
    private var requestedEmailChallenge: EmailChallengeInput?
    private var exchangedEmailChallenge: EmailExchangeInput?
    private var emailReauthenticationHistory: [EmailReauthenticationInput] = []
    private var emailReauthenticationFailuresRemaining: Int
    private var refreshed: RefreshInput?
    private var refreshHistory: [RefreshInput] = []
    private var refreshFailuresRemaining: Int
    private let accountEnabled: Bool
    private var revoked: String?
    private var phoneRevocationHistory: [PhoneRevocationInput] = []
    private var phoneRevocationFailuresRemaining: Int
    private var signedOut = false
    private var accountDeletionHistory: [AccountDeletionInput] = []
    private var accountDeletionFailuresRemaining: Int
    private let multiDeviceEnabled: Bool
    private let sharingEnabled: Bool
    private let bindingEnabled: Bool
    private let identityManagementEnabled: Bool
    private let accountDeletionEnabled: Bool
    private var shareManagementHistory: [String] = []
    private var shareCreateHistory: [ShareCreateInput] = []
    private var shareCreateFailuresRemaining: Int
    private var defaultSelectionHistory: [DefaultSelectionInput] = []
    private var defaultSelectionFailuresRemaining: Int
    private var bindingSnapshot: AccountBindingSnapshot
    private var bindingCreateHistory: [BindingCreateInput] = []
    private var bindingCreateFailuresRemaining: Int
    private var bindingConfirmHistory: [String] = []
    private var bindingConfirmFailuresRemaining: Int
    private var pendingBindingFingerprint: String?

    init(
        fixtures: AccountFixtures,
        refreshFailuresRemaining: Int = 0,
        accountEnabled: Bool = true,
        multiDeviceEnabled: Bool = false,
        sharingEnabled: Bool = false,
        bindingEnabled: Bool = true,
        identityManagementEnabled: Bool = true,
        accountDeletionEnabled: Bool = false,
        accountDeletionFailuresRemaining: Int = 0,
        phoneRevocationFailuresRemaining: Int = 0,
        shareCreateFailuresRemaining: Int = 0,
        emailReauthenticationFailuresRemaining: Int = 0,
        defaultSelectionFailuresRemaining: Int = 0,
        bindingCreateFailuresRemaining: Int = 0,
        bindingConfirmFailuresRemaining: Int = 0,
        bindingSnapshot: AccountBindingSnapshot? = nil
    ) {
        self.fixtures = fixtures
        self.refreshFailuresRemaining = refreshFailuresRemaining
        self.accountEnabled = accountEnabled
        self.multiDeviceEnabled = multiDeviceEnabled
        self.sharingEnabled = sharingEnabled
        self.bindingEnabled = bindingEnabled
        self.identityManagementEnabled = identityManagementEnabled
        self.accountDeletionEnabled = accountDeletionEnabled
        self.accountDeletionFailuresRemaining = accountDeletionFailuresRemaining
        self.phoneRevocationFailuresRemaining = phoneRevocationFailuresRemaining
        self.shareCreateFailuresRemaining = shareCreateFailuresRemaining
        self.emailReauthenticationFailuresRemaining = emailReauthenticationFailuresRemaining
        self.defaultSelectionFailuresRemaining = defaultSelectionFailuresRemaining
        self.bindingSnapshot = bindingSnapshot ?? fixtures.binding
        self.bindingCreateFailuresRemaining = bindingCreateFailuresRemaining
        self.bindingConfirmFailuresRemaining = bindingConfirmFailuresRemaining
    }

    func capabilities() async throws -> AccountCapabilities {
        guard !accountEnabled else {
            return fixtures.capabilities(
                multiDeviceEnabled: multiDeviceEnabled,
                sharingEnabled: sharingEnabled,
                bindingEnabled: bindingEnabled,
                identityManagementEnabled: identityManagementEnabled,
                accountDeletionEnabled: accountDeletionEnabled
            )
        }
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

    func requestEmailSignInChallenge(
        email: String,
        clientInstallationID: String
    ) async throws -> EmailOtpChallenge {
        requestedEmailChallenge = .init(
            email: email,
            clientInstallationID: clientInstallationID
        )
        return emailChallenge
    }

    func exchangeEmailChallenge(
        challengeID: String,
        email: String,
        code: String,
        clientInstallationID: String,
        displayName: String,
        appVersion: String,
        idempotencyKey: String
    ) async throws -> AccountSessionRecord {
        exchangedEmailChallenge = .init(
            challengeID: challengeID,
            email: email,
            code: code,
            clientInstallationID: clientInstallationID,
            displayName: displayName,
            appVersion: appVersion,
            idempotencyKey: idempotencyKey
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
        guard identityManagementEnabled else { throw AccountClientError.transport }
        return [fixtures.phoneA, fixtures.phoneB]
    }
    func binding(accessToken: String) async throws -> AccountBindingSnapshot {
        guard bindingEnabled else { throw AccountClientError.transport }
        return bindingSnapshot
    }
    func devices(accessToken: String) async throws -> AccountDevicePage {
        AccountDevicePage(
            items: [fixtures.deviceA, fixtures.deviceB] + (sharingEnabled ? [fixtures.sharedDevice] : []),
            maxOwnedDevices: 3
        )
    }
    func selectDefaultDevice(
        id: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountDevice {
        defaultSelectionHistory.append(.init(id: id, idempotencyKey: idempotencyKey))
        if defaultSelectionFailuresRemaining > 0 {
            defaultSelectionFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        guard let device = [fixtures.deviceA, fixtures.deviceB].first(where: { $0.deviceId == id }) else {
            throw AccountClientError.invalidResponse
        }
        return AccountDevice(
            id: device.id,
            generation: device.generation,
            deviceId: device.deviceId,
            desktopDisplayName: device.desktopDisplayName,
            publicKeyFingerprint: device.publicKeyFingerprint,
            connector: device.connector,
            hermes: device.hermes,
            gateway: device.gateway,
            endToEnd: device.endToEnd,
            access: device.access,
            isDefault: true
        )
    }
    func reauthenticateGoogle(
        _ proof: GoogleIdentityProof,
        scope: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant {
        AccountReauthenticationGrant(
            grant: "hgg_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
            scope: scope,
            expiresAt: "2099-09-07T00:10:00Z"
        )
    }
    func requestEmailReauthenticationChallenge(
        email: String,
        accessToken: String
    ) async throws -> EmailOtpChallenge {
        emailChallenge
    }
    func reauthenticateEmail(
        challengeID: String,
        email: String,
        code: String,
        scope: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountReauthenticationGrant {
        emailReauthenticationHistory.append(.init(
            challengeID: challengeID,
            email: email,
            code: code,
            scope: scope,
            idempotencyKey: idempotencyKey
        ))
        if emailReauthenticationFailuresRemaining > 0 {
            emailReauthenticationFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return AccountReauthenticationGrant(
            grant: "hgg_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
            scope: scope,
            expiresAt: "2099-09-07T00:10:00Z"
        )
    }
    func deviceShares(id: String, accessToken: String) async throws -> DeviceShareManagement {
        shareManagementHistory.append(id)
        return DeviceShareManagement(invitations: [], grants: [], maxGranteesPerDevice: 5)
    }
    func createShareInvitation(
        deviceID: String,
        email: String,
        grant: String,
        acknowledgedWholeDeviceAccess: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> DeviceShareInvitation {
        shareCreateHistory.append(.init(
            deviceID: deviceID,
            email: email,
            grant: grant,
            idempotencyKey: idempotencyKey
        ))
        if shareCreateFailuresRemaining > 0 {
            shareCreateFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return DeviceShareInvitation(
            id: "60000000-0000-4000-8000-000000000001",
            deviceId: deviceID,
            targetEmailHint: "g***@example.invalid",
            status: "pending",
            expiresAt: "2099-09-10T00:00:00Z",
            createdAt: "2099-09-07T00:00:00Z"
        )
    }
    func cancelShareInvitation(
        deviceID: String,
        invitationID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {}
    func revokeDeviceShare(
        deviceID: String,
        grantID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {}
    func leaveSharedDevice(
        deviceID: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {}
    func acceptShareInvitation(
        token: String,
        acknowledgedWholeDeviceAccess: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountDevice {
        fixtures.deviceA
    }
    func createBinding(
        desktopInstallationID: String,
        displayName: String,
        connectorPublicKey: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingSnapshot {
        bindingCreateHistory.append(BindingCreateInput(
            desktopInstallationID: desktopInstallationID,
            displayName: displayName,
            connectorPublicKey: connectorPublicKey,
            idempotencyKey: idempotencyKey
        ))
        if bindingCreateFailuresRemaining > 0 {
            bindingCreateFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        let publicKey = Data(base64URLEncodedForTest: connectorPublicKey)!
        let fingerprint = SHA256.hash(data: publicKey).map { String(format: "%02x", $0) }.joined()
        pendingBindingFingerprint = fingerprint
        bindingSnapshot = AccountBindingSnapshot(
            state: "binding_pending",
            id: "50000000-0000-4000-8000-000000000001",
            generation: 1,
            deviceId: "hermes-pending",
            displayName: displayName,
            publicKeyFingerprint: fingerprint,
            expiresAt: "2099-09-07T00:10:00Z",
            keyProved: false,
            healthVerified: false,
            binding: nil,
            previousBinding: nil
        )
        return bindingSnapshot
    }
    func confirmBinding(
        bindingID: String,
        generation: Int,
        accessToken: String,
        idempotencyKey: String
    ) async throws -> AccountBindingSnapshot {
        bindingConfirmHistory.append(idempotencyKey)
        bindingSnapshot = AccountBindingSnapshot(
            state: "bound",
            id: nil,
            generation: nil,
            deviceId: nil,
            displayName: nil,
            expiresAt: nil,
            keyProved: nil,
            healthVerified: nil,
            binding: ActiveAccountBinding(
                id: bindingID,
                generation: generation,
                deviceId: "hermes-pending",
                desktopDisplayName: "Mac mini",
                publicKeyFingerprint: pendingBindingFingerprint ?? "",
                connector: .init(online: true, lastSeenAt: "2026-09-07T00:00:00Z"),
                hermes: .init(reachable: true, version: "1.0.0"),
                gateway: .init(latencyMs: 10),
                endToEnd: .init(healthy: true, checkedAt: "2026-09-07T00:00:00Z")
            ),
            previousBinding: nil
        )
        if bindingConfirmFailuresRemaining > 0 {
            bindingConfirmFailuresRemaining -= 1
            throw AccountClientError.transport
        }
        return bindingSnapshot
    }

    func markPendingBindingHealthy() {
        bindingSnapshot = AccountBindingSnapshot(
            state: bindingSnapshot.state,
            id: bindingSnapshot.id,
            generation: bindingSnapshot.generation,
            deviceId: bindingSnapshot.deviceId,
            displayName: bindingSnapshot.displayName,
            publicKeyFingerprint: bindingSnapshot.publicKeyFingerprint,
            expiresAt: bindingSnapshot.expiresAt,
            keyProved: true,
            healthVerified: true,
            binding: bindingSnapshot.binding,
            previousBinding: bindingSnapshot.previousBinding
        )
    }

    func revokePhone(
        id: String,
        grant: String,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        revoked = id
        phoneRevocationHistory.append(.init(id: id, grant: grant, idempotencyKey: idempotencyKey))
        if phoneRevocationFailuresRemaining > 0 {
            phoneRevocationFailuresRemaining -= 1
            throw AccountClientError.transport
        }
    }
    func deleteAccount(
        grant: String,
        acknowledgedPermanentCloudDeletion: Bool,
        accessToken: String,
        idempotencyKey: String
    ) async throws {
        accountDeletionHistory.append(.init(
            grant: grant,
            acknowledged: acknowledgedPermanentCloudDeletion,
            idempotencyKey: idempotencyKey
        ))
        if accountDeletionFailuresRemaining > 0 {
            accountDeletionFailuresRemaining -= 1
            throw AccountClientError.transport
        }
    }
    func signOut(accessToken: String, idempotencyKey: String) async throws { signedOut = true }

    func exchangeInput() -> ExchangeInput? { exchanged }
    func emailChallengeInput() -> EmailChallengeInput? { requestedEmailChallenge }
    func emailExchangeInput() -> EmailExchangeInput? { exchangedEmailChallenge }
    func emailReauthenticationAttempts() -> [EmailReauthenticationInput] {
        emailReauthenticationHistory
    }
    func refreshInput() -> RefreshInput? { refreshed }
    func refreshAttempts() -> [RefreshInput] { refreshHistory }
    func revokedPhoneID() -> String? { revoked }
    func phoneRevocationAttempts() -> [PhoneRevocationInput] { phoneRevocationHistory }
    func accountDeletionAttempts() -> [AccountDeletionInput] { accountDeletionHistory }
    func didSignOut() -> Bool { signedOut }
    func selectedDefaultInput() -> DefaultSelectionInput? { defaultSelectionHistory.last }
    func defaultSelectionAttempts() -> [DefaultSelectionInput] { defaultSelectionHistory }
    func lastBindingCreateInput() -> BindingCreateInput? { bindingCreateHistory.last }
    func bindingCreateAttempts() -> [BindingCreateInput] { bindingCreateHistory }
    func bindingConfirmAttempts() -> [String] { bindingConfirmHistory }
    func shareManagementLookups() -> [String] { shareManagementHistory }
    func shareCreateAttempts() -> [ShareCreateInput] { shareCreateHistory }

    private var emailChallenge: EmailOtpChallenge {
        EmailOtpChallenge(
            challengeId: "90000000-0000-4000-8000-000000000001",
            expiresAt: "2099-09-07T00:10:00Z",
            resendAfter: "2099-09-07T00:01:00Z"
        )
    }
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

private final class MemoryDeviceSelectionStore: DeviceSelectionStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var storedValue: String?

    init(value: String? = nil) { storedValue = value }

    var value: String? { lock.withLock { storedValue } }

    func load(accountID: String) -> String? { value }

    func save(_ deviceID: String?, accountID: String) {
        lock.withLock { storedValue = deviceID }
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

private extension Data {
    init?(base64URLEncodedForTest value: String) {
        let padding = String(repeating: "=", count: (4 - value.count % 4) % 4)
        self.init(base64Encoded: value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + padding)
    }
}

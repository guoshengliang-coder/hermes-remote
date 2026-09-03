import CryptoKit
import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class AccountSecurityTests: XCTestCase {
    func testMachineIdentityProducesEd25519ProofWithoutExposingPrivateMaterial() throws {
        let identity = try ConnectorMachineIdentity(
            clientInstallationID: "70000000-0000-4000-8000-000000000001"
        )
        let message = Data("binding challenge".utf8)
        let signature = try identity.sign(message)
        let publicData = try XCTUnwrap(Data(testBase64URL: identity.connectorPublicKey))
        let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: publicData)

        XCTAssertTrue(publicKey.isValidSignature(signature, for: message))
        XCTAssertEqual(publicData.count, 32)
        XCTAssertEqual(identity.connectorPublicKey.count, 43)
        XCTAssertEqual(identity.connectorPublicKeyFingerprint.count, 64)
        XCTAssertFalse(String(describing: identity).contains("privateKey"))
    }

    func testAccountConfigurationRequiresTLSExceptForLoopbackAndTreatsBlankClientAsMissing() {
        let secure = DesktopAccountConfiguration.load(environment: [
            "HERMES_GO_ACCOUNT_GATEWAY_URL": "https://relay.example",
            "HERMES_GO_GOOGLE_MACOS_CLIENT_ID": "desktop.apps.googleusercontent.com",
        ])
        let loopback = DesktopAccountConfiguration.load(environment: [
            "HERMES_GO_ACCOUNT_GATEWAY_URL": "http://127.0.0.1:8444",
            "HERMES_GO_GOOGLE_MACOS_CLIENT_ID": "   ",
        ])
        let insecure = DesktopAccountConfiguration.load(environment: [
            "HERMES_GO_ACCOUNT_GATEWAY_URL": "http://relay.example",
        ])

        XCTAssertEqual(secure.gatewayURL.absoluteString, "https://relay.example")
        XCTAssertEqual(secure.googleClientID, "desktop.apps.googleusercontent.com")
        XCTAssertEqual(loopback.gatewayURL.absoluteString, "http://127.0.0.1:8444")
        XCTAssertNil(loopback.googleClientID)
        XCTAssertEqual(insecure.gatewayURL.absoluteString, "https://mrlgs.net")
    }

    func testGatewaySessionResponseDecodesWithoutClientOnlyRecoveryFields() throws {
        let data = Data(#"{"account":{"id":"10000000-0000-4000-8000-000000000001","displayName":"Liang","email":"liang@example.invalid","avatarUrl":null},"installation":{"id":"20000000-0000-4000-8000-000000000001","kind":"desktop","platform":"macos","displayName":"Mac mini"},"session":{"accessToken":"hga_fake","accessExpiresAt":"2099-09-02T01:00:00Z","refreshToken":"hgr_fake","refreshExpiresAt":"2099-10-02T00:00:00Z"}}"#.utf8)

        let record = try JSONDecoder().decode(AccountSessionRecord.self, from: data)

        XCTAssertEqual(record.account.displayName, "Liang")
        XCTAssertNil(record.pendingRefreshIdempotencyKey)
        XCTAssertNil(record.pendingOperationIdempotencyKeys)
    }

    func testMachineIdentityRejectsInvalidInstallationIdentifier() {
        XCTAssertThrowsError(try ConnectorMachineIdentity(clientInstallationID: "not-a-uuid")) { error in
            XCTAssertEqual(error as? AccountSecretStoreError, .invalidMachineIdentity)
        }
    }
}

private extension Data {
    init?(testBase64URL value: String) {
        var standard = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        standard.append(String(repeating: "=", count: (4 - standard.count % 4) % 4))
        self.init(base64Encoded: standard)
    }
}

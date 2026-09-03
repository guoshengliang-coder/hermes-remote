import XCTest
@testable import HermesGoDesktopCore

final class LegacyConnectorConfigTests: XCTestCase {
    func testParsesOnlyNonSecretCompatibilityFields() {
        let config = LegacyConnectorConfigParser.parse(
            """
            GATEWAY_URL=wss://mrlgs.net/v1/connect
            DEVICE_ID=studio-mini
            HERMES_BASE_URL=http://127.0.0.1:9119
            CONNECTOR_TOKEN=must-never-leave-parser
            APP_TOKEN=also-secret
            HERMES_BASIC_AUTH_PASSWORD=private
            SESSION_OBSERVER_ENABLED=0
            """
        )

        XCTAssertEqual(config.gatewayURL?.absoluteString, "wss://mrlgs.net/v1/connect")
        XCTAssertEqual(config.deviceID, "studio-mini")
        XCTAssertEqual(config.hermesBaseURL.absoluteString, "http://127.0.0.1:9119")
        XCTAssertFalse(config.observerEnabled)
        XCTAssertEqual(config.relayHealthURL?.absoluteString, "https://mrlgs.net/relay-health")
    }

    func testDefaultsRemainCompatibleWithCurrentConnector() {
        let config = LegacyConnectorConfigParser.parse("")

        XCTAssertNil(config.gatewayURL)
        XCTAssertEqual(config.deviceID, "mac-mini")
        XCTAssertEqual(config.hermesBaseURL.absoluteString, "http://127.0.0.1:9119")
        XCTAssertTrue(config.observerEnabled)
    }
}

import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class ConnectionProfileTests: XCTestCase {
    func testValidProfileNormalizesAndBuildsAndroidV1Payload() throws {
        let profile = try ConnectionProfile.validated(
            name: "  Mac mini  ",
            gatewayAddress: "HTTPS://mrlgs.net/",
            appToken: "  app-secret  "
        )

        XCTAssertEqual(profile.name, "Mac mini")
        XCTAssertEqual(profile.gatewayURL.absoluteString, "https://mrlgs.net")
        XCTAssertEqual(profile.appToken, "app-secret")
        XCTAssertEqual(profile.pairingPayload, #"{"token":"app-secret","url":"https://mrlgs.net","v":1}"#)
        XCTAssertFalse(profile.pairingPayload?.contains("Mac mini") == true)
    }

    func testRejectsRemoteHTTPCredentialsQueriesAndMissingToken() {
        for value in [
            "http://example.com",
            "https://user:password@example.com",
            "https://example.com?token=secret",
            "ftp://example.com",
        ] {
            XCTAssertThrowsError(
                try ConnectionProfile.validated(name: "Mac", gatewayAddress: value, appToken: "token")
            )
        }
        XCTAssertThrowsError(
            try ConnectionProfile.validated(name: "Mac", gatewayAddress: "https://example.com", appToken: "")
        )
    }

    func testAllowsLoopbackPrivateAndTailscaleHTTP() throws {
        for address in [
            "http://127.0.0.1:8444",
            "http://192.168.1.8:8444",
            "http://100.119.73.80:8444",
            "http://[::1]:8444",
        ] {
            XCTAssertNoThrow(
                try ConnectionProfile.validated(name: "Local", gatewayAddress: address, appToken: "token")
            )
        }
    }

    func testRejectsPayloadTooLargeForReliableQRRendering() {
        XCTAssertThrowsError(
            try ConnectionProfile.validated(
                name: "Mac",
                gatewayAddress: "https://relay.example",
                appToken: String(repeating: "x", count: 2_100)
            )
        ) { error in
            XCTAssertEqual(error as? ConnectionProfileValidationError, .pairingPayloadTooLarge)
        }
    }
}

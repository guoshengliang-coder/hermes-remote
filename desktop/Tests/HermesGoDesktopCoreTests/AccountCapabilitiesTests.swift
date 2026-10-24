import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class AccountCapabilitiesTests: XCTestCase {
    func testAccountCapabilitiesDoNotRequireOrUseRetiredTokenFlags() throws {
        var object: [String: Any] = [
            "version": 1,
            "accountAuth": ["enabled": true, "providers": ["email_otp"], "android": true, "macos": true],
            "binding": ["enabled": true, "replacement": true, "maxActiveConnectorsPerAccount": 3],
        ]
        let without = try JSONDecoder().decode(AccountCapabilities.self,
            from: JSONSerialization.data(withJSONObject: object))
        object["legacy"] = ["appTokenAccepted": true, "connectorTokenAccepted": true]
        let with = try JSONDecoder().decode(AccountCapabilities.self,
            from: JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(without, with)
        XCTAssertTrue(without.accountAuth.enabled)
        let encoded = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(with)) as? [String: Any])
        XCTAssertNil(encoded["legacy"])
    }
}

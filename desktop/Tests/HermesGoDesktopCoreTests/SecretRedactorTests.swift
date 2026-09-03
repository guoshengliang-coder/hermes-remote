import XCTest
@testable import HermesGoDesktopCore

final class SecretRedactorTests: XCTestCase {
    func testRedactsHeadersEnvironmentAndURLQueries() {
        let input = """
        Authorization: Bearer abc123
        CONNECTOR_TOKEN=connector-secret
        password=hunter2
        wss://example.test/api/ws?ticket=signed-ticket&mode=live
        """
        let redacted = SecretRedactor.redact(input)

        XCTAssertFalse(redacted.contains("abc123"))
        XCTAssertFalse(redacted.contains("connector-secret"))
        XCTAssertFalse(redacted.contains("hunter2"))
        XCTAssertFalse(redacted.contains("signed-ticket"))
        XCTAssertTrue(redacted.contains("<redacted>"))
    }

    func testRedactsKnownSecretWithoutChangingSafeStatus() {
        let redacted = SecretRedactor.redact(
            "Gateway heartbeat ok 42 ms; opaque-value",
            knownSecrets: ["opaque-value"]
        )

        XCTAssertEqual(redacted, "Gateway heartbeat ok 42 ms; <redacted>")
    }
}

import XCTest
@testable import HermesGoDesktopCore

final class AccountModelsTests: XCTestCase {
    func testBindingStateMapsEveryPublishedWireValue() {
        XCTAssertEqual(AccountBindingState(wireValue: "no_binding"), .noBinding)
        XCTAssertEqual(AccountBindingState(wireValue: "binding_pending"), .bindingPending)
        XCTAssertEqual(AccountBindingState(wireValue: "bound"), .bound)
        XCTAssertEqual(AccountBindingState(wireValue: "replacement_pending"), .replacementPending)
        XCTAssertEqual(AccountBindingState(wireValue: "revoked"), .revoked)
    }

    func testBindingStatePreservesUnknownWireValueForSafeFallback() {
        XCTAssertEqual(
            AccountBindingState(wireValue: "future_server_state"),
            .unknown("future_server_state")
        )
    }
}

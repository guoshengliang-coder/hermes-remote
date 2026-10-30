import XCTest
@testable import HermesGoDesktopCore

final class DesktopConnectorRecoveryTests: XCTestCase {
    func testLoadedStoppedJobIsNotRunningEvenWithActiveResourceCoalitions() {
        XCTAssertEqual(DesktopConnectorProcessState.parse("job = {\n\tstate = not running\n\tresource coalition = {\n\t\tstate = active\n\t}\n}"), .stopped)
        XCTAssertEqual(DesktopConnectorProcessState.parse("job = {\n\tresource coalition = {\n\t\tstate = not running\n\t}\n}"), .unknown)
        XCTAssertEqual(DesktopConnectorProcessState.parse("job = {\n\tstate = running\n\tpid = 42\n}", processExists: { $0 == 42 }), .running)
        XCTAssertEqual(DesktopConnectorProcessState.parse("job = {\n\tstate = running\n\tpid = 42\n}", processExists: { _ in false }), .unknown)
    }

    func testAutomaticAttemptsRequireStoppedProcessAndHaveCooldownAndLimit() {
        var policy = DesktopConnectorRecoveryPolicy()
        for state in [DesktopConnectorProcessState.running, .notLoaded, .unknown] {
            XCTAssertFalse(policy.begin(process: state, eligible: true, now: 0))
        }
        XCTAssertFalse(policy.begin(process: .stopped, eligible: false, now: 0))
        XCTAssertTrue(policy.begin(process: .stopped, eligible: true, now: 0))
        XCTAssertFalse(policy.begin(process: .stopped, eligible: true, now: 59))
        XCTAssertTrue(policy.begin(process: .stopped, eligible: true, now: 60))
        XCTAssertTrue(policy.begin(process: .stopped, eligible: true, now: 120))
        XCTAssertFalse(policy.begin(process: .stopped, eligible: true, now: 180))
        policy.succeeded()
        XCTAssertTrue(policy.begin(process: .stopped, eligible: true, now: 180))
        policy.blocked()
        XCTAssertFalse(policy.begin(process: .stopped, eligible: true, now: 240))
    }
}

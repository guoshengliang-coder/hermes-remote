import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DeviceSelectionStoreTests: XCTestCase {
    func testSelectionIsScopedByAccountAndCanBeCleared() throws {
        let suite = "HermesGoDesktopTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsDeviceSelectionStore(defaults: defaults, keyPrefix: "selection")
        let accountA = "10000000-0000-4000-8000-000000000001"
        let accountB = "10000000-0000-4000-8000-000000000002"

        store.save("hermes-office", accountID: accountA)

        XCTAssertEqual(store.load(accountID: accountA), "hermes-office")
        XCTAssertNil(store.load(accountID: accountB))
        store.save(nil, accountID: accountA)
        XCTAssertNil(store.load(accountID: accountA))
    }

    func testInvalidIdentifiersAreNeverPersisted() throws {
        let suite = "HermesGoDesktopTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = UserDefaultsDeviceSelectionStore(defaults: defaults, keyPrefix: "selection")
        let account = "10000000-0000-4000-8000-000000000001"

        store.save("../foreign?token=secret", accountID: account)

        XCTAssertNil(store.load(accountID: account))
        XCTAssertFalse(isValidDeviceID("../foreign"))
        XCTAssertTrue(isValidDeviceID("hermes-safe_1.test~device"))
    }
}

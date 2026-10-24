import Foundation
import XCTest
@testable import HermesGoDesktopCore

final class DesktopLocalHermesUsabilityTests: XCTestCase {
    private var installation: DesktopLocalHermesInstallation {
        DesktopLocalHermesInstallation(executable: URL(fileURLWithPath: "/hermes/bin/hermes"),
            checkoutRoot: URL(fileURLWithPath: "/hermes"), hermesHome: URL(fileURLWithPath: "/data"),
            commit: String(repeating: "a", count: 40), version: "0.21.3", identityChangedAt: Date())
    }
    func testInstalledFilesDoNotEstablishUsability() {
        let result = DesktopLocalHermesPresentation(detection: .usable(installation), health: nil, hasManagedInstallation: false)
        XCTAssertFalse(result.canOpen)
        XCTAssertEqual(result.title, "已安装，待验证")
        XCTAssertEqual(result.version, "0.21.3")
    }
    func testOnlyHealthyAuthenticatedProbeEnablesOpen() {
        for level in HealthLevel.allCases {
            let result = DesktopLocalHermesPresentation(detection: .usable(installation),
                health: ComponentHealth(component: .hermes, level: level, detail: "test", checkedAt: Date()), hasManagedInstallation: true)
            XCTAssertEqual(result.canOpen, level == .healthy)
        }
    }
    func testExpiredLocalHealthCannotEnableOpen() {
        let now = Date()
        for age in [181.0, -6.0] {
            let result = DesktopLocalHermesPresentation(detection: .usable(installation),
                health: ComponentHealth(component: .hermes, level: .healthy, detail: "old",
                                        checkedAt: now.addingTimeInterval(-age)),
                hasManagedInstallation: true, now: now)
            XCTAssertFalse(result.canOpen)
            XCTAssertEqual(result.level, .checking)
        }
    }

    func testUnsupportedAndAbsentRemainDistinct() {
        let unsupported = DesktopLocalHermesPresentation(detection: .unsupported(.multipleProfiles, detail: "private details"), health: nil, hasManagedInstallation: false)
        let absent = DesktopLocalHermesPresentation(detection: .absent(hermesDataPresent: true), health: nil, hasManagedInstallation: false)
        XCTAssertEqual(unsupported.title, "安装需要处理")
        XCTAssertEqual(unsupported.level, .failed)
        XCTAssertFalse(unsupported.detail.contains("private details"))
        XCTAssertEqual(absent.title, "尚未安装")
        XCTAssertEqual(absent.level, .unavailable)
        XCTAssertFalse(absent.canOpen)
    }
    func testBundledRuntimeCanBeUsableWithoutStandardCheckout() {
        let result = DesktopLocalHermesPresentation(detection: .absent(hermesDataPresent: false),
            health: ComponentHealth(component: .hermes, level: .healthy, detail: "authenticated", checkedAt: Date()), hasManagedInstallation: true)
        XCTAssertTrue(result.canOpen)
        XCTAssertEqual(result.version, "由 Desktop 管理")
    }
}

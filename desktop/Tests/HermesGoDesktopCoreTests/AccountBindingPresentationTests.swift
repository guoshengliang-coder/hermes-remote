import XCTest
@testable import HermesGoDesktopCore

final class AccountBindingPresentationTests: XCTestCase {
    func testFirstBindingRequiresProofAndHealthBeforeConfirmation() {
        let waiting = AccountBindingSnapshot(
            state: "binding_pending",
            id: candidate.id,
            generation: candidate.generation,
            keyProved: true,
            healthVerified: false
        )
        let ready = AccountBindingSnapshot(
            state: "binding_pending",
            id: candidate.id,
            generation: candidate.generation,
            keyProved: true,
            healthVerified: true
        )

        XCTAssertEqual(waiting.presentation.actions.map(\.action), [.refresh])
        XCTAssertEqual(
            ready.presentation.actions.map(\.action),
            [.confirmFirst(id: candidate.id, generation: candidate.generation)]
        )
        XCTAssertTrue(ready.presentation.safetyNote.contains("旧 Connector"))
    }

    func testReplacementKeepsOriginalUntilVerifiedThenTargetsRequest() {
        let waitingCandidate = candidate
        let readyCandidate = AccountBindingCandidate(
            id: candidate.id,
            generation: candidate.generation,
            deviceId: candidate.deviceId,
            displayName: candidate.displayName,
            publicKeyFingerprint: candidate.publicKeyFingerprint,
            state: candidate.state,
            expiresAt: candidate.expiresAt,
            keyProved: true,
            healthVerified: true
        )
        let waiting = AccountBindingSnapshot(
            state: "replacement_pending",
            id: requestID,
            previousBinding: activeBinding,
            candidate: waitingCandidate
        )
        let ready = AccountBindingSnapshot(
            state: "replacement_pending",
            id: requestID,
            previousBinding: activeBinding,
            candidate: readyCandidate
        )

        XCTAssertEqual(waiting.presentation.actions.map(\.action), [.refresh])
        XCTAssertEqual(
            ready.presentation.actions.map(\.action),
            [.confirmReplacement(requestID: requestID)]
        )
        XCTAssertTrue(ready.presentation.safetyNote.contains("原来的 Desktop"))
    }

    func testBoundStateSeparatesReplacementAndDestructiveUnbind() {
        let snapshot = AccountBindingSnapshot(state: "bound", binding: activeBinding)

        XCTAssertEqual(
            snapshot.presentation.actions.map(\.action),
            [.createReplacement, .unbind]
        )
        XCTAssertEqual(
            snapshot.presentation.actions.map(\.role),
            [.normal, .destructive]
        )
        XCTAssertTrue(snapshot.presentation.actions.allSatisfy { !$0.accessibilityHint.isEmpty })
    }

    func testUnknownStateOffersOnlySafeRefresh() {
        let snapshot = AccountBindingSnapshot(state: "future-state")

        XCTAssertEqual(snapshot.presentation.actions.map(\.action), [.refresh])
        XCTAssertTrue(snapshot.presentation.detail.contains("尚不认识"))
    }

    private let requestID = "90000000-0000-4000-8000-000000000001"

    private var candidate: AccountBindingCandidate {
        AccountBindingCandidate(
            id: "40000000-0000-4000-8000-000000000002",
            generation: 2,
            deviceId: "hermes-40000000-0000-4000-8000-000000000002",
            displayName: "Replacement Mac",
            publicKeyFingerprint: String(repeating: "b", count: 64),
            state: "binding_pending",
            expiresAt: "2026-09-03T01:10:00.000Z",
            keyProved: false,
            healthVerified: false
        )
    }

    private var activeBinding: ActiveAccountBinding {
        ActiveAccountBinding(
            id: "40000000-0000-4000-8000-000000000001",
            generation: 1,
            deviceId: "hermes-40000000-0000-4000-8000-000000000001",
            desktopDisplayName: "Original Mac",
            publicKeyFingerprint: String(repeating: "a", count: 64),
            connector: .init(online: true, lastSeenAt: nil),
            hermes: .init(reachable: true, version: "0.0.0-test"),
            gateway: .init(latencyMs: 12),
            endToEnd: .init(healthy: true, checkedAt: nil)
        )
    }
}

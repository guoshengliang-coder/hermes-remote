import CoreImage
import XCTest
@testable import HermesGoDesktopCore

final class PairingQRCodeGeneratorTests: XCTestCase {
    func testGeneratedImageDecodesToExactAndroidPayload() throws {
        let profile = try ConnectionProfile.validated(
            name: "Mac mini",
            gatewayAddress: "https://mrlgs.net",
            appToken: "test-app-token"
        )
        let payload = try XCTUnwrap(profile.pairingPayloadData)
        let image = try XCTUnwrap(PairingQRCodeGenerator.image(for: payload))
        let detector = try XCTUnwrap(
            CIDetector(
                ofType: CIDetectorTypeQRCode,
                context: nil,
                options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
            )
        )
        let result = try XCTUnwrap(detector.features(in: image).first as? CIQRCodeFeature)
        XCTAssertEqual(result.messageString, profile.pairingPayload)
    }
}

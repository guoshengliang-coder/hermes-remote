import CoreImage
import XCTest
@testable import HermesGoDesktopCore

final class PairingQRCodeGeneratorTests: XCTestCase {
    func testGeneratedImageDecodesToExactOnboardingLink() throws {
        let link = "https://go.mrlgs.net/download"
        let payload = Data(link.utf8)
        let image = try XCTUnwrap(PairingQRCodeGenerator.image(for: payload))
        let detector = try XCTUnwrap(
            CIDetector(
                ofType: CIDetectorTypeQRCode,
                context: nil,
                options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]
            )
        )
        let result = try XCTUnwrap(detector.features(in: image).first as? CIQRCodeFeature)
        XCTAssertEqual(result.messageString, link)
    }
}

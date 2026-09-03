import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

public enum PairingQRCodeGenerator {
    public static func image(for payload: Data, targetSize: CGFloat = 512) -> CIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = payload
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }

        let scale = max(1, floor(targetSize / output.extent.width))
        return output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    }
}

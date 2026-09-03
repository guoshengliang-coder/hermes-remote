import AppKit
import HermesGoDesktopCore
import SwiftUI

struct PairingQRCodeView: View {
    let payload: Data

    var body: some View {
        Group {
            if let image = renderedImage {
                Image(nsImage: image)
                    .resizable()
                    .interpolation(.none)
                    .scaledToFit()
                    .accessibilityLabel("包含 Relay 地址和 App Token 的手机配对二维码")
            } else {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 48, weight: .light))
                    .foregroundStyle(.orange)
                    .accessibilityLabel("二维码生成失败")
            }
        }
    }

    private var renderedImage: NSImage? {
        guard let ciImage = PairingQRCodeGenerator.image(for: payload) else { return nil }
        let representation = NSCIImageRep(ciImage: ciImage)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}

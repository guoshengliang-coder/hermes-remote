import SwiftUI

/// Small monochrome interpretation of upstream Hermes' portrait: bob haircut, headband and
/// left-facing profile. Reference: NousResearch/hermes-agent apps/desktop/assets/icon.png at
/// 633dda6d7fdcac7e8340471c9f5ed08cdccbd70c. Native vector geometry, not the Hermes GO app icon.
struct HermesBrandIcon: View {
    var body: some View {
        GeometryReader { geometry in
            let scale = min(geometry.size.width, geometry.size.height) / 24
            Path { p in
                p.move(to: CGPoint(x: 5, y: 11))
                p.addCurve(to: CGPoint(x: 3, y: 8), control1: CGPoint(x: 2, y: 11), control2: CGPoint(x: 2, y: 10))
                p.addCurve(to: CGPoint(x: 15, y: 3), control1: CGPoint(x: 4, y: 1), control2: CGPoint(x: 11, y: 1))
                p.addCurve(to: CGPoint(x: 21, y: 17), control1: CGPoint(x: 19, y: 4), control2: CGPoint(x: 19, y: 12))
                p.addCurve(to: CGPoint(x: 17, y: 22), control1: CGPoint(x: 24, y: 21), control2: CGPoint(x: 19, y: 23))
                p.addLine(to: CGPoint(x: 14, y: 18))
                p.move(to: CGPoint(x: 5, y: 11))
                p.addLine(to: CGPoint(x: 3, y: 14))
                p.addLine(to: CGPoint(x: 5, y: 15))
                p.addCurve(to: CGPoint(x: 10, y: 19), control1: CGPoint(x: 4, y: 19), control2: CGPoint(x: 7, y: 19))
                p.addLine(to: CGPoint(x: 11, y: 22))
                p.move(to: CGPoint(x: 4, y: 9))
                p.addLine(to: CGPoint(x: 7, y: 9))
                p.addLine(to: CGPoint(x: 8, y: 7))
                p.addLine(to: CGPoint(x: 9, y: 10))
                p.addLine(to: CGPoint(x: 13, y: 10))
                p.addCurve(to: CGPoint(x: 12, y: 19), control1: CGPoint(x: 14, y: 13), control2: CGPoint(x: 13, y: 17))
                p.move(to: CGPoint(x: 12, y: 2))
                p.addCurve(to: CGPoint(x: 17, y: 11), control1: CGPoint(x: 16, y: 4), control2: CGPoint(x: 17, y: 7))
                p.move(to: CGPoint(x: 7, y: 12))
                p.addLine(to: CGPoint(x: 9, y: 12))
                p.move(to: CGPoint(x: 17, y: 14))
                p.addCurve(to: CGPoint(x: 15, y: 17), control1: CGPoint(x: 19, y: 14), control2: CGPoint(x: 18, y: 17))
            }
            .transform(CGAffineTransform(scaleX: scale, y: scale))
            .stroke(style: StrokeStyle(lineWidth: 1.55 * scale, lineCap: .round, lineJoin: .round))
        }.accessibilityHidden(true)
    }
}

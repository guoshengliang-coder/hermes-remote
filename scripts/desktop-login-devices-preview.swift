// Local, credential-free rendering of the production login-device card. No account/network operations.
import AppKit
import SwiftUI
@testable import HermesGoDesktopCore

final class DesktopViewModel: ObservableObject {
    let currentModelConfiguration = DesktopModelConfiguration.unknown
}

@main struct LoginDevicesPreview {
    @MainActor static func main() throws {
        let output = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "/private/tmp/webapp-login-devices-ui")
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        let account = HermesAccount(id: "preview-account", displayName: "Preview", email: "preview@example.invalid", avatarUrl: nil)
        let session = AccountSessionRecord(account: account,
            installation: .init(id: "preview-desktop", kind: "desktop", platform: "macos", displayName: "Preview Mac"),
            session: .init(accessToken: "fixture-unused", accessExpiresAt: "2099-01-01T00:00:00Z",
                           refreshToken: "fixture-unused", refreshExpiresAt: "2099-02-01T00:00:00Z"))
        let binding = AccountBindingSnapshot(state: "no_binding", id: nil, generation: nil, deviceId: nil,
            displayName: nil, publicKeyFingerprint: nil, expiresAt: nil, keyProved: nil,
            healthVerified: nil, binding: nil, previousBinding: nil)
        let entries = [
            ManagedAccountInstallation(id: "preview-phone", kind: "phone", platform: "android",
                displayName: "Pixel 手机", lastSeenAt: "2026-10-27T08:15:00Z", status: "active", current: false),
            ManagedAccountInstallation(id: "preview-browser", kind: "browser", platform: "web",
                displayName: "iPhone · Safari WebApp", lastSeenAt: "2026-10-27T08:20:00Z", status: "active", current: false),
            ManagedAccountInstallation(id: "preview-browser-2", kind: "browser", platform: "web",
                displayName: "另一浏览器 · 很长的浏览器登录名称用于检查窄窗口布局", lastSeenAt: "invalid", status: "active", current: false),
            ManagedAccountInstallation(id: "preview-desktop", kind: "desktop", platform: "macos",
                displayName: "不在手机与浏览器列表中重复显示的 Mac", lastSeenAt: "2026-10-27T08:20:00Z", status: "active", current: true),
        ]
        _ = NSApplication.shared
        for state in ["mixed", "legacy", "empty", "busy"] {
            let dashboard = AccountDashboard(session: session, binding: binding,
                installations: state == "empty" ? [] : entries, browserInstallationRevocation: state != "legacy")
            for (layout, width, dark) in [("light", 720.0, false), ("dark", 720.0, true), ("compact", 420.0, false)] {
                let view = AccountLoginDevicesCard(dashboard: dashboard, isBusy: state == "busy", revoke: { _ in })
                    .padding(20).frame(width: width).background(dark ? Color.black : Color.white)
                    .environment(\.locale, Locale(identifier: "zh_CN"))
                    .preferredColorScheme(dark ? .dark : .light)
                let host = NSHostingView(rootView: view)
                let size = host.fittingSize
                let window = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: [.borderless], backing: .buffered, defer: false)
                window.contentView = host
                host.frame = NSRect(origin: .zero, size: size)
                window.orderFrontRegardless()
                RunLoop.main.run(until: Date().addingTimeInterval(0.15))
                host.layoutSubtreeIfNeeded()
                let bitmap = host.bitmapImageRepForCachingDisplay(in: host.bounds)!
                host.cacheDisplay(in: host.bounds, to: bitmap)
                try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent("\(state)-\(layout).png"))
                window.orderOut(nil)
            }
        }
        print("Rendered 12 login-device previews at \(output.path)")
    }
}

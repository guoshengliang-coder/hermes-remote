import AppKit
import SwiftUI

import HermesGoDesktopCore

/// Quitting Desktop mid-install must not leave an installer stage running on its own: a later
/// resume would then run beside it.
final class HermesGoDesktopAppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillTerminate(_ notification: Notification) {
        DesktopPosixProcessRunner.terminateAllProcessGroups()
    }
}

@main
struct HermesGoDesktopApp: App {
    @NSApplicationDelegateAdaptor(HermesGoDesktopAppDelegate.self) private var appDelegate
    @StateObject private var model = DesktopViewModel()

    var body: some Scene {
        WindowGroup("Hermes Go Desktop", id: "main") {
            RootView()
                .background(HiddenWindowTitle())
                .environmentObject(model)
                .frame(minWidth: 980, minHeight: 650)
                .task { model.startMonitoring() }
        }
        .defaultSize(width: 1180, height: 760)

        MenuBarExtra {
            MenuBarContentView()
                .environmentObject(model)
        } label: {
            // The full-color app artwork is for app/window identity. A status item needs a
            // small template glyph so macOS can keep it legible alongside other menu extras.
            Image(systemName: "h.circle")
                .symbolRenderingMode(.monochrome)
                .font(.system(size: 15, weight: .medium))
                .accessibilityLabel("Hermes Go Desktop")
        }
        .menuBarExtraStyle(.window)
    }
}

/// Keep the native title bar, traffic lights and semantic window title, hiding only duplicate text.
struct HiddenWindowTitle: NSViewRepresentable {
    final class TitleView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.titleVisibility = .hidden
        }
    }
    func makeNSView(context: Context) -> TitleView { TitleView() }
    func updateNSView(_ view: TitleView, context: Context) { view.window?.titleVisibility = .hidden }
}

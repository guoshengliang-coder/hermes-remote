import AppKit
import SwiftUI

@main
struct HermesGoDesktopApp: App {
    @StateObject private var model = DesktopViewModel()

    var body: some Scene {
        WindowGroup("Hermes Go Desktop", id: "main") {
            RootView()
                .environmentObject(model)
                .frame(minWidth: 980, minHeight: 650)
                .task { model.startMonitoring() }
        }
        .defaultSize(width: 1180, height: 760)

        MenuBarExtra {
            MenuBarContentView()
                .environmentObject(model)
        } label: {
            Image(nsImage: NSApplication.shared.applicationIconImage)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 18, height: 18)
        }
        .menuBarExtraStyle(.window)
    }
}

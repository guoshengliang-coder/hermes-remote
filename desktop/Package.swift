// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "HermesGoDesktop",
    platforms: [
        .macOS(.v14),
    ],
    products: [
        .library(name: "HermesGoDesktopCore", targets: ["HermesGoDesktopCore"]),
        .executable(name: "HermesGoDesktop", targets: ["HermesGoDesktop"]),
    ],
    targets: [
        .target(name: "HermesGoDesktopCore"),
        .executableTarget(
            name: "HermesGoDesktop",
            dependencies: ["HermesGoDesktopCore"]
        ),
        .testTarget(
            name: "HermesGoDesktopCoreTests",
            dependencies: ["HermesGoDesktopCore"]
        ),
    ]
)

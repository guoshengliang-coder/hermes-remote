import Foundation
import HermesGoDesktopCore

let args = Array(CommandLine.arguments.dropFirst())
do {
    guard (args.count == 4 || (args.count == 5 && args[4] == "--legacy")), let bundle = Bundle(path: args[0]), let build = Int(args[2]) else {
        throw DesktopAppUpdateInstallError.invalidRequest
    }
    try DesktopDistributionConfiguration.validate(bundle: bundle, version: args[1], build: build, source: args[3], allowLegacy: args.count == 5)
    let identity = try DesktopManagedComponentContentHasher().identify(directory: bundle.bundleURL)
    print("DESKTOP_APP_BUNDLE_SHA256=\(identity.sha256)")
    print("DESKTOP_DISTRIBUTION_CONFIGURATION_OK")
} catch {
    // No plist values, paths or raw exceptions are exposed by the release boundary.
    fputs("HR-DESKUPDATE-004 更新包的发行配置或身份不匹配。 / Release configuration or identity mismatch.\n", stderr)
    exit(65)
}

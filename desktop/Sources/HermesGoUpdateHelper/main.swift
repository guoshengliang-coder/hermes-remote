import Foundation
import HermesGoDesktopCore

let args = Array(CommandLine.arguments.dropFirst())
do {
    guard args.count == 3, let id = UUID(uuidString: args[1]), ["apply", "restore"].contains(args[2]) else {
        throw DesktopAppUpdateInstallError.invalidRequest
    }
    try DesktopAppUpdateTransactions.runHelper(root: URL(fileURLWithPath: args[0]), id: id, restoring: args[2] == "restore")
} catch {
    fputs("HR-DESKUPDATE-006 更新尚未确认，请查看更新结果。 / Update unconfirmed; review update recovery.\n", stderr)
    exit(1)
}

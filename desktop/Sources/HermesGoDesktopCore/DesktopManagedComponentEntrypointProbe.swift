import Foundation

/// Completes the read-only managed-store identity check without launching a component. The store
/// inspector has already rehashed the complete component tree; this probe additionally requires the
/// signed entrypoint to remain a safe executable inside that exact tree. Runtime-level readiness is
/// checked later by installation/activation, where dependency roots and service state are available.
public struct DesktopManagedComponentEntrypointProbe: Sendable {
    private let currentUserID: UInt32

    public init(currentUserID: UInt32) {
        self.currentUserID = currentUserID
    }

    public func callAsFunction(
        _ kind: DesktopManagedComponentKind,
        root: URL,
        entrypoint: URL
    ) throws -> Bool {
        let normalizedRoot = root.standardizedFileURL
        let normalizedEntrypoint = entrypoint.standardizedFileURL
        guard normalizedRoot.isFileURL,
              normalizedEntrypoint.isFileURL,
              normalizedRoot.path.hasPrefix("/"),
              normalizedRoot.path != "/",
              normalizedEntrypoint.path.hasPrefix(normalizedRoot.path + "/"),
              normalizedEntrypoint.resolvingSymlinksInPath() == normalizedEntrypoint,
              let values = try? normalizedEntrypoint.resourceValues(forKeys: [
                  .isRegularFileKey, .isSymbolicLinkKey, .isExecutableKey,
              ]),
              values.isRegularFile == true,
              values.isSymbolicLink != true,
              values.isExecutable == true,
              let attributes = try? FileManager.default.attributesOfItem(
                  atPath: normalizedEntrypoint.path
              ),
              (attributes[.ownerAccountID] as? NSNumber)?.uint32Value == currentUserID,
              let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue,
              permissions & 0o022 == 0
        else { return false }
        return true
    }
}

import CryptoKit
import Darwin
import Foundation

/// Private recovery material, never a compatibility approval or authority to stop a writer.
/// The executor must keep upstream and business writers quiescent throughout capture/recovery.
public struct DesktopHermesRecoveryMaterial: Equatable, Sendable {
    public struct Root: Equatable, Sendable {
        public let original: URL
        public let staged: URL
        public let sha256: String
    }
    public let runID: String
    public let roots: [Root]
}

/// Captures the home (including its checkout/venv) and explicitly collected external runtimes.
/// Materialization reconstructs verified trees in a new private workspace; it NEVER replaces
/// the installation or deletes candidate data. The eventual executor owns the data-safe commit.
public struct DesktopHermesRecoveryPointStore: Sendable {
    public let root: URL
    public init(root: URL) { self.root = root.standardizedFileURL }

    struct Tree: Codable, Equatable, Sendable {
        let sha256: String
        let entries: Int
        let bytes: Int64
    }
    struct SavedRoot: Codable, Equatable {
        let original: String
        let tree: Tree
    }
    struct Manifest: Codable, Equatable {
        let schemaVersion: Int
        let runID: String
        let sourceCommit: String
        let sourceIdentity: String
        let target: DesktopHermesUpgradeTarget
        let checkout: String
        let hermesHome: String
        let roots: [SavedRoot]
    }

    // Called under the upgrade operation's mutex and maintenance lease.
    func capture(journal: DesktopHermesUpgradeJournal, externalRuntimeRoots: [URL]) throws -> DesktopHermesUpgradeBackup {
        do {
            guard journal.phase == .backingUp, journal.backup == nil else { throw refusal }
            try journal.validate()
            let inputs = [URL(fileURLWithPath: journal.hermesHome)] + externalRuntimeRoots
            try validateInputs(inputs)
            // The initial legacy-venv path has an external interpreter in common installations.
            // Refuse an omitted runtime rather than returning a home-only recovery receipt.
            let interpreter = URL(fileURLWithPath: journal.checkout).appendingPathComponent("venv/bin/python").resolvingSymlinksInPath()
            guard inputs.contains(where: { interpreter.path.hasPrefix($0.path + "/") }),
                  FileManager.default.isExecutableFile(atPath: interpreter.path) else { throw refusal }
            _ = try owned(interpreter, type: S_IFREG)
            try privateDirectory(root, create: true)
            let destination = point(journal.runID)
            if exists(destination) {
                let receipt = try existingReceipt(journal: journal)
                let manifest = try verified(journal: journal, receipt: receipt)
                guard manifest.roots.map(\.original) == inputs.map(\.path) else { throw refusal }
                for (input, saved) in zip(inputs, manifest.roots) { guard try identify(input) == saved.tree else { throw refusal } }
                return receipt
            }
            let temporary = root.appendingPathComponent(".preparing-\(UUID().uuidString)")
            try privateDirectory(temporary, create: true)
            // Incomplete work remains private and is never accepted as a committed point.
            var saved: [SavedRoot] = []
            for (index, input) in inputs.enumerated() {
                let before = try identify(input)
                try requireCoveredLinks(input, coveredRoots: inputs)
                let copy = temporary.appendingPathComponent("tree-\(index)")
                try FileManager.default.copyItem(at: input, to: copy)
                guard try identify(copy, synchronize: true) == before, try identify(input) == before else { throw refusal }
                saved.append(.init(original: input.path, tree: before))
            }
            // Recheck all roots together: a dependency changed during a later root's copy is not
            // a consistent point. This detects change, but does not substitute for quiescence.
            for (input, saved) in zip(inputs, saved) { guard try identify(input) == saved.tree else { throw refusal } }
            let manifest = Manifest(schemaVersion: 1, runID: journal.runID, sourceCommit: journal.sourceCommit,
                sourceIdentity: journal.sourceIdentity, target: journal.target, checkout: journal.checkout,
                hermesHome: journal.hermesHome, roots: saved)
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(manifest)
            guard data.count <= 65_536 else { throw refusal }
            let file = temporary.appendingPathComponent("manifest.json")
            let fd = Darwin.open(file.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard fd >= 0 else { throw refusal }
            do {
                let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
                try handle.write(contentsOf: data)
                guard fsync(fd) == 0 else { throw refusal }
                Darwin.close(fd)
            } catch { Darwin.close(fd); throw error }
            try syncDirectory(temporary)
            try privateDirectory(root)
            guard renamex_np(temporary.path, destination.path, UInt32(RENAME_EXCL)) == 0 else { throw refusal }
            try syncDirectory(root)
            let receipt = DesktopHermesUpgradeBackup(runID: journal.runID, manifestSHA256: hash(data))
            _ = try verified(journal: journal, receipt: receipt)
            return receipt
        } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    func materialize(journal: DesktopHermesUpgradeJournal) throws -> DesktopHermesRecoveryMaterial {
        do {
            guard journal.phase == .recovering, let receipt = journal.backup else { throw refusal }
            let manifest = try verified(journal: journal, receipt: receipt)
            let destination = root.appendingPathComponent(".materialized-\(UUID().uuidString)")
            try privateDirectory(destination, create: true)
            var result: [DesktopHermesRecoveryMaterial.Root] = []
            for (index, saved) in manifest.roots.enumerated() {
                let source = point(journal.runID).appendingPathComponent("tree-\(index)")
                let staged = destination.appendingPathComponent("tree-\(index)")
                try FileManager.default.copyItem(at: source, to: staged)
                guard try identify(staged, synchronize: true) == saved.tree else { throw refusal }
                result.append(.init(original: URL(fileURLWithPath: saved.original), staged: staged, sha256: saved.tree.sha256))
            }
            try syncDirectory(destination); try syncDirectory(root)
            _ = try verified(journal: journal, receipt: receipt)
            return .init(runID: journal.runID, roots: result)
        } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }

    private func verified(journal: DesktopHermesUpgradeJournal, receipt: DesktopHermesUpgradeBackup) throws -> Manifest {
        try journal.validate(); try privateDirectory(root)
        guard receipt.runID == journal.runID else { throw refusal }
        let directory = point(journal.runID)
        try privateDirectory(directory)
        let file = directory.appendingPathComponent("manifest.json")
        let fd = Darwin.open(file.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        guard fd >= 0 else { throw refusal }; defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFREG,
              info.st_mode & 0o077 == 0, info.st_nlink == 1, info.st_size > 0, info.st_size <= 65_536,
              let data = try FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: 65_537),
              data.count == info.st_size, hash(data) == receipt.manifestSHA256,
              DesktopHermesUpgradeJSON.isUnambiguous(data),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == ["schemaVersion", "runID", "sourceCommit", "sourceIdentity", "target", "checkout", "hermesHome", "roots"],
              let roots = object["roots"] as? [[String: Any]], roots.allSatisfy({
                  Set($0.keys) == ["original", "tree"] && ($0["tree"] as? [String: Any]).map {
                      Set($0.keys) == ["sha256", "entries", "bytes"]
                  } == true
              }),
              let target = object["target"] as? [String: Any], Set(target.keys) == ["version", "commit", "channel"]
        else { throw refusal }
        let manifest = try JSONDecoder().decode(Manifest.self, from: data)
        guard manifest.schemaVersion == 1, manifest.runID == journal.runID,
              manifest.sourceCommit == journal.sourceCommit, manifest.sourceIdentity == journal.sourceIdentity,
              manifest.target == journal.target, manifest.checkout == journal.checkout,
              manifest.hermesHome == journal.hermesHome, manifest.roots.first?.original == journal.hermesHome
        else { throw refusal }
        // Original paths may no longer exist during recovery. Validate their shape/overlap only;
        // never follow or read the live candidate while validating preserved recovery material.
        try validateInputs(manifest.roots.map { URL(fileURLWithPath: $0.original) }, requireExisting: false)
        let expected = Set(["manifest.json"] + manifest.roots.indices.map { "tree-\($0)" })
        guard Set(try FileManager.default.contentsOfDirectory(atPath: directory.path)) == expected else { throw refusal }
        for (index, saved) in manifest.roots.enumerated() {
            guard try identify(directory.appendingPathComponent("tree-\(index)")) == saved.tree else { throw refusal }
        }
        var after = stat(), path = stat()
        guard fstat(fd, &after) == 0, lstat(file.path, &path) == 0,
              same(info, after), same(info, path) else { throw refusal }
        return manifest
    }

    private func validateInputs(_ inputs: [URL], requireExisting: Bool = true) throws {
        guard root.isFileURL, root.path != "/", root.resolvingSymlinksInPath().path == root.path,
              !inputs.isEmpty, inputs.count <= 8 else { throw refusal }
        let paths = inputs.map(\.path)
        guard Set(paths).count == paths.count else { throw refusal }
        for input in inputs {
            guard input.isFileURL, input.path != "/", input.standardizedFileURL.path == input.path,
                  !input.path.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains),
                  !overlap(input.path, root.path), !paths.contains(where: { $0 != input.path && overlap($0, input.path) })
            else { throw refusal }
            if requireExisting {
                guard input.resolvingSymlinksInPath().path == input.path else { throw refusal }
                let info = try owned(input, type: S_IFDIR)
                guard info.st_mode & 0o022 == 0 else { throw refusal }
            }
        }
    }

    /// A home-only copy must not silently leave state/config/runtime behind an external link.
    /// Check original targets before capture; later verification compares the preserved link
    /// bytes and never resolves links through a potentially changed live installation.
    private func requireCoveredLinks(_ directory: URL, coveredRoots: [URL]) throws {
        var count = 0
        func visit(_ url: URL) throws {
            count += 1; guard count <= 250_000 else { throw refusal }
            let info = try owned(url)
            if info.st_mode & S_IFMT == S_IFLNK {
                let resolved = url.resolvingSymlinksInPath().path
                guard coveredRoots.contains(where: { resolved == $0.path || resolved.hasPrefix($0.path + "/") }) else { throw refusal }
            } else if info.st_mode & S_IFMT == S_IFDIR {
                guard url.resolvingSymlinksInPath().path == url.path else { throw refusal }
                for name in try FileManager.default.contentsOfDirectory(atPath: url.path) {
                    try visit(url.appendingPathComponent(name))
                }
            }
        }
        try visit(directory)
    }

    // Stable bytes, paths, POSIX modes and link targets. Links are preserved, never followed.
    // This deliberately does not claim an OS snapshot or certify an arbitrary Python dependency
    // closure. The recipe must supply every external runtime and prove startup after recovery.
    func identify(_ directory: URL, synchronize: Bool = false) throws -> Tree {
        guard directory.resolvingSymlinksInPath().path == directory.path else { throw refusal }
        var digest = SHA256(), count = 0, bytes: Int64 = 0
        func visit(_ url: URL, relative: String, behindPrivateDirectory: Bool) throws {
            let before = try owned(url)
            count += 1; guard count <= 250_000 else { throw refusal }
            let kind = before.st_mode & S_IFMT
            let mode = before.st_mode & 0o7777
            let privateBoundary = behindPrivateDirectory || (kind == S_IFDIR && mode & 0o077 == 0)
            // uv creates venv/.lock with mode 0666. Preserve it under an owner-only home;
            // an exposed writable tree still refuses. Never "repair" source permissions.
            guard kind == S_IFLNK || mode & 0o022 == 0 || privateBoundary else { throw refusal }
            let content: String
            if kind == S_IFDIR { content = "directory" }
            else if kind == S_IFLNK { content = try FileManager.default.destinationOfSymbolicLink(atPath: url.path) }
            else if kind == S_IFREG {
                guard before.st_size >= 0, bytes <= 20 * 1_024 * 1_024 * 1_024 - before.st_size else { throw refusal }
                bytes += before.st_size
                let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
                guard fd >= 0 else { throw refusal }; defer { Darwin.close(fd) }
                var actual = stat(); guard fstat(fd, &actual) == 0, same(before, actual) else { throw refusal }
                var fileHash = SHA256(), read: Int64 = 0
                let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
                while let chunk = try handle.read(upToCount: 1_024 * 1_024), !chunk.isEmpty {
                    read += Int64(chunk.count); guard read <= before.st_size else { throw refusal }; fileHash.update(data: chunk)
                }
                guard read == before.st_size, fstat(fd, &actual) == 0, same(before, actual) else { throw refusal }
                if synchronize { guard fsync(fd) == 0 else { throw refusal } }
                content = fileHash.finalize().map { String(format: "%02x", $0) }.joined()
            } else { throw refusal }
            // JSON length/escaping prevents path/link text from creating ambiguous records.
            let record = try JSONSerialization.data(withJSONObject: [relative, String(kind), String(mode), content])
            digest.update(data: record); digest.update(data: Data([10]))
            if kind == S_IFDIR {
                let names = try FileManager.default.contentsOfDirectory(atPath: url.path).sorted()
                for name in names {
                    guard !name.isEmpty, name != ".", name != "..", !name.contains("/"),
                          !name.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { throw refusal }
                    try visit(url.appendingPathComponent(name), relative: relative.isEmpty ? name : relative + "/" + name,
                              behindPrivateDirectory: privateBoundary)
                }
                if synchronize { try syncDirectory(url) }
            }
            var after = stat(); guard lstat(url.path, &after) == 0, same(before, after) else { throw refusal }
        }
        try visit(directory, relative: "", behindPrivateDirectory: false)
        return .init(sha256: digest.finalize().map { String(format: "%02x", $0) }.joined(), entries: count, bytes: bytes)
    }

    private var refusal: DesktopHermesUpgradeError { .recoveryRequired }
    private func existingReceipt(journal: DesktopHermesUpgradeJournal) throws -> DesktopHermesUpgradeBackup {
        try privateDirectory(point(journal.runID))
        let file = point(journal.runID).appendingPathComponent("manifest.json")
        let fd = Darwin.open(file.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
        guard fd >= 0 else { throw refusal }; defer { Darwin.close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFREG,
              info.st_mode & 0o077 == 0, info.st_nlink == 1, info.st_size > 0, info.st_size <= 65_536,
              let data = try FileHandle(fileDescriptor: fd, closeOnDealloc: false).read(upToCount: 65_537),
              data.count == info.st_size else { throw refusal }
        return .init(runID: journal.runID, manifestSHA256: hash(data))
    }
    private func point(_ runID: String) -> URL { root.appendingPathComponent(runID.lowercased()) }
    private func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private func overlap(_ a: String, _ b: String) -> Bool { a == b || a.hasPrefix(b + "/") || b.hasPrefix(a + "/") }
    private func exists(_ url: URL) -> Bool { var info = stat(); return lstat(url.path, &info) == 0 || errno != ENOENT }
    private func owned(_ url: URL, type: mode_t? = nil) throws -> stat {
        var info = stat()
        guard lstat(url.path, &info) == 0, info.st_uid == getuid(),
              type == nil || info.st_mode & S_IFMT == type,
              info.st_mode & S_IFMT == S_IFLNK || info.st_mode & 0o6000 == 0
        else { throw refusal }
        return info
    }
    private func privateDirectory(_ url: URL, create: Bool = false) throws {
        guard url.isFileURL, url.path != "/", url.resolvingSymlinksInPath().path == url.path else { throw refusal }
        if create {
            let created = mkdir(url.path, 0o700) == 0
            guard created || errno == EEXIST else { throw refusal }
            if created { try syncDirectory(url.deletingLastPathComponent()) }
        }
        let info = try owned(url, type: S_IFDIR)
        guard info.st_mode & 0o077 == 0 else { throw refusal }
    }
    private func syncDirectory(_ url: URL) throws {
        let fd = Darwin.open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { throw refusal }; defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw refusal }
    }
    private func same(_ a: stat, _ b: stat) -> Bool {
        a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_uid == b.st_uid && a.st_mode == b.st_mode
            && a.st_size == b.st_size && a.st_nlink == b.st_nlink
            && a.st_mtimespec.tv_sec == b.st_mtimespec.tv_sec && a.st_mtimespec.tv_nsec == b.st_mtimespec.tv_nsec
            && a.st_ctimespec.tv_sec == b.st_ctimespec.tv_sec && a.st_ctimespec.tv_nsec == b.st_ctimespec.tv_nsec
    }
}

import CryptoKit
import Darwin
import Foundation

/// References private bytes, not proof that a caller inventoried every runtime dependency.
public struct DesktopHermesUpgradeDependencyArchive: Codable, Equatable, Sendable {
    public let runID: String
    public let sourceIdentity: String
    public let manifestSHA256: String
}

/// Explicitly selected external roots only. Never discovers a user's home or follows external links.
/// The caller still owns GO/upstream leases, stopped-writer proof and dependency-coverage evidence.
/// Neither capture nor export executes a tool or writes to a live source/runtime.
public struct DesktopHermesUpgradeDependencyStore: Sendable {
    public struct Root: Sendable {
        public let label: String
        public let directory: URL
        public init(label: String, directory: URL) { self.label = label; self.directory = directory }
    }
    private struct Entry: Codable, Equatable {
        let path: String; let kind: String; let permissions: UInt16
        let size: Int64; let digest: String?; let target: String?
    }
    private struct Tree: Codable, Equatable {
        let label: String; let original: String; let entries: [Entry]
    }
    private struct Manifest: Codable {
        let schema: Int; let runID: String; let sourceIdentity: String
        let hermesHome: String; let osBuild: String; let architecture: String; let trees: [Tree]
    }
    private struct Snapshot: Equatable {
        let entries: [Entry]; let stamps: [Stamp]
    }
    private struct Stamp: Equatable {
        let device: dev_t; let inode: ino_t; let mode: mode_t; let size: off_t
        let modifiedSeconds: Int; let modifiedNanos: Int; let changedSeconds: Int; let changedNanos: Int
        init(_ value: stat) {
            device = value.st_dev; inode = value.st_ino; mode = value.st_mode; size = value.st_size
            modifiedSeconds = value.st_mtimespec.tv_sec; modifiedNanos = value.st_mtimespec.tv_nsec
            changedSeconds = value.st_ctimespec.tv_sec; changedNanos = value.st_ctimespec.tv_nsec
        }
    }
    public let root: URL
    private let maximumEntries: Int
    private let maximumBytes: Int64
    private let beforePublish: (@Sendable (URL) throws -> Void)?
    public init(root: URL) { self.init(root: root, maximumEntries: 65_536, maximumBytes: 2 * 1024 * 1024 * 1024) }
    init(root: URL, maximumEntries: Int, maximumBytes: Int64, beforePublish: (@Sendable (URL) throws -> Void)? = nil) {
        self.root = root; self.maximumEntries = maximumEntries; self.maximumBytes = maximumBytes
        self.beforePublish = beforePublish
    }

    public func create(runID: String, sourceIdentity: String, hermesHome: URL, roots: [Root],
                       verifyStoppedWriters: () throws -> Void) throws -> DesktopHermesUpgradeDependencyArchive {
        try mapped {
            guard UUID(uuidString: runID) != nil, DesktopHermesUpgradeJournal.digest(sourceIdentity),
                  !roots.isEmpty, roots.count <= 16, Set(roots.map(\.label)).count == roots.count,
                  canonical(root), canonical(hermesHome), !overlap(root, hermesHome),
                  roots.allSatisfy({ validLabel($0.label) && canonical($0.directory) && !overlap($0.directory, hermesHome)
                      && !overlap($0.directory, root) && !contains($0.directory, FileManager.default.homeDirectoryForCurrentUser) }),
                  roots.enumerated().allSatisfy({ index, item in roots.dropFirst(index + 1).allSatisfy { !overlap(item.directory, $0.directory) } })
            else { throw DesktopHermesUpgradeError.preflightBlocked }
            try verifyStoppedWriters()
            let sorted = roots.sorted { $0.label < $1.label }
            var snapshots = [Snapshot](), remainingEntries = maximumEntries, remainingBytes = maximumBytes
            for item in sorted {
                let snapshot = try scan(item.directory, privateTree: false, entryBudget: remainingEntries, byteBudget: remainingBytes)
                snapshots.append(snapshot); remainingEntries -= snapshot.entries.count
                remainingBytes -= snapshot.entries.reduce(Int64(0)) { $0 + $1.size }
            }
            let total = try aggregate(snapshots.map(\.entries))
            try ensurePrivate(root)
            let available = try FileManager.default.attributesOfFileSystem(forPath: root.path)[.systemFreeSize] as? NSNumber
            guard let available, available.int64Value > total + max(total / 5, 256 * 1024 * 1024) else {
                throw DesktopHermesUpgradeError.preflightBlocked
            }
            let manifest = Manifest(schema: 1, runID: runID, sourceIdentity: sourceIdentity, hermesHome: hermesHome.path,
                osBuild: try platformValue("kern.osversion"), architecture: try platformValue("hw.machine"),
                trees: zip(sorted, snapshots).map { Tree(label: $0.label, original: $0.directory.path, entries: $1.entries) })
            let data = try encode(manifest)
            let receipt = DesktopHermesUpgradeDependencyArchive(runID: runID, sourceIdentity: sourceIdentity, manifestSHA256: hash(data))
            let final = root.appendingPathComponent(runID)
            if UpgradePrivateFiles.exists(final) {
                _ = try validated(receipt)
                guard try UpgradePrivateFiles.read(final.appendingPathComponent("manifest.json"), limit: 32 * 1024 * 1024) == data else {
                    throw DesktopHermesUpgradeError.recoveryRequired
                }
                try verifyStoppedWriters()
                guard try sorted.map({ try scan($0.directory, privateTree: false) }) == snapshots else { throw DesktopHermesUpgradeError.recoveryRequired }
                return receipt
            }
            let staging = root.appendingPathComponent(".\(UUID().uuidString).pending")
            try ensurePrivate(staging)
            defer { try? FileManager.default.removeItem(at: staging) }
            for (item, snapshot) in zip(sorted, snapshots) {
                try copy(item.directory, into: staging.appendingPathComponent(item.label), expected: snapshot.entries, privateSource: false)
            }
            try verifyStoppedWriters()
            guard try sorted.map({ try scan($0.directory, privateTree: false) }) == snapshots else { throw DesktopHermesUpgradeError.recoveryRequired }
            try UpgradePrivateFiles.write(data, to: staging.appendingPathComponent("manifest.json"))
            try UpgradePrivateFiles.sync(staging)
            try publish(staging, to: final)
            return receipt
        }
    }

    public func verify(_ receipt: DesktopHermesUpgradeDependencyArchive) throws { try mapped { _ = try validated(receipt) } }

    /// New directory only, with label-named trees. Absolute live venv references are not rewritten.
    /// Bytes survive deletion of the original source; runnable ABI/OS/tool coverage is separate proof.
    public func export(_ receipt: DesktopHermesUpgradeDependencyArchive, to destination: URL) throws {
        try mapped {
            let manifest = try validated(receipt)
            guard canonical(destination), !UpgradePrivateFiles.exists(destination), !overlap(destination, root),
                  !overlap(destination, URL(fileURLWithPath: manifest.hermesHome)),
                  !overlap(destination, URL(fileURLWithPath: manifest.hermesHome).resolvingSymlinksInPath()),
                  manifest.trees.allSatisfy({ !overlap(destination, URL(fileURLWithPath: $0.original))
                      && !overlap(destination, URL(fileURLWithPath: $0.original).resolvingSymlinksInPath()) }) else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            // Do not create a caller-selected parent or change its permissions.
            try UpgradePrivateFiles.directory(destination.deletingLastPathComponent())
            let staging = destination.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).pending")
            try ensurePrivate(staging)
            defer { try? FileManager.default.removeItem(at: staging) }
            for tree in manifest.trees {
                try copy(root.appendingPathComponent(receipt.runID + "/" + tree.label),
                    into: staging.appendingPathComponent(tree.label), expected: tree.entries, privateSource: true)
            }
            _ = try validated(receipt)
            try UpgradePrivateFiles.sync(staging)
            try publish(staging, to: destination)
        }
    }

    private func validated(_ receipt: DesktopHermesUpgradeDependencyArchive) throws -> Manifest {
        guard canonical(root), UUID(uuidString: receipt.runID) != nil,
              DesktopHermesUpgradeJournal.digest(receipt.sourceIdentity), DesktopHermesUpgradeJournal.digest(receipt.manifestSHA256) else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        try UpgradePrivateFiles.directory(root)
        let directory = root.appendingPathComponent(receipt.runID)
        try UpgradePrivateFiles.directory(directory)
        let data = try UpgradePrivateFiles.read(directory.appendingPathComponent("manifest.json"), limit: 32 * 1024 * 1024)
        guard hash(data) == receipt.manifestSHA256, let manifest = try? JSONDecoder().decode(Manifest.self, from: data),
              try encode(manifest) == data, manifest.schema == 1, manifest.runID == receipt.runID,
              manifest.sourceIdentity == receipt.sourceIdentity, validAbsolute(manifest.hermesHome),
              validPlatform(manifest.osBuild), validPlatform(manifest.architecture),
              !manifest.trees.isEmpty, manifest.trees.count <= 16, Set(manifest.trees.map(\.label)).count == manifest.trees.count,
              manifest.trees.allSatisfy({ validLabel($0.label) && validAbsolute($0.original)
                  && !overlap(URL(fileURLWithPath: $0.original), root)
                  && !overlap(URL(fileURLWithPath: $0.original), URL(fileURLWithPath: manifest.hermesHome)) })
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        _ = try aggregate(manifest.trees.map(\.entries))
        for tree in manifest.trees {
            guard try scan(directory.appendingPathComponent(tree.label), privateTree: true).entries == tree.entries else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
        }
        // Reject additional private trees rather than silently omitting recovery material.
        let directoryFD = try openDirectory(directory, privateTree: true); defer { Darwin.close(directoryFD) }
        guard Set(try names(directoryFD)) == Set(manifest.trees.map(\.label) + ["manifest.json"]) else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        return manifest
    }

    private func scan(_ directory: URL, privateTree: Bool, entryBudget: Int? = nil, byteBudget: Int64? = nil) throws -> Snapshot {
        let fd = try openDirectory(directory, privateTree: privateTree); defer { Darwin.close(fd) }
        var entries = [Entry](), stamps = [Stamp](), bytes: Int64 = 0
        func walk(_ parent: Int32, prefix: String, depth: Int) throws {
            guard depth <= 64 else { throw DesktopHermesUpgradeError.recoveryRequired }
            let before = try info(parent)
            stamps.append(Stamp(before))
            for name in try names(parent) {
                guard entries.count < (entryBudget ?? maximumEntries) else { throw DesktopHermesUpgradeError.recoveryRequired }
                let path = prefix + name
                guard path.utf8.count <= 4096 else { throw DesktopHermesUpgradeError.recoveryRequired }
                var metadata = stat()
                guard fstatat(parent, name, &metadata, AT_SYMLINK_NOFOLLOW) == 0, metadata.st_uid == getuid() else { throw DesktopHermesUpgradeError.recoveryRequired }
                stamps.append(Stamp(metadata))
                let mode = UInt16(metadata.st_mode & 0o700)
                switch metadata.st_mode & S_IFMT {
                case S_IFDIR:
                    let child = Darwin.openat(parent, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK)
                    guard child >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
                    defer { Darwin.close(child) }
                    try validateDirectory(try info(child), privateTree: privateTree)
                    guard Stamp(try info(child)) == Stamp(metadata) else { throw DesktopHermesUpgradeError.recoveryRequired }
                    entries.append(.init(path: path, kind: "directory", permissions: 0o700, size: 0, digest: nil, target: nil))
                    try walk(child, prefix: path + "/", depth: depth + 1)
                case S_IFREG:
                    guard metadata.st_size >= 0, metadata.st_size <= (byteBudget ?? maximumBytes) - bytes else { throw DesktopHermesUpgradeError.recoveryRequired }
                    let handle = try openRegular(parent, name: name, expected: metadata, privateTree: privateTree)
                    defer { try? handle.close() }
                    let digest = try readFile(handle, expected: metadata, writeTo: nil)
                    bytes += metadata.st_size
                    entries.append(.init(path: path, kind: "file", permissions: mode, size: metadata.st_size, digest: digest, target: nil))
                case S_IFLNK:
                    var buffer = [UInt8](repeating: 0, count: 4097)
                    // Darwin imports this output as a raw pointer. Give it element storage,
                    // never an inout Array value (Swift 6.1 can overwrite the Array itself).
                    let count = buffer.withUnsafeMutableBytes { bytes in
                        readlinkat(parent, name, bytes.baseAddress!, bytes.count)
                    }
                    guard count > 0, count <= 4096, let target = String(bytes: buffer.prefix(count), encoding: .utf8), validRelative(target) else {
                        throw DesktopHermesUpgradeError.recoveryRequired
                    }
                    entries.append(.init(path: path, kind: "link", permissions: 0, size: 0, digest: nil, target: target))
                default: throw DesktopHermesUpgradeError.recoveryRequired
                }
                var after = stat()
                guard fstatat(parent, name, &after, AT_SYMLINK_NOFOLLOW) == 0, Stamp(after) == Stamp(metadata) else { throw DesktopHermesUpgradeError.recoveryRequired }
            }
            guard Stamp(try info(parent)) == Stamp(before) else { throw DesktopHermesUpgradeError.recoveryRequired }
        }
        try walk(fd, prefix: "", depth: 0)
        try validateLinks(entries)
        var pathInfo = stat()
        guard lstat(directory.path, &pathInfo) == 0, Stamp(pathInfo) == Stamp(try info(fd)) else { throw DesktopHermesUpgradeError.recoveryRequired }
        return .init(entries: entries, stamps: stamps)
    }

    private func copy(_ source: URL, into destination: URL, expected: [Entry], privateSource: Bool) throws {
        try ensurePrivate(destination)
        let sourceFD = try openDirectory(source, privateTree: privateSource); defer { Darwin.close(sourceFD) }
        let destinationFD = try openDirectory(destination, privateTree: true); defer { Darwin.close(destinationFD) }
        for entry in expected {
            let parts = entry.path.split(separator: "/").map(String.init)
            guard let name = parts.last else { throw DesktopHermesUpgradeError.recoveryRequired }
            let from = try parentFD(sourceFD, components: Array(parts.dropLast()), privateTree: privateSource); defer { Darwin.close(from) }
            let to = try parentFD(destinationFD, components: Array(parts.dropLast()), privateTree: true); defer { Darwin.close(to) }
            switch entry.kind {
            case "directory":
                guard mkdirat(to, name, 0o700) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            case "link":
                guard let target = entry.target, symlinkat(target, to, name) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            case "file":
                var metadata = stat()
                guard fstatat(from, name, &metadata, AT_SYMLINK_NOFOLLOW) == 0, metadata.st_size == entry.size else { throw DesktopHermesUpgradeError.recoveryRequired }
                let input = try openRegular(from, name: name, expected: metadata, privateTree: privateSource); defer { try? input.close() }
                let fd = Darwin.openat(to, name, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
                guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
                defer { Darwin.close(fd) }
                let output = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
                guard try readFile(input, expected: metadata, writeTo: output) == entry.digest,
                      fchmod(fd, mode_t(entry.permissions)) == 0, fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
            default: throw DesktopHermesUpgradeError.recoveryRequired
            }
        }
        guard try scan(destination, privateTree: true).entries == expected else { throw DesktopHermesUpgradeError.recoveryRequired }
        for entry in expected.reversed() where entry.kind == "directory" { try UpgradePrivateFiles.sync(destination.appendingPathComponent(entry.path)) }
        guard fsync(destinationFD) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func readFile(_ handle: FileHandle, expected: stat, writeTo output: FileHandle?) throws -> String {
        var digest = SHA256(), read: Int64 = 0
        while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
            guard data.count <= expected.st_size - read else { throw DesktopHermesUpgradeError.recoveryRequired }
            read += Int64(data.count); digest.update(data: data); try output?.write(contentsOf: data)
        }
        guard read == expected.st_size, Stamp(try info(handle.fileDescriptor)) == Stamp(expected) else { throw DesktopHermesUpgradeError.recoveryRequired }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
    private func validateLinks(_ entries: [Entry]) throws {
        guard Set(entries.map(\.path)).count == entries.count else { throw DesktopHermesUpgradeError.recoveryRequired }
        let indexed = Dictionary(uniqueKeysWithValues: entries.map { ($0.path, $0) })
        for entry in entries where entry.kind == "link" {
            var pending = entry.path.split(separator: "/").map(String.init), resolved = [String](), hops = 0, steps = 0
            while !pending.isEmpty {
                steps += 1
                guard steps <= 512, pending.count <= 512 else { throw DesktopHermesUpgradeError.recoveryRequired }
                let part = pending.removeFirst()
                if part == "." { continue }
                if part == ".." {
                    guard !resolved.isEmpty else { throw DesktopHermesUpgradeError.recoveryRequired }
                    resolved.removeLast(); continue
                }
                let path = (resolved + [part]).joined(separator: "/")
                guard let node = indexed[path] else { throw DesktopHermesUpgradeError.recoveryRequired }
                if node.kind == "link" {
                    hops += 1
                    guard hops <= 40, let target = node.target else { throw DesktopHermesUpgradeError.recoveryRequired }
                    pending = target.split(separator: "/").map(String.init) + pending
                } else {
                    guard pending.isEmpty || node.kind == "directory" else { throw DesktopHermesUpgradeError.recoveryRequired }
                    resolved.append(part)
                }
            }
        }
    }
    private func aggregate(_ trees: [[Entry]]) throws -> Int64 {
        var bytes: Int64 = 0, count = 0
        for entries in trees {
            guard entries.count <= maximumEntries - count else { throw DesktopHermesUpgradeError.recoveryRequired }
            count += entries.count
            for entry in entries {
                guard entry.size >= 0, entry.size <= maximumBytes - bytes else { throw DesktopHermesUpgradeError.recoveryRequired }
                bytes += entry.size
            }
        }
        return bytes
    }
    private func names(_ fd: Int32) throws -> [String] {
        let duplicate = dup(fd)
        guard duplicate >= 0, let directory = fdopendir(duplicate) else {
            if duplicate >= 0 { Darwin.close(duplicate) }; throw DesktopHermesUpgradeError.recoveryRequired
        }
        defer { closedir(directory) }
        var names = [String](); rewinddir(directory)
        while true {
            errno = 0
            guard let entry = readdir(directory) else {
                guard errno == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }; break
            }
            let value = withUnsafePointer(to: &entry.pointee.d_name) {
                $0.withMemoryRebound(to: CChar.self, capacity: Int(entry.pointee.d_namlen) + 1) { String(validatingUTF8: $0) }
            }
            guard let value else { throw DesktopHermesUpgradeError.recoveryRequired }
            if value == "." || value == ".." { continue }
            guard names.count < maximumEntries, validRelative(value), !value.contains("/") else { throw DesktopHermesUpgradeError.recoveryRequired }
            names.append(value)
        }
        return names.sorted()
    }
    private func openDirectory(_ url: URL, privateTree: Bool) throws -> Int32 {
        guard canonical(url) else { throw DesktopHermesUpgradeError.recoveryRequired }
        let fd = Darwin.open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        do { try validateDirectory(info(fd), privateTree: privateTree); return fd } catch { Darwin.close(fd); throw error }
    }
    private func parentFD(_ root: Int32, components: [String], privateTree: Bool) throws -> Int32 {
        var fd = dup(root)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        do {
            for component in components {
                guard validRelative(component), component != "..", component != ".", !component.contains("/") else { throw DesktopHermesUpgradeError.recoveryRequired }
                let next = Darwin.openat(fd, component, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_NONBLOCK)
                guard next >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
                Darwin.close(fd); fd = next; try validateDirectory(info(fd), privateTree: privateTree)
            }
            return fd
        } catch { Darwin.close(fd); throw error }
    }
    private func openRegular(_ parent: Int32, name: String, expected: stat, privateTree: Bool) throws -> FileHandle {
        let fd = Darwin.openat(parent, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        do {
            let metadata = try info(fd)
            guard metadata.st_mode & S_IFMT == S_IFREG, metadata.st_uid == getuid(), metadata.st_nlink == 1,
                  metadata.st_mode & (privateTree ? 0o077 : 0o022) == 0, Stamp(metadata) == Stamp(expected) else {
                throw DesktopHermesUpgradeError.recoveryRequired
            }
            return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        } catch { Darwin.close(fd); throw error }
    }
    private func validateDirectory(_ metadata: stat, privateTree: Bool) throws {
        guard metadata.st_mode & S_IFMT == S_IFDIR, metadata.st_uid == getuid(),
              metadata.st_mode & (privateTree ? 0o077 : 0o022) == 0,
              !privateTree || metadata.st_mode & 0o777 == 0o700 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func info(_ fd: Int32) throws -> stat {
        var value = stat(); guard fstat(fd, &value) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }; return value
    }
    private func ensurePrivate(_ url: URL) throws {
        guard canonical(url) else { throw DesktopHermesUpgradeError.recoveryRequired }
        if !UpgradePrivateFiles.exists(url) { try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
        try UpgradePrivateFiles.directory(url)
    }
    private func publish(_ temporary: URL, to final: URL) throws {
        try beforePublish?(final)
        guard renamex_np(temporary.path, final.path, UInt32(RENAME_EXCL)) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        try UpgradePrivateFiles.sync(final.deletingLastPathComponent())
    }
    private func platformValue(_ key: String) throws -> String {
        var bytes = [CChar](repeating: 0, count: 128), count = 128
        let result = bytes.withUnsafeMutableBytes { buffer in
            sysctlbyname(key, buffer.baseAddress!, &count, nil, 0)
        }
        guard result == 0, (1...128).contains(count), bytes[count - 1] == 0,
              let value = String(validatingUTF8: bytes), validPlatform(value) else { throw DesktopHermesUpgradeError.recoveryRequired }
        return value
    }
    private func validPlatform(_ value: String) -> Bool {
        !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
            && value.range(of: "^[A-Za-z0-9._-]{1,64}$", options: .regularExpression) != nil
    }
    private func validLabel(_ value: String) -> Bool {
        !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains)
            && value.range(of: "^[a-z][a-z0-9-]{0,63}$", options: .regularExpression) != nil
    }
    private func validRelative(_ value: String) -> Bool { !value.isEmpty && !value.hasPrefix("/") && !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }
    private func validAbsolute(_ value: String) -> Bool { value.hasPrefix("/") && value != "/" && !value.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) && URL(fileURLWithPath: value).standardizedFileURL.path == value }
    private func canonical(_ url: URL) -> Bool {
        url.isFileURL && (url.host == nil || url.host == "") && url.query == nil && url.fragment == nil
            && validAbsolute(url.path) && url.resolvingSymlinksInPath().path == url.path
    }
    private func contains(_ parent: URL, _ child: URL) -> Bool { parent.path == child.path || child.path.hasPrefix(parent.path + "/") }
    private func overlap(_ a: URL, _ b: URL) -> Bool { contains(a, b) || contains(b, a) }
    private func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private func encode(_ manifest: Manifest) throws -> Data {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; let data = try encoder.encode(manifest)
        guard data.count <= 32 * 1024 * 1024 else { throw DesktopHermesUpgradeError.recoveryRequired }; return data
    }
    private func mapped<T>(_ body: () throws -> T) throws -> T {
        do { return try body() } catch let error as DesktopHermesUpgradeError { throw error } catch { throw DesktopHermesUpgradeError.recoveryRequired }
    }
}

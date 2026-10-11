import CryptoKit
import Darwin
import Foundation
import SQLite3

/// Private recovery material. The caller must hold GO + upstream leases and prove every writer
/// stopped; the proof is checked again after copying. No restore API writes into the live home.
public struct DesktopHermesUpgradeBackupStore: Sendable {
    private struct Entry: Codable, Equatable {
        let path: String
        let kind: String
        let digest: String?
        let size: Int64
        let permissions: UInt16
        let linkTarget: String?
    }
    private struct Manifest: Codable {
        let schemaVersion: Int
        let runID: String
        let sourceFingerprint: String
        let files: [Entry]
        let databases: [String]
    }
    public let root: URL
    public init(root: URL) { self.root = root.standardizedFileURL.resolvingSymlinksInPath() }

    public func create(home: URL, runID: String,
                       verifyStoppedWriters: () throws -> Void) throws -> DesktopHermesUpgradeBackup {
        guard UUID(uuidString: runID) != nil else { throw DesktopHermesUpgradeError.preflightBlocked }
        let source = home.standardizedFileURL.resolvingSymlinksInPath()
        guard source.path != "/", !root.path.hasPrefix(source.path + "/"), root != source else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        try verifyStoppedWriters()
        let original = try inventory(source)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let fingerprint = try hash(encoder.encode(original))
        let databases = try original.filter { $0.kind == "file" && isSQLite(source.appendingPathComponent($0.path)) }.map(\.path)
        let sidecars = Set(databases.flatMap { [$0 + "-wal", $0 + "-shm", $0 + "-journal"] })
        let bytes = original.reduce(Int64(0)) { $0 + $1.size }
        try privateDirectory(root)
        let available = try FileManager.default.attributesOfFileSystem(forPath: root.path)[.systemFreeSize] as? NSNumber
        guard let available, available.int64Value > bytes + max(bytes / 5, 256 * 1024 * 1024) else {
            throw DesktopHermesUpgradeError.preflightBlocked
        }
        let final = root.appendingPathComponent(runID)
        if exists(final) {
            let data = try readPrivate(final.appendingPathComponent("manifest.json"), limit: 32 * 1024 * 1024)
            let receipt = DesktopHermesUpgradeBackup(runID: runID, manifestSHA256: hash(data))
            let saved = try validatedManifest(receipt)
            guard saved.sourceFingerprint == fingerprint else { throw DesktopHermesUpgradeError.recoveryRequired }
            try verifyStoppedWriters()
            guard try inventory(source) == original else { throw DesktopHermesUpgradeError.recoveryRequired }
            return receipt
        }
        let staging = root.appendingPathComponent(".\(runID)-\(UUID().uuidString)")
        try privateDirectory(staging)
        defer { if exists(staging) { try? FileManager.default.removeItem(at: staging) } }
        let tree = staging.appendingPathComponent("home")
        try privateDirectory(tree)
        for entry in original where !sidecars.contains(entry.path) {
            let from = source.appendingPathComponent(entry.path), to = tree.appendingPathComponent(entry.path)
            switch entry.kind {
            case "directory": try privateDirectory(to)
            case "link":
                // Preserve the link itself, never traverse it into another home or dependency store.
                try FileManager.default.createSymbolicLink(atPath: to.path, withDestinationPath: entry.linkTarget!)
            case "file":
                if databases.contains(entry.path) { try backupSQLite(from: from, to: to) }
                else { try copyFile(from: from, to: to) }
                try FileManager.default.setAttributes([.posixPermissions: entry.permissions & 0o700], ofItemAtPath: to.path)
            default: throw DesktopHermesUpgradeError.preflightBlocked
            }
        }
        try verifyStoppedWriters()
        // WAL commits or config/cron edits invalidate the whole recovery point, even if SQLite
        // happened to give a consistent individual database snapshot during a racing writer.
        guard try inventory(source) == original else { throw DesktopHermesUpgradeError.recoveryRequired }
        let archived = try inventory(tree)
        for entry in original where entry.kind == "file" && !sidecars.contains(entry.path) && !databases.contains(entry.path) {
            guard archived.contains(where: { $0.path == entry.path && $0.kind == "file"
                && $0.digest == entry.digest && $0.size == entry.size }) else { throw DesktopHermesUpgradeError.recoveryRequired }
        }
        let manifest = Manifest(schemaVersion: 1, runID: runID, sourceFingerprint: fingerprint,
                                files: archived, databases: databases)
        let data = try encoder.encode(manifest)
        guard data.count <= 32 * 1024 * 1024 else { throw DesktopHermesUpgradeError.recoveryRequired }
        let manifestURL = staging.appendingPathComponent("manifest.json")
        try writePrivate(data, to: manifestURL)
        // Verify all archived bytes independently before acknowledging the backup to the journal.
        guard try inventory(tree) == manifest.files else { throw DesktopHermesUpgradeError.recoveryRequired }
        for entry in manifest.files.reversed() where entry.kind == "directory" {
            try syncDirectory(tree.appendingPathComponent(entry.path))
        }
        try syncDirectory(tree); try syncDirectory(staging)
        try FileManager.default.moveItem(at: staging, to: final)
        try syncDirectory(root)
        return .init(runID: runID, manifestSHA256: hash(data))
    }

    public func verify(_ receipt: DesktopHermesUpgradeBackup) throws {
        _ = try validatedManifest(receipt)
    }

    /// Extracts only SQLite recovery points into a NEW directory. New sessions in the live home
    /// remain intact. Deciding whether/how to reconcile them is a separate owner recovery action.
    public func exportDatabases(_ receipt: DesktopHermesUpgradeBackup, to destination: URL) throws {
        let manifest = try validatedManifest(receipt)
        let target = destination.standardizedFileURL.resolvingSymlinksInPath()
        guard target.path != "/", !exists(target), !target.path.hasPrefix(root.path + "/") else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        let tree = root.appendingPathComponent(receipt.runID + "/home")
        try privateDirectory(target)
        var completed = false
        defer { if !completed { try? FileManager.default.removeItem(at: target) } }
        for relative in manifest.databases {
            let to = target.appendingPathComponent(relative)
            try privateDirectory(to.deletingLastPathComponent())
            try backupSQLite(from: tree.appendingPathComponent(relative), to: to)
        }
        try syncDirectory(target)
        completed = true
    }

    private func validatedManifest(_ receipt: DesktopHermesUpgradeBackup) throws -> Manifest {
        guard UUID(uuidString: receipt.runID) != nil, DesktopHermesUpgradeJournal.digest(receipt.manifestSHA256) else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        let directory = root.appendingPathComponent(receipt.runID)
        try checkDirectory(root); try checkDirectory(directory)
        for privateRoot in [root, directory] {
            let mode = try FileManager.default.attributesOfItem(atPath: privateRoot.path)[.posixPermissions] as? NSNumber
            guard mode?.intValue == 0o700 else { throw DesktopHermesUpgradeError.recoveryRequired }
        }
        let data = try readPrivate(directory.appendingPathComponent("manifest.json"), limit: 32 * 1024 * 1024)
        guard hash(data) == receipt.manifestSHA256,
              let manifest = try? JSONDecoder().decode(Manifest.self, from: data),
              manifest.schemaVersion == 1, manifest.runID == receipt.runID,
              manifest.databases.allSatisfy({ relative in
                  !relative.hasPrefix("/") && !relative.split(separator: "/").contains("..")
                      && manifest.files.contains(where: { $0.path == relative && $0.kind == "file" })
              }),
              try inventory(directory.appendingPathComponent("home")) == manifest.files
        else { throw DesktopHermesUpgradeError.recoveryRequired }
        for relative in manifest.databases { try checkSQLite(directory.appendingPathComponent("home/" + relative)) }
        return manifest
    }

    private func inventory(_ directory: URL) throws -> [Entry] {
        try checkDirectory(directory)
        var result: [Entry] = []
        func walk(_ folder: URL, prefix: String) throws {
            for name in try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted() {
                guard result.count < 200_000 else { throw DesktopHermesUpgradeError.preflightBlocked }
                let url = folder.appendingPathComponent(name), relative = prefix + name
                var info = stat()
                guard lstat(url.path, &info) == 0, info.st_uid == getuid() else { throw DesktopHermesUpgradeError.preflightBlocked }
                let mode = UInt16(info.st_mode & 0o777)
                switch info.st_mode & S_IFMT {
                case S_IFDIR:
                    result.append(.init(path: relative, kind: "directory", digest: nil, size: 0, permissions: mode, linkTarget: nil))
                    try walk(url, prefix: relative + "/")
                case S_IFLNK:
                    result.append(.init(path: relative, kind: "link", digest: nil, size: 0, permissions: mode,
                                        linkTarget: try FileManager.default.destinationOfSymbolicLink(atPath: url.path)))
                case S_IFREG:
                    // SHM reader-lock slots change while a readonly SQLite connection opens. They
                    // contain no committed data and are reconstructed, unlike the WAL.
                    if name.hasSuffix("-shm"), isSQLite(URL(fileURLWithPath: String(url.path.dropLast(4)))) { continue }
                    let digest = try fileHash(url)
                    result.append(.init(path: relative, kind: "file", digest: digest, size: info.st_size, permissions: mode, linkTarget: nil))
                default: throw DesktopHermesUpgradeError.preflightBlocked
                }
            }
        }
        try walk(directory, prefix: "")
        return result
    }
    private func fileHash(_ url: URL) throws -> String {
        let handle = try openFile(url)
        defer { try? handle.close() }
        var digest = SHA256()
        while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty { digest.update(data: data) }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
    private func openFile(_ url: URL) throws -> FileHandle {
        let fd = Darwin.open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        var info = stat()
        guard fd >= 0, fstat(fd, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFREG, info.st_nlink == 1 else {
            if fd >= 0 { Darwin.close(fd) }; throw DesktopHermesUpgradeError.recoveryRequired
        }
        return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
    private func isSQLite(_ url: URL) -> Bool {
        guard let file = try? openFile(url) else { return false }
        defer { try? file.close() }
        return (try? file.read(upToCount: 16)) == Data("SQLite format 3\0".utf8)
    }
    private func copyFile(from: URL, to: URL) throws {
        let reader = try openFile(from); defer { try? reader.close() }
        let fd = Darwin.open(to.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        let writer = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        while let data = try reader.read(upToCount: 1024 * 1024), !data.isEmpty { try writer.write(contentsOf: data) }
        guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func backupSQLite(from: URL, to: URL) throws {
        guard isSQLite(from), !exists(to) else { throw DesktopHermesUpgradeError.recoveryRequired }
        var source: OpaquePointer?, destination: OpaquePointer?
        guard sqlite3_open_v2(try readOnlySQLiteURI(from), &source, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOFOLLOW | SQLITE_OPEN_URI, nil) == SQLITE_OK else {
            sqlite3_close(source); throw DesktopHermesUpgradeError.recoveryRequired
        }
        defer { sqlite3_close(source) }
        guard sqlite3_open_v2(try sqlitePath(to), &destination, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOFOLLOW, nil) == SQLITE_OK else {
            sqlite3_close(destination); throw DesktopHermesUpgradeError.recoveryRequired
        }
        defer { sqlite3_close(destination) }
        guard let backup = sqlite3_backup_init(destination, "main", source, "main") else { throw DesktopHermesUpgradeError.recoveryRequired }
        let copied = sqlite3_backup_step(backup, -1), finished = sqlite3_backup_finish(backup)
        guard copied == SQLITE_DONE, finished == SQLITE_OK else { throw DesktopHermesUpgradeError.recoveryRequired }
        // A WAL source carries WAL header flags. Normalize and close the destination before a
        // second readonly connection verifies it; the archive must need no live WAL/SHM sidecars.
        guard sqlite3_exec(destination, "PRAGMA journal_mode=DELETE;", nil, nil, nil) == SQLITE_OK,
              sqlite3_close(destination) == SQLITE_OK else { throw DesktopHermesUpgradeError.recoveryRequired }
        destination = nil
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: to.path)
        try checkSQLite(to)
        let fd = Darwin.open(to.path, O_RDONLY | O_NOFOLLOW)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func checkSQLite(_ url: URL) throws {
        var database: OpaquePointer?, statement: OpaquePointer?
        defer { sqlite3_finalize(statement); sqlite3_close(database) }
        guard sqlite3_open_v2(try readOnlySQLiteURI(url), &database, SQLITE_OPEN_READONLY | SQLITE_OPEN_NOFOLLOW | SQLITE_OPEN_URI, nil) == SQLITE_OK,
              sqlite3_prepare_v2(database, "PRAGMA quick_check", -1, &statement, nil) == SQLITE_OK,
              sqlite3_step(statement) == SQLITE_ROW, let text = sqlite3_column_text(statement, 0),
              String(cString: text) == "ok", sqlite3_step(statement) == SQLITE_DONE
        else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func sqlitePath(_ url: URL) throws -> String {
        // Keep the native parent path as a String, including Darwin's standard /tmp alias.
        guard let parent = realpath(url.deletingLastPathComponent().path, nil) else {
            throw DesktopHermesUpgradeError.recoveryRequired
        }
        defer { free(parent) }
        return String(cString: parent) + "/" + url.lastPathComponent
    }
    private func readOnlySQLiteURI(_ url: URL) throws -> String {
        let path = try sqlitePath(url)
        var components = URLComponents(); components.path = path
        // Apple's readonly WAL reader cannot open a retired WAL-mode database with no sidecars.
        // With no WAL/rollback bytes, all committed pages are already in the main file. The
        // stopped-writer proof and before/after fingerprint make immutable reading safe here.
        // Never use immutable on a nonempty WAL: doing so would silently omit committed pages.
        var hasPendingPages = false
        for suffix in ["-wal", "-journal"] {
            var info = stat()
            if lstat(path + suffix, &info) == 0 {
                guard info.st_mode & S_IFMT == S_IFREG, info.st_uid == getuid(), info.st_nlink == 1 else {
                    throw DesktopHermesUpgradeError.recoveryRequired
                }
                hasPendingPages = hasPendingPages || info.st_size > 0
            } else if errno != ENOENT { throw DesktopHermesUpgradeError.recoveryRequired }
        }
        return "file:" + components.percentEncodedPath + (hasPendingPages ? "" : "?immutable=1")
    }
    private func checkDirectory(_ url: URL) throws {
        var info = stat()
        guard lstat(url.path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR,
              url.resolvingSymlinksInPath().path == url.path else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func privateDirectory(_ url: URL) throws {
        if !exists(url) { try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
        try checkDirectory(url)
        let permissions = try FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? NSNumber
        guard permissions?.intValue == 0o700 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func writePrivate(_ data: Data, to url: URL) throws {
        let fd = Darwin.open(url.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
        guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func readPrivate(_ url: URL, limit: Int) throws -> Data {
        let file = try openFile(url); defer { try? file.close() }
        let data = try file.read(upToCount: limit + 1) ?? Data()
        guard data.count <= limit else { throw DesktopHermesUpgradeError.recoveryRequired }
        return data
    }
    private func syncDirectory(_ url: URL) throws {
        let fd = Darwin.open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard fd >= 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
        defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw DesktopHermesUpgradeError.recoveryRequired }
    }
    private func exists(_ url: URL) -> Bool { var info = stat(); return lstat(url.path, &info) == 0 }
    private func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}

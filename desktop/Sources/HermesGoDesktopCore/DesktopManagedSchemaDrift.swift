import Foundation

/// Notices that the shared `state.db` has grown columns the managed Hermes was not built to read.
///
/// The managed copy is pinned to one upstream commit; the owner's own Hermes rolls forward; they
/// share one database because that sharing is the product (`docs/MANAGED_HERMES_STRATEGY.md`).
/// Drift is therefore certain, and on 2026-09-19 it arrived as a 500 from the response encoder with
/// a traceback naming only FastAPI frames, surfacing on the phone as a generic error while the Mac
/// looked healthy. Two hours went into finding a cause that a single sentence could have named.
///
/// This check does not make the managed copy work against a newer database — nothing here can. It
/// makes the drift *visible at the moment it exists*, with an instruction the owner can act on.
///
/// **It deliberately does not compare `schema_version`.** That is the gate one would reach for
/// first, and it would have missed the incident it exists for: upstream added `display_identity`
/// and `display_order` to `messages` while leaving `SCHEMA_VERSION = 30` on both sides. Columns are
/// what actually differed, so columns are what this compares. (Checking the live database the same
/// day also turned up `sessions.transport_profile`, which nobody had noticed at all.)
public struct DesktopManagedSchemaDrift: Sendable {
    /// Table name to the columns present in the database but absent from the release's baseline.
    public let unknownColumns: [String: [String]]

    public init(unknownColumns: [String: [String]]) {
        self.unknownColumns = unknownColumns
    }

    public var hasDrift: Bool { unknownColumns.contains { !$0.value.isEmpty } }

    /// Stable, sorted, and short enough to paste: `messages: display_identity, display_order`.
    public var summary: String {
        unknownColumns
            .filter { !$0.value.isEmpty }
            .sorted { $0.key < $1.key }
            .map { "\($0.key): \($0.value.sorted().joined(separator: ", "))" }
            .joined(separator: "; ")
    }

    /// Compare a release's recorded baseline against the live schema.
    ///
    /// A table the baseline does not mention is skipped rather than reported: the baseline records
    /// the tables worth watching, and treating an unlisted one as entirely unknown would report
    /// every column it has.
    public static func compare(
        baseline: [String: [String]],
        live: [String: [String]]
    ) -> DesktopManagedSchemaDrift {
        var findings: [String: [String]] = [:]
        for (table, known) in baseline {
            guard let present = live[table] else { continue }
            let knownSet = Set(known)
            let extra = present.filter { !knownSet.contains($0) }
            if !extra.isEmpty { findings[table] = extra }
        }
        return DesktopManagedSchemaDrift(unknownColumns: findings)
    }
}

/// Reads the two halves the comparison needs. Both are read-only and both tolerate absence.
public struct DesktopManagedSchemaInspector: Sendable {
    public let identityURL: URL
    public let databaseURL: URL
    private let runner: @Sendable (String, [String]) -> String?

    public init(
        identityURL: URL,
        databaseURL: URL,
        runner: (@Sendable (String, [String]) -> String?)? = nil
    ) {
        self.identityURL = identityURL
        self.databaseURL = databaseURL
        self.runner = runner ?? DesktopManagedSchemaInspector.runSQLite
    }

    /// The pair the managed services actually run with: the baseline of the release `current`
    /// points at, against the database that release was pointed at.
    ///
    /// `current` is the symlink activation flips, so this reads whichever release is live now
    /// rather than whichever one was live when the app launched. `state.db` under `HERMES_HOME` is
    /// the owner's own database — the sharing is the product, see `docs/MANAGED_HERMES_STRATEGY.md`.
    public init(
        managedPaths paths: DesktopManagedBootstrapPaths,
        runner: (@Sendable (String, [String]) -> String?)? = nil
    ) {
        self.init(
            identityURL: paths.managedRoot
                .appendingPathComponent("current", isDirectory: true)
                .appendingPathComponent(
                    DesktopReleaseComponentKind.hermesServer.rawValue,
                    isDirectory: true
                )
                .appendingPathComponent("BUILD-IDENTITY.json"),
            databaseURL: paths.hermesHome.appendingPathComponent("state.db"),
            runner: runner
        )
    }

    /// nil when either half is unavailable — a missing baseline means a release built before this
    /// existed, and silence is the right answer for one of those rather than a false alarm.
    public func inspect() -> DesktopManagedSchemaDrift? {
        guard let baseline = readBaseline() else { return nil }
        guard !baseline.isEmpty else { return nil }
        var live: [String: [String]] = [:]
        for table in baseline.keys {
            guard let columns = readLiveColumns(table: table) else { return nil }
            live[table] = columns
        }
        return DesktopManagedSchemaDrift.compare(baseline: baseline, live: live)
    }

    private func readBaseline() -> [String: [String]]? {
        guard let data = try? Data(contentsOf: identityURL),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let recorded = object["schemaBaseline"] as? [String: Any]
        else { return nil }
        var baseline: [String: [String]] = [:]
        for (table, columns) in recorded {
            guard let names = columns as? [String] else { continue }
            baseline[table] = names
        }
        return baseline
    }

    /// `pragma table_info` through the sqlite3 binary: the managed installation does not link
    /// SQLite, and opening the owner's live database from Swift to read one pragma would be a
    /// bigger commitment than this check is worth.
    private func readLiveColumns(table: String) -> [String]? {
        // The table name comes from our own baseline, never from input, but keep it obvious that
        // nothing interpolated here is attacker-controlled.
        guard table.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }) else { return nil }
        guard let output = runner("/usr/bin/sqlite3", [
            "-readonly", databaseURL.path, "pragma table_info(\(table));",
        ]) else { return nil }
        let columns = output
            .split(separator: "\n")
            .compactMap { line -> String? in
                let fields = line.split(separator: "|", omittingEmptySubsequences: false)
                return fields.count > 1 ? String(fields[1]) : nil
            }
        return columns.isEmpty ? nil : columns
    }

    private static let runSQLite: @Sendable (String, [String]) -> String? = { path, arguments in
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe()
        do {
            try process.run()
        } catch {
            return nil
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

public extension DesktopIssue {
    /// The one place drift becomes something a person sees.
    ///
    /// It answers nil for "no drift" and for "could not tell", which are the same thing to the
    /// person reading the menu bar: nothing to do. HG-71 shipped every part of this check except a
    /// caller, so the mechanism worked in tests and the Mac stayed silent — the guard against that
    /// is `ManagedSchemaWiringTests`, not this function.
    static func managedSchemaDrift(_ drift: DesktopManagedSchemaDrift?) -> DesktopIssue? {
        guard let drift, drift.hasDrift else { return nil }
        return DesktopIssue(code: .managedHermesBehindDatabase, technicalCause: drift.summary)
    }
}

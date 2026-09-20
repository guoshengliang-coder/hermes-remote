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
    ///
    /// Columns that have been examined and found harmless are subtracted, but only on the terms
    /// recorded with them — see `DesktopManagedSchemaAcknowledgement`. Everything else is reported,
    /// which is the direction that matters: an unexamined column is exactly what this exists for.
    public static func compare(
        baseline: [String: [String]],
        live: [String: [DesktopManagedSchemaColumn]],
        acknowledged: [DesktopManagedSchemaAcknowledgement] = [],
        appliedPatches: Set<String> = []
    ) -> DesktopManagedSchemaDrift {
        var findings: [String: [String]] = [:]
        for (table, known) in baseline {
            guard let present = live[table] else { continue }
            let knownSet = Set(known)
            let extra = present
                .filter { !knownSet.contains($0.name) }
                .filter { column in
                    !acknowledged.contains {
                        $0.covers(table: table, column: column, appliedPatches: appliedPatches)
                    }
                }
                .map(\.name)
            if !extra.isEmpty { findings[table] = extra }
        }
        return DesktopManagedSchemaDrift(unknownColumns: findings)
    }
}

/// One column of the live database, with the type `pragma table_info` reports for it.
///
/// The type is carried because an acknowledgement is only valid for the column it was made about:
/// upstream widening a TEXT column to a BLOB is a different fact than the one someone examined.
public struct DesktopManagedSchemaColumn: Equatable, Sendable {
    public let name: String
    public let type: String

    public init(name: String, type: String = "") {
        self.name = name
        self.type = type
    }
}

/// A column someone has looked at and decided does not warrant telling the owner about.
///
/// This list is the difference between a warning that stays useful and one that is always on. The
/// drift it suppresses is real — the managed copy genuinely does not know these columns — but the
/// owner has nothing to do about them, and a notice that is permanently lit teaches people to stop
/// reading it, which costs exactly the one time it matters.
///
/// **Each entry states the terms it holds under, and the terms are checked, not assumed:**
///
/// - `neutralisedBy` names the patch that makes the column harmless. A release built without that
///   patch does not get the exemption. This is why the 2026-09-19 incident would still be reported
///   today by a release that dropped patch 010.
/// - `expectedType` must match what the database reports now. An acknowledgement of a TEXT column
///   does not carry over to the same name turned BLOB, and BLOB is the shape that broke the
///   response encoder.
///
/// Adding an entry is a decision to stay quiet about something. Write down what was checked.
public struct DesktopManagedSchemaAcknowledgement: Equatable, Sendable {
    public let table: String
    public let column: String
    public let expectedType: String
    /// The patch that neutralises the column, or nil when nothing needs to be in place.
    public let neutralisedBy: String?
    public let reason: String

    public init(
        table: String,
        column: String,
        expectedType: String,
        neutralisedBy: String?,
        reason: String
    ) {
        self.table = table
        self.column = column
        self.expectedType = expectedType
        self.neutralisedBy = neutralisedBy
        self.reason = reason
    }

    func covers(
        table: String,
        column: DesktopManagedSchemaColumn,
        appliedPatches: Set<String>
    ) -> Bool {
        guard self.table == table, self.column == column.name else { return false }
        guard expectedType.caseInsensitiveCompare(column.type) == .orderedSame else { return false }
        guard let neutralisedBy else { return true }
        return appliedPatches.contains(neutralisedBy)
    }

    /// The columns examined on 2026-09-20, the day the check was first wired to the window.
    ///
    /// All three were already in the owner's database and all three would otherwise light the
    /// notice permanently, because this Mac's own Hermes rolls forward and the managed copy does
    /// not. None of them is reachable by the phone today.
    public static let known: [DesktopManagedSchemaAcknowledgement] = [
        DesktopManagedSchemaAcknowledgement(
            table: "messages",
            column: "display_identity",
            expectedType: "BLOB",
            neutralisedBy: "010-message-dict-drops-unknown-columns.patch",
            reason: """
                The column that caused the 2026-09-19 500: a BLOB the response encoder called                 .decode() on. Patch 010 narrows the message dict to an explicit allowlist, so the                 value never reaches the encoder. Without that patch this is a live failure, which                 is why the exemption names it.
                """
        ),
        DesktopManagedSchemaAcknowledgement(
            table: "messages",
            column: "display_order",
            expectedType: "INTEGER",
            neutralisedBy: "010-message-dict-drops-unknown-columns.patch",
            reason: """
                Arrived with display_identity and is harmless by itself — an INTEGER serialises                 fine. Exempted on the same terms rather than on its type alone, because what is                 actually true is that the allowlist drops it.
                """
        ),
        DesktopManagedSchemaAcknowledgement(
            table: "sessions",
            column: "transport_profile",
            expectedType: "TEXT",
            neutralisedBy: nil,
            reason: """
                Found on 2026-09-20 while checking the live database; nobody had noticed it. TEXT                 serialises, so it cannot reproduce the encoder failure, and no allowlist protects                 the sessions rows — which is precisely why the exemption is pinned to TEXT. A BLOB                 appearing in sessions is the case this check must still catch.
                """
        ),
    ]
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
        guard let identity = readIdentity() else { return nil }
        guard !identity.baseline.isEmpty else { return nil }
        var live: [String: [DesktopManagedSchemaColumn]] = [:]
        for table in identity.baseline.keys {
            guard let columns = readLiveColumns(table: table) else { return nil }
            live[table] = columns
        }
        return DesktopManagedSchemaDrift.compare(
            baseline: identity.baseline,
            live: live,
            acknowledged: DesktopManagedSchemaAcknowledgement.known,
            appliedPatches: identity.patches
        )
    }

    /// The baseline and the patch names, from the one file that records what was actually built.
    ///
    /// The patches matter as much as the baseline: an acknowledgement that depends on patch 010
    /// must not apply to a release that does not carry it, and the only honest source for that is
    /// the release's own identity file.
    private func readIdentity() -> (baseline: [String: [String]], patches: Set<String>)? {
        guard let data = try? Data(contentsOf: identityURL),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let recorded = object["schemaBaseline"] as? [String: Any]
        else { return nil }
        var baseline: [String: [String]] = [:]
        for (table, columns) in recorded {
            guard let names = columns as? [String] else { continue }
            baseline[table] = names
        }
        var patches: Set<String> = []
        for entry in (object["patches"] as? [[String: Any]]) ?? [] {
            if let name = entry["name"] as? String { patches.insert(name) }
        }
        return (baseline, patches)
    }

    /// `pragma table_info` through the sqlite3 binary: the managed installation does not link
    /// SQLite, and opening the owner's live database from Swift to read one pragma would be a
    /// bigger commitment than this check is worth.
    private func readLiveColumns(table: String) -> [DesktopManagedSchemaColumn]? {
        // The table name comes from our own baseline, never from input, but keep it obvious that
        // nothing interpolated here is attacker-controlled.
        guard table.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "_" }) else { return nil }
        guard let output = runner("/usr/bin/sqlite3", [
            "-readonly", databaseURL.path, "pragma table_info(\(table));",
        ]) else { return nil }
        // cid|name|type|notnull|dflt_value|pk — the type is column 2 and is what an
        // acknowledgement is checked against.
        let columns = output
            .split(separator: "\n")
            .compactMap { line -> DesktopManagedSchemaColumn? in
                let fields = line.split(separator: "|", omittingEmptySubsequences: false)
                guard fields.count > 1 else { return nil }
                return DesktopManagedSchemaColumn(
                    name: String(fields[1]),
                    type: fields.count > 2 ? String(fields[2]) : ""
                )
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

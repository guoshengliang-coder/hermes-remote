import CryptoKit
import Foundation

/// In-memory receipts for a single Desktop session. This is not background/restart recovery.
/// Only acknowledged stages with unchanged output evidence can be skipped on a manual retry.
public actor DesktopHermesUpgradeSession {
    typealias Evidence = @Sendable (DesktopHermesUpgradeStep, DesktopHermesUpgradeTarget) -> String?
    private var key: String?
    private var receipts: [String] = []
    private var busy = false
    private let evidence: Evidence
    public init(paths: DesktopLocalHermesPaths) {
        evidence = { step, target in Self.evidence(step: step, target: target, paths: paths) }
    }
    init(evidence: @escaping Evidence) { self.evidence = evidence }
    func acquire() throws {
        guard !busy else { throw DesktopHermesInstallFailure.stageFailed(stage: "download", detail: "upgrade already running") }
        busy = true
    }
    func release() { busy = false }
    func resume(target: DesktopHermesUpgradeTarget, script: Data) -> Int {
        let newKey = target.commit + ":" + target.version + ":" + SHA256.hash(data: script).description
        if key != newKey { key = newKey; receipts = [] }
        var valid = 0
        for (step, receipt) in zip(DesktopHermesUpgradeStep.installerSteps, receipts) {
            guard evidence(step, target) == receipt else { break }
            valid += 1
        }
        receipts = Array(receipts.prefix(valid))
        return valid
    }
    func completed(_ step: DesktopHermesUpgradeStep, target: DesktopHermesUpgradeTarget) {
        let index = DesktopHermesUpgradeStep.installerSteps.firstIndex(of: step)!
        guard receipts.count == index, let proof = evidence(step, target) else { return }
        receipts.append(proof)
    }

    private static func evidence(step: DesktopHermesUpgradeStep, target: DesktopHermesUpgradeTarget,
                                 paths: DesktopLocalHermesPaths) -> String? {
        let fm = FileManager.default
        let root = paths.checkoutRoot
        guard DesktopLocalHermesDetector(paths: paths).readGitIdentity()?.commit == target.commit,
              let attrs = try? fm.attributesOfItem(atPath: root.path), let inode = attrs[.systemFileNumber] else { return nil }
        var values = [root.resolvingSymlinksInPath().path, "\(attrs[.systemNumber] ?? 0):\(inode)", target.commit]
        func record(_ url: URL, contents: Bool = false) -> Bool {
            let resolved = url.resolvingSymlinksInPath()
            guard let attributes = try? fm.attributesOfItem(atPath: resolved.path) else { return false }
            values.append(resolved.path)
            values.append("\(attributes[.systemFileNumber] ?? 0):\(attributes[.size] ?? 0):\(attributes[.modificationDate] ?? "")")
            if contents {
                guard let size = attributes[.size] as? NSNumber, size.intValue <= 4_000_000,
                      let data = try? Data(contentsOf: resolved) else { return false }
                values.append(SHA256.hash(data: data).description)
            }
            return true
        }
        switch step {
        case .repository:
            for file in ["pyproject.toml", "uv.lock", "pm/lock.json"] where fm.fileExists(atPath: root.appendingPathComponent(file).path) {
                guard record(root.appendingPathComponent(file), contents: true) else { return nil }
            }
        case .venv:
            // PM releases bootstrap Python through uv; legacy releases create an in-tree venv.
            if fm.fileExists(atPath: root.appendingPathComponent("pm/lock.json").path) {
                let base = paths.homeDirectory.appendingPathComponent(".local/share/uv/python")
                guard let lockData = try? Data(contentsOf: root.appendingPathComponent("pm/lock.json")),
                      let lock = try? JSONSerialization.jsonObject(with: lockData) as? [String: Any],
                      let packages = lock["packages"] as? [String: Any], let python = packages["python"] as? [String: Any],
                      let version = python["version"] as? String else { return nil }
                let minor = version.split(separator: ".").prefix(2).joined(separator: ".")
                let candidates = ((try? fm.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)) ?? [])
                    .filter { $0.lastPathComponent.hasPrefix("cpython-\(minor).") }.sorted { $0.path < $1.path }
                guard let python = candidates.map({ $0.appendingPathComponent("bin/python3") }).last(where: { fm.isExecutableFile(atPath: $0.path) }),
                      record(python) else { return nil }
            } else {
                guard record(root.appendingPathComponent("venv/pyvenv.cfg"), contents: true),
                      fm.isExecutableFile(atPath: root.appendingPathComponent("venv/bin/python").path),
                      record(root.appendingPathComponent("venv/bin/python")) else { return nil }
            }
        case .dependencies:
            if fm.fileExists(atPath: root.appendingPathComponent("pm/lock.json").path) {
                let installKey = SHA256.hash(data: Data(root.resolvingSymlinksInPath().path.utf8)).map { String(format: "%02x", $0) }.joined().prefix(16)
                let state = paths.hermesHome.appendingPathComponent("installs/\(installKey)")
                guard let data = try? Data(contentsOf: state.appendingPathComponent("facts.json")),
                      let facts = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let packages = facts["packages"] as? [String: Any], let venv = packages["venv"] as? [String: Any],
                      let path = venv["environment"] as? String else { return nil }
                let dir = URL(fileURLWithPath: path).resolvingSymlinksInPath()
                guard dir.path.hasPrefix(state.appendingPathComponent("environments").resolvingSymlinksInPath().path + "/"),
                      record(dir.appendingPathComponent("pyvenv.cfg"), contents: true), record(dir.appendingPathComponent("bin/python")),
                      fm.isExecutableFile(atPath: dir.appendingPathComponent("bin/python").path),
                      fm.fileExists(atPath: dir.appendingPathComponent("lib").path) else { return nil }
            } else {
                guard let installed = DesktopLocalHermesDetector(paths: paths).detect().installation,
                      installed.dependenciesConsistent, record(root.appendingPathComponent("venv/pyvenv.cfg"), contents: true) else { return nil }
            }
        case .config:
            // Never read configuration or credentials; the upstream stage promises these exist.
            guard (try? fm.attributesOfItem(atPath: paths.hermesHome.appendingPathComponent(".env").path)[.type]) as? FileAttributeType == .typeRegular,
                  fm.fileExists(atPath: paths.hermesHome.appendingPathComponent("sessions").path) else { return nil }
            if fm.fileExists(atPath: root.appendingPathComponent("cli-config.yaml.example").path),
               !fm.fileExists(atPath: paths.hermesHome.appendingPathComponent("config.yaml").path) { return nil }
        case .products, .complete:
            guard let installed = DesktopLocalHermesDetector(paths: paths).detect().installation,
                  installed.commit == target.commit, installed.version == target.version,
                  record(root.appendingPathComponent("install-stamp.json"), contents: true),
                  record(installed.executable) else { return nil }
            for (source, result) in [("ui-tui", "ui-tui/dist/entry.js"), ("web", "hermes_cli/web_dist/index.html")] {
                if fm.fileExists(atPath: root.appendingPathComponent(source).path), !record(root.appendingPathComponent(result)) { return nil }
            }
            for arch in ["mac", "mac-arm64"] {
                let app = root.appendingPathComponent("apps/desktop/release/\(arch)/Hermes.app")
                if fm.fileExists(atPath: app.path) {
                    guard record(app.appendingPathComponent("Contents/Resources/app.asar")) else { return nil }
                }
            }
        default: return nil
        }
        return values.joined(separator: "\n")
    }
}

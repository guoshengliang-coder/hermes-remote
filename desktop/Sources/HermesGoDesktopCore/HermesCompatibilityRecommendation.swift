import CryptoKit
import Foundation

public struct HermesCompatibilityComponent: Codable, Equatable, Sendable {
    public let name: String
    public let version: String
    public let artifactSha256: String
    public init(name: String, version: String, artifactSha256: String) {
        self.name = name; self.version = version; self.artifactSha256 = artifactSha256
    }
}
public struct HermesCompatibilityEnvironment: Codable, Equatable, Sendable {
    public let os: String
    public let arch: String
    public let osVersion: String
    public let installation: String
    public init(os: String = "macos", arch: String, osVersion: String, installation: String) {
        self.os = os; self.arch = arch; self.osVersion = osVersion; self.installation = installation
    }
}
public struct HermesCompatibilityIdentity: Decodable, Equatable, Sendable {
    public let schemaVersion: Int
    public let candidateId: String
    public let version: String
    public let sourceCommit: String
    public let targetCommit: String
    public let goCommit: String
    public let suiteSha256: String
    public let installerSha256: String
    public let environment: HermesCompatibilityEnvironment
    public let components: [HermesCompatibilityComponent]
}
public struct VerifiedHermesRecommendation: Sendable {
    public struct Entry: Decodable, Equatable, Sendable {
        public let schemaVersion: Int
        public let id: String
        public let runId: String
        public let identity: HermesCompatibilityIdentity
        public let reportSha256: String
    }
    public let revision: Int
    public let expiresAt: Date
    public let entries: [Entry]
    let payloadSha256: String
    fileprivate init(revision: Int, expiresAt: Date, entries: [Entry], payloadSha256: String) {
        self.revision = revision; self.expiresAt = expiresAt; self.entries = entries; self.payloadSha256 = payloadSha256
    }

    /// This selects a supported path only; execution separately requires a fresh online fetch.
    public func newestUpgrade(sourceCommit: String, currentVersion: String, environment: HermesCompatibilityEnvironment,
                              components: [HermesCompatibilityComponent], now: Date = Date()) -> Entry? {
        guard now < expiresAt, HermesCompatibilitySnapshot.version(currentVersion) else { return nil }
        return entries.filter {
            $0.identity.sourceCommit == sourceCommit && $0.identity.environment == environment
                && $0.identity.components == components && Self.newer($0.identity.version, than: currentVersion)
        }.max { Self.newer($1.identity.version, than: $0.identity.version) }
    }
    private static func newer(_ lhs: String, than rhs: String) -> Bool {
        let a = lhs.split(separator: ".").compactMap { Int($0) }, b = rhs.split(separator: ".").compactMap { Int($0) }
        for (x,y) in zip(a,b) where x != y { return x > y }
        return false
    }
}

public struct HermesCompatibilityRecommendationVerifier: Sendable {
    private let origin: String
    private let keys: [String: Data]
    public init(origin: String, keys: [String: Data]) throws {
        guard let url = URLComponents(string: origin), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.port == nil, url.query == nil, url.fragment == nil,
              url.path.isEmpty, !keys.isEmpty, keys.count <= 8,
              keys.allSatisfy({ Self.matches($0.key, #"\A[A-Za-z0-9._-]{1,64}\z"#) && $0.value.count == 32 })
        else { throw Self.issue }
        self.origin = origin; self.keys = keys
    }
    public func verify(_ data: Data, minimumRevision: Int, now: Date = Date()) throws -> VerifiedHermesRecommendation {
        do {
            guard DesktopHermesUpgradeJSON.isUnambiguous(data, maximumBytes: 512 * 1024),
                  (0...9_007_199_254_740_991).contains(minimumRevision) else { throw Self.issue }
            let envelope = try Self.object(JSONSerialization.jsonObject(with: data), keys: ["payload","keyId","algorithm","signature"])
            guard envelope["algorithm"] as? String == "Ed25519", let id = envelope["keyId"] as? String,
                  let keyData = keys[id], let payload = Self.base64(envelope["payload"], maximum: 256 * 1024),
                  let signature = Self.base64(envelope["signature"], maximum: 64), signature.count == 64,
                  try Curve25519.Signing.PublicKey(rawRepresentation: keyData).isValidSignature(signature, for: payload)
            else { throw Self.issue }
            guard DesktopHermesUpgradeJSON.isUnambiguous(payload, maximumBytes: 256 * 1024, maximumNodes: 16384) else { throw Self.issue }
            let root = try Self.object(JSONSerialization.jsonObject(with: payload), keys: ["schemaVersion","purpose","origin","revision","issuedAt","expiresAt","entries"])
            guard root["purpose"] as? String == "hermes-go.compatibility.recommendations.v1",
                  root["origin"] as? String == origin, let entries = root["entries"] as? [[String: Any]], entries.count <= 100
            else { throw Self.issue }
            for entry in entries {
                _ = try Self.object(entry, keys: ["schemaVersion","id","runId","identity","reportSha256"])
                let identity = try Self.object(entry["identity"], keys: ["schemaVersion","candidateId","version","sourceCommit","targetCommit","goCommit","suiteSha256","installerSha256","environment","components"])
                _ = try Self.object(identity["environment"], keys: ["os","arch","osVersion","installation"])
                guard let components = identity["components"] as? [[String:Any]], components.count == 5 else { throw Self.issue }
                for component in components { _ = try Self.object(component, keys: ["name","version","artifactSha256"]) }
            }
            struct Payload: Decodable {
                let schemaVersion: Int; let revision: Int; let issuedAt: String; let expiresAt: String
                let entries: [VerifiedHermesRecommendation.Entry]
            }
            let decoded = try JSONDecoder().decode(Payload.self, from: payload)
            let format = ISO8601DateFormatter()
            guard decoded.schemaVersion == 1, decoded.revision >= max(1, minimumRevision), decoded.revision <= 9_007_199_254_740_991,
                  let issued = format.date(from: decoded.issuedAt), let expires = format.date(from: decoded.expiresAt),
                  format.string(from: issued) == decoded.issuedAt, format.string(from: expires) == decoded.expiresAt,
                  issued <= now.addingTimeInterval(30), expires > now, expires > issued, expires.timeIntervalSince(issued) <= 86_400,
                  Set(decoded.entries.map(\.id)).count == decoded.entries.count
            else { throw Self.issue }
            for entry in decoded.entries {
                let v = entry.identity
                guard entry.schemaVersion == 1, Self.uuid(entry.id), Self.uuid(entry.runId), Self.hash(entry.reportSha256),
                      v.schemaVersion == 1, Self.uuid(v.candidateId), HermesCompatibilitySnapshot.version(v.version),
                      [v.sourceCommit,v.targetCommit,v.goCommit].allSatisfy(HermesCompatibilitySnapshot.commit),
                      v.sourceCommit != v.targetCommit, Self.hash(v.suiteSha256), Self.hash(v.installerSha256),
                      v.environment.os == "macos", v.environment.arch == "arm64", v.environment.installation == "legacy-venv-to-pm",
                      HermesCompatibilitySnapshot.version(v.environment.osVersion),
                      v.components.map(\.name) == ["desktop","connector","gateway","android","web"],
                      v.components.allSatisfy({ HermesCompatibilitySnapshot.version($0.version) && Self.hash($0.artifactSha256) })
                else { throw Self.issue }
            }
            return VerifiedHermesRecommendation(revision: decoded.revision, expiresAt: expires, entries: decoded.entries,
                payloadSha256: SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined())
        } catch { throw Self.issue }
    }
    private static var issue: DesktopIssue { DesktopIssue(code: .hermesCompatibilityRecommendationInvalid) }
    private static func object(_ value: Any?, keys: Set<String>) throws -> [String:Any] {
        guard let value = value as? [String:Any], Set(value.keys) == keys else { throw issue }; return value
    }
    private static func matches(_ value: String, _ pattern: String) -> Bool { value.range(of: pattern, options: .regularExpression) != nil }
    private static func hash(_ value: String) -> Bool { matches(value, #"\A[a-f0-9]{64}\z"#) }
    private static func uuid(_ value: String) -> Bool { matches(value, #"\A[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}\z"#) }
    private static func base64(_ raw: Any?, maximum: Int) -> Data? {
        guard let raw = raw as? String, raw.count <= maximum * 2, matches(raw, #"\A[A-Za-z0-9_-]+\z"#) else { return nil }
        let standard = raw.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            + String(repeating: "=", count: (4 - raw.count % 4) % 4)
        guard let data = Data(base64Encoded: standard), data.count <= maximum,
              data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") == raw
        else { return nil }; return data
    }
}

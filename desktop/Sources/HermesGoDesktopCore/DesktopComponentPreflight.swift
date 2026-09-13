import Foundation

public enum DesktopManagedComponentKind: String, Codable, CaseIterable, Sendable {
    case pythonRuntime = "python_runtime"
    case nodeRuntime = "node_runtime"
    case hermesCore = "hermes_core"
    case connector
    case browserAutomation = "browser_automation"
    case speechRuntime = "speech_runtime"
    case documentTools = "document_tools"
}

public enum DesktopManagedComponentInstallPhase: String, Codable, Sendable {
    case bootstrap
    case onDemand = "on_demand"
}

/// Exact-content reuse is for mutable runtimes and Python dependency trees. Compatibility reuse is
/// reserved for capabilities with an explicit, signed compatibility contract and a passing probe,
/// such as a supported system browser.
public enum DesktopManagedComponentReusePolicy: Equatable, Sendable {
    case exactContent(sha256: String)
    case verifiedCompatibility(identifier: String)
}

public struct DesktopManagedComponentRequirement: Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let version: String
    public let architecture: String
    public let downloadBytes: Int64
    public let installPhase: DesktopManagedComponentInstallPhase
    public let reusePolicy: DesktopManagedComponentReusePolicy

    public init(
        kind: DesktopManagedComponentKind,
        version: String,
        architecture: String,
        downloadBytes: Int64,
        installPhase: DesktopManagedComponentInstallPhase,
        reusePolicy: DesktopManagedComponentReusePolicy
    ) {
        self.kind = kind
        self.version = version
        self.architecture = architecture
        self.downloadBytes = downloadBytes
        self.installPhase = installPhase
        self.reusePolicy = reusePolicy
    }
}

public enum DesktopManagedComponentCandidateSource: String, Equatable, Sendable {
    case managedStore = "managed_store"
    case external
}

/// A detector may describe an existing component only after a bounded health probe. The planner
/// never executes candidates and never treats a version string alone as proof of safe reuse.
public struct DesktopManagedComponentCandidate: Equatable, Sendable {
    public let kind: DesktopManagedComponentKind
    public let version: String
    public let architecture: String
    public let source: DesktopManagedComponentCandidateSource
    public let contentSHA256: String?
    public let compatibilityIdentifier: String?
    public let healthProbePassed: Bool

    public init(
        kind: DesktopManagedComponentKind,
        version: String,
        architecture: String,
        source: DesktopManagedComponentCandidateSource,
        contentSHA256: String? = nil,
        compatibilityIdentifier: String? = nil,
        healthProbePassed: Bool
    ) {
        self.kind = kind
        self.version = version
        self.architecture = architecture
        self.source = source
        self.contentSHA256 = contentSHA256
        self.compatibilityIdentifier = compatibilityIdentifier
        self.healthProbePassed = healthProbePassed
    }
}

public enum DesktopManagedComponentAction: Equatable, Sendable {
    case reuse(DesktopManagedComponentCandidate)
    case download
    case deferUntilNeeded
}

public struct DesktopManagedComponentDecision: Equatable, Sendable {
    public let requirement: DesktopManagedComponentRequirement
    public let action: DesktopManagedComponentAction
}

public struct DesktopManagedComponentPreflightPlan: Equatable, Sendable {
    public let decisions: [DesktopManagedComponentDecision]

    public var bootstrapDownloadBytes: Int64 {
        decisions.reduce(0) { total, decision in
            guard decision.requirement.installPhase == .bootstrap,
                  decision.action == .download
            else { return total }
            return total + decision.requirement.downloadBytes
        }
    }

    public var deferredDownloadBytes: Int64 {
        decisions.reduce(0) { total, decision in
            guard decision.action == .deferUntilNeeded else { return total }
            return total + decision.requirement.downloadBytes
        }
    }
}

public enum DesktopManagedComponentPreflightError: Error, Equatable, Sendable {
    case duplicateRequirement
    case invalidRequirement
}

public enum DesktopManagedComponentPreflightPlanner {
    public static func plan(
        requirements: [DesktopManagedComponentRequirement],
        candidates: [DesktopManagedComponentCandidate]
    ) throws -> DesktopManagedComponentPreflightPlan {
        guard Set(requirements.map(\.kind)).count == requirements.count else {
            throw DesktopManagedComponentPreflightError.duplicateRequirement
        }
        guard requirements.allSatisfy(valid) else {
            throw DesktopManagedComponentPreflightError.invalidRequirement
        }

        let decisions = requirements.map { requirement in
            let match = candidates
                .filter { matches($0, requirement: requirement) }
                .sorted { lhs, rhs in
                    lhs.source == .managedStore && rhs.source != .managedStore
                }
                .first
            let action: DesktopManagedComponentAction
            if let match {
                action = .reuse(match)
            } else if requirement.installPhase == .onDemand {
                action = .deferUntilNeeded
            } else {
                action = .download
            }
            return DesktopManagedComponentDecision(requirement: requirement, action: action)
        }
        return DesktopManagedComponentPreflightPlan(decisions: decisions)
    }

    private static func matches(
        _ candidate: DesktopManagedComponentCandidate,
        requirement: DesktopManagedComponentRequirement
    ) -> Bool {
        guard candidate.kind == requirement.kind,
              candidate.healthProbePassed,
              candidate.architecture == requirement.architecture
                || candidate.architecture == "universal"
        else { return false }

        switch requirement.reusePolicy {
        case .exactContent(let sha256):
            return candidate.version == requirement.version
                && validSHA256(sha256)
                && candidate.contentSHA256 == sha256
        case .verifiedCompatibility(let identifier):
            return validCompatibilityIdentifier(identifier)
                && candidate.compatibilityIdentifier == identifier
        }
    }

    private static func valid(_ requirement: DesktopManagedComponentRequirement) -> Bool {
        guard requirement.version.range(
            of: "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$",
            options: .regularExpression
        ) != nil,
              ["arm64", "x86_64", "universal"].contains(requirement.architecture),
              requirement.downloadBytes > 0
        else { return false }
        switch requirement.reusePolicy {
        case .exactContent(let sha256):
            return validSHA256(sha256)
        case .verifiedCompatibility(let identifier):
            return validCompatibilityIdentifier(identifier)
        }
    }

    private static func validSHA256(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
    }

    private static func validCompatibilityIdentifier(_ value: String) -> Bool {
        guard (1...96).contains(value.utf8.count) else { return false }
        let allowed = CharacterSet(
            charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._"
        )
        return value.unicodeScalars.allSatisfy(allowed.contains)
    }
}

import Foundation

public enum HealthLevel: String, Codable, CaseIterable, Sendable {
    case checking
    case healthy
    case degraded
    case failed
    case unavailable
}

public enum HealthComponent: String, Codable, CaseIterable, Identifiable, Sendable {
    case desktopAgent
    case gateway
    case hermes
    case observer
    case endToEnd

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .desktopAgent: "后台 Agent"
        case .gateway: "Gateway"
        case .hermes: "本机 Hermes"
        case .observer: "只读观察"
        case .endToEnd: "端到端"
        }
    }

    public var isRequired: Bool {
        switch self {
        case .desktopAgent, .gateway, .hermes: true
        case .observer, .endToEnd: false
        }
    }
}

public struct ComponentHealth: Identifiable, Equatable, Sendable {
    public let component: HealthComponent
    public let level: HealthLevel
    public let detail: String
    public let checkedAt: Date?
    public let issue: DesktopIssue?

    public var id: String { component.id }

    public init(
        component: HealthComponent,
        level: HealthLevel,
        detail: String,
        checkedAt: Date? = nil,
        issue: DesktopIssue? = nil
    ) {
        self.component = component
        self.level = level
        self.detail = detail
        self.checkedAt = checkedAt
        self.issue = issue
    }
}

public enum OverallHealth: String, Equatable, Sendable {
    case checking
    case healthy
    case degraded
    case needsAttention
}

public struct DesktopHealthSnapshot: Equatable, Sendable {
    public let components: [ComponentHealth]
    public let checkedAt: Date

    public init(components: [ComponentHealth], checkedAt: Date = Date()) {
        self.components = components
        self.checkedAt = checkedAt
    }

    public var overall: OverallHealth {
        let required = components.filter(\.component.isRequired)

        if required.contains(where: { $0.level == .failed || $0.level == .unavailable }) {
            return .needsAttention
        }
        if required.contains(where: { $0.level == .checking }) || required.count < 3 {
            return .checking
        }
        if components.contains(where: { $0.level == .degraded || (!$0.component.isRequired && $0.level == .failed) }) {
            return .degraded
        }
        return required.allSatisfy { $0.level == .healthy } ? .healthy : .checking
    }

    public func component(_ component: HealthComponent) -> ComponentHealth {
        components.first(where: { $0.component == component })
            ?? ComponentHealth(component: component, level: .checking, detail: "正在检查")
    }

    public static let checking = DesktopHealthSnapshot(
        components: HealthComponent.allCases.map {
            ComponentHealth(component: $0, level: .checking, detail: "正在检查")
        }
    )
}

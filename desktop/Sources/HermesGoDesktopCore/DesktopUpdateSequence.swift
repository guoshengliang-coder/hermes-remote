/// One Desktop update may contain background changes and an app replacement. Never quit the app
/// until the background transaction has succeeded, including temporary-workspace cleanup.
public struct DesktopUpdateSequence: Equatable, Sendable {
    public enum Stage: Equatable, Sendable { case idle, background, app, completed, stopped }
    public private(set) var stage: Stage = .idle
    private var includesApp = false
    public init() {}
    public var isRunning: Bool { stage == .background || stage == .app }
    public mutating func begin(background: Bool, app: Bool) {
        guard !isRunning else { return }
        includesApp = app
        stage = background ? .background : (app ? .app : .completed)
    }
    public mutating func backgroundFinished(succeeded: Bool, cleanupPending: Bool) {
        guard stage == .background else { return }
        stage = succeeded && !cleanupPending ? (includesApp ? .app : .completed) : .stopped
    }
    public mutating func stop() { stage = .stopped }
}

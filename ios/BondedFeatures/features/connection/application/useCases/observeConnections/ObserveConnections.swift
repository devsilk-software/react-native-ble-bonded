/// Every known device's current connection, remembered devices included, then every change.
public struct ObserveConnections: Sendable {
  private let coordinator: ConnectionCoordinator

  public init(coordinator: ConnectionCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction() async -> AsyncStream<ConnectionChange> {
    await coordinator.changes()
  }
}

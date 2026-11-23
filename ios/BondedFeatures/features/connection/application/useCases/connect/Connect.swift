/// Connects and discovers the device's services; returns once it's ready. Throws a `BleError`.
public struct Connect: Sendable {
  private let coordinator: ConnectionCoordinator

  public init(coordinator: ConnectionCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ id: DeviceId, options: ConnectOptions) async throws {
    try await coordinator.connect(id, options: options)
  }
}

/// The services discovered when the device connected. Throws `notConnected` while it isn't connected.
public struct GetServices: Sendable {
  private let coordinator: ConnectionCoordinator

  public init(coordinator: ConnectionCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ id: DeviceId) async throws -> [GattService] {
    try await coordinator.services(of: id)
  }
}

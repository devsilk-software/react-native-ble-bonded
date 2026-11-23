/// Disconnects a device, or cancels its connection attempt; returns once it's disconnected.
public struct DisconnectDevice: Sendable {
  private let coordinator: ConnectionCoordinator

  public init(coordinator: ConnectionCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ id: DeviceId) async {
    await coordinator.disconnect(id)
  }
}

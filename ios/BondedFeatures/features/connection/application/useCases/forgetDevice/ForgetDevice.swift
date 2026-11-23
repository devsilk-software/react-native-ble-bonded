/// Disconnects a device and forgets it; the system's pairing stays.
public struct ForgetDevice: Sendable {
  private let coordinator: ConnectionCoordinator

  public init(coordinator: ConnectionCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ id: DeviceId) async {
    await coordinator.forget(id)
  }
}

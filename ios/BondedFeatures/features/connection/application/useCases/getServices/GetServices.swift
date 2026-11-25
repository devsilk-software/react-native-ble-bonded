/// The services discovered when the device connected, with each characteristic's descriptors when `descriptors` is
/// true. Throws `notConnected` while it isn't connected.
public struct GetServices: Sendable {
  private let coordinator: ConnectionCoordinator

  public init(coordinator: ConnectionCoordinator) {
    self.coordinator = coordinator
  }

  public func callAsFunction(_ id: DeviceId, descriptors: Bool = false) async throws -> [GattService] {
    try await coordinator.services(of: id, descriptors: descriptors)
  }
}

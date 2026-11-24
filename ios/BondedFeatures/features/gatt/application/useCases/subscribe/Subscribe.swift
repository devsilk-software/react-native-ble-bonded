/// Subscribes to a characteristic's notifications; returns once the device switched them on.
public struct Subscribe: Sendable {
  private let registry: SubscriptionRegistry

  public init(registry: SubscriptionRegistry) {
    self.registry = registry
  }

  public func callAsFunction(to address: CharacteristicAddress, on id: DeviceId) async throws -> (id: Int, events: AsyncStream<SubscriptionEvent>) {
    try await registry.subscribe(to: address, on: id)
  }
}

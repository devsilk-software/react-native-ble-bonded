/// Removes a subscription and switches the device's notifications off when it's connected.
public struct Unsubscribe: Sendable {
  private let registry: SubscriptionRegistry

  public init(registry: SubscriptionRegistry) {
    self.registry = registry
  }

  public func callAsFunction(_ subscription: Int) async {
    await registry.remove(subscription)
  }
}

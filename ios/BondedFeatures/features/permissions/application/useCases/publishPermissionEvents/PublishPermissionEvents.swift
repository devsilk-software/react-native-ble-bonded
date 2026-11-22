/// Publishes every permission change on the event bus, for whoever listens (diagnostics, for one). Runs until
/// its task is cancelled.
public struct PublishPermissionEvents: Sendable {
  private let observe: ObservePermission
  private let bus: EventBus

  public init(observe: ObservePermission, bus: EventBus) {
    self.observe = observe
    self.bus = bus
  }

  public func callAsFunction() async {
    for await status in observe() {
      await bus.publish(DomainEvent(kind: .permissionChanged, detail: status.name))
    }
  }
}

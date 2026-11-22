/// Publishes every Bluetooth state change on the event bus, for whoever listens (diagnostics, for one). Runs
/// until its task is cancelled.
public struct PublishAdapterStateEvents: Sendable {
  private let observe: ObserveAdapterState
  private let bus: EventBus

  public init(observe: ObserveAdapterState, bus: EventBus) {
    self.observe = observe
    self.bus = bus
  }

  public func callAsFunction() async {
    for await state in observe() {
      await bus.publish(DomainEvent(kind: .stateChanged, detail: state.name))
    }
  }
}

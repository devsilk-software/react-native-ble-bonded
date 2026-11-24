import Foundation

/// The in-process bus features publish domain events on (decision A2). The composition root creates one;
/// a feature that reacts subscribes with `events()`. Publishing with nobody subscribed costs almost nothing.
public actor EventBus {
  private var subscribers: [UUID: AsyncStream<DomainEvent>.Continuation] = [:]

  public init() {}

  public func publish(_ event: DomainEvent) {
    for subscriber in subscribers.values {
      subscriber.yield(event)
    }
  }

  /// Every event published from the moment this returns: the subscription is registered before it does.
  public func events() -> AsyncStream<DomainEvent> {
    let (stream, continuation) = AsyncStream<DomainEvent>.makeStream()
    let id = UUID()
    continuation.onTermination = { _ in
      Task { await self.removeSubscriber(id) }
    }
    subscribers[id] = continuation
    return stream
  }

  private func removeSubscriber(_ id: UUID) {
    subscribers[id] = nil
  }
}

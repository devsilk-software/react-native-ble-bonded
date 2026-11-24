import Foundation

/// The app's subscriptions: one per characteristic, kept until removed, across disconnects. Switches notifications
/// back on after each reconnect, which the `connection` feature announces on the event bus, and drops a device's
/// subscriptions when the app forgets it.
public actor SubscriptionRegistry {
  private let source: any CharacteristicSource
  private let bus: EventBus
  private var nextId = 1
  private var entries: [Int: SubscriptionEntry] = [:]
  private var isListening = false

  private struct SubscriptionEntry {
    let device: DeviceId
    let address: CharacteristicAddress
    let isIndication: Bool
    let continuation: AsyncStream<SubscriptionEvent>.Continuation
  }

  public init(source: any CharacteristicSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  /// Subscribes and returns once the device switched notifications on: the subscription's ID and what it reports.
  /// Throws `alreadySubscribed` when the characteristic has one, or the usual checks' errors.
  public func subscribe(to address: CharacteristicAddress, on id: DeviceId) async throws -> (id: Int, events: AsyncStream<SubscriptionEvent>) {
    await listen()
    if entries.values.contains(where: { $0.device == id && $0.address == address }) {
      throw Self.alreadySubscribed(address, id)
    }
    let properties = try await source.properties(of: address, on: id)
    if let failure = OperationCheck.failure(of: .subscribe, on: address, of: id, properties: properties) {
      throw failure
    }
    let isIndication = properties?.contains(.notify) != true
    try await source.setNotifications(true, indicate: isIndication, on: address, of: id)
    // Another call may have subscribed while this one waited for the device.
    if entries.values.contains(where: { $0.device == id && $0.address == address }) {
      throw Self.alreadySubscribed(address, id)
    }
    let (events, continuation) = AsyncStream<SubscriptionEvent>.makeStream()
    let subscription = nextId
    nextId += 1
    entries[subscription] = SubscriptionEntry(device: id, address: address, isIndication: isIndication, continuation: continuation)
    return (subscription, events)
  }

  /// Removes the subscription and switches notifications off when the device is connected.
  public func remove(_ subscription: Int) async {
    guard let entry = entries.removeValue(forKey: subscription) else { return }
    entry.continuation.finish()
    try? await source.setNotifications(false, indicate: entry.isIndication, on: entry.address, of: entry.device)
  }

  private func listen() async {
    guard !isListening else { return }
    isListening = true
    let notifications = await source.notifications()
    let events = await bus.events()
    Task { [weak self] in
      for await notification in notifications {
        await self?.deliver(notification)
      }
    }
    Task { [weak self] in
      for await event in events {
        await self?.handle(event)
      }
    }
  }

  private func deliver(_ notification: CharacteristicNotification) {
    for entry in entries.values where entry.device == notification.id && entry.address == notification.address {
      entry.continuation.yield(.value(notification.value))
    }
  }

  private func handle(_ event: DomainEvent) async {
    guard let device = event.deviceId else { return }
    switch event.kind {
    case .connected:
      for (subscription, entry) in entries where entry.device == device {
        do {
          try await source.setNotifications(true, indicate: entry.isIndication, on: entry.address, of: device)
        } catch {
          let failure = error as? BleError
            ?? BleError(code: .unknown, message: String(describing: error), deviceId: device, operation: "subscribe")
          entries[subscription]?.continuation.yield(.failed(failure))
        }
      }
    case .forgotten:
      for (subscription, entry) in entries where entry.device == device {
        entries[subscription] = nil
        entry.continuation.finish()
      }
    default:
      break
    }
  }

  private static func alreadySubscribed(_ address: CharacteristicAddress, _ id: DeviceId) -> BleError {
    BleError(
      code: .alreadySubscribed,
      message: "Characteristic \(address.characteristic) already has a subscription. Remove it first, or share its values in your app.",
      deviceId: id,
      operation: "subscribe"
    )
  }
}

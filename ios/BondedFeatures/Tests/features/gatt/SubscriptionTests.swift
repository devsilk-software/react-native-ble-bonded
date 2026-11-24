import Testing
@testable import BondedFeatures

@Suite("Subscriptions")
struct SubscriptionTests {
  private let device = DeviceId("A")

  private func source() -> FakeCharacteristicSource {
    FakeCharacteristicSource(characteristics: [
      testCharacteristic: [.read, .notify, .indicate],
      readOnlyCharacteristic: [.read],
      indicateOnlyCharacteristic: [.indicate],
    ])
  }

  @Test("subscribes and delivers each notification")
  func subscribesAndDeliversEachNotification() async throws {
    let source = source()
    let registry = SubscriptionRegistry(source: source, bus: EventBus())

    let subscription = try await registry.subscribe(to: testCharacteristic, on: device)
    let recorder = SubscriptionRecorder(subscription.events)
    await source.notify([1], at: testCharacteristic, on: device)
    await source.notify([2], at: testCharacteristic, on: device)
    await source.notify([9], at: readOnlyCharacteristic, on: device)
    await eventually { await recorder.events.count == 2 }

    #expect(await recorder.events == [.value([1]), .value([2])])
  }

  @Test("refuses a second subscription to the same characteristic")
  func refusesASecondSubscriptionToTheSameCharacteristic() async throws {
    let registry = SubscriptionRegistry(source: source(), bus: EventBus())
    _ = try await registry.subscribe(to: testCharacteristic, on: device)

    await #expect { try await registry.subscribe(to: testCharacteristic, on: device) } throws: { error in
      guard let error = error as? BleError else { return false }
      return error.code == .alreadySubscribed && error.message.contains("Remove it first")
    }
  }

  @Test("refuses to subscribe to a characteristic that neither notifies nor indicates")
  func refusesToSubscribeToACharacteristicThatNeitherNotifiesNorIndicates() async {
    let registry = SubscriptionRegistry(source: source(), bus: EventBus())

    await #expect { try await registry.subscribe(to: readOnlyCharacteristic, on: device) } throws: {
      ($0 as? BleError)?.code == .unsupported
    }
  }

  @Test("uses notifications when it can and indications otherwise")
  func usesNotificationsWhenItCanAndIndicationsOtherwise() async throws {
    let source = source()
    let registry = SubscriptionRegistry(source: source, bus: EventBus())

    _ = try await registry.subscribe(to: testCharacteristic, on: device)
    _ = try await registry.subscribe(to: indicateOnlyCharacteristic, on: device)

    #expect(await source.notificationSwitches.map(\.indicate) == [false, true])
  }

  @Test("switches notifications back on after a reconnect")
  func switchesNotificationsBackOnAfterAReconnect() async throws {
    let source = source()
    let bus = EventBus()
    let registry = SubscriptionRegistry(source: source, bus: bus)
    _ = try await registry.subscribe(to: testCharacteristic, on: device)

    await bus.publish(DomainEvent(kind: .connected, deviceId: device))
    await eventually { await source.notificationSwitches.count == 2 }

    #expect(await source.notificationSwitches.map(\.isOn) == [true, true])
  }

  @Test("reports when notifications can't be switched back on")
  func reportsWhenNotificationsCantBeSwitchedBackOn() async throws {
    let source = source()
    let bus = EventBus()
    let registry = SubscriptionRegistry(source: source, bus: bus)
    let subscription = try await registry.subscribe(to: testCharacteristic, on: device)
    let recorder = SubscriptionRecorder(subscription.events)
    let refusal = BleError(code: .pairingRequired, message: "needs pairing")

    await source.failSwitchingOn(with: refusal)
    await bus.publish(DomainEvent(kind: .connected, deviceId: device))
    await eventually { await recorder.events.count == 1 }
    await source.failSwitchingOn(with: nil)
    await source.notify([3], at: testCharacteristic, on: device)
    await eventually { await recorder.events.count == 2 }

    #expect(await recorder.events == [.failed(refusal), .value([3])])
  }

  @Test("stops notifications when the subscription is removed")
  func stopsNotificationsWhenTheSubscriptionIsRemoved() async throws {
    let source = source()
    let registry = SubscriptionRegistry(source: source, bus: EventBus())
    let subscription = try await registry.subscribe(to: testCharacteristic, on: device)
    let recorder = SubscriptionRecorder(subscription.events)

    await registry.remove(subscription.id)
    await eventually { await recorder.isFinished }

    #expect(await source.notificationSwitches.map(\.isOn) == [true, false])
    _ = try await registry.subscribe(to: testCharacteristic, on: device)
  }

  @Test("removes a forgotten device's subscriptions")
  func removesAForgottenDevicesSubscriptions() async throws {
    let source = source()
    let bus = EventBus()
    let registry = SubscriptionRegistry(source: source, bus: bus)
    let subscription = try await registry.subscribe(to: testCharacteristic, on: device)
    let recorder = SubscriptionRecorder(subscription.events)

    await bus.publish(DomainEvent(kind: .forgotten, deviceId: device))
    await eventually { await recorder.isFinished }

    #expect(await recorder.events.isEmpty)
    _ = try await registry.subscribe(to: testCharacteristic, on: device)
  }
}

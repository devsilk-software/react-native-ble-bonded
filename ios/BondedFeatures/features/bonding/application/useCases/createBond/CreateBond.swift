/// Pairs with the device by reading the trigger, a characteristic that needs encryption: iOS has no call to pair, and
/// shows its dialog when the device asks for encryption. Settles on the outcome, with no timeout of its own beyond
/// Bluetooth's 30 seconds. Resolves at once when already paired, since the read simply succeeds.
public struct CreateBond: Sendable {
  private let source: any BondSource
  private let bus: EventBus

  public init(source: any BondSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  public func callAsFunction(_ trigger: BondTrigger, on id: DeviceId) async throws {
    guard let isReadable = try await source.isReadable(trigger, on: id) else {
      throw BleError(
        code: .characteristicNotFound,
        message: "The device has no characteristic \(trigger.characteristic) in service \(trigger.service) to start pairing with. device.getServices() lists the ones it has.",
        deviceId: id,
        operation: "createBond"
      )
    }
    guard isReadable else {
      throw BleError(
        code: .unsupported,
        message: "Characteristic \(trigger.characteristic) can't be read, so reading it can't start pairing. Give one that can be read and needs encryption.",
        deviceId: id,
        operation: "createBond"
      )
    }
    do {
      try await source.readTrigger(trigger, on: id)
    } catch let error as BleError {
      await bus.publish(DomainEvent(kind: .bondStateChanged, deviceId: id, detail: "none", errorCode: error.code, platformCode: error.platformCode))
      throw error
    }
    await bus.publish(DomainEvent(kind: .bondStateChanged, deviceId: id, detail: "bonded"))
  }
}

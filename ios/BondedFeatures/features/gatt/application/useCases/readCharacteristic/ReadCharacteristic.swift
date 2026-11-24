/// Reads a characteristic's value, after checking the device has it and it allows reading. Gives up after the
/// timeout. Publishes the read for diagnostics, which records it only when asked to record GATT operations.
public struct ReadCharacteristic: Sendable {
  private let source: any CharacteristicSource
  private let bus: EventBus

  public init(source: any CharacteristicSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  public func callAsFunction(_ address: CharacteristicAddress, on id: DeviceId, timeout: Duration = .seconds(10)) async throws -> [UInt8] {
    let started = ContinuousClock.now
    do {
      let properties = try await source.properties(of: address, on: id)
      if let failure = OperationCheck.failure(of: .read, on: address, of: id, properties: properties) {
        throw failure
      }
      let expired = BleError(
        code: .timeout,
        message: "The device didn't answer the read of characteristic \(address.characteristic) within \(timeout)",
        deviceId: id,
        operation: "read"
      )
      let value = try await withOperationTimeout(timeout, timeout: expired) { try await source.read(address, on: id) }
      let elapsed = (ContinuousClock.now - started).wholeMilliseconds
      await bus.publish(DomainEvent(kind: .gattOperation, deviceId: id, detail: "read \(address.characteristic): \(value.count) bytes in \(elapsed) ms"))
      return value
    } catch let error as BleError {
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "read \(address.characteristic) failed",
        errorCode: error.code,
        platformCode: error.platformCode
      ))
      throw error
    }
  }
}

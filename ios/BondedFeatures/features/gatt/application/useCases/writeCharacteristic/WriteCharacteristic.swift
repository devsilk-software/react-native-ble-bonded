/// Writes a value, after checking the device has the characteristic, it allows this kind of write and the value
/// fits. Without response it never splits a value. Gives up after the timeout. Publishes the write for diagnostics,
/// which records it only when asked to record GATT operations.
public struct WriteCharacteristic: Sendable {
  private let source: any CharacteristicSource
  private let bus: EventBus

  public init(source: any CharacteristicSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  public func callAsFunction(
    _ value: [UInt8],
    to address: CharacteristicAddress,
    on id: DeviceId,
    withResponse: Bool,
    timeout: Duration = .seconds(10)
  ) async throws {
    let operation: CharacteristicOperation = withResponse ? .write : .writeWithoutResponse
    let started = ContinuousClock.now
    do {
      let properties = try await source.properties(of: address, on: id)
      let maxWriteLength = withResponse ? maxAttributeLength : try await source.maxWriteLength(on: id)
      if let failure = OperationCheck.failure(
        of: operation,
        on: address,
        of: id,
        properties: properties,
        length: value.count,
        maxWriteLength: maxWriteLength
      ) {
        throw failure
      }
      let expired = BleError(
        code: .timeout,
        message: "The device didn't take the \(operation.name) of characteristic \(address.characteristic) within \(timeout)",
        deviceId: id,
        operation: operation.name
      )
      try await withOperationTimeout(timeout, timeout: expired) {
        try await source.write(value, to: address, on: id, withResponse: withResponse)
      }
      let elapsed = (ContinuousClock.now - started).wholeMilliseconds
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "\(operation.name) \(address.characteristic): \(value.count) bytes in \(elapsed) ms"
      ))
    } catch let error as BleError {
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "\(operation.name) \(address.characteristic) failed",
        errorCode: error.code,
        platformCode: error.platformCode
      ))
      throw error
    }
  }
}

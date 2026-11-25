/// Writes a descriptor's value, after checking the device has the characteristic and the descriptor, the descriptor
/// isn't the notification descriptor Bonded manages, and the value fits. Gives up after the timeout. Publishes the
/// write for diagnostics, which records it only when asked to record GATT operations.
public struct WriteDescriptor: Sendable {
  private let source: any CharacteristicSource
  private let bus: EventBus

  public init(source: any CharacteristicSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  public func callAsFunction(
    _ value: [UInt8],
    to descriptor: DescriptorUuid,
    of address: CharacteristicAddress,
    on id: DeviceId,
    timeout: Duration = .seconds(10)
  ) async throws {
    let started = ContinuousClock.now
    do {
      let expired = BleError(
        code: .timeout,
        message: "The device didn't confirm the write of descriptor \(descriptor) within \(timeout)",
        deviceId: id,
        operation: CharacteristicOperation.writeDescriptor.name
      )
      // Finding the descriptors waits its turn on the device too, so the timeout covers it.
      try await withOperationTimeout(timeout, timeout: expired) {
        let descriptors = try await source.descriptors(of: address, on: id, for: .writeDescriptor)
        if let failure = DescriptorCheck.failure(
          of: .writeDescriptor,
          descriptor,
          of: address,
          on: id,
          descriptors: descriptors,
          length: value.count
        ) {
          throw failure
        }
        try await source.writeDescriptor(value, to: descriptor, of: address, on: id)
      }
      let elapsed = (ContinuousClock.now - started).wholeMilliseconds
      await bus.publish(DomainEvent(kind: .gattOperation, deviceId: id, detail: "descriptor write \(descriptor): \(value.count) bytes in \(elapsed) ms"))
    } catch let error as BleError {
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "descriptor write \(descriptor) failed",
        errorCode: error.code,
        platformCode: error.platformCode
      ))
      throw error
    }
  }
}

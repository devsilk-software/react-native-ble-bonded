/// Reads a descriptor's value, after checking the device has the characteristic and the descriptor. Gives up after
/// the timeout. Publishes the read for diagnostics, which records it only when asked to record GATT operations.
public struct ReadDescriptor: Sendable {
  private let source: any CharacteristicSource
  private let bus: EventBus

  public init(source: any CharacteristicSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  public func callAsFunction(
    _ descriptor: DescriptorUuid,
    of address: CharacteristicAddress,
    on id: DeviceId,
    timeout: Duration = .seconds(10)
  ) async throws -> [UInt8] {
    let started = ContinuousClock.now
    do {
      let expired = BleError(
        code: .timeout,
        message: "The device didn't answer the read of descriptor \(descriptor) within \(timeout)",
        deviceId: id,
        operation: CharacteristicOperation.readDescriptor.name
      )
      // Finding the descriptors waits its turn on the device too, so the timeout covers it.
      let value = try await withOperationTimeout(timeout, timeout: expired) {
        let descriptors = try await source.descriptors(of: address, on: id, for: .readDescriptor)
        if let failure = DescriptorCheck.failure(of: .readDescriptor, descriptor, of: address, on: id, descriptors: descriptors) {
          throw failure
        }
        return try await source.readDescriptor(descriptor, of: address, on: id)
      }
      let elapsed = (ContinuousClock.now - started).wholeMilliseconds
      await bus.publish(DomainEvent(kind: .gattOperation, deviceId: id, detail: "descriptor read \(descriptor): \(value.count) bytes in \(elapsed) ms"))
      return value
    } catch let error as BleError {
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "descriptor read \(descriptor) failed",
        errorCode: error.code,
        platformCode: error.platformCode
      ))
      throw error
    }
  }
}

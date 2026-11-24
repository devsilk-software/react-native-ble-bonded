/// Writes data longer than one write carries: cuts it to the device's maximum write length and sends the chunks in
/// order as one operation, so nothing else on the device runs between them. Stops at the first chunk that fails and
/// says which one and how many bytes the device took before it. Each chunk gets the timeout. Publishes the write for
/// diagnostics, which records it only when asked to record GATT operations.
public struct WriteChunked: Sendable {
  private let source: any CharacteristicSource
  private let bus: EventBus

  public init(source: any CharacteristicSource, bus: EventBus) {
    self.source = source
    self.bus = bus
  }

  public func callAsFunction(
    _ data: [UInt8],
    to address: CharacteristicAddress,
    on id: DeviceId,
    withResponse: Bool,
    timeout: Duration = .seconds(10)
  ) async throws {
    let operation: CharacteristicOperation = withResponse ? .write : .writeWithoutResponse
    let started = ContinuousClock.now
    do {
      let properties = try await source.properties(of: address, on: id)
      if let failure = OperationCheck.failure(of: operation, on: address, of: id, properties: properties) {
        throw failure
      }
      guard !data.isEmpty else { return }
      let size = try await source.maxWriteLength(on: id)
      let chunks = Chunks.split(data, size: size)
      do {
        try await source.write(pieces: chunks, to: address, on: id, withResponse: withResponse, timeout: timeout)
      } catch let failure as PieceFailure {
        throw Chunks.failure(failure.error, index: failure.index, count: chunks.count, size: size, id: id)
      }
      let elapsed = (ContinuousClock.now - started).wholeMilliseconds
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "chunked \(operation.name) \(address.characteristic): \(data.count) bytes in \(chunks.count) chunks in \(elapsed) ms"
      ))
    } catch let error as BleError {
      await bus.publish(DomainEvent(
        kind: .gattOperation,
        deviceId: id,
        detail: "chunked \(operation.name) \(address.characteristic) failed",
        errorCode: error.code,
        platformCode: error.platformCode
      ))
      throw error
    }
  }
}

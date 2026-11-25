/// Reads the connected device's signal strength in dBm. Gives up after the timeout.
public struct ReadRssi: Sendable {
  private let source: any CharacteristicSource

  public init(source: any CharacteristicSource) {
    self.source = source
  }

  public func callAsFunction(on id: DeviceId, timeout: Duration = .seconds(10)) async throws -> Int {
    let expired = BleError(
      code: .timeout,
      message: "The device's signal strength didn't come within \(timeout)",
      deviceId: id,
      operation: "readRssi"
    )
    return try await withOperationTimeout(timeout, timeout: expired) { try await source.readRssi(on: id) }
  }
}
